package com.nutritionscribe.shared.model

/**
 * A day's total for one nutrient, and how much of the day it actually covers.
 *
 * Unknown is not zero. A day of mostly-labelled snacks would otherwise look
 * magnesium-deficient when it is only undocumented, so every total carries the
 * share of the day's calories whose foods said anything about it.
 */
data class NutrientTotal(
    val metric: Metric,
    val amount: Double,
    /** 0..1: the share of the day's calories from foods that list this nutrient. */
    val coverage: Double,
) {
    /** Enough of the day is known for the total to mean something. */
    val trustworthy: Boolean get() = coverage >= TRUSTED_COVERAGE

    companion object {
        /** Below this the total is shown as "?" rather than as a low number. */
        const val TRUSTED_COVERAGE = 0.6
    }
}

object NutrientDay {

    /** One food within the day, whether it came as an item or as a whole entry. */
    private data class Part(val name: String, val nutrients: Map<String, Double>)

    private fun parts(entries: List<Entry>): List<Part> = entries
        .filter { !it.needsDetail && it.nutrients.isNotEmpty() }
        .flatMap { e ->
            if (e.items.isNotEmpty()) e.items.map { Part(it.name, it.nutrients) }
            else listOf(Part(e.name, e.nutrients))
        }

    fun total(entries: List<Entry>, metric: Metric): NutrientTotal {
        val amount = entries.sumOf { it.nutrients[metric.key] ?: 0.0 }
        return NutrientTotal(metric, amount, coverage(entries, metric))
    }

    fun totals(entries: List<Entry>, metrics: List<Metric> = Metric.entries): Map<Metric, NutrientTotal> =
        metrics.associateWith { total(entries, it) }

    fun coverage(entries: List<Entry>, metric: Metric): Double {
        val ps = parts(entries)
        if (ps.isEmpty()) return 0.0
        val kcal = Metric.CALORIES.key
        val all = ps.sumOf { it.nutrients[kcal] ?: 0.0 }
        if (all <= 0.0) return if (ps.any { metric.key in it.nutrients }) 1.0 else 0.0
        val known = ps.filter { metric.key in it.nutrients }.sumOf { it.nutrients[kcal] ?: 0.0 }
        return (known / all).coerceIn(0.0, 1.0)
    }

    /** Which foods gave the most of [metric] today, largest first. */
    fun sources(entries: List<Entry>, metric: Metric): List<Pair<String, Double>> =
        parts(entries).mapNotNull { p -> p.nutrients[metric.key]?.takeIf { it > 0 }?.let { p.name to it } }
            .groupBy({ it.first }, { it.second }).map { (n, v) -> n to v.sum() }
            .sortedByDescending { it.second }

    /** Foods that said nothing about [metric] — why a total is only partly known. */
    fun unknownFor(entries: List<Entry>, metric: Metric): List<String> =
        parts(entries).filter { metric.key !in it.nutrients && (it.nutrients[Metric.CALORIES.key] ?: 0.0) > 0 }
            .map { it.name }.distinct()
}
