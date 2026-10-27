package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.food.FoodQuery
import com.nutritionscribe.shared.model.Metric

/**
 * Search over the bundled USDA database (food.db).
 *
 * This is the ranking that used to live in the app's FoodDatabase, moved here so
 * the tests exercise it directly. The SQL and scoring are unchanged: a curated
 * alias wins outright, then a hand-built word index (Android's SQLite has no
 * FTS5) shortlists rows and [FoodQuery.score] orders them.
 */
class UsdaFoodSource(private val sql: SqlReader) {

    fun search(text: String, limit: Int = 5): List<FoodCandidate> {
        val words = FoodQuery.contentWords(text)
        if (words.isEmpty()) return emptyList()
        val out = mutableListOf<FoodCandidate>()
        alias(text)?.let { out += it }
        for (c in rank(words, limit + 1)) {
            if (out.none { it.id == c.id }) out += c
            if (out.size >= limit) break
        }
        return out
    }

    fun byId(fdcId: Long): FoodCandidate? = runCatching {
        sql.query("SELECT $COLUMNS FROM food f WHERE f.fdc_id = ?", listOf(fdcId.toString())) { read(it) }
            .firstOrNull()?.let { withPortions(it) }
    }.getOrNull()

    /** A curated phrase: authoritative, and exempt from the credibility check. */
    fun hasAlias(text: String): Boolean = runCatching {
        sql.query("SELECT 1 FROM alias WHERE term = ?", listOf(FoodQuery.normalise(text))) { true }
            .isNotEmpty()
    }.getOrDefault(false)

    /** Has this database ever seen the (stemmed) word? A brand or a typo, if not. */
    private val known = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun knowsWord(word: String): Boolean = known.getOrPut(word) {
        runCatching {
            sql.query("SELECT 1 FROM word_index WHERE word = ? LIMIT 1", listOf(word)) { true }.isNotEmpty()
        }.getOrDefault(false)
    }

    private fun alias(text: String): FoodCandidate? = runCatching {
        sql.query(
            "SELECT $COLUMNS FROM alias a JOIN food f ON f.fdc_id = a.fdc_id WHERE a.term = ?",
            listOf(FoodQuery.normalise(text)),
        ) { read(it) }.firstOrNull()?.let { withPortions(it) }
    }.getOrNull()

    private fun rank(words: List<String>, limit: Int): List<FoodCandidate> = runCatching {
        // The LENGTH tie-break is load-bearing: for a one-word query every row has
        // one hit, and without it the LIMIT cut "Egg, whole, raw" before ranking
        // ever saw it.
        val ids = sql.query(
            "SELECT w.fdc_id FROM word_index w JOIN food f ON f.fdc_id = w.fdc_id" +
                " WHERE w.word IN (${words.joinToString(",") { "?" }}) GROUP BY w.fdc_id" +
                " ORDER BY COUNT(DISTINCT w.word) DESC, LENGTH(f.description) ASC LIMIT $CANDIDATES",
            words,
        ) { it.long(0).toString() }
        if (ids.isEmpty()) return@runCatching emptyList()

        val scored = sql.query(
            "SELECT fdc_id, description, source FROM food WHERE fdc_id IN (${ids.joinToString(",") { "?" }})",
            ids,
        ) { it.long(0) to FoodQuery.score(words, it.string(1).orEmpty(), it.string(2)) + asEaten(words, it.string(1).orEmpty()) }
        val winners = scored.sortedByDescending { it.second }.take(limit).map { it.first }
        if (winners.isEmpty()) return@runCatching emptyList()

        val byId = sql.query(
            "SELECT $COLUMNS FROM food f WHERE f.fdc_id IN (${winners.joinToString(",") { "?" }})",
            winners.map { it.toString() },
        ) { read(it) }.associateBy { it.id }
        winners.mapNotNull { byId["usda:$it"] }.map { withPortions(it) }
    }.getOrDefault(emptyList())

    private fun read(r: SqlRow): FoodCandidate {
        val nutrients = buildMap {
            NUTRIENT_COLUMNS.forEachIndexed { i, (_, metric) ->
                // Absent means USDA never recorded it. 0.0 would turn "unknown"
                // into a claim.
                r.doubleOrNull(3 + i)?.let { put(metric.key, it) }
            }
        }
        return FoodCandidate(
            id = "usda:${r.long(0)}",
            name = r.string(1).orEmpty(),
            origin = Origin.USDA,
            per100g = nutrients,
            defaultGrams = r.doubleOrNull(3 + NUTRIENT_COLUMNS.size) ?: 100.0,
        )
    }

    private fun withPortions(c: FoodCandidate): FoodCandidate {
        val fdcId = c.id.removePrefix("usda:")
        val portions = runCatching {
            sql.query(
                "SELECT label, grams FROM portion WHERE fdc_id = ? ORDER BY grams LIMIT 12",
                listOf(fdcId),
            ) { Portion(it.string(0).orEmpty(), it.doubleOrNull(1) ?: 0.0) }
        }.getOrDefault(emptyList())
        return c.copy(portions = portions.filter { it.grams > 0 })
    }

