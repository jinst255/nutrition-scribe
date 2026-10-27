package com.nutritionscribe.shared.model

/**
 * Goals › Fill in for me (note S38): fills the boxes below from what the app knows,
 * shows each change first, and by default only touches boxes the person never typed in.
 */
object GoalFill {
    /**
     * Tolerable upper intake levels for adults (National Academies DRI), for the
     * nutrients where the limit applies to food and supplements together. Those that
     * apply only to supplements (magnesium, vitamin E, folic acid, niacin, preformed
     * vitamin A) are left out: food alone never reaches them, so a line would mislead.
     */
    fun upperLimit(metric: Metric, profile: Profile?): Double? = when (metric) {
        Metric.CALCIUM -> if ((profile?.ageYears ?: 30) > 50) 2000.0 else 2500.0
        Metric.IRON -> 45.0
        Metric.ZINC -> 40.0
        Metric.SELENIUM -> 400.0
        Metric.VITAMIN_C -> 2000.0
        Metric.VITAMIN_D -> 100.0
        else -> null
    }

    enum class Box { TARGET, TOO_HIGH }

    data class Change(val metric: Metric, val box: Box, val old: Double?, val new: Double)

    fun plan(goals: List<MetricGoal>, needs: Map<Metric, Double>, profile: Profile?, targets: Boolean, lines: Boolean, onlyEmpty: Boolean): List<Change> {
        val out = mutableListOf<Change>()
        for (g in goals) {
            val m = g.metric
            if (m.partOf != null) continue
            if (targets) {
                val need = needs[m.goalMeasure]
                // A target still at the app's starting figure was never typed.
                val untouched = g.target == null || g.target == m.defaultTarget
                if (need != null && need > 0 && (!onlyEmpty || untouched) && g.target != need) out += Change(m, Box.TARGET, g.target, need)
            }
            if (lines) {
                val ul = upperLimit(m, profile)
                if (ul != null && (!onlyEmpty || g.ceilingValue == null) && g.ceilingValue != ul) out += Change(m, Box.TOO_HIGH, g.ceilingValue, ul)
            }
        }
        return out
    }

    fun apply(goals: List<MetricGoal>, changes: List<Change>): List<MetricGoal> {
        val by = changes.groupBy { it.metric }
        return goals.mapNotNull { g ->
            val cs = by[g.metric] ?: return@mapNotNull null
            cs.fold(g) { acc, c -> if (c.box == Box.TARGET) acc.copy(target = c.new) else acc.copy(ceilingValue = c.new) }
        }
    }
}
