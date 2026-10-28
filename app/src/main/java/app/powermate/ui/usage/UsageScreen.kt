package app.powermate.ui.usage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.powermate.AppContainer
import app.powermate.ble.LinkStatus
import app.powermate.ble.LivePower
import app.powermate.ble.PowerpalProtocol
import app.powermate.data.AppSettings
import app.powermate.data.Period
import app.powermate.data.Tariff
import app.powermate.data.UsageBar
import app.powermate.data.UsageCalculator
import app.powermate.data.UsageSummary
import app.powermate.ui.Format
import app.powermate.ui.components.BarChart
import app.powermate.ui.components.CompactToggle
import app.powermate.ui.components.EnergyClock
import app.powermate.ui.components.InfoRow
import app.powermate.ui.components.LiveMood
import app.powermate.ui.components.MeterPulse
import app.powermate.ui.components.RateLegend
import app.powermate.ui.components.RateSwatch
import app.powermate.ui.components.ScreenScaffold
import app.powermate.ui.components.SectionCard
import app.powermate.ui.components.TariffText
import app.powermate.ui.components.appContainer
import app.powermate.ui.components.liveMoodBrush
import app.powermate.ui.components.rateColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.abs

/** Ways to show a period's usage, cycled with the icon on the chart card. */
enum class UsageView(val title: String, val icon: ImageVector) {
    CLOCK("Energy clock", Icons.Filled.Schedule),
    CHART("Chart", Icons.Filled.BarChart),
    LINE("Line graph", Icons.AutoMirrored.Filled.ShowChart),
    TABLE("Table", Icons.Filled.TableChart);

    companion object {
        /** The clock only makes sense for a single day. */
        fun availableFor(period: Period): List<UsageView> = if (period == Period.DAY) entries else listOf(CHART, LINE, TABLE)
    }
}

/** What the energy clock's spikes measure. */
enum class ClockMeasure(val label: String) {
    /** Energy per quarter hour. */
    KWH("kWh"),

    /** Average power per five minutes. */
    KW("kW"),

    /** Each quarter hour's energy at the rate in force, without the daily connection fee. */
    COST("Cost"),
}

data class UsageUiState(
    val period: Period = Period.DAY,
    val view: UsageView = UsageView.CLOCK,
    val showCost: Boolean = false,
    val clockMeasure: ClockMeasure = ClockMeasure.KWH,
    val selected: Int? = null,
    val summary: UsageSummary? = null,
    val tariff: Tariff = Tariff(),
    val pulsesPerKwh: Double = 1000.0,
    val canGoForward: Boolean = false,
    val live: LivePower? = null,
    val connected: Boolean = false,
)

class UsageViewModel(private val container: AppContainer) : ViewModel() {
    private data class Selection(
        val period: Period,
        val anchor: LocalDate,
        val view: UsageView,
        val showCost: Boolean,
        val clockMeasure: ClockMeasure,
        val selected: Int?,
    )

    private val selection = MutableStateFlow(Selection(Period.DAY, LocalDate.now(), UsageView.CLOCK, showCost = false, clockMeasure = ClockMeasure.KWH, selected = null))

    private data class Computed(val summary: UsageSummary, val settings: AppSettings)

    // Recomputed only when the period, day, settings or stored readings change: not for a selected
    // bar or quarter hour, a switch of view or units, or a live report.
    private val summaries =
        combine(
            selection.map { it.period to it.anchor }.distinctUntilChanged(),
            container.settings.settings,
            container.usage.changes(),
        ) { (period, anchor), settings, _ -> Triple(period, anchor, settings) }
            .conflate()
            .map { (period, anchor, settings) -> Computed(container.usage.summary(period, anchor, settings), settings) }

