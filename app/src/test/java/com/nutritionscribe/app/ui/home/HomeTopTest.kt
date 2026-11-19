package com.nutritionscribe.app.ui.home

import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Review H4: a fresh install opened on "0 days streak" and seven empty dots. Review
 * H1: the last thing logged was at the very bottom of Home. Home now hides the
 * streak until the first entry and says what was logged last at the top.
 */
class HomeTopTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 14)
    private fun at(day: LocalDate, h: Int, m: Int) = Entry(id = "1", timestampEpochMillis = day.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli(),
        rawText = "", name = "x", source = "local", confidence = Confidence.ESTIMATED)

    @Test
    fun `no streak before the first entry`() {
        assertFalse(HomeTop.showStreak(anyEntryEver = false))
        assertTrue(HomeTop.showStreak(anyEntryEver = true))
    }

    @Test
    fun `the last line names the food and the time`() {
        assertEquals("Last: Moms chili · 12:04 PM", HomeTop.lastLine(at(today, 12, 4), "Moms chili", zone, today))
    }

    @Test
    fun `yesterday and older say the day`() {
        assertEquals("Last: Oats · Yesterday 8:10 PM", HomeTop.lastLine(at(today.minusDays(1), 20, 10), "Oats", zone, today))
        assertEquals("Last: Oats · Mon, Nov 9 7:00 AM", HomeTop.lastLine(at(LocalDate.of(2026, 11, 9), 7, 0), "Oats", zone, today))
    }

    @Test
    fun `nothing logged means no line`() {
        assertNull(HomeTop.lastLine(null, "", zone, today))
    }

    /** On the emulator the line read "Last: grilled chicken breast": the AI's lower-case name. */
    @Test
    fun `a lower-case name starts with a capital`() {
        assertEquals("Last: Grilled chicken breast · 12:21 PM", HomeTop.lastLine(at(today, 12, 21), "grilled chicken breast", zone, today))
    }
}
