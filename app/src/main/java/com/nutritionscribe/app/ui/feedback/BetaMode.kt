package com.nutritionscribe.app.ui.feedback

import android.content.Context

/**
 * Whether this copy is in beta testing: a test build ("1.8-test") that hasn't left.
 *
 * Owner, 11-19: "in settings, add a thing at the very bottom (under show advanced
 * settings) … a 'leave beta testing mode'". Leaving hides the Beta testing section,
 * the tester welcome and name check, the week-later "send your log" card and shake
 * to report, and turns debug mode off. The food log is untouched; the same row
 * rejoins.
 *
 * Kept in its own small preferences file, read at once: whether the beta screens
 * show is decided before the first frame.
 */
object BetaMode {
    private const val PREFS = "nutrition_scribe_beta"
    private const val LEFT = "left"

    fun isOn(versionName: String?, left: Boolean): Boolean = versionName.orEmpty().contains("test") && !left

    /** Observed by the screens that change when they leave or rejoin. */
    val left = androidx.compose.runtime.mutableStateOf<Boolean?>(null)

    fun hasLeft(context: Context): Boolean = left.value ?: runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(LEFT, false)
    }.getOrDefault(false).also { left.value = it }

    fun setLeft(context: Context, value: Boolean) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(LEFT, value).apply() }
        left.value = value
    }
}
