package app.powermate.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.powermate.R
import app.powermate.ui.MainActivity

/** Posting notifications: one channel per kind, so each can be silenced in Android's settings. */
object Notifier {
    const val CHANNEL_REMINDERS = "rate_reminders"
    const val CHANNEL_USAGE = "usage_alerts"
    const val CHANNEL_PRICES = "price_alerts"
    const val CHANNEL_SUMMARIES = "summaries"
    const val CHANNEL_DEVICE = "device"
    const val CHANNEL_LIVE = "live_monitoring"

    const val ID_LIVE = 1
    const val ID_REMINDER = 10
    const val ID_HIGH_USAGE = 20
    const val ID_PEAK_USAGE = 21
    const val ID_ALWAYS_ON = 22
    const val ID_PRICE = 30
    const val ID_DAILY = 40
    const val ID_WEEKLY = 41
    const val ID_BATTERY = 50

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            NotificationChannel(CHANNEL_REMINDERS, "Rate reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Before your cheapest and most expensive rates begin" },
            NotificationChannel(CHANNEL_USAGE, "Usage alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "High usage, use during peak, and always-on creep" },
            NotificationChannel(CHANNEL_PRICES, "Price alerts", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Wholesale price spikes" },
            NotificationChannel(CHANNEL_SUMMARIES, "Summaries", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Daily and weekly usage summaries" },
            NotificationChannel(CHANNEL_DEVICE, "Powerpal", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Battery and device problems" },
            NotificationChannel(CHANNEL_LIVE, "Live monitoring", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while PowerMate stays connected in the background" },
        ).forEach(manager::createNotificationChannel)
    }

    fun permitted(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun post(context: Context, channel: String, id: Int, title: String, text: String) {
        if (!permitted(context)) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // checked by permitted()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun batteryLow(context: Context, level: Int) = post(
        context,
        CHANNEL_DEVICE,
        ID_BATTERY,
        "Powerpal battery at $level%",
        "Powerpal's batteries are user-replaceable. Note your pairing code before changing them.",
    )

    /** The ongoing notification shown while the background live connection runs. */
    fun live(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle("PowerMate")
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
}
