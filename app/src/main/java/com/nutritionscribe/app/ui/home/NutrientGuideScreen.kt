package com.nutritionscribe.app.ui.home

import androidx.compose.foundation.BorderStroke
import com.nutritionscribe.app.ui.home.SectionLabel
import com.nutritionscribe.app.data.db.toEntry
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bloodtype
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.theme.NumberStyle
import com.nutritionscribe.shared.model.Ideas
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.NutrientGuide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "What does fiber do for me?": opened from a nutrient's sheet. What staying in
 * range does for you, where the day stands, and easy ways to get more (foods rich
 * in it, from the bundled USDA data) or to have less (swaps, for a limit).
 *
 * [have] and [target] are the sheet's own numbers, so the two always agree.
 * [dayLabel] is null for today, else "On Mon, Oct 12".
 */
@Composable
fun NutrientGuideScreen(metric: Metric, have: Double, target: Double, dayLabel: String?) {
    val guide = remember(metric) { NutrientGuide.of(metric) }
    LaunchedEffect(metric) {
        ServiceLocator.debugEvents.event("nutrient_guide_open", "metric" to guide.metric.key, "have" to have, "target" to target)
    }
    // The last four weeks of their own log: what they already eat that has it (S18), and caffeine's usual (S12).
    val recent by produceState<List<com.nutritionscribe.shared.model.Entry>?>(null) {
        value = withContext(Dispatchers.IO) {
            val since = System.currentTimeMillis() - 28L * 86_400_000L
            runCatching { ServiceLocator.database.entryDao().all().map { it.toEntry() }.filter { it.timestampEpochMillis >= since } }.getOrDefault(emptyList())
        }
    }
    val foods by produceState<List<Pair<Ideas.Food, Double>>?>(null, guide.metric) {
        value = withContext(Dispatchers.IO) {
            val usda = ServiceLocator.foodDatabase.reader()?.let { com.nutritionscribe.shared.resolve.UsdaFoodSource(it) }
            NutrientGuide.richIn(guide.metric, { id -> usda?.byId(id)?.per100g })
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(guide.headline, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 31.sp))
        if (target > 0) {
            Text(
                NutrientGuide.progress(guide.metric, have, target, dayLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        SectionLabel(guide.pointsTitle)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            guide.points.forEach { p ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(iconFor(p.icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(p.title) }
                            append(" ")
                            append(p.body)
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (guide.metric == Metric.CAFFEINE) recent?.let { CaffeineUsual(it) }
        if (guide.listsFoods) {
            val mine = remember(recent, guide.metric) { recent?.let { fromWhatYouEat(it, guide.metric, target) }.orEmpty() }
            if (mine.isNotEmpty()) {
                SectionLabel("From what you already eat")
                Lines(mine.map { it.first to "+" + NutrientGuide.amount(it.second, guide.metric) })
            }
            val list = foods
            if (list == null || list.isNotEmpty()) SectionLabel("Easy ways to get more")
            if (!list.isNullOrEmpty()) {
                Lines(list.map { (f, v) -> "${f.name}, ${f.serving}" to NutrientGuide.amount(v, guide.metric) })
            }
        } else if (guide.swaps.isNotEmpty()) {
            SectionLabel("Easy ways to have less")
            Lines(guide.swaps.map { it to null })
        }
        Text(
            "From ${guide.source}." + (if (guide.listsFoods && !foods.isNullOrEmpty()) " Amounts from USDA FoodData Central." else "") +
                " General information, not medical advice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/**
 * Foods in their own log that have [metric] (note S18): "Red bell pepper · you log it
 * 3 times a week · ½ cup" with what one usual portion gives. Mostly-trace foods are left out.
 */
private fun fromWhatYouEat(entries: List<com.nutritionscribe.shared.model.Entry>, metric: Metric, target: Double): List<Pair<String, Double>> {
    data class Seen(val name: String, val amount: Double, val portion: String?, val day: java.time.LocalDate)
    val zone = java.time.ZoneId.systemDefault()
    val seen = entries.flatMap { e ->
        val day = com.nutritionscribe.shared.model.LogDays.dayOf(e.timestampEpochMillis, zone)
        e.items.mapNotNull { i -> i.nutrients[metric.key]?.takeIf { it > 0 }?.let { Seen(i.name, it, i.portion, day) } }
    }
    val floor = maxOf(target * 0.05, if (metric.unit == "g") 0.5 else 0.0)
    return seen.groupBy { it.name }.map { (name, list) ->
        val avg = list.sumOf { it.amount } / list.size
        val days = list.map { it.day }.distinct().size
        val often = when {
            days >= 8 -> "you log it ${Math.round(days / 4.0)} times a week"
            days >= 2 -> "you've had it $days times lately"
            else -> "you had it lately"
        }
        Triple(name.substringAfter(" \u2014 ").take(40) + " \u00B7 " + often + (list.last().portion?.let { " \u00B7 $it" } ?: ""), avg, days)
    }.filter { it.second >= floor }.sortedWith(compareByDescending<Triple<String, Double, Int>> { it.second * minOf(it.third, 8) }).take(3).map { it.first to it.second }
}

/**
 * Caffeine's own story (note S12): their usual on days they have it, their most,
 * and a line just above it, offered once there are 5 days of it to go on.
 */
@Composable
private fun CaffeineUsual(entries: List<com.nutritionscribe.shared.model.Entry>) {
    val zone = java.time.ZoneId.systemDefault()
    val today = com.nutritionscribe.shared.model.LogDays.today()
    val byDay = entries.groupBy { com.nutritionscribe.shared.model.LogDays.dayOf(it.timestampEpochMillis, zone) }
        .mapValues { (_, es) -> es.sumOf { it.nutrients[Metric.CAFFEINE.key] ?: 0.0 } }
    val withIt = byDay.filter { it.value > 0.5 }
    if (withIt.size < 5) {
        Text("Your usual shows here once you've logged caffeine on 5 or more days.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
        return
    }
    val usual = withIt.values.sorted().let { it[it.size / 2] }
    val most = withIt.maxBy { it.value }
    val from = entries.flatMap { e -> e.items.filter { (it.nutrients[Metric.CAFFEINE.key] ?: 0.0) > 0 }.map { it.name } }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.substringAfter(" \u2014 ")
    val line = (Math.ceil(most.value * 1.2 / 50.0) * 50).coerceIn(100.0, 400.0)
    val goal by remember { ServiceLocator.goalsRepository.goals }.collectAsState(initial = emptyList())
    val set = goal.firstOrNull { it.metric == Metric.CAFFEINE }?.ceilingValue
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val days = (13 downTo 0).map { today.minusDays(it.toLong()) }
    val top = maxOf(days.maxOf { byDay[it] ?: 0.0 }, usual * 1.3, 1.0)
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Your usual")
            val bar = MaterialTheme.colorScheme.primary
            val dots = MaterialTheme.colorScheme.secondary
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(80.dp)) {
                val w = size.width / days.size
                days.forEachIndexed { i, d ->
                    val v = byDay[d] ?: 0.0
                    val h = (v / top * size.height).toFloat().coerceAtLeast(if (v > 0) 3f else 1.5f)
                    drawRect(bar.copy(alpha = if (v > 0) 0.85f else 0.25f), androidx.compose.ui.geometry.Offset(i * w + w * 0.15f, size.height - h),
                        androidx.compose.ui.geometry.Size(w * 0.7f, h))
                }
                val y = (size.height - usual / top * size.height).toFloat()
                drawLine(dots, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 2f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            }
            Text("About ${Math.round(usual)} mg on days you have it" + (from?.let { ", mostly from ${it.lowercase()}" } ?: "") +
                ". Your most was ${Math.round(most.value)} mg, on ${most.key.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Most adults do fine under 400 mg a day.", style = MaterialTheme.typography.titleSmall)
            if (set != null) {
                Text("Your line is at ${Math.round(set)} mg. Days past it show in rose.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary)
            } else {
                Text("Your own most so far is ${Math.round(most.value)} mg. A line just above it, at ${Math.round(line)} mg, would tell you when a day is well past your usual.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                androidx.compose.material3.FilledTonalButton(onClick = {
                    ServiceLocator.debugEvents.event("caffeine_line_set", "mg" to line, "usual" to usual, "most" to most.value)
                    scope.launch { ServiceLocator.goalsRepository.setCeiling(Metric.CAFFEINE, line) }
                }) { Text("Set a line at ${Math.round(line)} mg") }
            }
        }
    }
}

/** A white card of rows split by hairlines: a name, and an amount when there is one. */
@Composable
private fun Lines(rows: List<Pair<String, String?>>) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            rows.forEachIndexed { i, (name, value) ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (value != null) Text(value, style = NumberStyle.copy(fontSize = 14.sp))
                }
            }
        }
    }
}

private fun iconFor(i: NutrientGuide.Icon): ImageVector = when (i) {
    NutrientGuide.Icon.FULL -> Icons.Outlined.Restaurant
    NutrientGuide.Icon.STEADY -> Icons.Outlined.ShowChart
    NutrientGuide.Icon.HEART -> Icons.Outlined.FavoriteBorder
    NutrientGuide.Icon.ENERGY -> Icons.Outlined.Bolt
    NutrientGuide.Icon.MUSCLE -> Icons.Outlined.FitnessCenter
    NutrientGuide.Icon.BONE -> Icons.Outlined.Accessibility
    NutrientGuide.Icon.BLOOD -> Icons.Outlined.Bloodtype
    NutrientGuide.Icon.SHIELD -> Icons.Outlined.Shield
    NutrientGuide.Icon.HEAL -> Icons.Outlined.Healing
    NutrientGuide.Icon.EYE -> Icons.Outlined.Visibility
    NutrientGuide.Icon.BRAIN -> Icons.Outlined.Psychology
    NutrientGuide.Icon.CELL -> Icons.Outlined.Grain
    NutrientGuide.Icon.SLEEP -> Icons.Outlined.Bedtime
    NutrientGuide.Icon.LEAF -> Icons.Outlined.Eco
    NutrientGuide.Icon.SMILE -> Icons.Outlined.SentimentSatisfied
    NutrientGuide.Icon.DROP -> Icons.Outlined.WaterDrop
    NutrientGuide.Icon.BABY -> Icons.Outlined.ChildCare
}
