package app.powermate.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.powermate.data.AlertSettings
import app.powermate.data.AppSettings
import app.powermate.notify.LiveMonitorService
import app.powermate.notify.Notifier
import app.powermate.ui.components.ScreenScaffold
import app.powermate.ui.components.SectionCard
import app.powermate.ui.components.appContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertsScreen(onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val alerts = settings.alerts
    var permitted by remember { mutableStateOf(Notifier.permitted(context)) }
    LifecycleResumeEffect(Unit) {
        permitted = Notifier.permitted(context)
        onPauseOrDispose {}
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = Notifier.permitted(context) }

    fun update(change: AlertSettings.() -> AlertSettings) {
        container.scope.launch { container.settings.setAlerts(alerts.change()) }
    }

    ScreenScaffold("Notifications", onBack = onBack) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!permitted) {
                SectionCard(title = "Notifications are off") {
                    Text("Allow PowerMate to send notifications to get the alerts below.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    }) { Text("Allow notifications") }
                }
            }

            SectionCard(title = "Your plan") {
                Toggle(
                    "Rate reminders",
                    "Before your cheapest and most expensive rates begin, e.g. free power or peak.",
                    alerts.rateReminders,
                ) { update { copy(rateReminders = it) } }
                if (alerts.rateReminders) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 15, 30, 60).forEach { minutes ->
                            FilterChip(
                                selected = alerts.reminderLeadMinutes == minutes,
                                onClick = { update { copy(reminderLeadMinutes = minutes) } },
                                label = { Text("$minutes min before") },
                            )
                        }
                    }
                }
            }

            SectionCard(title = "Live usage") {
                Toggle("High usage", "When the house draws more than this for two minutes.", alerts.highUsage) { update { copy(highUsage = it) } }
                if (alerts.highUsage) {
                    WattChips(alerts.highUsageWatts, listOf(3_000, 5_000, 7_000, 10_000)) { w -> update { copy(highUsageWatts = w) } }
                }
                Toggle(
                    "Heavy use at the dearest rate",
                    "When usage stays above this while your most expensive rate applies.",
                    alerts.peakUsage,
                ) { update { copy(peakUsage = it) } }
                if (alerts.peakUsage) {
                    WattChips(alerts.peakUsageWatts, listOf(1_000, 2_000, 3_000, 5_000)) { w -> update { copy(peakUsageWatts = w) } }
                }
                Toggle(
                    "Stay connected in the background",
                    "Live alerts need a connection to the Powerpal. With this on, PowerMate stays connected while closed " +
                        "and shows live power in an ongoing notification. It uses a little more phone battery.",
                    settings.backgroundLive,
                ) { enabled ->
                    container.scope.launch { container.settings.setBackgroundLive(enabled) }
                    if (enabled) LiveMonitorService.start(context) else LiveMonitorService.stop(context)
                }
            }

            SectionCard(title = "Insights") {
                Toggle("Always-on creep", "When your always-on load rises noticeably from the week before.", alerts.alwaysOnCreep) {
                    update { copy(alwaysOnCreep = it) }
                }
                Toggle("Daily summary", "Each morning: yesterday's use and cost.", alerts.dailySummary) { update { copy(dailySummary = it) } }
                Toggle("Weekly summary", "Monday mornings: last week compared with the week before.", alerts.weeklySummary) {
                    update { copy(weeklySummary = it) }
                }
                Toggle("Powerpal battery low", "When the Powerpal's battery drops to 20%.", alerts.batteryLow) { update { copy(batteryLow = it) } }
            }

            SectionCard(title = "Wholesale prices") {
                Toggle(
                    "Price spikes",
                    "When high Victorian wholesale prices are coming up in the next 90 minutes. Most useful on a " +
                        "wholesale plan such as Amber.",
                    alerts.priceSpikes,
                ) { update { copy(priceSpikes = it) } }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WattChips(current: Int, options: List<Int>, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { watts ->
            FilterChip(selected = current == watts, onClick = { onPick(watts) }, label = { Text("${watts / 1000.0} kW".replace(".0 ", " ")) })
        }
    }
}
