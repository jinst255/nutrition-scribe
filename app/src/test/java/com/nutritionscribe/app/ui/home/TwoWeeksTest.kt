package com.nutritionscribe.app.ui.home

import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Home round 2: a goal's sheet shows its last 14 days. The average is only over
 * full days before today, as History counts them, so a half-eaten today or a day
 * with just a breakfast doesn't drag it down.
 */
class TwoWeeksTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 20)
    private var n = 0
    private fun e(daysAgo: Long, protein: Double, kcal: Double) = Entry(
        id = "e${++n}", timestampEpochMillis = today.minusDays(daysAgo).atTime(12, 0).toInstant(zone).toEpochMilli(),
        rawText = "x", name = "x", source = "t", confidence = Confidence.STATED,
        nutrients = mapOf("protein" to protein, "calories" to kcal),
    )

    @Test
    fun `fourteen days ending today, averaged over full days only`() {
        val log = listOf(e(0, 20.0, 400.0), e(1, 100.0, 2000.0), e(2, 80.0, 1800.0), e(3, 5.0, 200.0), e(20, 300.0, 3000.0))
        val d = TwoWeeks.of(log, Metric.PROTEIN, 2000.0, zone, today)
        assertEquals(14, d.days.size)
        assertEquals(today.toEpochDay(), d.days.last().epochDay)
        assertEquals(today.toEpochDay(), d.todayDay)
        assertEquals(90.0, d.average!!, 1e-9)   // today and the 200 kcal day are left out; day 20 is outside
    }

    @Test
    fun `nothing logged yet`() {
        val d = TwoWeeks.of(emptyList(), Metric.PROTEIN, 2000.0, zone, today)
        assertEquals(14, d.days.size)
        assertNull(d.average)
    }
}
