package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import com.example.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.math.RoundingMode
import java.math.BigDecimal
import java.util.UUID

private const val chartUrl = "https://appassets.androidplatform.net/assets/chart/index.html"
private fun candlesJson(bars: List<Candle>)=JSONArray().apply {
    bars.forEach { b -> put(JSONObject().put("timestamp",b.time).put("open",b.open).put("high",b.high).put("low",b.low).put("close",b.close).put("volume",b.volume)) }
}.toString()
private class ChartBridge(
    private val ready: () -> Unit,
    private val older: (Long) -> Unit,
    private val select: (String, String) -> Unit,
    private val alert: (String, BigDecimal, Boolean) -> Unit
) {
    @JavascriptInterface fun ready(){ready.invoke()}
    @JavascriptInterface fun older(timestamp:String){timestamp.toLongOrNull()?.takeIf{it>0}?.let(older)}
    @JavascriptInterface fun select(kind:String,value:String){if(kind in setOf("entry","sl","tp") && value.length<64)select.invoke(kind,value)}
    @JavascriptInterface fun createAlert(condition: String, price: String) = registerAlert(condition, price, false)
    @JavascriptInterface fun createSmsAlert(condition: String, price: String) = registerAlert(condition, price, true)
    private fun registerAlert(condition: String, price: String, sms: Boolean) {
        if (condition !in setOf("ABOVE", "BELOW") || !price.matches(Regex("[0-9]{1,20}(\\.[0-9]{1,16})?"))) return
        price.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { alert(condition, it, sms) }
    }
}
@Composable
internal fun AdvancedChartDialog(
    symbol: String,
    initialInterval: String,
    precision: Int,
    entry: String,
    stop: String,
    target: String,
    onDismiss: () -> Unit,
    onSelect: (String, String) -> Unit,
    symbols: Map<String, Int> = emptyMap(),
    allowLevels: Boolean = true,
    demoChart: Boolean = false,
    demoLevels: List<ChartTradeLevel> = emptyList()
) {
    var activeSymbol by rememberSaveable(symbol) { mutableStateOf(symbol) }
    var interval by rememberSaveable(initialInterval) { mutableStateOf(initialInterval) }
    var type by rememberSaveable { mutableStateOf("LAST_PRICE") }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val symbolsByName = remember(symbols, symbol, precision) { symbols + (symbol to precision) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)
    ) {
        val view = LocalView.current
        DisposableEffect(view, fullScreen) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            if (fullScreen) {
                controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                if (fullScreen) controller?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) {
                        TextButton(
                            onClick = { query = ""; menu = "symbol" },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text(activeSymbol + " ▾", maxLines = 1) }
                        DropdownMenu(expanded = menu == "symbol", onDismissRequest = { menu = "" }) {
                            OutlinedTextField(
                                query, { query = it }, singleLine = true,
                                label = { Text("جستجوی نماد") },
                                modifier = Modifier.width(235.dp).padding(8.dp)
                            )
                            val matches = symbolsByName.keys.filter { it.contains(query.trim(), ignoreCase = true) }
                            if (matches.isEmpty()) DropdownMenuItem(text = { Text("نمادی پیدا نشد") }, onClick = {})
                            matches.take(12).forEach { item ->
                                DropdownMenuItem(text = { Text(item) }, onClick = { activeSymbol = item; menu = "" })
                            }
                        }
                    }
                    Box {
                        TextButton(
                            onClick = { menu = "interval" }, contentPadding = PaddingValues(horizontal = 5.dp)
                        ) { Text(interval + " ▾") }
                        DropdownMenu(expanded = menu == "interval", onDismissRequest = { menu = "" }) {
                            MarketData.intervals.forEach { item ->
                                DropdownMenuItem(text = { Text(item) }, onClick = { interval = item; menu = "" })
                            }
                        }
                    }
                    Box {
                        TextButton(
                            onClick = { menu = "price" }, contentPadding = PaddingValues(horizontal = 5.dp)
                        ) { Text(if (type == "LAST_PRICE") "Last ▾" else "Mark ▾") }
                        DropdownMenu(expanded = menu == "price", onDismissRequest = { menu = "" }) {
                            DropdownMenuItem(text = { Text("Last Price") }, onClick = { type = "LAST_PRICE"; menu = "" })
                            DropdownMenuItem(text = { Text("Mark Price") }, onClick = { type = "MARK_PRICE"; menu = "" })
                        }
                    }
                    IconButton(
                        onClick = { fullScreen = !fullScreen },
                        modifier = Modifier.semantics { contentDescription = if (fullScreen) "خروج از تمام‌صفحه" else "تمام‌صفحه" }
                    ) { Text(if (fullScreen) "↙" else "⛶", style = MaterialTheme.typography.titleLarge) }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.semantics { contentDescription = "بستن چارت" }
                    ) { Text("×", style = MaterialTheme.typography.headlineSmall) }
                }
                val calculatorLinked = activeSymbol == symbol && allowLevels
                key(activeSymbol, interval, type, calculatorLinked) {
                    AdvancedChart(
                        activeSymbol, interval, type, symbolsByName[activeSymbol] ?: precision,
                        entry, stop, target, calculatorLinked, onSelect, demoChart,
                        if (activeSymbol == symbol) demoLevels else emptyList(),
                        Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
        }
    }
}
@SuppressLint("SetJavaScriptEnabled")
@Composable private fun AdvancedChart(
    symbol: String, interval: String, type: String, precision: Int, entry: String, stop: String,
    target: String, calculatorLinked: Boolean, onSelect: (String, String) -> Unit,
    demoChart: Boolean, demoLevels: List<ChartTradeLevel>, modifier: Modifier
) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope=rememberCoroutineScope()
    val currentSelect by rememberUpdatedState(onSelect)
    val currentPrecision by rememberUpdatedState(precision.coerceIn(0, 16))
    var ready by remember{mutableStateOf(false)}
    var web by remember{mutableStateOf<WebView?>(null)}
    var loadingOlder by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("دریافت تاریخچه")}
    var oldest by remember{mutableStateOf(0L)}
    var lastClosed by remember{mutableStateOf(0L)}
    var pendingPermissionRule by remember { mutableStateOf<WatchRule?>(null) }
    var alertRevision by remember { mutableIntStateOf(0) }
    var alerts by remember { mutableStateOf<List<WatchRule>>(emptyList()) }
    var snapshot by remember { mutableStateOf(ChartTradeSnapshot(emptyList())) }
    val positionReader = remember { BitunixChartPositions() }
    val smsSettings = remember { SmsAlertSettings(context) }
    var smsConnection by remember { mutableStateOf<SmsAlertConnection?>(null) }
    var smsSaving by remember { mutableStateOf(false) }
    fun js(script:String){web?.evaluateJavascript(script,null)}
    fun alertResult(message: String) = js("window.SafeChart.alertResult(${JSONObject.quote(message)})")
    fun addAlert(rule: WatchRule) {
        if (!WatchStore.add(context, rule)) {
            alertResult("هشدار ذخیره نشد. حداکثر ۳۰ هشدار در رصد بازار نگه‌داری می‌شود")
            return
        }
        WatchStore.setEnabled(context, true)
        alertRevision++
        alertResult("هشدار قیمت ثبت شد")
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val rule = pendingPermissionRule
        pendingPermissionRule = null
        if (granted && rule != null) addAlert(rule)
        else if (rule != null) alertResult("برای ثبت هشدار، مجوز اعلان را فعال کنید")
    }
    suspend fun createAlert(condition: String, price: BigDecimal, sms: Boolean) {
        val value = price.setScale(currentPrecision, RoundingMode.HALF_UP).stripTrailingZeros()
        if (value.signum() <= 0) {
            alertResult("قیمت هشدار با دقت این نماد معتبر نیست")
            return
        }
        val rule = WatchRule(UUID.randomUUID().toString(), symbol, condition, value.toPlainString(), "",
            interval, "", emptyList(), priceType = type)
        if (sms) {
            val connected = smsConnection
            if (connected == null) {
                alertResult("ابتدا سرور پیامک را در صفحه رصد بازار متصل کنید")
                return
            }
            if (smsSaving) return
            smsSaving = true
            try {
                val added = SmsAlerts.create(connected, rule)
                alertResult(if (added.mode == "dry-run") "هشدار آزمایشی روی سرور ثبت شد" else "هشدار پیامکی روی سرور ثبت شد")
            } catch (e: CancellationException) {
                throw e
            } catch (e: SmsAlertException) {
                alertResult(e.message.orEmpty())
            } catch (_: Exception) {
                alertResult("پاسخ ثبت از سرور دریافت نشد. فهرست هشدارهای پیامکی را بررسی کنید")
            } finally { smsSaving = false }
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            if (pendingPermissionRule == null) {
                pendingPermissionRule = rule
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else addAlert(rule)
    }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        smsConnection = withContext(Dispatchers.IO) { runCatching { smsSettings.load() }.getOrNull() }
        js("window.SafeChart.smsAvailable(${smsConnection != null})")
    }
    LaunchedEffect(ready, alertRevision) {
        if (!ready) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                alerts = WatchStore.read(context).filter { it.active && it.symbol == symbol && it.priceType == type }
                delay(1500)
            }
        }
    }
    LaunchedEffect(ready, demoChart) {
        if (!ready || demoChart) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val vault = BitunixVault(context)
            while (isActive) {
                snapshot = try {
                    val credentials = withContext(Dispatchers.IO) { vault.load() }
                    if (credentials == null) ChartTradeSnapshot(emptyList(), "حساب Bitunix متصل نیست")
                    else positionReader.read(credentials, symbol)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ChartTradeSnapshot(emptyList(), "دریافت پوزیشن‌های Bitunix ناموفق بود")
                }
                delay(12000)
            }
        }
    }
    LaunchedEffect(ready, alerts, snapshot, demoLevels, demoChart) {
        if (!ready) return@LaunchedEffect
        val levels = if (demoChart) demoLevels else snapshot.levels
        val lines = JSONArray().apply { levels.forEach { put(it.toJson()) } }
        val alarms = JSONArray().apply {
            alerts.filter { it.type in setOf("ABOVE", "BELOW", "CLOSE_ABOVE", "CLOSE_BELOW") }.forEach {
                val threshold=it.low.toDoubleOrNull()
                if (threshold != null && threshold.isFinite() && threshold>0) {
                    val direction = if (it.type.endsWith("ABOVE")) "↑" else "↓"
                    val label = if (it.type.startsWith("CLOSE")) "هشدار بسته‌شدن ${it.interval} $direction" else "هشدار $direction"
                    put(JSONObject().put("price", threshold).put("label", label))
                }
            }
        }
        val status = if (demoChart) "حساب تمرینی" else snapshot.status
        js("window.SafeChart.marketLayers($alarms,$lines,${JSONObject.quote(status)})")
    }
    fun fit(v:WebView){
        val density=v.resources.displayMetrics.density
        if(v.height>0 && v.width>0 && density>0){
            val h=v.height/density;val w=v.width/density
            v.evaluateJavascript("window.SafeChart && window.SafeChart.fit("+h+","+w+")",null)
        }
    }
    fun loadOlder(timestamp:Long){
        if (loadingOlder) return
        if (!ready || oldest <= 0 || timestamp != oldest) {
            js("window.SafeChart.olderFailed()")
            return
        }
        loadingOlder=true
        scope.launch{
            try{
                val list=MarketData.candles(symbol,interval,type,timestamp,200).filter{it.time<timestamp}
                if(list.isNotEmpty())oldest=list.first().time
                js("window.SafeChart.older("+candlesJson(list)+","+list.isNotEmpty()+")")
            }catch(e:CancellationException){throw e}catch(_:Exception){js("window.SafeChart.olderFailed()")}
            finally{loadingOlder=false}
        }
    }
    DisposableEffect(Unit){onDispose{web?.let{it.removeJavascriptInterface("ChartNative");it.stopLoading();it.destroy()};web=null}}
    LaunchedEffect(ready){
        if(!ready)return@LaunchedEffect
        val market = listOf(symbol, interval, type).joinToString(",") { JSONObject.quote(it) }
        val intervals = JSONArray(MarketData.intervals)
        js("window.SafeChart.init($market,$currentPrecision,$calculatorLinked,$intervals)")
        while(isActive){
            try{
                val bars=MarketData.candles(symbol,interval,type,null,200)
                require(bars.isNotEmpty())
                if(oldest==0L){
                    oldest=bars.first().time;lastClosed=bars.last().time
                    js("window.SafeChart.reset("+candlesJson(bars)+")")
                }else{
                    // Reconnect/backfill: authoritative closed bars correct the latest forming candle.
                    val fresh=bars.filter{it.time>=lastClosed}
                    if(fresh.isNotEmpty()){
                        lastClosed=fresh.last().time
                        js("window.SafeChart.sync("+candlesJson(fresh)+")")
                    }
                }
                status="تاریخچه دریافت شد؛ در انتظار اتصال WebSocket"
                withTimeout(600000){
                    MarketLive.stream(symbol,interval,type).conflate().collect{live->
                        var start=MarketLive.nextOpen(lastClosed,interval)
                        // A gap or rollover is reconciled with REST, not filled with fake candles.
                        if(live.eventTime>=MarketLive.nextOpen(start,interval)){
                            val newer=MarketData.candles(symbol,interval,type,null,200).filter{it.time>=lastClosed}
                            if(newer.isNotEmpty()){
                                lastClosed=newer.last().time
                                js("window.SafeChart.sync("+candlesJson(newer)+")")
                            }
                            start=MarketLive.nextOpen(lastClosed,interval)
                        }
                        if(live.eventTime>=start && live.eventTime<MarketLive.nextOpen(start,interval)){
                            js("window.SafeChart.tick("+candlesJson(listOf(Candle(start,live.open,live.high,live.low,live.close,live.volume)))+"[0])")
                            MarketMonitor.observeChartPrice(context, symbol, type, BigDecimal.valueOf(live.close))
                            status=""
                        }
                    }
                }
            }catch(e:TimeoutCancellationException){status="در حال تازه‌سازی تاریخچه"}
            catch(e:CancellationException){throw e}catch(_:Exception){status=if(oldest==0L) "دریافت داده نمودار ناموفق؛ نماد و اتصال اینترنت را بررسی کنید" else "ارتباط زنده قطع است؛ تلاش مجدد"}
            delay(1500)
        }
    }
    LaunchedEffect(ready, currentPrecision) {
        if (ready) js("window.SafeChart.precision($currentPrecision)")
    }
    LaunchedEffect(ready,entry,stop,target,lastClosed){
        if(calculatorLinked && ready && lastClosed>0) js("window.SafeChart.levels("+JSONObject.quote(entry)+","+JSONObject.quote(stop)+","+JSONObject.quote(target)+")")
    }
    Box(modifier) {
        AndroidView(modifier=Modifier.fillMaxSize(),factory={context->
            val loader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(context)).build()
            WebView(context).apply{
                web=this
                setBackgroundColor(android.graphics.Color.rgb(11,16,24))
                layoutParams=android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                settings.useWideViewPort=true
                settings.loadWithOverviewMode=false
                settings.textZoom=100
                settings.javaScriptEnabled=true
                settings.domStorageEnabled=true
                settings.allowFileAccess=false
                settings.allowContentAccess=false
                settings.javaScriptCanOpenWindowsAutomatically=false
                settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                addJavascriptInterface(ChartBridge(
                    {scope.launch{ready=true}},
                    {ts->scope.launch{loadOlder(ts)}},
                    {kind,value->scope.launch{
                        val n=value.toBigDecimalOrNull()
                        if(calculatorLinked && n!=null && n.signum()>0)currentSelect(kind,n.setScale(currentPrecision,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
                    }},
                    {condition, price, sms -> scope.launch { createAlert(condition, price, sms) }}
                ),"ChartNative")
                webViewClient=object:WebViewClient(){
                    override fun onPageFinished(view:WebView,url:String){
                        fit(view)
                        view.evaluateJavascript("typeof window.SafeChart !== 'undefined' && typeof window.klinecharts !== 'undefined'"){v->if(v!="true")status="موتور چارت آماده نشد؛ فایل نمودار یا Android System WebView را بررسی کنید"}
                    }
                    override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=request.url.toString()!=chartUrl
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? =
                        if(request.url.scheme=="https" && request.url.host=="appassets.androidplatform.net" && request.url.path?.startsWith("/assets/chart/")==true)
                            loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain","utf-8",ByteArrayInputStream(ByteArray(0)))
                        else WebResourceResponse("text/plain","utf-8",ByteArrayInputStream(ByteArray(0)))
                }
                addOnLayoutChangeListener { v,_,_,_,_,_,_,_,_ ->
                    val wv=v as WebView
                    wv.post { fit(wv) }
                }
                loadUrl(chartUrl)
            }
        })
        if (status.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 55.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    status, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
    }
}
