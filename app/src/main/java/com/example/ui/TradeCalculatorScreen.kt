package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.viewmodel.TradeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeCalculatorScreen(viewModel: TradeViewModel) {
    val freeMargin by viewModel.freeMargin.collectAsStateWithLifecycle()
    val totalEquity by viewModel.totalEquity.collectAsStateWithLifecycle()

    var symbol by remember { mutableStateOf("") }
    var stopLossPercentStr by remember { mutableStateOf("") }
    var entrySteps by remember { mutableIntStateOf(1) }

    var showConfirmDialog by remember { mutableStateOf(false) }
    var selectedRiskPercent by remember { mutableIntStateOf(1) }

    val stopLossPercent = stopLossPercentStr.toDoubleOrNull() ?: 0.0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("ماشین حساب ریسک", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("مارجین آزاد:")
            Text("$${String.format("%.2f", freeMargin)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = symbol,
            onValueChange = { symbol = it.uppercase() },
            label = { Text("نماد (مثل BTC/USDT)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = stopLossPercentStr,
            onValueChange = { stopLossPercentStr = it },
            label = { Text("حد ضرر (Stop Loss %)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Text("تعداد پله‌های ورود")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3).forEach { step ->
                FilterChip(
                    selected = (entrySteps == step),
                    onClick = { entrySteps = step },
                    label = { Text("$step پله") }
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        if (stopLossPercent > 0) {
            Spacer(Modifier.height(8.dp))
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RiskBox(1, "ریسک پایین", MaterialTheme.colorScheme.tertiary, freeMargin, stopLossPercent, entrySteps) {
                    selectedRiskPercent = 1
                    showConfirmDialog = true
                }
                RiskBox(2, "متعادل", Color(0xFFA78BFA), freeMargin, stopLossPercent, entrySteps) {
                    selectedRiskPercent = 2
                    showConfirmDialog = true
                }
                RiskBox(3, "پر ریسک", MaterialTheme.colorScheme.error, freeMargin, stopLossPercent, entrySteps) {
                    selectedRiskPercent = 3
                    showConfirmDialog = true
                }
            }
        }
    }

    if (showConfirmDialog) {
        ConfirmTradeDialog(
            symbol = symbol,
            riskPercent = selectedRiskPercent,
            freeMargin = freeMargin,
            stopLossPercent = stopLossPercent,
            entrySteps = entrySteps,
            onDismiss = { showConfirmDialog = false },
            onConfirm = { isLong, leverage, lockedMargin, positionSize ->
                viewModel.addTrade(symbol.ifEmpty { "UNKNOWN" }, isLong, selectedRiskPercent, positionSize, entrySteps, leverage, lockedMargin)
                showConfirmDialog = false
                symbol = ""
                stopLossPercentStr = ""
                entrySteps = 1
            }
        )
    }
}

@Composable
fun RiskBox(
    riskPercent: Int,
    label: String,
    accentColor: Color,
    freeMargin: Double,
    stopLossPercent: Double,
    entrySteps: Int,
    onClick: () -> Unit
) {
    val riskDollar = freeMargin * (riskPercent / 100.0)
    val positionSize = if (stopLossPercent > 0) riskDollar / (stopLossPercent / 100.0) else 0.0

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accentColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info, 
                    contentDescription = label,
                    tint = accentColor
                )
                Text("$label ($riskPercent%)", fontWeight = FontWeight.Bold, color = accentColor, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("حجم کل پوزیشن", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$${String.format("%,.0f", positionSize)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text("$${String.format("%,.2f", riskDollar)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
            
            if (entrySteps > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                val stepSize = positionSize / entrySteps
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("حجم هر پله", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$entrySteps x $${String.format("%,.0f", stepSize)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun ConfirmTradeDialog(
    symbol: String,
    riskPercent: Int,
    freeMargin: Double,
    stopLossPercent: Double,
    entrySteps: Int,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, Int, Double, Double) -> Unit
) {
    var leverage by remember { mutableFloatStateOf(10f) }
    val leverageInt = leverage.toInt()
    var isLong by remember { mutableStateOf(true) }
    
    val riskDollar = freeMargin * (riskPercent / 100.0)
    val positionSize = if (stopLossPercent > 0) riskDollar / (stopLossPercent / 100.0) else 0.0
    val calcMargin = positionSize / leverageInt
    val isMarginOk = calcMargin <= freeMargin

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تایید موقعیت") },
        text = {
            Column {
                Text("نماد: ${if(symbol.isEmpty()) "نامشخص" else symbol}")
                Text("مقدار ریسک: $riskPercent% ($${String.format("%.2f", riskDollar)})")
                Text("حجم کل پوزیشن: $${String.format("%,.2f", positionSize)}")
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("جهت معامله: ")
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = isLong, onClick = { isLong = true }, label = { Text("LONG") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = !isLong, onClick = { isLong = false }, label = { Text("SHORT") })
                }
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${leverageInt}x", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("تنظیم اهرم (Leverage)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = leverage,
                    onValueChange = { leverage = it },
                    valueRange = 1f..100f
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("1x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    Text("25x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    Text("50x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    Text("75x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    Text("100x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
                Text("مارجین مورد نیاز: $${String.format("%,.2f", calcMargin)}")
                if (!isMarginOk) {
                    Text("خطا: مارجین آزاد کافی نیست!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(isLong, leverageInt, calcMargin, positionSize) }, enabled = isMarginOk && leverageInt > 0) {
                Text("تایید")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}
