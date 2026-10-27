package com.nutritionscribe.app.ui.cost

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutritionscribe.app.data.db.toEntry
import com.nutritionscribe.app.data.usage.AiUsageRow
import com.nutritionscribe.app.data.usage.PriceBook
import com.nutritionscribe.app.data.usage.UsageKind
import com.nutritionscribe.app.di.ServiceLocator
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Room for the y-axis labels; taps are mapped across the same plot area. */
private val CHART_LEFT = 44.dp

/** Money as a person would say it: fractions of a cent in cents, the rest in dollars. */
/**
 * Spending is shown in cents until it reaches a dollar: "2.4¢" and "37¢" say more
 * than "$0.02" and "$0.37", and at these prices most months never get to a dollar.
 */
fun formatUsd(v: Double): String = when {
    v <= 0.0 -> "0¢"
    v < 0.01 -> String.format(Locale.US, "%.2f¢", v * 100)
    v < 0.10 -> String.format(Locale.US, "%.1f¢", v * 100)
    v < 1 -> String.format(Locale.US, "%.0f¢", v * 100)
    else -> String.format(Locale.US, "$%.2f", v)
}

/** Axis labels: short, and the same precision down a column. */
private fun axisUsd(v: Double, step: Double): String = when {
    v == 0.0 -> "0"
    step < 0.01 -> String.format(Locale.US, "%.${max(0, -floor(log10(step * 100)).toInt())}f¢", v * 100)
    step < 1 -> if (v < 1) String.format(Locale.US, "%.0f¢", v * 100) else String.format(Locale.US, "$%.2f", v)
    else -> String.format(Locale.US, "$%.0f", v)
}

/** The series and their colours: categorical slots 1 to 3, validated on this app's surfaces in both modes. */
private enum class Series(val kinds: Set<String>, val label: String, val light: Color, val dark: Color) {
    LOOKUP(setOf(UsageKind.FOOD_LOOKUP.name, UsageKind.OTHER.name), "Food lookups", Color(0xFF2A78D6), Color(0xFF3987E5)),
    SPEECH(setOf(UsageKind.SPEECH.name), "Speech to text", Color(0xFFEB6834), Color(0xFFD95926)),
    ASK(setOf(UsageKind.ASK.name), "Ask chat", Color(0xFF8A5CC8), Color(0xFF9D74DB)),
}

private fun Series.of(row: AiUsageRow) = row.kind in kinds

private enum class Range(val label: String, val days: Long?, val bucket: ChronoUnit) {
    WEEK("7 days", 7, ChronoUnit.DAYS),
    MONTH("30 days", 30, ChronoUnit.DAYS),
    QUARTER("90 days", 90, ChronoUnit.WEEKS),
    YEAR("12 months", 365, ChronoUnit.MONTHS),
    ALL("All", null, ChronoUnit.MONTHS),
}

private data class Bucket(val start: LocalDate, val end: LocalDate, val rows: List<AiUsageRow>) {
    fun cost(s: Series) = rows.filter { s.of(it) }.sumOf { it.costUsd }
    val total: Double get() = rows.sumOf { it.costUsd }
}

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun AiUsageRow.date(): LocalDate = Instant.ofEpochMilli(timestampEpochMillis).atZone(zone).toLocalDate()

private fun startOf(d: LocalDate, unit: ChronoUnit): LocalDate = when (unit) {
    ChronoUnit.WEEKS -> d.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    ChronoUnit.MONTHS -> d.withDayOfMonth(1)
    else -> d
}

private fun buckets(rows: List<AiUsageRow>, range: Range, today: LocalDate): List<Bucket> {
    val first = range.days?.let { today.minusDays(it - 1) } ?: (rows.minOfOrNull { it.date() } ?: today)
    var cursor = startOf(first, range.bucket)
    val out = mutableListOf<Bucket>()
    val byDate = rows.groupBy { it.date() }
    while (!cursor.isAfter(today)) {
        val next = cursor.plus(1, range.bucket)
        val inside = byDate.filterKeys { !it.isBefore(cursor) && it.isBefore(next) && !it.isBefore(first) }.values.flatten()
        out += Bucket(cursor, next.minusDays(1), inside)
        cursor = next
    }
    return out
}

