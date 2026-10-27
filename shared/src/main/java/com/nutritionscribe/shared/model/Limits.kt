package com.nutritionscribe.shared.model

/**
 * What colour a number gets, by the owner's round 9 rule (decision 3, option A):
 *
 * - green: at or under a target, or filling toward one;
 * - sand: over a "stay under" target, but not past a line;
 * - rose: past a line the user typed. Nothing turns rose unless they set one;
 * - blue ▼: a finished day under a target to reach, or under a "too low" line;
 * - grey: nothing to judge it by (not a goal, no line).
 *
 * The tones are soft on purpose; bright red is kept for errors and "looks off".
 */
object Limits {
    enum class State { IN_RANGE, OVER, PAST, LOW, NOT_JUDGED }

    /**
     * [dayOver] is false for today: "too low" is only said of a day that has ended,
     * since at 9 in the morning every floor would be missed.
     */
    fun judge(amount: Double, goal: MetricGoal?, dayOver: Boolean): State {
        val isGoal = goal?.enabled == true
        val floor = goal?.floorValue
        val ceiling = goal?.ceilingValue
        if (ceiling != null && amount > ceiling) return State.PAST
        if (dayOver && floor != null && amount < floor) return State.LOW
        val target = goal?.target?.takeIf { isGoal }
        if (target != null) {
            return when (goal.effectiveDirection) {
                GoalDirection.LIMIT -> if (amount > target) State.OVER else State.IN_RANGE
                GoalDirection.REACH -> if (dayOver && amount < target) State.LOW else State.IN_RANGE
            }
        }
        return if (floor != null || ceiling != null) State.IN_RANGE else State.NOT_JUDGED
    }

    /**
     * A bar's fill and its ticks. Under the target the bar's end is the target and
     * no tick is drawn. Once over, the bar rescales so the target shows as a white
     * tick and the line as a rose one, and how far past them it went is visible.
     */
    data class Bar(val fraction: Float, val targetTick: Float?, val lineTick: Float?)

    fun bar(amount: Double, target: Double, line: Double?): Bar {
        if (target <= 0) return Bar(0f, null, null)
        if (amount <= target) return Bar((amount / target).toFloat(), null, null)
        val scale = maxOf(amount, (line ?: target) * 1.08)
        return Bar((amount / scale).toFloat(), (target / scale).toFloat(), line?.let { (it / scale).toFloat() })
    }
}
