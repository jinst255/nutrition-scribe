package com.nutritionscribe.shared.model

/**
 * What a goal's target was on a given day (owner, 11-04: "this line isn't actually
 * correct on days I don't need plasma, or if I were to change my goals"). History
 * drew one flat line at today's target and judged every past day by it, so raising
 * protein from 140 to 160 g turned last month's good days "too low".
 *
 * Each change is kept as (metric, day, before, after). A day before a change had
 * that change's "before"; a day after the last change has the current target.
 * Days before the first recorded change can't be known any better than "before".
 * Plasma: on a donation day and the day after, the protein target rises by the
 * extra (see [PlasmaDonation.extraProtein]), as it does on Home.
 */
object GoalTimeline {
    data class Change(val metric: String, val day: Long, val before: Double?, val after: Double?)

    const val MAX_KEPT = 400

    /** The target for [metric] on [day], given its [current] target and every recorded change (oldest first). */
    fun targetOn(metric: String, day: Long, current: Double?, changes: List<Change>): Double? {
        val next = changes.firstOrNull { it.metric == metric && it.day > day } ?: return current
        return next.before
    }

    /** Adds a change unless nothing changed; keeps the newest [MAX_KEPT]. */
    fun record(changes: List<Change>, metric: String, day: Long, before: Double?, after: Double?): List<Change> {
        if (before == after) return changes
        // The Goals field saves on every keystroke (160 → 16 → 1 → 14 → 140): changes on
        // one day merge into one, from the day's first "before" to its last "after".
        val last = changes.lastOrNull()
        if (last != null && last.metric == metric && last.day == day) {
            val merged = changes.dropLast(1)
            return if (last.before == after) merged else merged + last.copy(after = after)
        }
        return (changes + Change(metric, day, before, after)).takeLast(MAX_KEPT)
    }

    fun encode(changes: List<Change>): String =
        changes.joinToString("\n") { "${it.metric}|${it.day}|${it.before ?: ""}|${it.after ?: ""}" }

    fun decode(text: String?): List<Change> = text.orEmpty().lines().mapNotNull { line ->
        val p = line.split('|')
        if (p.size != 4) return@mapNotNull null
        val day = p[1].toLongOrNull() ?: return@mapNotNull null
        Change(p[0], day, p[2].toDoubleOrNull(), p[3].toDoubleOrNull())
    }
}
