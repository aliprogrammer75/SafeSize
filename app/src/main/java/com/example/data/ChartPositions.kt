package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

internal data class ChartTradeLevel(
    val label: String,
    val kind: String,
    val price: Double,
    val timestamp: Long = 0
)

internal data class ChartTradeSnapshot(val levels: List<ChartTradeLevel>, val status: String = "")

private fun MutableList<ChartTradeLevel>.level(label: String, kind: String, price: String, at: Long = 0) {
    val value = price.toDoubleOrNull() ?: return
    if (value.isFinite() && value > 0) add(ChartTradeLevel(label, kind, value, at))
}

internal fun ChartTradeLevel.toJson(): JSONObject =
    JSONObject().put("label", label).put("kind", kind).put("price", price).put("timestamp", timestamp)

private fun orderDirection(row: JSONObject): String = when (row.optString("side")) {
    "BUY", "LONG" -> "LONG"
    "SELL", "SHORT" -> "SHORT"
    else -> ""
}

private fun opens(row: JSONObject, position: BitunixPosition): Boolean {
    if (row.optBoolean("reduceOnly") || row.optString("tradeSide") == "CLOSE") return false
    return orderDirection(row) == position.side &&
        (row.optString("tradeSide") == "OPEN" ||
            (position.mode == "ONE_WAY" && row.has("reduceOnly") && !row.isNull("reduceOnly")))
}

internal fun chartTradeLevels(
    positions: List<BitunixPosition>,
    protection: List<JSONObject>,
    pending: List<JSONObject>,
    fills: Map<String, List<JSONObject>>
): List<ChartTradeLevel> = buildList {
    val nextStage = mutableMapOf<String, Int>()
    for (position in positions) {
        val side = if (position.side == "LONG") "لانگ" else "شورت"
        level("میانگین ورود $side", "entry", position.entry)
        val history = fills[position.id].orEmpty().filter { row ->
            opens(row, position) && row.optLong("ctime") > 0 && row.optString("price").toBigDecimalOrNull()?.signum() == 1
        }
        val stages = history
            .groupBy { it.optString("orderId").ifBlank { it.optString("tradeId") } }
            .values.filter { it.isNotEmpty() }.sortedBy { rows -> rows.minOf { it.optLong("ctime") } }
        for ((index, rows) in stages.withIndex()) {
            for (row in rows) {
                level("ورود اجراشده ${index + 1} $side", "fill", row.optString("price"), row.optLong("ctime"))
            }
        }
        nextStage[position.id] = maxOf(2, stages.size + 1)
        if (stages.isEmpty()) level("زمان باز شدن $side · میانگین فعلی", "fill", position.entry, position.openedAt)
        val orders = protection.filter { it.optString("positionId") == position.id }
        for (order in orders) {
            level("TP $side", "tp", order.optString("tpPrice"))
            level("SL $side", "sl", order.optString("slPrice"))
        }
    }
    for (order in pending.sortedBy { it.optLong("ctime") }) {
        val type = order.optString("orderType").ifBlank { order.optString("type") }
        if (type != "LIMIT" || order.optString("status") !in setOf("NEW", "PART_FILLED")) continue
        val position = positions.singleOrNull { opens(order, it) }
        val side = when (orderDirection(order)) {
            "LONG" -> " Buy"
            "SHORT" -> " Sell"
            else -> ""
        }
        val label = when {
            order.optBoolean("reduceOnly") || order.optString("tradeSide") == "CLOSE" -> "Limit خروج$side"
            position != null -> {
                val stage = nextStage.getValue(position.id)
                nextStage[position.id] = stage + 1
                "پله $stage · Limit$side"
            }
            else -> "Limit باز$side"
        }
        level(label, "pending", order.optString("price"))
        level("TP سفارش Limit$side", "pending-tp", order.optString("tpPrice"))
        level("SL سفارش Limit$side", "pending-sl", order.optString("slPrice"))
    }
}.distinct()

internal class BitunixChartPositions {
    private data class CachedFills(val qty: String, val price: String, val rows: List<JSONObject>)
    private var account: BitunixCredentials? = null
    private val fillCache = mutableMapOf<String, CachedFills>()

    suspend fun read(credentials: BitunixCredentials, symbol: String): ChartTradeSnapshot = coroutineScope {
        if (account != credentials) {
            fillCache.clear()
            account = credentials
        }
        val list = BitunixClient.fetchPositions(credentials, symbol).filter { it.symbol == symbol }
        val protection = async { partial { BitunixClient.chartProtectionOrders(credentials, symbol) } }
        val pending = async { partial { BitunixClient.chartPendingOrders(credentials, symbol) } }
        fillCache.keys.retainAll(list.map { it.id }.toSet())
        for (position in list) {
            val cached = fillCache[position.id]
            if (cached?.qty != position.qty || cached.price != position.entry) {
                partial { BitunixClient.chartFills(credentials, position) }?.let {
                    fillCache[position.id] = CachedFills(position.qty, position.entry, it)
                }
            }
        }
        val guards = protection.await()
        val orders = pending.await()
        ChartTradeSnapshot(
            chartTradeLevels(list, guards.orEmpty(), orders.orEmpty(), fillCache.mapValues { it.value.rows }),
            if (guards == null || orders == null) "بخشی از سطوح Bitunix دریافت نشد" else ""
        )
    }

    private suspend fun <T> partial(fetch: suspend () -> T): T? = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
