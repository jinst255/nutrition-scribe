package com.nutritionscribe.app.ui.confirm

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Which day the food being logged belongs to.
 *
 * Null [date] is the everyday case, now. From History's "+" on a past day it is
 * that day, and everything downstream — speech, typing, a scan, "log again", the
 * check card — logs onto it through the same flow as today rather than a
 * numbers-only form, so "what I had on Tuesday" never needs its protein known.
 *
 * A past day with no [time] stores noon and says so (timeUnspecified): a plausible
 * made-up time would quietly corrupt meal-timing analysis of the export.
 */
object LogDay {
    @Volatile var date: LocalDate? = null
    @Volatile var time: LocalTime? = null

    fun today() { date = null; time = null }

    /** A time for today's entry (from Log food's "Now ▾"); null is now. */
    fun setTodayTime(t: LocalTime?) { if (date == null) time = t }

    fun set(d: LocalDate?) {
        date = d?.takeIf { it != com.nutritionscribe.shared.model.LogDays.today() }
        time = null
    }

    val isPast: Boolean get() = date != null

    /** The timestamp to store, and whether it is a placeholder rather than a reported time. */
    fun stamp(zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Boolean> {
        // Today with a time picked on Log food ("Now ▾" set to 12:40): that time today.
        val d = date ?: time?.let { com.nutritionscribe.shared.model.LogDays.today() } ?: return System.currentTimeMillis() to false
        val t = time
        return if (t == null) d.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli() to true
        // 1 AM on a day that ends at 3 AM is the early hours after it, not before it.
        else (if (t.hour < com.nutritionscribe.shared.model.LogDays.endHour) d.plusDays(1) else d)
            .atTime(t).atZone(zone).toInstant().toEpochMilli() to false
    }

    /** "Tuesday, Sep 23" */
    fun label(): String? = date?.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
}
