package com.example.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import java.math.BigDecimal
import java.security.MessageDigest

@Serializable
internal data class WatchRule(
    val id:String, val symbol:String, val type:String, val low:String, val high:String,
    val interval:String, val strategy:String, val checklist:List<String>, val checked:List<Int> = emptyList(),
    val active:Boolean=true, val hitAt:Long=0, val lastPrice:String="", val checkedAt:Long=0, val priceType:String="LAST_PRICE"
)
internal fun WatchRule.isLivePriceRule() = type in setOf("ABOVE", "BELOW", "ZONE")

internal object WatchStore {
    private val lock=Any()
    private fun prefs(c:Context)=c.getSharedPreferences("market_watch_v1",0)
    private val json=Json{ignoreUnknownKeys=true}
    fun read(c:Context):List<WatchRule> = synchronized(lock) { runCatching{json.decodeFromString<List<WatchRule>>(prefs(c).getString("rules","[]")?:"[]")}.getOrDefault(emptyList()) }
    fun edit(c:Context,action:(List<WatchRule>)->List<WatchRule>)=synchronized(lock){prefs(c).edit().putString("rules",json.encodeToString(action(read(c)))).commit()}
    fun add(c:Context,rule:WatchRule):Boolean = synchronized(lock) {
        val current=read(c)
        current.size<30 && edit(c){it+rule}
    }
    fun enabled(c:Context)=prefs(c).getBoolean("enabled",false)
    fun activeRules(c: Context): List<WatchRule> =
        if (enabled(c)) read(c).filter { it.active } else emptyList()

    fun ruleChanges(c: Context) = callbackFlow {
        val prefs = prefs(c)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "rules" || key == "enabled" || key == null) trySend(activeRules(c))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(activeRules(c))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.buffer(Channel.CONFLATED)

