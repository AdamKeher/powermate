package app.powermate.ui.settings

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.powermate.AppContainer
import app.powermate.data.Rate
import app.powermate.data.Tariff
import app.powermate.data.TimeRange
import app.powermate.ui.Format
import app.powermate.ui.components.MAX_TIME_OF_USE_RATES
import app.powermate.ui.components.RateSwatch
import app.powermate.ui.components.ScreenScaffold
import app.powermate.ui.components.SectionCard
import app.powermate.ui.components.TariffText
import app.powermate.ui.components.TariffWeekTimeline
import app.powermate.ui.components.appContainer
import app.powermate.ui.components.localeWeek
import app.powermate.ui.components.rateColor
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** Draft of one rate while it's being edited. Prices stay text until saved so typing isn't fought. */
class RateDraft(val target: Int, name: String, price: String, ranges: List<TimeRange>) {
    var name by mutableStateOf(name)
    var price by mutableStateOf(price)
    val ranges = mutableStateListOf<TimeRange>().apply { addAll(ranges) }

    val isOtherTimes: Boolean get() = target == OTHER_TIMES
    val isNew: Boolean get() = target == NEW_RATE

    val problems: List<String>
        get() = buildList {
            if (name.isBlank()) add("Give the rate a name.")
            if (price.toDoubleOrNull()?.takeIf { it >= 0 } == null) add("Enter a price (0 for free).")
            if (!isOtherTimes && ranges.isEmpty()) add("Add at least one time when this rate applies.")
            if (ranges.any { it.days.isEmpty() }) add("Each time range needs at least one day.")
            if (ranges.any { it.months.isEmpty() }) add("Each time range needs at least one month.")
        }

    fun toRate(): Rate? =
        if (problems.isNotEmpty()) null
        else Rate(name.trim(), price.toDouble(), if (isOtherTimes) emptyList() else ranges.toList())

    companion object {
        const val NEW_RATE = -1
        const val OTHER_TIMES = -2

        fun of(rate: Rate, target: Int) = RateDraft(target, rate.name, Format.number(rate.centsPerKwh), rate.ranges)
    }
}

class TariffViewModel(private val container: AppContainer) : ViewModel() {
    var tariff: Tariff? by mutableStateOf(null)
        private set
    var supplyText by mutableStateOf("")
    var editing: RateDraft? by mutableStateOf(null)
        private set
    private var saved: Tariff? = null

    init {
        viewModelScope.launch {
            val current = container.settings.current().tariff
            saved = current
            tariff = current
            supplyText = Format.number(current.supplyCentsPerDay)
        }
    }

    val supplyCents: Double? get() = supplyText.toDoubleOrNull()?.takeIf { it >= 0 }

    private fun edited(): Tariff? = tariff?.let { t -> supplyCents?.let { t.copy(supplyCentsPerDay = it) } ?: t }

    val hasChanges: Boolean get() = edited() != saved

    fun editRate(index: Int) {
        val t = tariff ?: return
        editing = if (index == RateDraft.OTHER_TIMES) RateDraft.of(t.otherTimes, index) else RateDraft.of(t.rates[index], index)
    }

    fun addRate() {
        val t = tariff ?: return
        val taken = t.allRates.map { it.name.lowercase() }.toSet()
        val name = NAME_SUGGESTIONS.firstOrNull { it.lowercase() !in taken } ?: ""
        editing = RateDraft(RateDraft.NEW_RATE, name, "", listOf(TimeRange(16 * 60, 19 * 60)))
    }

    fun cancelEdit() {
        editing = null
    }

    fun commitEdit() {
        val draft = editing ?: return
        val rate = draft.toRate() ?: return
        val t = tariff ?: return
        tariff = when (draft.target) {
            RateDraft.NEW_RATE -> t.copy(rates = t.rates + rate)
            RateDraft.OTHER_TIMES -> t.copy(otherTimes = rate)
            else -> t.copy(rates = t.rates.toMutableList().also { it[draft.target] = rate })
        }
        editing = null
    }

    fun deleteEditedRate() {
        val draft = editing ?: return
        val t = tariff ?: return
        if (draft.target >= 0) tariff = t.copy(rates = t.rates.filterIndexed { i, _ -> i != draft.target })
        editing = null
    }

