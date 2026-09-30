package com.nutritionscribe.shared.model

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fixes for the numbers [NutrientSanity] proves cannot be true, applied to food
 * that is already saved.
 *
 * A misread label was only ever flagged, so it went into My Foods as read and
 * every "log again" repeated it: oats saved at 3.5 kcal a serving (the fat line,
 * read as calories), peanut butter saved with 73.5 g of sugar in 12 g of carbs.
 *
 * Only what the arithmetic settles is changed:
 *   - Calories far from 4/4/9 of the macros (under half, or over double) become
 *     the 4/4/9 figure, provided the macros themselves fit in the food's weight.
 *   - Sugar over the carbs, fibre over the carbs, and saturated over total fat
 *     are dropped. Which number is wrong can't be known, but the part can't be
 *     trusted when it is larger than its whole, and a missing sugar figure is
 *     better than one that fills the bar on its own.
 * Everything else is left exactly as the user confirmed it.
 */
object NutrientRepair {

    data class Fix(val code: String, val metric: String, val from: Double, val to: Double?)

    data class Result(val nutrients: Map<String, Double>, val fixes: List<Fix>)

    private val kcal = Metric.CALORIES.key
    private val protein = Metric.PROTEIN.key
    private val carbs = Metric.CARBS.key
    private val fat = Metric.FAT.key
    private val fiber = Metric.FIBER.key
    private val sugar = Metric.SUGAR.key
    private val satFat = Metric.SATURATED_FAT.key

    fun repair(n: Map<String, Double>, grams: Double?): Result {
        val out = n.toMutableMap()
        val fixes = mutableListOf<Fix>()
        val c = n[carbs]
        fun drop(key: String, code: String) {
            n[key]?.let { fixes += Fix(code, key, it, null); out.remove(key) }
        }
        if (c != null) {
            n[sugar]?.let { if (it > c + 1.0) drop(sugar, "sugar_over_carbs") }
            n[fiber]?.let { if (it > c + 1.0) drop(fiber, "fiber_over_carbs") }
        }
        val tf = n[fat]
        n[satFat]?.let { if (tf != null && it > tf + 1.0) drop(satFat, "satfat_over_fat") }

        val p = n[protein]; val k = n[kcal]
        if (k != null && p != null && c != null && tf != null) {
            val expected = 4 * p + 4 * c + 9 * tf
            val mass = p + c + tf
            val fits = grams == null || grams <= 0 || mass <= grams * 1.05 + 1.0
            if (fits && expected >= 20 && (k < expected * 0.5 || k > max(expected * 2, expected + 60))) {
                val to = expected.roundToInt().toDouble()
                fixes += Fix("kcal_from_macros", kcal, k, to)
                out[kcal] = to
            }
        }
        return Result(out, fixes)
    }

    /**
     * [whole] after its parts were repaired: every key a part changed or lost is
     * re-summed from the parts, everything else is kept.
     */
    fun resum(whole: Map<String, Double>, parts: List<Map<String, Double>>, touched: Set<String>): Map<String, Double> {
        if (touched.isEmpty()) return whole
        val out = whole.toMutableMap()
        for (key in touched) {
            val have = parts.mapNotNull { it[key] }
            if (have.isEmpty()) out.remove(key) else out[key] = have.sum()
        }
        return out
    }

    /** The entry with each item repaired and its totals re-summed, or null when nothing needed it. */
    fun repair(entry: Entry): Pair<Entry, List<Fix>>? {
        if (entry.items.isEmpty()) {
            val r = repair(entry.nutrients, null)
            return if (r.fixes.isEmpty()) null else entry.copy(nutrients = r.nutrients) to r.fixes
        }
        val fixes = mutableListOf<Fix>()
        val items = entry.items.map { item ->
            val r = repair(item.nutrients, item.grams)
            fixes += r.fixes
            if (r.fixes.isEmpty()) item else item.copy(nutrients = r.nutrients)
        }
        if (fixes.isEmpty()) return null
        val nutrients = resum(entry.nutrients, items.map { it.nutrients }, fixes.map { it.metric }.toSet())
        return entry.copy(items = items, nutrients = nutrients) to fixes
    }
}
