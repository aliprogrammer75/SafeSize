package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun BitunixTradingScreen() {
    val context = LocalContext.current.applicationContext
    val vault = remember { BitunixVault(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf<BitunixSnapshot?>(null) }
    var orders by remember { mutableStateOf<List<BitunixOrder>>(emptyList()) }
    var symbol by remember { mutableStateOf("") }
    var side by remember { mutableStateOf("BUY") }
    var kind by remember { mutableStateOf("LIMIT") }
    var qty by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var sl by remember { mutableStateOf("") }
    var tp by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<BitunixPreview?>(null) }
    var closing by remember { mutableStateOf<BitunixPosition?>(null) }
    var closeQty by remember { mutableStateOf("") }
    var cancelling by remember { mutableStateOf<BitunixOrder?>(null) }
    var lastClientId by remember { mutableStateOf("") }
    var uncertain by remember { mutableStateOf(false) }
    suspend fun creds(): BitunixCredentials = withContext(Dispatchers.IO) { vault.load() } ?: error("ابتدا در داشبورد حساب را متصل کنید")
    suspend fun reload(c: BitunixCredentials) {
        val s = BitunixClient.fetch(c)
        val o = BitunixClient.pending(c)
        snapshot = s
        orders = o
    }
    fun run(write: Boolean = false, action: suspend (BitunixCredentials) -> Unit) {
        if (busy || (write && (!enabled || uncertain))) return
        busy = true
        message = ""
        scope.launch {
            try { action(creds()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (write) {
                    uncertain = true
                    message = "نتیجه عملیات قطعی نیست یا درخواست رد شده است. در خود صرافی وضعیت را بررسی کنید؛ ارسال مجدد خودکار وجود ندارد."
                } else {
                    message = when (e) {
                        is BitunixApiException -> "خطای صرافی: " + e.code
                        is IllegalArgumentException, is IllegalStateException -> e.message ?: "بررسی ورودی ناموفق بود"
                        else -> "دریافت اطلاعات ناموفق بود"
                    }
                }
            } finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("معاملات واقعی بیت‌یونیکس", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("فیوچرز USDT · برداشت و انتقال وجه وجود ندارد", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("فعال‌سازی معامله برای همین جلسه", fontWeight = FontWeight.Bold)
                Text("معاملات اهرمی واقعی هستند. در هر سفارش، مقدار بر حسب واحد خود ارز است، نه دلار.")
                Row {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it; preview = null }, enabled = !busy)
                    Text("آگاهانه اجازه ثبت سفارش دستی در این صفحه را می‌دهم", modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        Button(onClick = { run { reload(it); message = "اطلاعات دریافت شد" } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("دریافت وضعیت حساب و سفارش‌ها") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, color = if (uncertain) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (lastClientId.isNotBlank()) {
            Text("شناسه پیگیری سفارش: " + lastClientId, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = { run { message = "وضعیت سفارش: " + BitunixClient.orderStatus(it, lastClientId) } }) { Text("استعلام نتیجه سفارش") }
        }
        if (uncertain) {
            TextButton(enabled = !busy, onClick = { uncertain = false; enabled = false; message = "برای ادامه، پس از بررسی صرافی دوباره معامله را فعال کنید." }) { Text("وضعیت را در صرافی بررسی کردم") }
        }
        val canWrite = enabled && !busy && !uncertain
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("سفارش جدید", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(symbol, { symbol = it.uppercase(Locale.US).replace("/", "").trim() }, label = { Text("نماد مانند BTCUSDT") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = side == "BUY", onClick = { side = "BUY" }, label = { Text("Long / Buy") }, enabled = !busy)
                    FilterChip(selected = side == "SELL", onClick = { side = "SELL" }, label = { Text("Short / Sell") }, enabled = !busy)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == "LIMIT", onClick = { kind = "LIMIT" }, label = { Text("Limit") }, enabled = !busy)
                    FilterChip(selected = kind == "MARKET", onClick = { kind = "MARKET" }, label = { Text("Market") }, enabled = !busy)
                }
                OutlinedTextField(qty, { qty = it }, label = { Text("حجم سفارش (واحد ارز)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                if (kind == "LIMIT") OutlinedTextField(price, { price = it }, label = { Text("قیمت سفارش USDT") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                OutlinedTextField(sl, { sl = it }, label = { Text("حد ضرر (اختیاری)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                OutlinedTextField(tp, { tp = it }, label = { Text("حد سود (اختیاری)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Text("اهرم و نوع مارجین از تنظیمات فعلی صرافی خوانده می‌شود و در این مرحله تغییر نمی‌کند.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = canWrite && symbol.isNotBlank() && qty.isNotBlank(), onClick = {
                    val d = BitunixDraft(symbol, side, kind, qty.trim(), price.trim(), sl.trim(), tp.trim())
                    run { preview = BitunixClient.prepare(it, d) }
                }, modifier = Modifier.fillMaxWidth()) { Text("بررسی و پیش‌نمایش سفارش") }
            }
        }
        Text("پوزیشن‌های واقعی", style = MaterialTheme.typography.titleLarge)
        if (snapshot == null) Text("ابتدا وضعیت حساب را دریافت کنید.")
        snapshot?.let { s ->
            Text("موجودی آزاد: " + money(s.available) + " USDT")
            if (s.positions.isEmpty()) Text("پوزیشن بازی وجود ندارد.")
            s.positions.forEach { p ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(p.symbol + " · " + p.side + " · " + p.leverage + "x", fontWeight = FontWeight.Bold)
                        Text("حجم: " + p.qty + " | PnL: " + money(p.pnl))
                        OutlinedButton(enabled = canWrite, onClick = { closing = p; closeQty = p.qty }) { Text("بستن پوزیشن") }
                    }
                }
            }
        }
        Text("سفارش‌های باز (حداکثر ۱۰۰)", style = MaterialTheme.typography.titleLarge)
        if (snapshot != null && orders.isEmpty()) Text("سفارش بازی دریافت نشد.")
        orders.forEach { o ->
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(o.symbol + " · " + o.status, fontWeight = FontWeight.Bold)
                    Text("حجم: " + o.qty + " | ID: " + o.id)
                    OutlinedButton(enabled = canWrite, onClick = { cancelling = o }) { Text("لغو سفارش") }
                }
            }
        }
    }
    preview?.let { p ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("تأیید سفارش واقعی") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.draft.symbol + " · " + p.draft.side + " · " + p.draft.kind, fontWeight = FontWeight.Bold)
                Text("حجم: " + p.draft.qty + " واحد ارز")
                Text("قیمت: " + if (p.draft.kind == "MARKET") "قیمت بازار (ممکن است لغزش داشته باشد)" else p.draft.price)
                Text("اهرم: " + p.leverage + "x · " + p.marginMode + " · " + p.mode)
                Text("موجودی آزاد: " + money(p.available) + " USDT")
                Text("حد ضرر: " + p.draft.sl.ifBlank { "ندارد" } + " | حد سود: " + p.draft.tp.ifBlank { "ندارد" })
                if (p.mode == "ONE_WAY") Text("در حالت One-way سفارش مخالف ممکن است پوزیشن موجود را کاهش دهد یا معکوس کند.", color = MaterialTheme.colorScheme.error)
                Text("تأیید نهایی، سفارش واقعی ارسال می‌کند. اجرای حد ضرر به قیمت دقیق تضمین نمی‌شود.", color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { Button(enabled = enabled && !busy && !uncertain, onClick = {
                preview = null
                lastClientId = p.clientId
                run(true) { c ->
                    val id = BitunixClient.place(c, p)
                    message = "درخواست ثبت پذیرفته شد: " + id + "؛ تکمیل معامله هنوز تأیید نشده است."
                    try { reload(c) } catch (e: CancellationException) { throw e } catch (_: Exception) { message += " دریافت وضعیت جدید ناموفق بود." }
                }
            }) { Text("ارسال سفارش واقعی") } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("انصراف") } })
    }
    closing?.let { p ->
        AlertDialog(onDismissRequest = { closing = null }, title = { Text("تأیید خروج از پوزیشن") },
            text = { Column {
                Text(p.symbol + " · " + p.side + " · حجم فعلی " + p.qty)
                OutlinedTextField(closeQty, { closeQty = it }, label = { Text("حجم خروج به واحد ارز") }, singleLine = true, readOnly = p.mode != "HEDGE")
                Text("خروج با قیمت بازار انجام می‌شود. خروج جزئی فعلاً فقط در حالت Hedge امکان‌پذیر است.")
            } },
            confirmButton = { Button(enabled = enabled && !busy && !uncertain && closeQty.toBigDecimalOrNull()?.let { it.signum() > 0 && it <= (p.qty.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) } == true, onClick = {
                val amount = closeQty.trim()
                closing = null
                run(true) { c ->
                    BitunixClient.close(c, p, amount)
                    message = "درخواست خروج پذیرفته شد؛ وضعیت نهایی را بررسی کنید."
                    try { reload(c) } catch (e: CancellationException) { throw e } catch (_: Exception) { message += " دریافت وضعیت جدید ناموفق بود." }
                }
            }) { Text("ارسال خروج واقعی") } },
            dismissButton = { TextButton(onClick = { closing = null }) { Text("انصراف") } })
    }
    cancelling?.let { o ->
        AlertDialog(onDismissRequest = { cancelling = null }, title = { Text("لغو سفارش واقعی") },
            text = { Text(o.symbol + " · ID: " + o.id + "\nممکن است سفارش قبل از لغو اجرا شده باشد.") },
            confirmButton = { Button(enabled = enabled && !busy && !uncertain, onClick = {
                cancelling = null
                run(true) { c ->
                    BitunixClient.cancel(c, o)
                    message = "درخواست لغو پذیرفته شد؛ وضعیت نهایی را بررسی کنید."
                    try { reload(c) } catch (e: CancellationException) { throw e } catch (_: Exception) { message += " دریافت وضعیت جدید ناموفق بود." }
                }
            }) { Text("تأیید لغو") } },
            dismissButton = { TextButton(onClick = { cancelling = null }) { Text("انصراف") } })
    }
}
