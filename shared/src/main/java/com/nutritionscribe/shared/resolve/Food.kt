package com.nutritionscribe.shared.resolve

import kotlinx.serialization.Serializable

/**
 * The smallest window onto SQLite the food sources need.
 *
 * It exists so the search and ranking code lives HERE, in plain Kotlin, and the
 * app and the JVM tests run the very same code: the app wraps Android's
 * SQLiteDatabase, the tests wrap sqlite-jdbc over the same food.db file. Before
 * this, the ranking lived in the Android class and could only be tested on a
 * device, so it was tested far less than it was changed.
 */
interface SqlReader {
    fun <T> query(sql: String, args: List<String>, map: (SqlRow) -> T): List<T>
}

interface SqlRow {
    fun long(i: Int): Long
    fun doubleOrNull(i: Int): Double?
    fun string(i: Int): String?
}

/** Where a candidate's numbers came from. Persisted by name, so never rename. */
enum class Origin {
    /** The bundled USDA database. */
    USDA,
    /** A real package label, from the optional label pack or a scan. */
    LABEL,
    /** The user's own confirmed answer. */
    PERSONAL,
    /** The opt-in AI lookup. */
    AI,
    /** A curated typical value for something USDA does not list as people buy it. */
    CURATED,
    /** Typed in by hand. */
    MANUAL,
}

@Serializable
data class Portion(val label: String, val grams: Double)

/**
 * One food something could resolve to.
 *
 * Numbers are carried per 100 g where the source knows the weight (USDA, labels),
 * and as a fixed amount for [servingLabel] where it does not (an AI answer, a
 * hand-typed entry). [nutrientsFor] hides the difference from everything else.
 */
@Serializable
data class FoodCandidate(
    /** Stable id: "usda:2341362", "label:1234567", "ai:<hash>", "mine:<key>". */
    val id: String,
    val name: String,
    val origin: Origin,
    /** Per 100 g, keyed by Metric.key. Null when the source never had a weight. */
    val per100g: Map<String, Double>? = null,
    val portions: List<Portion> = emptyList(),
    /** Grams in "one of it" when nothing else says how much. */
    val defaultGrams: Double? = null,
    /** For sources without a weight: the numbers for exactly one [servingLabel]. */
    val perServing: Map<String, Double>? = null,
    val servingLabel: String? = null,
    /** The serving a label names ("2 cookies (29g)") — how packaged food is counted. */
    val labelServingGrams: Double? = null,
    val labelServingText: String? = null,
    val brand: String? = null,
    /** The company behind the brand ("fairlife, LLC" for Core Power): it names the product too. */
    val owner: String? = null,
    val barcode: String? = null,
) {
    /** Nutrients for [grams] of it, or for [servings] of a weightless source. */
    fun nutrientsFor(grams: Double?, servings: Double = 1.0): Map<String, Double> = when {
        per100g != null && grams != null -> per100g.mapValues { (_, v) -> v * grams / 100.0 }
        perServing != null -> perServing.mapValues { (_, v) -> v * servings }
        else -> emptyMap()
    }
}
