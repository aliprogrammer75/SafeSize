package com.example.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class MarketTracking { INACTIVE, CONNECTING, LIVE, PERIODIC }

class MarketAlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == stopAction) {
            WatchStore.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(serviceChannel, "وضعیت رصد قیمت", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
            ServiceCompat.startForeground(this, serviceId, notification(MarketTracking.CONNECTING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: IllegalStateException) {
            stopSelf()
            return START_NOT_STICKY
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        scope.launch { trackRules() }
        return START_STICKY
    }

    private suspend fun trackRules() {
        WatchStore.ruleChanges(this).map { rules ->
            if (rules.isEmpty()) null else rules.filter { it.isLivePriceRule() }
                .map { PriceMarket(it.symbol, it.priceType) }.toSet()
        }.distinctUntilChanged().collectLatest { markets ->
            if (markets == null || !MarketMonitor.canNotify(this)) {
                stopSelf()
                return@collectLatest
            }
            update(if (markets.isEmpty()) MarketTracking.PERIODIC else MarketTracking.CONNECTING)
            coroutineScope {
                if (markets.isNotEmpty()) launch { trackPrices(markets) }
                launch {
                    while (isActive) {
                        if (!MarketMonitor.canNotify(this@MarketAlertService)) {
                            stopSelf()
                            break
                        }
                        withContext(Dispatchers.IO) { MarketMonitor.checkPrices(this@MarketAlertService) }
                        delay(if (tracking.value == MarketTracking.LIVE) 20_000 else 5_000)
                    }
                }
            }
        }
    }

    private suspend fun trackPrices(markets: Set<PriceMarket>) {
        val lastByMarket = mutableMapOf<PriceMarket, Long>()
        var retryDelay = 1000L
        while (currentCoroutineContext().isActive) {
            try {
                MarketLive.prices(markets).collect { tick ->
                    if (tick.at < (lastByMarket[tick.market] ?: 0L)) return@collect
                    lastByMarket[tick.market] = tick.at
                    withContext(Dispatchers.IO) {
                        MarketMonitor.observeChartPrice(this@MarketAlertService,
                            tick.market.symbol, tick.market.priceType, tick.price)
                    }
                    retryDelay = 1000L
                    update(MarketTracking.LIVE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The price snapshot check remains active while the public socket reconnects.
            }
            update(MarketTracking.PERIODIC)
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
        }
    }

    private fun update(value: MarketTracking) {
        if (monitorState.value == value) return
        monitorState.value = value
        getSystemService(NotificationManager::class.java).notify(serviceId, notification(value))
    }

    private fun notification(status: MarketTracking): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, serviceId,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)
        val stop = PendingIntent.getService(this, serviceId,
            Intent(this, MarketAlertService::class.java).setAction(stopAction), flags)
        val detail = when (status) {
            MarketTracking.LIVE -> "قیمت زنده در پس‌زمینه بررسی می‌شود"
            MarketTracking.PERIODIC -> "در حال بررسی دوره‌ای قیمت"
            else -> "در حال اتصال به قیمت Bitunix"
        }
        return NotificationCompat.Builder(this, serviceChannel)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("رصد قیمت SafeSize")
            .setContentText(detail)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "توقف هشدارهای گوشی", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        monitorState.value = MarketTracking.INACTIVE
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val serviceChannel = "market_watch_tracking"
        private const val serviceId = 7331
        private const val stopAction = "com.example.STOP_MARKET_TRACKING"
        private val monitorState = MutableStateFlow(MarketTracking.INACTIVE)
        internal val tracking = monitorState.asStateFlow()

        internal fun startIfNeeded(context: Context): Boolean {
            if (WatchStore.activeRules(context).isEmpty() || !MarketMonitor.canNotify(context)) return false
            return try {
                // Only the visible UI starts monitoring. Promote in onStartCommand so a slow
                // activity launch cannot time out while the service callback waits to run.
                context.startService(Intent(context, MarketAlertService::class.java)) != null
            } catch (_: IllegalStateException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, MarketAlertService::class.java))
        }
    }
}
