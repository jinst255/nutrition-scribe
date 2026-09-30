package com.nutritionscribe.app.data.food

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.nutritionscribe.shared.food.FoodQuery
import com.nutritionscribe.shared.model.Metric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One candidate food from the bundled USDA database.
 *
 * [nutrients] is per 100 g, keyed by [Metric.key], and only holds metrics USDA
 * actually recorded — an absent metric is unknown, never zero.
 */
data class FoodMatch(
    val fdcId: Long,
    val description: String,
    val source: String,
    val nutrients: Map<String, Double>,
    /**
     * How much of this food "one of it" means when no quantity was given.
     * Computed at build time — see the note in tools/build_food_db.py. Falling
     * back to the smallest listed portion gives 6 g of banana; falling back to a
     * flat 100 g gives two eggs as 200 g. Neither is defensible.
     */
    val defaultGrams: Double = 100.0,
    val portions: List<Portion> = emptyList(),
) {
    data class Portion(val label: String, val grams: Double)

    /** Scale the per-100 g figures to an actual serving. */
    fun nutrientsFor(grams: Double): Map<String, Double> =
        nutrients.mapValues { (_, per100) -> per100 * grams / 100.0 }
}

/**
 * The offline food lookup: ~13,400 USDA foods, their portions, and a word index,
 * bundled in the APK. This is what lets the app work with no API key, no account
 * and no network.
 *
 * The index is a plain `word_index` table, NOT FTS5. Android's system SQLite is
 * compiled without the fts5 module — it is present in desktop SQLite and in every
 * tutorial, but on a device every query fails with "no such module: fts5". The
 * alternatives were bundling a custom SQLite (~4 MB per ABI) or FTS4, which has
 * no bm25(). A hand-built index costs a few hundred KB and works everywhere.
 *
 * It is read-only and deliberately NOT a Room database. Room owns the user's log,
 * which must migrate and must never be lost; this file is disposable reference
 * data that is replaced wholesale whenever the app updates. Keeping the two apart
 * means a food-data refresh can never put a single logged entry at risk.
 */
class FoodDatabase(private val context: Context) {

    private var db: SQLiteDatabase? = null

    /**
     * Bump when the bundled asset changes so installed copies refresh. Without
     * this, an app update ships new food data that no existing install ever reads.
     */
    private val assetVersion = 6

    private val dbFile: File get() = File(context.filesDir, "food-v$assetVersion.db")

