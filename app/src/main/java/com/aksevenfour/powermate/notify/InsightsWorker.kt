package com.aksevenfour.powermate.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aksevenfour.powermate.PowerMateApp
import com.aksevenfour.powermate.data.Period
import com.aksevenfour.powermate.data.UsageCalculator
import com.aksevenfour.powermate.ui.Format
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Morning digest: yesterday's use, last week's on Mondays, and always-on creep. */
class InsightsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as PowerMateApp).container
        val settings = container.settings.current()
        if (settings.device == null) return Result.success()
        val alerts = settings.alerts
        val today = LocalDate.now()

        if (alerts.dailySummary) {
            val day = container.usage.summary(Period.DAY, today.minusDays(1), settings)
            if (day.hasData) {
                val top = day.byRate.maxByOrNull { it.kwh }?.takeIf { settings.tariff.isTimeOfUse && day.totalKwh > 0 }
                Notifier.post(
                    applicationContext,
                    Notifier.CHANNEL_SUMMARIES,
                    Notifier.ID_DAILY,
                    "Yesterday: ${Format.kwh(day.totalKwh)} · ${Format.money(day.totalCents)}",
                    top?.let { "${(it.kwh / day.totalKwh * 100).roundToInt()}% of it at the ${it.name} rate." } ?: "Tap for the details.",
                )
            }
        }

        if (alerts.weeklySummary && today.dayOfWeek == DayOfWeek.MONDAY) {
            val lastWeek = container.usage.summary(Period.WEEK, today.minusWeeks(1), settings)
            val weekBefore = container.usage.summary(Period.WEEK, today.minusWeeks(2), settings)
            if (lastWeek.hasData) {
                val change = if (weekBefore.totalKwh > 0) (lastWeek.totalKwh - weekBefore.totalKwh) / weekBefore.totalKwh * 100 else null
                Notifier.post(
                    applicationContext,
                    Notifier.CHANNEL_SUMMARIES,
                    Notifier.ID_WEEKLY,
                    "Last week: ${Format.kwh(lastWeek.totalKwh)} · ${Format.money(lastWeek.totalCents)}",
                    change?.let { "${abs(it).roundToInt()}% ${if (it < 0) "less" else "more"} than the week before." } ?: "Tap for the details.",
                )
            }
        }

        if (alerts.alwaysOnCreep) {
            val now = System.currentTimeMillis() / 1000 / 900 * 900
            val week = 7 * 86_400L
            val dao = container.database.readings()
            val recent = UsageCalculator.alwaysOnWatts(dao.quarterHours(now - week, now), settings.pulsesPerKwh)
            val before = UsageCalculator.alwaysOnWatts(dao.quarterHours(now - 2 * week, now - week), settings.pulsesPerKwh)
            if (recent != null && before != null && recent - before >= CREEP_MIN_WATTS && recent >= before * CREEP_RATIO) {
                Notifier.post(
                    applicationContext,
                    Notifier.CHANNEL_USAGE,
                    Notifier.ID_ALWAYS_ON,
                    "Always-on use is up to ${Format.power(recent)}",
                    "It was ${Format.power(before)} the week before. Something new may be running around the clock.",
                )
            }
        }
        return Result.success()
    }

    private companion object {
        const val CREEP_MIN_WATTS = 50.0
        const val CREEP_RATIO = 1.3
    }
}

object InsightsScheduler {
    private const val WORK = "insights"
    private const val HOUR = 8

    fun apply(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) {
            manager.cancelUniqueWork(WORK)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(HOUR, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<InsightsWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
