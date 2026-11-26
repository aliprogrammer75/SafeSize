package com.example.data

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

internal data class LiveBar(val eventTime: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)
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
}
