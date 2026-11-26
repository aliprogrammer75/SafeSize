package com.example.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.math.BigDecimal
import java.util.UUID
import java.io.File
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

internal data class BitunixCredentials(val apiKey: String, val secret: String, val passphrase: String = "")
internal data class BitunixPosition(val symbol: String, val side: String, val leverage: String, val entry: String, val margin: String, val pnl: String, val id: String, val qty: String, val mode: String)
internal data class BitunixSnapshot(val available: String, val margin: String, val frozen: String, val crossPnl: String, val isolatedPnl: String, val positions: List<BitunixPosition>, val fetchedAt: Long)

internal class BitunixApiException(val code: Int) : Exception("Bitunix API error")

// Keys never leave this device except the API key in authenticated HTTPS headers.
// Ciphertext is excluded from Android backup; the AES key is non-exportable.
internal class BitunixVault(context: Context, private val exchange: String = "bitunix") {
    private val file = AtomicFile(File(context.noBackupFilesDir, exchange + "_credentials_v1"))
    private val alias = "safesize." + exchange + ".aes.v1"
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
    fun load(): BitunixCredentials? {
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully()
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return BitunixCredentials(json.getString("apiKey"), json.getString("secret"), json.optString("passphrase"))
    }
    fun save(credentials: BitunixCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        require(cipher.iv.size == 12)
        val json = JSONObject().put("apiKey", credentials.apiKey).put("secret", credentials.secret).put("passphrase", credentials.passphrase)
        val payload = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try { output.write(payload); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    fun clear() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}

internal object BitunixClient {
    private val random = SecureRandom()
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun sha(value: String) = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    // Fixed endpoint allowlist; no withdrawal or transfer routes.
    private fun get(credentials: BitunixCredentials, path: String, query: String, canonical: String, body: String? = null): JSONObject {
        require(path in setOf("/api/v1/futures/position/get_history_positions", "/api/v1/futures/account", "/api/v1/futures/position/get_pending_positions", "/api/v1/futures/account/get_leverage_margin_mode", "/api/v1/futures/trade/get_pending_orders", "/api/v1/futures/market/trading_pairs", "/api/v1/futures/trade/get_order_detail", "/api/v1/futures/trade/place_order", "/api/v1/futures/trade/flash_close_position", "/api/v1/futures/trade/cancel_orders"))
        require(if (body == null) !path.endsWith("/place_order") && !path.endsWith("/flash_close_position") && !path.endsWith("/cancel_orders") else path.endsWith("/place_order") || path.endsWith("/flash_close_position") || path.endsWith("/cancel_orders"))
        val nonce = hex(ByteArray(8).also { random.nextBytes(it) })
        val timestamp = System.currentTimeMillis().toString()
        val digest = sha(nonce + timestamp + credentials.apiKey + canonical + (body ?: ""))
        val connection = URL("https://fapi.bitunix.com" + path + query).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.setRequestProperty("api-key", credentials.apiKey)
            connection.setRequestProperty("nonce", nonce)
            connection.setRequestProperty("timestamp", timestamp)
            connection.setRequestProperty("sign", sha(digest + credentials.secret))
            connection.setRequestProperty("language", "en-US")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            if (connection.responseCode !in 200..299) throw BitunixApiException(connection.responseCode)
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val code = json.getInt("code")
            if (code != 0) throw BitunixApiException(code)
            return json
        } finally { connection.disconnect() }
    }
    suspend fun fetch(credentials: BitunixCredentials): BitunixSnapshot = withContext(Dispatchers.IO) {
        val result = get(credentials, "/api/v1/futures/account", "?marginCoin=USDT", "marginCoinUSDT")
        val account = result.getJSONArray("data").getJSONObject(0)
        val data = get(credentials, "/api/v1/futures/position/get_pending_positions", "?includeSubAccounts=false", "includeSubAccountsfalse").getJSONArray("data")
        val positions = (0 until data.length()).map { i ->
            val p = data.getJSONObject(i)
            BitunixPosition(p.getString("symbol"), p.getString("side"), p.getString("leverage"), p.getString("avgOpenPrice"), p.getString("margin"), p.getString("unrealizedPNL"), p.getString("positionId"), p.getString("qty"), p.getString("positionMode"))
        }
        BitunixSnapshot(account.getString("available"), account.getString("margin"), account.getString("frozen"),
            account.getString("crossUnrealizedPNL"), account.getString("isolationUnrealizedPNL"), positions, System.currentTimeMillis())
    }

    suspend fun history(c: BitunixCredentials): List<ExchangeHistory> = withContext(Dispatchers.IO) {
        val result = mutableListOf<ExchangeHistory>()
        for (page in 0 until 5) {
            val skip = page * 100
            val d = get(c, "/api/v1/futures/position/get_history_positions", "?limit=100&skip=" + skip, "limit100skip" + skip).getJSONObject("data")
            val a = d.getJSONArray("positionList")
            for (i in 0 until a.length()) {
                val x = a.getJSONObject(i)
                result.add(ExchangeHistory(x.getString("positionId"), x.getString("symbol"), x.getString("side"), x.getString("realizedPNL"), x.getString("fee"), x.getString("funding"), x.getLong("mtime")))
            }
            if (a.length() < 100 || result.size >= d.getInt("total")) break
        }
        result.distinctBy { it.id }.sortedByDescending { it.time }
    }
    private fun validSymbol(s: String) = s.matches(Regex("[A-Z0-9]{2,20}USDT"))
    private fun positive(s: String): BigDecimal {
        require(s.matches(Regex("[0-9]{1,16}(\\.[0-9]{1,16})?"))) { "عدد نامعتبر است" }
        return s.toBigDecimal().also { require(it.signum() > 0) { "عدد باید مثبت باشد" } }
    }
    suspend fun prepare(c: BitunixCredentials, draft: BitunixDraft): BitunixPreview = withContext(Dispatchers.IO) {
        require(validSymbol(draft.symbol)) { "نماد USDT نامعتبر است" }
        require(draft.side in setOf("BUY", "SELL") && draft.kind in setOf("LIMIT", "MARKET"))
        val qty = positive(draft.qty)
        val pairs = get(c, "/api/v1/futures/market/trading_pairs", "?symbols=" + draft.symbol, "symbols" + draft.symbol).getJSONArray("data")
        val pair = (0 until pairs.length()).map { pairs.getJSONObject(it) }.firstOrNull { it.getString("symbol") == draft.symbol }
            ?: throw IllegalArgumentException("نماد در صرافی یافت نشد")
        require(pair.getString("symbolStatus") == "OPEN" && pair.getBoolean("isApiSupported") && pair.getString("quote") == "USDT") { "نماد برای API فعال نیست" }
        val max = pair.getString(if (draft.kind == "LIMIT") "maxLimitOrderVolume" else "maxMarketOrderVolume").toBigDecimal()
        require(qty >= pair.getString("minTradeVolume").toBigDecimal() && qty <= max && qty.stripTrailingZeros().scale() <= pair.getInt("basePrecision")) { "مقدار یا دقت حجم با قوانین صرافی سازگار نیست" }
        val price = if (draft.kind == "LIMIT") positive(draft.price) else null
        val sl = if (draft.sl.isNotBlank()) positive(draft.sl) else null
        val tp = if (draft.tp.isNotBlank()) positive(draft.tp) else null
        listOfNotNull(price, sl, tp).forEach { require(it.stripTrailingZeros().scale() <= pair.getInt("quotePrecision")) { "دقت قیمت بیش از حد مجاز است" } }
        if (price != null) {
            require(sl == null || if (draft.side == "BUY") sl < price else sl > price) { "جهت حد ضرر نادرست است" }
            require(tp == null || if (draft.side == "BUY") tp > price else tp < price) { "جهت حد سود نادرست است" }
        }
        val account = get(c, "/api/v1/futures/account", "?marginCoin=USDT", "marginCoinUSDT").getJSONArray("data").getJSONObject(0)
        val mode = account.getString("positionMode")
        require(mode in setOf("ONE_WAY", "HEDGE")) { "حالت حساب ناشناخته است" }
        val settings = get(c, "/api/v1/futures/account/get_leverage_margin_mode", "?marginCoin=USDT&symbol=" + draft.symbol, "marginCoinUSDTsymbol" + draft.symbol).getJSONObject("data")
        BitunixPreview(draft, mode, settings.getInt("leverage"), settings.getString("marginMode"), account.getString("available"), UUID.randomUUID().toString().replace("-", ""), System.currentTimeMillis())
    }
    suspend fun place(c: BitunixCredentials, preview: BitunixPreview): String = withContext(Dispatchers.IO) {
        require(System.currentTimeMillis() - preview.at in 0..60000L) { "پیش‌نمایش منقضی شده است؛ دوباره بررسی کنید" }
        val fresh = prepare(c, preview.draft)
        require(fresh.mode == preview.mode && fresh.leverage == preview.leverage && fresh.marginMode == preview.marginMode) { "تنظیمات حساب تغییر کرده؛ دوباره بررسی کنید" }
        val d = preview.draft
        val body = JSONObject().put("symbol", d.symbol).put("qty", positive(d.qty).toPlainString()).put("side", d.side)
            .put("orderType", d.kind).put("clientId", preview.clientId).put("reduceOnly", false)
        if (preview.mode == "HEDGE") body.put("tradeSide", "OPEN")
        if (d.kind == "LIMIT") body.put("price", positive(d.price).toPlainString()).put("effect", "GTC")
        if (d.sl.isNotBlank()) body.put("slPrice", positive(d.sl).toPlainString()).put("slStopType", "MARK_PRICE").put("slOrderType", "MARKET")
        if (d.tp.isNotBlank()) body.put("tpPrice", positive(d.tp).toPlainString()).put("tpStopType", "MARK_PRICE").put("tpOrderType", "MARKET")
        get(c, "/api/v1/futures/trade/place_order", "", "", body.toString()).getJSONObject("data").getString("orderId")
    }
    suspend fun orderStatus(c: BitunixCredentials, id: String): String = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[a-f0-9]{32}")))
        val d = get(c, "/api/v1/futures/trade/get_order_detail", "?clientId=" + id, "clientId" + id).getJSONObject("data")
        d.getString("status") + " | " + d.getString("orderId")
    }
    suspend fun pending(c: BitunixCredentials): List<BitunixOrder> = withContext(Dispatchers.IO) {
        val d = get(c, "/api/v1/futures/trade/get_pending_orders", "?limit=100", "limit100").getJSONObject("data").getJSONArray("orderList")
        (0 until d.length()).map { i ->
            val o = d.getJSONObject(i)
            BitunixOrder(o.getString("orderId"), o.getString("symbol"), o.getString("qty"), o.optString("status"))
        }
    }
    suspend fun cancel(c: BitunixCredentials, order: BitunixOrder) = withContext(Dispatchers.IO) {
        require(validSymbol(order.symbol) && order.id.isNotBlank())
        val body = JSONObject().put("symbol", order.symbol).put("orderList", JSONArray().put(JSONObject().put("orderId", order.id)))
        val d = get(c, "/api/v1/futures/trade/cancel_orders", "", "", body.toString()).getJSONObject("data")
        require(d.getJSONArray("failureList").length() == 0 && d.getJSONArray("successList").length() == 1) { "صرافی لغو سفارش را تأیید نکرد" }
    }
    suspend fun close(c: BitunixCredentials, position: BitunixPosition, amount: String) = withContext(Dispatchers.IO) {
        val qty = positive(amount)
        val actual = fetch(c).positions.firstOrNull { it.id == position.id && it.side == position.side && it.symbol == position.symbol }
            ?: throw IllegalArgumentException("پوزیشن دیگر وجود ندارد")
        require(actual.qty.toBigDecimal().compareTo(position.qty.toBigDecimal()) == 0 && actual.mode == position.mode) { "پوزیشن تغییر کرده است؛ دوباره دریافت کنید" }
        require(qty <= actual.qty.toBigDecimal()) { "حجم خروج بیش از حجم پوزیشن است" }
        if (qty.compareTo(actual.qty.toBigDecimal()) == 0) {
            get(c, "/api/v1/futures/trade/flash_close_position", "", "", JSONObject().put("positionId", actual.id).toString())
        } else {
            require(actual.mode == "HEDGE") { "خروج جزئی فعلاً فقط برای حالت Hedge فعال است" }
            require(actual.side in setOf("LONG", "SHORT"))
            val body = JSONObject().put("symbol", actual.symbol).put("qty", qty.toPlainString())
                .put("side", if (actual.side == "LONG") "BUY" else "SELL").put("tradeSide", "CLOSE")
                .put("positionId", actual.id).put("orderType", "MARKET").put("reduceOnly", true)
                .put("clientId", UUID.randomUUID().toString().replace("-", ""))
            get(c, "/api/v1/futures/trade/place_order", "", "", body.toString())
        }
        Unit
    }
}
internal data class BitunixDraft(val symbol: String, val side: String, val kind: String, val qty: String, val price: String, val sl: String, val tp: String)
internal data class BitunixPreview(val draft: BitunixDraft, val mode: String, val leverage: Int, val marginMode: String, val available: String, val clientId: String, val at: Long)
internal data class BitunixOrder(val id: String, val symbol: String, val qty: String, val status: String)
