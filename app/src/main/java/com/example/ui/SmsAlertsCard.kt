package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SmsAlertsCard() {
    val context = LocalContext.current.applicationContext
    val settings = remember { SmsAlertSettings(context) }
    val scope = rememberCoroutineScope()
    var connection by remember { mutableStateOf<SmsAlertConnection?>(null) }
    var state by remember { mutableStateOf<SmsAlertStatus?>(null) }
    var setup by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    suspend fun refresh(selected: SmsAlertConnection) {
        state = SmsAlerts.list(selected)
        message = ""
    }

    fun run(failure: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: SmsAlertException) { message = e.message.orEmpty() }
            catch (_: Exception) { message = failure }
            finally { busy = false }
        }
    }

    LaunchedEffect(Unit) {
        run("دریافت وضعیت سرور پیامک ناموفق بود") {
            connection = withContext(Dispatchers.IO) { settings.load() }
            connection?.let { refresh(it) }
        }
    }

    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("هشدار پیامکی • ملی‌پیامک", style = MaterialTheme.typography.titleMedium)
            Text(when {
                state?.mode == "live" -> "ارسال واقعی فعال است"
                state?.mode == "dry-run" -> "حالت آزمایشی · نتیجه در سرور ثبت می‌شود"
                connection != null -> "برای مشاهده وضعیت، سرور را بررسی کنید"
                else -> "برای فعال شدن گزینهٔ پیامک روی چارت، سرور هشدار را متصل کنید."
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { setup = true }) {
                    Text(if (connection == null) "اتصال سرور پیامک" else "تنظیم اتصال")
                }
                connection?.let { connected ->
                    TextButton(enabled = !busy, onClick = {
                        run("دریافت وضعیت سرور پیامک ناموفق بود") { refresh(connected) }
                    }) { Text("به‌روزرسانی") }
                }
            }
            state?.alerts.orEmpty().forEach { alert ->
                val source = if (alert.priceType == "MARK_PRICE") "Mark" else "Last"
                val direction = if (alert.condition == "ABOVE") "≥" else "≤"
                Text("${alert.symbol} · $source $direction ${alert.price}")
                Row {
                    Text(when (alert.status) {
                        "armed" -> "در انتظار قیمت"
                        "queued", "sending" -> "در صف ارسال"
                        "simulated" -> "آزمایش موفق"
                        "accepted" -> "پذیرفته‌شده توسط ملی‌پیامک"
                        "failed" -> "ارسال ناموفق"
                        "uncertain" -> "نتیجه ارسال نامشخص"
                        "cancelled" -> "متوقف"
                        else -> alert.status
                    }, modifier = Modifier.weight(1f))
                    if (alert.status in setOf("armed", "queued")) {
                        TextButton(enabled = !busy, onClick = {
                            connection?.let { connected ->
                                run("توقف هشدار ناموفق بود") {
                                    SmsAlerts.cancel(connected, alert.id)
                                    refresh(connected)
                                }
                            }
                        }) { Text("توقف") }
                    }
                }
            }
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        }
    }
    if (setup) {
        var address by remember { mutableStateOf(connection?.address.orEmpty()) }
        var token by remember { mutableStateOf(connection?.token.orEmpty()) }
        val selected = SmsAlertConnection.parse(address, token)
        AlertDialog(
            onDismissRequest = { if (!busy) setup = false },
            title = { Text("اتصال سرور پیامک") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(address, { address = it }, label = { Text("آدرس HTTPS سرور") }, singleLine = true)
                    OutlinedTextField(token, { token = it }, label = { Text("توکن سرور SafeSize") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(enabled = !busy && selected != null, onClick = {
                    val item = selected ?: return@Button
                    run("اتصال به سرور پیامک برقرار نشد") {
                        val checked = SmsAlerts.list(item)
                        withContext(Dispatchers.IO) { settings.save(item) }
                        connection = item
                        state = checked
                        message = ""
                        setup = false
                    }
                }) { Text("بررسی و ذخیره") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { setup = false }) { Text("بستن") } }
        )
    }
}
