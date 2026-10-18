package com.nutritionscribe.shared.model

import kotlin.math.abs
import kotlin.math.max

/**
 * Numbers that cannot all be true at once.
 *
 * A label can be misread and a lookup can match the wrong food, and either way
 * the result is usually internally inconsistent before it is obviously wrong:
 * 49 g of sugar in 7 g of carbohydrate, 300 kcal from 5 g of macros, 60 g of
 * protein in a 30 g serving. These checks catch that without knowing the food.
 *
 * They are for the debug log and for pointing at a box on the scan form. None of
 * them blocks anything: labels round, and a few foods (sugar alcohols, fibre
 * counted differently abroad) break the arithmetic honestly.
 */
object NutrientSanity {

    data class Issue(
        /** Stable, greppable: "sugar_over_carbs". */
        val code: String,
        /** Short enough to show under a box. */
        val message: String,
        /** The boxes it is about; the first is the one most likely wrong. */
        val metrics: List<String>,
    )

    private val kcal = Metric.CALORIES.key
    private val protein = Metric.PROTEIN.key
    private val carbs = Metric.CARBS.key
    private val fat = Metric.FAT.key
    private val fiber = Metric.FIBER.key
    private val sugar = Metric.SUGAR.key
    private val satFat = Metric.SATURATED_FAT.key

    /**
     * [n] is one food's (or one serving's) nutrients. [grams], when known, is the
     * weight they are for.
     */
    fun check(n: Map<String, Double>, grams: Double? = null): List<Issue> = buildList {
        fun v(k: String) = n[k]

        // A part cannot exceed its whole. Labels round each line, so allow 1 g.
        val c = v(carbs)
        v(sugar)?.let { s -> if (c != null && s > c + 1.0) add(Issue("sugar_over_carbs", "More sugar than total carbs (${f(c)} g)", listOf(sugar, carbs))) }
        v(fiber)?.let { fi -> if (c != null && fi > c + 1.0) add(Issue("fiber_over_carbs", "More fiber than total carbs (${f(c)} g)", listOf(fiber, carbs))) }
        val sf = v(satFat); val tf = v(fat)
        if (sf != null && tf != null && sf > tf + 1.0) add(Issue("satfat_over_fat", "More saturated fat than total fat (${f(tf)} g)", listOf(satFat, fat)))
        val trans = v(Metric.TRANS_FAT.key)
        if (sf != null && trans != null && tf != null && sf + trans > tf + 1.0 && sf <= tf + 1.0) {
            add(Issue("parts_over_fat", "Saturated and trans fat add up to more than total fat (${f(tf)} g)", listOf(Metric.TRANS_FAT.key, satFat, fat)))
        }
        val added = v(Metric.ADDED_SUGAR.key)
        v(sugar)?.let { s -> if (added != null && added > s + 1.0) add(Issue("added_over_sugar", "More added sugar than total sugar (${f(s)} g)", listOf(Metric.ADDED_SUGAR.key, sugar))) }

        // Calories against 4/4/9. Fibre and sugar alcohols make the true figure a
        // little lower than the arithmetic, so only a large gap counts.
        val p = v(protein); val k = v(kcal)
        if (k != null && p != null && c != null && tf != null) {
            val expected = 4 * p + 4 * c + 9 * tf
            val gap = abs(k - expected)
            if (gap > max(40.0, 0.25 * max(k, expected))) {
                // Point at calories unless the macros are what look off.
                add(Issue("kcal_mismatch", "Calories don't match the macros (about ${f(expected)} kcal)", listOf(kcal, protein, carbs, fat)))
            }
        }
        if (k != null && k == 0.0 && (p ?: 0.0) + (c ?: 0.0) + (tf ?: 0.0) > 3.0) {
            add(Issue("zero_kcal_with_macros", "0 calories but it has macros", listOf(kcal)))
        }

        // Nothing weighs less than its protein, carbs and fat combined.
        if (grams != null && grams > 0) {
            val mass = (p ?: 0.0) + (c ?: 0.0) + (tf ?: 0.0)
            if (mass > grams * 1.05 + 1.0) add(Issue("macros_over_weight", "More macros (${f(mass)} g) than the serving weighs (${f(grams)} g)", listOf(protein, carbs, fat)))
            if (k != null && k > grams * 9.5 + 5) add(Issue("kcal_over_weight", "More calories than ${f(grams)} g of anything can hold", listOf(kcal)))
        }
    }

    /**
     * What a misread box most likely says: a value that makes its warnings go away
     * without starting new ones. Text recognition drops or adds a digit and loses a
     * decimal point ("80" for 8, "1" for 11); those are the slips tried, smallest
     * change first. Nothing is offered when no single slip explains it.
     */
    fun suggest(n: Map<String, Double>, grams: Double? = null): Map<String, Double> {
        val issues = check(n, grams)
        if (issues.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, Double>()
        for (issue in issues) {
            val key = issue.metrics.firstOrNull() ?: continue
            if (key in out) continue
            val v = n[key] ?: continue
            val before = issues.size
            val fix = slips(v).firstOrNull { alt ->
                val after = check(n + (key to alt), grams)
                after.none { it.code == issue.code } && after.size < before && after.none { a -> issues.none { it.code == a.code } }
            } ?: continue
            out[key] = fix
        }
        return out
    }

    /**
     * Compared with the same product's own label, the boxes off by more than a
     * quarter: the misreads that still add up. [expected] is for the same serving.
     */
    fun against(n: Map<String, Double>, expected: Map<String, Double>): Map<String, Double> =
        n.filter { (k, v) ->
            val e = expected[k] ?: return@filter false
            val floor = if (k == kcal) 15.0 else if (k == Metric.SODIUM.key || k == Metric.POTASSIUM.key || k == Metric.CALCIUM.key) 20.0 else 1.5
            abs(v - e) > max(floor, 0.25 * max(v, e))
        }.mapValues { expected.getValue(it.key) }

    private fun slips(v: Double): List<Double> {
        if (v <= 0) return emptyList()
        val whole = v == Math.floor(v)
        val digits = if (whole) v.toLong().toString() else ""
        return buildList {
            add(v / 10.0)
            if (whole && digits.length >= 2) {
                for (i in digits.indices) digits.removeRange(i, i + 1).toLongOrNull()?.let { add(it.toDouble()) }
            }
            add(v * 10.0)
            if (whole && digits.length == 1) add(v + 10.0)
        }.filter { it > 0 && it != v }.distinct()
    }

    /** Just the codes, for a log line. */
    fun codes(n: Map<String, Double>, grams: Double? = null): List<String> = check(n, grams).map { it.code }

    private fun f(x: Double) = if (x == Math.floor(x)) x.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", x)
}
