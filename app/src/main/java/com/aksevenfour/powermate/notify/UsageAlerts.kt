package com.aksevenfour.powermate.notify

import android.content.Context
import com.aksevenfour.powermate.data.AppSettings
import com.aksevenfour.powermate.ui.Format
import java.time.LocalDateTime

/**
 * Watches live power for sustained high draw, and for heavy use while the plan's most expensive
 * rate applies. Only works while a live connection is running (app open, or background monitoring).
 */
class UsageAlerts(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val high = Sustained()
    private val peak = Sustained()

    fun onLivePower(watts: Double, settings: AppSettings) {
        val alerts = settings.alerts
        val tariff = settings.tariff
        val now = clock()
        if (high.update(alerts.highUsage && watts >= alerts.highUsageWatts, now)) {
            val rate = tariff.rateAt(LocalDateTime.now())
            Notifier.post(
                context,
                Notifier.CHANNEL_USAGE,
                Notifier.ID_HIGH_USAGE,
                "Using ${Format.power(watts)} right now",
                "That's about ${Format.money(watts / 1000 * rate.centsPerKwh)} an hour at the ${rate.name} rate.",
            )
        }
        val rate = tariff.rateAt(LocalDateTime.now())
        val dearest = tariff.isTimeOfUse && rate.centsPerKwh == tariff.allRates.maxOf { it.centsPerKwh }
        if (peak.update(alerts.peakUsage && dearest && watts >= alerts.peakUsageWatts, now)) {
            val next = tariff.nextChange(LocalDateTime.now())
            Notifier.post(
                context,
                Notifier.CHANNEL_USAGE,
                Notifier.ID_PEAK_USAGE,
                "${Format.power(watts)} during ${rate.name}",
                "Power costs ${Format.rate(rate.centsPerKwh)} right now" +
                    (next?.let { ", until ${com.aksevenfour.powermate.ui.components.TariffText.time(it.hour * 60 + it.minute, isEnd = true)}" } ?: "") +
                    ". Can anything wait?",
            )
        }
    }

    /** True once a condition has held for [SUSTAIN_MS], at most once per [COOLDOWN_MS]. */
    private class Sustained {
        private var since: Long? = null
        private var lastAlert = Long.MIN_VALUE / 2

        fun update(condition: Boolean, now: Long): Boolean {
            if (!condition) {
                since = null
                return false
            }
            val start = since ?: now.also { since = it }
            if (now - start < SUSTAIN_MS || now - lastAlert < COOLDOWN_MS) return false
            lastAlert = now
            return true
        }
    }

    private companion object {
        const val SUSTAIN_MS = 2 * 60_000L
        const val COOLDOWN_MS = 30 * 60_000L
    }
}
