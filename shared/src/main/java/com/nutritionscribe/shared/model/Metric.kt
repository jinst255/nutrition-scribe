package com.nutritionscribe.shared.model

/**
 * Which way a goal points.
 *
 * Protein you try to REACH; sodium you try to stay UNDER. Treating every goal as
 * something to reach would make a day of 3000mg sodium render as a triumphant full
 * ring, which is exactly backwards.
 */
enum class GoalDirection { REACH, LIMIT }

/**
 * Everything the app can track.
 *
 * [key] is persisted, so it must never change once shipped — labels and ordering are
 * presentation and may. New metrics can be appended freely: because values live in
 * the normalised `entry_nutrient` table rather than in columns on `entries`, adding
 * one costs no database migration.
 *
 * Water is deliberately absent; it is parked for a later release.
 */
enum class Metric(
    val key: String,
    val label: String,
    val unit: String,
    val direction: GoalDirection,
    /** Suggested starting target, used only to prefill the Goals screen. */
    val defaultTarget: Double,
) {
    CALORIES("calories", "Calories", "kcal", GoalDirection.REACH, 2200.0),
    PROTEIN("protein", "Protein", "g", GoalDirection.REACH, 160.0),
    CARBS("carbs", "Carbohydrates", "g", GoalDirection.REACH, 250.0),
    FAT("fat", "Fat", "g", GoalDirection.REACH, 70.0),
    FIBER("fiber", "Fiber", "g", GoalDirection.REACH, 30.0),
    SUGAR("sugar", "Sugar", "g", GoalDirection.LIMIT, 50.0),
    SATURATED_FAT("saturated_fat", "Saturated fat", "g", GoalDirection.LIMIT, 20.0),
    SODIUM("sodium", "Sodium", "mg", GoalDirection.LIMIT, 2300.0),
    CAFFEINE("caffeine", "Caffeine", "mg", GoalDirection.LIMIT, 400.0);

    companion object {
        fun fromKey(key: String): Metric? = entries.firstOrNull { it.key == key }
    }
}