    val state: StateFlow<UsageUiState> =
        combine(summaries, selection, container.powerpal.live, container.powerpal.link) { computed, s, live, link ->
            // Period-dependent fields follow the summary, which can trail the selection briefly.
            val summary = computed.summary
            val period = summary.period
            UsageUiState(
                period = period,
                view = if (s.view in UsageView.availableFor(period)) s.view else UsageView.CHART,
                showCost = s.showCost,
                clockMeasure = s.clockMeasure,
                selected = s.selected,
                summary = summary,
                tariff = computed.settings.tariff,
                pulsesPerKwh = computed.settings.pulsesPerKwh,
                canGoForward = period.shift(summary.start, 1) <= LocalDate.now(),
                live = live,
                connected = link.status == LinkStatus.LIVE,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageUiState())

    fun setPeriod(period: Period) = selection.update { it.copy(period = period, selected = null) }

    fun step(by: Long) = selection.update { s ->
        val start = s.period.startOf(s.anchor, container.usage.firstDayOfWeek())
        val target = s.period.shift(start, by)
        if (target.isAfter(LocalDate.now())) s else s.copy(anchor = target, selected = null)
    }

    fun today() = selection.update { it.copy(anchor = LocalDate.now(), selected = null) }

    /** Shows [date] in the day view, unless it's still to come. */
    fun openDay(date: LocalDate) = selection.update { s ->
        if (date.isAfter(LocalDate.now()) || date == s.anchor) s else s.copy(anchor = date, selected = null)
    }

    fun setShowCost(showCost: Boolean) = selection.update { it.copy(showCost = showCost) }

    fun setClockMeasure(measure: ClockMeasure) = selection.update { it.copy(clockMeasure = measure) }

    /** Opens the next finer period at [date]: a month of the year, or a day of the week or month. */
    fun drillInto(date: LocalDate) = selection.update { s ->
        val finer = when (s.period) {
            Period.YEAR -> Period.MONTH
            Period.MONTH, Period.WEEK -> Period.DAY
            Period.DAY -> null
        }
        if (finer == null || date.isAfter(LocalDate.now())) s else s.copy(period = finer, anchor = date, selected = null)
    }

    fun select(index: Int?) = selection.update { it.copy(selected = index) }

    fun cycleView() = selection.update { s ->
        val views = UsageView.availableFor(s.period)
        val current = if (s.view in views) s.view else UsageView.CHART
        s.copy(view = views[(views.indexOf(current) + 1) % views.size], selected = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen() {
    val container = appContainer()
    val viewModel: UsageViewModel = viewModel { UsageViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The bottom bar already says this is Usage, so the space goes to the chart.
    ScreenScaffold(null) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val summary = state.summary ?: return@Column
            SectionCard {
                val views = UsageView.availableFor(state.period)
                val next = views[(views.indexOf(state.view) + 1) % views.size]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.view.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    when (state.view) {
                        UsageView.CLOCK -> CompactToggle(
                            ClockMeasure.entries.map { it.label },
                            state.clockMeasure.ordinal,
                            { viewModel.setClockMeasure(ClockMeasure.entries[it]) },
                        )
                        UsageView.CHART -> CompactToggle(listOf("kWh", "Cost"), if (state.showCost) 1 else 0, { viewModel.setShowCost(it == 1) })
                        UsageView.LINE, UsageView.TABLE -> {}
                    }
                    IconButton(onClick = viewModel::cycleView) {
                        Icon(next.icon, contentDescription = "Show ${next.title.lowercase()}")
                    }
                }
                when (state.view) {
                    UsageView.CLOCK -> ClockView(state, summary, viewModel::select)
                    UsageView.CHART -> ChartView(state, summary, viewModel::select, viewModel::drillInto)
                    UsageView.LINE -> LineView(state, summary, viewModel::select, viewModel::drillInto)
                    UsageView.TABLE -> UsageTable(summary, state.tariff, viewModel::drillInto)
                }
            }

            // Date and period controls sit under the chart they drive.
            if (state.period == Period.DAY && summary.monthDays.isNotEmpty()) {
                MonthStrip(summary.monthDays, summary.start, viewModel::openDay)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.step(-1) }) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous") }
                Text(
                    summary.title,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.step(1) }, enabled = state.canGoForward) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Next")
                }
                // After the arrows, so neither moves while stepping back through earlier periods.
                AnimatedVisibility(
                    visible = !summary.isCurrent,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) {
                    TextButton(onClick = viewModel::today, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Today") }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Period.entries.forEachIndexed { i, period ->
                    SegmentedButton(
                        selected = state.period == period,
                        onClick = { viewModel.setPeriod(period) },
                        shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                    ) { Text(period.label) }
                }
            }

            SummaryCard(summary, state.tariff)
        }
    }
}

