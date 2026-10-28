package app.powermate.notify

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.powermate.PowerMateApp
import app.powermate.prices.PriceAnalysis
import app.powermate.ui.components.TariffText
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Every half hour: warns once about each stretch of high prices starting within the next 90 minutes. */
class PriceAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as PowerMateApp).container
        val settings = container.settings.current()
        if (!settings.alerts.priceSpikes) return Result.success()
        val outlook = runCatching { container.prices.refresh(settings.amberToken) }.getOrElse { return Result.retry() }
        val now = Instant.now()
        val upcoming = outlook.upcoming(now)
        val soon = PriceAnalysis.windows(upcoming)
            .firstOrNull { !it.great && it.start.isBefore(now.plus(LOOKAHEAD)) && it.end.isAfter(now) }
        val spike = upcoming.firstOrNull { it.spike && it.start.isBefore(now.plus(LOOKAHEAD)) }
        val start = soon?.start ?: spike?.start ?: return Result.success()

        val state = applicationContext.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        if (state.getLong(LAST_WARNED, 0) == start.epochSecond) return Result.success()
        state.edit { putLong(LAST_WARNED, start.epochSecond) }

        fun clock(i: Instant) = i.atZone(ZoneId.systemDefault()).let { TariffText.time(it.hour * 60 + it.minute) }
        val title = if (soon != null) "High prices ${clock(soon.start)} – ${clock(soon.end)}" else "Price spike from ${clock(start)}"
        val text = if (soon != null) {
            "Around ${soon.averageCents.roundToInt()}¢/kWh, ${soon.percentFromAverage}% above the day's average " +
                "(${outlook.source.label.lowercase()}). Hold off on heavy loads if you can."
        } else {
            "Amber expects prices to spike. Hold off on heavy loads if you can."
        }
        Notifier.post(applicationContext, Notifier.CHANNEL_PRICES, Notifier.ID_PRICE, title, text)
        return Result.success()
    }

    private companion object {
        val LOOKAHEAD: Duration = Duration.ofMinutes(90)
        const val STATE = "price_alerts"
        const val LAST_WARNED = "last_warned_start"
    }
}

object PriceAlertScheduler {
    private const val WORK = "price-alerts"

    fun apply(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) {
            manager.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<PriceAlertWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
