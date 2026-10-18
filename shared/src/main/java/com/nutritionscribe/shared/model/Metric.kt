package com.nutritionscribe.shared.model

/**
 * Which way a goal points.
 *
 * Protein you try to REACH; sodium you try to stay UNDER. Treating every goal as
 * something to reach would make a day of 3000mg sodium render as a triumphant full
 * ring, which is exactly backwards.
 */
enum class GoalDirection { REACH, LIMIT }

/** Where a metric is shown: the Macros page, or one of the Minerals & vitamins sections. */
enum class MetricGroup(val label: String) {
    MACRO("Macros"),
    MINERAL("Minerals"),
    VITAMIN("Vitamins"),
}

/**
 * Everything the app can track.
 *
 * [key] is persisted, so it must never change once shipped — labels and ordering are
 * presentation and may. New metrics can be appended freely: because values live in
 * the normalised `entry_nutrient` table rather than in columns on `entries`, adding
 * one costs no database migration.
 *
 * The micronutrients' [defaultTarget] is the FDA Daily Value for an adult — the
 * number behind "%DV" on every label — so a fresh install compares against the same
 * figure the packaging does. Omega-3 has no DV; 250 mg of EPA + DHA is the common
 * recommendation.
 *
 * Water is deliberately absent; it is parked for a later release.
 */
enum class Metric(
    val key: String,
    val label: String,
    val unit: String,
    val direction: GoalDirection,
    /** Suggested starting target, used to prefill Goals and to scale a bar with no goal. */
    val defaultTarget: Double,
    val group: MetricGroup = MetricGroup.MACRO,
    /** Shorter name for tight rows ("Sat. fat"). */
    val shortLabel: String = label,
) {
    CALORIES("calories", "Calories", "kcal", GoalDirection.REACH, 2200.0),
    PROTEIN("protein", "Protein", "g", GoalDirection.REACH, 160.0),
    CARBS("carbs", "Carbohydrates", "g", GoalDirection.REACH, 250.0, shortLabel = "Carbs"),
    FAT("fat", "Fat", "g", GoalDirection.REACH, 70.0),
    FIBER("fiber", "Fiber", "g", GoalDirection.REACH, 30.0),
    SUGAR("sugar", "Sugar", "g", GoalDirection.LIMIT, 50.0),
    SATURATED_FAT("saturated_fat", "Saturated fat", "g", GoalDirection.LIMIT, 20.0, shortLabel = "Sat. fat"),
    SODIUM("sodium", "Sodium", "mg", GoalDirection.LIMIT, 2300.0),
    CAFFEINE("caffeine", "Caffeine", "mg", GoalDirection.LIMIT, 400.0),

    CALCIUM("calcium", "Calcium", "mg", GoalDirection.REACH, 1300.0, MetricGroup.MINERAL),
    IRON("iron", "Iron", "mg", GoalDirection.REACH, 18.0, MetricGroup.MINERAL),
    POTASSIUM("potassium", "Potassium", "mg", GoalDirection.REACH, 4700.0, MetricGroup.MINERAL),
    MAGNESIUM("magnesium", "Magnesium", "mg", GoalDirection.REACH, 420.0, MetricGroup.MINERAL),
    ZINC("zinc", "Zinc", "mg", GoalDirection.REACH, 11.0, MetricGroup.MINERAL),
    SELENIUM("selenium", "Selenium", "µg", GoalDirection.REACH, 55.0, MetricGroup.MINERAL),
    VITAMIN_A("vitamin_a", "Vitamin A", "µg", GoalDirection.REACH, 900.0, MetricGroup.VITAMIN),
    VITAMIN_C("vitamin_c", "Vitamin C", "mg", GoalDirection.REACH, 90.0, MetricGroup.VITAMIN),
    VITAMIN_D("vitamin_d", "Vitamin D", "µg", GoalDirection.REACH, 20.0, MetricGroup.VITAMIN),
    VITAMIN_E("vitamin_e", "Vitamin E", "mg", GoalDirection.REACH, 15.0, MetricGroup.VITAMIN),
    VITAMIN_K("vitamin_k", "Vitamin K", "µg", GoalDirection.REACH, 120.0, MetricGroup.VITAMIN),
    VITAMIN_B12("vitamin_b12", "Vitamin B12", "µg", GoalDirection.REACH, 2.4, MetricGroup.VITAMIN, shortLabel = "B12"),
    FOLATE("folate", "Folate", "µg", GoalDirection.REACH, 400.0, MetricGroup.VITAMIN),
    OMEGA3("omega3", "Omega-3 (EPA + DHA)", "mg", GoalDirection.REACH, 250.0, MetricGroup.VITAMIN, shortLabel = "Omega-3"),
    // Not a mineral; it is filed with the micronutrients so it shows under "Stay under" there.
    CHOLESTEROL("cholesterol", "Cholesterol", "mg", GoalDirection.LIMIT, 300.0, MetricGroup.MINERAL),

    // Parts of sugar and fat. They are stored and read from labels like any other
    // metric, but shown inside their whole (Sugar, Fat) rather than as goals of
    // their own: see [partOf].
    /** Under 10% of calories (Dietary Guidelines 2020-2025): the label's 50 g at 2,000 kcal. */
    ADDED_SUGAR("added_sugar", "Added sugar", "g", GoalDirection.LIMIT, 50.0),
    /** No safe amount is set; the WHO's "under 1% of calories" is about 2 g at 2,000 kcal. */
    TRANS_FAT("trans_fat", "Trans fat", "g", GoalDirection.LIMIT, 2.0);

    val isMicro: Boolean get() = group != MetricGroup.MACRO

    /** The name of it as a goal. A sugar limit is a limit on ADDED sugar (see Sugars). */
    val goalLabel: String get() = if (this == SUGAR) "Added sugar" else label

    /** What a goal on this metric is measured against: the sugar goal is an added-sugar limit. */
    val goalMeasure: Metric get() = if (this == SUGAR) ADDED_SUGAR else this

    /** The name a Nutrition Facts panel prints for it ("Total Sugars", "Incl. Added Sugars"). */
    val panelLabel: String get() = when (this) {
        SUGAR -> "Total sugars"
        ADDED_SUGAR -> "Added sugars"
        CARBS -> "Total carbohydrate"
        FAT -> "Total fat"
        else -> label
    }

    /** The metric this one is a part of, shown inside its row: added sugar is part of sugar. */
    val partOf: Metric? get() = when (this) {
        ADDED_SUGAR -> SUGAR
        TRANS_FAT -> FAT
        else -> null
    }

    companion object {
        fun fromKey(key: String): Metric? = entries.firstOrNull { it.key == key }

        /** The macros that can be goals. Parts of another metric ([partOf]) are shown inside it. */
        val MACROS: List<Metric> = entries.filter { !it.isMicro && it.partOf == null }
        val MICROS: List<Metric> = entries.filter { it.isMicro }
    }
}
