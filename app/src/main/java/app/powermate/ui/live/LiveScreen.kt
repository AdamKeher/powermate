package app.powermate.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.powermate.AppContainer
import app.powermate.ble.LinkState
import app.powermate.ble.LinkStatus
import app.powermate.ble.LivePower
import app.powermate.ble.PowerpalManager
import app.powermate.ble.PowerpalProtocol
import app.powermate.ble.Transfer
import app.powermate.data.AppSettings
import app.powermate.data.Period
import app.powermate.data.UsageSummary
import app.powermate.ui.Format
import app.powermate.ui.components.InfoRow
import app.powermate.ui.components.LinkStatusLabel
import app.powermate.ui.components.LiveMood
import app.powermate.ui.components.MeterPulse
import app.powermate.ui.components.RateSwatch
import app.powermate.ui.components.ScreenScaffold
import app.powermate.ui.components.SectionCard
import app.powermate.ui.components.Sparkline
import app.powermate.ui.components.TariffText
import app.powermate.ui.components.appContainer
import app.powermate.ui.components.liveMoodBrush
import app.powermate.ui.components.rateColor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LiveUiState(
    val link: LinkState = LinkState(),
    val transfer: Transfer? = null,
    val live: LivePower? = null,
    val history: List<LivePower> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val today: UsageSummary? = null,
    val alwaysOnWatts: Double? = null,
)

class LiveViewModel(private val container: AppContainer) : ViewModel() {
    private data class Connection(val link: LinkState, val transfer: Transfer?, val live: LivePower?, val history: List<LivePower>)
    private data class DailyFigures(val today: UsageSummary?, val alwaysOnWatts: Double?)

    private val minuteTicks = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    private val connection = combine(
        container.powerpal.link,
        container.powerpal.transfer,
        container.powerpal.live,
        container.powerpal.liveHistory,
        ::Connection,
    )

    private val daily = combine(container.settings.settings, container.usage.changes(), minuteTicks) { settings, _, _ -> settings }
        .conflate()
        .map { settings ->
            DailyFigures(
                today = container.usage.summary(Period.DAY, LocalDate.now(), settings),
                alwaysOnWatts = container.usage.alwaysOnWatts(settings.pulsesPerKwh),
            )
        }

