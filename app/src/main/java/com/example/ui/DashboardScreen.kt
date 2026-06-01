package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import com.example.data.Trade
import com.example.viewmodel.TradeViewModel

@Composable
fun DashboardScreen(viewModel: TradeViewModel) {
    val totalEquity by viewModel.totalEquity.collectAsStateWithLifecycle()
    val freeMargin by viewModel.freeMargin.collectAsStateWithLifecycle()
    val usedMargin by viewModel.usedMargin.collectAsStateWithLifecycle()
    val openTrades by viewModel.openTrades.collectAsStateWithLifecycle()
    val closedTrades by viewModel.closedTrades.collectAsStateWithLifecycle()

    var tradeToClose by remember { mutableStateOf<Trade?>(null) }
    var showEditBalanceDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header (Account Summary)
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("کل دارایی (EQUITY)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { showEditBalanceDialog = true }, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = androidx.compose.material.icons.Icons.Default.Edit, contentDescription = "Edit Balance", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$${String.format("%,.0f", totalEquity)}",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("مارجین درگیر (Locked)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("$${String.format("%,.0f", usedMargin)}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("مارجین آزاد (Available)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("$${String.format("%,.0f", freeMargin)}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { showResetDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ریست کلی برنامه (حذف تمام داده‌ها)")
                }
            }
        }

        // 4. Psychological Radar
        val lastThreeTrades = closedTrades.take(3)
        val warningReasons = listOf("FOMO", "طمع", "Overtrade")
        val warningCount = lastThreeTrades.count { warningReasons.contains(it.psychologicalReason) }

        if (warningCount >= 2) {
            Text("رادار روانشناسی", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(containerColor = Color(0x19F57F17)), // Subtle yellow fill
                border = BorderStroke(1.dp, Color(0x40F57F17)),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠️", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("هشدار سیستم", fontWeight = FontWeight.Bold, color = Color(0xFFF57F17))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "ضررهای اخیر شما بیشتر به دلایل احساسی (مثل طمع و FOMO) بوده است. قبل از معامله بعدی حتماً از ماشین حساب مدیریت ریسک استفاده کنید.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 3. Performance Pulse
        val wins = closedTrades.filter { (it.pnl ?: 0.0) > 0 }
        val losses = closedTrades.filter { (it.pnl ?: 0.0) < 0 }
        val winRate = if (closedTrades.isNotEmpty()) (wins.size.toDouble() / closedTrades.size) * 100 else 0.0
        val grossProfit = wins.sumOf { it.pnl ?: 0.0 }
        val grossLoss = losses.sumOf { Math.abs(it.pnl ?: 0.0) }
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else if (grossProfit > 0) 999.9 else 0.0

        Text("نبض عملکرد (کل)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedCard(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("سود خالص", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    val netPnl = grossProfit - grossLoss
                    Text(
                        "${if(netPnl>=0)"+" else "-"}$${String.format("%,.1f", Math.abs(netPnl))}",
                        fontWeight = FontWeight.Bold,
                        color = if (netPnl >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                    )
                }
            }
            OutlinedCard(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("فاکتور سود", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(String.format("%.2f", profitFactor), fontWeight = FontWeight.Bold)
                }
            }
            OutlinedCard(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("نرخ برد", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(String.format("%.0f%%", winRate), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }

        // 2. Quick Open Positions
        Text("پوزیشن‌های در جریان", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (openTrades.isEmpty()) {
            Text("هیچ پوزیشن فعالی ندارید.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            openTrades.take(3).forEach { trade ->
                TradeCard(
                    trade = trade,
                    onArchive = { tradeToClose = trade },
                    onDelete = { viewModel.deleteTrade(trade) },
                    onExecuteStep = { viewModel.executeStep(it) },
                    onCancelStep = { viewModel.cancelStep(it) }
                )
            }
        }
    }

    tradeToClose?.let { trade ->
        CloseTradeDialog(
            trade = trade,
            onDismiss = { tradeToClose = null },
            onConfirm = { pnl, reason ->
                viewModel.closeTrade(trade, pnl, reason)
                tradeToClose = null
            }
        )
    }

    if (showEditBalanceDialog) {
        var strBalance by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showEditBalanceDialog = false },
            title = { Text("ویرایش سرمایه کل") },
            text = {
                Column {
                    Text("سرمایه اولیه خود را وارد کنید:")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = strBalance,
                        onValueChange = { strBalance = it },
                        label = { Text("سرمایه اولیه ($)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    strBalance.toDoubleOrNull()?.let { newBalance ->
                        viewModel.updateInitialBalance(newBalance)
                    }
                    showEditBalanceDialog = false
                }) {
                    Text("ذخیره")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditBalanceDialog = false }) { Text("لغو") }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("هشدار مهم!", color = MaterialTheme.colorScheme.error) },
            text = { Text("آیا مطمئن هستید؟ با تایید این کار تمام معاملات و داده‌های قبلی شما برای همیشه پاک خواهد شد و قابل بازگشت نیست.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetAllData()
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("بله، تمام داده‌ها حذف شود")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("انصراف") }
            }
        )
    }
}
