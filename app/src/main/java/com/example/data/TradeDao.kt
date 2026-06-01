package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TradeDao {
    @Query("SELECT * FROM trades ORDER BY timestamp DESC")
    fun getAllTrades(): Flow<List<Trade>>
    
    @Query("SELECT * FROM trades WHERE isClosed = 0 ORDER BY timestamp DESC")
    fun getOpenTrades(): Flow<List<Trade>>

    @Query("SELECT * FROM trades WHERE isClosed = 1 ORDER BY closedTimestamp DESC")
    fun getClosedTrades(): Flow<List<Trade>>

    @Query("SELECT SUM(lockedMargin) FROM trades WHERE isClosed = 0")
    fun getUsedMargin(): Flow<Double?>
    
    @Query("SELECT SUM(pnl) FROM trades WHERE isClosed = 1")
    fun getTotalRealizedPnl(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrade(trade: Trade)

    @Update
    suspend fun updateTrade(trade: Trade)

    @Query("DELETE FROM trades WHERE id = :id")
    suspend fun deleteTradeById(id: Int)

    @Query("DELETE FROM trades")
    suspend fun deleteAllTrades()
}
