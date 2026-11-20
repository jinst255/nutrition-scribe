package com.nutritionscribe.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.ui.history.DayValue
import com.nutritionscribe.app.ui.history.MetricHistory
import com.nutritionscribe.app.ui.history.formatAmount
import com.nutritionscribe.app.ui.theme.NumberStyle
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.Metric
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tap a goal on Home (round 2, approved 11-20): the last two weeks of it, small and
 * fixed, in the sheet's top right. Bars per day (today hollow, still going), your
 * average as a solid line, the goal dashed. Tap it for the full chart.
 */
object TwoWeeks {
    const val DAYS = 14

    data class Data(val days: List<DayValue>, val average: Double?, val todayDay: Long)

    /** The last [DAYS] days of [metric]; the average counts only full days before today, like History. */
    fun of(entries: List<Entry>, metric: Metric, calorieTarget: Double, zone: ZoneId, today: LocalDate): Data {
        val daily = MetricHistory.daily(entries, metric, zone, today)
        val first = today.toEpochDay() - (DAYS - 1)
        val byDay = daily.associateBy { it.epochDay }
        val days = (first..today.toEpochDay()).map { byDay[it] ?: DayValue(it, null) }
        val full = MetricHistory.fullDays(entries, zone, today, calorieTarget)
        return Data(days, MetricHistory.averageFull(days, full), today.toEpochDay())
    }
}

@Composable
fun TwoWeekMini(data: TwoWeeks.Data, metric: Metric, goal: Double?, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val avg = data.average
    val words = "Last 2 weeks of ${metric.label.lowercase()}: " +
        (avg?.let { "average ${formatAmount(it, metric)} a day" } ?: "no full days yet") +
        (goal?.let { ", goal ${formatAmount(it, metric)}" } ?: "") + ". Opens the full chart."
    Surface(
        shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainerLow,
        modifier = Modifier.width(132.dp).clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = "Full chart", onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = words },
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("14 days", style = MaterialTheme.typography.labelSmall, color = scheme.secondary, modifier = Modifier.weight(1f))
                Text("avg " + (avg?.let { formatAmount(it, metric) } ?: "–"), style = NumberStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                    fontWeight = FontWeight.SemiBold)
            }
            val barColor = scheme.primary
            val lineColor = scheme.onSurface
            val goalColor = scheme.secondary
            Canvas(Modifier.width(116.dp).height(44.dp)) {
                val values = data.days.mapNotNull { it.value }
                val top = (listOfNotNull(values.maxOrNull(), goal, avg).maxOrNull() ?: 1.0).coerceAtLeast(1.0) * 1.1
                val n = data.days.size
                val gap = 2.dp.toPx()
                val w = (size.width - gap * (n - 1)) / n
                fun y(v: Double) = size.height - (v / top * size.height).toFloat()
                data.days.forEachIndexed { i, d ->
                    val v = d.value ?: return@forEachIndexed
                    val x = i * (w + gap)
                    val h = (size.height - y(v)).coerceAtLeast(1.5f)
                    if (d.epochDay == data.todayDay) {
                        drawRoundRect(barColor, Offset(x, size.height - h), Size(w, h), CornerRadius(2f, 2f), style = Stroke(1.5.dp.toPx()))
                    } else {
                        drawRoundRect(barColor.copy(alpha = 0.75f), Offset(x, size.height - h), Size(w, h), CornerRadius(2f, 2f))
                    }
                }
                goal?.let { g -> drawLine(goalColor, Offset(0f, y(g)), Offset(size.width, y(g)), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))) }
                avg?.let { a -> drawLine(lineColor, Offset(0f, y(a)), Offset(size.width, y(a)), 2.dp.toPx()) }
            }
            Text("Full chart ›", style = MaterialTheme.typography.labelSmall, color = scheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}