    /**
     * Ranking by words alone could not tell the plain food from the prepared one:
     * "150 g grilled chicken breast" found "Chicken breast, grilled with sauce",
     * "baked sweet potato" the "fat added" row, "pork tenderloin" the RAW meat. A
     * row that adds something nobody said loses points; plain preparation, and a
     * cooked form of meat or fish, gains them. Measured in tools/foodsearch.py.
     */
    private fun asEaten(words: List<String>, description: String): Double {
        val d = description.lowercase()
        var adj = 0.0
        for ((pattern, penalty) in ADDED) {
            if (pattern.containsMatchIn(d) && words.none { w -> pattern.pattern.contains(w) }) adj -= penalty
        }
        if (PLAIN.containsMatchIn(d)) adj += 3.0
        val head = FoodQuery.headOf(description)
        if (RAW_ROW.containsMatchIn(d) && MEATS.any { head.contains(it) } && "raw" !in words) adj -= 14.0
        return adj
    }

    companion object {
        private val ADDED = listOf(
            Regex("\\bwith sauce\\b") to 8.0, Regex("\\bfat added\\b") to 6.0, Regex("\\bskin eaten\\b") to 5.0,
            Regex("\\bcoated\\b") to 6.0, Regex("\\bbreaded\\b") to 6.0, Regex("\\bfrom restaurant\\b") to 5.0,
            Regex("\\bglutinous\\b") to 6.0, Regex("\\bsprouted\\b") to 6.0, Regex("\\bstir-fried\\b") to 5.0,
            Regex("\\bpuerto rican\\b") to 4.0, Regex("\\bnuggets\\b") to 5.0, Regex("\\bchinese\\b") to 4.0,
            Regex("\\bsweetened\\b") to 3.0, Regex("\\bcanned\\b") to 2.0, Regex("\\bfrozen\\b") to 2.0,
            Regex("\\bdry mix\\b|\\bunprepared\\b") to 10.0, Regex("\\bsoup\\b") to 4.0,
        )
        private val PLAIN = Regex("\\b(no added fat|skin not eaten|meat only|plain|without salt|lean only)\\b")
        private val RAW_ROW = Regex("\\braw\\b|\\bunheated\\b|\\buncooked\\b")
        private val MEATS = listOf("beef", "pork", "chicken", "turkey", "fish", "salmon", "tuna", "cod", "shrimp", "lamb", "ham", "sausage", "bacon")

        /** Wider than the shortlist so ranking has something to choose between. */
        private const val CANDIDATES = 120

        /** Column order is what [read] indexes; 3 onward are nutrients. */
        val NUTRIENT_COLUMNS = listOf(
            "kcal" to Metric.CALORIES,
            "protein_g" to Metric.PROTEIN,
            "carbs_g" to Metric.CARBS,
            "fat_g" to Metric.FAT,
            "fiber_g" to Metric.FIBER,
            "sugar_g" to Metric.SUGAR,
            "sat_fat_g" to Metric.SATURATED_FAT,
            "sodium_mg" to Metric.SODIUM,
            "caffeine_mg" to Metric.CAFFEINE,
            // Micronutrients (food.db since asset version 6). The first nine are also
            // the label pack's columns, so new ones only ever go on the end.
            "calcium_mg" to Metric.CALCIUM,
            "iron_mg" to Metric.IRON,
            "potassium_mg" to Metric.POTASSIUM,
            "magnesium_mg" to Metric.MAGNESIUM,
            "zinc_mg" to Metric.ZINC,
            "vitamin_a_ug" to Metric.VITAMIN_A,
            "vitamin_c_mg" to Metric.VITAMIN_C,
            "vitamin_d_ug" to Metric.VITAMIN_D,
            "vitamin_e_mg" to Metric.VITAMIN_E,
            "vitamin_k_ug" to Metric.VITAMIN_K,
            "vitamin_b12_ug" to Metric.VITAMIN_B12,
            "folate_ug" to Metric.FOLATE,
            "selenium_ug" to Metric.SELENIUM,
            "omega3_mg" to Metric.OMEGA3,
            "cholesterol_mg" to Metric.CHOLESTEROL,
            // More to track (food.db since asset version 7, tools/add_extras_food_db.py).
            "copper_mg" to Metric.COPPER,
            "manganese_mg" to Metric.MANGANESE,
            "phosphorus_mg" to Metric.PHOSPHORUS,
            "choline_mg" to Metric.CHOLINE,
            "thiamin_mg" to Metric.THIAMIN,
            "riboflavin_mg" to Metric.RIBOFLAVIN,
            "niacin_mg" to Metric.NIACIN,
            "vitamin_b6_mg" to Metric.VITAMIN_B6,
            "ala_g" to Metric.OMEGA3_ALA,
            "alcohol_g" to Metric.ALCOHOL,
        )

        private val COLUMNS = "f.fdc_id, f.description, f.source, " +
            NUTRIENT_COLUMNS.joinToString(", ") { "f.${it.first}" } + ", f.default_grams"
    }
}
