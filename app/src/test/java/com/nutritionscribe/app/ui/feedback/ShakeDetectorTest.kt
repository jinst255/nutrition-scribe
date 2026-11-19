package com.nutritionscribe.app.ui.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hold to report got in the way of the app's own presses (owner, 2026-11-14), so a
 * report now starts with a shake. These pin down what counts: three hard jolts in
 * a second does; lying still, one bump, a drop on the table or walking doesn't.
 */
class ShakeDetectorTest {
    private val g = ShakeDetector.GRAVITY.toFloat()

    /** Feeds samples every 20 ms: [jolts] are the times (ms) of 60 ms jolts at [strength] g. */
    private fun run(d: ShakeDetector, untilMs: Long, jolts: List<Long>, strength: Float = 3f): Int {
        var fired = 0
        var t = 0L
        while (t <= untilMs) {
            val inJolt = jolts.any { t in it until it + 60 }
            val z = if (inJolt) strength * g else g
            if (d.onSample(0f, 0f, z, t)) fired++
            t += 20
        }
        return fired
    }

    @Test
    fun `three hard jolts within a second is a shake`() {
        assertEquals(1, run(ShakeDetector(), 1500, listOf(100, 400, 700)))
    }

    @Test
    fun `a phone lying still never fires`() {
        assertEquals(0, run(ShakeDetector(), 10_000, emptyList()))
    }

    @Test
    fun `one bump or a drop on the table is not a shake`() {
        assertEquals(0, run(ShakeDetector(), 2000, listOf(500), strength = 6f))
    }

    @Test
    fun `walking, a jolt a step far apart, is not a shake`() {
        assertEquals(0, run(ShakeDetector(), 6000, (0L..5L).map { it * 1100 }))
    }

    @Test
    fun `gentle movement under the line is not a shake`() {
        assertEquals(0, run(ShakeDetector(), 1500, listOf(100, 300, 500, 700), strength = 1.8f))
    }

    @Test
    fun `one long jolt counts once, not three times`() {
        val d = ShakeDetector()
        var fired = false
        for (t in 0L..300L step 20) fired = fired || d.onSample(0f, 0f, 3f * g, t)
        assertFalse(fired)
    }

    @Test
    fun `after a shake, nothing more for a moment`() {
        // Two shakes 1.2 s apart: the second falls in the cooldown.
        assertEquals(1, run(ShakeDetector(), 3000, listOf(100, 400, 700, 1300, 1600, 1900)))
        // Well apart, both count.
        assertEquals(2, run(ShakeDetector(), 6000, listOf(100, 400, 700, 4000, 4300, 4600)))
    }

    @Test
    fun `shaking along any axis counts`() {
        val d = ShakeDetector()
        var fired = false
        for ((i, t) in listOf(100L, 400L, 700L).withIndex()) {
            d.onSample(0f, 0f, g, t - 20)
            fired = d.onSample(if (i % 2 == 0) 3f * g else -3f * g, 0f, 0f, t) || fired
        }
        assertTrue(fired)
    }

    /** The owner asked for shake to report on by default (2026-11-14); Settings turns it off. */
    @Test
    fun `shake to report is on by default`() {
        assertTrue(ShakeReport.DEFAULT_ON)
    }

    /** With shaking switched off, the Settings tip still said "shake your phone". */
    @Test
    fun `the tip follows the shake setting`() {
        assertTrue(ShakeReport.tip(true).startsWith("shake your phone"))
        assertFalse("shake your phone" in ShakeReport.tip(false))
        assertTrue("Tell us" in ShakeReport.tip(false))
    }

    /** On the emulator a report from Home read "On Saturday, Nov 14": Home's title is the date. */
    @Test
    fun `a report from Home says Home`() {
        assertEquals("Home (home)", ShakeReport.screenName("home", "Saturday, Nov 14"))
        assertEquals("My foods (my_foods)", ShakeReport.screenName("my_foods", "My foods"))
    }
}
