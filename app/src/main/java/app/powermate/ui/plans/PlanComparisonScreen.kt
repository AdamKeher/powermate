package app.powermate.ui.plans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.powermate.AppContainer
import app.powermate.plans.PlanEstimate
import app.powermate.plans.PlanPricing
import app.powermate.plans.PlanRepository
import app.powermate.plans.RetailPlan
import app.powermate.plans.UsageSample
import app.powermate.ui.components.ScreenScaffold
import app.powermate.ui.components.SectionCard
import app.powermate.ui.components.TariffText
import app.powermate.ui.components.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

const val WINDOW_YEAR = 365

/** Recent stretches that can stand in for typical usage, in days, with their labels. */
private val WINDOWS = listOf(7 to "1 week", 14 to "2 weeks", 28 to "4 weeks", 182 to "6 months", WINDOW_YEAR to "12 months")

data class PlanComparisonState(
    val postcode: String = "",
    /** Days of recent readings to price, scaled to a year. */
    val window: Int = WINDOW_YEAR,
    val running: Boolean = false,
    val stage: String? = null,
    val progress: Float? = null,
    val error: String? = null,
    val basisDays: Int = 0,
    val basisFrom: LocalDate? = null,
    val basisTo: LocalDate? = null,
    val currentYearly: Double? = null,
    val results: List<PlanEstimate> = emptyList(),
)

class PlanComparisonViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = PlanRepository(container.context, container.log)
    private val _state = MutableStateFlow(PlanComparisonState())
    val state: StateFlow<PlanComparisonState> = _state.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            val settings = container.settings.current()
            val window = settings.comparisonDays.takeIf { days -> WINDOWS.any { it.first == days } } ?: WINDOW_YEAR
            _state.update { it.copy(postcode = settings.postcode.orEmpty(), window = window) }
        }
    }

    fun setPostcode(text: String) = _state.update { it.copy(postcode = text.filter(Char::isDigit).take(4), error = null) }

    fun setWindow(days: Int) {
        if (days == _state.value.window) return
        _state.update { it.copy(window = days) }
        viewModelScope.launch { container.settings.setComparisonDays(days) }
        // Plans are cached, so re-pricing an existing comparison is quick.
        if (_state.value.basisDays > 0 && !_state.value.running) compare()
    }

    fun compare(forceRefresh: Boolean = false) {
        val postcode = _state.value.postcode
        if (!PlanRepository.isVictorian(postcode)) {
            _state.update { it.copy(error = "Enter a Victorian postcode (3000–3999). PowerMate compares Victorian plans only.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(running = true, error = null, stage = "Preparing", progress = null) }
            try {
                container.settings.setPostcode(postcode)
                val settings = container.settings.current()
                val usage = loadUsage(settings.pulsesPerKwh, _state.value.window)
                if (usage.isEmpty()) throw IllegalStateException("No readings yet. Sync with your Powerpal or import cloud history first.")
                val days = usage.map { it.start.toLocalDate() }.toSortedSet()
                _state.update {
                    it.copy(
                        basisDays = days.size,
                        basisFrom = days.first(),
                        basisTo = days.last(),
                        currentYearly = PlanPricing.estimateTariff(settings.tariff, usage),
                    )
                }
                val plans = repository.plansFor(postcode, forceRefresh) { p ->
                    _state.update { it.copy(stage = p.stage, progress = if (p.total > 0) p.done.toFloat() / p.total else null) }
                }
                _state.update { it.copy(stage = "Pricing ${plans.size} plans against your usage", progress = null) }
                val estimates = withContext(Dispatchers.Default) {
                    plans.map { PlanPricing.estimate(it, usage) }
                        .filter { it.yearlyDollars > 0 }
                        .distinctBy { "${it.plan.brandName}|${it.plan.displayName}|${(it.yearlyDollars).toLong()}" }
                        .sortedBy { it.yearlyDollars }
                }
                _state.update { it.copy(results = estimates, stage = null, progress = null) }
                if (estimates.isEmpty()) _state.update { it.copy(error = "No plans found for $postcode.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't compare plans") }
            } finally {
                _state.update { it.copy(running = false, stage = null, progress = null) }
            }
        }
    }

    fun useAsTariff(plan: RetailPlan) {
        viewModelScope.launch {
            container.settings.setTariff(PlanPricing.toTariff(plan, LocalDate.now()))
            _messages.send("${plan.brandName} ${plan.displayName} is now your plan in PowerMate")
        }
    }

    /**
     * The last [days] whole days of readings as quarter-hour samples in local time. Today is left
     * out: it would be charged a full day's supply for part of a day's usage.
     */
    private suspend fun loadUsage(pulsesPerKwh: Double, days: Int): List<UsageSample> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val end = today.atStartOfDay(zone).toEpochSecond()
        val start = today.minusDays(days.toLong()).atStartOfDay(zone).toEpochSecond()
        val buckets = container.database.readings().quarterHours(start, end)
        return buckets.map { b ->
            UsageSample(LocalDateTime.ofInstant(Instant.ofEpochSecond(b.bucket), zone), b.pulses / pulsesPerKwh)
        }
    }
}

