package com.nutritionscribe.shared.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Review V4: Home's week strip and the streak month always started on Monday, so
 * in the US (where calendars start on Sunday) Sunday sat at the far end, and a
 * Sunday rest day counted toward the week before the one shown.
 */
class WeeksTest {
    private val sat = LocalDate.of(2026, 11, 14)

    @Test
    fun `the week starts on Sunday in the US and Monday in Germany`() {
        assertEquals(DayOfWeek.SUNDAY, Weeks.firstDay(Locale.US))
        assertEquals(DayOfWeek.MONDAY, Weeks.firstDay(Locale.GERMANY))
    }

    @Test
    fun `the strip shows the US week Sunday to Saturday`() {
        val days = Weeks.daysOf(sat, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 8), days.first())
        assertEquals(sat, days.last())
        assertEquals(listOf("S", "M", "T", "W", "T", "F", "S"), Weeks.letters(DayOfWeek.SUNDAY, Locale.US))
    }

    @Test
    fun `a Monday week still reads M to S`() {
        assertEquals(LocalDate.of(2026, 11, 9), Weeks.daysOf(sat, DayOfWeek.MONDAY).first())
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), Weeks.letters(DayOfWeek.MONDAY, Locale.UK))
    }

    @Test
    fun `on the first day of the week the week starts that day`() {
        val sun = LocalDate.of(2026, 11, 15)
        assertEquals(sun, Weeks.startOf(sun, DayOfWeek.SUNDAY))
    }

    @Test
    fun `month grid lead cells follow the week start`() {
        val nov1 = LocalDate.of(2026, 11, 1) // a Sunday
        assertEquals(0, Weeks.lead(nov1, DayOfWeek.SUNDAY))
        assertEquals(6, Weeks.lead(nov1, DayOfWeek.MONDAY))
    }

    @Test
    fun `a Sunday rest day belongs to the US week it is shown in`() {
        // Logged Mon 9 to Sat 14, missed Sun 8 and Sun 15. One rest day a week.
        val counted = (9..14).map { LocalDate.of(2026, 11, it) }.toSet() + LocalDate.of(2026, 11, 7)
        val today = LocalDate.of(2026, 11, 16)
        val us = Streak.compute(counted + today, today, restPerWeek = 1, weekStart = DayOfWeek.SUNDAY)
        // Sun 8 is a rest in the week of Nov 8; Sun 15 is a rest in the week of Nov 15. The run holds.
        assertEquals(Streak.DayState.REST, us.state(LocalDate.of(2026, 11, 8)))
        assertEquals(Streak.DayState.REST, us.state(LocalDate.of(2026, 11, 15)))
        // A Monday week puts Sun 8 and Sun 15 in different weeks too, but Sat 7's week (Nov 2) gets Sun 8.
        val eu = Streak.compute(counted + today, today, restPerWeek = 1)
        assertEquals(Streak.DayState.REST, eu.state(LocalDate.of(2026, 11, 8)))
    }
}