    private fun open(): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }
        return try {
            if (!dbFile.exists()) {
                // SQLite needs a real file; it cannot read from a compressed asset.
                context.assets.open(ASSET_NAME).use { input ->
                    dbFile.outputStream().use { output -> input.copyTo(output) }
                }
                // Older copies are dead weight once a new version is unpacked.
                context.filesDir.listFiles()
                    ?.filter { it.name.startsWith("food-v") && it.name != dbFile.name }
                    ?.forEach { it.delete() }
            }
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
                .also { db = it }
        } catch (e: Exception) {
            // A missing or corrupt food database must degrade to "no suggestions",
            // never take the app down — the user can still type the numbers in.
            if (dbFile.exists()) dbFile.delete()
            null
        }
    }

    /**
     * Best matches for a food phrase, best first.
     *
     * A curated alias wins outright when one exists (see tools/curate_food_db.py):
     * ranking alone finds the right food but often the wrong form of it, and for
     * staples like rice that is a threefold calorie error, not a nuance.
     */
    suspend fun search(
        text: String,
        limit: Int = 5,
        prefixLast: Boolean = false,
    ): List<FoodMatch> = withContext(Dispatchers.IO) {
        val database = open() ?: return@withContext emptyList()
        val words = FoodQuery.contentWords(text)
        if (words.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<FoodMatch>()
        aliasMatch(database, text)?.let { results += it }

        for (match in rank(database, words, limit + 1, prefixLast)) {
            if (results.none { it.fdcId == match.fdcId }) results += match
            if (results.size >= limit) break
        }
        results
    }

    /** The same file, for the shared resolver. Null if it could not be opened. */
    fun reader(): com.nutritionscribe.shared.resolve.SqlReader? =
        open()?.let { com.nutritionscribe.app.data.resolve.AndroidSql(it) }

    /** The single best match, or null when nothing in the database fits. */
    suspend fun lookup(text: String): FoodMatch? = search(text, limit = 1).firstOrNull()

    /**
     * Is there a curated or learned default for this exact phrase?
     *
     * Callers need this because a curated alias is authoritative and must not be
     * second-guessed by the credibility check — "protein shake" deliberately
     * points at "Nutritional powder mix, protein, NFS", which shares no word with
     * the query, and rejecting it lost every protein shake in the log.
     */
    /**
     * Did they use a word this database has never seen?
     *
     * With filler properly stripped this is a good brand detector: USDA has no
     * "baconator", no "kodiak", no "chipotle". It is also a typo detector, which
     * is the same thing as far as confidence goes.
     */
    suspend fun hasUnknownWord(words: List<String>): Boolean = withContext(Dispatchers.IO) {
        val database = open() ?: return@withContext false
        try {
            words.any { word ->
                database.rawQuery(
                    "SELECT 1 FROM word_index WHERE word = ? LIMIT 1",
                    arrayOf(word),
                ).use { !it.moveToFirst() }
            }
        } catch (e: SQLiteException) {
            false
        }
    }

    suspend fun hasAlias(text: String): Boolean = withContext(Dispatchers.IO) {
        val database = open() ?: return@withContext false
        try {
            database.rawQuery(
                "SELECT 1 FROM alias WHERE term = ?",
                arrayOf(FoodQuery.normalise(text)),
            ).use { it.moveToFirst() }
        } catch (e: SQLiteException) {
            false
        }
    }

    private fun aliasMatch(database: SQLiteDatabase, text: String): FoodMatch? =
        try {
            database.rawQuery(
                "SELECT $COLUMNS FROM alias a JOIN food f ON f.fdc_id = a.fdc_id WHERE a.term = ?",
                arrayOf(FoodQuery.normalise(text)),
            ).use { c -> if (c.moveToFirst()) readMatch(database, c) else null }
        } catch (e: SQLiteException) {
            null
        }

    private fun rank(
        database: SQLiteDatabase,
        words: List<String>,
        limit: Int,
        prefixLast: Boolean,
    ): List<FoodMatch> {
        // Scoring only reads the description, so the expensive columns are fetched
        // for the handful of winners rather than for every candidate. Reading all
        // twelve columns for the whole shortlist cost about half a second per
        // lookup on a device, because every cursor read crosses a Binder window.
        try {
            val where = StringBuilder("w.word IN (${words.joinToString(",") { "?" }})")
            val args = words.toMutableList()
            if (prefixLast && words.isNotEmpty()) {
                where.append(" OR w.word LIKE ?")
                args.add(words.last() + "%")
            }

            // The LENGTH tie-break is load-bearing. For a single-word query every
            // candidate scores exactly one hit, so ordering by hits alone lets the
            // LIMIT truncate arbitrarily — "Egg, whole, raw" was being cut before
            // ranking ever saw it, and "egg" resolved to "Egg burrito". USDA's
            // generic forms have the shortest descriptions.
            val ids = mutableListOf<String>()
            database.rawQuery(
                "SELECT w.fdc_id FROM word_index w JOIN food f ON f.fdc_id = w.fdc_id" +
                    " WHERE $where GROUP BY w.fdc_id" +
                    " ORDER BY COUNT(DISTINCT w.word) DESC, LENGTH(f.description) ASC" +
                    " LIMIT $CANDIDATES",
                args.toTypedArray(),
            ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0).toString()) }
            if (ids.isEmpty()) return emptyList()

            val placeholders = ids.joinToString(",") { "?" }
            val scored = mutableListOf<Pair<Long, Double>>()
            database.rawQuery(
                "SELECT fdc_id, description, source FROM food WHERE fdc_id IN ($placeholders)",
                ids.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    scored.add(c.getLong(0) to FoodQuery.score(words, c.getString(1), c.getString(2)))
                }
            }

            val winners = scored.sortedByDescending { it.second }.take(limit).map { it.first }
            if (winners.isEmpty()) return emptyList()

            val byId = mutableMapOf<Long, FoodMatch>()
            database.rawQuery(
                "SELECT $COLUMNS FROM food f WHERE f.fdc_id IN (${winners.joinToString(",") { "?" }})",
                winners.map { it.toString() }.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val m = readMatch(database, c, withPortions = false)
                    byId[m.fdcId] = m
                }
            }
            // Re-impose the ranked order; SQL's IN gives no ordering guarantee.
            return winners.mapNotNull { byId[it] }
                .map { it.copy(portions = portionsFor(database, it.fdcId)) }
        } catch (e: SQLiteException) {
            return emptyList()
        }
    }

    private fun readMatch(
        database: SQLiteDatabase,
        c: android.database.Cursor,
        withPortions: Boolean = true,
    ): FoodMatch {
        val id = c.getLong(0)
        val nutrients = buildMap {
            NUTRIENT_COLUMNS.forEachIndexed { i, (_, metric) ->
                val col = 3 + i
                // Absent means USDA never recorded it. Storing 0.0 would turn
                // "unknown" into a claim, and the app treats those differently.
                if (!c.isNull(col)) put(metric.key, c.getDouble(col))
            }
        }
        val defaultCol = 3 + NUTRIENT_COLUMNS.size
        return FoodMatch(
            fdcId = id,
            description = c.getString(1),
            source = c.getString(2),
            nutrients = nutrients,
            defaultGrams = if (c.isNull(defaultCol)) 100.0 else c.getDouble(defaultCol),
            portions = if (withPortions) portionsFor(database, id) else emptyList(),
        )
    }

    private fun portionsFor(database: SQLiteDatabase, fdcId: Long): List<FoodMatch.Portion> {
        val out = mutableListOf<FoodMatch.Portion>()
        return try {
            database.rawQuery(
                "SELECT label, grams FROM portion WHERE fdc_id = ? ORDER BY grams LIMIT 12",
                arrayOf(fdcId.toString()),
            ).use { c ->
                while (c.moveToNext()) out.add(FoodMatch.Portion(c.getString(0), c.getDouble(1)))
            }
            out
        } catch (e: SQLiteException) {
            emptyList()
        }
    }

    fun close() {
        db?.takeIf { it.isOpen }?.close()
        db = null
    }

    private companion object {
        const val ASSET_NAME = "food.db"

        /** Wider than the shortlist so ranking has something to choose between. */
        const val CANDIDATES = 120

        /** Column order must match [readMatch]; index 3 onward are the nutrients. */
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
        )

        val COLUMNS = "f.fdc_id, f.description, f.source, " +
            NUTRIENT_COLUMNS.joinToString(", ") { "f.${it.first}" } + ", f.default_grams"
    }
}
