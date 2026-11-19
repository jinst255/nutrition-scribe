package com.nutritionscribe.shared.model

/**
 * Which way the calorie goal points, from the body aim (review Hi2): stay under
 * it unless the aim is to gain. Calories used to be "reach" for everyone, so a
 * day 300 over got a check mark and a day under read as "too low".
 */
object CalorieAim {
    /** The calorie goal after the profile is saved with [aim]. Other metrics are untouched. */
    fun adjust(goal: MetricGoal, aim: TargetCalculator.Aim): MetricGoal {
        if (goal.metric != Metric.CALORIES) return goal
        return when {
            aim == TargetCalculator.Aim.GAIN && goal.direction == null -> goal.copy(direction = GoalDirection.REACH)
            // Gaining no more: back to the default (stay under).
            aim != TargetCalculator.Aim.GAIN && goal.direction == GoalDirection.REACH -> goal.copy(direction = null)
            else -> goal
        }
    }
}
