package com.nutritionscribe.app.ui.entry

import com.nutritionscribe.shared.model.Metric
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * "420 → 840 kcal · 1,100 → 760 mg sodium": what an AI fix changed, for the card
 * that replaces the old one-line snackbar (owner, 11-19: make it "more fancy").
 * Only numbers that really moved, calories first, at most [max].
 */
object FixDiff {
    private val ORDER = listOf(Metric.CALORIES, Metric.PROTEIN, Metric.FIBER, Metric.SODIUM, Metric.CARBS, Metric.FAT, Metric.SUGAR)

    fun lines(before: Map<String, Double>, after: Map<String, Double>, max: Int = 2): List<String> =
        ORDER.mapNotNull { m ->
            val a = before[m.key]; val b = after[m.key]
            when {
                a == null && b == null -> null
                a == null -> "${num(b!!)} ${unit(m)} added"
                b == null -> "${unit(m)} removed"
                abs(b - a) < maxOf(1.0, abs(a) * 0.02) -> null
                else -> "${num(a)} → ${num(b)} ${unit(m)}"
            }
        }.take(max)

    private fun unit(m: Metric) = if (m == Metric.CALORIES) "kcal" else "${m.unit} ${m.label.lowercase()}"

    private fun num(v: Double): String =
        if (abs(v) >= 10) String.format(java.util.Locale.US, "%,d", v.roundToLong()) else String.format(java.util.Locale.US, "%.1f", v).removeSuffix(".0")
}
