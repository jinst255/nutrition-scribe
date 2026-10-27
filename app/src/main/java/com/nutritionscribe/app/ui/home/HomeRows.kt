package com.nutritionscribe.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import com.nutritionscribe.app.ui.common.timeLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutritionscribe.app.ui.theme.limitColors
import com.nutritionscribe.shared.model.Limits
import com.nutritionscribe.app.data.repository.HomeSort
import com.nutritionscribe.app.ui.history.formatAmount
import com.nutritionscribe.app.ui.theme.NumberStyle
import com.nutritionscribe.shared.model.GoalDirection
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import com.nutritionscribe.shared.model.NutrientTotal
import com.nutritionscribe.shared.resolve.Fats
import com.nutritionscribe.shared.resolve.Sugars

/**
 * A row on Home. Sugar and fat are shown split, the way the guidelines judge them:
 * the limit is for ADDED sugar and for SATURATED + TRANS fat. Fruit, plain milk
 * and olive oil never turn a bar red.
 */
internal sealed class HomeRow {
    data class One(val metric: Metric) : HomeRow()
    data object SugarPair : HomeRow()
    data object FatPair : HomeRow()

    companion object {
        /** Metrics in display order, with sugar and fat (and saturated fat) folded into their pairs. */
        fun of(metrics: List<Metric>): List<HomeRow> = metrics.map {
            when (it) {
                Metric.SUGAR, Metric.ADDED_SUGAR -> SugarPair
                Metric.FAT, Metric.SATURATED_FAT, Metric.TRANS_FAT -> FatPair
                else -> One(it)
            }
        }.distinct()
    }
}

/** What the sugar and fat pairs are made of today. */
internal class Splits(val sugar: Sugars.Split, val fat: Fats.Split)

/** How full a row is, for sorting: its own bar, or its limit line for a pair. */
internal fun fullness(r: HomeRow, totals: Map<Metric, NutrientTotal>, goals: Map<Metric, MetricGoal>, refs: Map<Metric, Double>, splits: Splits): Double {
    fun target(m: Metric) = goals[m]?.takeIf { it.enabled }?.target ?: refs[m] ?: m.defaultTarget
    return when (r) {
        is HomeRow.One -> {
            val t = totals[r.metric]
            if (r.metric.isMicro && t != null && !t.trustworthy) Double.NaN else (t?.amount ?: 0.0) / target(r.metric).coerceAtLeast(0.001)
        }
        HomeRow.SugarPair -> (splits.sugar.added + splits.sugar.unknown) / sugarLimit(goals, refs)
        HomeRow.FatPair -> splits.fat.saturatedAndTrans / fatLimit(goals, refs)
    }
}

internal fun sugarLimit(goals: Map<Metric, MetricGoal>, refs: Map<Metric, Double>): Double =
    goals[Metric.SUGAR]?.takeIf { it.enabled }?.target
        ?: goals[Metric.ADDED_SUGAR]?.takeIf { it.enabled }?.target
        ?: refs[Metric.ADDED_SUGAR] ?: Metric.ADDED_SUGAR.defaultTarget

internal fun fatLimit(goals: Map<Metric, MetricGoal>, refs: Map<Metric, Double>): Double =
    goals[Metric.SATURATED_FAT]?.takeIf { it.enabled }?.target ?: refs[Metric.SATURATED_FAT] ?: Metric.SATURATED_FAT.defaultTarget

/**
 * [rows] in the chosen order. Lowest first is "what do I still need": the things to
 * reach, emptiest first, then the things to stay under. Rows with too little known
 * today go last either way.
 */
internal fun sortRows(rows: List<HomeRow>, sort: HomeSort, f: (HomeRow) -> Double): List<HomeRow> {
    if (sort == HomeSort.GOALS) return rows
    fun isLimit(r: HomeRow) = r !is HomeRow.One || r.metric.direction == GoalDirection.LIMIT
    val (unknown, known) = rows.partition { f(it).isNaN() }
    val sorted = when (sort) {
        HomeSort.LOWEST -> known.filterNot(::isLimit).sortedBy(f) + known.filter(::isLimit).sortedByDescending(f)
        else -> known.sortedByDescending(f)
    }
    return sorted + unknown
}

