package com.nutritionscribe.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nutritionscribe.app.data.db.EntryNutrientRow
import com.nutritionscribe.app.data.db.EntryRow
import com.nutritionscribe.app.data.db.FixedFoodCacheRow
import com.nutritionscribe.app.data.db.MetricGoalRow
import com.nutritionscribe.app.data.db.NutritionScribeDatabase
import com.nutritionscribe.app.data.db.UnprocessedRow
import com.nutritionscribe.shared.model.Metric
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A reset has to erase exactly what was asked for and nothing else.
 *
 * The risk here is not that it fails to delete — that is obvious the moment you
 * try it. The risk is that it deletes something the user chose to KEEP, which is
 * unrecoverable and which they will only notice later.
 *
 * It runs against its own settings files, not the app's. An earlier version used
 * the production store, which meant running the test suite on a phone someone was
 * actually using would erase their settings and their API key — a landmine to
 * leave in a repo you intend to give away.
 */
@RunWith(AndroidJUnit4::class)
class ResetManagerTest {

    private lateinit var db: NutritionScribeDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var reset: ResetManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NutritionScribeDatabase::class.java)
            // Foreign keys are what make entry_nutrient go with its entry.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()
        settings = SettingsRepository(
            context,
            SettingsStorage(
                preferencesName = "reset_manager_test_settings",
                secureName = "reset_manager_test_secure",
            ),
        )
        reset = ResetManager(
            entryDao = db.entryDao(),
            unprocessedDao = db.unprocessedDao(),
            fixedFoodCacheDao = db.fixedFoodCacheDao(),
            metricGoalDao = db.metricGoalDao(),
            settingsRepository = settings,
        )
    }

    @After
    fun tearDown() {
        db.close()
        runBlocking { settings.clearAllPreferences() }
        settings.clearApiKeys()
    }

    private fun seed() = runBlocking {
        db.entryDao().upsert(
            EntryRow(
                id = "e1",
                timestampEpochMillis = 1_700_000_000_000,
                rawText = "2 eggs",
                name = "Egg, whole, raw",
                isFixedFood = false,
                source = "usda",
                confidence = "ESTIMATED",
            ),
            listOf(
                EntryNutrientRow("e1", Metric.PROTEIN.key, 12.4),
                EntryNutrientRow("e1", Metric.CALORIES.key, 143.0),
            ),
        )
        db.unprocessedDao().insert(
            UnprocessedRow(
                id = "u1",
                timestampEpochMillis = 1_700_000_000_000,
                rawText = "something",
                errorCode = "NO_NETWORK",
            ),
        )
        db.fixedFoodCacheDao().upsert(
            FixedFoodCacheRow(
                normalizedName = "built bar",
                name = "Built Bar",
                proteinGrams = 17.0,
                calories = 130.0,
                carbsGrams = null,
                fatGrams = null,
                source = "packet",
            ),
        )
        db.metricGoalDao().upsert(
            MetricGoalRow(
                metric = Metric.PROTEIN.key,
                enabled = true,
                target = 160.0,
                floorValue = null,
                ceilingValue = null,
                sortOrder = 0,
            ),
        )
        settings.setApiKey(AiBackendKind.OPENAI, "sk-test-not-a-real-key")
        settings.setOnboarded(true)
    }

    private fun entryNutrientCount(): Int = runBlocking {
        db.query("SELECT COUNT(*) FROM entry_nutrient", emptyArray()).use {
            it.moveToFirst(); it.getInt(0)
        }
    }

    @Test
    fun erasingTheFoodLogLeavesGoalsAndKeyAlone() = runBlocking {
        seed()
        reset.reset(ResetManager.Scope(foodLog = true, settings = false, apiKey = false))

        assertEquals(0, db.entryDao().count())
        // entry_nutrient is ON DELETE CASCADE, but that only holds while foreign
        // keys are actually enforced — so check the rows, not the declaration.
        assertEquals("nutrient rows outlived their entries", 0, entryNutrientCount())
        assertNull(db.unprocessedDao().observeAll().first().firstOrNull())
        assertNull(db.fixedFoodCacheDao().find("built bar"))

        assertNotNull("goals were kept", db.metricGoalDao().find(Metric.PROTEIN.key))
        assertTrue("the key was kept", settings.hasAnyApiKey())
        assertTrue("onboarding was kept", settings.isOnboarded.first())
    }

    @Test
    fun erasingSettingsLeavesTheFoodLogAlone() = runBlocking {
        seed()
        reset.reset(ResetManager.Scope(foodLog = false, settings = true, apiKey = false))

        assertEquals("the log was kept", 1, db.entryDao().count())
        assertEquals("its numbers were kept", 2, entryNutrientCount())
        assertTrue("the key was kept", settings.hasAnyApiKey())

        assertNull(db.metricGoalDao().find(Metric.PROTEIN.key))
        // Clearing preferences puts onboarding back, which is what sends the user
        // through goal-picking again rather than into an app with no goals.
        assertFalse(settings.isOnboarded.first())
    }

    @Test
    fun erasingTheKeyLeavesEverythingElseAlone() = runBlocking {
        seed()
        reset.reset(ResetManager.Scope(foodLog = false, settings = false, apiKey = true))

        assertFalse(settings.hasAnyApiKey())
        assertEquals(1, db.entryDao().count())
        assertNotNull(db.metricGoalDao().find(Metric.PROTEIN.key))
        assertTrue(settings.isOnboarded.first())
    }

    @Test
    fun erasingEverythingLeavesNothing() = runBlocking {
        seed()
        reset.reset(ResetManager.Scope(foodLog = true, settings = true, apiKey = true))

        assertEquals(0, db.entryDao().count())
        assertEquals(0, entryNutrientCount())
        assertNull(db.metricGoalDao().find(Metric.PROTEIN.key))
        assertFalse(settings.hasAnyApiKey())
        assertFalse(settings.isOnboarded.first())
    }

    @Test
    fun keepingEverythingErasesNothing() = runBlocking {
        seed()
        val scope = ResetManager.Scope(foodLog = false, settings = false, apiKey = false)
        assertFalse("the dialog must not offer to do nothing", scope.erasesAnything)

        reset.reset(scope)
        assertEquals(1, db.entryDao().count())
        assertNotNull(db.metricGoalDao().find(Metric.PROTEIN.key))
        assertTrue(settings.hasAnyApiKey())
    }

    @Test
    fun theCountShownInTheDialogIsTheRealOne() = runBlocking {
        seed()
        assertEquals(1, reset.loggedEntryCount())
        assertTrue(reset.hasApiKey())
    }
}
