package com.nutritionscribe.shared.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricGoalTest {

    @Test
    fun `a reach goal is met by hitting or exceeding it`() {
        val protein = MetricGoal(Metric.PROTEIN, enabled = true, target = 160.0)
        assertFalse(protein.isMet(159.9))
        assertTrue(protein.isMet(160.0))
        assertTrue(protein.isMet(200.0))
    }

    @Test
    fun `a limit goal is met by staying under it`() {
        // The failure this guards: treating every metric as "more is better", so a
        // 3,000 mg sodium day renders as a full ring and reads as a success.
        val sodium = MetricGoal(Metric.SODIUM, enabled = true, target = 2300.0)
        assertTrue(sodium.isMet(1800.0))
        assertTrue(sodium.isMet(2300.0))
        assertFalse(sodium.isMet(2301.0))
        assertFalse(sodium.isMet(3000.0))
    }

    @Test
    fun `every limit metric really is a limit`() {
        // Sugar, saturated fat, sodium and caffeine are the ones where over is bad.
        for (metric in listOf(Metric.SUGAR, Metric.SATURATED_FAT, Metric.SODIUM, Metric.CAFFEINE)) {
            assertEquals("${metric.key} should be a LIMIT", GoalDirection.LIMIT, metric.direction)
            val goal = MetricGoal.default(metric)
            assertFalse("${metric.key} counted double its target as met", goal.isMet(metric.defaultTarget * 2))
        }
    }

    @Test
    fun `a metric with no target set is never a failure`() {
        // "I want to watch my sodium" without a number is a complete thought.
        // Showing it as failed would invent a target the user never chose.
        val watching = MetricGoal(Metric.SODIUM, enabled = true, target = null)
        assertTrue(watching.isMet(99999.0))
        assertEquals(0.0, watching.progress(500.0), 0.0001)
    }

    @Test
    fun `progress can exceed one so an overshoot is visible`() {
        val protein = MetricGoal(Metric.PROTEIN, enabled = true, target = 100.0)
        assertEquals(0.5, protein.progress(50.0), 0.0001)
        assertEquals(1.0, protein.progress(100.0), 0.0001)
        // Clamping here would hide a 2,000 mg sodium overshoot entirely.
        assertEquals(1.5, protein.progress(150.0), 0.0001)
    }

    @Test
    fun `a zero or negative target cannot divide by zero`() {
        val silly = MetricGoal(Metric.PROTEIN, enabled = true, target = 0.0)
        assertEquals(0.0, silly.progress(50.0), 0.0001)
    }

    @Test
    fun `no metric starts with a red line the user did not set`() {
        // Red lines are the user's alone: the app cannot know how much sugar is
        // too much for this person, so it never draws one for them.
        for (m in Metric.entries) {
            assertNull(m.key, MetricGoal.default(m).ceilingValue)
            assertNull(m.key, MetricGoal.default(m).floorValue)
        }
    }

    @Test
    fun `defaults are ordered the way the metrics are declared`() {
        val order = Metric.entries.map { MetricGoal.default(it).sortOrder }
        assertEquals(order.sorted(), order)
        assertEquals(Metric.entries.size, order.distinct().size)
    }

    @Test
    fun `every metric can be defaulted and carries a usable target`() {
        for (metric in Metric.entries) {
            val goal = MetricGoal.default(metric)
            assertNotNull("${metric.key} has no default target", goal.target)
            assertTrue("${metric.key} default target should be positive", goal.target!! > 0)
            assertFalse("nothing should be enabled unless asked for", goal.enabled)
        }
    }
}
