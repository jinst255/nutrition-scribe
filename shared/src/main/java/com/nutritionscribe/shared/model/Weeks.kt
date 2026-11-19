package com.nutritionscribe.shared.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Where a week starts, from the phone's locale (review V4): Sunday in the US,
 * Monday in most of Europe. The strip on Home, the month grid and the streak's
 * rest-day weeks all use this, so a rest day belongs to the week you see.
 */
object Weeks {
    fun firstDay(locale: Locale = Locale.getDefault()): DayOfWeek = WeekFields.of(locale).firstDayOfWeek

    fun startOf(day: LocalDate, first: DayOfWeek): LocalDate = day.with(TemporalAdjusters.previousOrSame(first))

    /** The seven days of [day]'s week, in order. */
    fun daysOf(day: LocalDate, first: DayOfWeek): List<LocalDate> = startOf(day, first).let { s -> (0L..6L).map { s.plusDays(it) } }

    /** One letter a day, in week order: "SMTWTFS" in the US. */
    fun letters(first: DayOfWeek, locale: Locale = Locale.getDefault()): List<String> =
        (0L..6L).map { first.plus(it).getDisplayName(TextStyle.NARROW, locale) }

    /** Empty cells before the 1st in a month grid that starts on [first]. */
    fun lead(monthFirst: LocalDate, first: DayOfWeek): Int = (monthFirst.dayOfWeek.value - first.value + 7) % 7
}
