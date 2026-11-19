package com.nutritionscribe.shared.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review Hi2: calories were a goal to "reach" for everyone. On the emulator a
 * 2,288 / 2,200 kcal day got Home's "reached" check, and History marked days
 * under the target "too low". Calories now default to stay-under; only a saved
 * aim of gaining weight turns them into something to reach.
 */
class CalorieAimTest {
    private val calories = MetricGoal(Metric.CALORIES, enabled = true, target = 2200.0)

    @Test
    fun `calories default to stay under`() {
        assertEquals(GoalDirection.LIMIT, calories.effectiveDirection)
        assertFalse(Limits.met(2288.0, 2200.0, calories.effectiveDirection, isGoal = true))
        assertEquals(Limits.State.OVER, Limits.judge(2288.0, calories, dayOver = true))
        // A day under is fine, not "too low".
        assertEquals(Limits.State.IN_RANGE, Limits.judge(1600.0, calories, dayOver = true))
    }

    @Test
    fun `gaining weight makes calories a goal to reach`() {
        val g = CalorieAim.adjust(calories, TargetCalculator.Aim.GAIN)
        assertEquals(GoalDirection.REACH, g.effectiveDirection)
        assertTrue(Limits.met(2288.0, 2200.0, g.effectiveDirection, isGoal = true))
    }

    @Test
    fun `no longer gaining goes back to stay under`() {
        val gaining = calories.copy(direction = GoalDirection.REACH)
        assertNull(CalorieAim.adjust(gaining, TargetCalculator.Aim.LOSE).direction)
        assertNull(CalorieAim.adjust(gaining, TargetCalculator.Aim.MAINTAIN).direction)
    }

    @Test
    fun `a limit someone chose is kept whatever the aim`() {
        val cutting = calories.copy(direction = GoalDirection.LIMIT)
        assertEquals(GoalDirection.LIMIT, CalorieAim.adjust(cutting, TargetCalculator.Aim.GAIN).direction)
    }

    @Test
    fun `other metrics are never touched`() {
        val protein = MetricGoal(Metric.PROTEIN, enabled = true, target = 160.0)
        assertEquals(protein, CalorieAim.adjust(protein, TargetCalculator.Aim.GAIN))
    }
}
