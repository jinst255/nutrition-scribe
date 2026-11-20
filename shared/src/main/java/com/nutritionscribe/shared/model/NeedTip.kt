package com.nutritionscribe.shared.model

/**
 * "53 g protein to go. A shake covers 30 g." (watch Today screen, approved 11-19,
 * on condition it is cheap.) Worked out on the phone from the person's own log,
 * with no AI call, so it costs nothing: the food they eat most often in the last
 * [DAYS] days that covers a good part of the gap without going far past it.
 */
object NeedTip {
    const val DAYS = 30L

    /** One of their foods that closes part of the gap: the entry to log again, and how much it gives. */
    data class Pick(val entry: Entry, val amount: Double) {
        val name: String get() = UsualMeals.short(entry.name).let { if (it.length > 24) it.take(23).trimEnd() + "…" else it }
    }

    fun of(metric: Metric, left: Double, entries: List<Entry>, now: Long): String? {
        val best = foods(metric, left, entries, now, limit = 1).firstOrNull() ?: return null
        val unit = if (metric == Metric.CALORIES) "kcal" else metric.unit
        return "${best.name} covers ${Math.round(best.amount)} $unit."
    }

    /**
     * The foods [of] chooses from, most eaten first: what the watch's "need most"
     * screen and "Close the gap" slot log with one tap (owner, 11-20). Each is the
     * latest time it was logged, so one tap copies that amount.
     */
    fun foods(metric: Metric, left: Double, entries: List<Entry>, now: Long, limit: Int = 2): List<Pick> {
        if (left <= 0.5) return emptyList()
        val since = now - DAYS * 86_400_000L
        return entries.filter { it.timestampEpochMillis in since..now && !it.needsDetail && !it.needsConfirm }
            .mapNotNull { e -> e.nutrients[metric.key]?.let { v -> e to v } }
            .filter { (_, v) -> v >= left * 0.25 && v <= left * 1.3 }
            .groupBy { (e, _) -> LogAgain.key(e) }
            .values
            .sortedWith(compareByDescending<List<Pair<Entry, Double>>> { it.size }.thenByDescending { g -> g.maxOf { it.first.timestampEpochMillis } })
            .take(limit)
            .map { g -> g.maxBy { it.first.timestampEpochMillis }.let { (e, v) -> Pick(e, v) } }
    }
}
