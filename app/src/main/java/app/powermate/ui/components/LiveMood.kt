package app.powermate.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * How busy the house is right now, as a background colour. Powerpal's user guide describes its
 * app the same way ("blue/green for low energy use, amber for moderate energy use and red for high
 * energy use") but gives no thresholds, so these are PowerMate's own. Colours blend smoothly
 * between the levels; every one keeps white text at 4.5:1 contrast or better.
 */
object LiveMood {
    enum class Level(val label: String) { OFFLINE("Not connected"), LOW("Low use"), MODERATE("Moderate use"), HIGH("High use") }

    private data class Stop(val watts: Double, val top: Color, val bottom: Color)

    private val offline = Stop(0.0, Color(0xFF1E5AA8), Color(0xFF0E3466))
    private val stops = listOf(
        Stop(600.0, Color(0xFF1B7F5A), Color(0xFF0E4F3A)),
        Stop(1_800.0, Color(0xFFA86200), Color(0xFF6A3D00)),
        Stop(4_000.0, Color(0xFFC0392B), Color(0xFF7A1E15)),
    )

    fun level(watts: Double?): Level = when {
        watts == null -> Level.OFFLINE
        watts < 1_200 -> Level.LOW
        watts < 2_900 -> Level.MODERATE
        else -> Level.HIGH
    }

    /** Top and bottom gradient colours for [watts]; null means no live reading. */
    fun colors(watts: Double?): Pair<Color, Color> {
        if (watts == null) return offline.top to offline.bottom
        if (watts <= stops.first().watts) return stops.first().top to stops.first().bottom
        if (watts >= stops.last().watts) return stops.last().top to stops.last().bottom
        val upper = stops.indexOfFirst { it.watts >= watts }
        val a = stops[upper - 1]
        val b = stops[upper]
        val t = ((watts - a.watts) / (b.watts - a.watts)).toFloat()
        return lerp(a.top, b.top, t) to lerp(a.bottom, b.bottom, t)
    }
}

/** Vertical gradient for [watts] that eases between colours as usage changes. */
@Composable
fun liveMoodBrush(watts: Double?): Brush {
    val (top, bottom) = LiveMood.colors(watts)
    val animatedTop by animateColorAsState(top, tween(1_200), label = "moodTop")
    val animatedBottom by animateColorAsState(bottom, tween(1_200), label = "moodBottom")
    return Brush.verticalGradient(listOf(animatedTop, animatedBottom))
}

/**
 * A ring that ripples outward once per meter pulse. The Powerpal reports the time between the
 * meter's pulses, so the ripple keeps the meter's own rhythm: one ripple per watt-hour at
 * 1000 imp/kWh, faster as usage rises. Very fast rhythms are slowed to stay comfortable to watch.
 * [intervalMs] null (not connected) stops it.
 */
@Composable
fun MeterPulse(intervalMs: Long?, modifier: Modifier = Modifier, color: Color = Color.White) {
    val progress = remember { Animatable(1f) }
    val latestInterval by rememberUpdatedState(intervalMs)
    LaunchedEffect(Unit) {
        while (true) {
            val interval = latestInterval
            if (interval == null) {
                progress.snapTo(1f)
                delay(500)
                continue
            }
            val period = interval.coerceIn(MIN_PULSE_MS, MAX_PULSE_MS)
            val ripple = min(period, RIPPLE_MS)
            progress.snapTo(0f)
            progress.animateTo(1f, tween(ripple.toInt(), easing = LinearOutSlowInEasing))
            delay(period - ripple)
        }
    }
    Canvas(modifier) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        val base = size.minDimension / 2 * 0.62f
        val radius = base + (size.minDimension / 2 - base) * p
        drawCircle(color.copy(alpha = 0.45f * (1 - p)), radius, style = Stroke(width = 3.dp.toPx() * (1 - p) + 1.dp.toPx()))
    }
}

private const val MIN_PULSE_MS = 450L
private const val MAX_PULSE_MS = 8_000L
private const val RIPPLE_MS = 1_400L
