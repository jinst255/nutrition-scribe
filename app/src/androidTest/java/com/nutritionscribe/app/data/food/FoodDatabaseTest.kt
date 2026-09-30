package com.nutritionscribe.app.data.food

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nutritionscribe.shared.model.Metric
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against the REAL bundled food.db, because the thing most worth testing is
 * the asset itself: that it shipped, that it opens, and that the foods people log
 * every day resolve to the form they actually ate.
 */
@RunWith(AndroidJUnit4::class)
class FoodDatabaseTest {

    private lateinit var foods: FoodDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.filesDir.listFiles()?.filter { it.name.startsWith("food-v") }?.forEach { it.delete() }
        foods = FoodDatabase(context)
    }

    @After
    fun tearDown() = foods.close()

    @Test
    fun theBundledDatabaseUnpacksAndAnswers() = runBlocking {
        val match = foods.lookup("banana")
        assertNotNull("the food database did not ship or did not open", match)
        assertTrue(match!!.description.startsWith("Banana", ignoreCase = true))
    }

    @Test
    fun staplesResolveToTheCookedFormNotTheRawOne() = runBlocking {
        // The failure this guards is not cosmetic. Raw white rice is ~360 kcal/100g
        // against ~129 cooked, so defaulting to raw overstates a rice portion
        // roughly threefold, silently, forever.
        val rice = foods.lookup("white rice")!!
        assertTrue("got ${rice.description}", rice.description.contains("cooked", ignoreCase = true))
        assertTrue("raw rice calories leaked through", rice.nutrients[Metric.CALORIES.key]!! < 200)

        val beef = foods.lookup("ground beef")!!
        assertTrue("got ${beef.description}", beef.description.contains("cooked", ignoreCase = true))
    }

    @Test
    fun curatedFoodsResolveToTheirIntendedSense() = runBlocking {
        val expected = mapOf(
            "chicken breast" to "Chicken breast, baked, broiled, or roasted",
            "cheddar cheese" to "Cheese, Cheddar",
            "bacon" to "Pork, cured, bacon, cooked",
            "french fries" to "Potato, french fries",
            "soda" to "Soft drink",
            "coffee" to "Coffee, brewed",
        )
        for ((query, prefix) in expected) {
            val got = foods.lookup(query)?.description ?: "(no match)"
            assertTrue("$query -> $got, expected $prefix", got.startsWith(prefix, ignoreCase = true))
        }
    }

    @Test
    fun everyTrackedMetricHasDataBehindIt() = runBlocking {
        // Sodium, saturated fat and caffeine were missing from the first build of
        // this database, which meant three metrics the Goals picker offers had
        // nothing behind them. The picker must not be able to outrun the data.
        val salty = foods.lookup("bacon")!!
        assertNotNull("no sodium data", salty.nutrients[Metric.SODIUM.key])
        assertNotNull("no saturated fat data", salty.nutrients[Metric.SATURATED_FAT.key])

        val coffee = foods.lookup("coffee")!!
        assertNotNull("no caffeine data", coffee.nutrients[Metric.CAFFEINE.key])
        assertTrue("brewed coffee should carry caffeine", coffee.nutrients[Metric.CAFFEINE.key]!! > 0)
    }

    @Test
    fun anUnknownNutrientIsAbsentRatherThanZero() = runBlocking {
        // "USDA never measured this" and "this food contains none" are different
        // facts, and collapsing them invents data the log then reports as true.
        //
        // Tempeh is a real gap in the source data: no caffeine, fiber or sugar
        // figure. A well-covered food would not test anything, since most FNDDS
        // rows carry all nine values.
        val tempeh = foods.search("tempeh", limit = 5)
            .firstOrNull { it.description.equals("Tempeh", ignoreCase = true) }
        assertNotNull("expected the USDA Tempeh row to be findable", tempeh)
        assertNull("an unmeasured nutrient must not read as 0.0", tempeh!!.nutrients[Metric.CAFFEINE.key])
        assertNull(tempeh.nutrients[Metric.SUGAR.key])
        // ...while the nutrients it DOES have still come through.
        assertNotNull(tempeh.nutrients[Metric.PROTEIN.key])
    }

    @Test
    fun portionsComeBackForCommonFoods() = runBlocking {
        val egg = foods.lookup("egg")!!
        assertTrue("no household portions for an egg", egg.portions.isNotEmpty())
        assertTrue(egg.portions.all { it.grams > 0 })
    }

    @Test
    fun nutrientsScaleToTheServingSize() = runBlocking {
        val rice = foods.lookup("white rice")!!
        val per100 = rice.nutrients[Metric.CALORIES.key]!!
        val for200 = rice.nutrientsFor(200.0)[Metric.CALORIES.key]!!
        assertEquals(per100 * 2, for200, 0.0001)
    }

    @Test
    fun aQuantityPhraseStillFindsTheFood() = runBlocking {
        assertTrue(foods.lookup("8oz chicken breast")!!.description.contains("Chicken", true))
        assertTrue(foods.lookup("2 cups of white rice")!!.description.contains("Rice", true))
    }

    @Test
    fun gibberishReturnsNothingInsteadOfAWrongAnswer() = runBlocking {
        // Offering a confident match for input it cannot parse is worse than
        // offering none — the user would have to notice it was wrong.
        assertTrue(foods.search("qqqzzxwv").isEmpty())
        assertTrue(foods.search("").isEmpty())
        assertNull(foods.lookup("   "))
    }

    @Test
    fun lookupIsFastEnoughToTypeAgainst() = runBlocking {
        // The benchmark in tools/ runs desktop Python; this is the number that
        // actually matters. Generous bound because an emulator on a loaded machine
        // is slower than a real phone — it is here to catch a collapse (a missing
        // index, a full table scan), not to police milliseconds.
        val queries = listOf(
            "chicken breast", "white rice", "greek yogurt", "banana",
            "peanut butter", "black beans", "cheddar cheese", "oatmeal",
        )
        queries.forEach { foods.lookup(it) }  // warm

        val timings = mutableListOf<Long>()
        repeat(5) {
            for (q in queries) {
                val start = System.nanoTime()
                foods.lookup(q)
                timings.add((System.nanoTime() - start) / 1_000_000)
            }
        }
        timings.sort()
        val p50 = timings[timings.size / 2]
        val p95 = timings[(timings.size * 95) / 100]
        println("FOODSEARCH on-device p50=${p50}ms p95=${p95}ms n=${timings.size}")
        assertTrue("food lookup p95 was ${p95}ms, which would be felt while typing", p95 < 250)
    }

    @Test
    fun searchNeverReturnsTheSameFoodTwice() = runBlocking {
        // The curated alias is prepended to the ranked list, so without
        // de-duplication a curated food would appear twice in the pick-list.
        val results = foods.search("white rice", limit = 5)
        assertEquals(results.map { it.fdcId }.distinct().size, results.size)
    }
}
