package app.powermate.ui.prices

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.powermate.AppContainer
import app.powermate.data.Tariff
import app.powermate.prices.PriceAnalysis
import app.powermate.prices.PriceInterval
import app.powermate.prices.PriceLevel
import app.powermate.prices.PriceOutlook
import app.powermate.prices.PriceSource
import app.powermate.prices.PriceWindow
import app.powermate.ui.Format
import app.powermate.ui.components.LineChart
import app.powermate.ui.components.ScreenScaffold
import app.powermate.ui.components.SectionCard
import app.powermate.ui.components.TariffText
import app.powermate.ui.components.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

class PricesViewModel(private val container: AppContainer) : ViewModel() {
    val outlook: StateFlow<PriceOutlook?> = container.prices.outlook
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            _loading.value = true
            try {
                val token = container.settings.settings.first().amberToken
                container.prices.refresh(token, maxAge = if (force) java.time.Duration.ZERO else java.time.Duration.ofMinutes(4))
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't load prices"
            } finally {
                _loading.value = false
            }
        }
    }

    private val _selected = MutableStateFlow<Int?>(null)
    val selected: StateFlow<Int?> = _selected.asStateFlow()
    fun select(index: Int?) {
        _selected.value = index
    }
}

/** Background fill for a price level; readable with white text. */
fun priceLevelFill(level: PriceLevel): Color = when (level) {
    PriceLevel.GREAT -> Color(0xFF1B7F5A)
    PriceLevel.GOOD -> Color(0xFF4A7A2A)
    PriceLevel.ELEVATED -> Color(0xFFA86200)
    PriceLevel.HIGH -> Color(0xFFC0392B)
}

/** Line colour for a price level against the app's surface. */
@Composable
fun priceLevelLine(level: PriceLevel): Color {
    val dark = isSystemInDarkTheme()
    return when (level) {
        PriceLevel.GREAT -> if (dark) Color(0xFF3DD68C) else Color(0xFF1B7F5A)
        PriceLevel.GOOD -> if (dark) Color(0xFF9BD45A) else Color(0xFF4A7A2A)
        PriceLevel.ELEVATED -> if (dark) Color(0xFFF5A623) else Color(0xFFA86200)
        PriceLevel.HIGH -> if (dark) Color(0xFFFF6B5B) else Color(0xFFC0392B)
    }
}

private fun cents(value: Double): String = "${value.roundToInt()}¢"

private fun clock(instant: Instant): String {
    val t = instant.atZone(ZoneId.systemDefault())
    return TariffText.time(t.hour * 60 + t.minute)
}

