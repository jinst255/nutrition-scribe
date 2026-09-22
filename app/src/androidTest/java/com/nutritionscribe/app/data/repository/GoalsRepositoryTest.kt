package com.nutritionscribe.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nutritionscribe.app.data.db.NutritionScribeDatabase
import com.nutritionscribe.shared.model.Metric
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoalsRepositoryTest {

    private lateinit var db: NutritionScribeDatabase
    private lateinit var repo: GoalsRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NutritionScribeDatabase::class.java).build()
        repo = GoalsRepository(db.metricGoalDao(), SettingsRepository(context))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun seedingCreatesARowForEveryMetric() = runBlocking {
        repo.ensureSeeded(null, null)
        val goals = repo.goals.first()
        assertEquals(Metric.entries.size, goals.size)
        assertEquals(Metric.entries.toSet(), goals.map { it.metric }.toSet())
    }

    @Test
    fun seedingCarriesTheOldProteinGoalAndBaselineAcross() = runBlocking {
        // The whole reason this runs in app code rather than in the Room migration:
        // these two numbers live in DataStore, which SQL cannot read. Losing them
        // would silently reset a goal someone had been using for months.
        repo.ensureSeeded(legacyGoalGrams = 185.0, legacyBaselineGrams = 120.0)
        val protein = repo.find(Metric.PROTEIN)!!
        assertEquals(185.0, protein.target!!, 0.0001)
        assertEquals(120.0, protein.floorValue!!, 0.0001)
        assertTrue(protein.enabled)
    }

    @Test
    fun seedingIsNotDestructiveOnASecondRun() = runBlocking {
        repo.ensureSeeded(185.0, 120.0)
        repo.setTarget(Metric.PROTEIN, 200.0)
        repo.setEnabled(Metric.SODIUM, true)

        // Runs on every launch, so it must never overwrite what the user has set.
        repo.ensureSeeded(185.0, 120.0)

        assertEquals(200.0, repo.find(Metric.PROTEIN)!!.target!!, 0.0001)
        assertTrue(repo.find(Metric.SODIUM)!!.enabled)
    }

    @Test
    fun aFreshUserStartsWithProteinAndCaloriesOnly() = runBlocking {
        repo.ensureSeeded(null, null)
        val enabled = repo.enabledGoals.first().map { it.metric }.toSet()
        assertEquals(setOf(Metric.PROTEIN, Metric.CALORIES), enabled)
    }

    @Test
    fun turningAMetricOffAndBackOnRemembersItsNumber() = runBlocking {
        repo.ensureSeeded(null, null)
        repo.setEnabled(Metric.SUGAR, true)
        repo.setTarget(Metric.SUGAR, 40.0)
        repo.setEnabled(Metric.SUGAR, false)
        repo.setEnabled(Metric.SUGAR, true)
        // Forgetting it would make the checkbox quietly destructive.
        assertEquals(40.0, repo.find(Metric.SUGAR)!!.target!!, 0.0001)
    }

    @Test
    fun anEmptySelectionIsRefused() = runBlocking {
        repo.ensureSeeded(null, null)
        assertFalse("an app tracking nothing has nothing to show", repo.applySelection(emptySet()))
        // And the previous selection must survive the refusal.
        assertTrue(repo.enabledGoals.first().isNotEmpty())
    }

    @Test
    fun applyingASelectionEnablesExactlyThoseMetrics() = runBlocking {
        repo.ensureSeeded(null, null)
        assertTrue(repo.applySelection(setOf(Metric.SUGAR, Metric.SODIUM)))
        assertEquals(
            setOf(Metric.SUGAR, Metric.SODIUM),
            repo.enabledGoals.first().map { it.metric }.toSet(),
        )
    }

    @Test
    fun proteinOnlyIsAllowedBecauseTheNudgeIsNotABlock() = runBlocking {
        repo.ensureSeeded(null, null)
        assertTrue(repo.applySelection(setOf(Metric.PROTEIN)))
        assertEquals(listOf(Metric.PROTEIN), repo.enabledGoals.first().map { it.metric })
    }

    @Test
    fun aLimitMetricKeepsItsCeilingInStepWithItsTarget() = runBlocking {
        repo.ensureSeeded(null, null)
        repo.setTarget(Metric.SODIUM, 1500.0)
        val sodium = repo.find(Metric.SODIUM)!!
        // A stale ceiling would colour the chart against a number the user replaced.
        assertEquals(1500.0, sodium.ceilingValue!!, 0.0001)
    }

    @Test
    fun aTargetCanBeClearedWithoutDisablingTheMetric() = runBlocking {
        repo.ensureSeeded(null, null)
        repo.setTarget(Metric.PROTEIN, null)
        val protein = repo.find(Metric.PROTEIN)!!
        assertNull(protein.target)
        assertTrue("clearing a number must not stop tracking it", protein.enabled)
    }

    @Test
    fun editingTheProteinTargetReachesTheScreensThatStillReadDataStore() = runBlocking {
        // The home ring, History chart, widget and watch read dailyGoalGrams, not
        // metric_goal. Without this mirror, changing the protein target would update
        // the Goals tab and nothing else, and Settings no longer offers the old field
        // — so there would be no way left to change the number those screens use.
        val settings = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        repo.ensureSeeded(null, null)

        repo.setTarget(Metric.PROTEIN, 190.0)
        assertEquals(190.0, settings.dailyGoalGrams.first(), 0.0001)

        repo.setFloor(Metric.PROTEIN, 130.0)
        assertEquals(130.0, settings.dailyBaselineGrams.first(), 0.0001)
    }

    @Test
    fun editingAnotherMetricDoesNotTouchTheProteinGoal() = runBlocking {
        val settings = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        repo.ensureSeeded(null, null)
        repo.setTarget(Metric.PROTEIN, 175.0)

        repo.setTarget(Metric.SODIUM, 1500.0)

        assertEquals("sodium leaked into the protein goal", 175.0, settings.dailyGoalGrams.first(), 0.0001)
    }

    @Test
    fun goalsComeBackInAStableOrder() = runBlocking {
        repo.ensureSeeded(null, null)
        val order = repo.goals.first().map { it.sortOrder }
        assertEquals(order.sorted(), order)
    }
}
