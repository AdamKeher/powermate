package com.aksevenfour.powermate.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aksevenfour.powermate.PowerMateApp
import com.aksevenfour.powermate.ble.LinkStatus
import com.aksevenfour.powermate.ble.PowerpalProtocol
import com.aksevenfour.powermate.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Keeps the Powerpal connected while the app is closed, so live usage alerts keep working. Android
 * requires an ongoing notification for this; it shows live power. Opt-in, as it costs some battery.
 */
class LiveMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            Notifier.ID_LIVE,
            Notifier.live(this, "Connecting to your Powerpal…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        val container = (application as PowerMateApp).container
        container.acquireLive(OWNER)
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            while (true) {
                val settings = container.settings.settings.first()
                val link = container.powerpal.link.value
                val live = container.powerpal.live.value
                val text = when {
                    link.status == LinkStatus.LIVE && live != null -> {
                        val watts = PowerpalProtocol.liveWatts(live.intervalMs, System.currentTimeMillis() - live.receivedAtMillis, settings.pulsesPerKwh)
                        val rate = settings.tariff.rateAt(LocalDateTime.now())
                        "${Format.power(watts)} · ${rate.name} · ≈ ${Format.money(watts / 1000 * rate.centsPerKwh)}/hr"
                    }
                    link.status == LinkStatus.WAITING -> "Waiting for the Powerpal to come into range"
                    link.status == LinkStatus.ERROR -> link.message ?: "Can't reach the Powerpal"
                    else -> "Connecting to your Powerpal…"
                }
                if (Notifier.permitted(this@LiveMonitorService)) {
                    @Suppress("MissingPermission")
                    androidx.core.app.NotificationManagerCompat.from(this@LiveMonitorService)
                        .notify(Notifier.ID_LIVE, Notifier.live(this@LiveMonitorService, text))
                }
                delay(UPDATE_MS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        (application as PowerMateApp).container.releaseLive(OWNER, linger = false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val OWNER = "service"
        private const val UPDATE_MS = 5_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LiveMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveMonitorService::class.java))
        }
    }
}
