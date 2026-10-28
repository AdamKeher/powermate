package app.powermate.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.powermate.data.Tariff
import app.powermate.data.TimeRange
import app.powermate.ui.Format
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** Most time-of-use rates a plan can have: one per validated categorical colour. */
const val MAX_TIME_OF_USE_RATES = 8

// Categorical slots validated (adjacent CVD and normal-vision separation) against the app's
// light and dark surfaces. "All other times" is a neutral grey, like an "Other" bucket.
private val LightSlots = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF6250D6, 0xFFE34948)
private val DarkSlots = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300, 0xFF9085E9, 0xFFE66767)
private const val LIGHT_OTHER = 0xFFA9A8A1
private const val DARK_OTHER = 0xFF6B6A65

/** Colour for the rate at [index] in [Tariff.allRates]; it follows the rate everywhere it appears. */
@Composable
fun rateColor(tariff: Tariff, index: Int): Color {
    val dark = isSystemInDarkTheme()
    if (index >= tariff.rates.size) return Color(if (dark) DARK_OTHER else LIGHT_OTHER)
    return Color((if (dark) DarkSlots else LightSlots)[index % MAX_TIME_OF_USE_RATES])
}

@Composable
fun RateSwatch(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
}

object TariffText {
    /** Localised time of day; an end at 00:00 reads as "midnight". */
    fun time(minute: Int, isEnd: Boolean = false): String =
        if (isEnd && minute == 0) "midnight"
        else LocalTime.of(minute / 60, minute % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    fun days(days: Set<DayOfWeek>): String = when (days) {
        TimeRange.EVERY_DAY -> "every day"
        TimeRange.WEEKDAYS -> "weekdays"
        TimeRange.WEEKENDS -> "weekends"
        else -> runs(DayOfWeek.entries, days) { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }

    fun months(months: Set<Month>): String =
        if (months == TimeRange.ALL_YEAR) "all year"
        else runs(Month.entries, months) { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }

    fun range(range: TimeRange): String = buildString {
        append(if (range.isAllDay) "All day" else "${time(range.startMinute)} – ${time(range.endMinute, isEnd = true)}")
        append(", ").append(days(range.days))
        if (range.months != TimeRange.ALL_YEAR) append(", ").append(months(range.months))
    }

    /**
     * Joins the selected members of a cyclic sequence into runs, e.g. "Mon–Wed, Fri" or "Nov–Mar",
     * letting a run wrap from the end of [all] back to the start.
     */
    private fun <T> runs(all: List<T>, selected: Set<T>, name: (T) -> String): String {
        if (selected.isEmpty()) return "never"
        val n = all.size
        // Start just after a gap so a run that wraps around stays in one piece.
        val startIndex = (0 until n).firstOrNull { all[it] in selected && all[(it - 1 + n) % n] !in selected } ?: 0
        val parts = mutableListOf<String>()
        var i = 0
        while (i < n) {
            val item = all[(startIndex + i) % n]
            if (item !in selected) {
                i++
                continue
            }
            var j = i
            while (j + 1 < n && all[(startIndex + j + 1) % n] in selected) j++
            val last = all[(startIndex + j) % n]
            parts += when (j - i) {
                0 -> name(item)
                1 -> "${name(item)}, ${name(last)}"
                else -> "${name(item)}–${name(last)}"
            }
            i = j + 1
        }
        return parts.joinToString(", ")
    }
}

/** The days of the week in the user's locale order. */
fun localeWeek(): List<DayOfWeek> {
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    return (0L until 7L).map { first.plus(it) }
}

/**
 * Seven 24-hour bars, one per weekday, coloured by the rate in force, so gaps and overlaps in a
 * plan are visible at a glance. Rates are judged for [month] when the plan is seasonal.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TariffWeekTimeline(tariff: Tariff, month: Month, modifier: Modifier = Modifier) {
    val week = remember { localeWeek() }
    val segments = remember(tariff, month) {
        week.map { day ->
            val date = LocalDate.of(2026, month, 1).with(TemporalAdjusters.nextOrSame(day))
            rateSegments(tariff, date)
        }
    }
    val colors = tariff.allRates.indices.map { rateColor(tariff, it) }
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val labelWidth = 36.dp

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        week.forEachIndexed { row, day ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = labelStyle,
                    modifier = Modifier.width(labelWidth),
                )
                Canvas(Modifier.weight(1f).height(16.dp)) {
                    val gap = 1.dp.toPx()
                    segments[row].forEach { (from, to, index) ->
                        val left = from / 1440f * size.width + if (from > 0) gap else 0f
                        val right = to / 1440f * size.width - if (to < 1440) gap else 0f
                        if (right > left) {
                            drawRoundRect(
                                color = colors[index],
                                topLeft = Offset(left, 0f),
                                size = Size(right - left, size.height),
                                cornerRadius = CornerRadius(3.dp.toPx()),
                            )
                        }
                    }
                }
            }
        }
        Row {
            Spacer(Modifier.width(labelWidth))
            Canvas(Modifier.weight(1f).height(16.dp)) {
                listOf(0, 6, 12, 18, 24).forEach { hour ->
                    val text = measurer.measure(TariffText.time((hour % 24) * 60), labelStyle)
                    val x = (hour / 24f * size.width - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                    drawText(text, topLeft = Offset(x, 0f))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tariff.allRates.forEachIndexed { index, rate ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RateSwatch(colors[index])
                    Text(
                        " ${rate.name.ifBlank { "Unnamed" }} · ${Format.rate(rate.centsPerKwh)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** Runs of `(fromMinute, toMinute, rateIndex)` covering [date]. */
fun rateSegments(tariff: Tariff, date: LocalDate): List<Triple<Int, Int, Int>> {
    val segments = mutableListOf<Triple<Int, Int, Int>>()
    var start = 0
    var current = tariff.rateIndexAt(date.atStartOfDay())
    for (minute in 1..1440) {
        val index = if (minute == 1440) -1 else tariff.rateIndexAt(date.atTime(minute / 60, minute % 60))
        if (index != current) {
            segments += Triple(start, minute, current)
            start = minute
            current = index
        }
    }
    return segments
}