    fun enabledSince(c:Context)=prefs(c).getLong("since",0)
    fun status(c:Context)=prefs(c).getString("status","هنوز بررسی نشده")?:""
    fun status(c:Context,s:String){prefs(c).edit().putString("status",s).apply()}
    fun setEnabled(c:Context,on:Boolean):Boolean{
        val p=prefs(c)
        if(on && !p.getBoolean("enabled",false))p.edit().putLong("since",System.currentTimeMillis()).apply()
        p.edit().putBoolean("enabled",on).apply()
        val wm=WorkManager.getInstance(c)
        if(on){
            wm.enqueueUniquePeriodicWork("market_watch_periodic",ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<MarketWatchWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            val started = MarketAlertService.startIfNeeded(c)
            checkNow(c)
            return started
        }
        MarketAlertService.stop(c)
        wm.cancelUniqueWork("market_watch_periodic")
        wm.cancelUniqueWork("market_watch_now")
        return false
    }
    fun checkNow(c:Context){
        WorkManager.getInstance(c).enqueueUniqueWork("market_watch_now",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MarketWatchWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun triggerSeen(c:Context,key:String)=prefs(c).getBoolean("trigger_"+key,false)
    fun markTrigger(c:Context,key:String){prefs(c).edit().putBoolean("trigger_"+key,true).apply()}
}
internal object MarketMonitor {
    private const val alertChannel = "market_watch"
    private val priceMutex = Mutex()
    private val tradeMutex = Mutex()

    fun canNotify(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = c.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(alertChannel)
        if (channel != null) return channel.importance != NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(NotificationChannel(alertChannel, "هشدار معاملات و بازار", NotificationManager.IMPORTANCE_HIGH))
        return true
    }

    private fun notify(c:Context,id:String,title:String,text:String):Boolean {
        if (!canNotify(c)) return false
        val nm=c.getSystemService(NotificationManager::class.java)
        val intent=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(alertChannel,id.hashCode(),NotificationCompat.Builder(c,alertChannel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(intent).setAutoCancel(true).build())
        return true
    }
    private fun deliverPrice(c:Context,rule:WatchRule,price:BigDecimal,met:Boolean):Boolean {
        var sent=false
        WatchStore.edit(c){items->items.map{current->
            if(current.id!=rule.id || !current.active || !WatchStore.enabled(c)) current
            else {
                val now=System.currentTimeMillis()
                val text=if(rule.strategy.isBlank()) {
                    val source=if(rule.priceType=="MARK_PRICE") "Mark" else "Last"
                    "$source ${price.stripTrailingZeros().toPlainString()} • سطح ${rule.low} USDT"
                } else "شرط ${rule.strategy} برقرار است"
                sent=met && notify(c,rule.id,"هشدار قیمت ${rule.symbol}",text)
                current.copy(lastPrice=price.stripTrailingZeros().toPlainString(),checkedAt=now,
                    active=!sent,hitAt=if(sent)now else current.hitAt)
            }
        }}
        return sent
    }

    private fun meets(rule:WatchRule,price:BigDecimal):Boolean {
        val level=rule.low.toBigDecimalOrNull() ?: return false
        return when(rule.type){
            "ABOVE","CLOSE_ABOVE" -> price>=level
            "BELOW","CLOSE_BELOW" -> price<=level
            "ZONE" -> rule.high.toBigDecimalOrNull()?.let{price in level..it}==true
            else -> false
        }
    }

    fun observeChartPrice(c:Context,symbol:String,priceType:String,price:BigDecimal) {
        if(!WatchStore.enabled(c))return
        WatchStore.read(c).filter {
            it.active && it.symbol==symbol && it.priceType==priceType && it.isLivePriceRule()
        }.filter{meets(it,price)}.forEach{deliverPrice(c,it,price,true)}
    }

    private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    private data class CheckResult(val failed: Boolean, val hits: Int)

    private suspend fun readPrices(c: Context): CheckResult = priceMutex.withLock {
        val rules = WatchStore.activeRules(c).take(30)
        val symbols = rules.filter { it.isLivePriceRule() }.map { it.symbol }.distinct()
        var failed = false
        var hits = 0
        val quotes = if (symbols.isEmpty()) emptyMap() else try {
            MarketData.quotes(symbols)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
            emptyMap()
        }
        for (rule in rules) {
            if (!WatchStore.enabled(c)) break
            try {
                val price = if (rule.isLivePriceRule()) {
                    val quote = quotes[rule.symbol]
                    if (quote == null) {
                        failed = true
                        continue
                    }
                    if (rule.priceType == "MARK_PRICE") quote.mark else quote.last
                } else {
                    val bar = MarketData.candles(rule.symbol, rule.interval, rule.priceType, limit = 1)
                        .lastOrNull() ?: error("No closed candle")
                    delay(150)
                    BigDecimal.valueOf(bar.close)
                }
                if (deliverPrice(c, rule, price, meets(rule, price))) hits++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            }
        }
        CheckResult(failed, hits)
    }

    private fun reportCheck(c: Context, failed: Boolean, hits: Int) {
        val timestamp = java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
        val status = if (failed) "بخشی از بررسی ناموفق بود" else "بررسی انجام شد"
        WatchStore.status(c, "$status • $timestamp • اعلان: $hits")
    }

    suspend fun checkPrices(c: Context): Boolean {
        val result = readPrices(c)
        reportCheck(c, result.failed, result.hits)
        return !result.failed
    }

    suspend fun check(c: Context, includePrices: Boolean = true): Boolean = tradeMutex.withLock {
        if (!WatchStore.enabled(c)) return@withLock true
        val result = if (includePrices) readPrices(c) else CheckResult(false, 0)
        var failed = result.failed
        var hits = result.hits
        try{
            val credentials=withContext(Dispatchers.IO){BitunixVault(c).load()}
            if(credentials!=null && WatchStore.enabled(c)){
                val tag=hash(credentials.apiKey)
                val notices=BitunixClient.triggeredOrders(credentials,WatchStore.enabledSince(c))
                for(n in notices){
                    if(!WatchStore.enabled(c))break
                    val key=hash(tag+":"+n.id+":"+n.time)
                    if(!WatchStore.triggerSeen(c,key) && notify(c,key,"تریگر TP/SL در "+n.symbol,"صرافی فعال شدن تریگر را گزارش کرده است؛ اجرای نهایی سفارش خروج را بررسی کنید.")){WatchStore.markTrigger(c,key);hits++}
                }
            }
        }catch(e:CancellationException){throw e}catch(_:Exception){failed=true}
        if (includePrices || hits > 0 || failed) reportCheck(c, failed, hits)
        !failed
    }
}
class MarketWatchWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=try{if(MarketMonitor.check(applicationContext)) Result.success() else Result.retry()}catch(e:CancellationException){throw e}catch(_:Exception){Result.retry()}
}
