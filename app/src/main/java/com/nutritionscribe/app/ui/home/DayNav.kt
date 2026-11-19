package com.nutritionscribe.app.ui.home

import java.time.LocalDate

/**
 * Moving between days on a day's page (review Hi5). A past day opened from
 * History was a dead end: no way to add the snack you forgot, and back to
 * History to see the next day. It gets an Add button and ‹ › arrows; there is
 * no "next" past today.
 */
object DayNav {
    fun previous(day: LocalDate): LocalDate = day.minusDays(1)

    fun next(day: LocalDate, today: LocalDate): LocalDate? = day.plusDays(1).takeIf { !it.isAfter(today) }
}
