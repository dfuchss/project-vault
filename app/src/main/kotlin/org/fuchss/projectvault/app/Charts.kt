package org.fuchss.projectvault.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun StatCard(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified, estimated: Boolean = false) {
    // An "estimated" card (e.g. expected income) is visually set apart: a tinted container, a primary
    // outline, an "≈ EST" tag and a "≈" prefix on the value, so the number never reads as an actual.
    val accent = MaterialTheme.colorScheme.primary
    Card(
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
        border = if (estimated) BorderStroke(1.dp, accent.copy(alpha = 0.5f)) else null,
        colors = if (estimated) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (estimated) {
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(4.dp), color = accent.copy(alpha = 0.16f)) {
                        Text(
                            LocalStrings.current.estTag,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (estimated) "≈ $value" else value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }
    }
}

@Composable
internal fun CategoryBar(name: String, color: Color, amount: Long, fraction: Float) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color)
            Spacer(Modifier.width(6.dp))
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatCents(amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(4.dp))
        Bar(fraction, color)
    }
}

@Composable
internal fun Bar(fraction: Float, color: Color) {
    Box(
        Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color))),
        )
    }
}

/** A ring chart: each slice's color and (positive) weight. Weights are normalized to 360°. */
@Composable
internal fun DonutChart(slices: List<Pair<Color, Float>>, trackColor: Color, modifier: Modifier = Modifier, thickness: Dp = 18.dp) {
    Canvas(modifier) {
        val stroke = thickness.toPx()
        val d = minOf(size.width, size.height) - stroke
        val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        val arcSize = Size(d, d)
        drawArc(color = trackColor, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
        val total = slices.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(1e-6f)
        var start = -90f
        slices.forEach { (color, value) ->
            val sweep = value / total * 360f
            drawArc(color = color, startAngle = start, sweepAngle = (sweep - 3f).coerceAtLeast(0.5f), useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            start += sweep
        }
    }
}

/**
 * A line-and-area sparkline for a signed series. The line and its **Farbverlauf** (gradient) area are
 * green above the zero baseline and red below it, so a positive value reads green and a negative one
 * red at a glance. Hovering snaps to the nearest point and shows a value tooltip with a guide line, so
 * the exact state at any point is readable. Each point is a `label to cents`.
 *
 * [anchorZero] controls the vertical range: `true` (default) forces zero into the range so the sign
 * baseline is always meaningful — right for a monthly-net series that swings around zero. `false`
 * scales to the data's own min/max so small month-to-month movement stays visible — right for a
 * balance trajectory that sits well above zero (the zero baseline then only appears if the balance
 * actually dips negative, i.e. a projected shortfall).
 *
 * [band] (optional, one `(lowerCents, upperCents)` per point) draws a translucent uncertainty region
 * around the line — used by the forecast to show the ±1σ spread of estimated variable spending. It is
 * included in the vertical range so the whole cone is visible, and the hover tooltip shows the ± span.
 */
@Composable
internal fun TrendChart(
    points: List<Pair<String, Long>>,
    modifier: Modifier = Modifier,
    anchorZero: Boolean = true,
    band: List<Pair<Long, Long>>? = null,
) {
    if (points.size < 2) return
    val values = points.map { it.second.toFloat() }
    val bandLo = band?.map { it.first.toFloat() }
    val bandHi = band?.map { it.second.toFloat() }
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val tooltipBg = MaterialTheme.colorScheme.inverseSurface
    val tooltipFg = MaterialTheme.colorScheme.inverseOnSurface
    val guideColor = MaterialTheme.colorScheme.outline
    val bandColor = MaterialTheme.colorScheme.onSurfaceVariant
    val positive = MoneyPositive
    val negative = MoneyNegative
    val strings = LocalStrings.current
    var hovered by remember { mutableStateOf<Int?>(null) }

    Canvas(
        modifier.pointerInput(points.size) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Exit) {
                        hovered = null
                    } else {
                        val x = event.changes.first().position.x
                        val stepX = size.width / (points.size - 1)
                        hovered = if (stepX <= 0f) 0 else (x / stepX).roundToInt().coerceIn(0, points.size - 1)
                    }
                }
            }
        },
    ) {
        // Include zero (when anchored) and the band extremes in the range so everything stays visible;
        // otherwise scale to the data so small movements stay visible.
        val dataMin = minOf(values.min(), bandLo?.min() ?: Float.MAX_VALUE)
        val dataMax = maxOf(values.max(), bandHi?.max() ?: Float.MIN_VALUE)
        val allMin = if (anchorZero) minOf(dataMin, 0f) else dataMin
        val allMax = if (anchorZero) maxOf(dataMax, 0f) else dataMax
        val range = (allMax - allMin).coerceAtLeast(1f)
        val stepX = size.width / (points.size - 1)
        val pad = size.height * 0.12f
        fun y(v: Float) = size.height - pad - (v - allMin) / range * (size.height - 2 * pad)
        fun colorAt(v: Float) = if (v >= 0f) positive else negative
        val zeroY = y(0f).coerceIn(0f, size.height)

        // Uncertainty band: a filled ribbon between the upper and lower edges, drawn behind the line.
        if (bandLo != null && bandHi != null) {
            val ribbon = Path().apply {
                bandHi.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(i * stepX, y(v)) }
                for (i in bandLo.indices.reversed()) lineTo(i * stepX, y(bandLo[i]))
                close()
            }
            drawPath(ribbon, bandColor.copy(alpha = 0.18f))
        }

        val line = Path().apply {
            values.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(i * stepX, y(v)) }
        }
        // Area between the line and the zero baseline (dips below zero for negative months).
        val area = Path().apply {
            addPath(line)
            lineTo((points.size - 1) * stepX, zeroY)
            lineTo(0f, zeroY)
            close()
        }
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)

        // Clip to above/below the zero baseline and paint each half in its sign colour + gradient.
        clipRect(0f, 0f, size.width, zeroY) {
            drawPath(area, Brush.verticalGradient(listOf(positive.copy(alpha = 0.32f), positive.copy(alpha = 0f)), startY = 0f, endY = zeroY.coerceAtLeast(1f)))
            drawPath(line, positive, style = stroke)
        }
        clipRect(0f, zeroY, size.width, size.height) {
            drawPath(area, Brush.verticalGradient(listOf(negative.copy(alpha = 0f), negative.copy(alpha = 0.32f)), startY = zeroY, endY = size.height))
            drawPath(line, negative, style = stroke)
        }
        // Faint zero reference (only when zero is actually within range) and per-point dots by sign.
        if (0f in allMin..allMax) {
            drawLine(guideColor.copy(alpha = 0.5f), start = Offset(0f, zeroY), end = Offset(size.width, zeroY), strokeWidth = 1.dp.toPx())
        }
        values.forEachIndexed { i, v -> drawCircle(colorAt(v), radius = 3.dp.toPx(), center = Offset(i * stepX, y(v))) }

        val h = hovered ?: return@Canvas
        val hx = h * stepX
        val hy = y(values[h])
        // Guide line + emphasized point.
        drawLine(guideColor, start = Offset(hx, 0f), end = Offset(hx, size.height), strokeWidth = 1.dp.toPx())
        drawCircle(onSurface, radius = 5.5.dp.toPx(), center = Offset(hx, hy))
        drawCircle(colorAt(values[h]), radius = 3.5.dp.toPx(), center = Offset(hx, hy))

        // Tooltip: "MM/YYYY · +1.234,56 €"; when a band is shown, a second line gives the expected
        // range "min … max". Clamped to stay within the chart bounds.
        val bounds = band?.getOrNull(h)?.takeIf { it.second > it.first }
        val line1 = "${points[h].first} · ${formatCents(points[h].second)}"
        val line2 = bounds?.let { strings.expectedRange(formatCents(it.first), formatCents(it.second)) }
        val style11 = TextStyle(fontSize = 11.sp)
        val l1 = measurer.measure(line1, style = style11)
        val l2 = line2?.let { measurer.measure(it, style = TextStyle(fontSize = 10.sp)) }
        val padX = 6.dp.toPx(); val padY = 3.dp.toPx(); val gap = 2.dp.toPx()
        val textW = maxOf(l1.size.width, l2?.size?.width ?: 0).toFloat()
        val textH = l1.size.height.toFloat() + (l2?.let { it.size.height + gap } ?: 0f)
        val boxW = textW + padX * 2
        val boxH = textH + padY * 2
        val boxX = (hx - boxW / 2).coerceIn(0f, size.width - boxW)
        val boxY = (hy - boxH - 8.dp.toPx()).coerceAtLeast(0f)
        drawRoundRect(tooltipBg, topLeft = Offset(boxX, boxY), size = Size(boxW, boxH), cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()))
        drawText(l1, color = tooltipFg, topLeft = Offset(boxX + padX, boxY + padY))
        if (l2 != null) drawText(l2, color = tooltipFg.copy(alpha = 0.8f), topLeft = Offset(boxX + padX, boxY + padY + l1.size.height + gap))
    }
}
