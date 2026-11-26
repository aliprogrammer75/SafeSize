package com.example.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import androidx.work.*
import com.example.data.*
import com.example.viewmodel.TradeViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

@Serializable
private data class LocalBackup(val schema: Int = 1, val trades: List<Trade>, val initialBalance: Double, val risk: Map<String, String>)
private val json = Json { ignoreUnknownKeys = false }

@Composable
fun ProductToolsScreen(viewModel: TradeViewModel, onRisk: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val closed by viewModel.closedTrades.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences("risk_studio_v1", 0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf<LocalBackup?>(null) }
    var notifications by remember { mutableStateOf(prefs.getBoolean("notifications", false)) }
    val keys = listOf("risk", "daily", "streak", "maxTrades", "checklist")
    fun schedule(on: Boolean) {
        val wm = WorkManager.getInstance(context)
        prefs.edit().putBoolean("notifications", on).apply()
        notifications = on
        if (on) wm.enqueueUniquePeriodicWork("risk_guard_v1", ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<RiskReminderWorker>(15, TimeUnit.MINUTES).build())
        else wm.cancelUniqueWork("risk_guard_v1")
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) schedule(true) else status = "مجوز اعلان داده نشد"
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val b = LocalBackup(trades = db.tradeDao().getAllTrades().first(), initialBalance = UserPreferencesRepository(context).initialBalance.first(),
                        risk = keys.mapNotNull { k -> prefs.getString(k, null)?.let { k to it } }.toMap())
                    withContext(Dispatchers.IO) { (context.contentResolver.openOutputStream(uri) ?: error("No stream")).use { it.write(json.encodeToString(b).toByteArray(Charsets.UTF_8)) } }
                    status = "نسخه پشتیبان ژورنال دستی ذخیره شد"
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = "ذخیره ناموفق بود" }
                finally { busy = false }
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    val b = withContext(Dispatchers.IO) {
                        val bytes = (context.contentResolver.openInputStream(uri) ?: error("No stream")).use { input ->
                            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 10_000_000); out.write(buffer, 0, n) }
                            out.toByteArray()
                        }
                        json.decodeFromString<LocalBackup>(String(bytes, Charsets.UTF_8))
                    }
                    require(b.schema == 1 && b.trades.size <= 50000 && b.initialBalance.isFinite() && b.initialBalance >= 0)
                    require(b.trades.all { it.symbol.isNotBlank() && it.symbol.length <= 100 && it.entrySteps > 0 && it.leverage > 0 && it.riskPercent >= 0 && it.totalPositionSize.isFinite() && it.totalPositionSize >= 0 && it.lockedMargin.isFinite() && it.lockedMargin >= 0 && (it.pnl == null || it.pnl.isFinite()) })
                    restore = b
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = "فایل پشتیبان نامعتبر یا ناسازگار است" }
                finally { busy = false }
            }
        }
    }
    val known = closed.filter { it.pnl != null && it.pnl.isFinite() }
    val wins = known.count { it.pnl!! > 0 }
    val losses = known.count { it.pnl!! < 0 }
    val tagged = known.count { !it.strategy.isNullOrBlank() }
    val emotional = known.count { it.psychologicalReason in listOf("FOMO", "طمع", "Overtrade") }
    val riskLimit = prefs.getString("risk", "1")?.toDoubleOrNull() ?: 1.0
    val compliant = known.count { it.riskPercent <= riskLimit }
    val score = if (known.isEmpty()) null else ((compliant + tagged + known.count { !it.psychologicalReason.isNullOrBlank() }) * 100 / (known.size * 3))
    val strategies = known.filter { !it.strategy.isNullOrBlank() }.groupBy { it.strategy!! }.entries.sortedByDescending { it.value.sumOf { t -> t.pnl!! } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ابزارهای رشد معامله‌گر", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Button(onClick = onRisk, modifier = Modifier.fillMaxWidth()) { Text("محافظ سرمایه و ورود پله‌ای") }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("امتیاز نظم ژورنال", style = MaterialTheme.typography.titleLarge)
                Text(score?.let { "$it / 100" } ?: "داده کافی نیست", style = MaterialTheme.typography.displaySmall)
                if (score != null) LinearProgressIndicator(progress = { score / 100f }, modifier = Modifier.fillMaxWidth())
                Text("براساس ریسک ثبت‌شده در محدوده سقف فعلی، ثبت استراتژی و ثبت دلیل خروج. فقط ژورنال دستی؛ معیار سودآوری یا سلامت روان نیست.")
                Text("رعایت سقف ریسک: $compliant از " + known.size + " | استراتژی ثبت‌شده: $tagged")
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("مربی آفلاین مبتنی بر قواعد", style = MaterialTheme.typography.titleLarge)
                Text("بدون مدل AI، بدون ارسال اطلاعات به سرور و بدون سیگنال خرید یا فروش.")
                if (known.size < 10) Text("برای نتیجه قابل اتکاتر، ابتدا معاملات بیشتری ثبت کنید.")
                Text("معاملات سودده: $wins | زیان‌ده: $losses | دلایل احساسی ثبت‌شده: $emotional")
                if (tagged < known.size) Text("پیشنهاد: نام ستاپ معاملات بدون استراتژی را تکمیل کنید.")
                if (compliant < known.size) Text("ریسک معاملات بالاتر از سقف فعلی را مرور کنید.")
                strategies.take(5).forEach { (name, trades) ->
                    Text(name + " • " + trades.size + " معامله • PnL: " + String.format(java.util.Locale.US, "%.2f", trades.sumOf { it.pnl!! }))
                }
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("پشتیبان‌گیری و بازیابی", style = MaterialTheme.typography.titleLarge)
                Text("ژورنال دستی، سرمایه اولیه و تنظیمات ریسک؛ کلید API و تاریخچه صرافی در فایل قرار نمی‌گیرند. فایل JSON رمز نشده است.")
                Button(enabled = !busy, onClick = { save.launch("SafeSize-backup.json") }) { Text("ذخیره پشتیبان") }
                OutlinedButton(enabled = !busy, onClick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("بازیابی از فایل") }
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("اعلان محافظ سرمایه", style = MaterialTheme.typography.titleLarge)
                Row {
                    Switch(checked = notifications, onCheckedChange = { on ->
                        if (!on) schedule(false)
                        else if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else schedule(true)
                    })
                    Text("بررسی دوره‌ای ژورنال دستی", modifier = Modifier.padding(12.dp))
                }
                Text("حداقل فاصله ۱۵ دقیقه؛ زمان اجرا تابع محدودیت‌های اندروید است. هشدار لحظه‌ای صرافی نیست.")
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status)
    }
    restore?.let { b ->
        AlertDialog(onDismissRequest = { restore = null }, title = { Text("بازیابی ژورنال دستی") },
            text = { Text("تعداد رکوردهای فایل: " + b.trades.size + "\nرکوردهای مشابه وارد نمی‌شوند؛ موجودی و قوانین فعلی تغییر نمی‌کنند. هیچ داده‌ای حذف نمی‌شود.") },
            confirmButton = { Button(onClick = {
                restore = null; busy = true
                scope.launch {
                    try {
                        val db = AppDatabase.getDatabase(context)
                        db.withTransaction {
                            val dao = db.tradeDao()
                            val existing = dao.getAllTrades().first().map { it.copy(id = 0) }.toMutableSet()
                            b.trades.forEach { trade -> if (existing.add(trade.copy(id = 0))) dao.insertTrade(trade.copy(id = 0)) }
                        }
                        status = "بازیابی ادغامی ژورنال انجام شد؛ سرمایه و قوانین فعلی حفظ شدند."
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { status = "بازیابی ناموفق بود" }
                    finally { busy = false }
                }
            }) { Text("ادغام رکوردها") } }, dismissButton = { TextButton(onClick = { restore = null }) { Text("لغو") } })
    }
}
