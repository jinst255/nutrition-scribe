package com.nutritionscribe.app.ui.common

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Call first thing inside a full-screen Dialog that has fields and a bottom bar.
 * A dialog's window is not resized for the keyboard by default, so imePadding()
 * got no insets and the editor's Save sat under the keyboard (owner, 2026-11-06).
 * Checked by hand: docs/MANUAL_CHECKS.md, "Save above the keyboard".
 */
@Composable
fun DialogKeyboardFit() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    val dark = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < 0.5f
    SideEffect {
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Sam (beta, Android 15+, three-button nav): Compose sizes the dialog's content
        // to the whole display (2399 px) while the system fitted the window between
        // the bars (y 128-2274), so the bottom 254 px, Done included, were cut off.
        // The window now covers the whole screen and fits nothing itself; the
        // dialog's content pads for the bars (statusBarsPadding, navigationBarsPadding).
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.also {
                it.fitInsetsTypes = FullScreenDialog.FIT_NONE
                // Without this the window still started below the camera cutout (y 128).
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // A dialog window has its own bars' icon colour: match the theme as the app does.
        com.nutritionscribe.app.ui.theme.SystemBars.apply(window, view, dark)
    }
}

/** A full-screen dialog draws under the bars and pads for them itself. */
object FullScreenDialog {
    const val FIT_NONE = 0
}

/**
 * The same for a full screen in the activity: while it is shown, the keyboard
 * resizes rather than pans, so imePadding() works and the page scrolls to its
 * button. Sam (beta): on Tell us, with the keyboard up, Save could not be reached;
 * the window slid up and nothing scrolled. Restored when the screen goes.
 */
@Composable
fun ScreenKeyboardFit() {
    val activity = LocalView.current.context.let { c ->
        generateSequence(c) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
    } ?: return
    androidx.compose.runtime.DisposableEffect(activity) {
        val window = activity.window
        val before = window.attributes.softInputMode
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose { window.setSoftInputMode(before) }
    }
}
