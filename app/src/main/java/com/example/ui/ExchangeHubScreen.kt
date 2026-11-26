package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.security.MessageDigest
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun ExchangeHubScreen(onTrade: () -> Unit) {
    var selected by remember { mutableStateOf("bitunix") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("مرکز صرافی‌ها", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("حساب‌های مستقل فیوچرز USDT • بدون برداشت یا انتقال", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("bitunix" to "Bitunix", "weex" to "WEEX", "lbank" to "LBank").forEach { (id, name) ->
                FilterChip(selected = selected == id, onClick = { selected = id }, label = { Text(name) })
            }
        }
        key(selected) {
            if (selected == "bitunix") {
                BitunixAccountCard()
                Button(onClick = onTrade, modifier = Modifier.fillMaxWidth()) { Text("مدیریت سفارش‌های Bitunix") }
                ExchangeJournal()
            } else {
                OtherAccount(selected)
                if (selected == "weex") {
                    WeexTradingCard()
                    ExchangeJournal("weex")
                }
            }
        }
    }
}

@Composable
private fun OtherAccount(exchange: String) {
    val context = LocalContext.current.applicationContext
    val vault = remember(exchange) { BitunixVault(context, exchange) }
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<ReadAccount?>(null) }
    var configured by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    suspend fun refresh(new: BitunixCredentials? = null) {
        if (busy) return
        busy = true; error = ""
        try {
            val c = new ?: withContext(Dispatchers.IO) { vault.load() }
            if (c != null) {
                if (new == null) configured = true
                val result = if (exchange == "weex") ExchangeReadClient.weexAccount(c) else ExchangeReadClient.lbankAccount(c)
                if (new != null) withContext(Dispatchers.IO) { vault.save(c) }
                configured = true; data = result; edit = false
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "دریافت اطلاعات ناموفق بود. کلید، مجوز خواندن، IP و ساعت گوشی را بررسی کنید." }
        finally { busy = false }
    }
    LaunchedEffect(Unit) { refresh() }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (exchange == "weex") "حساب WEEX" else "LBank • اتصال آزمایشی", style = MaterialTheme.typography.titleLarge)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            data?.let { a ->
                Text("آخرین دریافت موفق", color = MaterialTheme.colorScheme.tertiary)
                a.available?.let { Text("موجودی آزاد USDT"); Text(it, style = MaterialTheme.typography.headlineMedium) }
                a.balance?.let { Text("موجودی: " + it + " USDT") }
                a.pnl?.let { Text("سود/زیان باز: " + it + " USDT") }
                a.positions?.let { positions ->
                    HorizontalDivider()
                    Text("پوزیشن‌های باز", fontWeight = FontWeight.Bold)
                    if (positions.isEmpty()) Text("پوزیشن بازی دریافت نشد")
                    positions.forEach { p ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(p.symbol + " • " + p.side + " • " + p.leverage + "x")
                                Text("حجم: " + p.size + " | PnL: " + p.pnl)
                            }
                        }
                    }
                }
                if (a.note.isNotBlank()) Text(a.note)
            }
            Button(onClick = { edit = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (configured) "تغییر کلید" else "اتصال حساب") }
            if (configured || error.isNotBlank()) {
                Row {
                    TextButton(onClick = { scope.launch { refresh() } }, enabled = !busy && configured) { Text("به‌روزرسانی") }
                    TextButton(onClick = { disconnect = true }, enabled = !busy) { Text("حذف کلید") }
                }
            }
            Text(if (exchange == "weex") "برای ثبت سفارش، مجوز Futures Trade ضروری است. برداشت و انتقال را فعال نکنید." else "ثبت سفارش در این اتصال فعال نیست.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (edit) {
        var api by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!busy) edit = false }, title = { Text("اتصال کلید API") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (exchange == "lbank") Text("کلید HmacSHA256 فیوچرز؛ این اتصال آزمایشی است.")
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(api, { api = it }, label = { Text("API Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                    OutlinedTextField(secret, { secret = it }, label = { Text("Secret Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (exchange == "weex") OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { Button(enabled = !busy && api.isNotBlank() && secret.isNotBlank() && (exchange != "weex" || pass.isNotBlank()), onClick = {
            val c = BitunixCredentials(api.trim(), secret.trim(), pass)
            scope.launch { refresh(c) }
        }) { Text("آزمایش و ذخیره") } }, dismissButton = { TextButton(enabled = !busy, onClick = { edit = false }) { Text("انصراف") } })
    }
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("حذف اتصال از گوشی؟") },
        text = { Text("کلید محلی حذف می‌شود، حساب صرافی تغییر نمی‌کند.") }, confirmButton = { TextButton(onClick = {
            disconnect = false; scope.launch {
                busy = true
                try { withContext(Dispatchers.IO) { vault.clear() }; configured = false; data = null; error = "" }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "حذف کلید ناموفق بود" }
                finally { busy = false }
            }
        }) { Text("حذف") } }, dismissButton = { TextButton(onClick = { disconnect = false }) { Text("انصراف") } })
}

