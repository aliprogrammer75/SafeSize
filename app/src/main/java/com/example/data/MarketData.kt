package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.math.BigDecimal
import java.math.RoundingMode

internal data class MarketPair(val symbol: String, val qtyPrecision: Int, val pricePrecision: Int, val minQty: BigDecimal, val maxQty: BigDecimal)
internal data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)
internal data class Quote(val mark: BigDecimal, val last: BigDecimal)
internal data class TriggerNotice(val id: String, val symbol: String, val time: Long)
internal data class Sizing(val quantity: BigDecimal, val loss: BigDecimal, val notional: BigDecimal, val reward: BigDecimal?)
internal object MarketData {
    val intervals = listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d")
    fun validSymbol(symbol: String) = symbol.matches(Regex("[A-Z0-9]{2,20}USDT"))
    private fun request(path: String, query: String): JSONArray {
        require(path in setOf("trading_pairs", "tickers", "kline"))
        val c = URL("https://fapi.bitunix.com/api/v1/futures/market/" + path + query).openConnection() as HttpsURLConnection
        try {
            c.connectTimeout = 10000; c.readTimeout = 10000; c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            require(c.responseCode in 200..299) { "HTTP " + c.responseCode }
            val r = c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            require(r.getInt("code") == 0) { "API " + r.getInt("code") }
            return r.getJSONArray("data")
        } finally { c.disconnect() }
    }
    suspend fun pairs(): List<MarketPair> = withContext(Dispatchers.IO) {
        val a = request("trading_pairs", "")
        (0 until a.length()).mapNotNull { i ->
            val p = a.getJSONObject(i)
            val s = p.getString("symbol")
            if (validSymbol(s) && p.getString("quote") == "USDT" && p.getString("symbolStatus") == "OPEN" && p.getBoolean("isApiSupported"))
                MarketPair(s, p.getInt("basePrecision"), p.getInt("quotePrecision"), p.getString("minTradeVolume").toBigDecimal(), p.getString("maxLimitOrderVolume").toBigDecimal()) else null
        }.sortedBy { it.symbol }
    }
    suspend fun quotes(symbols: List<String>): Map<String, Quote> = withContext(Dispatchers.IO) {
        require(symbols.isNotEmpty() && symbols.size <= 30 && symbols.all { validSymbol(it) })
        val a = request("tickers", "?symbols=" + symbols.distinct().joinToString(","))
        (0 until a.length()).associate { i ->
            val t = a.getJSONObject(i)
            t.getString("symbol") to Quote(t.getString("markPrice").toBigDecimal(), t.getString("lastPrice").toBigDecimal())
        }
    }
    suspend fun candles(symbol: String, interval: String): List<Candle> = withContext(Dispatchers.IO) {
        require(validSymbol(symbol) && interval in intervals)
        val a = request("kline", "?symbol=" + symbol + "&interval=" + interval + "&limit=100&type=LAST_PRICE")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { c ->
            Candle(c.getLong("time"), c.getString("open").toDouble(), c.getString("high").toDouble(), c.getString("low").toDouble(), c.getString("close").toDouble(), c.getString("baseVol").toDouble())
        } }.filter { listOf(it.open,it.high,it.low,it.close,it.volume).all { v -> v.isFinite() } && it.volume >= 0 && it.low > 0 && it.high >= it.low && it.open in it.low..it.high && it.close in it.low..it.high }
            .distinctBy { it.time }.sortedBy { it.time }
    }
    fun size(pair: MarketPair, available: BigDecimal, percent: BigDecimal, entry: BigDecimal, stop: BigDecimal, target: BigDecimal?, long: Boolean, feePct: BigDecimal): Sizing {
        require(available.signum() > 0 && percent.signum() > 0 && percent <= BigDecimal("100"))
        require(entry.signum() > 0 && stop.signum() > 0 && if (long) stop < entry else stop > entry)
        require(target == null || (target.signum() > 0 && (if (long) target > entry else target < entry)))
        require(feePct >= BigDecimal.ZERO && feePct <= BigDecimal("5"))
        require(listOfNotNull(entry,stop,target).all { it.stripTrailingZeros().scale() <= pair.pricePrecision })
        val fee = feePct.movePointLeft(2)
        val perCoin = (entry-stop).abs() + (entry+stop)*fee
        val budget = available*percent.movePointLeft(2)
        val q = budget.divide(perCoin, pair.qtyPrecision, RoundingMode.DOWN)
        require(q >= pair.minQty && q <= pair.maxQty && q.signum() > 0) { "حجم محاسبه شده خارج از حدود صرافی است" }
        return Sizing(q, q*perCoin, q*entry, target?.let { q*((it-entry).abs()-(entry+it)*fee) })
    }
}