/** The sort switch above the rows. */
@Composable
internal fun SortSwitch(sort: HomeSort, onSort: (HomeSort) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
            Text("Sort: ${sort.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            HomeSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (s) {
                                HomeSort.GOALS -> "Goals first"
                                HomeSort.LOWEST -> "Lowest first: what you still need"
                                HomeSort.HIGHEST -> "Highest first"
                            },
                            fontWeight = if (s == sort) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = { open = false; onSort(s) },
                )
            }
        }
    }
}

/**
 * One line: name, a thin bar, the number. Shared by every row on Home so the bars
 * start and end in the same place. Values stay plain text: the bar carries the
 * colour, so nothing on the row shouts.
 */
@Composable
internal fun BarRow(
    label: String,
    bold: Boolean,
    bar: Limits.Bar,
    fill: Color,
    unknown: Boolean,
    value: String,
    valueWidth: Dp,
    description: String,
    onClick: () -> Unit,
    indent: Boolean = false,
    sub: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.width(100.dp).padding(start = if (indent) 10.dp else 0.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                color = if (indent) scheme.secondary else scheme.onBackground,
                maxLines = 1,
            )
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontSize = 11.sp), color = scheme.secondary, maxLines = 2) }
        }
        HomeBar(bar.fraction, fill, unknown, Modifier.weight(1f), bar.targetTick, bar.lineTick)
        Text(
            value,
            style = NumberStyle,
            color = scheme.onBackground,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(valueWidth),
        )
    }
}

private fun plain(amount: Double, scale: Double) = Limits.Bar(if (scale > 0) (amount / scale).toFloat() else 0f, null, null)

/** Added sugar against its limit, natural sugar for information. */
@Composable
internal fun SugarRows(split: Sugars.Split, goals: Map<Metric, MetricGoal>, refs: Map<Metric, Double>, valueWidth: Dp, onOpen: (Metric) -> Unit) {
    val colors = limitColors()
    val goal = goals[Metric.SUGAR]?.takeIf { it.enabled } ?: goals[Metric.ADDED_SUGAR]?.takeIf { it.enabled }
    val lines = goals[Metric.SUGAR]?.takeIf { it.ceilingValue != null || it.floorValue != null }
    val limit = sugarLimit(goals, refs)
    // Sugar no label or food kind split is counted as added: most of it comes from
    // packaged food, and the limit is the side to err on.
    val added = split.added + split.unknown
    val judgedBy = (goal ?: lines)?.copy(target = goal?.let { limit }, direction = GoalDirection.LIMIT)
    val state = Limits.judge(added, judgedBy, dayOver = false)
    BarRow(
        label = "Added sugar", bold = goal != null,
        bar = if (judgedBy != null) Limits.bar(added, limit, judgedBy.ceilingValue) else plain(added, limit),
        fill = colors.of(state), unknown = false,
        value = "${if (split.unknown > 0.5) "≈" else ""}${formatAmount(added, Metric.ADDED_SUGAR, withUnit = false)} / ≤${formatAmount(limit, Metric.ADDED_SUGAR)}",
        valueWidth = valueWidth, sub = limitSub(state, judgedBy, Metric.ADDED_SUGAR),
        description = "Added sugar: ${Math.round(added)} of ${Math.round(limit)} grams",
        onClick = { onOpen(Metric.SUGAR) },
    )
    // No limit applies; the bar is scaled to a typical day's natural sugar so it reads.
    val scale = ((refs[Metric.SUGAR] ?: 90.0) - limit).coerceAtLeast(20.0)
    BarRow(
        label = "Natural", bold = false, bar = plain(split.natural, scale),
        fill = colors.none, unknown = false,
        value = "${Math.round(split.natural)} g · no limit",
        valueWidth = valueWidth, indent = true,
        description = "Natural sugar: ${Math.round(split.natural)} grams, no limit",
        // Its own sheet (note S11): it opened the added-sugar one.
        onClick = { NaturalSugar.open.value = true },
    )
}

