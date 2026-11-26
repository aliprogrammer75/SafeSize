package com.example.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.data.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.Locale

@Composable
fun WeexTradingCard() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val pending = remember { context.getSharedPreferences("weex_pending_v1", 0) }
    var symbol by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("LONG") }
    var qty by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var stop by remember { mutableStateOf("") }
    var tp by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<WeexDraft?>(null) }
    var unresolved by remember { mutableStateOf(pending.getString("id", "") ?: "") }
    var result by remember { mutableStateOf("") }
    val valid = symbol.matches(Regex("[A-Z0-9]{2,20}USDT")) && listOf(qty, price, stop).all { it.toBigDecimalOrNull()?.signum() == 1 } &&
        (if (direction == "LONG") stop.toBigDecimalOrNull()?.let { s -> price.toBigDecimalOrNull()?.let { s < it } } == true else stop.toBigDecimalOrNull()?.let { s -> price.toBigDecimalOrNull()?.let { s > it } } == true)
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("سفارش واقعی WEEX • آزمایشی", style = MaterialTheme.typography.titleLarge)
            Text("فقط Limit با حد ضرر؛ مقدار بر حسب واحد ارز. اهرم و مارجین فعلی در صرافی را پیش از ثبت بررسی کنید. اجرای حد ضرر تضمین نمی‌شود.")
            Row { Checkbox(enabled, { enabled = it }, enabled = !busy); Text("فعال‌سازی معامله برای همین جلسه", modifier = Modifier.padding(top = 12.dp)) }
            OutlinedTextField(symbol, { symbol = it.uppercase(Locale.US).replace("/", "").trim() }, label = { Text("نماد مانند BTCUSDT") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(direction == "LONG", { direction = "LONG" }, { Text("Long") }, enabled = !busy)
                FilterChip(direction == "SHORT", { direction = "SHORT" }, { Text("Short") }, enabled = !busy)
            }
            OutlinedTextField(qty, { qty = it }, label = { Text("حجم به واحد ارز") }, singleLine = true)
            OutlinedTextField(price, { price = it }, label = { Text("قیمت Limit") }, singleLine = true)
            OutlinedTextField(stop, { stop = it }, label = { Text("حد ضرر") }, singleLine = true)
            OutlinedTextField(tp, { tp = it }, label = { Text("حد سود اختیاری") }, singleLine = true)
            Button(enabled = valid && enabled && !busy && unresolved.isBlank(), onClick = {
                draft = WeexDraft(symbol, direction, "LIMIT", qty, price, stop, tp, UUID.randomUUID().toString().replace("-", ""))
            }) { Text("پیش‌نمایش سفارش") }
            if (unresolved.isNotBlank()) {
                Text("شناسه درخواست نامشخص: " + unresolved + "؛ قبل از هر ارسال بعدی، وضعیت را در خود صرافی بررسی کنید.", color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { pending.edit().remove("id").commit(); unresolved = ""; enabled = false }) { Text("وضعیت را در صرافی بررسی کردم") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (result.isNotBlank()) Text(result)
        }
    }
    draft?.let { d -> AlertDialog(onDismissRequest = { draft = null }, title = { Text("تأیید سفارش واقعی") },
        text = { Text(d.symbol + " • " + d.position + "\nحجم: " + d.qty + "\nقیمت: " + d.price + "\nحد ضرر: " + d.stop + "\nحد سود: " + d.tp.ifBlank { "ندارد" } + "\nمطمئن شوید حجم، حداقل سفارش و اهرم را در صرافی بررسی کرده‌اید.") },
        confirmButton = { Button(enabled = !busy && enabled && unresolved.isBlank(), onClick = {
            draft = null; busy = true
            scope.launch {
                try {
                    val c = withContext(Dispatchers.IO) { BitunixVault(context, "weex").load() } ?: error("حساب متصل نیست")
                    require(withContext(Dispatchers.IO) { pending.edit().putString("id", d.id).commit() })
                    unresolved = d.id
                    val orderId = ExchangeReadClient.weexPlace(c, d)
                    require(withContext(Dispatchers.IO) { pending.edit().remove("id").commit() })
                    unresolved = ""
                    result = "سفارش پذیرفته شد: " + orderId + "؛ پذیرش به معنی اجرا نیست."
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { result = "ارسال ناموفق یا نامشخص؛ وضعیت را در صرافی بررسی کنید." }
                finally { busy = false }
            }
        }) { Text("ارسال واقعی") } }, dismissButton = { TextButton(onClick = { draft = null }) { Text("انصراف") } }) }
}
