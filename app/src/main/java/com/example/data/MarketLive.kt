package com.example.data

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

internal data class LiveBar(val eventTime: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)
internal data class PriceMarket(val symbol: String, val priceType: String) {
    val channel: String get() = if (priceType == "MARK_PRICE") "price" else "ticker"
    val field: String get() = if (priceType == "MARK_PRICE") "mp" else "la"
}

internal data class MarketPriceTick(val market: PriceMarket, val price: BigDecimal, val at: Long)

internal object MarketLive {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val names = mapOf("1m" to "1min","3m" to "3min","5m" to "5min","15m" to "15min","30m" to "30min","1h" to "60min","2h" to "2h","4h" to "4h","6h" to "6h","8h" to "8h","12h" to "12h","1d" to "1day","3d" to "3day","1w" to "1week","1M" to "1month")
    fun nextOpen(open: Long, interval: String): Long {
        val dt = Instant.ofEpochMilli(open).atZone(ZoneOffset.UTC)
        return when {
            interval == "1M" -> dt.plusMonths(1).toInstant().toEpochMilli()
            interval.endsWith("m") -> open + interval.dropLast(1).toLong()*60_000
            interval.endsWith("h") -> open + interval.dropLast(1).toLong()*3_600_000
            interval.endsWith("d") -> open + interval.dropLast(1).toLong()*86_400_000
            interval.endsWith("w") -> open + interval.dropLast(1).toLong()*604_800_000
            else -> error("Invalid interval")
        }
    }
    fun stream(symbol: String, interval: String, type: String) = callbackFlow<LiveBar> {
        require(MarketData.validSymbol(symbol) && interval in names && type in setOf("LAST_PRICE","MARK_PRICE"))
        val channel = (if(type=="MARK_PRICE")"mark" else "market")+"_kline_"+names.getValue(interval)
        val socket = client.newWebSocket(Request.Builder().url("wss://fapi.bitunix.com/public/").build(),object:WebSocketListener(){
            override fun onOpen(webSocket: WebSocket,response: Response){
                webSocket.send(JSONObject().put("op","subscribe").put("args",JSONArray().put(JSONObject().put("symbol",symbol).put("ch",channel))).toString())
            }
            override fun onMessage(webSocket: WebSocket,text:String){
                try{
                    val j=JSONObject(text)
                    if(j.optString("ch")!=channel || j.optString("symbol")!=symbol)return
                    val data=j.optJSONObject("data")?:return
                    val b=LiveBar(j.getLong("ts"),data.getString("o").toDouble(),data.getString("h").toDouble(),data.getString("l").toDouble(),data.getString("c").toDouble(),data.getString("b").toDouble())
                    if(b.eventTime>0 && listOf(b.open,b.high,b.low,b.close,b.volume).all{it.isFinite()} && b.low>0 && b.high>=b.low && b.open in b.low..b.high && b.close in b.low..b.high && b.volume>=0)trySend(b)
                }catch(_:Exception){}
            }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){close(t)}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String){close()}
        })
        awaitClose{socket.cancel()}
    }

    internal fun parsePriceTick(
        text: String, subscriptions: Set<PriceMarket>, now: Long = System.currentTimeMillis()
    ): MarketPriceTick? {
        val message = try { JSONObject(text) } catch (_: org.json.JSONException) { return null }
        val symbol = message.optString("symbol")
        val channel = message.optString("ch")
        val market = subscriptions.firstOrNull { it.channel == channel && it.symbol == symbol } ?: return null
        val at = message.optLong("ts")
        if (at !in now - 20_000..now + 5_000) return null
        val data = message.optJSONObject("data") ?: return null
        val dataSymbol = data.optString("s")
        if (dataSymbol.isNotBlank() && dataSymbol != symbol) return null
        val price = data.optString(market.field).toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        return MarketPriceTick(market, price, at)
    }

    fun prices(subscriptions: Set<PriceMarket>) = callbackFlow<MarketPriceTick> {
        require(subscriptions.isNotEmpty() && subscriptions.all { MarketData.validSymbol(it.symbol) })
        val lastReceived = java.util.concurrent.atomic.AtomicLong(android.os.SystemClock.elapsedRealtime())
        val socket = client.newWebSocket(
            Request.Builder().url("wss://fapi.bitunix.com/public/").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val args = JSONArray()
                    for (market in subscriptions) args.put(JSONObject().put("symbol", market.symbol).put("ch", market.channel))
                    webSocket.send(JSONObject().put("op", "subscribe").put("args", args).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val tick = parsePriceTick(text, subscriptions) ?: return
                    lastReceived.set(android.os.SystemClock.elapsedRealtime())
                    if (trySend(tick).isFailure) close(java.io.IOException("Price stream buffer unavailable"))
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { close() }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
            }
        )
        val heartbeat = launch {
            while (isActive) {
                delay(15_000)
                if (android.os.SystemClock.elapsedRealtime() - lastReceived.get() >= 30_000) {
                    close(java.io.IOException("No fresh market prices"))
                    break
                }
                socket.send(JSONObject().put("op", "ping").put("ping", System.currentTimeMillis() / 1000).toString())
            }
        }
        awaitClose {
            heartbeat.cancel()
            socket.cancel()
        }
    }.buffer(256)

}
