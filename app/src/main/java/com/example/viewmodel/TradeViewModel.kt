package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Trade
import com.example.data.TradeRepository
import com.example.data.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import kotlinx.coroutines.flow.map

class TradeViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val repository = TradeRepository(database.tradeDao())
    val userPrefs = UserPreferencesRepository(application)

    init {
        // Sample data generation removed
    }

    val allTrades: StateFlow<List<Trade>> = repository.allTrades.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allStrategies: StateFlow<List<String>> = repository.allTrades.map { trades ->
        trades.mapNotNull { it.strategy }.filter { it.isNotBlank() }.distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSignalSources: StateFlow<List<String>> = repository.allTrades.map { trades ->
        trades.mapNotNull { it.signalSource }.filter { it.isNotBlank() }.distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val openTrades: StateFlow<List<Trade>> = repository.openTrades.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val closedTrades: StateFlow<List<Trade>> = repository.closedTrades.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val totalEquity: StateFlow<Double> = combine(
        userPrefs.initialBalance,
        repository.totalRealizedPnl
    ) { initial, realizedPnl ->
        initial + (realizedPnl ?: 0.0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 10000.0)

    val usedMargin: StateFlow<Double> = repository.usedMargin.combine(MutableStateFlow(0.0)) { dbUsedMargin, _ ->
        dbUsedMargin ?: 0.0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val freeMargin: StateFlow<Double> = combine(totalEquity, usedMargin) { equity, used ->
        equity - used
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 10000.0)

    fun addTrade(
        symbol: String,
        isLong: Boolean,
        riskPercent: Int,
        totalPositionSize: Double,
        entrySteps: Int,
        leverage: Int,
        lockedMargin: Double,
        strategy: String? = null,
        signalSource: String? = null
    ) {
        viewModelScope.launch {
            val trade = Trade(
                symbol = symbol,
                isLong = isLong,
                riskPercent = riskPercent,
                totalPositionSize = totalPositionSize,
                entrySteps = entrySteps,
                leverage = leverage,
                lockedMargin = lockedMargin,
                strategy = strategy,
                signalSource = signalSource
            )
            repository.insert(trade)
        }
    }

    fun closeTrade(trade: Trade, pnl: Double, psychologicalReason: String) {
        viewModelScope.launch {
            val updatedTrade = trade.copy(
                isClosed = true,
                pnl = pnl,
                psychologicalReason = psychologicalReason,
                closedTimestamp = System.currentTimeMillis()
            )
            repository.update(updatedTrade)
        }
    }

    fun executeStep(trade: Trade) {
        if (trade.executedSteps + trade.canceledSteps >= trade.entrySteps) return
        viewModelScope.launch {
            val updatedTrade = trade.copy(
                executedSteps = trade.executedSteps + 1
            )
            repository.update(updatedTrade)
        }
    }

    fun cancelStep(trade: Trade) {
        if (trade.executedSteps + trade.canceledSteps >= trade.entrySteps) return
        
        // When a step is canceled, we free up the margin it had allocated.
        // Initially, position size and margin was allocated completely.
        val stepPositionSize = trade.totalPositionSize / trade.entrySteps
        val stepLockedMargin = trade.lockedMargin / trade.entrySteps
        
        viewModelScope.launch {
            val updatedTrade = trade.copy(
                canceledSteps = trade.canceledSteps + 1,
                totalPositionSize = trade.totalPositionSize - stepPositionSize,
                lockedMargin = trade.lockedMargin - stepLockedMargin
            )
            repository.update(updatedTrade)
        }
    }

    fun deleteTrade(trade: Trade) {
        viewModelScope.launch {
            repository.deleteById(trade.id)
        }
    }

    fun updateInitialBalance(newBalance: Double) {
        viewModelScope.launch {
            userPrefs.updateInitialBalance(newBalance)
        }
    }

    fun resetAllData() {
        viewModelScope.launch {
            repository.deleteAll()
            userPrefs.updateInitialBalance(10000.0)
        }
    }
}
