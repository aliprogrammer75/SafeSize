package com.example.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebViewAssetLoader
import com.example.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.RoundingMode

private const val chartUrl = "https://appassets.androidplatform.net/assets/chart/index.html"
private fun candlesJson(bars: List<Candle>)=JSONArray().apply {
    bars.forEach { b -> put(JSONObject().put("timestamp",b.time).put("open",b.open).put("high",b.high).put("low",b.low).put("close",b.close).put("volume",b.volume)) }
}.toString()
private class ChartBridge(private val ready:()->Unit,private val older:(Long)->Unit,private val select:(String,String)->Unit){
    @JavascriptInterface fun ready(){ready.invoke()}
    @JavascriptInterface fun older(timestamp:String){timestamp.toLongOrNull()?.takeIf{it>0}?.let(older)}
    @JavascriptInterface fun select(kind:String,value:String){if(kind in setOf("entry","sl","tp") && value.length<64)select.invoke(kind,value)}
}
@Composable
fun AdvancedChartDialog(symbol:String, initialInterval:String, precision:Int, entry:String, stop:String, target:String, onDismiss:()->Unit,onSelect:(String,String)->Unit){
    var interval by remember{mutableStateOf(initialInterval)}
    var type by remember{mutableStateOf("LAST_PRICE")}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=true)){
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
            Column(Modifier.fillMaxSize().padding(8.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Text(symbol+" • Bitunix",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=10.dp))
                    TextButton(onClick=onDismiss){Text("بازگشت")}
                }
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    MarketData.intervals.forEach{t->FilterChip(t==interval,{interval=t},{Text(t)})}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    FilterChip(type=="LAST_PRICE",{type="LAST_PRICE"},{Text("Last Price")})
                    FilterChip(type=="MARK_PRICE",{type="MARK_PRICE"},{Text("Mark Price")})
                }
                key(symbol,interval,type){
                    AdvancedChart(symbol,interval,type,precision,entry,stop,target,onSelect,Modifier.weight(1f).fillMaxWidth())
                }
                Text("تحلیل مستقل؛ انتخاب سطح فقط ماشین حساب را به‌روز می‌کند و معامله ارسال نمی‌کند.",style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}
@SuppressLint("SetJavaScriptEnabled")
@Composable private fun AdvancedChart(symbol:String,interval:String,type:String,precision:Int,entry:String,stop:String,target:String,onSelect:(String,String)->Unit,modifier:Modifier){
    val scope=rememberCoroutineScope()
    val currentSelect by rememberUpdatedState(onSelect)
    var ready by remember{mutableStateOf(false)}
    var web by remember{mutableStateOf<WebView?>(null)}
    var loadingOlder by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("دریافت تاریخچه")}
    var oldest by remember{mutableStateOf(0L)}
    var lastClosed by remember{mutableStateOf(0L)}
    fun js(script:String){web?.evaluateJavascript(script,null)}
    fun loadOlder(timestamp:Long){
        if(!ready || loadingOlder || oldest<=0 || timestamp!=oldest)return
        loadingOlder=true
        scope.launch{
            try{
                val list=MarketData.candles(symbol,interval,type,timestamp,200).filter{it.time<timestamp}
                if(list.isNotEmpty())oldest=list.first().time
                js("window.SafeChart.older("+candlesJson(list)+","+(list.size==200)+")")
            }catch(e:CancellationException){throw e}catch(_:Exception){js("window.SafeChart.olderFailed()")}
            finally{loadingOlder=false}
        }
    }
    DisposableEffect(Unit){onDispose{web?.let{it.removeJavascriptInterface("ChartNative");it.stopLoading();it.destroy()};web=null}}
    LaunchedEffect(ready){
        if(!ready)return@LaunchedEffect
        val series=symbol+"|"+interval+"|"+type
        js("window.SafeChart.init("+JSONObject.quote(series)+","+precision.coerceIn(0,16)+")")
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
                status="کندل‌های بسته‌شده دریافت شدند؛ اتصال زنده"
                withTimeout(60000){
                    MarketLive.stream(symbol,interval,type).conflate().collect{live->
                        var start=MarketLive.nextOpen(lastClosed,interval)
                        // A gap or rollover is reconciled with REST, not filled with fake candles.
                        if(live.eventTime>=MarketLive.nextOpen(start,interval) || live.eventTime<start){
                            val newer=MarketData.candles(symbol,interval,type,null,200).filter{it.time>=lastClosed}
                            if(newer.isNotEmpty()){
                                lastClosed=newer.last().time;lastPlotted=lastClosed
                                js("window.SafeChart.sync("+candlesJson(newer)+")")
                            }
                            start=MarketLive.nextOpen(lastClosed,interval)
                        }
                        if(live.eventTime>=start && live.eventTime<MarketLive.nextOpen(start,interval)){
                            js("window.SafeChart.tick("+candlesJson(listOf(Candle(start,live.open,live.high,live.low,live.close,live.volume)))+"[0])")
                            status="WebSocket • کندل در حال تشکیل"
                        }
                    }
                }
            }catch(e:TimeoutCancellationException){status="در حال تازه‌سازی تاریخچه"}
            catch(e:CancellationException){throw e}catch(_:Exception){status="ارتباط زنده قطع است؛ تلاش مجدد"}
            delay(1500)
        }
    }
    LaunchedEffect(ready,entry,stop,target,lastClosed){
        if(ready && lastClosed>0) js("window.SafeChart.levels("+JSONObject.quote(entry)+","+JSONObject.quote(stop)+","+JSONObject.quote(target)+")")
    }
    Column(modifier){
        Text(status,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        AndroidView(modifier=Modifier.weight(1f).fillMaxWidth(),factory={context->
            val loader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(context)).build()
            WebView(context).apply{
                web=this
                setBackgroundColor(android.graphics.Color.rgb(11,16,24))
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
                        if(n!=null && n.signum()>0)currentSelect(kind,n.setScale(precision.coerceIn(0,16),RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
                    }}
                ),"ChartNative")
                webViewClient=object:WebViewClient(){
                    override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=request.url.toString()!=chartUrl
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? =
                        if(request.url.scheme=="https" && request.url.host=="appassets.androidplatform.net" && request.url.path?.startsWith("/assets/chart/")==true)
                            loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain","utf-8",ByteArrayInputStream(ByteArray(0)))
                        else WebResourceResponse("text/plain","utf-8",ByteArrayInputStream(ByteArray(0)))
                }
                loadUrl(chartUrl)
            }
        })
    }
}
