package com.aksevenfour.powermate.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aksevenfour.powermate.data.Tariff
import com.aksevenfour.powermate.data.UsageCalculator
import com.aksevenfour.powermate.ui.Format
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Minutes of the day → clock angle in degrees (canvas convention), with midday at the top. */
private fun angleOf(minute: Float): Float = -90f + (minute - 720f) / 1440f * 360f

private fun minuteAt(angleDegrees: Float): Float = (((angleDegrees + 90f) / 360f * 1440f + 720f) % 1440f + 1440f) % 1440f

/** Wedges sweep in around the dial over this long when a day is first shown. */
private const val SWEEP_MS = 1_100

/** New readings for the day on show ease wedges to their new lengths over this long. */
private const val MORPH_MS = 500

/** The share of the day growing at once during the sweep: a 6-hour leading edge. */
private const val SWEEP_SPREAD = 0.25f

/**
 * Wedge lengths, as fractions of full scale, easing between readings. A new day grows in from
 * nothing in a clockwise sweep from midnight; new readings for the same day ease into place.
 */
private class WedgeMotion(size: Int) {
    private var from = FloatArray(size)
    private var to = FloatArray(size)
    // State, so a draw that skipped the wedges for a new day still redraws once its sweep starts.
    private var shownDate by mutableStateOf<LocalDate?>(null)
    private var sweep = false
    private val progress = Animatable(1f)

    suspend fun animateTo(date: LocalDate, target: FloatArray) {
        val newDay = date != shownDate
        from = if (newDay) FloatArray(target.size) else FloatArray(target.size) { fraction(it) }
        to = target
        sweep = newDay
        shownDate = date
        progress.snapTo(0f)
        progress.animateTo(1f, tween(if (newDay) SWEEP_MS else MORPH_MS, easing = LinearEasing))
    }

    fun target(q: Int): Float = to[q]

    /** False for the frame between a new day arriving and its sweep starting, so the old day doesn't flash. */
    fun isShowing(date: LocalDate): Boolean = date == shownDate

    /** How far slot [q] is through its change, eased. Reads animation state, so call it while drawing. */
    fun eased(q: Int): Float {
        val p = progress.value
        val spread = to.size * SWEEP_SPREAD
        val t = if (sweep) ((p * (to.size + spread) - q) / spread).coerceIn(0f, 1f) else p
        return FastOutSlowInEasing.transform(t)
    }

    fun fraction(q: Int): Float = from[q] + (to[q] - from[q]) * eased(q)
}

/**
 * A 24-hour dial of one day's use, midnight at the bottom and midday at the top. The day is split
 * into equal slots ([values], e.g. 96 quarter hours of energy or 288 five-minute power averages),
 * each a spike whose length is its value, coloured by the rate in force. Full scale is the day's
 * highest value, but never less than [minFullScale], so a quiet day isn't blown up to look busy.
 * The outer ring shows the tariff's rates; [nowMinute] draws a hand at the current time. Tapping
 * or dragging over the dial selects a quarter hour; tapping the centre clears it. [center] fills
 * the hole in the middle.
 *
 * The ring draws itself in when the clock appears, wedges sweep in for each day and ease to new
 * readings, and the selection hand glides between quarter hours.
 */
