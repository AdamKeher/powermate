package com.aksevenfour.powermate.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aksevenfour.powermate.PowerMateApp
import com.aksevenfour.powermate.data.AlertSettings
import com.aksevenfour.powermate.data.Rate
import com.aksevenfour.powermate.data.Tariff
import com.aksevenfour.powermate.ui.Format
import com.aksevenfour.powermate.ui.components.TariffText
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/** A heads-up that the cheapest or the most expensive rate is about to begin. */
data class RateReminder(val fireAt: LocalDateTime, val rate: Rate, val startsAt: LocalDateTime, val endsAt: LocalDateTime?) {
    val title: String get() = "${rate.name} starts at ${TariffText.time(startsAt.hour * 60 + startsAt.minute)}"
    val text: String
        get() = Format.rate(rate.centsPerKwh) +
            (endsAt?.let { " until ${TariffText.time(it.hour * 60 + it.minute, isEnd = true)}" } ?: "") + "."
}

object RateReminders {
    /**
     * The next reminder due after [now]: [AlertSettings.reminderLeadMinutes] before the plan next
     * switches into its cheapest or its most expensive rate. Changes too close to warn about are skipped.
     */
    fun next(tariff: Tariff, alerts: AlertSettings, now: LocalDateTime): RateReminder? {
        if (!alerts.rateReminders || !tariff.isTimeOfUse) return null
        val prices = tariff.allRates.map { it.centsPerKwh }
        val cheapest = prices.min()
        val dearest = prices.max()
        if (cheapest == dearest) return null
        var from = now
        repeat(MAX_CHANGES_SCANNED) {
            val change = tariff.nextChange(from) ?: return null
            val rate = tariff.rateAt(change)
            if (rate.centsPerKwh == cheapest || rate.centsPerKwh == dearest) {
                val fireAt = change.minusMinutes(alerts.reminderLeadMinutes.toLong())
                if (fireAt.isAfter(now)) return RateReminder(fireAt, rate, change, tariff.nextChange(change))
            }
            from = change
        }
        return null
    }

    private const val MAX_CHANGES_SCANNED = 40
}

/** Keeps exactly one reminder alarm pending. Inexact alarms are fine: a minute or two either way. */
object RateReminderScheduler {
    fun reschedule(context: Context, tariff: Tariff, alerts: AlertSettings) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val reminder = RateReminders.next(tariff, alerts, LocalDateTime.now())
        val pending = pendingIntent(context, reminder)
        alarms.cancel(pending)
        if (reminder == null) return
        val at = reminder.fireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    private fun pendingIntent(context: Context, reminder: RateReminder?): PendingIntent {
        val intent = Intent(context, RateReminderReceiver::class.java)
        reminder?.let {
            intent.putExtra(EXTRA_TITLE, it.title)
            intent.putExtra(EXTRA_TEXT, it.text)
        }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
}

class RateReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(RateReminderScheduler.EXTRA_TITLE) ?: return
        Notifier.post(context, Notifier.CHANNEL_REMINDERS, Notifier.ID_REMINDER, title, intent.getStringExtra(RateReminderScheduler.EXTRA_TEXT).orEmpty())
        // Line up the next one.
        val container = (context.applicationContext as PowerMateApp).container
        val done = goAsync()
        container.scope.launch {
            try {
                val s = container.settings.current()
                RateReminderScheduler.reschedule(context, s.tariff, s.alerts)
            } finally {
                done.finish()
            }
        }
    }
}

/** Re-arms alarms and the background connection after a reboot, update or clock change. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as PowerMateApp).container
        val restartService = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        val done = goAsync()
        container.scope.launch {
            try {
                val s = container.settings.current()
                RateReminderScheduler.reschedule(context, s.tariff, s.alerts)
                if (restartService && s.backgroundLive && s.device != null) LiveMonitorService.start(context)
            } finally {
                done.finish()
            }
        }
    }
}