@Composable
private fun ClockView(state: UsageUiState, summary: UsageSummary, onSelect: (Int?) -> Unit) {
    val now by produceState(LocalTime.now()) {
        while (true) {
            delay(1_000)
            value = LocalTime.now()
        }
    }
    val isToday = summary.isCurrent
    val measure = state.clockMeasure
    val quarterCents = remember(summary, state.tariff) {
        summary.quarterKwh.mapIndexed { q, kwh -> kwh * state.tariff.centsPerKwhAt(summary.start.atTime(q / 4, (q % 4) * 15)) }
    }
    EnergyClock(
        values = when (measure) {
            ClockMeasure.KWH -> summary.quarterKwh
            ClockMeasure.KW -> summary.powerKw
            ClockMeasure.COST -> quarterCents
        },
        hasData = if (measure == ClockMeasure.KW) summary.powerHasData else summary.quarterHasData,
        // About 200 W: 50 Wh in a quarter hour, 0.2 kW, or a cent.
        minFullScale = when (measure) {
            ClockMeasure.KWH -> 0.05
            ClockMeasure.KW -> 0.2
            ClockMeasure.COST -> 1.0
        },
        date = summary.start,
        tariff = state.tariff,
        nowMinute = if (isToday) now.hour * 60 + now.minute else null,
        selected = state.selected,
        onSelect = onSelect,
    ) {
        val selected = state.selected
        // Remembered so the quarter-hour detail can fade out after the selection clears.
        val lastSelected = remember { intArrayOf(0) }
        if (selected != null) lastSelected[0] = selected
        Crossfade(targetState = selected != null, label = "clock centre") { showDetail ->
            when {
                showDetail -> QuarterDetail(lastSelected[0], summary, state.tariff, measure, quarterCents)
                isToday -> LiveCentre(state, summary)
                else -> DayTotal(summary, measure)
            }
        }
    }
    RateLegend(state.tariff)
    Text(
        when (measure) {
            ClockMeasure.KWH -> "Each spike is the energy used in a quarter hour. "
            ClockMeasure.KW -> "Each spike is the average power over five minutes. "
            ClockMeasure.COST -> "Each spike is what a quarter hour's energy cost; the daily connection fee isn't included. "
        } +
            if (state.selected == null) "Tap or drag around the clock to see any quarter hour." else "Tap the centre to go back.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Live power in the clock's centre, on the same colour scale and pulse as the Live screen. */
@Composable
private fun LiveCentre(state: UsageUiState, summary: UsageSummary) {
    val nowMillis by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val live = state.live.takeIf { state.connected }
    val watts = live?.let { PowerpalProtocol.liveWatts(it.intervalMs, nowMillis - it.receivedAtMillis, state.pulsesPerKwh) }
    val moodWatts = if (state.connected) watts ?: 0.0 else null
    Box(Modifier.fillMaxSize().clip(CircleShape).background(liveMoodBrush(moodWatts)), contentAlignment = Alignment.Center) {
        MeterPulse(live?.intervalMs, Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.connected) {
                Text(
                    watts?.let { Format.power(it) } ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                val rate = state.tariff.centsPerKwhAt(java.time.LocalDateTime.now())
                Text(
                    watts?.let { Format.money(it / 1000 * rate) + "/hr" } ?: "waiting",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
                Text(LiveMood.level(moodWatts).label, style = MaterialTheme.typography.labelSmall, color = Color.White)
            } else {
                Icon(Icons.Filled.BluetoothDisabled, contentDescription = null, tint = Color.White)
                Text("Not connected", style = MaterialTheme.typography.labelMedium, color = Color.White)
                Text(Format.kwh(summary.totalKwh) + " today", style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
        }
    }
}

@Composable
private fun DayTotal(summary: UsageSummary, measure: ClockMeasure) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (measure == ClockMeasure.COST) {
            Text(Format.money(summary.totalCents), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(Format.kwh(summary.totalKwh), style = MaterialTheme.typography.bodyMedium)
            Text(
                "Usage ${Format.money(summary.usageCents)} · connection ${Format.money(summary.supplyCents)}",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(Format.kwh(summary.totalKwh), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(Format.money(summary.totalCents), style = MaterialTheme.typography.bodyMedium)
        }
        if (measure == ClockMeasure.KW) {
            val peak = summary.powerKw.indices.filter { summary.powerHasData[it] }.maxByOrNull { summary.powerKw[it] }
            if (peak != null && summary.powerKw[peak] > 0) {
                Text(
                    "Peak ${Format.power(summary.powerKw[peak] * 1_000)} at ${TariffText.time(peak * UsageCalculator.POWER_SLOT_MINUTES)}",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun QuarterDetail(quarter: Int, summary: UsageSummary, tariff: Tariff, measure: ClockMeasure, quarterCents: List<Double>) {
    val start = quarter * 15
    val kwh = summary.quarterKwh.getOrElse(quarter) { 0.0 }
    val hasData = summary.quarterHasData.getOrElse(quarter) { false }
    val perQuarter = 15 / UsageCalculator.POWER_SLOT_MINUTES
    val peakKw = (quarter * perQuarter until (quarter + 1) * perQuarter).maxOfOrNull { summary.powerKw.getOrElse(it) { 0.0 } } ?: 0.0
    val rateIndex = tariff.rateIndexAt(summary.start.atTime(start / 60, start % 60))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${TariffText.time(start)} – ${TariffText.time((start + 15) % 1440, isEnd = true)}",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            when {
                !hasData -> "No readings"
                measure == ClockMeasure.KW -> "${Format.power(kwh * 4_000)} avg"
                measure == ClockMeasure.COST -> Format.money(quarterCents.getOrElse(quarter) { 0.0 })
                else -> Format.kwh(kwh)
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (hasData) {
            Text(
                when (measure) {
                    ClockMeasure.KWH -> "≈ ${Format.power(kwh * 4_000)} avg"
                    ClockMeasure.KW -> "Peak ${Format.power(peakKw * 1_000)}"
                    ClockMeasure.COST -> Format.kwh(kwh)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RateSwatch(rateColor(tariff, rateIndex))
            Text(" ${tariff.allRates[rateIndex].name}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ChartView(state: UsageUiState, summary: UsageSummary, onSelect: (Int?) -> Unit, onDrill: (LocalDate) -> Unit) {
    val tariff = state.tariff
    val values = summary.bars.map { if (state.showCost) it.costCents else it.kwh }
    // With time-of-use pricing, each bar is split by the rate its energy was bought at.
    val stacked = tariff.isTimeOfUse
    BarChart(
        values = values,
        labels = summary.bars.map { it.label },
        hasData = summary.bars.map { it.hasData },
        selected = state.selected,
        onSelect = onSelect,
        formatAxis = { if (state.showCost) Format.money(it) else axisKwh(it) },
        labelEvery = when (state.period) {
            Period.DAY -> 6
            Period.WEEK -> 1
            Period.MONTH -> 7
            Period.YEAR -> 2
        },
        segments = if (stacked) summary.bars.map { if (state.showCost) it.rateCents else it.rateKwh } else null,
        segmentColors = tariff.allRates.indices.map { rateColor(tariff, it) },
        onDoubleTap = drillHint(summary.period)?.let { { i -> onDrill(barDate(summary, i)) } },
    )
    if (stacked) RateLegend(tariff)
    val selected = state.selected?.let { summary.bars.getOrNull(it) }
    if (selected != null) {
        Text(selected.detail, style = MaterialTheme.typography.titleSmall)
        Text(
            if (selected.hasData) "${Format.kwh(selected.kwh)} · ${Format.money(selected.costCents)}" else "No readings",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (stacked && selected.hasData) {
            selected.rateKwh.forEachIndexed { rate, kwh ->
                if (kwh <= 0) return@forEachIndexed
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RateSwatch(rateColor(tariff, rate))
                    Text(" ${tariff.allRates[rate].name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(
                        "${Format.kwh(kwh)} · ${Format.money(selected.rateCents.getOrElse(rate) { 0.0 })}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    } else {
        val peak = summary.bars.withIndex().filter { it.value.hasData }.maxByOrNull { it.value.kwh }
        Text(
            (
                peak?.let { "Highest: ${it.value.detail} (${Format.kwh(it.value.kwh)}). Tap a bar for details." }
                    ?: "No readings for this period yet."
                ) + (drillHint(summary.period)?.let { " Double-tap $it to open it." } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A power curve for a day (quarter hours) or week (hours), or daily energy for a month or year,
 * coloured by the rate in force. Dragging along it reads off any point.
 */
@Composable
private fun LineView(state: UsageUiState, summary: UsageSummary, onSelect: (Int?) -> Unit, onDrill: (LocalDate) -> Unit) {
    val tariff = state.tariff
    val series = summary.series
    val power = summary.period == Period.DAY || summary.period == Period.WEEK
    val step = summary.seriesStepHours
    val values = series.map { if (it.hasData) (if (power) it.kwh / step else it.kwh) else null }
    val primary = MaterialTheme.colorScheme.primary
    val pointColors = series.map { p ->
        if (tariff.isTimeOfUse && p.rateIndex != null) rateColor(tariff, p.rateIndex) else primary
    }
    val xLabels = series.indices.filter { i ->
        val t = series[i].start
        when (summary.period) {
            Period.DAY -> t.minute == 0 && t.hour % 6 == 0
            Period.WEEK -> t.hour == 0
            Period.MONTH -> (t.dayOfMonth - 1) % 7 == 0
            Period.YEAR -> t.dayOfMonth == 1 && (t.monthValue - 1) % 3 == 0
        }
    }.map { i ->
        val t = series[i].start
        i to when (summary.period) {
            Period.DAY -> TariffText.time(t.hour * 60).replace(":00", "").replace(" ", "").lowercase(Locale.getDefault())
            Period.WEEK -> t.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.getDefault())
            Period.MONTH -> t.dayOfMonth.toString()
            Period.YEAR -> t.month.getDisplayName(DateTextStyle.SHORT, Locale.getDefault())
        }
    }
    val selected = state.selected?.takeIf { it in series.indices }

    Text(
        if (power) "Average power (kW)" else "Energy per day (kWh)",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    app.powermate.ui.components.LineChart(
        values = values,
        pointColors = pointColors,
        xLabels = xLabels,
        selected = selected,
        onSelect = onSelect,
        formatAxis = { axisKwh(it) },
        // A point opens the period above it: its day, or in a year its month.
        onDoubleTap = drillHint(summary.period)?.let { { i -> onDrill(series[i].start.toLocalDate()) } },
    )
    if (tariff.isTimeOfUse && power) RateLegend(tariff)
    if (selected != null) {
        val p = series[selected]
        val whenText = when (summary.period) {
            Period.DAY -> "${TariffText.time(p.start.hour * 60 + p.start.minute)} – ${TariffText.time((p.start.hour * 60 + p.start.minute + 15) % 1440, isEnd = true)}"
            Period.WEEK -> "${p.start.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.getDefault())} ${TariffText.time(p.start.hour * 60)}"
            Period.MONTH, Period.YEAR -> p.start.toLocalDate().format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
        }
        Text(whenText, style = MaterialTheme.typography.titleSmall)
        Text(
            when {
                !p.hasData -> "No readings"
                power -> "${Format.power(p.kwh / step * 1000)} average · ${Format.kwh(p.kwh)}" +
                    (p.rateIndex?.let { " · ${tariff.allRates[it].name}" } ?: "")
                else -> Format.kwh(p.kwh)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Text(
            "Drag along the graph to read any point." + (drillHint(summary.period)?.let { " Double-tap to open that ${it.removePrefix("a ")}." } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The period as a two-column table of kWh and cost. Cost includes each row's share of the daily
 * connection fee, so the rows add up to what's actually paid. In the day view each hour is tinted
 * with the colour of the rate in force.
 */
@Composable
private fun UsageTable(summary: UsageSummary, tariff: Tariff, onDrill: (LocalDate) -> Unit) {
    val drill = drillHint(summary.period)
    val bars = summary.bars
    val half = (bars.size + 1) / 2
    val numbers = TextStyle(fontFeatureSettings = "tnum")
    val heading = when (summary.period) {
        Period.DAY -> "Time"
        Period.WEEK, Period.MONTH -> "Day"
        Period.YEAR -> "Month"
    }

    Row(Modifier.fillMaxWidth()) {
        listOf(0 until half, half until bars.size).forEachIndexed { column, indices ->
            if (column == 1) VerticalDivider(Modifier.height(((half + 1) * 30).dp).padding(horizontal = 4.dp))
            Column(Modifier.weight(1f)) {
                TableRow(heading, "kWh", "Cost", header = true)
                indices.forEach { i ->
                    val bar = bars[i]
                    val tint = if (summary.period == Period.DAY) {
                        rateColor(tariff, tariff.rateIndexAt(summary.start.atTime(i, 0))).copy(alpha = 0.16f)
                    } else if (i % 2 == 1) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    } else {
                        Color.Transparent
                    }
                    TableRow(
                        label = rowLabel(summary, i, bar),
                        kwh = if (bar.hasData) String.format(Locale.getDefault(), "%.2f", bar.kwh) else "",
                        cost = if (bar.hasData) Format.money(bar.totalCents) else "",
                        background = tint,
                        style = numbers,
                        onDoubleTap = drill?.let { { onDrill(barDate(summary, i)) } },
                    )
                }
            }
        }
    }
    val kwh = bars.sumOf { it.kwh }
    val cents = bars.sumOf { it.totalCents }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("Total", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text("${Format.kwh(kwh)} · ${Format.money(cents)}", style = MaterialTheme.typography.titleSmall.merge(numbers))
    }
    Text(
        "Costs include the daily connection fee, shared across the " +
            (if (summary.period == Period.DAY) "hours" else "days") + " with readings." +
            (drill?.let { " Double-tap a row to open it." } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TableRow(
    label: String,
    kwh: String,
    cost: String,
    header: Boolean = false,
    background: Color = Color.Transparent,
    style: TextStyle = TextStyle.Default,
    onDoubleTap: (() -> Unit)? = null,
) {
    val textStyle = (if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall).merge(style)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val tappable = if (onDoubleTap != null) {
        Modifier.pointerInput(Unit) { detectTapGestures(onDoubleTap = { currentOnDoubleTap?.invoke() }) }
    } else {
        Modifier
    }
    Row(
        Modifier.fillMaxWidth().height(30.dp).then(tappable).background(background).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = textStyle, fontWeight = if (header) FontWeight.SemiBold else null, modifier = Modifier.weight(1.2f))
        Text(kwh, style = textStyle, fontWeight = if (header) FontWeight.SemiBold else null, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Box(Modifier.width(6.dp))
        Text(cost, style = textStyle, fontWeight = if (header) FontWeight.SemiBold else null, textAlign = TextAlign.End, modifier = Modifier.weight(1.1f))
    }
}

/**
 * The day's month as a strip of small daily bars, the day on show highlighted. Tapping or dragging
 * along it opens another day. Days still to come are left empty.
 */
@Composable
private fun MonthStrip(days: List<Double?>, shown: LocalDate, onPick: (LocalDate) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val selectedLabelStyle = labelStyle.copy(color = colors.primary, fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    val today = LocalDate.now()
    val month = shown.withDayOfMonth(1)
    val currentOnPick by rememberUpdatedState(onPick)
    val currentMonth by rememberUpdatedState(month)
    // Full scale is the month's busiest day, but at least 1 kWh so a quiet month stays small.
    val fullScale = maxOf(days.maxOf { it ?: 0.0 }, 1.0)
    val monthName = month.month.getDisplayName(DateTextStyle.FULL, Locale.getDefault())

    fun pick(x: Float, width: Float) {
        val day = (x / (width / days.size)).toInt().coerceIn(0, days.lastIndex)
        currentOnPick(currentMonth.plusDays(day.toLong()))
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics { contentDescription = "Daily use in $monthName. Tap a day to open it." }
            .pointerInput(days.size) { detectTapGestures { pick(it.x, size.width.toFloat()) } }
            .pointerInput(days.size) {
                detectHorizontalDragGestures { change, _ -> pick(change.position.x, size.width.toFloat()) }
            },
    ) {
        val labelHeight = measurer.measure("31", labelStyle).size.height
        val baseline = size.height - labelHeight - 4.dp.toPx()
        val slot = size.width / days.size
        val barWidth = minOf(slot * 0.7f, 10.dp.toPx())
        val radius = CornerRadius(minOf(barWidth / 2, 2.dp.toPx()))
        days.forEachIndexed { i, kwh ->
            val date = month.plusDays(i.toLong())
            if (date.isAfter(today)) return@forEachIndexed
            val selected = date == shown
            val x = i * slot + (slot - barWidth) / 2
            val height = maxOf(((kwh ?: 0.0) / fullScale).toFloat() * baseline, 2.dp.toPx())
            val color = when {
                selected -> colors.primary
                kwh == null -> colors.outlineVariant
                else -> colors.onSurfaceVariant.copy(alpha = 0.5f)
            }
            drawRoundRect(color, Offset(x, baseline - height), Size(barWidth, height), radius)
            // Odd days are labelled, plus the day on show; its neighbours give way so labels don't collide.
            if (selected || (i % 2 == 0 && abs(i - (shown.dayOfMonth - 1)) != 1)) {
                val text = measurer.measure((i + 1).toString(), if (selected) selectedLabelStyle else labelStyle)
                drawText(text, topLeft = Offset(i * slot + (slot - text.size.width) / 2, baseline + 4.dp.toPx()))
            }
        }
    }
}

/** What a double tap opens in [period], for hints ("a month"), or null when there's nothing finer. */
private fun drillHint(period: Period): String? = when (period) {
    Period.YEAR -> "a month"
    Period.MONTH, Period.WEEK -> "a day"
    Period.DAY -> null
}

/** The month (in a year) or day (in a week or month) that bar [index] covers. */
private fun barDate(summary: UsageSummary, index: Int): LocalDate = when (summary.period) {
    Period.YEAR -> summary.start.plusMonths(index.toLong())
    Period.DAY, Period.WEEK, Period.MONTH -> summary.start.plusDays(index.toLong())
}

private fun rowLabel(summary: UsageSummary, index: Int, bar: UsageBar): String = when (summary.period) {
    Period.DAY, Period.YEAR -> bar.label
    Period.WEEK, Period.MONTH -> summary.start.plusDays(index.toLong()).let { day ->
        "${day.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.getDefault())} ${day.dayOfMonth}"
    }
}

@Composable
private fun SummaryCard(summary: UsageSummary, tariff: Tariff) {
    SectionCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Used", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Format.kwh(summary.totalKwh), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Cost", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Format.money(summary.totalCents), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        InfoRow("Usage charges", Format.money(summary.usageCents))
        if (summary.period == Period.DAY) {
            InfoRow("Connection fee", Format.money(summary.supplyCents))
            // Gaps in the readings make the day's usage, not its fee, an underestimate.
            val hours = summary.bars.count { it.hasData }
            if (hours in 1 until 24) InfoRow("Readings", "$hours of 24 hours")
        } else {
            InfoRow("Connection fee", "${Format.money(summary.supplyCents)} (${summary.daysWithData} day${if (summary.daysWithData == 1) "" else "s"})")
        }
        if (summary.period != Period.DAY && summary.daysWithData > 0) {
            InfoRow("Average per day", "${Format.kwh(summary.totalKwh / summary.daysWithData)} · ${Format.money(summary.totalCents / summary.daysWithData)}")
        }
        summary.projectedCents?.let { InfoRow("On track for", Format.money(it)) }
        if (tariff.isTimeOfUse && summary.byRate.isNotEmpty()) {
            Text("By rate", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            summary.byRate.forEach { usage ->
                val share = if (summary.totalKwh > 0) usage.kwh / summary.totalKwh * 100 else 0.0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RateSwatch(rateColor(tariff, usage.rateIndex))
                    Text(
                        " ${usage.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${Format.kwh(usage.kwh)} (${share.toInt()}%) · ${Format.money(usage.cents)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun axisKwh(value: Double): String =
    if (value >= 10) String.format(Locale.getDefault(), "%.0f", value) else String.format(Locale.getDefault(), "%.1f", value)
