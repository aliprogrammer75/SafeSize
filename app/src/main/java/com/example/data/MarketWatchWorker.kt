package com.example.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.MainActivity
import kotlinx.coroutines.*
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
    fun enabledSince(c:Context)=prefs(c).getLong("since",0)
    fun status(c:Context)=prefs(c).getString("status","هنوز بررسی نشده")?:""
    fun status(c:Context,s:String){prefs(c).edit().putString("status",s).apply()}
    fun setEnabled(c:Context,on:Boolean){
        val p=prefs(c)
        if(on && !p.getBoolean("enabled",false))p.edit().putLong("since",System.currentTimeMillis()).apply()
        p.edit().putBoolean("enabled",on).apply()
        val wm=WorkManager.getInstance(c)
        if(on){
            wm.enqueueUniquePeriodicWork("market_watch_periodic",ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<MarketWatchWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            checkNow(c)
        }else{wm.cancelUniqueWork("market_watch_periodic");wm.cancelUniqueWork("market_watch_now")}
    }
    fun checkNow(c:Context){
        WorkManager.getInstance(c).enqueueUniqueWork("market_watch_now",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MarketWatchWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun triggerSeen(c:Context,key:String)=prefs(c).getBoolean("trigger_"+key,false)
    fun markTrigger(c:Context,key:String){prefs(c).edit().putBoolean("trigger_"+key,true).apply()}
}
internal object MarketMonitor {
    private val mutex=Mutex()
    private fun notify(c:Context,id:String,title:String,text:String):Boolean {
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false
        val nm=c.getSystemService(NotificationManager::class.java)
        if(!nm.areNotificationsEnabled())return false
        nm.createNotificationChannel(NotificationChannel("market_watch","هشدار معاملات و بازار",NotificationManager.IMPORTANCE_HIGH))
        if(nm.getNotificationChannel("market_watch").importance==NotificationManager.IMPORTANCE_NONE)return false
        val intent=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(id.hashCode(),NotificationCompat.Builder(c,"market_watch").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
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
            it.active && it.symbol==symbol && it.priceType==priceType && it.type in setOf("ABOVE","BELOW","ZONE")
        }.filter{meets(it,price)}.forEach{deliverPrice(c,it,price,true)}
    }

    private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    suspend fun check(c:Context):Boolean=mutex.withLock {
        if(!WatchStore.enabled(c))return@withLock true
        val rules=WatchStore.read(c).filter{it.active}.take(30)
        var failed=false
        var hits=0
        val quotes=if(rules.isEmpty())emptyMap() else try{MarketData.quotes(rules.map{it.symbol}.distinct())}catch(e:CancellationException){throw e}catch(_:Exception){failed=true;emptyMap()}
        for(r in rules){
            try{
                if(!WatchStore.enabled(c))break
                val ticker=quotes[r.symbol]?:continue
                val price=if(r.type=="CLOSE_ABOVE" || r.type=="CLOSE_BELOW") {
                    val bar=MarketData.candles(r.symbol,r.interval,r.priceType).lastOrNull()?:error("No candle")
                    BigDecimal.valueOf(bar.close)
                } else if (r.priceType=="MARK_PRICE") ticker.mark else ticker.last
                if(deliverPrice(c,r,price,meets(r,price))) hits++
                // Stay within public API rate limits when checking many candle rules.
                delay(150)
            }catch(e:CancellationException){throw e}catch(_:Exception){failed=true}
        }
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
        WatchStore.status(c,(if(failed)"بخشی از بررسی ناموفق بود" else "بررسی انجام شد")+" • "+java.text.DateFormat.getDateTimeInstance().format(java.util.Date())+" • اعلان: "+hits)
        !failed
    }
}
class MarketWatchWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=try{MarketMonitor.check(applicationContext);Result.success()}catch(e:CancellationException){throw e}catch(_:Exception){Result.failure()}
}
