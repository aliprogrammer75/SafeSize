package com.example.data

import kotlinx.coroutines.flow.Flow

class TradeRepository(private val tradeDao: TradeDao) {
    val allTrades: Flow<List<Trade>> = tradeDao.getAllTrades()
    val openTrades: Flow<List<Trade>> = tradeDao.getOpenTrades()
    val closedTrades: Flow<List<Trade>> = tradeDao.getClosedTrades()
    val usedMargin: Flow<Double?> = tradeDao.getUsedMargin()
    val totalRealizedPnl: Flow<Double?> = tradeDao.getTotalRealizedPnl()

    suspend fun insert(trade: Trade) = tradeDao.insertTrade(trade)
    suspend fun update(trade: Trade) = tradeDao.updateTrade(trade)
    suspend fun deleteById(id: Int) = tradeDao.deleteTradeById(id)
    suspend fun deleteAll() = tradeDao.deleteAllTrades()
}
