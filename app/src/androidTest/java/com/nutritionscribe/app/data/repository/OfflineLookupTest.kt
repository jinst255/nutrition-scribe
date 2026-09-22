package com.nutritionscribe.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nutritionscribe.app.data.cache.FixedFoodCache
import com.nutritionscribe.app.data.db.NutritionScribeDatabase
import com.nutritionscribe.app.data.food.FoodDatabase
import com.nutritionscribe.app.network.backend.AiBackend
import com.nutritionscribe.app.network.backend.LookupOutcome
import com.nutritionscribe.app.ui.debuglog.DebugLogger
import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Metric
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The offline path end to end: raw text in, a reviewable entry with real numbers
 * out, without an API key or a network.
 */
@RunWith(AndroidJUnit4::class)
class OfflineLookupTest {

    private lateinit var db: NutritionScribeDatabase
    private lateinit var repo: EntryRepository

    /** Fails loudly if called: nothing here may reach the network. */
    private val forbiddenBackend = object : AiBackend {
        override suspend fun lookup(rawText: String): LookupOutcome =
            throw AssertionError("offline lookup must never call the AI backend")
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NutritionScribeDatabase::class.java).build()
        repo = EntryRepository(
            entryDao = db.entryDao(),
            unprocessedDao = db.unprocessedDao(),
            fixedFoodCache = FixedFoodCache(db.fixedFoodCacheDao()),
            backend = forbiddenBackend,
            debugLogger = DebugLogger(context),
            foodDatabase = FoodDatabase(context),
            lookupSource = LookupSource.OFFLINE,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun entryFor(text: String) =
        (runBlocking { repo.processRawEntry(text) } as LogEntryResult.NeedsReview).entry

    @Test
    fun aPlainFoodBecomesAReviewableEntryWithRealNumbers() {
        val entry = entryFor("banana")
        assertTrue(entry.name.contains("Banana", ignoreCase = true))
        assertTrue("no calories came back", entry.calories!! > 0)
        assertTrue(entry.proteinGrams >= 0)
        assertEquals("usda", entry.source)
        // The numbers describe a typical banana, not the one that was eaten.
        assertEquals(Confidence.ESTIMATED, entry.confidence)
    }

    @Test
    fun theSourceFoodIsRecordedSoTheEntryCanBeRecheckedLater() {
        // foodDbId is what lets a future version re-derive nutrients for a metric
        // the user only started tracking this month.
        assertTrue((entryFor("banana").foodDbId ?: 0L) > 0L)
    }

    @Test
    fun anExplicitWeightIsUsedLiterally() {
        val hundred = entryFor("100g chicken breast")
        val twoHundred = entryFor("200g chicken breast")
        assertEquals(
            "doubling the grams must double the calories",
            hundred.calories!! * 2, twoHundred.calories!!, 1.0,
        )
    }

    @Test
    fun ouncesAreConvertedToGramsNotTreatedAsGrams() {
        // 8 oz is 227 g. Treating it as 8 g would under-report by 28x, which is
        // exactly the kind of error a food log would never surface on its own.
        val eightOz = entryFor("8oz chicken breast")
        val oneGram = entryFor("1g chicken breast")
        assertEquals(226.8, eightOz.calories!! / oneGram.calories!!, 5.0)
    }

    @Test
    fun aCookedStapleDoesNotReturnRawCalories() {
        // Raw white rice is ~360 kcal/100g, cooked ~129. This is the single most
        // consequential default in the whole offline path.
        val rice = entryFor("100g white rice")
        assertTrue("got ${rice.calories} kcal for 100g rice", rice.calories!! < 200)
    }

    @Test
    fun everyTrackedMetricComesBackNotJustProtein() {
        // The whole point of capturing full nutrition: a goal switched on in March
        // should still be able to show what January looked like.
        val entry = entryFor("100g bacon")
        for (metric in listOf(Metric.CALORIES, Metric.PROTEIN, Metric.FAT, Metric.SODIUM, Metric.SATURATED_FAT)) {
            assertTrue("missing ${metric.key}", entry.amountOf(metric) != null)
        }
    }

    @Test
    fun aBareFoodNameLogsARealisticServing() {
        // The bug this guards shipped once: with no quantity given the app took the
        // SMALLEST listed portion, so "banana" logged one 6 g slice and "almonds"
        // logged a single 1.2 g nut. A food log quietly off by 20x is worthless.
        val banana = entryFor("banana")
        assertTrue("a banana should be roughly 100 kcal, got ${banana.calories}",
            banana.calories!! in 80.0..160.0)

        val egg = entryFor("egg")
        assertTrue("one egg should be roughly 70 kcal, got ${egg.calories}",
            egg.calories!! in 50.0..110.0)

        val milk = entryFor("milk")
        assertTrue("a glass of milk should be over 60 kcal, got ${milk.calories}",
            milk.calories!! > 60.0)
    }

    @Test
    fun aCountMultipliesTheServingNotTheHundredGramBasis() {
        // "2 eggs" must be two eggs (about 12 g protein), not 200 g of egg.
        val one = entryFor("egg")
        val two = entryFor("2 eggs")
        assertEquals(one.proteinGrams * 2, two.proteinGrams, 0.5)
        assertTrue("two eggs came to ${two.proteinGrams} g protein",
            two.proteinGrams in 9.0..16.0)
    }

    @Test
    fun aBareFoodNameFindsThePlainFoodNotACompoundDish() {
        // "egg" resolved to "Egg burrito" when the candidate shortlist truncated
        // on a tie, which also tripled the protein.
        assertTrue(entryFor("egg").name.startsWith("Egg,", ignoreCase = true))
    }

    @Test
    fun anUnknownFoodFailsHonestlyRatherThanGuessing() {
        val result = runBlocking { repo.processRawEntry("qqzzxwv nonsense") }
        assertTrue(result is LogEntryResult.Failed)
        assertEquals("OFFLINE_NO_MATCH", (result as LogEntryResult.Failed).code)
    }

    @Test
    fun aLoggedOfflineEntryPersistsWithAllItsNutrients() {
        val entry = entryFor("100g bacon")
        runBlocking { repo.confirm(entry) }

        // Read it back out of the database rather than trusting the object in
        // hand — the nutrients are written to a separate normalised table, and
        // that round trip is where a metric could silently be dropped.
        val saved = runBlocking { repo.observeEntries().first() }
        assertEquals(1, saved.size)
        assertEquals(entry.id, saved[0].id)
        assertEquals(entry.nutrients, saved[0].nutrients)
        assertTrue("expected a full nutrient set, got ${saved[0].nutrients.keys}",
            saved[0].nutrients.size >= 5)
        assertEquals(entry.foodDbId, saved[0].foodDbId)
    }

    // --- quality gaps found by comparing against the AI path ----------------

    @Test
    fun vagueInputIsKeptWithoutInventingNumbers() {
        // This used to answer "a solid breakfast" with "Breakfast bar, NFS" and a
        // full set of numbers, which the user would have had to notice was fiction.
        for (text in listOf("a solid breakfast", "leftovers", "the usual", "lunch")) {
            val entry = entryFor(text)
            assertTrue("$text was not flagged as needing detail", entry.needsDetail)
            assertTrue("$text invented nutrients", entry.nutrients.isEmpty())
            assertEquals(text, entry.rawText)
        }
    }

    @Test
    fun aMealWordNextToARealFoodStillLooksUp() {
        val entry = entryFor("breakfast burrito")
        assertFalse("a real food was mistaken for vague input", entry.needsDetail)
        assertTrue(entry.calories!! > 0)
    }

    @Test
    fun aMultiItemMealSumsItsParts() {
        // "chicken, rice and broccoli" matched "Rice, fried, with chicken" and
        // silently dropped the broccoli, under-reporting the meal.
        val combined = entryFor("chicken, rice and broccoli")
        val chicken = entryFor("chicken")
        val rice = entryFor("rice")
        val broccoli = entryFor("broccoli")

        val expected = chicken.calories!! + rice.calories!! + broccoli.calories!!
        assertEquals(expected, combined.calories!!, 1.0)
        assertTrue(
            "the combined protein should exceed any single item",
            combined.proteinGrams > maxOf(chicken.proteinGrams, rice.proteinGrams),
        )
    }

    @Test
    fun aPhraseContainingWithIsNotTornApart() {
        // Splitting on "with" would log only the sauce.
        val entry = entryFor("spaghetti with meat sauce")
        assertTrue(entry.calories!! > 0)
        assertFalse(entry.needsDetail)
    }

    @Test
    fun aStatedProteinFigureBeatsTheDatabaseEstimate() {
        // "Built bar, 16g protein" matched a different bar at 30 g. The user's own
        // number is better evidence than a lookup for a food we may not stock.
        val entry = entryFor("Built bar, 16g protein")
        assertEquals(16.0, entry.proteinGrams, 0.0001)
        assertEquals(Confidence.STATED, entry.confidence)
    }

    @Test
    fun drinksResolveToDrinksAndNotToBakedGoods() {
        // "diet coke" resolved to "Roll, diet"; "black coffee" to "Coffee, Cuban"
        // at 126 kcal and 23 g of sugar for something that is about 2 kcal.
        val coke = entryFor("diet coke")
        assertTrue("diet coke came to ${coke.calories} kcal", coke.calories!! < 20)

        val coffee = entryFor("black coffee")
        assertTrue("black coffee came to ${coffee.calories} kcal", coffee.calories!! < 30)
        assertTrue(coffee.amountOf(Metric.SUGAR) == null || coffee.amountOf(Metric.SUGAR)!! < 2.0)

        val water = entryFor("water")
        assertEquals(0.0, water.calories!!, 0.001)
    }
}
