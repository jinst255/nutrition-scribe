package com.nutritionscribe.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Review Hi5: a past day's page had no way to the day before or after; you went
 * back to History each time. It has ‹ › now, and › stops at today.
 */
class DayNavTest {
    private val today = LocalDate.of(2026, 11, 14)

    @Test
    fun `a past day steps to the next day up to today`() {
        assertEquals(LocalDate.of(2026, 11, 13), DayNav.next(LocalDate.of(2026, 11, 12), today))
        assertEquals(today, DayNav.next(LocalDate.of(2026, 11, 13), today))
    }

    @Test
    fun `there is no next day after today`() {
        assertNull(DayNav.next(today, today))
    }

    @Test
    fun `previous crosses months and years`() {
        assertEquals(LocalDate.of(2025, 12, 31), DayNav.previous(LocalDate.of(2026, 1, 1)))
    }
}
