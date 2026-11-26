package com.example.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection

internal data class ExchangeHistory(val id: String, val symbol: String, val side: String, val pnl: String, val fee: String, val funding: String, val time: Long)
internal data class ReadPosition(val symbol: String, val side: String, val size: String, val leverage: String, val pnl: String)
internal data class ReadAccount(val available: String?, val balance: String?, val pnl: String?, val positions: List<ReadPosition>?, val note: String = "")

internal object ExchangeReadClient {
    private fun hmac(secret: String, message: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        doFinal(message.toByteArray(Charsets.UTF_8))
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(Locale.US, it.toInt() and 255) }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun get(url: String, headers: Map<String, String>, body: String? = null): String {
        val c = URL(url).openConnection() as HttpsURLConnection
        try {
            c.requestMethod = if (body == null) "GET" else "POST"
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }
    private fun weex(c: BitunixCredentials, path: String): JSONArray {
        require(path in setOf("/capi/v3/account/balance", "/capi/v3/account/position/allPosition"))
        val ts = System.currentTimeMillis().toString()
        val signature = Base64.encodeToString(hmac(c.secret, ts + "GET" + path), Base64.NO_WRAP)
        return JSONArray(get("https://api-contract.weex.com" + path, mapOf("ACCESS-KEY" to c.apiKey,
            "ACCESS-SIGN" to signature, "ACCESS-TIMESTAMP" to ts, "ACCESS-PASSPHRASE" to c.passphrase, "Content-Type" to "application/json")))
    }
    suspend fun weexAccount(c: BitunixCredentials): ReadAccount = withContext(Dispatchers.IO) {
        require(c.passphrase.isNotBlank())
        val b = weex(c, "/capi/v3/account/balance")
        val usdt = (0 until b.length()).map { b.getJSONObject(it) }.firstOrNull { it.getString("asset") == "USDT" }
        val a = weex(c, "/capi/v3/account/position/allPosition")
        val positions = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("asset") == "USDT" && it.getString("size").toBigDecimal().signum() != 0 }
            .map { ReadPosition(it.getString("symbol"), it.getString("side"), it.getString("size"), it.getString("leverage"), it.getString("unrealizePnl")) }
        ReadAccount(usdt?.getString("availableBalance"), usdt?.getString("balance"), usdt?.getString("unrealizePnl"), positions)
    }
    // Official contract documentation shows this signed account request but not the complete response schema.
    // Validate the envelope only; do not infer balance or position field meanings.
    suspend fun lbankAccount(c: BitunixCredentials): ReadAccount = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis().toString()
        val echo = hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val params = sortedMapOf("api_key" to c.apiKey, "asset" to "USDT", "echostr" to echo,
            "productGroup" to "SwapU", "signature_method" to "HmacSHA256", "timestamp" to ts)
        val canonical = params.entries.joinToString("&") { it.key + "=" + it.value }
        val digest = hex(MessageDigest.getInstance("MD5").digest(canonical.toByteArray(Charsets.UTF_8))).uppercase(Locale.US)
        val signature = hex(hmac(c.secret, digest))
        val query = params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) } + "&sign=" + enc(signature)
        val response = JSONObject(get("https://lbkperp.lbank.com/cfd/openApi/v1/prv/account?" + query,
            mapOf("Content-Type" to "application/json", "timestamp" to ts, "signature_method" to "HmacSHA256", "echostr" to echo)))
        require(response.has("error_code") && response.getInt("error_code") == 0 &&
            (response.optBoolean("result", false) || response.optBoolean("success", false)) && !response.isNull("data")) { "LBank: درخواست حساب تأیید نشد" }
        ReadAccount(null, null, null, null, "احراز هویت حساب موفق بود؛ نمایش موجودی و پوزیشن پس از تأیید ساختار پاسخ فعال می‌شود.")
    }

    private fun signedWeex(c: BitunixCredentials, path: String, query: String = "", body: String? = null): String {
        require(path in setOf("/capi/v3/userTrades", "/capi/v3/order"))
        require(body == null || path == "/capi/v3/order" && query.isEmpty())
        val ts = System.currentTimeMillis().toString()
        val signature = Base64.encodeToString(hmac(c.secret, ts + (if (body == null) "GET" else "POST") + path + query + (body ?: "")), Base64.NO_WRAP)
        return get("https://api-contract.weex.com" + path + query, mapOf("ACCESS-KEY" to c.apiKey,
            "ACCESS-SIGN" to signature, "ACCESS-TIMESTAMP" to ts, "ACCESS-PASSPHRASE" to c.passphrase, "Content-Type" to "application/json"), body)
    }
    suspend fun weexHistory(c: BitunixCredentials): List<ExchangeHistory> = withContext(Dispatchers.IO) {
        val a = JSONArray(signedWeex(c, "/capi/v3/userTrades", "?limit=100"))
        (0 until a.length()).map { i ->
            val t = a.getJSONObject(i)
            ExchangeHistory(t.getString("symbol") + ":" + t.getString("id"), t.getString("symbol"), t.getString("side") + "/" + t.getString("positionSide"), t.getString("realizedPnl"),
                t.getString("commission") + " " + t.getString("commissionAsset"), "—", t.getLong("time"))
        }
    }
    suspend fun weexPlace(c: BitunixCredentials, draft: WeexDraft): String = withContext(Dispatchers.IO) {
        require(draft.symbol.matches(Regex("[A-Z0-9]{2,20}USDT")) && draft.position in setOf("LONG", "SHORT") && draft.kind in setOf("LIMIT", "MARKET"))
        fun positive(v: String): java.math.BigDecimal {
            require(v.matches(Regex("[0-9]{1,16}(\\.[0-9]{1,16})?")))
            return v.toBigDecimal().also { require(it.signum() > 0) }
        }
        val qty = positive(draft.qty)
        val stop = positive(draft.stop)
        // Explicit position direction and reduceOnly flag. No automatic retries.
        val body = JSONObject().put("symbol", draft.symbol).put("side", if (draft.position == "LONG") "BUY" else "SELL")
            .put("positionSide", draft.position).put("type", draft.kind).put("quantity", qty.toPlainString())
            .put("newClientOrderId", draft.id).put("reduceOnly", false).put("slTriggerPrice", stop.toPlainString()).put("SlWorkingType", "MARK_PRICE")
        if (draft.kind == "LIMIT") {
            val price = positive(draft.price)
            require(if (draft.position == "LONG") stop < price else stop > price)
            body.put("price", price.toPlainString()).put("timeInForce", "GTC")
        }
        if (draft.tp.isNotBlank()) {
            val tp = positive(draft.tp)
            if (draft.kind == "LIMIT") require(if (draft.position == "LONG") tp > draft.price.toBigDecimal() else tp < draft.price.toBigDecimal())
            body.put("tpTriggerPrice", tp.toPlainString()).put("TpWorkingType", "MARK_PRICE")
        }
        val response = JSONObject(signedWeex(c, "/capi/v3/order", body = body.toString()))
        require(response.getBoolean("success") && response.getString("clientOrderId") == draft.id)
        response.getString("orderId")
    }
}
internal data class WeexDraft(val symbol: String, val position: String, val kind: String, val qty: String, val price: String, val stop: String, val tp: String, val id: String)
