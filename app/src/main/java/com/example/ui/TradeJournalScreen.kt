package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Trade
import com.example.viewmodel.TradeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeJournalScreen(viewModel: TradeViewModel) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    
    val openTrades by viewModel.openTrades.collectAsStateWithLifecycle()
    val closedTrades by viewModel.closedTrades.collectAsStateWithLifecycle()

    var tradeToClose by remember { mutableStateOf<Trade?>(null) }

        Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTabIndex) {
            Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }, text = { Text("پوزیشن‌های در جریان") })
            Tab(selected = selectedTabIndex == 1, onClick = { selectedTabIndex = 1 }, text = { Text("تاریخچه معاملات") })
        }
        
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("جستجوی نماد (مثل BTC)") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
        
        if (selectedTabIndex == 0) {
            val filteredOpen = openTrades.filter { it.symbol.contains(searchQuery, ignoreCase = true) }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredOpen) { trade ->
                    TradeCard(
                        trade = trade, 
                        onArchive = { tradeToClose = trade }, 
                        onDelete = { viewModel.deleteTrade(trade) },
                        onExecuteStep = { viewModel.executeStep(it) },
                        onCancelStep = { viewModel.cancelStep(it) }
                    )
                }
            }
        } else {
            var filterType by remember { mutableStateOf("ماه") }
            val cutoffTime = when (filterType) {
                "روز" -> java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
                "هفته" -> java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -7); set(java.util.Calendar.HOUR_OF_DAY, 0) }.timeInMillis
                else -> java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -30); set(java.util.Calendar.HOUR_OF_DAY, 0) }.timeInMillis
            }
            
            val timeFiltered = closedTrades.filter { (it.closedTimestamp ?: 0L) >= cutoffTime }
            val searchFiltered = timeFiltered.filter { it.symbol.contains(searchQuery, ignoreCase = true) }
            
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    HistorySummaryChart(filterType, timeFiltered) { filterType = it }
                }
                items(searchFiltered) { trade ->
                    TradeCard(trade, onArchive = null, onDelete = { viewModel.deleteTrade(trade) })
                }
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
}

