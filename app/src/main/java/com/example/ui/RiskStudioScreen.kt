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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.viewmodel.TradeViewModel
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

private fun number(v: Double) = String.format(Locale.US, "%,.4f", v)

@Composable
fun RiskStudioScreen(viewModel: TradeViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("risk_studio_v1", 0) }
    val closed by viewModel.closedTrades.collectAsStateWithLifecycle()
    val all by viewModel.allTrades.collectAsStateWithLifecycle()
    val equity by viewModel.totalEquity.collectAsStateWithLifecycle()
    var risk by remember { mutableStateOf(prefs.getString("risk", "1") ?: "1") }
    var daily by remember { mutableStateOf(prefs.getString("daily", "3") ?: "3") }
    var streak by remember { mutableStateOf(prefs.getString("streak", "2") ?: "2") }
    var maxTrades by remember { mutableStateOf(prefs.getString("maxTrades", "3") ?: "3") }
    var checklist by remember { mutableStateOf(prefs.getString("checklist", "ستاپ مطابق استراتژی است\nحد ضرر مشخص است\nحجم محاسبه شده است\nورود احساسی نیست") ?: "") }
    var checks by remember { mutableStateOf(setOf<Int>()) }
    var editChecks by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val tasks = checklist.lines().filter { it.isNotBlank() }
    val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val today = closed.filter { (it.closedTimestamp ?: 0L) >= start }
    val net = today.sumOf { it.pnl ?: 0.0 }
    val starting = equity - net
    val losses = closed.sortedByDescending { it.closedTimestamp ?: 0L }.takeWhile { (it.pnl ?: 0.0) < 0 }.size
    val opened = all.count { it.timestamp >= start }
    val dailyLimit = daily.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 100 }
    val lossLimit = streak.toIntOrNull()?.takeIf { it > 0 }
    val countLimit = maxTrades.toIntOrNull()?.takeIf { it > 0 }
    val exceeded = (dailyLimit != null && starting > 0 && -net / starting * 100 >= dailyLimit) ||
        (lossLimit != null && losses >= lossLimit) || (countLimit != null && opened >= countLimit)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("محافظ سرمایه", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("کنترل نظم و ریسک • بر اساس ژورنال دستی؛ هشدارها مانع معامله در صرافی نمی‌شوند.")
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (exceeded) "یکی از محدودیت‌های شما رد شده است" else "وضعیت قوانین ژورنال", color = if (exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                Text("سود/زیان خالص امروز: " + number(net) + " USDT")
                Text("ضرر متوالی: " + losses + " | معاملات ثبت‌شده امروز: " + opened)
                OutlinedTextField(risk, { risk = it }, label = { Text("سقف ریسک هر معامله (%)") }, singleLine = true)
                OutlinedTextField(daily, { daily = it }, label = { Text("سقف زیان روزانه (%)") }, singleLine = true)
                OutlinedTextField(streak, { streak = it }, label = { Text("حد ضرر متوالی (تعداد)") }, singleLine = true)
                OutlinedTextField(maxTrades, { maxTrades = it }, label = { Text("حد معاملات روزانه (تعداد)") }, singleLine = true)
                Button(onClick = {
                    val r = risk.toDoubleOrNull()
                    if (r != null && r.isFinite() && r > 0 && r <= 100 && dailyLimit != null && lossLimit != null && countLimit != null) {
                        prefs.edit().putString("risk", risk).putString("daily", daily).putString("streak", streak).putString("maxTrades", maxTrades).apply()
                        status = "قوانین ذخیره شد"
                    } else status = "مقادیر معتبر و مثبت وارد کنید"
                }) { Text("ذخیره قوانین") }
                if (status.isNotBlank()) Text(status)
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("چک‌لیست شخصی قبل از ورود", style = MaterialTheme.typography.titleLarge)
                val progress = if (tasks.isEmpty()) 0f else checks.size.toFloat() / tasks.size
                Text("تکمیل " + (progress * 100).toInt() + "٪؛ امتیاز آمادگی، نه پیش‌بینی سود")
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                tasks.forEachIndexed { i, t ->
                    Row {
                        Checkbox(checked = i in checks, onCheckedChange = { on -> checks = if (on) checks + i else checks - i })
                        Text(t, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                Row {
                    TextButton(onClick = { editChecks = true }) { Text("ویرایش موارد") }
                    TextButton(onClick = { checks = emptySet() }) { Text("شروع بررسی جدید") }
                }
            }
        }
        LadderPlanner(equity, risk.toDoubleOrNull())
    }
    if (editChecks) {
        var draft by remember { mutableStateOf(checklist) }
        AlertDialog(onDismissRequest = { editChecks = false }, title = { Text("چک‌لیست اختصاصی") },
            text = { OutlinedTextField(draft, { draft = it }, label = { Text("هر شرط در یک خط") }, minLines = 4, maxLines = 8) },
            confirmButton = { Button(onClick = { checklist = draft.lines().map { it.trim() }.filter { it.isNotBlank() }.take(12).joinToString("\n"); checks = emptySet(); prefs.edit().putString("checklist", checklist).apply(); editChecks = false }) { Text("ذخیره") } },
            dismissButton = { TextButton(onClick = { editChecks = false }) { Text("لغو") } })
    }
}

@Composable
private fun LadderPlanner(equity: Double, riskLimit: Double?) {
    var capital by remember { mutableStateOf("") }
    var risk by remember { mutableStateOf("1") }
    var stop by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf("") }
    var leverage by remember { mutableStateOf("1") }
    var long by remember { mutableStateOf(true) }
    val c = capital.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val r = risk.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 100 }
    val s = stop.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val lev = leverage.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1 }
    val raw = entries.lines().filter { it.isNotBlank() }
    val prices = raw.mapNotNull { it.trim().toDoubleOrNull()?.takeIf { v -> v.isFinite() && v > 0 } }
    val valid = c != null && r != null && s != null && lev != null && raw.size == prices.size && prices.size in 1..10 &&
        prices.all { if (long) it > s else it < s }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("طراحی ورود پله‌ای", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("تقسیم مساوی بودجه ریسک میان پله‌ها؛ فقط محاسبه، بدون ارسال سفارش.")
            Row {
                FilterChip(selected = long, onClick = { long = true }, label = { Text("Long") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = !long, onClick = { long = false }, label = { Text("Short") })
            }
            OutlinedTextField(capital, { capital = it }, label = { Text("سرمایه مبنا USDT") }, singleLine = true)
            TextButton(onClick = { if (equity.isFinite() && equity > 0) capital = equity.toString() }) { Text("استفاده از سرمایه ژورنال دستی") }
            OutlinedTextField(risk, { risk = it }, label = { Text("ریسک کل (%)") }, singleLine = true)
            OutlinedTextField(stop, { stop = it }, label = { Text("حد ضرر مشترک") }, singleLine = true)
            OutlinedTextField(entries, { entries = it }, label = { Text("قیمت هر پله (هر قیمت در یک خط)") }, minLines = 3)
            OutlinedTextField(leverage, { leverage = it }, label = { Text("اهرم محاسباتی") }, singleLine = true)
            if (valid) {
                val budget = c!! * r!! / 100
                val amounts = prices.map { budget / prices.size / abs(it - s!!) }
                val notional = prices.zip(amounts).sumOf { (p, q) -> p * q }
                val amount = amounts.sum()
                val margin = notional / lev!!
                if (listOf(budget, notional, amount, margin).all { it.isFinite() } && amount > 0) {
                    HorizontalDivider()
                    Text("ریسک نظری کل: " + number(budget) + " USDT")
                    Text("میانگین ورود: " + number(notional / amount))
                    Text("مارجین نظری: " + number(margin) + " USDT")
                    if (riskLimit != null && r > riskLimit) Text("بیشتر از سقف ریسک شخصی", color = MaterialTheme.colorScheme.error)
                    if (margin > c) Text("مارجین بیشتر از سرمایه مبناست", color = MaterialTheme.colorScheme.error)
                    prices.forEachIndexed { i, p -> Text("پله " + (i + 1) + " | قیمت " + number(p) + " | حجم " + number(amounts[i])) }
                    Text("کارمزد، لغزش، حداقل حجم و لیکوییدیشن لحاظ نشده‌اند؛ پیش از معامله بررسی شوند.", style = MaterialTheme.typography.bodySmall)
                }
            } else Text("سرمایه، ریسک، اهرم، حد ضرر و ۱ تا ۱۰ قیمت معتبر را وارد کنید.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
