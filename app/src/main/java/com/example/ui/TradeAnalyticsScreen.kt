package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.example.data.Trade
import com.example.viewmodel.TradeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeAnalyticsScreen(viewModel: TradeViewModel) {
    val closedTrades by viewModel.closedTrades.collectAsStateWithLifecycle()
    val allStrategies by viewModel.allStrategies.collectAsStateWithLifecycle()
    val allSignalSources by viewModel.allSignalSources.collectAsStateWithLifecycle()
    var showCalendar by remember { mutableStateOf(false) }
    
    var selectedStrategy by remember { mutableStateOf("همه") }
    var strategyExpanded by remember { mutableStateOf(false) }

    var selectedSource by remember { mutableStateOf("همه") }
    var sourceExpanded by remember { mutableStateOf(false) }

    val filteredTrades = closedTrades.filter { trade ->
        (selectedStrategy == "همه" || trade.strategy == selectedStrategy) &&
        (selectedSource == "همه" || trade.signalSource == selectedSource)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("تحلیل‌ها و بینش‌ها", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        // Filters
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposedDropdownMenuBox(
                expanded = strategyExpanded,
                onExpandedChange = { strategyExpanded = !strategyExpanded },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    readOnly = true,
                    value = selectedStrategy,
                    onValueChange = { },
                    label = { Text("استراتژی", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = strategyExpanded) },
                    textStyle = MaterialTheme.typography.bodySmall
                )
                ExposedDropdownMenu(
                    expanded = strategyExpanded,
                    onDismissRequest = { strategyExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("همه") },
                        onClick = { strategyExpanded = false; selectedStrategy = "همه" }
                    )
                    allStrategies.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item) },
                            onClick = { strategyExpanded = false; selectedStrategy = item }
                        )
                    }
                }
            }

            ExposedDropdownMenuBox(
                expanded = sourceExpanded,
                onExpandedChange = { sourceExpanded = !sourceExpanded },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    readOnly = true,
                    value = selectedSource,
                    onValueChange = { },
                    label = { Text("منبع", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceExpanded) },
                    textStyle = MaterialTheme.typography.bodySmall
                )
                ExposedDropdownMenu(
                    expanded = sourceExpanded,
                    onDismissRequest = { sourceExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("همه") },
                        onClick = { sourceExpanded = false; selectedSource = "همه" }
                    )
                    allSignalSources.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item) },
                            onClick = { sourceExpanded = false; selectedSource = item }
                        )
                    }
                }
            }
        }

        // 1. PnL Chart
        Text("نمودار سود و زیان (PnL)", style = MaterialTheme.typography.titleMedium)
        PnLChart(
            trades = filteredTrades.reversed(), // Oldest to newest for plotting
            onClick = { showCalendar = true }
        )

        // 2. Best Pair Logic
        val bestPair = filteredTrades
            .groupBy { it.symbol }
            .mapValues { entry -> entry.value.sumOf { it.pnl ?: 0.0 } }
            .maxByOrNull { it.value }

        if (bestPair != null && filteredTrades.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("بهترین جفت ارز معامله شده", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(bestPair.key, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("+$${String.format("%.2f", bestPair.value)}", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF4CAF50))
                    }
                    val pairTrades = filteredTrades.filter { it.symbol == bestPair.key }
                    val pairWins = pairTrades.count { (it.pnl ?: 0.0) > 0 }
                    val pairWinRate = (pairWins.toDouble() / pairTrades.size) * 100
                    Text("نرخ برد: ${String.format("%.1f%%", pairWinRate)} در ${pairTrades.size} معامله", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 3. Psychology Analysis
        val wins = filteredTrades.filter { (it.pnl ?: 0.0) > 0 }
        val losses = filteredTrades.filter { (it.pnl ?: 0.0) <= 0 }

        val topWinReasons = wins.groupBy { it.psychologicalReason ?: "نامشخص" }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(3)

        val topLossReasons = losses.groupBy { it.psychologicalReason ?: "نامشخص" }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(3)

        if (topWinReasons.isNotEmpty()) {
            Text("عوامل موفقیت (پرتکرار)", style = MaterialTheme.typography.titleMedium)
            topWinReasons.forEach { (reason, count) ->
                ReasonProgressBar(reason, count, wins.size, Color(0xFF4CAF50))
            }
        }

        if (topLossReasons.isNotEmpty()) {
            Text("عوامل شکست (پرتکرار)", style = MaterialTheme.typography.titleMedium)
            topLossReasons.forEach { (reason, count) ->
                ReasonProgressBar(reason, count, losses.size, Color(0xFFF44336))
            }
        }
    }

    if (showCalendar) {
        CalendarDialog(trades = filteredTrades, onDismiss = { showCalendar = false })
    }
}

