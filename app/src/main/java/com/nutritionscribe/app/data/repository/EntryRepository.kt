package com.nutritionscribe.app.data.repository

import com.nutritionscribe.app.data.cache.FixedFoodCache
import com.nutritionscribe.app.data.food.FoodDatabase
import com.nutritionscribe.app.data.food.FoodMatch
import com.nutritionscribe.app.data.db.EntryDao
import com.nutritionscribe.app.data.db.FrequentFood
import com.nutritionscribe.app.data.db.UnprocessedDao
import com.nutritionscribe.app.data.db.toEntry
import com.nutritionscribe.app.data.db.toNutrientRows
import com.nutritionscribe.app.data.db.toModel
import com.nutritionscribe.app.data.db.toRow
import com.nutritionscribe.app.network.backend.AiBackend
import com.nutritionscribe.app.network.backend.LookupOutcome
import com.nutritionscribe.app.ui.debuglog.DebugLogger
import com.nutritionscribe.shared.food.FoodQuery
import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.nutrientsOf
import com.nutritionscribe.shared.model.UnprocessedEntry
import com.nutritionscribe.shared.model.UnprocessedErrorCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

sealed class LogEntryResult {
    data class NeedsReview(val entry: Entry) : LogEntryResult()
    data class Failed(val errorCode: UnprocessedErrorCode, val code: String, val message: String) : LogEntryResult()
}

/**
 * Single source of truth for entries (Section 14). Implements the logging pipeline
 * from Section 4: stated-value shortcut, fixed-food cache shortcut, LLM lookup,
 * and the unprocessed-queue fallback on any failure — nothing typed or said is ever
 * discarded, only its processing is deferred.
 */
