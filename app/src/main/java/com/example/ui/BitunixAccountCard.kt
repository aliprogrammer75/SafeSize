package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.example.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun BitunixAccountCard() {
    val context = LocalContext.current.applicationContext
    val vault = remember(context) { BitunixVault(context) }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<BitunixSnapshot?>(null) }
    var positionsLoaded by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    fun failure(e: Exception): String = when (e) {
        is BitunixApiException -> {
            val explanation = if (e.http) when (e.code) {
                401 -> "احراز هویت HTTP ناموفق"
                403 -> "دسترسی HTTP رد شد؛ محدودیت سرویس یا شبکه را بررسی کنید"
                429 -> "تعداد درخواست‌ها زیاد است"
                else -> "پاسخ HTTP ناموفق"
            } else when (e.code) {
                403 -> "کلید مجوز این عملیات را ندارد"
                10002 -> "پارامتر درخواست نامعتبر است"
                10003 -> "کلید API خالی است"
                10004 -> "IP فعلی در فهرست مجاز کلید نیست"
                10007 -> "امضای درخواست نامعتبر است؛ کلید و Secret را بررسی کنید"
                10010 -> "طول nonce نامعتبر است"
                10011 -> "اختلاف ساعت دستگاه با صرافی؛ تاریخ و ساعت خودکار را فعال کنید"
                10005, 10006 -> "درخواست بیش از حد مجاز"
                else -> "درخواست از طرف صرافی رد شد"
            }
            e.stage + " | " + (if (e.http) "HTTP " else "API ") + e.code + " — " + explanation
        }
        is BitunixConnectionException -> e.stage + " | " + e.category + " — " + when (e.category) {
            "DNS" -> "دامنه صرافی پیدا نشد"
            "TIMEOUT" -> "زمان پاسخ به پایان رسید"
            "TLS" -> "اتصال امن برقرار نشد"
            "CONNECT", "NETWORK_IO" -> "ارتباط شبکه برقرار نشد"
            "ACCOUNT_USDT_MISSING" -> "حساب فیوچرز USDT در پاسخ یافت نشد"
            else -> "ساختار پاسخ صرافی با انتظار برنامه متفاوت است"
        }
        is java.security.GeneralSecurityException -> "ذخیره یا خواندن کلید | KEYSTORE"
        else -> "خطای داخلی در اتصال | " + e.javaClass.simpleName
    }
    suspend fun refresh(newCredentials: BitunixCredentials? = null) {
        if (busy) return
        busy = true
        error = null
        positionsLoaded = false
        try {
            val credentials = newCredentials ?: withContext(Dispatchers.IO) { vault.load() }
            if (credentials != null) {
                if (newCredentials == null) configured = true
                val fresh = BitunixClient.fetchAccount(credentials)
                if (newCredentials != null) {
                    try { withContext(Dispatchers.IO) { vault.save(credentials) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { throw BitunixConnectionException("ذخیره کلید", "KEYSTORE") }
                }
                configured = true
                snapshot = fresh
                edit = false
                val positions = BitunixClient.fetchPositions(credentials)
                snapshot = fresh.copy(positions = positions)
                positionsLoaded = true
            } else {
                configured = false
                snapshot = null
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = failure(e) }
        finally { busy = false }
    }
    LaunchedEffect(Unit) { refresh() }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("حساب فیوچرز Bitunix", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        when { busy -> "در حال همگام‌سازی"; error != null -> "همگام‌سازی ناموفق"; snapshot != null -> "آخرین دریافت موفق"; else -> "اتصال حساب" },
                        style = MaterialTheme.typography.labelMedium,
                        color = when { error != null -> MaterialTheme.colorScheme.error; snapshot != null -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                    )
                }
                if (configured) IconButton(enabled = !busy, onClick = { scope.launch { refresh() } }) {
                    Icon(Icons.Default.Refresh, contentDescription = "به‌روزرسانی")
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            snapshot?.let { s ->
                Text("موجودی آزاد (USDT)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                LtrAmount(s.available, MaterialTheme.colorScheme.onSurface, true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExchangeMetric("مارجین پوزیشن", s.margin, Modifier.weight(1f))
                    ExchangeMetric("مسدودی سفارش", s.frozen, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExchangeMetric("سود/زیان Cross", s.crossPnl, Modifier.weight(1f), true)
                    ExchangeMetric("سود/زیان Isolated", s.isolatedPnl, Modifier.weight(1f), true)
                }
                Text("آخرین دریافت: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.fetchedAt)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                Text("پوزیشن‌های باز صرافی", fontWeight = FontWeight.SemiBold)
                if (!positionsLoaded) Text("اطلاعات پوزیشن هنوز تأیید نشده است.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (s.positions.isEmpty()) Text("هیچ پوزیشن بازی وجود ندارد.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (positionsLoaded) s.positions.forEach { p ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(p.symbol, fontWeight = FontWeight.Bold)
                                Text(p.side + " · " + p.leverage + "x", color = if (p.side == "LONG") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("سود/زیان باز", style = MaterialTheme.typography.bodySmall)
                                LtrAmount(p.pnl, pnlColor(p.pnl))
                            }
                            Text("ورود: " + p.entry + " | مارجین: " + p.margin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (!configured && snapshot == null) {
                Text("موجودی و پوزیشن‌ها را مشاهده کنید. برای معامله، از تب معامله استفاده کنید.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { edit = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("اتصال حساب بیت‌یونیکس") }
                if (error != null) TextButton(onClick = { confirmDisconnect = true }, enabled = !busy) { Text("پاک کردن کلید ذخیره‌شده") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { edit = true }, enabled = !busy) { Text("تغییر کلید") }
                    TextButton(onClick = { confirmDisconnect = true }, enabled = !busy) { Text("قطع اتصال") }
                }
            }
            Text("اطلاعات صرافی از ژورنال دستی جداست؛ قیمت‌ها پس از به‌روزرسانی تغییر می‌کنند.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (edit) {
        var apiKey by remember { mutableStateOf("") }
        var secret by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) edit = false },
            title = { Text("اتصال امن به Bitunix") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("در صرافی کلید با مجوز خواندن و در صورت نیاز معامله بسازید. مجوز برداشت و انتقال را فعال نکنید.")
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        OutlinedTextField(secret, { secret = it }, label = { Text("Secret Key") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { Button(enabled = !busy && apiKey.isNotBlank() && secret.isNotBlank(), onClick = {
                val credentials = BitunixCredentials(apiKey.trim(), secret.trim())
                scope.launch { refresh(credentials) }
            }) { Text(if (busy) "در حال بررسی" else "آزمایش و ذخیره") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { edit = false }) { Text("لغو") } }
        )
    }
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text("قطع اتصال صرافی") },
        text = { Text("کلید ذخیره‌شده روی این دستگاه حذف می‌شود. این کار روی حساب صرافی یا ژورنال دستی اثری ندارد.") },
        confirmButton = { TextButton(onClick = {
            confirmDisconnect = false
            scope.launch {
                busy = true
                try { withContext(Dispatchers.IO) { vault.clear() }; configured = false; snapshot = null; positionsLoaded = false; error = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = failure(e) }
                finally { busy = false }
            }
        }) { Text("قطع اتصال") } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("لغو") } }
    )
}

@Composable private fun pnlColor(value: String) = when {
    (value.toBigDecimalOrNull()?.signum() ?: 0) > 0 -> MaterialTheme.colorScheme.tertiary
    (value.toBigDecimalOrNull()?.signum() ?: 0) < 0 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable private fun LtrAmount(value: String, color: androidx.compose.ui.graphics.Color, large: Boolean = false) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(value.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: "—", color = color,
            style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun ExchangeMetric(label: String, value: String, modifier: Modifier, pnl: Boolean = false) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LtrAmount(value, if (pnl) pnlColor(value) else MaterialTheme.colorScheme.onSurface)
        }
    }
}
