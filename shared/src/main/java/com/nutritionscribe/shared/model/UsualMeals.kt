package com.nutritionscribe.shared.model

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * "Your usual lunch": the same few foods logged one by one, close together, on
 * more than one day (owner, 11-19: "I eat the same vegetable bag with the same
 * yogurt with the same burrito, or sometimes two burritos. So that's 4 separate
 * clicks when it could be 1."). Offered as one Log again tap that logs them all,
 * each as it was last time. No pop-up: it only appears in Log again.
 */
object UsualMeals {
    /** Entries this close to the one before belong to the same sitting. */
    const val SITTING_MINUTES = 30L
    const val MIN_DAYS = 2
    private const val HISTORY_DAYS = 30L
    private const val MINUTE = 60_000L

    data class Meal(
        /** "Your usual lunch". */
        val label: String,
        /** Short names, in the order they were logged last time. */
        val names: List<String>,
        /** The last sitting's entries: what one tap logs again. */
        val entries: List<Entry>,
        /** On how many different days they were logged together. */
        val days: Int,
        val lastAt: Long,
    ) {
        val calories: Double get() = entries.sumOf { it.nutrients["calories"] ?: 0.0 }
    }

    private fun key(e: Entry) = LogAgain.key(e)

    /** Entries split into sittings: each within [SITTING_MINUTES] of the one before. */
    fun sittings(entries: List<Entry>): List<List<Entry>> {
        val out = mutableListOf<MutableList<Entry>>()
        for (e in entries.filter { !it.timeUnspecified && !it.needsDetail }.sortedBy { it.timestampEpochMillis }) {
            val last = out.lastOrNull()?.last()
            if (last != null && e.timestampEpochMillis - last.timestampEpochMillis <= SITTING_MINUTES * MINUTE) out.last() += e
            else out += mutableListOf(e)
        }
        return out
    }

    fun of(entries: List<Entry>, now: Long, zone: ZoneId = ZoneId.systemDefault(), limit: Int = 2): List<Meal> {
        val recent = entries.filter { it.timestampEpochMillis in (now - HISTORY_DAYS * 24 * 60 * MINUTE)..now }
        // Same set of foods (amounts may differ: one burrito or two), at least two of them.
        val groups = sittings(recent)
            .map { s -> s.distinctBy(::key) }
            .filter { it.size >= 2 }
            .groupBy { s -> s.map(::key).toSortedSet().joinToString("|") }
        val meals = groups.values.mapNotNull { same ->
            val days = same.map { LogDays.dayOf(it.first().timestampEpochMillis, zone) }.distinct().size
            if (days < MIN_DAYS) return@mapNotNull null
            val latest = same.maxBy { it.last().timestampEpochMillis }
            val hours = same.map { Instant.ofEpochMilli(it.first().timestampEpochMillis).atZone(zone).let { t -> t.hour + t.minute / 60.0 } }
            Meal(labelFor(hours.sorted()[hours.size / 2]), latest.map { short(it.name) }, latest, days, latest.last().timestampEpochMillis)
        }
        // A smaller set inside a bigger usual one is the same habit: keep the bigger.
        val kept = meals.filter { m -> meals.none { o -> o !== m && o.days >= m.days && o.entries.size > m.entries.size &&
            o.entries.map(::key).containsAll(m.entries.map(::key)) } }
        val nowHour = Instant.ofEpochMilli(now).atZone(zone).let { it.hour + it.minute / 60.0 }
        return kept.sortedWith(compareBy<Meal>({ -it.days }, { -it.lastAt })).take(limit)
            .sortedBy { abs(hourOf(it, zone) - nowHour) }
    }

    private fun hourOf(m: Meal, zone: ZoneId) =
        Instant.ofEpochMilli(m.entries.first().timestampEpochMillis).atZone(zone).let { it.hour + it.minute / 60.0 }

    fun labelFor(hour: Double): String = "Your usual " + when {
        hour < 4 -> "late snack"
        hour < 10.5 -> "breakfast"
        hour < 15 -> "lunch"
        hour < 17 -> "snack"
        hour < 21.5 -> "dinner"
        else -> "late snack"
    }

    /** "Great Value — Frozen Broccoli Florets" → "Frozen Broccoli Florets"; "Oats + Banana" stays. */
    fun short(name: String): String = name.substringAfter(" — ").trim()
}
