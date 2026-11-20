package com.nutritionscribe.shared.sync

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * What each watch-face shortcut shows and does (watch canvas B2 and B3, approved
 * 11-20). The person picks which shortcut goes in which slot, the usual Wear OS
 * way: long-press the face › Customise › tap a slot › Nutrition Scribe › one of
 * these. Kept free of Android so the choices are tested on the JVM.
 *
 * Every pick is made on the watch from the phone's last [WatchState], with the
 * hour now: the phone isn't asked again when the time of day moves on.
 */
object WatchShortcuts {
    /** A usual meal this close to its usual hour is the one "now". */
    const val MEAL_NEAR_HOURS = 2.0
    /** A food logged this close to its usual hour is the one "again". */
    const val AGAIN_NEAR_HOURS = 1.5

    /** Hours apart on the clock face: 23:00 and 01:00 are 2 apart, not 22. */
    fun hoursApart(a: Double, b: Double): Double = abs(a - b).let { min(it, 24 - it) }

    /** Need most + Say (B2): "21 g" and "Fiber", or null when every goal is met (the slot shows just Say). */
    fun needSay(s: WatchState): Pair<String, String>? {
        val need = s.need ?: return null
        if (need.left < 0.5) return null
        return "${need.left.roundToLong()} ${need.unit}" to need.name
    }

    /** Your usual meal for this time of day, if there is one. */
    fun mealNow(s: WatchState, hour: Double): WatchState.Meal? =
        s.meals.filter { it.hour != null }
            .minByOrNull { hoursApart(it.hour!!, hour) }
            ?.takeIf { hoursApart(it.hour!!, hour) <= MEAL_NEAR_HOURS }

    /** What they log most around now; failing that, the top of Log again. */
    fun againNow(s: WatchState, hour: Double): WatchState.Again? =
        s.again.firstOrNull { a -> a.hour?.let { hoursApart(it, hour) <= AGAIN_NEAR_HOURS } == true } ?: s.again.firstOrNull()

    /** Close the gap: the one food of theirs that covers what's left. Null once there. */
    fun gap(s: WatchState): WatchState.Fill? = s.need?.takeIf { it.left >= 0.5 }?.foods?.firstOrNull()

    /** "+30 g": what a [WatchState.Fill] gives. */
    fun gives(f: WatchState.Fill, unit: String): String = "+${f.amount.roundToLong()} $unit"

    /** "4 h": how long since the last entry, for faces that can't count up by themselves. */
    fun since(lastAt: Long?, now: Long): String? {
        lastAt ?: return null
        val minutes = ((now - lastAt).coerceAtLeast(0)) / 60_000
        return when {
            minutes < 60 -> "$minutes m"
            minutes < 48 * 60 -> "${minutes / 60} h"
            else -> "${minutes / (24 * 60)} d"
        }
    }

    /** "Waiting on phone": shown only while something is. */
    fun toCheck(s: WatchState): Int? = s.toCheck.takeIf { it > 0 }
}
