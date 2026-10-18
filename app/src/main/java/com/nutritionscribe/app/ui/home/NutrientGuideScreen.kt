package com.nutritionscribe.app.ui.home

import androidx.compose.foundation.BorderStroke
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
        if (guide.listsFoods) {
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