@Composable
fun EnergyClock(
    values: List<Double>,
    hasData: List<Boolean>,
    minFullScale: Double,
    date: LocalDate,
    tariff: Tariff,
    nowMinute: Int?,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit,
) {
    val segments = remember(tariff, date) { rateSegments(tariff, date) }
    val slots = values.size.coerceAtLeast(1)
    val slotMinutes = 1440 / slots
    val slotRates = remember(tariff, date, slots) {
        IntArray(slots) { q -> (q * slotMinutes).let { m -> tariff.rateIndexAt(date.atTime(m / 60, m % 60)) } }
    }
    val colors = tariff.allRates.indices.map { rateColor(tariff, it) }
    val guide = MaterialTheme.colorScheme.outlineVariant
    val hand = MaterialTheme.colorScheme.onSurface
    // White on the dark theme; on a light background white would vanish, so use the theme's ink.
    val selection = if (isSystemInDarkTheme()) Color.White else MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val latestOnSelect by rememberUpdatedState(onSelect)
    val target = remember(values, hasData, minFullScale) {
        val fullScale = maxOf(values.maxOrNull() ?: 0.0, minFullScale)
        FloatArray(slots) { q ->
            val value = values.getOrElse(q) { 0.0 }
            if (hasData.getOrElse(q) { false } && value > 0) (value / fullScale).toFloat() else 0f
        }
    }
    // A different slot count (switching energy and power) gets a fresh sweep.
    val wedges = remember(slots) { WedgeMotion(slots) }
    LaunchedEffect(wedges, date, target) { wedges.animateTo(date, target) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(SWEEP_MS, easing = FastOutSlowInEasing)) }
    // The selection hand, in minutes of the day. Unwrapped, so it takes the short way past midnight.
    val handMinute = remember { Animatable(0f) }
    var handShown by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        if (selected == null) {
            handShown = false
            return@LaunchedEffect
        }
        var goal = selected * 15f + 7.5f
        if (!handShown) {
            handMinute.snapTo(goal)
            handShown = true
            return@LaunchedEffect
        }
        while (goal - handMinute.value > 720f) goal -= 1440f
        while (handMinute.value - goal > 720f) goal += 1440f
        handMinute.animateTo(goal, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
    }

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val diameter: Dp = minOf(maxWidth, 380.dp)
        // Labels run along the ring, so the margin only needs to fit one line of text.
        val labelSpace = 16.dp
        val ringWidth = 12.dp
        val outerRadius = diameter / 2 - labelSpace
        val innerRadius = outerRadius * 0.42f

        fun select(position: Offset, sizePx: Float, innerPx: Float) {
            val dx = position.x - sizePx / 2
            val dy = position.y - sizePx / 2
            if (sqrt(dx * dx + dy * dy) < innerPx * 0.9f) {
                latestOnSelect(null)
                return
            }
            val minute = minuteAt((atan2(dy, dx) * 180 / PI).toFloat())
            latestOnSelect((minute / 15).toInt().coerceIn(0, UsageCalculator.QUARTERS_PER_DAY - 1))
        }

        Canvas(
            Modifier
                .size(diameter)
                .pointerInput(Unit) {
                    detectTapGestures { select(it, size.width.toFloat(), innerRadius.toPx()) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ -> select(change.position, size.width.toFloat(), innerRadius.toPx()) }
                },
        ) {
            val c = Offset(size.width / 2, size.height / 2)
            val outer = outerRadius.toPx()
            val inner = innerRadius.toPx()
            val ring = ringWidth.toPx()
            val ringCentre = outer - ring / 2
            val barStart = inner + 2.dp.toPx()
            val barMax = ringCentre - ring / 2 - 6.dp.toPx() - barStart
            fun point(minute: Float, radius: Float): Offset {
                val a = angleOf(minute) * PI / 180
                return Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat())
            }

            for (hour in 0 until 24 step 2) {
                drawLine(guide, point(hour * 60f, barStart), point(hour * 60f, barStart + barMax), strokeWidth = 1.dp.toPx())
            }

            // Each slot is a wedge filling its whole width, with a hairline gap between neighbours
            // (measured at the inner edge, where wedges are narrowest; thinner for narrow slots).
            val slotDegrees = 360f / slots
            val gapDegrees = minOf((1.5.dp.toPx() / barStart * 180 / PI).toFloat(), slotDegrees * 0.3f)
            val slotsPerQuarter = (15 / slotMinutes).coerceAtLeast(1)
            var selectedWedge: Path? = null
            val minLength = 2.dp.toPx()
            val showing = wedges.isShowing(date)
            val lengths = FloatArray(slots) { q ->
                if (!showing) return@FloatArray 0f
                val length = wedges.fraction(q) * barMax
                // Keep tiny readings visible once grown, without popping in ahead of the sweep.
                if (wedges.target(q) > 0) maxOf(length, minLength * wedges.eased(q)) else length
            }
            for (q in lengths.indices) {
                val length = lengths[q]
                if (length < 0.5f) continue
                val inSelection = selected != null && q / slotsPerQuarter == selected
                val dim = selected != null && !inSelection
                val start = angleOf(q * slotMinutes.toFloat()) + gapDegrees / 2
                val sweep = (slotDegrees - gapDegrees).coerceAtLeast(0.5f)
                val wedge = Path().apply {
                    arcTo(Rect(c, barStart + length), start, sweep, forceMoveTo = true)
                    arcTo(Rect(c, barStart), start + sweep, -sweep, forceMoveTo = false)
                    close()
                }
                drawPath(wedge, colors[slotRates[q]].copy(alpha = if (dim) 0.3f else 1f))
                // Only whole quarter-hour wedges get an outline; thin slots rely on the dimming.
                if (inSelection && slotsPerQuarter == 1) selectedWedge = wedge
            }
            selectedWedge?.let { drawPath(it, selection, style = Stroke(width = 1.5.dp.toPx())) }

            // The ring draws itself clockwise from midnight as the clock appears.
            val gap = 0.6f
            val revealed = reveal.value * 1440f
            segments.forEach { (from, to, index) ->
                if (from >= revealed) return@forEach
                val sweep = (minOf(to.toFloat(), revealed) - from) / 1440f * 360f
                if (sweep <= 2 * gap) return@forEach
                drawArc(
                    color = colors[index],
                    startAngle = angleOf(from.toFloat()) + gap,
                    sweepAngle = sweep - 2 * gap,
                    useCenter = false,
                    topLeft = Offset(c.x - ringCentre, c.y - ringCentre),
                    size = Size(ringCentre * 2, ringCentre * 2),
                    style = Stroke(width = ring),
                )
            }

            // Hour labels wrap around the ring: rotated to follow the circle, reading left to right
            // on the top half and flipped on the bottom half so none are upside down.
            for (hour in 0 until 24 step 2) {
                val text = measurer.measure(hourLabel(hour), labelStyle)
                val angle = angleOf(hour * 60f)
                val at = point(hour * 60f, outer + labelSpace.toPx() / 2)
                val onTopHalf = sin(angle * PI / 180) <= 1e-6
                rotate(if (onTopHalf) angle + 90f else angle - 90f, pivot = at) {
                    drawText(text, topLeft = Offset(at.x - text.size.width / 2f, at.y - text.size.height / 2f), alpha = reveal.value)
                }
            }

            if (nowMinute != null) {
                // Faded while a quarter hour is selected, so it isn't mistaken for the selection.
                val nowColor = if (selected != null) hand.copy(alpha = 0.35f) else hand
                val tip = point(nowMinute.toFloat(), outer)
                drawLine(nowColor, point(nowMinute.toFloat(), inner), tip, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(nowColor, radius = 3.dp.toPx(), center = tip)
            }

            // The selected quarter hour gets its own hand, following the finger around the dial. It
            // starts beyond the wedge so the outlined wedge stays visible.
            if (selected != null && handShown) {
                val minute = handMinute.value
                val under = (((minute / slotMinutes).toInt() % slots) + slots) % slots
                val tip = point(minute, outer + 2.dp.toPx())
                val from = minOf(barStart + lengths[under] + 4.dp.toPx(), outer - 4.dp.toPx())
                drawLine(selection, point(minute, from), tip, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(selection, radius = 5.dp.toPx(), center = tip)
            }
        }
        Box(Modifier.size(innerRadius * 2 * 0.94f), contentAlignment = Alignment.Center) { center() }
    }
}

/** Swatches naming each rate, for under the clock and other rate-coloured views. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RateLegend(tariff: Tariff, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tariff.allRates.forEachIndexed { index, rate ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RateSwatch(rateColor(tariff, index))
                Text(" ${rate.name.ifBlank { "Unnamed" }} · ${Format.rate(rate.centsPerKwh)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun hourLabel(hour: Int): String =
    LocalTime.of(hour, 0).format(DateTimeFormatter.ofPattern("ha", Locale.getDefault())).lowercase(Locale.getDefault())
