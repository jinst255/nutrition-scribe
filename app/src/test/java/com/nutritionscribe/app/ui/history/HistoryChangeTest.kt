package com.nutritionscribe.app.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * History round 2: each card says how its daily average compares with the same
 * number of days before ("vs the 2 weeks before", owner 11-19: keep it). Only full
 * days count, so today and a breakfast-only day don't skew either side.
 */
class HistoryChangeTest {
    private val today = LocalDate.of(2026, 11, 20)
    private val t = today.toEpochDay()

    @Test
    fun `down four percent against the days before`() {
        val daily = (0 until 28).map { DayValue(t - it, if (it < 14) 96.0 else 100.0) }
        val full = daily.map { it.epochDay }.filter { it < t }.toSet()
        val pct = MetricHistory.change(daily, full, 14, today)!!
        assertEquals(-4.0, pct, 1e-9)
        assertEquals("▼ 4%", MetricHistory.changeText(pct))
        assertEquals("▲ 6%", MetricHistory.changeText(6.2))
        assertEquals("same", MetricHistory.changeText(0.3))
    }

    @Test
    fun `nothing to compare with`() {
        val recent = (0 until 14).map { DayValue(t - it, 90.0) }
        assertNull(MetricHistory.change(recent, recent.map { it.epochDay }.toSet(), 14, today))
        val zeroBefore = recent + (14 until 28).map { DayValue(t - it, 0.0) }
        assertNull(MetricHistory.change(zeroBefore, zeroBefore.map { it.epochDay }.toSet(), 14, today))
    }

    @Test
    fun `only full days count on either side`() {
        val daily = (0 until 28).map { DayValue(t - it, if (it == 5) 10.0 else if (it < 14) 110.0 else 100.0) }
        val full = daily.map { it.epochDay }.filter { it < t && it != t - 5 }.toSet()
        assertEquals(10.0, MetricHistory.change(daily, full, 14, today)!!, 1e-9)
    }
}