    /** Moves a rate up the list; the higher rate wins wherever two overlap. */
    fun moveUp(index: Int) {
        val t = tariff ?: return
        if (index <= 0) return
        tariff = t.copy(rates = t.rates.toMutableList().also { it.add(index - 1, it.removeAt(index)) })
    }

    fun save(onSaved: () -> Unit) {
        val t = edited() ?: return
        if (supplyCents == null) return
        viewModelScope.launch {
            container.settings.setTariff(t)
            saved = t
            onSaved()
        }
    }

    companion object {
        val NAME_SUGGESTIONS = listOf("Peak", "Off-peak", "Shoulder", "Free", "Super off-peak", "Solar soak")
    }
}

@Composable
fun TariffScreen(onBack: () -> Unit) {
    val container = appContainer()
    val viewModel: TariffViewModel = viewModel { TariffViewModel(container) }
    val draft = viewModel.editing
    if (draft != null) {
        RateEditor(
            draft = draft,
            tariff = viewModel.tariff ?: return,
            onCancel = viewModel::cancelEdit,
            onDone = viewModel::commitEdit,
            onDelete = viewModel::deleteEditedRate,
        )
    } else {
        TariffOverview(viewModel, onBack)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TariffOverview(viewModel: TariffViewModel, onBack: () -> Unit) {
    val tariff = viewModel.tariff
    var confirmDiscard by remember { mutableStateOf(false) }
    var previewMonth by remember { mutableStateOf(LocalDate.now().month) }
    val leave = { if (viewModel.hasChanges) confirmDiscard = true else onBack() }
    BackHandler(onBack = leave)

    ScreenScaffold("Electricity plan", onBack = leave) { padding ->
        if (tariff == null) return@ScreenScaffold
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Copy the prices from your electricity bill or plan, including GST/VAT. Prices are in cents (or pence).",
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionCard(title = "Daily connection fee") {
                OutlinedTextField(
                    value = viewModel.supplyText,
                    onValueChange = { viewModel.supplyText = Format.decimalInput(it) },
                    label = { Text("Cents per day") },
                    supportingText = { Text("Also called the supply or service charge. Charged every day, whatever you use.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = viewModel.supplyCents == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard(title = "Usage rates") {
                Text(
                    "Add a rate for each priced period on your plan: peak, off-peak, shoulder, free and so on. " +
                        "Each rate can apply at several times, on chosen days and months.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tariff.rates.forEachIndexed { index, rate ->
                    RateRow(
                        rate = rate,
                        color = rateColor(tariff, index),
                        detail = rate.ranges.map(TariffText::range),
                        onClick = { viewModel.editRate(index) },
                    )
                    HorizontalDivider()
                }
                RateRow(
                    rate = tariff.otherTimes,
                    color = rateColor(tariff, tariff.rates.size),
                    detail = listOf(if (tariff.isTimeOfUse) "All other times" else "All the time"),
                    onClick = { viewModel.editRate(RateDraft.OTHER_TIMES) },
                )
                OutlinedButton(
                    onClick = viewModel::addRate,
                    enabled = tariff.rates.size < MAX_TIME_OF_USE_RATES,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(" Add a rate")
                }
            }

            val overlaps = tariff.overlappingRates()
            if (overlaps.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                "  Some times overlap",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        overlaps.forEach { (first, second) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "“${tariff.rates[first].name}” wins over “${tariff.rates[second].name}” where they overlap.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { viewModel.moveUp(second) }) { Text("Swap") }
                            }
                        }
                    }
                }
            }

            SectionCard(title = "Your week") {
                if (tariff.isSeasonal) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Month.entries.forEach { month ->
                            FilterChip(
                                selected = previewMonth == month,
                                onClick = { previewMonth = month },
                                label = { Text(month.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                            )
                        }
                    }
                }
                TariffWeekTimeline(tariff, previewMonth)
            }

            Button(
                enabled = viewModel.supplyCents != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.save(onBack) },
            ) { Text("Save plan") }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your changes to the plan haven't been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun RateRow(rate: Rate, color: androidx.compose.ui.graphics.Color, detail: List<String>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RateSwatch(color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rate.name.ifBlank { "Unnamed" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(Format.rate(rate.centsPerKwh), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            detail.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = "Edit ${rate.name}")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RateEditor(
    draft: RateDraft,
    tariff: Tariff,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    var showProblems by remember { mutableStateOf(false) }
    val title = when {
        draft.isOtherTimes -> "All other times"
        draft.isNew -> "New rate"
        else -> "Edit rate"
    }

    ScreenScaffold(
        title,
        onBack = onCancel,
        actions = {
            TextButton(onClick = {
                showProblems = true
                onDone()
            }) { Text("Done") }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (draft.isOtherTimes) {
                Text(
                    if (tariff.isTimeOfUse) "This rate applies whenever none of your other rates do."
                    else "With no other rates, this is your flat rate for all usage.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft.name = it.take(30) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TariffViewModel.NAME_SUGGESTIONS.forEach { suggestion ->
                    SuggestionChip(onClick = { draft.name = suggestion }, label = { Text(suggestion) })
                }
            }
            OutlinedTextField(
                value = draft.price,
                onValueChange = { draft.price = Format.decimalInput(it) },
                label = { Text("Price (cents per kWh)") },
                supportingText = { Text("As many decimals as your plan shows, e.g. 32.175. Use 0 for a free period.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = showProblems && draft.price.toDoubleOrNull() == null,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!draft.isOtherTimes) {
                Text("When it applies", style = MaterialTheme.typography.titleMedium)
                draft.ranges.forEachIndexed { index, range ->
                    TimeRangeEditor(
                        range = range,
                        onChange = { draft.ranges[index] = it },
                        onRemove = { draft.ranges.removeAt(index) },
                    )
                }
                OutlinedButton(onClick = { draft.ranges.add(TimeRange(7 * 60, 9 * 60)) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(" Add another time")
                }
            }

            if (showProblems) {
                draft.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            if (!draft.isOtherTimes && !draft.isNew) {
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(" Delete this rate", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeRangeEditor(range: TimeRange, onChange: (TimeRange) -> Unit, onRemove: () -> Unit) {
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    var showMonths by remember { mutableStateOf(range.months != TimeRange.ALL_YEAR) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) {
                    Text("From ${TariffText.time(range.startMinute)}")
                }
                OutlinedButton(onClick = { picking = false }, modifier = Modifier.weight(1f)) {
                    Text("To ${TariffText.time(range.endMinute, isEnd = true)}")
                }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove this time") }
            }
            if (range.isAllDay) {
                Text("Same start and end: applies all day.", style = MaterialTheme.typography.bodySmall)
            } else if (range.endMinute < range.startMinute && range.endMinute != 0) {
                Text("Runs overnight, past midnight.", style = MaterialTheme.typography.bodySmall)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                localeWeek().forEach { day ->
                    FilterChip(
                        selected = day in range.days,
                        onClick = { onChange(range.copy(days = range.days.toggle(day))) },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                    )
                }
            }
            Row {
                listOf("Every day" to TimeRange.EVERY_DAY, "Weekdays" to TimeRange.WEEKDAYS, "Weekends" to TimeRange.WEEKENDS)
                    .forEach { (label, days) ->
                        TextButton(onClick = { onChange(range.copy(days = days)) }) { Text(label) }
                    }
            }

            if (showMonths) {
                Text("Months", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Month.entries.forEach { month ->
                        FilterChip(
                            selected = month in range.months,
                            onClick = { onChange(range.copy(months = range.months.toggle(month))) },
                            label = { Text(month.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                        )
                    }
                }
                TextButton(onClick = {
                    onChange(range.copy(months = TimeRange.ALL_YEAR))
                    showMonths = false
                }) { Text("Apply all year") }
            } else {
                TextButton(onClick = { showMonths = true }) { Text("Only in some months (seasonal)…") }
            }
        }
    }

    picking?.let { isStart ->
        TimePickerDialog(
            title = if (isStart) "Starts at" else "Ends at",
            initialMinute = if (isStart) range.startMinute else range.endMinute,
            onDismiss = { picking = null },
            onPick = { minute ->
                onChange(if (isStart) range.copy(startMinute = minute) else range.copy(endMinute = minute))
                picking = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(title: String, initialMinute: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item
