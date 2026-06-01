package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trades")
data class Trade(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val symbol: String,
    val isLong: Boolean,
    val riskPercent: Int, // 1, 2, or 3
    val totalPositionSize: Double, // The dollar size of the trade
    val entrySteps: Int, // 1, 2, or 3
    val executedSteps: Int = 1, // Number of steps currently active/filled
    val canceledSteps: Int = 0, // Number of steps canceled
    val leverage: Int,
    val lockedMargin: Double, // totalPositionSize / leverage
    val timestamp: Long = System.currentTimeMillis(),
    
    val isClosed: Boolean = false,
    val pnl: Double? = null,
    val psychologicalReason: String? = null,
    val closedTimestamp: Long? = null,
    val strategy: String? = null,
    val signalSource: String? = null
)
