package com.nutritionscribe.app.data.export

import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.GoalDirection
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import com.nutritionscribe.shared.model.Profile
import com.nutritionscribe.shared.model.TargetCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BackupJsonTest {

    private val entry = Entry(id = "e1", timestampEpochMillis = 1_000, rawText = "toast", name = "Toast",
        nutrients = mapOf("calories" to 80.0), source = "usda", confidence = Confidence.ESTIMATED)
    private val goals = listOf(
        MetricGoal(Metric.PROTEIN, enabled = true, target = 120.0, floorValue = 90.0),
        MetricGoal(Metric.SODIUM, enabled = true, target = 2000.0, direction = GoalDirection.LIMIT, ceilingValue = 2300.0, sortOrder = 3),
    )
    private val profile = Profile(TargetCalculator.Sex.FEMALE, 40, 165.0, 60.0, TargetCalculator.Activity.LIGHT, TargetCalculator.Aim.MAINTAIN)

    /**
     * Review S2 (2026-11-04): a backup held the log and My foods but not the goals,
     * About you or plasma days, so restoring onto a new phone lost every target the
     * person had set. They go in and come back out. The API key never does.
     */
    @Test
    fun `a backup brings back goals, about you and plasma days`() {
        val text = BackupJson.encode(listOf(entry), emptyList(),
            BackupJson.Extras.of(goals, profile, setOf(LocalDate.of(2026, 11, 2))))
        val extras = BackupJson.extrasOf(text)!!
        assertEquals(goals, extras.goals())
        assertEquals(profile, extras.profile)
        assertEquals(setOf(LocalDate.of(2026, 11, 2)), extras.plasmaDays())
        assertEquals(listOf(entry), BackupJson.entriesOf(text))
        assertFalse(text.contains("sk-"))
    }

    @Test
    fun `older backups still read, with nothing extra to restore`() {
        val plain = BackupJson.encode(listOf(entry), emptyList(), null)
        assertEquals(listOf(entry), BackupJson.entriesOf(plain))
        assertNull(BackupJson.extrasOf(plain))
    }
}
