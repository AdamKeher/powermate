package app.powermate.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Rounds [value] up to 1, 2, 2.5 or 5 times a power of ten, for tidy axis maxima. */
fun niceCeiling(value: Double): Double {
    if (value <= 0 || value.isNaN()) return 1.0
    val magnitude = 10.0.pow(floor(log10(value)))
    val fraction = value / magnitude
    val nice = when {
        fraction <= 1.0 -> 1.0
        fraction <= 2.0 -> 2.0
        fraction <= 2.5 -> 2.5
        fraction <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * magnitude
}

/**
 * Tappable bar chart with a left value axis. Tapping a bar selects it; tapping it again clears the
 * selection; double-tapping one calls [onDoubleTap] when given (single taps then wait a moment to
 * rule out a second). Bars with [hasData] false are drawn faintly so gaps in history are visible.
 *
 * With [segments], each bar is a stack: `segments[bar][series]`, bottom to top in series order,
 * coloured by [segmentColors]; [values] should then be the stack totals.
 */
@Composable
fun BarChart(
    values: List<Double>,
    labels: List<String>,
    hasData: List<Boolean>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    formatAxis: (Double) -> String,
    modifier: Modifier = Modifier,
    labelEvery: Int = 1,
    height: Dp = 220.dp,
    segments: List<List<Double>>? = null,
    segmentColors: List<Color> = emptyList(),
    onDoubleTap: ((Int) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val maxValue = niceCeiling(values.maxOrNull() ?: 0.0)
    val axisLabels = listOf(maxValue, maxValue / 2).map { formatAxis(it) }
    val axisWidth = axisLabels.maxOf { measurer.measure(it, labelStyle).size.width }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(values.size, axisWidth, onDoubleTap != null) {
                fun barAt(x: Float): Int? {
                    val left = axisWidth + 8.dp.toPx()
                    val index = ((x - left) / ((size.width - left) / values.size)).toInt()
                    return index.takeIf { x >= left && it in values.indices }
                }
                detectTapGestures(
                    onDoubleTap = if (onDoubleTap != null) { offset -> barAt(offset.x)?.let { currentOnDoubleTap?.invoke(it) } } else null,
                ) { offset ->
                    currentOnSelect(barAt(offset.x)?.takeIf { it != currentSelected })
                }
            },
    ) {
        if (values.isEmpty()) return@Canvas
        val left = axisWidth + 8.dp.toPx()
        val labelHeight = measurer.measure("0", labelStyle).size.height
        val bottom = size.height - labelHeight - 6.dp.toPx()
        val top = labelHeight / 2f
        val chartHeight = bottom - top
        val slot = (size.width - left) / values.size
        val barWidth = minOf(slot * 0.7f, 28.dp.toPx())

        listOf(0.0, maxValue / 2, maxValue).forEach { gridValue ->
            val y = bottom - (gridValue / maxValue * chartHeight).toFloat()
            drawLine(colors.outlineVariant, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            if (gridValue > 0) {
                val text = measurer.measure(formatAxis(gridValue), labelStyle)
                drawText(text, topLeft = Offset(0f, y - text.size.height / 2f))
            }
        }

        values.forEachIndexed { i, value ->
            val barHeight = (value / maxValue * chartHeight).toFloat().coerceAtLeast(if (hasData.getOrElse(i) { false }) 2f else 0f)
            val x = left + i * slot + (slot - barWidth) / 2
            val stack = segments?.getOrNull(i)
            if (stack != null && hasData.getOrElse(i) { false } && value > 0) {
                drawStack(stack, segmentColors, x, bottom, barWidth, chartHeight / maxValue.toFloat(), dim = selected != null && selected != i)
            } else {
                val color = when {
                    selected == i -> colors.tertiary
                    !hasData.getOrElse(i) { false } -> colors.surfaceVariant
                    selected != null -> colors.primary.copy(alpha = 0.45f)
                    else -> colors.primary
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, bottom - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 4, barWidth / 4),
                )
            }
            if (i % labelEvery == 0 && i < labels.size) {
                val text = measurer.measure(labels[i], labelStyle)
                drawText(text, topLeft = Offset(left + i * slot + slot / 2 - text.size.width / 2f, bottom + 4.dp.toPx()))
            }
        }
    }
}

/** One stacked bar: segments bottom-up, a hairline gap between them, only the top one rounded. */
private fun DrawScope.drawStack(
    stack: List<Double>,
    colors: List<Color>,
    x: Float,
    bottom: Float,
    width: Float,
    pxPerUnit: Float,
    dim: Boolean,
) {
    val gap = 2.dp.toPx()
    val shown = stack.withIndex().filter { it.value > 0 }
    var y = bottom
    shown.forEachIndexed { n, (series, amount) ->
        val h = (amount * pxPerUnit).toFloat()
        val isTop = n == shown.lastIndex
        val drawn = (if (n > 0) h - gap else h).coerceAtLeast(1f)
        val color = colors.getOrElse(series) { Color.Gray }.copy(alpha = if (dim) 0.35f else 1f)
        val topLeft = Offset(x, y - drawn - if (n > 0) gap else 0f)
        if (isTop) {
            drawRoundRect(color, topLeft, Size(width, drawn), CornerRadius(minOf(width / 4, drawn / 2)))
        } else {
            drawRect(color, topLeft, Size(width, drawn))
        }
        y -= h
    }
}

/** Filled line chart of (time, value) points over the window ending at [endMillis]. */
@Composable
fun Sparkline(
    points: List<Pair<Long, Double>>,
    windowMs: Long,
    endMillis: Long,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
) {
    val line = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (points.size < 2) return@Canvas
        val maxValue = niceCeiling(points.maxOf { it.second })
        val startMillis = endMillis - windowMs
        fun x(t: Long) = ((t - startMillis).toFloat() / windowMs * size.width).coerceIn(0f, size.width)
        fun y(v: Double) = size.height - (v / maxValue * size.height).toFloat()

        val path = Path()
        points.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
        val fill = Path().apply {
            addPath(path)
            lineTo(x(points.last().first), size.height)
            lineTo(x(points.first().first), size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), line.copy(alpha = 0.02f))))
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * Line (or [stepped]) chart over evenly spaced points. A null value is a gap. Each segment takes the
 * colour of the point it starts at, with a light fill down to zero; negative values get their own
 * axis range. Tapping or dragging selects a point; tapping the selection again clears it;
 * double-tapping calls [onDoubleTap] when given. [nowIndex] (fractional) draws a dashed "now" marker.
 */
