package com.nutritionscribe.shared.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review H5: reaching a protein or fibre target looked the same as being halfway;
 * Home gave no "done" signal. A reached goal now gets a check. Limits never do:
 * being under a limit at noon isn't an achievement, and over it isn't either.
 */
class LimitsMetTest {
    @Test
    fun `a reach goal at or past its target is met`() {
        assertTrue(Limits.met(160.0, 160.0, GoalDirection.REACH, isGoal = true))
        assertTrue(Limits.met(171.4, 160.0, GoalDirection.REACH, isGoal = true))
    }

    @Test
    fun `just under the target is not met`() {
        assertFalse(Limits.met(159.9, 160.0, GoalDirection.REACH, isGoal = true))
    }

    @Test
    fun `limits are never met`() {
        assertFalse(Limits.met(1500.0, 2200.0, GoalDirection.LIMIT, isGoal = true))
        assertFalse(Limits.met(2500.0, 2200.0, GoalDirection.LIMIT, isGoal = true))
    }

    @Test
    fun `only goals the person set get a check`() {
        // A micro shown against its reference amount isn't their goal.
        assertFalse(Limits.met(20.0, 8.0, GoalDirection.REACH, isGoal = false))
    }

    @Test
    fun `no target or a zero target is never met`() {
        assertFalse(Limits.met(0.0, 0.0, GoalDirection.REACH, isGoal = true))
        assertFalse(Limits.met(10.0, null, GoalDirection.REACH, isGoal = true))
    }
}
