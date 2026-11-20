package com.nutritionscribe.app.ui.history

import kotlin.math.roundToLong

/**
 * A chart in words, for screen readers (docs/UI_GUIDELINES.md §5, item 13): the
 * number of days, the average, the highest, and the goal. Canvas charts say
 * nothing to TalkBack on their own.
 */
object ChartWords {
    fun strip(values: List<Double>, goal: Double?): String {
        if (values.isEmpty()) return "Chart: no days logged yet."
        val avg = values.average()
        fun n(v: Double) = String.format(java.util.Locale.US, "%,d", v.roundToLong())
        return buildString {
            append("Chart of ${values.size} ${if (values.size == 1) "day" else "days"}. ")
            append("Average ${n(avg)}, highest ${n(values.max())}, lowest ${n(values.min())}.")
            goal?.let { append(" Goal ${n(it)}.") }
        }
    }
}