@Composable
fun PricesScreen() {
    val container = appContainer()
    val viewModel: PricesViewModel = viewModel { PricesViewModel(container) }
    val outlook by viewModel.outlook.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val now by produceState(Instant.now()) {
        while (true) {
            delay(30_000)
            value = Instant.now()
        }
    }
    // Prices move every five minutes; refresh while the screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            delay(5 * 60_000L)
        }
    }

    ScreenScaffold(
        "Prices",
        actions = { IconButton(onClick = { viewModel.refresh(force = true) }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh prices") } },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading && outlook == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.refresh(force = true) }) { Text("Retry") }
                }
            }
            val data = outlook ?: return@Column
            val upcoming = data.upcoming(now)
            val average = PriceAnalysis.average(upcoming)
            val current = data.current(now)

            CurrentPrice(data, current, average)
            settings?.tariff?.let { if (data.source == PriceSource.SPOT) YourRate(it, now) }
            Forecast(PriceAnalysis.windows(upcoming))
            TodayChart(data, now, average, selected, viewModel::select)
            Text(
                "Prices: " + (if (data.source == PriceSource.AMBER) "Amber Electric" else "AEMO (Australian Energy Market Operator)") +
                    ". Renewables: Amber Electric. Updated ${clock(data.fetchedAt)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CurrentPrice(data: PriceOutlook, current: PriceInterval?, average: Double) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(data.source.label, style = MaterialTheme.typography.titleMedium)
        if (current == null) {
            Text("No current price", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val level = PriceAnalysis.level(current.centsPerKwh, average)
        PriceOrb(current.centsPerKwh, level)
        Text(
            when (level) {
                PriceLevel.GREAT -> "Great price right now"
                PriceLevel.GOOD -> "Below the day's average"
                PriceLevel.ELEVATED -> "Above the day's average"
                PriceLevel.HIGH -> "High price right now"
            },
            style = MaterialTheme.typography.labelLarge,
        )
        current.renewables?.let {
            Text("${it.roundToInt()}% renewables in the grid", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            data.source.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** What the user's own plan charges now, so the wholesale figure isn't mistaken for it. */
@Composable
private fun YourRate(tariff: Tariff, now: Instant) {
    val time = LocalDateTime.ofInstant(now, ZoneId.systemDefault())
    val rate = remember(tariff, time.hour, time.minute) { tariff.rateAt(time) }
    val until = remember(tariff, time.hour, time.minute) { tariff.nextChange(time) }
    SectionCard(title = "What you pay now") {
        Text(Format.rate(rate.centsPerKwh), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "${rate.name} on your plan" + (until?.let { " until ${TariffText.time(it.hour * 60 + it.minute, isEnd = true)}" } ?: "") + ".",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Your retailer charges this whatever the wholesale price does. Amber's prices, which the Powerpal app " +
                "showed, follow the wholesale price but add network charges, fees and GST on top.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The current price in a circle that breathes and sends out ripples: slowly when the price is
 * great, faster as it climbs. The colour fades between levels and the figure counts to new prices.
 */
@Composable
private fun PriceOrb(centsPerKwh: Double, level: PriceLevel) {
    val fill by animateColorAsState(priceLevelFill(level), tween(800), label = "price colour")
    val shown = remember { Animatable(0f) }
    LaunchedEffect(centsPerKwh) { shown.animateTo(centsPerKwh.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    val period = when (level) {
        PriceLevel.GREAT -> 3_600
        PriceLevel.GOOD -> 3_000
        PriceLevel.ELEVATED -> 2_200
        PriceLevel.HIGH -> 1_400
    }
    // Read only while drawing, so the loop redraws without recomposing.
    val phase = rememberInfiniteTransition(label = "price pulse")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing)), label = "phase")
    fun breath() = 1f + 0.03f * (0.5f - 0.5f * cos(2 * PI * phase.value).toFloat())

    Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = 90.dp.toPx() * breath()
            val reach = size.minDimension / 2 - base
            for (offset in listOf(0f, 0.5f)) {
                val p = (phase.value + offset) % 1f
                drawCircle(fill.copy(alpha = 0.65f * (1 - p)), base + reach * p, style = Stroke(width = (4 * (1 - p) + 1.5f).dp.toPx()))
            }
        }
        Box(
            Modifier.size(180.dp).graphicsLayer { scaleX = breath(); scaleY = breath() }.background(fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cents(shown.value.toDouble()), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Text("/kWh", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
    }
}

@Composable
private fun Forecast(windows: List<PriceWindow>) {
    Text("Forecast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (windows.isEmpty()) {
        Text(
            "No standout cheap or expensive stretches in the next 24 hours.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(windows) { window -> ForecastCard(window) }
    }
}

@Composable
private fun ForecastCard(window: PriceWindow) {
    val accent = priceLevelFill(if (window.great) PriceLevel.GREAT else PriceLevel.HIGH)
    val start = clock(window.start)
    val end = clock(window.end)
    Column(
        Modifier
            .width(300.dp)
            .border(2.dp, accent, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$start – $end", style = MaterialTheme.typography.labelLarge)
                Text(if (window.great) "GREAT PRICES" else "HIGH PRICES", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                "${cents(window.averageCents)}/kWh",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.background(accent, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Text(
            if (window.great) {
                "From $start prices will be ${window.percentFromAverage}% cheaper than the day's average" +
                    (window.percentGreener?.takeIf { it > 0 }?.let { ", and $it% greener" } ?: "") +
                    ". A good time for the dishwasher, laundry or EV charging."
            } else {
                "Prices will be ${window.percentFromAverage}% above the day's average between $start and $end. " +
                    "Try delaying heavy loads like the dishwasher or dryer."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Today's half-hourly prices, midnight to midnight, with a "now" marker. */
@Composable
private fun TodayChart(data: PriceOutlook, now: Instant, average: Double, selected: Int?, onSelect: (Int?) -> Unit) {
    val zone = ZoneId.systemDefault()
    val midnight = LocalDate.now(zone).atStartOfDay(zone)
    val slots = List(48) { i -> midnight.plusMinutes(30L * i).toInstant() }
    val byStart = data.intervals.associateBy { it.start }
    val day = slots.map { byStart[it] }
    val values = day.map { it?.centsPerKwh }
    val lineColors = day.map { interval ->
        priceLevelLine(if (interval == null) PriceLevel.GOOD else PriceAnalysis.level(interval.centsPerKwh, average))
    }
    val nowIndex = (java.time.Duration.between(midnight.toInstant(), now).toMinutes() / 30f).coerceIn(0f, 48f)
    val labels = (0 until 48 step 8).map { i -> i to TariffText.time(i * 30).replace(":00", "").replace(" ", "").lowercase(Locale.getDefault()) }

    SectionCard(title = "Today") {
        val shown = selected?.let { day.getOrNull(it) } ?: data.current(now)
        if (shown != null) {
            Row(
                Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${clock(shown.start)} – ${clock(shown.end)}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("${cents(shown.centsPerKwh)}/kWh", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                shown.renewables?.let {
                    Text("  ·  ${it.roundToInt()}% renewable", style = MaterialTheme.typography.bodySmall)
                }
                if (shown.isForecast) Text("  ·  forecast", style = MaterialTheme.typography.bodySmall)
            }
        }
        LineChart(
            values = values,
            pointColors = lineColors,
            xLabels = labels,
            selected = selected,
            onSelect = onSelect,
            formatAxis = { "${it.roundToInt()}¢" },
            stepped = true,
            nowIndex = nowIndex,
        )
        Text(
            "Drag along the chart for any half hour. Green is cheap for the day, red is dear.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
