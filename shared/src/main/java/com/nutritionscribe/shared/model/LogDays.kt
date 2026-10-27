package com.nutritionscribe.shared.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Which day a moment belongs to. A day ends at [endHour] (3 AM unless changed in
 * Goals › Streak), so a snack at 12:30 AM still counts for the evening before it.
 * At midnight a late dinner used to start the next day's numbers and its streak.
 *
 * Kept in one place so Home, History, the streak and the day sheet agree.
 */
object LogDays {
    /** 0 is midnight. Set from settings at start-up and whenever it changes. */
    @Volatile var endHour: Int = 3

    fun dayOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).minusHours(endHour.toLong()).toLocalDate()

    fun today(zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): LocalDate = dayOf(now, zone)

    /** When [day] begins: [endHour] on its calendar date. */
    fun start(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        day.atStartOfDay(zone).plusHours(endHour.toLong()).toInstant().toEpochMilli()

    fun end(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long = start(day.plusDays(1), zone)
}