/** Saturated + trans fat against the limit, unsaturated fat for information. */
@Composable
internal fun FatRows(split: Fats.Split, goals: Map<Metric, MetricGoal>, refs: Map<Metric, Double>, valueWidth: Dp, onOpen: (Metric) -> Unit) {
    val colors = limitColors()
    val g = goals[Metric.SATURATED_FAT]
    val goal = g?.takeIf { it.enabled }
    val limit = fatLimit(goals, refs)
    val judgedBy = g?.takeIf { it.enabled || it.ceilingValue != null }?.copy(target = goal?.let { limit }, direction = GoalDirection.LIMIT)
    val state = Limits.judge(split.saturatedAndTrans, judgedBy, dayOver = false)
    BarRow(
        label = "Sat. + trans", bold = goal != null,
        bar = if (judgedBy != null) Limits.bar(split.saturatedAndTrans, limit, judgedBy.ceilingValue) else plain(split.saturatedAndTrans, limit),
        fill = colors.of(state), unknown = false,
        value = "${formatAmount(split.saturatedAndTrans, Metric.SATURATED_FAT, withUnit = false)} / ≤${formatAmount(limit, Metric.SATURATED_FAT)}",
        valueWidth = valueWidth, sub = limitSub(state, judgedBy, Metric.SATURATED_FAT),
        description = "Saturated and trans fat: ${Math.round(split.saturatedAndTrans)} of ${Math.round(limit)} grams",
        onClick = { onOpen(Metric.SATURATED_FAT) },
    )
    val fg = goals[Metric.FAT]
    val fatGoal = fg?.takeIf { it.enabled }
    val fatTarget = fatGoal?.target ?: refs[Metric.FAT] ?: Metric.FAT.defaultTarget
    val fatState = Limits.judge(split.total, fg, dayOver = false)
    BarRow(
        label = "Unsat. fat", bold = fatGoal != null,
        bar = if (fatGoal != null) Limits.bar(split.total, fatTarget, fg.ceilingValue) else plain(split.unsaturated, (fatTarget - limit).coerceAtLeast(10.0)),
        fill = if (fatGoal != null) colors.of(fatState) else colors.none, unknown = false,
        value = if (fatGoal != null) "${formatAmount(split.total, Metric.FAT, withUnit = false)} / ${formatAmount(fatTarget, Metric.FAT)} total"
        else "${Math.round(split.unsaturated)} g · no limit",
        valueWidth = valueWidth, indent = true, sub = if (fatGoal != null) limitSub(fatState, fg, Metric.FAT) else null,
        description = "Unsaturated fat: ${Math.round(split.unsaturated)} grams; total fat ${Math.round(split.total)} grams",
        onClick = { onOpen(Metric.FAT) },
    )
}


/** Natural sugar's own sheet, opened from its row on Home or a day's page (note S11). */
object NaturalSugar {
    val open = kotlinx.coroutines.flow.MutableStateFlow(false)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun NaturalSheet(entries: List<com.nutritionscribe.shared.model.Entry>, onAboutAdded: () -> Unit, onOpenEntry: (String) -> Unit, onDismiss: () -> Unit) {
    val split = androidx.compose.runtime.remember(entries) { Sugars.split(entries) }
    val sources = androidx.compose.runtime.remember(entries) { Sugars.naturalSources(entries).take(6) }
    androidx.compose.runtime.LaunchedEffect(Unit) { com.nutritionscribe.app.di.ServiceLocator.debugEvents.event("natural_sugar_open", "grams" to split.natural) }
    com.nutritionscribe.app.ui.feedback.ReportableSheet("Natural sugar", onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                Text("Natural sugar", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("${Math.round(split.natural)} g \u00B7 no limit", style = com.nutritionscribe.app.ui.theme.NumberStyle)
            }
            Text("Sugar that comes with fruit and plain milk. It doesn't count toward your added-sugar limit.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            if (sources.isNotEmpty()) {
                SectionLabel("Today it came from")
                sources.forEach { (name, g) ->
                    val from = entries.lastOrNull { e -> e.items.any { it.name == name } || (e.items.isEmpty() && e.name == name) }
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = from != null) { onDismiss(); from?.let { onOpenEntry(it.id) } },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            from?.let { e -> Text(e.timeLabel(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary) }
                        }
                        Text("${Math.round(g)} g", style = com.nutritionscribe.app.ui.theme.NumberStyle)
                    }
                }
            } else Text("None logged today.", style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.TextButton(onClick = { onDismiss(); onAboutAdded() }) { Text("About added sugar \u203A") }
        }
    }
}