class EntryRepository(
    private val entryDao: EntryDao,
    private val unprocessedDao: UnprocessedDao,
    private val fixedFoodCache: FixedFoodCache,
    private val backend: AiBackend,
    private val debugLogger: DebugLogger,
    private val foodDatabase: FoodDatabase? = null,
    private val lookupSource: LookupSource = LookupSource.AI,
) {

    private companion object {
        val GRAM_UNITS = setOf("g", "gram", "grams")
        val OUNCE_UNITS = setOf("oz", "ounce", "ounces")
    }

    fun observeEntries(): Flow<List<Entry>> = entryDao.observeAll().map { rows -> rows.map { it.toEntry() } }

    /** Running total of one metric over a window. */
    fun observeMetricTotal(metric: Metric, startEpochMillis: Long, endEpochMillis: Long): Flow<Double> =
        entryDao.observeMetricTotal(metric.key, startEpochMillis, endEpochMillis)

    fun observeProteinTotal(startEpochMillis: Long, endEpochMillis: Long): Flow<Double> =
        observeMetricTotal(Metric.PROTEIN, startEpochMillis, endEpochMillis)

    fun search(query: String): Flow<List<Entry>> = entryDao.search(query).map { rows -> rows.map { it.toEntry() } }

    fun observeUnprocessed(): Flow<List<UnprocessedEntry>> =
        unprocessedDao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun discardUnprocessed(entry: UnprocessedEntry) = unprocessedDao.delete(entry.toRow())

    suspend fun clearUnprocessed() = unprocessedDao.deleteAll()

    /**
     * Runs the full pipeline for a fresh raw entry (typed or transcribed). Returns a
     * NeedsReview entry (not yet saved) for the Review screen, or Failed if it must go
     * to the Unprocessed Queue instead. Whether the user already stated a definitive
     * protein amount (vs. only mentioning one ingredient's protein within a larger,
     * multi-part meal) is judged by the model itself via the prompt, not a client-side
     * shortcut — see [AiBackend.lookup].
     */
    suspend fun processRawEntry(rawText: String): LogEntryResult {
        val cached = fixedFoodCache.lookup(rawText)
        if (cached != null) {
            return LogEntryResult.NeedsReview(toPendingEntry(rawText, cached))
        }

        if (lookupSource == LookupSource.OFFLINE) {
            return offlineLookup(rawText)
                ?: LogEntryResult.Failed(
                    UnprocessedErrorCode.PARSE_ERROR,
                    "OFFLINE_NO_MATCH",
                    "No match in the offline food list. Add it manually, or switch on AI lookup in Settings.",
                )
        }

        return when (val outcome = backend.lookup(rawText)) {
            is LookupOutcome.Success -> {
                fixedFoodCache.store(outcome.result)
                LogEntryResult.NeedsReview(toPendingEntry(rawText, outcome.result))
            }
            is LookupOutcome.Failure -> failureFor(outcome, rawText)
        }
    }

    /**
     * Nutrition from the bundled USDA database — no key, no network, no cost.
     *
     * A quantity in the text ("8oz chicken breast") is applied against the food's
     * household portions; with no quantity the entry is one standard portion, or
     * 100 g when USDA lists none. Confidence is ESTIMATED, never STATED: the
     * numbers are a real measurement of *a* chicken breast, not of the one eaten.
     */
    private suspend fun offlineLookup(rawText: String): LogEntryResult? {
        val foods = foodDatabase ?: return null

        // "A solid breakfast" names no food, so any match is fiction. This used to
        // answer with "Breakfast bar, NFS" — a number the user would have to notice
        // was invented. Keep their words, ask for the detail later.
        if (FoodQuery.isVague(rawText)) return LogEntryResult.NeedsReview(vagueEntry(rawText))

        // Dictation is messy in repeatable ways: unicode fractions run into the
        // next word, and the quantity turns up wherever the sentence put it.
        // The claim comes out of the RAW text: preprocess moves a stray "6 g" to
        // the front, which would split "6 g of protein" apart before it is read.
        val claim = FoodQuery.stripProteinClaim(rawText)
        val text = FoodQuery.preprocess(claim.text)

        // "chicken, rice and broccoli" is three foods. Looking up the whole phrase
        // matched "Rice, fried, with chicken" and silently dropped the broccoli.
        // A curated phrase is exempt: "mac and cheese" split on "and" and matched
        // "Big Mac (McDonalds)".
        val plain = if (foods.hasAlias(text)) listOf(text) else FoodQuery.splitItems(text)
        val readings = mutableListOf(resolve(foods, plain))
        val widened = FoodQuery.splitOnWith(plain)
        if (widened != plain) readings += resolve(foods, widened)

        // Whichever reading accounts for more of what was actually said. Ties go
        // to the first, which is the conservative one that did not split on "with".
        val allWords = FoodQuery.contentWords(text)
        val found = readings.maxByOrNull {
            FoodQuery.explained(it.map { (match, _) -> match.description }, allWords)
        }.orEmpty()
        if (found.isEmpty()) return null

        val nutrients = mutableMapOf<String, Double>()
        for ((match, grams) in found) {
            for ((key, amount) in match.nutrientsFor(grams)) {
                nutrients[key] = (nutrients[key] ?: 0.0) + amount
            }
        }

        // Their own stated figure beats an estimate for a food the database may not
        // even contain — "Built bar, 16g protein" matched a different bar at 30 g.
        val stated = claim.grams
        if (stated != null) nutrients[Metric.PROTEIN.key] = stated

        // Coverage is the honest confidence signal; a single match that accounts
        // for less than half of what was said is a guess worth flagging.
        val single = found.singleOrNull()
        val weak = single != null && FoodQuery.coverage(
            FoodQuery.contentWords(FoodQuery.parseQuantity(text).food),
            single.first.description,
        ) < 0.5

        return LogEntryResult.NeedsReview(
            Entry(
                id = UUID.randomUUID().toString(),
                timestampEpochMillis = System.currentTimeMillis(),
                rawText = rawText,
                name = if (found.size == 1) found[0].first.description else rawText,
                nutrients = nutrients,
                isFixedFood = false,
                source = "usda",
                confidence = if (stated != null) Confidence.STATED else Confidence.ESTIMATED,
                needsDetail = weak,
                foodDbId = single?.first?.fdcId,
            ),
        )
    }

    /** Keeps what was said without inventing numbers for it. */
    private fun vagueEntry(rawText: String) = Entry(
        id = UUID.randomUUID().toString(),
        timestampEpochMillis = System.currentTimeMillis(),
        rawText = rawText,
        name = rawText,
        nutrients = emptyMap(),
        isFixedFood = false,
        source = "usda",
        confidence = Confidence.ESTIMATED,
        needsDetail = true,
    )

    /**
     * Look each item up, dropping the ones whose match is not credible.
     *
     * A curated alias is exempt from the credibility check — see
     * [FoodQuery.isCredible] and [FoodDatabase.hasAlias].
     */
    private suspend fun resolve(
        foods: FoodDatabase,
        items: List<String>,
    ): List<Pair<FoodMatch, Double>> = items.mapNotNull { item ->
        val parsed = FoodQuery.parseQuantity(FoodQuery.preprocess(item))
        val match = foods.lookup(parsed.food) ?: return@mapNotNull null
        if (!foods.hasAlias(parsed.food) &&
            !FoodQuery.isCredible(parsed.food, match.description)
        ) {
            return@mapNotNull null
        }
        match to gramsFor(match, parsed, item)
    }

    private fun gramsFor(match: FoodMatch, parsed: FoodQuery.Parsed, text: String = ""): Double {
        val count = parsed.amount ?: 1.0
        val unit = parsed.unit

        if (unit in GRAM_UNITS) return count
        if (unit in OUNCE_UNITS) return count * 28.3495

        // A unit the person actually named beats any default.
        if (unit != null) {
            val singular = unit.trimEnd('s')
            val named = match.portions.filter { it.label.contains(singular, ignoreCase = true) }
            // Portions arrive smallest first, so taking the first match meant "4
            // slices of white bread" used a 10 g "1 slice, snack-size".
            if (named.isNotEmpty()) return count * plainest(named)
        }

        // "a small cookie", "1 large pancake" — a size the person named beats any
        // default, exactly as a unit would. Without this, "small" was discarded as
        // a stop word and a small cookie was logged at 295 calories.
        FoodQuery.sizeOf(text.ifEmpty { parsed.food })?.let { size ->
            val sized = match.portions.filter { it.label.contains(size, ignoreCase = true) }
            if (sized.isNotEmpty()) return count * plainest(sized)
        }

        // A bare count of a countable food means that many OF IT, not that many
        // servings: "six pancakes" was logged as six 100 g servings, 1692 calories.
        if (parsed.amount != null && unit == null) {
            val item = itemPortion(match)
            // A quarter of the serving is the floor. USDA lists "1 chip" (2 g) for
            // potato chips, so "a bag of potato chips" came out at ELEVEN calories.
            if (item != null && item >= match.defaultGrams * 0.25) return count * item
        }

        // No unit given, so "2 eggs" means two of whatever one egg is. This is the
        // build-time default serving, NOT the smallest listed portion: that would
        // read "banana" as one 6 g slice.
        return count * match.defaultGrams
    }

    /** A portion that is one of the food itself, preferring the medium one. */
    private fun itemPortion(match: FoodMatch): Double? {
        val nouns = Regex("[a-z]+").findAll(FoodQuery.headOf(match.description))
            .map { FoodQuery.stem(it.value) }.filter { it.length > 2 }.toSet()
        if (nouns.isEmpty()) return null

        val named = match.portions.filter { portion ->
            val low = portion.label.lowercase()
            val words = Regex("[a-z]+").findAll(low).map { it.value }.toList()
            if (words.none { FoodQuery.stem(it) in nouns }) return@filter false
            // "1 medium pancake" describes one pancake. "1 Bagel Thin" describes a
            // different product that happens to say bagel, and taking it made "a
            // bagel with cream cheese" a 46 g bagel. Accept only a size-qualified
            // portion or the bare "1 <food>".
            val sized = FoodQuery.SIZE_WORDS.any { low.contains(it) }
            sized || words.none { FoodQuery.stem(it) !in nouns }
        }
        return if (named.isEmpty()) null else plainest(named)
    }

    /**
     * The default-sized portion out of several labelled ones.
     *
     * USDA offers "1 slice, snack-size" (10 g) through "1 large or thick slice"
     * (43 g) for the same bread. "Medium"/"regular" is USDA's own word for the
     * normal one; failing that the shortest label is the least qualified.
     */
    private fun plainest(portions: List<FoodMatch.Portion>): Double {
        for (want in listOf("medium", "regular")) {
            portions.firstOrNull { it.label.contains(want, ignoreCase = true) }
                ?.let { return it.grams }
        }
        return portions.minBy { it.label.length }.grams
    }

    private fun toPendingEntry(rawText: String, result: com.nutritionscribe.shared.model.NutritionLookupResult) = Entry(
        id = UUID.randomUUID().toString(),
        timestampEpochMillis = System.currentTimeMillis(),
        rawText = rawText,
        name = result.name,
        nutrients = result.toNutrients(),
        isFixedFood = result.isFixedFood,
        source = result.source,
        confidence = result.confidence,
        needsDetail = result.needsDetail,
    )

    private suspend fun failureFor(failure: LookupOutcome.Failure, rawText: String): LogEntryResult.Failed {
        val code = when (failure) {
            is LookupOutcome.Failure.NoNetwork -> UnprocessedErrorCode.NO_NETWORK
            is LookupOutcome.Failure.AuthError -> UnprocessedErrorCode.AUTH_ERROR
            is LookupOutcome.Failure.MalformedResponse -> UnprocessedErrorCode.PARSE_ERROR
            is LookupOutcome.Failure.ApiError -> UnprocessedErrorCode.API_ERROR
        }
        // API errors carry the real HTTP status in the code (Section 6: "API_0{status}"),
        // so a 400 and a 429 don't both show up as the same generic code.
        val codeString = if (failure is LookupOutcome.Failure.ApiError) "API_0${failure.statusCode}" else code.code
        val message = if (failure is LookupOutcome.Failure.ApiError) "Couldn't process that. Err: $codeString" else code.message
        debugLogger.log("EntryRepository", "Lookup failed: $failure for entry length=${rawText.length}")
        val unprocessed = UnprocessedEntry(
            id = UUID.randomUUID().toString(),
            timestampEpochMillis = System.currentTimeMillis(),
            rawText = rawText,
            errorCode = codeString,
        )
        unprocessedDao.insert(unprocessed.toRow())
        return LogEntryResult.Failed(code, codeString, message)
    }

    suspend fun retryUnprocessed(entry: UnprocessedEntry): LogEntryResult {
        val result = processRawEntry(entry.rawText)
        if (result is LogEntryResult.NeedsReview) {
            unprocessedDao.delete(entry.toRow())
        }
        return result
    }

    /** Backing data for the manual-entry quick-pick (see [FrequentFood]). */
    fun observeFrequentFoods(limit: Int = 12): Flow<List<FrequentFood>> = entryDao.observeFrequentFoods(limit)

    /**
     * Saves an entry the user filled in themselves, with no LLM call and no Review step.
     * This is the whole no-API-key path, and it is also how entries get backdated onto a
     * past day. Confidence is STATED because the number came from a human, not an estimate.
     */
    suspend fun logManual(
        name: String,
        proteinGrams: Double,
        calories: Double? = null,
        carbsGrams: Double? = null,
        fatGrams: Double? = null,
        timestampEpochMillis: Long = System.currentTimeMillis(),
        timeUnspecified: Boolean = false,
    ): Entry {
        val entry = Entry(
            id = UUID.randomUUID().toString(),
            timestampEpochMillis = timestampEpochMillis,
            rawText = name,
            name = name,
            nutrients = nutrientsOf(
                protein = proteinGrams, calories = calories, carbs = carbsGrams, fat = fatGrams,
            ),
            isFixedFood = false,
            source = "manual",
            confidence = Confidence.STATED,
            timeUnspecified = timeUnspecified,
        )
        entryDao.upsert(entry.toRow(), entry.toNutrientRows())
        return entry
    }

    suspend fun confirm(entry: Entry) {
        entryDao.upsert(entry.toRow(), entry.toNutrientRows())
    }

    suspend fun update(entry: Entry) {
        entryDao.upsert(entry.toRow(), entry.toNutrientRows())
    }

    suspend fun delete(entry: Entry) {
        entryDao.delete(entry.toRow())
    }

    suspend fun deleteMany(ids: List<String>) {
        entryDao.deleteByIds(ids)
    }

    suspend fun dismissUnprocessed(entry: UnprocessedEntry) {
        unprocessedDao.delete(entry.toRow())
    }
}
