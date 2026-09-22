package com.proteinscribe.app.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * One progress ring for one tracked quantity.
 *
 * Deliberately generic rather than protein-specific: only the protein ring is shown
 * today, but the home screen is being laid out so calories/carbs/fat can join it
 * without a rewrite, and they differ only in label, value and color.
 */
@Composable
fun MacroRing(
    value: Double,
    goal: Double,
    label: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    strokeWidth: Dp = 14.dp,
) {
    val fraction = if (goal > 0) (value / goal).toFloat() else 0f
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 550),
        label = "ringProgress",
    )
    // Past the goal a second, brighter arc laps the ring, so overshoot stays visible
    // instead of a full ring reading identically at 100% and 160%.
    val overflow by animateFloatAsState(
        targetValue = (fraction - 1f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 550),
        label = "ringOverflow",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(size)) {
                val stroke = strokeWidth.toPx()
                val diameter = min(this.size.width, this.size.height) - stroke
                val topLeft = Offset(
                    (this.size.width - diameter) / 2f,
                    (this.size.height - diameter) / 2f,
                )
                val arcSize = Size(diameter, diameter)

                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (animated > 0f) {
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 360f * animated,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
                if (overflow > 0f) {
                    drawArc(
                        color = color.copy(alpha = 0.45f),
                        startAngle = -90f,
                        sweepAngle = 360f * overflow,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke / 2f, cap = StrokeCap.Round),
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    value.toInt().toString(),
                    style = MaterialTheme.typography.displaySmall,
                    fontSize = (size.value * 0.26f).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "of ${goal.toInt()}$unit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/**
 * Row of rings, sized so one ring reads as the hero and several stay legible side by
 * side. Today it is called with a single ring; the sizing rule is what makes adding
 * calories/carbs/fat later a data change rather than a layout change.
 */
@Composable
fun MacroRingRow(
    modifier: Modifier = Modifier,
    rings: List<@Composable (Dp) -> Unit>,
) {
    val ringSize = when (rings.size) {
        0, 1 -> 168.dp
        2 -> 132.dp
        3 -> 104.dp
        else -> 84.dp
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        rings.forEach { ring -> ring(ringSize) }
    }
}
