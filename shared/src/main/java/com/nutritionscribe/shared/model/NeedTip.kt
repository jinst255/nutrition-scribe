package com.nutritionscribe.shared.model

/**
 * "53 g protein to go. A shake covers 30 g." (watch Today screen, approved 11-19,
 * on condition it is cheap.) Worked out on the phone from the person's own log,
 * with no AI call, so it costs nothing: the food they eat most often in the last
 * [DAYS] days that covers a good part of the gap without going far past it.
 */
object NeedTip {
    const val DAYS = 30L

    fun of(metric: Metric, left: Double, entries: List<Entry>, now: Long): String? {
        if (left <= 0.5) return null
        val since = now - DAYS * 86_400_000L
        val best = entries.filter { it.timestampEpochMillis in since..now && !it.needsDetail }
            .mapNotNull { e -> e.nutrients[metric.key]?.let { v -> e to v } }
            .filter { (_, v) -> v >= left * 0.25 && v <= left * 1.3 }
            .groupBy { (e, _) -> LogAgain.key(e) }
            .maxWithOrNull(compareBy({ it.value.size }, { g -> g.value.maxOf { it.first.timestampEpochMillis } }))
            ?: return null
        val (entry, amount) = best.value.maxBy { it.first.timestampEpochMillis }
        val name = UsualMeals.short(entry.name).let { if (it.length > 24) it.take(23).trimEnd() + "…" else it }
        val unit = if (metric == Metric.CALORIES) "kcal" else metric.unit
        return "$name covers ${Math.round(amount)} $unit."
    }
}
