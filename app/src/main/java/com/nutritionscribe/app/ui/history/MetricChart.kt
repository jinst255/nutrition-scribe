package com.nutritionscribe.app.ui.history

import com.nutritionscribe.app.di.ServiceLocator

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.ui.common.timeLabel
import com.nutritionscribe.shared.model.GoalDirection
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** The colours a day can be. Same meaning everywhere in the app (see Limits). */
internal typealias StandingColors = com.nutritionscribe.app.ui.theme.LimitColors

@Composable
internal fun standingColors(): StandingColors = com.nutritionscribe.app.ui.theme.limitColors()

/**
 * A card's days as small bars, coloured by how each day did. Today and half-logged
 * days are drawn hollow: they aren't judged or averaged until they're complete.
 * The full chart it opens draws the same bars, bigger (note S19).
 */
@Composable
internal fun DayStrip(days: List<DayValue>, goal: MetricGoal, full: Set<Long>, today: Long, modifier: Modifier = Modifier) {
    val colors = standingColors()
    val hollow = MaterialTheme.colorScheme.outline
    val patchy = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Canvas(modifier) {
        if (days.isEmpty()) return@Canvas
        val hi = max(days.mapNotNull { it.value }.maxOrNull() ?: 0.0, listOfNotNull(goal.target, goal.ceilingValue).maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        val gap = 2.dp.toPx()
        val w = ((size.width - gap * (days.size - 1)) / days.size).coerceAtLeast(1f)
        days.forEachIndexed { i, d ->
            val x = i * (w + gap)
            val v = d.value
            val h = if (v == null) (if (d.patchy) size.height * 0.2f else 0f) else (size.height * (v / hi)).toFloat().coerceIn(3.dp.toPx(), size.height)
            if (h <= 0f) return@forEachIndexed
            val top = size.height - h
            val r = CornerRadius(2.dp.toPx())
            when {
                v == null -> drawRoundRect(patchy, Offset(x, top), Size(w, h), r)
                d.epochDay == today || d.epochDay !in full -> drawRoundRect(
                    hollow, Offset(x + 0.75.dp.toPx(), top + 0.75.dp.toPx()), Size(w - 1.5.dp.toPx(), h - 1.5.dp.toPx()), r,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                else -> drawRoundRect(colors.of(MetricHistory.standing(goal, v)), Offset(x, top), Size(w, h), r)
            }
        }
    }
}

private enum class Preset(val label: String, val days: Double?) {
    WEEK("Week", 7.0), MONTH("Month", 30.0), QUARTER("3 months", 91.0), YEAR("Year", 365.0), ALL("All", null)
}

/**
 * The full-size chart for one metric, opened from its History card.
 *
 * Pinch to zoom from a week to all of history; drag to move through time; tap a
 * bar to see that day's entries underneath. Bars widen from days to weeks to
 * months as the view widens, so the chart never turns into a grey smear.
 */
@Composable
fun MetricChartScreen(
    startMetric: Metric,
    onOpenEntry: (String) -> Unit,
    onAddToDay: (LocalDate) -> Unit = {},
    viewModel: HistoryViewModel = viewModel(),
) {
    var metric by remember { mutableStateOf(startMetric) }
    val entries by viewModel.allEntries.collectAsState(initial = emptyList())
    val goals by viewModel.allGoals.collectAsState(initial = emptyList())
    val goal = chartGoal(metric, goals)

    val zone = remember { ZoneId.systemDefault() }
    val today = com.nutritionscribe.shared.model.LogDays.today(zone)
    val todayDay = today.toEpochDay()
    val days = remember(entries, metric) { MetricHistory.daily(entries, metric, zone, today) }
    val counted by ServiceLocator.settingsRepository.historyCounted.collectAsState(initial = emptySet())
    val calTarget = goals.firstOrNull { it.metric == Metric.CALORIES && it.enabled }?.target
        ?: com.nutritionscribe.shared.model.Needs.forProfile(ServiceLocator.profileNow()).getValue(Metric.CALORIES)
    val full = remember(entries, counted, calTarget) { MetricHistory.fullDays(entries, zone, today, calTarget, counted) }
    val firstDay = days.firstOrNull()?.epochDay ?: todayDay

    var viewport by remember { mutableStateOf(MetricHistory.latest(30.0, todayDay, todayDay)) }
    // Re-clamp once the data arrives: before that the history is "just today".
    var settled by remember { mutableStateOf(false) }
    if (!settled && days.isNotEmpty()) {
        viewport = MetricHistory.latest(30.0, firstDay, todayDay)
        settled = true
    }
    var selected by remember { mutableStateOf<Bucket?>(null) }

    val size = MetricHistory.bucketSizeFor(viewport.spanDays)
    val buckets = remember(days, size) { MetricHistory.buckets(days, size) }
    val visibleDays = days.filter { it.epochDay + 1 > viewport.startDay && it.epochDay < viewport.endDay }
    val average = MetricHistory.averageFull(visibleDays, full)
    val loggedCount = visibleDays.count { it.value != null && it.epochDay in full }
    val colors = standingColors()

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            MetricPicker(
                selected = metric,
                goals = goals,
                onSelect = {
                    if (it != metric) {
                        metric = it
                        selected = null
                        ServiceLocator.debugEvents.event("chart_metric", "metric" to it.key)
                    }
                },
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Text(
                (if (metric == com.nutritionscribe.shared.model.Metric.SUGAR) "Added sugar" else metric.label) + " · " + rangeLabel(viewport),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    average?.let { formatAmount(it, metric, withUnit = false) } ?: "–",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    " ${metric.unit} a day, on average",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(
                buildString {
                    append(if (loggedCount == 1) "1 full day" else "$loggedCount full days")
                    goal.target?.let {
                        append(" · ")
                        append(
                            when {
                                !goal.enabled && goal.metric.direction == GoalDirection.LIMIT -> "limit "
                                !goal.enabled -> if (com.nutritionscribe.app.di.ServiceLocator.profileNow() != null) "you need " else "Daily Value "
                                goal.effectiveDirection == GoalDirection.LIMIT -> "limit "
                                else -> "goal "
                            },
                        )
                        append(formatAmount(it, metric))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val maxSpan = MetricHistory.maxSpan(firstDay, todayDay)
                Preset.entries.forEach { p ->
                    val span = (p.days ?: maxSpan).coerceAtMost(maxSpan)
                    // A preset longer than all of history is the same as "All";
                    // showing both as options would offer two buttons that do one thing.
                    if (p.days != null && p.days > maxSpan && p != Preset.WEEK) return@forEach
                    val active = kotlin.math.abs(viewport.spanDays - span) < 0.5 &&
                        viewport.endDay >= todayDay + 0.5
                    FilterChip(
                        selected = active,
                        onClick = {
                            viewport = MetricHistory.latest(span, firstDay, todayDay)
                            selected = null
                        },
                        label = { Text(p.label) },
                    )
                }
            }
        }

        InteractiveChart(
            buckets = buckets,
            viewport = viewport,
            goal = goal,
            colors = colors,
            selected = selected,
            full = full,
            onViewportChange = { viewport = it },
            onSelect = { selected = it },
            firstDay = firstDay,
            today = todayDay,
            // Shorter than it was: the nutrient picker sits above it now, and the
            // tapped bar's foods still need room underneath.
            modifier = Modifier.fillMaxWidth().height(230.dp).padding(top = 8.dp),
        )
        if (days.isNotEmpty()) {
            Legend(goal, colors, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, start = 16.dp, end = 16.dp))
        }
        Text(
            if (days.isEmpty()) {
                "Nothing recorded for ${metric.label.lowercase()} yet."
            } else {
                "Pinch to zoom · drag to move through time · tap a bar"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SelectionPanel(
            bucket = selected,
            size = size,
            metric = metric,
            goal = goal,
            entries = entries,
            zone = zone,
            colors = colors,
            full = full,
            onCount = { day, on ->
                ServiceLocator.debugEvents.event("history_count_day", "day" to day, "on" to on)
                kotlinx.coroutines.MainScope().launch { ServiceLocator.settingsRepository.setHistoryCounted(day, on) }
            },
            onOpenEntry = onOpenEntry,
            onAdd = onAddToDay,
            onZoomTo = { b ->
                val span = max(MetricHistory.MIN_SPAN_DAYS, b.days.toDouble())
                val center = (b.startEpochDay + b.endEpochDay + 1) / 2.0
                viewport = MetricHistory.clamp(
                    Viewport(endDay = center + span / 2, spanDays = span),
                    firstDay, todayDay,
                )
                selected = null
            },
            modifier = Modifier.weight(1f),
        )
    }

}

/**
 * What the bar colours mean for THIS goal, with ▲ and ▼ so it isn't colour alone
 * (note S28). Not a goal and no line of their own: grey, and the key says so.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Legend(goal: MetricGoal, colors: StandingColors, modifier: Modifier = Modifier) {
    val limit = goal.effectiveDirection == GoalDirection.LIMIT
    val scored = goal.enabled && goal.target != null
    val items = buildList {
        if (!scored && goal.floorValue == null && goal.ceilingValue == null) {
            add(colors.none to "No line set · set one in Goals")
        } else {
            if (goal.floorValue != null || (scored && !limit)) add(colors.low to "▼ Too low")
            add(colors.inRange to "In range")
            if (scored && limit) add(colors.over to "Over target")
            if (goal.ceilingValue != null) add(colors.past to "▲ Past your line")
        }
        add(androidx.compose.ui.graphics.Color.Transparent to "Hollow: today or half-logged")
    }
    androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (c, text) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .padding(end = 5.dp)
                        .size(9.dp)
                        .background(c, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        .then(if (c == androidx.compose.ui.graphics.Color.Transparent) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)) else Modifier),
                )
                Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val SHORT_DATE = DateTimeFormatter.ofPattern("MMM d")
private val SHORT_DATE_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy")

private fun rangeLabel(vp: Viewport): String {
    val start = LocalDate.ofEpochDay(ceil(vp.startDay).toLong())
    val end = LocalDate.ofEpochDay(ceil(vp.endDay).toLong() - 1)
    val fmt = if (start.year != end.year) SHORT_DATE_YEAR else SHORT_DATE
    return "${start.format(fmt)} – ${end.format(if (end.year != LocalDate.now().year) SHORT_DATE_YEAR else SHORT_DATE)}"
}

@Composable
internal fun InteractiveChart(
    buckets: List<Bucket>,
    viewport: Viewport,
    goal: MetricGoal,
    colors: StandingColors,
    selected: Bucket?,
    onViewportChange: (Viewport) -> Unit,
    onSelect: (Bucket?) -> Unit,
    firstDay: Long,
    today: Long,
    modifier: Modifier = Modifier,
    /** Days that count; the rest (today, half-logged) are drawn hollow. Every day counts when null. */
    full: Set<Long>? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val ruleColor = MaterialTheme.colorScheme.outline
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    // The gesture handlers are installed once; read the latest state through these
    // so a pinch always starts from where the view is now, not where it began.
    val vpState = rememberUpdatedState(viewport)
    val bucketsState = rememberUpdatedState(buckets)
    val bounds = rememberUpdatedState(firstDay to today)

    val visible = buckets.filter { it.endEpochDay + 1 > viewport.startDay && it.startEpochDay < viewport.endDay }
    val peak = visible.mapNotNull { it.value }.maxOrNull() ?: 0.0
    val lines = listOfNotNull(goal.target, goal.floorValue, goal.ceilingValue)
    // Animate the scale so dragging into a taller stretch eases up instead of jumping.
    val top by animateFloatAsState(
        targetValue = niceCeiling(max(peak, lines.maxOrNull() ?: 0.0).coerceAtLeast(1.0) * 1.1).toFloat(),
        animationSpec = tween(220),
        label = "chart scale",
    )

    Canvas(
        modifier
            .pointerInputTransform(vpState, bounds, onViewportChange, plotRightInset = 44.dp)
            .pointerInputTap(vpState, bucketsState, onSelect, plotRightInset = 44.dp),
    ) {
        val right = 44.dp.toPx()
        val bottom = 22.dp.toPx()
        val topPad = 8.dp.toPx()
        val left = 16.dp.toPx()
        val plotW = size.width - left - right
        val plotH = size.height - bottom - topPad
        fun x(day: Double) = left + ((day - viewport.startDay) / viewport.spanDays * plotW).toFloat()
        fun y(v: Double) = topPad + (plotH * (1 - v / top)).toFloat()

        // Horizontal grid, labelled on the right where the thumb isn't.
        val step = niceStep(top.toDouble(), 4)
        var g = 0.0
        while (g <= top + 1e-6) {
            val gy = y(g)
            drawLine(gridColor, Offset(left, gy), Offset(left + plotW, gy), strokeWidth = 1f)
            drawLabel(measurer, compact(g), labelStyle, Offset(left + plotW + 6.dp.toPx(), gy), alignCenterY = true)
            g += step
        }

        clipRect(left = left, top = 0f, right = left + plotW, bottom = size.height) {
            // Bars.
            val barGap = if (viewport.spanDays <= 14) 0.28f else 0.18f
            for (b in visible) {
                if (b.value == null && b.patchyDays > 0) {
                    // Logged, but too patchy to total: a hatched stub, never a low bar.
                    val x0 = x(b.startEpochDay.toDouble())
                    val w = x(b.endEpochDay + 1.0) - x0
                    val inset = w * barGap / 2
                    val barW = max(w - inset * 2, 1.5.dp.toPx())
                    val h = plotH * 0.22f
                    val yTop = y(0.0) - h
                    clipRect(left = x0 + inset, top = yTop, right = x0 + inset + barW, bottom = y(0.0)) {
                        var sx = x0 + inset - h
                        while (sx < x0 + inset + barW) {
                            drawLine(ruleColor.copy(alpha = 0.5f), Offset(sx, y(0.0)), Offset(sx + h, yTop), strokeWidth = 1.2.dp.toPx())
                            sx += 4.dp.toPx()
                        }
                    }
                    continue
                }
                val v = b.value ?: continue
                val x0 = x(b.startEpochDay.toDouble())
                val x1 = x(b.endEpochDay + 1.0)
                val w = (x1 - x0)
                val inset = w * barGap / 2
                val barW = max(w - inset * 2, 1.5.dp.toPx())
                val standing = MetricHistory.standing(goal, v)
                val isSel = selected != null && selected.startEpochDay == b.startEpochDay && selected.endEpochDay == b.endEpochDay
                val alpha = if (selected == null || isSel) 1f else 0.38f
                val yTop = y(v)
                val radius = CornerRadius(minOf(barW / 2, 4.dp.toPx()))
                val hollow = b.days == 1L && full != null && (b.startEpochDay == today || b.startEpochDay !in full)
                if (hollow) {
                    val sw = 1.5.dp.toPx()
                    drawRoundRect(ruleColor.copy(alpha = alpha), Offset(x0 + inset + sw / 2, yTop + sw / 2), Size(barW - sw, y(0.0) - yTop - sw), radius, style = Stroke(sw))
                    continue
                }
                val c = colors.of(standing).copy(alpha = alpha)
                drawRoundRect(c, Offset(x0 + inset, yTop), Size(barW, y(0.0) - yTop), radius)
                // ▲ past a line, ▼ too low: the shape says it as well as the colour.
                val mark = when (standing) { Standing.PAST -> "▲"; Standing.LOW -> "▼"; else -> null }
                if (mark != null && barW >= 6.dp.toPx()) {
                    val layout = measurer.measure(mark, TextStyle(fontSize = 9.sp, color = c))
                    drawText(layout, topLeft = Offset(x0 + inset + barW / 2 - layout.size.width / 2, yTop - layout.size.height - 1.dp.toPx()))
                }
            }

            // Goal and threshold rules, over the bars so they stay readable.
            goal.target?.let { t ->
                drawLine(
                    ruleColor, Offset(left, y(t)), Offset(left + plotW, y(t)), strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                )
            }
            listOfNotNull(goal.floorValue, goal.ceilingValue?.takeIf { it != goal.target }).forEach { t ->
                drawLine(
                    ruleColor.copy(alpha = 0.6f), Offset(left, y(t)), Offset(left + plotW, y(t)), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
                )
            }
        }

        // Date axis.
        drawLine(gridColor, Offset(left, y(0.0)), Offset(left + plotW, y(0.0)), strokeWidth = 1f)
        for ((day, text) in axisTicks(viewport)) {
            val tx = x(day.toDouble())
            if (tx < left - 1 || tx > left + plotW) continue
            drawLine(gridColor, Offset(tx, y(0.0)), Offset(tx, y(0.0) + 4.dp.toPx()), strokeWidth = 1f)
            val layout = measurer.measure(text, labelStyle)
            if (tx + layout.size.width > left + plotW + right) continue
            drawText(layout, topLeft = Offset(tx + 2.dp.toPx(), y(0.0) + 5.dp.toPx()))
        }
    }
}

private fun DrawScope.drawLabel(m: TextMeasurer, text: String, style: TextStyle, at: Offset, alignCenterY: Boolean) {
    val layout = m.measure(text, style)
    val dy = if (alignCenterY) layout.size.height / 2f else 0f
    drawText(layout, topLeft = Offset(at.x, at.y - dy))
}

/** 1,000+ as "1.2k" on the axis, where there is only room for four characters. */
private fun compact(v: Double): String = when {
    v >= 10_000 -> "${(v / 1000).toInt()}k"
    v >= 1000 -> String.format("%.1fk", v / 1000).replace(".0k", "k")
    else -> v.toInt().toString()
}

/** Gridline spacing on 1-2-5 steps, so labels are numbers a person would pick. */
internal fun niceStep(range: Double, lines: Int): Double {
    val raw = range / lines
    val mag = 10.0.pow(floor(log10(raw)))
    val norm = raw / mag
    val nice = when {
        norm <= 1 -> 1.0
        norm <= 2 -> 2.0
        norm <= 2.5 -> 2.5
        norm <= 5 -> 5.0
        else -> 10.0
    }
    return nice * mag
}

internal fun niceCeiling(v: Double): Double {
    val step = niceStep(v, 4)
    return ceil(v / step) * step
}

/**
 * Where to put date labels for this much time on screen. Chosen by span so there
 * are always roughly four to seven labels, each on a boundary people count by —
 * days, Mondays, the 1st and 15th, months, years.
 */
private fun axisTicks(vp: Viewport): List<Pair<Long, String>> {
    val start = floor(vp.startDay).toLong()
    val end = ceil(vp.endDay).toLong()
    val span = vp.spanDays
    val out = ArrayList<Pair<Long, String>>()
    for (d in start..end) {
        val date = LocalDate.ofEpochDay(d)
        val label: String? = when {
            span <= 10 -> date.format(DateTimeFormatter.ofPattern("EEE d"))
            span <= 21 -> if (d % 2 == 0L) date.format(SHORT_DATE) else null
            span <= 50 -> if (date.dayOfWeek == java.time.DayOfWeek.MONDAY) date.format(SHORT_DATE) else null
            span <= 120 -> if (date.dayOfMonth == 1 || date.dayOfMonth == 15) date.format(SHORT_DATE) else null
            span <= 400 -> if (date.dayOfMonth == 1) date.format(DateTimeFormatter.ofPattern("MMM")) else null
            span <= 1100 -> if (date.dayOfMonth == 1 && (date.monthValue - 1) % 3 == 0) {
                if (date.monthValue == 1) date.year.toString() else date.format(DateTimeFormatter.ofPattern("MMM"))
            } else null
            else -> if (date.dayOfYear == 1) date.year.toString() else null
        }
        if (label != null) out += d to label
    }
    return out
}

// ---- gestures -------------------------------------------------------------

private fun Modifier.pointerInputTransform(
    vp: androidx.compose.runtime.State<Viewport>,
    bounds: androidx.compose.runtime.State<Pair<Long, Long>>,
    onChange: (Viewport) -> Unit,
    plotRightInset: androidx.compose.ui.unit.Dp,
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        val left = 16.dp.toPx()
        val right = plotRightInset.toPx()
        detectTransformGestures { centroid, pan, zoom, _ ->
            val (first, today) = bounds.value
            val plotW = (size.width - left - right).coerceAtLeast(1f)
            var next = vp.value
            if (zoom != 1f) {
                val focus = ((centroid.x - left) / plotW).coerceIn(0f, 1f)
                next = MetricHistory.zoom(next, zoom, focus, first, today)
            }
            if (pan.x != 0f) {
                // Dragging right reveals the past, like pulling paper across a desk.
                val deltaDays = pan.x / plotW * next.spanDays
                next = MetricHistory.pan(next, deltaDays, first, today)
            }
            onChange(next)
        }
    },
)

private fun Modifier.pointerInputTap(
    vp: androidx.compose.runtime.State<Viewport>,
    buckets: androidx.compose.runtime.State<List<Bucket>>,
    onSelect: (Bucket?) -> Unit,
    plotRightInset: androidx.compose.ui.unit.Dp,
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        val left = 16.dp.toPx()
        val right = plotRightInset.toPx()
        detectTapGestures { pos ->
            val plotW = (size.width - left - right).coerceAtLeast(1f)
            val v = vp.value
            val day = floor(v.startDay + (pos.x - left) / plotW * v.spanDays).toLong()
            onSelect(buckets.value.firstOrNull { day in it.startEpochDay..it.endEpochDay })
        }
    },
)

// ---- the panel under the chart --------------------------------------------

@Composable
private fun SelectionPanel(
    bucket: Bucket?,
    size: BucketSize,
    metric: Metric,
    goal: MetricGoal,
    entries: List<com.nutritionscribe.shared.model.Entry>,
    zone: ZoneId,
    colors: StandingColors,
    full: Set<Long>,
    onCount: (Long, Boolean) -> Unit,
    onOpenEntry: (String) -> Unit,
    onAdd: (LocalDate) -> Unit,
    onZoomTo: (Bucket) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bucket == null) {
        Column(modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Tap a bar to see what you ate.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        return
    }

    val start = LocalDate.ofEpochDay(bucket.startEpochDay)
    val end = LocalDate.ofEpochDay(bucket.endEpochDay)
    val oneDay = bucket.days == 1L
    // Sorted by what gave the most of this nutrient that day (note S27).
    val dayEntries = if (oneDay) {
        entries.filter { MetricHistory.dayOf(it, zone) == bucket.startEpochDay }
            .sortedWith(compareByDescending<com.nutritionscribe.shared.model.Entry> { it.amountOf(metric) ?: -1.0 }.thenBy { it.timestampEpochMillis })
    } else {
        emptyList()
    }
    val dayTotal = dayEntries.sumOf { it.amountOf(metric) ?: 0.0 }
    val dayTop = dayEntries.maxOfOrNull { it.amountOf(metric) ?: 0.0 }?.coerceAtLeast(0.0001) ?: 1.0

    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            oneDay -> dayLabel(start)
                            size == BucketSize.MONTH -> start.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                            else -> "Week of ${start.format(SHORT_DATE)}"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val v = bucket.value
                    Text(
                        when {
                            v == null && bucket.patchyDays > 0 -> "Too few of the foods logged list it to give a total"
                            v == null -> "Nothing recorded"
                            oneDay -> formatAmount(v, metric)
                            else -> "${formatAmount(v, metric)} a day · ${bucket.recordedDays} of ${bucket.days} days logged"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    if (oneDay && bucket.value != null && bucket.startEpochDay != com.nutritionscribe.shared.model.LogDays.today(zone).toEpochDay()) {
                        val counts = bucket.startEpochDay in full
                        Text(
                            if (counts) "Counted in your averages" else "Under half your calories logged, so it's left out of averages",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                        )
                        if (!counts) TextButton(onClick = { onCount(bucket.startEpochDay, true) }) { Text("Count this day") }
                    }
                }
                if (oneDay) {
                    // A day with nothing logged is exactly where this is needed:
                    // the gap is visible on the chart, and this is how to fill it.
                    TextButton(onClick = { onAdd(start) }) {
                        Icon(Icons.Filled.AddCircleOutline, contentDescription = null)
                        Text(" Add")
                    }
                } else {
                    TextButton(onClick = { onZoomTo(bucket) }) {
                        Icon(Icons.Filled.ZoomIn, contentDescription = null)
                        Text(" Zoom in")
                    }
                }
            }
        }
        if (!oneDay) {
            item {
                Text(
                    "${start.format(SHORT_DATE)} – ${end.format(SHORT_DATE)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        if (dayEntries.isNotEmpty()) item {
            Text("Sorted by ${metric.label.lowercase()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 8.dp))
        }
        items(dayEntries, key = { it.id }) { e ->
            val amt = e.amountOf(metric)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenEntry(e.id) }
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, maxLines = 2)
                        Text(e.timeLabel(zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(amt?.let { formatAmount(it, metric) } ?: "not known", style = com.nutritionscribe.app.ui.theme.NumberStyle)
                        if (amt != null && dayTotal > 0) Text("${Math.round(amt / dayTotal * 100)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                if (amt != null) androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth((amt / dayTop).toFloat().coerceIn(0.01f, 1f)).height(4.dp)
                        .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}
