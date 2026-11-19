package com.nutritionscribe.app.ui.home

import com.nutritionscribe.shared.model.Entry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The top of Home before and after the first entry (review H1, H4).
 *
 * H4: a new install showed "0 days streak" and seven empty dots before anything
 * was logged, which reads as already failing. The streak waits for the first
 * entry; until then Home says how to log the first meal.
 *
 * H1: what was logged last sat at the bottom of Home under every bar. A line at
 * the top now says it ("Last: Moms chili · 12:04 PM") and opens it.
 */
object HomeTop {
    /** The streak shows once anything has been logged, ever. */
    fun showStreak(anyEntryEver: Boolean): Boolean = anyEntryEver

    /** "Last: Moms chili · 12:04 PM", or "Last: … · Yesterday 8:10 PM"; null with nothing logged. */
    fun lastLine(last: Entry?, name: String, zone: ZoneId, today: java.time.LocalDate, locale: Locale = Locale.US): String? {
        last ?: return null
        val at = Instant.ofEpochMilli(last.timestampEpochMillis).atZone(zone)
        val time = at.format(DateTimeFormatter.ofPattern("h:mm a", locale))
        val day = when (at.toLocalDate()) {
            today -> ""
            today.minusDays(1) -> "Yesterday "
            else -> at.format(DateTimeFormatter.ofPattern("EEE, MMM d", locale)) + " "
        }
        // AI names come back lower-case ("grilled chicken breast"); a line starts with a capital.
        return "Last: ${name.replaceFirstChar { it.uppercase() }} · $day$time"
    }
}
