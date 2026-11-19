package com.nutritionscribe.app.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review H2: the "Add your height, weight and activity" card on Home had no close
 * button, so anyone who skipped the body page saw it on every open, forever. It
 * now closes, comes back once a week later, and after a second close stays gone.
 */
class ProfileNudgeTest {
    private val day = 86_400_000L
    private val now = 100 * day

    @Test
    fun `the profile nudge shows until it is first closed`() {
        assertTrue(ProfileNudge.due(hasProfile = false, closes = 0, lastClosedAt = 0, now = now))
    }

    @Test
    fun `once closed it stays away for a week, then asks once more`() {
        assertFalse(ProfileNudge.due(false, closes = 1, lastClosedAt = now - day, now = now))
        assertFalse(ProfileNudge.due(false, closes = 1, lastClosedAt = now - 7 * day + 1, now = now))
        assertTrue(ProfileNudge.due(false, closes = 1, lastClosedAt = now - 7 * day, now = now))
    }

    @Test
    fun `after a second close it never asks again`() {
        assertFalse(ProfileNudge.due(false, closes = 2, lastClosedAt = now - 365 * day, now = now))
        assertFalse(ProfileNudge.due(false, closes = 9, lastClosedAt = 0, now = now))
    }

    @Test
    fun `nobody with a profile is nudged`() {
        assertFalse(ProfileNudge.due(hasProfile = true, closes = 0, lastClosedAt = 0, now = now))
    }

    @Test
    fun `a clock set back does not bring it back early`() {
        // Closed "in the future" (the phone's clock moved back): no ask until a week after.
        assertFalse(ProfileNudge.due(false, closes = 1, lastClosedAt = now + day, now = now))
    }
}
