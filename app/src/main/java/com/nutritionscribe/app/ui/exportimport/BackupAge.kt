package com.nutritionscribe.app.ui.exportimport

/**
 * The line under Settings › Back up & restore: when the last backup was, and a
 * nudge once it is a month old (review S2: there was no date and no reminder, on
 * an app whose only copy of the log is the phone).
 */
object BackupAge {
    const val NUDGE_DAYS = 30

    fun daysSince(at: Long?, now: Long): Int? = at?.let { ((now - it) / 86_400_000L).toInt().coerceAtLeast(0) }

    fun line(at: Long?, now: Long): String {
        val days = daysSince(at, now) ?: return "Never backed up. Your log is only on this phone."
        val ago = when (days) { 0 -> "today"; 1 -> "yesterday"; else -> "$days days ago" }
        return if (days >= NUDGE_DAYS) "Last backup $ago. Time for a new one." else "Last backup $ago."
    }
}