/** A "nice" step (1, 2, 2.5, 5 × 10ⁿ) giving about [count] gridlines up to [max]. */
private fun niceStep(maxValue: Double, count: Int = 4): Double {
    if (maxValue <= 0) return 0.001
    val raw = maxValue / count
    val mag = 10.0.pow(floor(log10(raw)))
    val n = raw / mag
    val nice = when { n <= 1 -> 1.0; n <= 2 -> 2.0; n <= 2.5 -> 2.5; n <= 5 -> 5.0; else -> 10.0 }
    return nice * mag
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingScreen(onOpenEntry: (String) -> Unit) {
    val rows by remember { ServiceLocator.database.aiUsageDao().observeAll() }.collectAsState(initial = null)
    val names by remember {
        ServiceLocator.database.entryDao().observeAll().map { all -> all.associate { it.entry.id to it.toEntry().name } }
    }.collectAsState(initial = emptyMap())
    var range by remember { mutableStateOf(Range.MONTH) }
    var running by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val today = LocalDate.now()

    val all = rows ?: return
    if (all.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Nothing spent yet", style = MaterialTheme.typography.titleLarge)
            Text(
                "With Highest quality mode on, every AI food lookup and every spoken entry sent for transcription shows up here, " +
                    "with what it cost. Foods you've logged before never call the AI, so they're always free.",
                color = MaterialTheme.colorScheme.secondary,
            )
            PriceNote()
        }
        return
    }

    val ok = all.filter { it.status in 200..299 }
    val monthStart = today.withDayOfMonth(1)
    val thisMonth = ok.filter { !it.date().isBefore(monthStart) }.sumOf { it.costUsd }
    val last30 = ok.filter { !it.date().isBefore(today.minusDays(29)) }.sumOf { it.costUsd }
    val allTime = ok.sumOf { it.costUsd }
    val perEntry = ok.filter { it.entryId != null }.groupBy { it.entryId }.values.map { g -> g.sumOf { it.costUsd } }
    val daysIn = today.lengthOfMonth()
    val projected = if (today.dayOfMonth >= 3) thisMonth / today.dayOfMonth * daysIn else null
    val bs = remember(all, range, today) { buckets(ok, range, today) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- The headline: this month, and where it is heading --------------
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("This month", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                Text(formatUsd(thisMonth), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        if (projected != null) append("On pace for about ${formatUsd(projected)} by ${monthStart.plusMonths(1).minusDays(1).format(DateTimeFormatter.ofPattern("MMM d"))}")
                        else append("${today.format(DateTimeFormatter.ofPattern("MMMM"))} so far")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile("Last 30 days", formatUsd(last30), Modifier.weight(1f))
                StatTile("All time", formatUsd(allTime), Modifier.weight(1f))
                StatTile("Per AI entry", if (perEntry.isEmpty()) "–" else formatUsd(perEntry.average()), Modifier.weight(1f))
            }
        }

        // ---- The chart ------------------------------------------------------
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Range.entries.forEach { r ->
                            FilterChip(selected = r == range, onClick = { range = r; selected = null }, label = { Text(r.label) })
                        }
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(false to "Per ${range.bucket.name.lowercase().removeSuffix("s")}", true to "Running total").forEachIndexed { i, (v, label) ->
                            SegmentedButton(
                                selected = running == v,
                                onClick = { running = v; selected = null },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                                label = { Text(label) },
                            )
                        }
                    }
                    Legend(bs, dark, running)
                    SpendChart(
                        buckets = bs,
                        running = running,
                        dark = dark,
                        selected = selected,
                        onSelect = { selected = it },
                        unit = range.bucket,
                    )
                    val sel = selected?.let { bs.getOrNull(it) }
                    if (sel != null) BucketDetail(sel, range.bucket, running, bs, dark)
                    else Text(
                        if (running) "Tap or drag across the line to read the total on any day."
                        else "Tap a bar to see what went into it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        // ---- Where it went --------------------------------------------------
        item {
            val inRange = bs.flatMap { it.rows }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Where it went · ${range.label.lowercase()}", style = MaterialTheme.typography.titleSmall)
                val total = inRange.sumOf { it.costUsd }.coerceAtLeast(1e-12)
                Series.entries.forEach { s ->
                    val r = inRange.filter { s.of(it) }
                    val cost = r.sumOf { it.costUsd }
                    val detail = if (s == Series.SPEECH) {
                        "${r.size} recording${if (r.size == 1) "" else "s"} · ${"%.1f".format(r.sumOf { it.audioSeconds } / 60)} min"
                    } else {
                        "${r.size} call${if (r.size == 1) "" else "s"} · ${r.sumOf { it.inputTokens }.fmtTokens()} in, ${r.sumOf { it.outputTokens }.fmtTokens()} out"
                    }
                    ShareRow(s.label, formatUsd(cost), detail, (cost / total).toFloat(), if (dark) s.dark else s.light)
                }
                val models = inRange.groupBy { it.model }.mapValues { (_, v) -> v.sumOf { it.costUsd } }.entries.sortedByDescending { it.value }
                if (models.size > 1) {
                    Text("By model", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 4.dp))
                    models.forEach { (m, c) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(m, style = MaterialTheme.typography.bodySmall)
                            Text(formatUsd(c), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val failed = all.count { it.status !in 200..299 && bs.isNotEmpty() && !it.date().isBefore(bs.first().start) }
                if (failed > 0) {
                    Text("$failed call${if (failed == 1) "" else "s"} failed in this range and cost nothing.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        // ---- Every call, newest first (the table view of the chart) ----------
        item {
            HorizontalDivider()
            Text("Recent calls", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
        }
        items(all.asReversed().take(40), key = { it.id }) { r ->
            val entryName = r.entryId?.let { names[it] }
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = r.entryId != null && entryName != null) { r.entryId?.let(onOpenEntry) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val s = Series.entries.first { it.of(r) }
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(if (dark) s.dark else s.light))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(
                        entryName ?: when (s) { Series.SPEECH -> "Speech"; Series.ASK -> "Ask"; else -> "Lookup (not logged)" },
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    )
                    Text(
                        listOf(
                            Instant.ofEpochMilli(r.timestampEpochMillis).atZone(zone).format(DateTimeFormatter.ofPattern("MMM d, h:mm a")),
                            if (s == Series.SPEECH) "${"%.0f".format(r.audioSeconds)} s of speech" else "${r.inputTokens.fmtTokens()} in / ${r.outputTokens.fmtTokens()} out",
                            r.model,
                        ).joinToString(" · ") + if (r.status !in 200..299) " · failed (${r.status})" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                    )
                }
                Text(formatUsd(r.costUsd), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
            }
        }
        item { PriceNote() }
    }
}

private fun Long.fmtTokens(): String = when {
    this >= 1_000_000 -> "%.1fM".format(this / 1_000_000.0)
    this >= 10_000 -> "${this / 1000}k"
    this >= 1000 -> "%.1fk".format(this / 1000.0)
    else -> toString()
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Legend(bs: List<Bucket>, dark: Boolean, running: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (running) {
            LegendItem("Total so far", MaterialTheme.colorScheme.onSurface, bs.sumOf { it.total })
        } else {
            Series.entries.forEach { s -> LegendItem(s.label, if (dark) s.dark else s.light, bs.sumOf { it.cost(s) }) }
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color, total: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(" $label ", style = MaterialTheme.typography.labelMedium)
        Text(formatUsd(total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun ShareRow(label: String, value: String, detail: String, share: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun BucketDetail(b: Bucket, unit: ChronoUnit, running: Boolean, all: List<Bucket>, dark: Boolean) {
    val fmt = DateTimeFormatter.ofPattern("MMM d")
    val title = when (unit) {
        ChronoUnit.DAYS -> b.start.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
        ChronoUnit.WEEKS -> "Week of ${b.start.format(fmt)}"
        else -> b.start.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(formatUsd(b.total), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        if (running) {
            val upTo = all.takeWhile { !it.start.isAfter(b.start) }.sumOf { it.total }
            Text("${formatUsd(upTo)} in total by the end of it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
        }
        Series.entries.forEach { s ->
            val r = b.rows.filter { s.of(it) }
            if (r.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(if (dark) s.dark else s.light))
                    Text(
                        "  ${s.label}: ${formatUsd(r.sumOf { it.costUsd })} · " +
                            if (s == Series.SPEECH) "${r.size} recording${if (r.size == 1) "" else "s"}" else "${r.size} call${if (r.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (b.rows.isEmpty()) Text("No AI calls.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun PriceNote() {
    Column(Modifier.padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            "These are estimates: each call's own token count (or seconds of speech) times the provider's list price, " +
                "checked ${PriceBook.CHECKED}. Your provider's bill is the final word.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        PriceBook.rows().forEach { (m, p) ->
            Text("$m — $p", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/**
 * Stacked bars (lookups under speech) on one dollar scale, or the running total
 * as a line on its own scale — never both at once, since two scales on one chart
 * make the bars look like nothing next to the total.
 */
@Composable
private fun SpendChart(
    buckets: List<Bucket>,
    running: Boolean,
    dark: Boolean,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    unit: ChronoUnit,
) {
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val lineColor = MaterialTheme.colorScheme.onSurface
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = ink)
    val values = if (running) buckets.runningFold(0.0) { acc, b -> acc + b.total }.drop(1) else buckets.map { it.total }
    val maxV = values.maxOrNull()?.takeIf { it > 0 } ?: 0.001
    val step = niceStep(maxV)
    val top = ceil(maxV / step) * step
    val xFmt = when (unit) {
        ChronoUnit.MONTHS -> DateTimeFormatter.ofPattern("MMM")
        else -> DateTimeFormatter.ofPattern("MMM d")
    }
    val description = "Spending chart, ${buckets.size} ${unit.name.lowercase()}, highest ${formatUsd(maxV)}"

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = description }
            .pointerInput(buckets.size, running) {
                detectTapGestures { p -> onSelect(indexAt(p.x, size.width.toFloat(), CHART_LEFT.toPx(), buckets.size)) }
            }
            .pointerInput(buckets.size, running) {
                detectDragGestures { change, _ -> onSelect(indexAt(change.position.x, size.width.toFloat(), CHART_LEFT.toPx(), buckets.size)) }
            },
    ) {
        val left = CHART_LEFT.toPx()
        val bottom = size.height - 18.dp.toPx()
        val plotW = size.width - left
        val plotH = bottom - 6.dp.toPx()
        fun y(v: Double) = (bottom - (v / top) * plotH).toFloat()

        // Gridlines and y labels: recessive, one scale.
        var g = 0.0
        while (g <= top + step / 2) {
            val yy = y(g)
            drawLine(grid, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f,
                pathEffect = if (g == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            label(measurer, axisUsd(g, step), labelStyle, Offset(0f, yy - 7.sp.toPx()), left - 6.dp.toPx())
            g += step
        }

        val n = buckets.size.coerceAtLeast(1)
        val slot = plotW / n
        val barW = (slot * 0.7f).coerceAtMost(28.dp.toPx()).coerceAtLeast(2f)

        selected?.let { i ->
            drawRect(highlight, Offset(left + slot * i, 0f), Size(slot, bottom))
        }

        if (!running) {
            buckets.forEachIndexed { i, b ->
                val cx = left + slot * i + slot / 2
                var base = 0.0
                val parts = Series.entries.map { it to b.cost(it) }.filter { it.second > 0 }
                parts.forEachIndexed { k, (s, v) ->
                    val y0 = y(base); val y1 = y(base + v)
                    val isTop = k == parts.lastIndex
                    val h = (y0 - y1 - if (k > 0) 2.dp.toPx() else 0f).coerceAtLeast(1.5f)
                    val yTop = y0 - h - if (k > 0) 2.dp.toPx() else 0f
                    val color = if (dark) s.dark else s.light
                    if (isTop) {
                        // Rounded data-end on top, square where it meets the one below.
                        val r = (4.dp.toPx()).coerceAtMost(barW / 2).coerceAtMost(h)
                        drawRoundRect(color, Offset(cx - barW / 2, yTop), Size(barW, h), CornerRadius(r, r))
                        if (h > r) drawRect(color, Offset(cx - barW / 2, yTop + r), Size(barW, h - r))
                    } else {
                        drawRect(color, Offset(cx - barW / 2, yTop), Size(barW, h))
                    }
                    base += v
                }
            }
        } else {
            val path = Path()
            val area = Path()
            values.forEachIndexed { i, v ->
                val x = left + slot * i + slot / 2
                if (i == 0) { path.moveTo(x, y(v)); area.moveTo(x, bottom); area.lineTo(x, y(v)) }
                else { path.lineTo(x, y(v)); area.lineTo(x, y(v)) }
            }
            if (values.isNotEmpty()) {
                area.lineTo(left + slot * values.lastIndex + slot / 2, bottom); area.close()
                drawPath(area, lineColor.copy(alpha = 0.08f))
                drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
            selected?.let { i ->
                values.getOrNull(i)?.let { v ->
                    val x = left + slot * i + slot / 2
                    drawCircle(surface, 6.dp.toPx(), Offset(x, y(v)))
                    drawCircle(lineColor, 4.dp.toPx(), Offset(x, y(v)))
                }
            }
        }

        // X labels: first, middle, last — enough to place the range, never crowded.
        val marks = listOf(0, buckets.size / 2, buckets.lastIndex).distinct().filter { it in buckets.indices }
        marks.forEach { i ->
            val text = buckets[i].start.format(xFmt)
            val w = measurer.measure(text, labelStyle).size.width
            val cx = left + slot * i + slot / 2
            val x = (cx - w / 2).coerceIn(left, size.width - w)
            drawText(measurer, text, Offset(x, bottom + 4.dp.toPx()), labelStyle)
        }
    }
}

/** Which bucket a touch at [x] is over, across the plot area to the right of the y labels. */
private fun indexAt(x: Float, width: Float, left: Float, n: Int): Int? {
    if (n == 0 || width <= left) return null
    return floor((x - left) / ((width - left) / n)).toInt().coerceIn(0, n - 1)
}

private fun DrawScope.label(m: TextMeasurer, text: String, style: TextStyle, at: Offset, rightEdge: Float) {
    val w = m.measure(text, style).size.width
    drawText(m, text, Offset(rightEdge - w, at.y), style)
}
