package com.example.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

internal data class SmsAlertConnection(val address: String, val token: String) {
    companion object {
        fun parse(address: String, token: String): SmsAlertConnection? {
            val uri = runCatching { URI(address.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host == null || uri.userInfo != null || uri.query != null ||
                uri.fragment != null || !uri.rawPath.orEmpty().matches(Regex("(/[A-Za-z0-9_-]+)*/?"))) return null
            val value = token.trim()
            if (!value.matches(Regex("[a-f0-9]{64}"))) return null
            return SmsAlertConnection(uri.toString().trimEnd('/'), value)
        }
    }
}

// Use the existing Android Keystore storage with a separate file and key alias.
internal class SmsAlertSettings(context: Context) {
    private val vault = BitunixVault(context, "sms_alerts")
    fun load() = vault.load()?.let { SmsAlertConnection.parse(it.apiKey, it.secret) }
    fun save(connection: SmsAlertConnection) = vault.save(BitunixCredentials(connection.address, connection.token))
}

internal data class ServerSmsAlert(
    val id: String, val symbol: String, val priceType: String, val price: String,
    val condition: String, val status: String, val mode: String
)

internal data class SmsAlertStatus(val mode: String, val alerts: List<ServerSmsAlert>)

internal class SmsAlertException(message: String) : Exception(message)

internal object SmsAlerts {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private suspend fun request(connection: SmsAlertConnection, path: String,
        body: JSONObject? = null, delete: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(connection.address + "/v1/alerts" + path)
            .header("Authorization", "Bearer " + connection.token)
        if (body != null) builder.put(body.toString().toRequestBody(json))
        if (delete) builder.delete()
        client.newCall(builder.build()).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching { JSONObject(payload).optString("error") }.getOrDefault("")
                throw SmsAlertException(when {
                    response.code == 401 -> "توکن اتصال سرور معتبر نیست"
                    error == "active_limit" -> "ظرفیت هشدارهای فعال سرور پر است"
                    error == "already_processed" -> "هشدار دیگر در انتظار نیست"
                    else -> "درخواست هشدار پیامکی روی سرور کامل نشد"
                })
            }
            JSONObject(payload)
        }
    }

    private fun decode(obj: JSONObject) = ServerSmsAlert(
        obj.getString("id"), obj.getString("symbol"), obj.getString("priceType"), obj.getString("price"),
        obj.getString("condition"), obj.getString("status"), obj.getString("mode")
    )

    suspend fun list(connection: SmsAlertConnection): SmsAlertStatus {
        val response = request(connection, "")
        if (response.optString("service") != "safesize-sms-alerts") throw SmsAlertException("این آدرس متعلق به سرویس هشدار SafeSize نیست")
        val items = response.getJSONArray("alerts")
        return SmsAlertStatus(response.getString("mode"), List(items.length()) { i -> decode(items.getJSONObject(i)) })
    }

    suspend fun create(connection: SmsAlertConnection, rule: WatchRule): ServerSmsAlert {
        val body = JSONObject().put("symbol", rule.symbol).put("priceType", rule.priceType)
            .put("price", rule.low).put("condition", rule.type)
        return decode(request(connection, "/" + rule.id, body).getJSONObject("alert"))
    }

    suspend fun cancel(connection: SmsAlertConnection, id: String) {
        request(connection, "/" + id, delete = true)
    }
}
