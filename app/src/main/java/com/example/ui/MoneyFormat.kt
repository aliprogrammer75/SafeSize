package com.example.ui
import java.math.BigDecimal
import java.math.RoundingMode
internal fun money(n: BigDecimal): String = n.setScale(2,RoundingMode.HALF_UP).toPlainString()
internal fun money(n: String): String = n.toBigDecimalOrNull()?.let { money(it) } ?: "—"
internal fun money(n: Double): String = if(n.isFinite()) money(BigDecimal.valueOf(n)) else "—"
internal fun moneyWithUnit(n: String): String {
    val parts = n.trim().split(" ",limit=2)
    return parts[0].toBigDecimalOrNull()?.let { money(it) + if(parts.size>1) " "+parts[1] else "" } ?: "—"
}
