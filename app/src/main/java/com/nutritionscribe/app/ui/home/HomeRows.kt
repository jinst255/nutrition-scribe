package com.nutritionscribe.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
 * start and end in the same place.
 */
@Composable
internal fun BarRow(
    label: String,
    bold: Boolean,
    fraction: Float,
    fill: Color,
    unknown: Boolean,
    value: String,
    over: Boolean,
    valueWidth: Dp,
    description: String,
    onClick: () -> Unit,
    indent: Boolean = false,
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
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = if (indent) scheme.secondary else scheme.onBackground,
            maxLines = 1,
            modifier = Modifier.width(88.dp).padding(start = if (indent) 8.dp else 0.dp),
        )
        HomeBar(fraction, fill, unknown, Modifier.weight(1f))
        Text(
            value,
            style = NumberStyle,
            color = if (over) scheme.error else scheme.onBackground,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(valueWidth),
        )
    }
}

/** Added sugar against its limit, natural sugar for information. */
@Composable
internal fun SugarRows(split: Sugars.Split, goals: Map<Metric, MetricGoal>, refs: Map<Metric, Double>, valueWidth: Dp, onOpen: (Metric) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val goal = goals[Metric.SUGAR]?.takeIf { it.enabled } ?: goals[Metric.ADDED_SUGAR]?.takeIf { it.enabled }
    val limit = sugarLimit(goals, refs)
    // Sugar no label or food kind split is counted as added: most of it comes from
    // packaged food, and the limit is the side to err on.
    val added = split.added + split.unknown
    val over = goal != null && added > limit
    BarRow(
        label = "Added sugar", bold = goal != null, fraction = (added / limit).toFloat(),
        fill = if (over) scheme.error else scheme.primary, unknown = false,
        value = "${if (split.unknown > 0.5) "≈" else ""}${formatAmount(added, Metric.ADDED_SUGAR, withUnit = false)} / ≤${formatAmount(limit, Metric.ADDED_SUGAR)}",
        over = over, valueWidth = valueWidth,
        description = "Added sugar: ${Math.round(added)} of ${Math.round(limit)} grams",
        onClick = { onOpen(Metric.SUGAR) },
    )
    // No limit applies; the bar is scaled to a typical day's natural sugar so it reads.
    val scale = ((refs[Metric.SUGAR] ?: 90.0) - limit).coerceAtLeast(20.0)
    BarRow(
        label = "Natural", bold = false, fraction = (split.natural / scale).toFloat(),
        fill = scheme.secondary.copy(alpha = 0.55f), unknown = false,
        value = "${Math.round(split.natural)} g · no limit",
        over = false, valueWidth = valueWidth, indent = true,
        description = "Natural sugar: ${Math.round(split.natural)} grams, no limit",
        onClick = { onOpen(Metric.SUGAR) },
    )
}

/** Saturated + trans fat against the limit, unsaturated fat for information. */
@Composable
internal fun FatRows(split: Fats.Split, goals: Map<Metric, MetricGoal>, refs: Map<Metric, Double>, valueWidth: Dp, onOpen: (Metric) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val goal = goals[Metric.SATURATED_FAT]?.takeIf { it.enabled }
    val limit = fatLimit(goals, refs)
    val over = goal != null && split.saturatedAndTrans > limit
    BarRow(
        label = "Sat. + trans fat", bold = goal != null, fraction = (split.saturatedAndTrans / limit).toFloat(),
        fill = if (over) scheme.error else scheme.primary, unknown = false,
        value = "${formatAmount(split.saturatedAndTrans, Metric.SATURATED_FAT, withUnit = false)} / ≤${formatAmount(limit, Metric.SATURATED_FAT)}",
        over = over, valueWidth = valueWidth,
        description = "Saturated and trans fat: ${Math.round(split.saturatedAndTrans)} of ${Math.round(limit)} grams",
        onClick = { onOpen(Metric.SATURATED_FAT) },
    )
    val fatGoal = goals[Metric.FAT]?.takeIf { it.enabled }
    val fatTarget = fatGoal?.target ?: refs[Metric.FAT] ?: Metric.FAT.defaultTarget
    BarRow(
        label = "Unsat. fat", bold = fatGoal != null, fraction = (split.unsaturated / (fatTarget - limit).coerceAtLeast(10.0)).toFloat(),
        fill = scheme.secondary.copy(alpha = 0.55f), unknown = false,
        value = if (fatGoal != null) "${formatAmount(split.total, Metric.FAT, withUnit = false)} / ${formatAmount(fatTarget, Metric.FAT)} total"
        else "${Math.round(split.unsaturated)} g · no limit",
        over = false, valueWidth = valueWidth, indent = true,
        description = "Unsaturated fat: ${Math.round(split.unsaturated)} grams; total fat ${Math.round(split.total)} grams",
        onClick = { onOpen(Metric.FAT) },
    )
}