    val state: StateFlow<LiveUiState> = combine(connection, container.settings.settings, daily) { c, settings, figures ->
        LiveUiState(c.link, c.transfer, c.live, c.history, settings, figures.today, figures.alwaysOnWatts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUiState())

    fun retry() = container.powerpal.requestSync()

    fun downloadEarlier(requested: Boolean) {
        viewModelScope.launch { container.powerpal.setEarlierHistoryRequested(requested) }
    }

    fun dismissEarlier() {
        viewModelScope.launch { container.settings.dismissEarlier() }
    }
}

@Composable
fun LiveScreen() {
    val container = appContainer()
    val viewModel: LiveViewModel = viewModel { LiveViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }

    ScreenScaffold("PowerMate", actions = { LinkStatusLabel(state.link) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionNotice(state, now, onRetry = viewModel::retry)
            LivePowerCard(state, now)
            HistoryCard(state, onDownload = viewModel::downloadEarlier, onDismiss = viewModel::dismissEarlier)
            LiveTrendCard(state, now)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionCard(Modifier.weight(1f), title = "Today") {
                    val today = state.today
                    Text(
                        today?.let { Format.kwh(it.totalKwh) } ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        today?.let { Format.money(it.totalCents) + " incl. supply" } ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SectionCard(Modifier.weight(1f), title = "Always on") {
                    val watts = state.alwaysOnWatts
                    Text(watts?.let { Format.power(it) } ?: "—", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        watts?.let { "≈ " + Format.money(state.settings.tariff.dailyUsageCents(it, LocalDate.now())) + " a day" }
                            ?: "Needs a day of data",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SectionCard(title = "Powerpal") {
                InfoRow("Battery", state.settings.battery?.let { "$it%" } ?: "—")
                InfoRow("Last synced", Format.ago(state.settings.lastSyncMillis, now))
                InfoRow("Meter rate", "${Format.number(state.settings.pulsesPerKwh)} imp/kWh")
            }
        }
    }
}

/** Out of range is routine and resolves itself; only errors the user can fix ask for action. */
@Composable
private fun ConnectionNotice(state: LiveUiState, now: Long, onRetry: () -> Unit) {
    when (state.link.status) {
        LinkStatus.WAITING -> SectionCard {
            Text("Looking for your Powerpal", style = MaterialTheme.typography.titleSmall)
            Text(
                "It's out of range or asleep. PowerMate reconnects by itself as soon as it's back, then fetches " +
                    "everything recorded in the meantime." +
                    (if (state.settings.lastSyncMillis > 0) " Last synced ${Format.ago(state.settings.lastSyncMillis, now)}." else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinkStatus.ERROR -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.link.message ?: "Can't reach your Powerpal",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
        else -> Unit
    }
}

/** Background downloads in progress, or the offer to fetch readings from before pairing. */
@Composable
private fun HistoryCard(state: LiveUiState, onDownload: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val transfer = state.transfer
    val settings = state.settings
    when {
        transfer?.kind == Transfer.Kind.CATCH_UP -> SectionCard(title = "Catching up") {
            LinearProgressIndicator(progress = { transfer.progress }, modifier = Modifier.fillMaxWidth())
            Text(
                "Fetching readings recorded while PowerMate was away. Live power keeps working meanwhile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        settings.earlierRequested && settings.earlierAvailable -> SectionCard(title = "Downloading earlier readings") {
            LinearProgressIndicator(progress = { transfer?.progress ?: settings.earlierProgress }, modifier = Modifier.fillMaxWidth())
            Text(
                if (transfer == null) "Paused until the Powerpal is in range. It carries on by itself, even with the app closed."
                else "Newest first, in the background. Live power keeps working meanwhile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onDownload(false) }) { Text("Stop") }
        }
        settings.earlierAvailable && !settings.earlierDismissed -> SectionCard(title = "Earlier readings available") {
            Text(
                "Your Powerpal still holds " + String.format(java.util.Locale.getDefault(), "%.1f", settings.earlierDays) +
                    " days of readings from before PowerMate was set up. Download them in the background to fill in " +
                    "your history and today's total.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onDownload(true) }) { Text("Download") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}

@Composable
private fun LivePowerCard(state: LiveUiState, now: Long) {
    val live = state.live
    val connected = state.link.status == LinkStatus.LIVE
    // Only decay while connected; offline, show the last report as it was.
    val watts = live?.let {
        val age = if (connected) now - it.receivedAtMillis else 0L
        PowerpalProtocol.liveWatts(it.intervalMs, age, state.settings.pulsesPerKwh)
    }
    val tariff = state.settings.tariff
    val minute = now / 60_000
    val (rateIndex, nextChange) = remember(tariff, minute) {
        val time = LocalDateTime.now()
        tariff.rateIndexAt(time) to tariff.nextChange(time)
    }
    val rate = tariff.allRates[rateIndex]

    // Background follows how busy the house is; blue while there's no live reading.
    val moodWatts = if (connected) watts ?: 0.0 else null
    val onMood = Color.White

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Column(
            Modifier.fillMaxWidth().background(liveMoodBrush(moodWatts)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Using now", style = MaterialTheme.typography.titleMedium, color = onMood, modifier = Modifier.weight(1f))
                Text(LiveMood.level(moodWatts).label, style = MaterialTheme.typography.labelLarge, color = onMood)
            }
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                MeterPulse(live?.intervalMs?.takeIf { connected }, Modifier.size(150.dp), color = onMood)
                if (watts == null) {
                    Text(
                        if (connected) "Waiting for the next meter pulse…" else "Live power appears once connected",
                        style = MaterialTheme.typography.bodyLarge,
                        color = onMood,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    val (value, unit) = Format.powerParts(watts)
                    val alpha = if (connected) 1f else 0.6f
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            value,
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Bold,
                            color = onMood.copy(alpha = alpha),
                        )
                        Text(
                            " $unit",
                            style = MaterialTheme.typography.headlineSmall,
                            color = onMood.copy(alpha = alpha),
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                }
            }
            if (watts != null) {
                Text(
                    "≈ ${Format.money(watts / 1000 * rate.centsPerKwh)} per hour",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onMood,
                )
                if (!connected) {
                    Text("Last seen ${Format.ago(live.receivedAtMillis, now)}", style = MaterialTheme.typography.bodySmall, color = onMood)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                RateSwatch(rateColor(tariff, rateIndex))
                Text(
                    " ${rate.name} rate, ${Format.rate(rate.centsPerKwh)}" + (nextChange?.let { " until ${describeChange(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = onMood,
                )
            }
        }
    }
}

private fun describeChange(time: LocalDateTime): String {
    val clock = TariffText.time(time.hour * 60 + time.minute, isEnd = true)
    val days = ChronoUnit.DAYS.between(LocalDate.now(), time.toLocalDate())
    return when (days) {
        0L -> clock
        1L -> "$clock tomorrow"
        else -> "${time.format(DateTimeFormatter.ofPattern("EEE"))} $clock"
    }
}

@Composable
private fun LiveTrendCard(state: LiveUiState, now: Long) {
    val ppk = state.settings.pulsesPerKwh
    val points = state.history
        .filter { it.receivedAtMillis >= now - PowerpalManager.LIVE_HISTORY_MS }
        .map { it.receivedAtMillis to PowerpalProtocol.wattsFromInterval(it.intervalMs, ppk) }
    if (points.size < 2) return
    SectionCard(title = "Last 15 minutes") {
        Sparkline(points, windowMs = PowerpalManager.LIVE_HISTORY_MS, endMillis = now)
        Row {
            Text(
                "Peak ${Format.power(points.maxOf { it.second })}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Low ${Format.power(points.minOf { it.second })}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
