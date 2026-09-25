package com.nutritionscribe.shared.model

import kotlinx.serialization.Serializable

enum class Confidence {
    ESTIMATED,
    STATED,
}

/**
 * Full logged entry.
 *
 * Nutrient values live in [nutrients], keyed by [Metric.key], rather than as one
 * field per nutrient. That is what makes the Goals picker extensible: adding fiber,
 * caffeine or anything later is a new map key, not a database migration over a log
 * that is the only copy of the user's data.
 *
 * The named accessors below keep the common reads (`entry.proteinGrams`) short and
 * keep older call sites working; they are derived, never stored twice.
 */
@Serializable
data class Entry(
    val id: String,
    val timestampEpochMillis: Long,
    val rawText: String,
    val name: String,
    /** [Metric.key] -> amount, in that metric's own unit. */
    val nutrients: Map<String, Double> = emptyMap(),
    val isFixedFood: Boolean = false,
    val source: String,
    val confidence: Confidence,
    /**
     * True when the user backdated this entry without saying what time they ate.
     * [timestampEpochMillis] is then noon on that date purely so the entry sorts
     * sensibly within its day — it is NOT a time the user reported. The UI shows
     * "time not set" rather than that placeholder, and any meal-timing analysis
     * over exported data should exclude these rather than treat noon as real.
     */
    val timeUnspecified: Boolean = false,
    /**
     * True when nothing identifiable was logged — "I ate a solid breakfast". The
     * entry keeps the user's words and carries NO invented numbers; the app asks
     * what it was rather than guessing, because a confident wrong number is worse
     * than an admitted gap in a log meant for analysis.
     */
    val needsDetail: Boolean = false,
    /** Row this resolved to in the bundled food database, when it came from there. */
    val foodDbId: Long? = null,
    /**
     * What the entry is made of, item by item, as it was confirmed: which food, how
     * much, and where the numbers came from. This is what "Fix" opens, and what a
     * correction is written back through to the user's own foods.
     */
    val items: List<LoggedItem> = emptyList(),
    /**
     * Logged without anyone looking — a first-time food said to the watch. It
     * counts toward today (a best guess beats a gap) but waits on the phone for a
     * glance, and nothing is remembered from it until then.
     */
    val needsConfirm: Boolean = false,
) {
    fun amountOf(metric: Metric): Double? = nutrients[metric.key]

    val proteinGrams: Double get() = nutrients[Metric.PROTEIN.key] ?: 0.0
    val calories: Double? get() = nutrients[Metric.CALORIES.key]
    val carbsGrams: Double? get() = nutrients[Metric.CARBS.key]
    val fatGrams: Double? get() = nutrients[Metric.FAT.key]
}

/** One food within an entry. */
@Serializable
data class LoggedItem(
    /** Display name of the food it resolved to. */
    val name: String,
    /** The words it came from. */
    val said: String,
    /** Its key in the user's own foods. */
    val key: String,
    /** "1 cup (244 g)", "your usual" — the amount, in words. */
    val portion: String? = null,
    val grams: Double? = null,
    /** Where the numbers came from: USDA, LABEL, PERSONAL, AI, CURATED, MANUAL. */
    val origin: String,
    /** The food row it resolved to ("usda:123", "label:456"), for opening it again. */
    val candidateId: String? = null,
    val nutrients: Map<String, Double> = emptyMap(),
    /** The parsed amount, kept so a fix can re-scale against it. */
    val amount: Double? = null,
    val unit: String? = null,
)

/** Returns a copy with one metric set, or removed when [amount] is null. */
fun Entry.withNutrient(metric: Metric, amount: Double?): Entry = copy(
    nutrients = if (amount == null) nutrients - metric.key else nutrients + (metric.key to amount),
)

/** Builds the nutrient map from the four values the old model carried. */
fun nutrientsOf(
    protein: Double? = null,
    calories: Double? = null,
    carbs: Double? = null,
    fat: Double? = null,
    fiber: Double? = null,
    sugar: Double? = null,
    saturatedFat: Double? = null,
    sodium: Double? = null,
    caffeine: Double? = null,
): Map<String, Double> = buildMap {
    protein?.let { put(Metric.PROTEIN.key, it) }
    calories?.let { put(Metric.CALORIES.key, it) }
    carbs?.let { put(Metric.CARBS.key, it) }
    fat?.let { put(Metric.FAT.key, it) }
    fiber?.let { put(Metric.FIBER.key, it) }
    sugar?.let { put(Metric.SUGAR.key, it) }
    saturatedFat?.let { put(Metric.SATURATED_FAT.key, it) }
    sodium?.let { put(Metric.SODIUM.key, it) }
    caffeine?.let { put(Metric.CAFFEINE.key, it) }
}

/** The structured-output contract requested from every LLM lookup call (Section 5). */
@Serializable
data class NutritionLookupResult(
    val name: String,
    val proteinGrams: Double,
    val calories: Double? = null,
    val carbsGrams: Double? = null,
    val fatGrams: Double? = null,
    val fiberGrams: Double? = null,
    val sugarGrams: Double? = null,
    val saturatedFatGrams: Double? = null,
    val sodiumMg: Double? = null,
    val caffeineMg: Double? = null,
    val isFixedFood: Boolean,
    val source: String,
    val confidence: Confidence,
    /** The model judged the description too vague to put numbers to. */
    val needsDetail: Boolean = false,
) {
    fun toNutrients(): Map<String, Double> = nutrientsOf(
        protein = proteinGrams, calories = calories, carbs = carbsGrams, fat = fatGrams,
        fiber = fiberGrams, sugar = sugarGrams, saturatedFat = saturatedFatGrams,
        sodium = sodiumMg, caffeine = caffeineMg,
    )
}

enum class UnprocessedErrorCode(val code: String, val message: String) {
    NO_NETWORK("NET_0012", "No connection. Err: NET_0012"),
    // Placeholder only: the real code is "API_0{status}", built per failure in EntryRepository.
    API_ERROR("API_0500", "Couldn't process that. Err: API_0500"),
    PARSE_ERROR("PARSE_0001", "Couldn't understand the result. Err: PARSE_0001"),
    AUTH_ERROR("AUTH_0001", "Check your API key in Settings. Err: AUTH_0001"),
}

/** A raw entry that failed processing and is waiting for retry (Section 7). */
@Serializable
data class UnprocessedEntry(
    val id: String,
    val timestampEpochMillis: Long,
    val rawText: String,
    val errorCode: String,
)
