package com.example.ui

import androidx.compose.foundation.layout.*
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
            } else OtherAccount(selected)
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
            Text(if (exchange == "weex") "حساب WEEX • فقط خواندنی" else "LBank • اتصال آزمایشی", style = MaterialTheme.typography.titleLarge)
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
            Text("ثبت سفارش در این اتصال فعلاً فعال نیست.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (edit) {
        var api by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!busy) edit = false }, title = { Text("کلید API فقط خواندنی") }, text = {
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

@Composable
private fun ExchangeJournal() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ExchangeHistory>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("تاریخچه پوزیشن‌های Bitunix", style = MaterialTheme.typography.titleLarge)
            Text("حداکثر ۵۰۰ رکورد اخیر؛ جدا از ژورنال دستی. سود قبل از کارمزد و فاندینگ.")
            Button(enabled = !busy, onClick = {
                busy = true; status = ""
                scope.launch {
                    try {
                        val c = withContext(Dispatchers.IO) { BitunixVault(context).load() } ?: error("ابتدا حساب را متصل کنید")
                        entries = BitunixClient.history(c)
                        status = entries.size.toString() + " رکورد دریافت شد"
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { status = "دریافت تاریخچه ناموفق بود؛ اتصال حساب را بررسی کنید" }
                    finally { busy = false }
                }
            }) { Text("دریافت تاریخچه") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status)
            entries.forEach { e ->
                HorizontalDivider()
                Text(e.symbol + " • " + e.side, fontWeight = FontWeight.Bold)
                Text("سود/زیان خام: " + e.pnl + " | کارمزد: " + e.fee + " | فاندینگ: " + e.funding)
                Text(DateFormat.getDateTimeInstance().format(Date(e.time)), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