@Composable
fun ReasonProgressBar(reason: String, count: Int, total: Int, color: Color) {
    val percentage = if (total > 0) count.toFloat() / total else 0f
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(reason, style = MaterialTheme.typography.bodyMedium)
            Text("${(percentage * 100).toInt()}% ($count)", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(percentage)
                    .background(color)
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun PnLChart(trades: List<Trade>, onClick: () -> Unit) {
    if (trades.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Text("داده کافی برای نمایش نمودار وجود ندارد")
        }
        return
    }

    val cumulativePnl = mutableListOf<Double>()
    var currentSum = 0.0
    cumulativePnl.add(currentSum)
    trades.forEach { trade ->
        currentSum += (trade.pnl ?: 0.0)
        cumulativePnl.add(currentSum)
    }

    val maxVal = cumulativePnl.maxOrNull() ?: 0.0
    val minVal = cumulativePnl.minOrNull() ?: 0.0
    val range = maxVal - minVal
    val adjustedRange = if (range == 0.0) 100.0 else range

    val lineColor = if (currentSum >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
    val gradientColors = listOf(lineColor.copy(alpha = 0.5f), Color.Transparent)

    Canvas(modifier = Modifier
        .fillMaxWidth()
        .height(240.dp)
        .padding(vertical = 16.dp)
        .clickable(onClick = onClick)
    ) {
        val width = size.width
        val height = size.height

        val stepX = width / (cumulativePnl.size - 1).coerceAtLeast(1)

        val strokePath = Path()
        val fillPath = Path()
        
        cumulativePnl.forEachIndexed { index, pnl ->
            val x = index * stepX
            val normalizedY = 1f - ((pnl - minVal) / adjustedRange).toFloat()
            val y = normalizedY * height

            if (index == 0) {
                strokePath.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                strokePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            
            if (index == cumulativePnl.lastIndex) {
                fillPath.lineTo(x, height)
                fillPath.close()
            }
        }
        
        // Draw Fill Gradient
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = gradientColors,
                startY = 0f,
                endY = height
            )
        )

        // Draw zero line if it's within range
        if (minVal < 0 && maxVal > 0) {
            val zeroY = (1f - ((0.0 - minVal) / adjustedRange).toFloat()) * height
            drawLine(
                color = Color.LightGray.copy(alpha = 0.3f),
                start = Offset(0f, zeroY),
                end = Offset(width, zeroY),
                strokeWidth = 2f
            )
        }

        // Draw Line Stroke
        drawPath(
            path = strokePath,
            color = lineColor,
            style = Stroke(width = 8f)
        )
    }
}

@Composable
fun CalendarDialog(trades: List<Trade>, onDismiss: () -> Unit) {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    
    // Group trades by date string
    val dailyPnlMap = trades.groupBy { trade ->
        val cal = Calendar.getInstance().apply { timeInMillis = trade.closedTimestamp ?: 0 }
        sdf.format(cal.time)
    }.mapValues { entry -> 
        entry.value.sumOf { it.pnl ?: 0.0 } 
    }

    // Generate last 30 days
    val last30Days = remember {
        val dates = mutableListOf<String>()
        val cal = Calendar.getInstance()
        for (i in 0 until 30) {
            dates.add(sdf.format(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        dates.reversed() // Oldest to newest
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("تقویم عملکرد (۳۰ روز اخیر)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().height(300.dp)
            ) {
                items(last30Days) { dateStr ->
                    val pnl = dailyPnlMap[dateStr] ?: 0.0
                    val boxColor = when {
                        pnl > 0 -> Color(0xFF4CAF50).copy(alpha = 0.8f) // Green
                        pnl < 0 -> Color(0xFFF44336).copy(alpha = 0.8f) // Red
                        else -> MaterialTheme.colorScheme.surfaceVariant // Gray/Neutral
                    }
                    
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(boxColor),
                        contentAlignment = Alignment.Center
                    ) {
                        if (pnl != 0.0) {
                            val sign = if (pnl > 0) "+" else "-"
                            val absPnl = Math.abs(pnl)
                            val pnlStr = if (absPnl >= 1000) {
                                String.format("%s$%.1fk", sign, absPnl / 1000)
                            } else {
                                String.format("%s$%.0f", sign, absPnl)
                            }
                            Text(
                                text = pnlStr,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("بستن")
            }
        }
    )
}

