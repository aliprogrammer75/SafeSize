package com.example.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

internal data class BitunixCredentials(val apiKey: String, val secret: String)
internal data class BitunixPosition(val symbol: String, val side: String, val leverage: String, val entry: String, val margin: String, val pnl: String)
internal data class BitunixSnapshot(val available: String, val margin: String, val frozen: String, val crossPnl: String, val isolatedPnl: String, val positions: List<BitunixPosition>, val fetchedAt: Long)

internal class BitunixApiException(val code: Int) : Exception("Bitunix API error")

// Keys never leave this device except the API key in authenticated HTTPS headers.
// Ciphertext is excluded from Android backup; the AES key is non-exportable.
internal class BitunixVault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "bitunix_credentials_v1"))
    private val alias = "safesize.bitunix.aes.v1"
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
        return BitunixCredentials(json.getString("apiKey"), json.getString("secret"))
    }
    fun save(credentials: BitunixCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        require(cipher.iv.size == 12)
        val json = JSONObject().put("apiKey", credentials.apiKey).put("secret", credentials.secret)
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
    // Only two fixed GET endpoints are supported. No orders or transfers.
    private fun get(credentials: BitunixCredentials, path: String, query: String, canonical: String): JSONObject {
        val nonce = hex(ByteArray(8).also { random.nextBytes(it) })
        val timestamp = System.currentTimeMillis().toString()
        val digest = sha(nonce + timestamp + credentials.apiKey + canonical)
        val connection = URL("https://fapi.bitunix.com" + path + query).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.setRequestProperty("api-key", credentials.apiKey)
            connection.setRequestProperty("nonce", nonce)
            connection.setRequestProperty("timestamp", timestamp)
            connection.setRequestProperty("sign", sha(digest + credentials.secret))
            connection.setRequestProperty("language", "en-US")
            connection.setRequestProperty("Accept", "application/json")
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
            BitunixPosition(p.getString("symbol"), p.getString("side"), p.getString("leverage"), p.getString("avgOpenPrice"), p.getString("margin"), p.getString("unrealizedPNL"))
        }
        BitunixSnapshot(account.getString("available"), account.getString("margin"), account.getString("frozen"),
            account.getString("crossUnrealizedPNL"), account.getString("isolationUnrealizedPNL"), positions, System.currentTimeMillis())
    }
}