@Composable
fun LineChart(
    values: List<Double?>,
    pointColors: List<Color>,
    xLabels: List<Pair<Int, String>>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    formatAxis: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    stepped: Boolean = false,
    nowIndex: Float? = null,
    onDoubleTap: ((Int) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val present = values.filterNotNull()
    val maxValue = niceCeiling(present.maxOrNull() ?: 0.0)
    val minValue = present.minOrNull()?.takeIf { it < 0 }?.let { -niceCeiling(-it) } ?: 0.0
    val axisWidth = listOf(maxValue, minValue).maxOf { measurer.measure(formatAxis(it), labelStyle).size.width }

    fun indexAt(x: Float, width: Float, left: Float): Int? {
        if (values.isEmpty() || x < left) return null
        return ((x - left) / ((width - left) / values.size)).toInt().coerceIn(0, values.lastIndex)
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(values.size, axisWidth, onDoubleTap != null) {
                detectTapGestures(
                    onDoubleTap = if (onDoubleTap != null) {
                        { offset ->
                            indexAt(offset.x, size.width.toFloat(), axisWidth + 8.dp.toPx())?.let { currentOnDoubleTap?.invoke(it) }
                        }
                    } else {
                        null
                    },
                ) { offset ->
                    val index = indexAt(offset.x, size.width.toFloat(), axisWidth + 8.dp.toPx())
                    currentOnSelect(if (index == currentSelected) null else index)
                }
            }
            .pointerInput(values.size, axisWidth) {
                detectDragGestures { change, _ ->
                    indexAt(change.position.x, size.width.toFloat(), axisWidth + 8.dp.toPx())?.let(currentOnSelect)
                }
            },
    ) {
        if (values.isEmpty()) return@Canvas
        val left = axisWidth + 8.dp.toPx()
        val labelHeight = measurer.measure("0", labelStyle).size.height
        val bottom = size.height - labelHeight - 6.dp.toPx()
        val top = labelHeight / 2f + if (nowIndex != null) labelHeight.toFloat() else 0f
        val range = (maxValue - minValue).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = bottom - ((v - minValue) / range * (bottom - top)).toFloat()
        val slot = (size.width - left) / values.size
        fun xStart(i: Int) = left + i * slot
        fun xCentre(i: Int) = xStart(i) + slot / 2
        val zeroY = y(0.0)

        val grid = buildList {
            add(maxValue)
            add(maxValue / 2)
            add(0.0)
            if (minValue < 0) add(minValue)
        }
        grid.forEach { g ->
            val gy = y(g)
            drawLine(colors.outlineVariant, Offset(left, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx())
            if (g != 0.0 || minValue < 0) {
                val text = measurer.measure(formatAxis(g), labelStyle)
                drawText(text, topLeft = Offset(0f, gy - text.size.height / 2f))
            }
        }

        val stroke = 2.dp.toPx()
        for (i in values.indices) {
            val v = values[i] ?: continue
            val color = pointColors.getOrElse(i) { colors.primary }
            if (stepped) {
                val x0 = xStart(i)
                val x1 = xStart(i + 1)
                drawRect(color.copy(alpha = 0.16f), Offset(x0, minOf(y(v), zeroY)), Size(x1 - x0, kotlin.math.abs(zeroY - y(v))))
                drawLine(color, Offset(x0, y(v)), Offset(x1, y(v)), strokeWidth = stroke)
                val next = values.getOrNull(i + 1)
                if (next != null) {
                    drawLine(pointColors.getOrElse(i + 1) { color }, Offset(x1, y(v)), Offset(x1, y(next)), strokeWidth = stroke)
                }
            } else {
                val next = values.getOrNull(i + 1) ?: continue
                val a = Offset(xCentre(i), y(v))
                val b = Offset(xCentre(i + 1), y(next))
                val area = Path().apply {
                    moveTo(a.x, zeroY)
                    lineTo(a.x, a.y)
                    lineTo(b.x, b.y)
                    lineTo(b.x, zeroY)
                    close()
                }
                drawPath(area, color.copy(alpha = 0.16f))
                drawLine(color, a, b, strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }

        if (nowIndex != null) {
            val nx = left + nowIndex * slot
            drawLine(
                colors.onSurface,
                Offset(nx, top),
                Offset(nx, bottom),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
            val text = measurer.measure("NOW", labelStyle.copy(color = colors.onSurface))
            drawText(text, topLeft = Offset((nx - text.size.width / 2f).coerceIn(left, size.width - text.size.width), top - text.size.height))
        }

        val sel = selected
        val selValue = sel?.let { values.getOrNull(it) }
        if (sel != null) {
            val sx = if (stepped) xStart(sel) + slot / 2 else xCentre(sel)
            drawLine(colors.onSurface, Offset(sx, top), Offset(sx, bottom), strokeWidth = 1.dp.toPx())
            if (selValue != null) {
                drawCircle(colors.surface, radius = 6.dp.toPx(), center = Offset(sx, y(selValue)))
                drawCircle(pointColors.getOrElse(sel) { colors.primary }, radius = 4.dp.toPx(), center = Offset(sx, y(selValue)))
            }
        }

        xLabels.forEach { (index, label) ->
            val text = measurer.measure(label, labelStyle)
            val x = (if (stepped) xStart(index) else xCentre(index)) - text.size.width / 2f
            drawText(text, topLeft = Offset(x.coerceIn(left - text.size.width / 2f, size.width - text.size.width), bottom + 4.dp.toPx()))
        }
    }
}
