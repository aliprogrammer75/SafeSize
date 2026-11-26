package com.example.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.util.Calendar

class RiskReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences("risk_studio_v1", 0)
        if (!prefs.getBoolean("notifications", false)) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val trades = AppDatabase.getDatabase(context).tradeDao().getAllTrades().first()
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val closed = trades.filter { it.isClosed }.sortedByDescending { it.closedTimestamp ?: 0L }
        val today = closed.filter { (it.closedTimestamp ?: 0L) >= start }
        val pnl = today.sumOf { it.pnl ?: 0.0 }
        val initial = UserPreferencesRepository(context).initialBalance.first()
        val starting = initial + closed.filter { (it.closedTimestamp ?: 0L) < start }.sumOf { it.pnl ?: 0.0 }
        val streak = closed.takeWhile { (it.pnl ?: 0.0) < 0 }.size
        val count = trades.count { it.timestamp >= start }
        val warning = (starting > 0 && -pnl / starting * 100 >= (prefs.getString("daily", "3")?.toDoubleOrNull() ?: 3.0)) ||
            streak >= (prefs.getString("streak", "2")?.toIntOrNull() ?: 2) ||
            count >= (prefs.getString("maxTrades", "3")?.toIntOrNull() ?: 3)
        if (warning && prefs.getLong("lastRiskNotice", 0) != start) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("risk_guard", "محافظ سرمایه", NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.areNotificationsEnabled()) {
                manager.notify(1821, NotificationCompat.Builder(context, "risk_guard").setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("قوانین معاملاتی را بررسی کنید").setContentText("یکی از حدود ژورنال دستی شما رسیده است.").setAutoCancel(true).build())
                prefs.edit().putLong("lastRiskNotice", start).apply()
            }
        }
        return Result.success()
    }
}