private fun dollars(value: Double): String =
    NumberFormat.getCurrencyInstance(Locale.getDefault()).apply { maximumFractionDigits = 0 }.format(value)

@Composable
fun PlanComparisonScreen(onBack: () -> Unit) {
    val container = appContainer()
    val viewModel: PlanComparisonViewModel = viewModel { PlanComparisonViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var shown by remember { mutableIntStateOf(25) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<RetailPlan?>(null) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    ScreenScaffold("Compare plans", onBack = onBack, snackbarHostState = snackbar) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Prices every residential electricity plan offered at your postcode against your own readings, " +
                    "using the plans retailers publish to the Australian Energy Regulator.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Typical usage: the last…", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WINDOWS.forEach { (days, label) ->
                        FilterChip(
                            selected = state.window == days,
                            onClick = { viewModel.setWindow(days) },
                            label = { Text(label) },
                            enabled = !state.running,
                        )
                    }
                }
                Text(
                    when {
                        state.window == WINDOW_YEAR -> "Uses up to a year of readings, so summer and winter both count."
                        state.window > 31 -> "Scales the last six months up to a year. Covers more than one season, but not all of them."
                        else -> "Scales the last ${state.window} days up to a year. Best if your habits or plan changed recently; misses seasons."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.postcode,
                    onValueChange = viewModel::setPostcode,
                    label = { Text("Postcode") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { viewModel.compare() }, enabled = !state.running) { Text("Find plans") }
            }
            Text(
                "The first search downloads a few tens of megabytes of plan data, so Wi-Fi is best. Results are kept for a week.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.running) {
                Text(state.stage ?: "Working…", style = MaterialTheme.typography.labelLarge)
                state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (state.basisDays > 0) {
                val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                SectionCard(title = "Your current plan") {
                    Text(
                        state.currentYearly?.let { "About ${dollars(it)} a year" } ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    state.currentYearly?.let { Text("Around ${dollars(it / 12)} a month", style = MaterialTheme.typography.bodyMedium) }
                    Text(
                        "Based on ${state.basisDays} days of readings (${state.basisFrom?.format(fmt)} – ${state.basisTo?.format(fmt)}), " +
                            "scaled to a year, with the plan set up in Settings." +
                            if (state.window == WINDOW_YEAR && state.basisDays < 60) " Estimates firm up with more history; seasons matter." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.results.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.results.size} plans, cheapest first", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.compare(forceRefresh = true) }, enabled = !state.running) { Text("Refresh") }
                }
                state.results.take(shown).forEachIndexed { rank, estimate ->
                    PlanCard(
                        rank = rank + 1,
                        estimate = estimate,
                        current = state.currentYearly,
                        expanded = expanded == estimate.plan.planId,
                        onToggle = { expanded = if (expanded == estimate.plan.planId) null else estimate.plan.planId },
                        onUse = { confirm = estimate.plan },
                    )
                }
                if (shown < state.results.size) {
                    OutlinedButton(onClick = { shown += 25 }, modifier = Modifier.fillMaxWidth()) { Text("Show more") }
                }
                Text(
                    "Estimates include GST, supply charges and guaranteed discounts. They leave out demand charges, " +
                        "conditional discounts (such as pay-on-time), controlled load, solar feed-in and fees. " +
                        "Check the retailer's offer, or Victorian Energy Compare, before switching.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    confirm?.let { plan ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Use this plan's prices?") },
            text = {
                Text(
                    "PowerMate will replace your electricity plan with ${plan.brandName} ${plan.displayName}'s current " +
                        "rates and times. This only changes how PowerMate prices your usage; it doesn't switch your retailer.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.useAsTariff(plan)
                    confirm = null
                }) { Text("Use it") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PlanCard(
    rank: Int,
    estimate: PlanEstimate,
    current: Double?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onUse: () -> Unit,
) {
    val plan = estimate.plan
    val difference = current?.let { estimate.yearlyDollars - it }
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("$rank. ${plan.brandName}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(plan.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(modelLabel(plan), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(dollars(estimate.yearlyDollars), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("a year", style = MaterialTheme.typography.bodySmall)
                    difference?.let { d ->
                        Text(
                            if (d < 0) "Save ${dollars(abs(d))}" else "${dollars(d)} more",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (d < 0) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                val period = plan.periodOn(LocalDate.now()) ?: plan.periods.first()
                Text(
                    "Supply ${String.format(Locale.getDefault(), "%.2f", period.dailySupplyDollars * 100 * PlanPricing.GST)} c/day",
                    style = MaterialTheme.typography.bodyMedium,
                )
                period.rates.forEach { rate ->
                    val price = rate.tiers.joinToString(" then ") { tier ->
                        String.format(Locale.getDefault(), "%.2f c/kWh", tier.dollarsPerKwh * 100 * PlanPricing.GST) +
                            (tier.kwh?.let { " (first ${it.toInt()} kWh)" } ?: "")
                    }
                    val times = rate.times.joinToString("; ") { t ->
                        "${TariffText.time(t.startMinute)} – ${TariffText.time(t.endMinute, isEnd = true)}, ${TariffText.days(t.days)}"
                    }
                    Text("${rate.name}: $price" + if (times.isNotEmpty()) "\n   $times" else "", style = MaterialTheme.typography.bodySmall)
                }
                if (plan.periods.size > 1) Text("Prices change with the season; shown for today.", style = MaterialTheme.typography.bodySmall)
                if (plan.hasDemandCharges) Text("Has demand charges, not included in the estimate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (plan.hasConditionalDiscounts) Text("Has conditional discounts, not included.", style = MaterialTheme.typography.bodySmall)
                if (plan.distributors.isNotEmpty()) Text("Network: ${plan.distributors.joinToString()}", style = MaterialTheme.typography.bodySmall)
                Text("Plan ID ${plan.planId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onUse) { Text("Use as my plan in PowerMate") }
            }
        }
    }
}

private fun modelLabel(plan: RetailPlan): String {
    val model = when {
        plan.pricingModel.startsWith("TIME_OF_USE") -> "Time of use"
        plan.pricingModel.startsWith("SINGLE_RATE") -> "Single rate"
        plan.pricingModel.startsWith("FLEXIBLE") -> "Flexible"
        else -> plan.pricingModel.lowercase().replace('_', ' ')
    }
    val kind = when (plan.type) {
        "STANDING" -> " · Standing offer"
        "REGULATED" -> " · Default offer"
        else -> ""
    }
    return model + kind
}
