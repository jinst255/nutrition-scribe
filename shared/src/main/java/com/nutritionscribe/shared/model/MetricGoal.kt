package com.nutritionscribe.shared.model

/**
 * One metric the user has chosen to care about, and what "a good day" means for it.
 *
 * A goal exists for every [Metric] whether or not it is [enabled], so that turning
 * one on later restores the number that was set rather than starting from scratch.
 *
 * [target] is nullable on purpose: "I want to watch my sodium" is a complete thought
 * on its own, and forcing a number before someone knows what a reasonable one is
 * invents a target they would only be failing against.
 */
data class MetricGoal(
    val metric: Metric,
    val enabled: Boolean,
    val target: Double? = null,
    /**
     * Overrides [Metric.direction] when the user wants the opposite.
     *
     * Every metric ships with a sensible default — protein is something to reach,
     * sodium something to stay under — but the defaults are not right for
     * everybody. Someone cutting eats to a calorie LIMIT while someone bulking
     * eats to a calorie target, and the app was quietly scoring both as "more is
     * better". null means "whatever the metric says".
     */
    val direction: GoalDirection? = null,
    /** Below this reads red. For a REACH metric this is "not enough". */
    val floorValue: Double? = null,
    /** Above this reads red. For a LIMIT metric this is "too much". */
    val ceilingValue: Double? = null,
    val sortOrder: Int = 0,
    val pinned: Boolean = false,
) {
    /**
     * Whether today's number is where the user wants it.
     *
     * Direction matters: 2,400 mg of sodium against a 2,300 mg target is a miss,
     * while 170 g of protein against a 160 g target is a win. Treating every metric
     * as "more is better" is how a tracker congratulates someone for a bad day.
     */
    /** What this goal actually scores by — the override, or the metric's default. */
    val effectiveDirection: GoalDirection get() = direction ?: metric.direction

    fun isMet(amount: Double): Boolean {
        val goal = target ?: return true
        return when (effectiveDirection) {
            GoalDirection.REACH -> amount >= goal
            GoalDirection.LIMIT -> amount <= goal
        }
    }

    /** 0..1 for a ring or bar. Can exceed 1 — the UI decides how to show an overshoot. */
    fun progress(amount: Double): Double {
        val goal = target ?: return 0.0
        if (goal <= 0.0) return 0.0
        return amount / goal
    }

    companion object {
        /**
         * The starting point for someone who has never set this metric up.
         *
         * A LIMIT metric gets its default as a ceiling as well as a target, because
         * "2,300 mg of sodium" means an upper bound, not something to hit.
         */
        fun default(metric: Metric, enabled: Boolean = false): MetricGoal = MetricGoal(
            metric = metric,
            enabled = enabled,
            target = metric.defaultTarget,
            ceilingValue = if (metric.direction == GoalDirection.LIMIT) metric.defaultTarget else null,
            sortOrder = Metric.entries.indexOf(metric),
        )

        /**
         * What a brand-new user starts with if they accept the suggestion.
         * Deliberately small: two metrics is a habit, nine is a chore.
         */
        val STARTER_METRICS = listOf(Metric.PROTEIN, Metric.CALORIES)
    }
}
