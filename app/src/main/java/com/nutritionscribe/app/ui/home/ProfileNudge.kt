package com.nutritionscribe.app.ui.home

/**
 * When Home shows "Add your height, weight and activity…" (review H2). It had no
 * close button and showed on every open for anyone who skipped the body page.
 * Now: until it is closed once; then once more a week later; then never.
 */
object ProfileNudge {
    const val ASK_AGAIN_AFTER_MS = 7L * 86_400_000L

    fun due(hasProfile: Boolean, closes: Int, lastClosedAt: Long, now: Long): Boolean = when {
        hasProfile -> false
        closes <= 0 -> true
        closes == 1 -> now - lastClosedAt >= ASK_AGAIN_AFTER_MS
        else -> false
    }
}