@Composable
fun TradeCard(
    trade: Trade, 
    onArchive: (() -> Unit)?, 
    onDelete: () -> Unit,
    onExecuteStep: ((Trade) -> Unit)? = null,
    onCancelStep: ((Trade) -> Unit)? = null
) {
    val isWin = (trade.pnl ?: 0.0) >= 0
    val cardBorderColor = if(trade.isClosed) {
        if(isWin) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    } else {
           if(trade.isLong) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, cardBorderColor.copy(alpha = 0.5f)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(trade.symbol, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (trade.isLong) "LONG ${trade.leverage}x" else "SHORT ${trade.leverage}x",
                        color = if(trade.isLong) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    if (trade.isClosed) {
                        Text(
                            "${if (isWin) "+" else ""}$${String.format("%,.2f", trade.pnl ?: 0.0)}",
                            fontWeight = FontWeight.Bold,
                            color = if (isWin) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium
                        )
                    } else {
                        Text("ریسک: ${trade.riskPercent}%", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("حجم موقعیت", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$${String.format("%,.2f", trade.totalPositionSize)}", fontWeight = FontWeight.SemiBold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("مارجین درگیر", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$${String.format("%,.2f", trade.lockedMargin)}", fontWeight = FontWeight.SemiBold)
                }
            }
            
            if (trade.entrySteps > 1) {
                Spacer(Modifier.height(16.dp))
                var expanded by remember { mutableStateOf(false) }
                Row(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("مدیریت پله‌ها (${trade.entrySteps} پله)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Icon(imageVector = if (expanded) androidx.compose.material.icons.Icons.Default.KeyboardArrowUp else androidx.compose.material.icons.Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                if (expanded) {
                    Spacer(Modifier.height(8.dp))
                    val activeStepsCount = trade.entrySteps - trade.canceledSteps
                    val stepPositionSize = if(activeStepsCount > 0) trade.totalPositionSize / activeStepsCount else 0.0
                    Column(modifier = Modifier.fillMaxWidth()) {
                        for (i in 1..trade.entrySteps) {
                            val isCanceled = i > activeStepsCount
                            val isExecuted = !isCanceled && i <= trade.executedSteps
                            val isPending = !isCanceled && !isExecuted
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("پله $i: $${String.format("%,.2f", stepPositionSize)}", style = MaterialTheme.typography.labelSmall)
                                if (isCanceled) {
                                    Text("لغو شده", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                } else if (isExecuted) {
                                    Text("فعال", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (onExecuteStep != null) {
                                            OutlinedButton(onClick = { onExecuteStep(trade) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(28.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)) {
                                                Text("اجرا", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                                            }
                                        }
                                        if (onCancelStep != null) {
                                            OutlinedButton(onClick = { onCancelStep(trade) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(28.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
                                                Text("لغو", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!trade.strategy.isNullOrBlank() || !trade.signalSource.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!trade.strategy.isNullOrBlank()) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("استراتژی: ${trade.strategy}", fontSize = 10.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha=0.5f))
                        )
                    }
                    if (!trade.signalSource.isNullOrBlank()) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("منبع: ${trade.signalSource}", fontSize = 10.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha=0.5f))
                        )
                    }
                }
            }

            if (trade.isClosed) {
                Spacer(Modifier.height(12.dp))
                Text("دلیل بسته شدن: ${trade.psychologicalReason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f), contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) {
                    Text("حذ‌ف تاریخچه معامله")
                }
            } else if (onArchive != null) {
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onArchive, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
                        Text("بستن پوزیشن")
                    }
                    Button(onClick = onDelete, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))) {
                        Text("حذ‌ف معامله")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloseTradeDialog(
    trade: Trade,
    onDismiss: () -> Unit,
    onConfirm: (Double, String) -> Unit
) {
    var pnlStr by remember { mutableStateOf("") }
    val pnl = pnlStr.toDoubleOrNull() ?: 0.0
    val selectedReasons = remember { mutableStateListOf<String>() }
    var customNote by remember { mutableStateOf("") }
    
    val positiveReasons = listOf("پایبندی به استراتژی", "برخورد به تارگت (TP)", "ورود با تاییدیه", "نقطه ورود بهینه", "معامله در جهت روند", "مدیریت ریسک دقیق", "سیو سود پله‌ای", "ریسک‌فری (Risk-Free)", "صبر و خونسردی", "خروج دستی بموقع", "تشخیص درست الگو", "خروج قبل از خبر", "کنترل طمع", "نسبت R/R عالی", "مومنتوم قوی")
    val negativeReasons = listOf("فومو (FOMO)", "طمع", "ترید انتقامی", "جابجایی استاپ‌لاس", "ورود زودهنگام", "حجم/اهرم بالا", "معامله خلاف روند", "خروج از روی ترس", "اورترید (Overtrading)", "نوسان خبر", "خستگی/عدم تمرکز", "ترید با سیگنال", "بازار رِنج و خنثی", "تحلیل اشتباه", "تردید و تاخیر در ورود")
    
    val isWin = pnlStr.isEmpty() || pnl >= 0
    val reasons = if (isWin) positiveReasons else negativeReasons

    // Reset selected reasons when win/loss condition changes
    LaunchedEffect(isWin) {
        selectedReasons.clear()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("بستن موقعیت") },
        text = {
            Column {
                OutlinedTextField(
                    value = pnlStr,
                    onValueChange = { pnlStr = it },
                    label = { Text("سود یا زیان (دلار)") },
                    placeholder = { Text("خالی = سود یا صفر") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customNote,
                    onValueChange = { customNote = it },
                    label = { Text("یادداشت دلخواه (اختیاری)") },
                    minLines = 2,
                    maxLines = 4
                )
                Spacer(Modifier.height(16.dp))
                Text("دلایل روانشناختی و تکنیکال:", fontWeight = FontWeight.Bold, color = if (isWin) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    reasons.forEach { reason ->
                        val isSelected = selectedReasons.contains(reason)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) selectedReasons.remove(reason) else selectedReasons.add(reason)
                            },
                            label = { Text(reason, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (isWin) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                                selectedLabelColor = if (isWin) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { 
                val finalReasonStr = selectedReasons.joinToString("، ") + if (customNote.isNotBlank()) " | یادداشت: $customNote" else ""
                onConfirm(pnl, finalReasonStr) 
            }, enabled = selectedReasons.isNotEmpty() || customNote.isNotBlank()) {
                Text("تایید نهایی")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}

@Composable
fun HistorySummaryChart(currentFilter: String, trades: List<Trade>, onFilterChange: (String) -> Unit) {
    val totalStandardPnL = trades.sumOf { it.pnl ?: 0.0 }
    val winCount = trades.count { (it.pnl ?: 0.0) > 0 }
    val lossCount = trades.count { (it.pnl ?: 0.0) <= 0 }
    
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=0.2f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Filter Row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("روز", "هفته", "ماه").forEach { f ->
                    FilterChip(
                        selected = currentFilter == f,
                        onClick = { onFilterChange(f) },
                        label = { Text(f) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            // Summary Stats
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("سود/ضرر دوره", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${if(totalStandardPnL>=0)"+" else "-"}$${String.format("%,.2f", Math.abs(totalStandardPnL))}",
                        color = if (totalStandardPnL >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("تعداد معاملات", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("${trades.size} (${winCount} برد / ${lossCount} باخت)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(24.dp))
            
            // Simple Bar Chart
            SimpleBarChart(trades, currentFilter)
        }
    }
}

@Composable
fun SimpleBarChart(trades: List<Trade>, filterType: String) {
    if (trades.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text("داده‌ای وجود ندارد", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val sdf = when (filterType) {
        "روز" -> java.text.SimpleDateFormat("HH:00", java.util.Locale.US)
        else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.US)
    }
    
    val grouped = trades.groupBy { 
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = it.closedTimestamp ?: 0 }
        sdf.format(cal.time)
    }
    
    val bucketPnL = grouped.mapValues { it.value.sumOf { t -> t.pnl ?: 0.0 } }
    val maxAbsVal = bucketPnL.values.maxOfOrNull { Math.abs(it) } ?: 1.0
    val adjMax = if (maxAbsVal == 0.0) 1.0 else maxAbsVal

    val positiveColor = MaterialTheme.colorScheme.tertiary
    val negativeColor = MaterialTheme.colorScheme.error

    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        val width = size.width
        val height = size.height
        
        val zeroY = height / 2f
        drawLine(
            color = androidx.compose.ui.graphics.Color.LightGray.copy(alpha = 0.3f),
            start = androidx.compose.ui.geometry.Offset(0f, zeroY),
            end = androidx.compose.ui.geometry.Offset(width, zeroY),
            strokeWidth = 2f
        )
        
        val buckets = bucketPnL.keys.sorted() 
        val barWidth = (width / (buckets.size * 2).coerceAtLeast(2)).coerceAtMost(40f)
        
        buckets.forEachIndexed { index, key ->
            val pnl = bucketPnL[key] ?: 0.0
            val barHeight = (Math.abs(pnl) / adjMax).toFloat() * (height / 2f - 10f)
            
            val segmentWidth = width / buckets.size.coerceAtLeast(1)
            val x = index * segmentWidth + segmentWidth / 2f - barWidth / 2f
            
            val top = if (pnl >= 0) zeroY - barHeight else zeroY
            val bottom = if (pnl >= 0) zeroY else zeroY + barHeight
            
            drawRect(
                color = if (pnl >= 0) positiveColor else negativeColor,
                topLeft = androidx.compose.ui.geometry.Offset(x, top),
                size = androidx.compose.ui.geometry.Size(barWidth, (bottom - top).coerceAtLeast(2f))
            )
        }
    }
}
