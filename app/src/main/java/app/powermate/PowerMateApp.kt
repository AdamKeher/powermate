package app.powermate

import android.app.Application
import android.content.Context
import app.powermate.ble.DiagnosticLog
import app.powermate.ble.PowerpalManager
import app.powermate.ble.PowerpalProtocol
import app.powermate.ble.PowerpalScanner
import app.powermate.data.AppDatabase
import app.powermate.data.DataTools
import app.powermate.data.SettingsRepository
import app.powermate.data.UsageRepository
import app.powermate.notify.InsightsScheduler
import app.powermate.notify.LiveMonitorService
import app.powermate.notify.Notifier
import app.powermate.notify.PriceAlertScheduler
import app.powermate.notify.RateReminderScheduler
import app.powermate.notify.UsageAlerts
import app.powermate.prices.PriceRepository
import app.powermate.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PowerMateApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

/** Hand-wired dependencies; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val context: Context = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val log = DiagnosticLog()
    val database = AppDatabase.build(this.context)
    val settings = SettingsRepository(this.context)
    val powerpal = PowerpalManager(this.context, settings, database.readings(), log)
    val scanner = PowerpalScanner(this.context)
    val usage = UsageRepository(database.readings())
    val dataTools = DataTools(this.context, database.readings(), log)
    val prices = PriceRepository(log)
    private val usageAlerts = UsageAlerts(this.context)

    fun start() {
        Notifier.createChannels(context)
        applyBackgroundSync()
        watchAlerts()
    }

    fun applyBackgroundSync() {
        scope.launch {
            val current = settings.current()
            SyncScheduler.apply(context, current.backgroundSync && current.device != null)
        }
    }

    /** Keeps scheduled notifications in step with the plan and the user's alert choices. */
    private fun watchAlerts() {
        scope.launch {
            settings.settings.map { Triple(it.tariff, it.alerts, it.device != null) }.distinctUntilChanged().collect { (tariff, alerts, paired) ->
                RateReminderScheduler.reschedule(context, tariff, alerts)
                InsightsScheduler.apply(context, paired && (alerts.dailySummary || alerts.weeklySummary || alerts.alwaysOnCreep))
                PriceAlertScheduler.apply(context, alerts.priceSpikes)
            }
        }
        // Usage alerts ride on live reports, whichever session (screen or background service) produces them.
        scope.launch {
            combine(powerpal.live, settings.settings) { live, s -> live to s }.collect { (live, s) ->
                if (live != null) {
                    usageAlerts.onLivePower(PowerpalProtocol.wattsFromInterval(live.intervalMs, s.pulsesPerKwh), s)
                }
            }
        }
        scope.launch {
            settings.settings.map { Triple(it.battery, it.batteryAlertedAt, it.alerts.batteryLow) }.distinctUntilChanged()
                .collect { (battery, alertedAt, enabled) ->
                    if (battery == null) return@collect
                    when {
                        battery > BATTERY_RECOVERED -> if (alertedAt != null) settings.setBatteryAlertedAt(null)
                        enabled && battery <= BATTERY_LOW && (alertedAt == null || battery <= alertedAt - BATTERY_STEP) -> {
                            Notifier.batteryLow(context, battery)
                            settings.setBatteryAlertedAt(battery)
                        }
                    }
                }
        }
    }

    // Who wants the live connection: the screen ("ui") and/or the background service. Main thread only.
    private val liveOwners = mutableSetOf<String>()
    private var liveJob: Job? = null
    private var lingerJob: Job? = null

    /** Debug builds only: simulate the live connection instead of using Bluetooth (see MainActivity). */
    var mockLive = false

    fun acquireLive(owner: String) {
        lingerJob?.cancel()
        lingerJob = null
        liveOwners += owner
        if (liveJob?.isActive != true) {
            liveJob = scope.launch {
                if (mockLive) powerpal.runMockLive(settings.current().pulsesPerKwh) else powerpal.runLive()
            }
        }
    }

    /** With [linger], the connection survives briefly so a quick app switch doesn't mean reconnecting. */
    fun releaseLive(owner: String, linger: Boolean) {
        liveOwners -= owner
        if (liveOwners.isNotEmpty()) return
        lingerJob?.cancel()
        if (!linger) {
            stopLiveJob()
            return
        }
        lingerJob = scope.launch(Dispatchers.Main) {
            delay(LIVE_LINGER_MS)
            if (liveOwners.isEmpty()) stopLiveJob()
        }
    }

    fun onForeground() = acquireLive(UI_OWNER)

    fun onBackground() = releaseLive(UI_OWNER, linger = true)

    /** Stops everything, e.g. when the device is forgotten. */
    fun stopLive() {
        liveOwners.clear()
        LiveMonitorService.stop(context)
        stopLiveJob()
    }

    private fun stopLiveJob() {
        lingerJob?.cancel()
        lingerJob = null
        liveJob?.cancel()
        liveJob = null
    }

    private companion object {
        const val UI_OWNER = "ui"
        const val LIVE_LINGER_MS = 60_000L
        const val BATTERY_LOW = 20
        const val BATTERY_STEP = 5
        const val BATTERY_RECOVERED = 50
    }
}