private fun accountTag(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

private data class JournalNote(val strategy: String, val note: String)
private fun encodeHistory(entries: List<ExchangeHistory>) = JSONArray().apply {
    entries.forEach { e -> put(JSONObject().put("id", e.id).put("symbol", e.symbol).put("side", e.side).put("pnl", e.pnl)
        .put("fee", e.fee).put("funding", e.funding).put("time", e.time)) }
}.toString()
private fun decodeHistory(raw: String): List<ExchangeHistory> = runCatching {
    val a = JSONArray(raw)
    (0 until a.length()).map { i -> a.getJSONObject(i).let { e ->
        ExchangeHistory(e.getString("id"), e.getString("symbol"), e.getString("side"), e.getString("pnl"), e.getString("fee"), e.getString("funding"), e.getLong("time"))
    } }
}.getOrDefault(emptyList())
private fun readNotes(raw: String): Map<String, JournalNote> = runCatching {
    val j = JSONObject(raw)
    j.keys().asSequence().associateWith { key -> j.getJSONObject(key).let { JournalNote(it.optString("strategy"), it.optString("note")) } }
}.getOrDefault(emptyMap())
private fun writeNotes(notes: Map<String, JournalNote>): String = JSONObject().apply {
    notes.forEach { (id, note) -> put(id, JSONObject().put("strategy", note.strategy).put("note", note.note)) }
}.toString()
// Quote every field and prevent formula injection when user notes are opened in a spreadsheet.
private fun csvField(raw: String): String {
    val safe = if (raw.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r') && raw.toBigDecimalOrNull() == null) "'" + raw else raw
    return "\"" + safe.replace("\"", "\"\"") + "\""
}

@Composable
private fun ExchangeJournal(exchange: String = "bitunix") {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("exchange_journal_v1", 0) }
    var entries by remember { mutableStateOf<List<ExchangeHistory>>(emptyList()) }
    var notes by remember { mutableStateOf<Map<String, JournalNote>>(emptyMap()) }
    var owner by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ExchangeHistory?>(null) }
    var lastSync by remember { mutableStateOf(0L) }
    var exportText by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && exportText.isNotEmpty()) {
            val payload = exportText
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri) ?: error("No stream")
                        stream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    }
                    status = "فایل CSV ذخیره شد"
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = "ذخیره فایل ناموفق بود" }
                finally { exportText = "" }
            }
        } else exportText = ""
    }
    suspend fun loadLocal(): BitunixCredentials? {
        val c = withContext(Dispatchers.IO) { BitunixVault(context, exchange).load() }
        val tag = c?.let { if (exchange == "bitunix") accountTag(it.apiKey) else accountTag(exchange + ":" + it.apiKey) } ?: ""
        if (tag != owner) {
            owner = tag
            entries = if (tag.isBlank()) emptyList() else decodeHistory(prefs.getString(tag + "_history", "[]") ?: "[]")
            notes = if (tag.isBlank()) emptyMap() else readNotes(prefs.getString(tag + "_notes", "{}") ?: "{}")
            lastSync = if (tag.isBlank()) 0 else prefs.getLong(tag + "_sync", 0)
            editing = null
        }
        return c
    }
    LaunchedEffect(Unit) {
        try { loadLocal() } catch (e: CancellationException) { throw e } catch (_: Exception) { status = "خواندن تاریخچه محلی ممکن نبود" }
    }
    // Read the active credential again before sync/export/edit to avoid mixing accounts.
    val shown = entries.filter { e -> e.symbol.contains(filter, true) || notes[e.id]?.strategy?.contains(filter, true) == true }
    val pnl = shown.mapNotNull { it.pnl.toBigDecimalOrNull() }
    val sum = pnl.fold(BigDecimal.ZERO) { a, b -> a + b }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (exchange == "weex") "ژورنال اجرای معاملات WEEX" else "ژورنال صرافی Bitunix", style = MaterialTheme.typography.titleLarge)
            Text(if (exchange == "weex") "۱۰۰ اجرای اخیر در ۷ روز گذشته؛ هر اجرا یک رکورد است، نه یک معامله کامل. فاندینگ دریافت نمی‌شود." else "تاریخچه ذخیره‌شده روی گوشی؛ حداکثر ۵۰۰ رکورد اخیر. سود خام قبل از کارمزد و فاندینگ.")
            if (lastSync > 0) Text("آخرین دریافت: " + DateFormat.getDateTimeInstance().format(Date(lastSync)), style = MaterialTheme.typography.labelSmall)
            Button(enabled = !busy, onClick = {
                busy = true; status = ""
                scope.launch {
                    try {
                        val c = loadLocal() ?: error("ابتدا حساب را متصل کنید")
                        val tag = owner
                        val fresh = if (exchange == "weex") ExchangeReadClient.weexHistory(c) else BitunixClient.history(c)
                        val current = withContext(Dispatchers.IO) { BitunixVault(context, exchange).load() }
                        require(current != null && (if (exchange == "bitunix") accountTag(current.apiKey) else accountTag(exchange + ":" + current.apiKey)) == tag)
                        entries = (fresh + entries).distinctBy { it.id }.sortedByDescending { it.time }.take(500)
                        lastSync = System.currentTimeMillis()
                        prefs.edit().putString(tag + "_history", encodeHistory(entries)).putLong(tag + "_sync", lastSync).apply()
                        status = fresh.size.toString() + " رکورد دریافت شد"
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { status = "دریافت تاریخچه ناموفق بود؛ اتصال حساب را بررسی کنید" }
                    finally { busy = false }
                }
            }) { Text("همگام‌سازی تاریخچه") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status)
            if (entries.isNotEmpty()) {
                OutlinedTextField(filter, { filter = it }, label = { Text("جستجوی نماد یا استراتژی") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("خلاصه رکوردهای فیلترشده")
                        Text(sum.stripTrailingZeros().toPlainString() + " USDT", style = MaterialTheme.typography.headlineSmall, color = if (sum.signum() < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
                        Text("تعداد رکورد: " + shown.size + " | مثبت: " + pnl.count { it.signum() > 0 } + " | منفی: " + pnl.count { it.signum() < 0 })
                    }
                }
                OutlinedButton(enabled = !busy && shown.isNotEmpty() && exportText.isEmpty(), onClick = {
                    scope.launch {
                        try {
                            val before = owner
                            require(loadLocal() != null && owner == before)
                            val rows = mutableListOf("exchange,symbol,side,realized_pnl_before_fees,funding,fee,updated_at_ms,position_id,strategy,note")
                            shown.forEach { e ->
                                val n = notes[e.id]
                                rows.add(listOf(exchange, e.symbol, e.side, e.pnl, e.funding, e.fee, e.time.toString(), e.id, n?.strategy ?: "", n?.note ?: "").joinToString(",") { csvField(it) })
                            }
                            exportText = "\uFEFF" + rows.joinToString("\r\n")
                            export.launch("SafeSize-" + exchange + ".csv")
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { status = "حساب تغییر کرده یا خروجی ناموفق است"; exportText = "" }
                    }
                }) { Text("خروجی CSV") }
            }
            shown.forEach { e ->
                HorizontalDivider()
                Text(e.symbol + " • " + e.side, fontWeight = FontWeight.Bold)
                Text("سود/زیان خام: " + e.pnl + " | کارمزد: " + e.fee + " | فاندینگ: " + e.funding)
                Text(DateFormat.getDateTimeInstance().format(Date(e.time)), style = MaterialTheme.typography.labelSmall)
                notes[e.id]?.let { n -> if (n.strategy.isNotBlank()) Text("استراتژی: " + n.strategy); if (n.note.isNotBlank()) Text(n.note) }
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        try { val before = owner; if (loadLocal() != null && before == owner) editing = e }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { status = "حساب قابل بررسی نیست" }
                    }
                }) { Text("ثبت استراتژی و یادداشت") }
            }
        }
    }
    editing?.let { e ->
        var strategy by remember(e.id) { mutableStateOf(notes[e.id]?.strategy ?: "") }
        var note by remember(e.id) { mutableStateOf(notes[e.id]?.note ?: "") }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("یادداشت " + e.symbol) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(strategy, { strategy = it.take(100) }, label = { Text("نام استراتژی / ستاپ") }, singleLine = true)
                OutlinedTextField(note, { note = it.take(2000) }, label = { Text("دلیل ورود، اشتباهات و نکات") }, minLines = 3)
            } }, confirmButton = { Button(onClick = {
                val before = owner
                scope.launch {
                    try {
                        require(loadLocal() != null && before == owner)
                        notes = notes + (e.id to JournalNote(strategy.trim(), note.trim()))
                        prefs.edit().putString(owner + "_notes", writeNotes(notes)).apply()
                        editing = null
                    } catch (ex: CancellationException) { throw ex }
                    catch (_: Exception) { editing = null; status = "حساب تغییر کرده است" }
                }
            }) { Text("ذخیره") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("لغو") } })
    }
}
