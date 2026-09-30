package com.nutritionscribe.shared.model

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min

/**
 * What to offer as "log again": the foods someone is likely to be eating now.
 *
 * Most recent first was the obvious order and the wrong one: at 7 pm it offered
 * the breakfast from this morning. What someone eats repeats by time of day, so
 * the order is what they usually have around now, then what they have most often,
 * with a nudge for the last few days so a new habit shows up quickly.
 */
object LogAgain {

    data class Choice(
        /** The most recent time it was logged: what "again" copies. */
        val entry: Entry,
        val count: Int,
        val lastAt: Long,
        /** How many of those were within [WINDOW_MINUTES] of the time of day now. */
        val nearNow: Int,
    )

    const val WINDOW_MINUTES = 90
    private const val HISTORY_DAYS = 60L

    fun key(e: Entry): String = e.name.trim().lowercase()

    fun choices(entries: List<Entry>, nowMillis: Long, zone: ZoneId): List<Choice> {
        val since = nowMillis - HISTORY_DAYS * 86_400_000L
        val nowMinute = minuteOfDay(nowMillis, zone)
        return entries.asSequence()
            .filter { it.timestampEpochMillis in since..nowMillis }
            .filter { it.nutrients.isNotEmpty() && !it.needsDetail && !it.needsConfirm && it.name.isNotBlank() }
            .groupBy { key(it) }
            .map { (_, group) ->
                val latest = group.maxBy { it.timestampEpochMillis }
                Choice(
                    entry = latest,
                    count = group.size,
                    lastAt = latest.timestampEpochMillis,
                    nearNow = group.count { e ->
                        // A backdated entry with no time says nothing about when it is eaten.
                        !e.timeUnspecified && circularDistance(minuteOfDay(e.timestampEpochMillis, zone), nowMinute) <= WINDOW_MINUTES
                    },
                )
            }
    }

    /** The chips on Home. */
    fun smart(choices: List<Choice>, nowMillis: Long, limit: Int = 8): List<Choice> =
        choices.sortedWith(
            compareByDescending<Choice> { score(it, nowMillis) }.thenByDescending { it.lastAt },
        ).take(limit)

    fun recent(choices: List<Choice>): List<Choice> = choices.sortedByDescending { it.lastAt }

    fun often(choices: List<Choice>): List<Choice> =
        choices.sortedWith(compareByDescending<Choice> { it.count }.thenByDescending { it.lastAt })

    fun score(c: Choice, nowMillis: Long): Double {
        val daysAgo = (nowMillis - c.lastAt) / 86_400_000.0
        val fresh = if (daysAgo <= 3) 2.0 else if (daysAgo <= 14) 1.0 else 0.0
        return c.nearNow * 3.0 + c.count + fresh
    }

    private fun minuteOfDay(millis: Long, zone: ZoneId): Int {
        val t = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
        return t.hour * 60 + t.minute
    }

    private fun circularDistance(a: Int, b: Int): Int {
        val d = abs(a - b)
        return min(d, 1440 - d)
    }
}
