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
    private fun get(url: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpsURLConnection
        try {
            c.requestMethod = "GET"
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
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
}
