package com.nutritionscribe.app.ui.confirm

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.Origin
import com.nutritionscribe.shared.resolve.ItemParser
import com.nutritionscribe.shared.resolve.Resolver
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sam (beta, Android 17, three-button navigation): in the food editor, Done sat
 * under the nav buttons and couldn't be pressed. The editor is a full-screen
 * dialog; Compose sized its content to the whole display while Android 15+
 * placed the window below the camera cutout and between the bars, so the bottom
 * of the content, Done included, went off the screen. The dialog now covers the
 * screen and pads for the bars itself (DialogKeyboardFit).
 *
 * Needs an emulator; the bug only shows on Android 15 (API 35) and later, with
 * three-button navigation (`adb shell cmd overlay enable
 * com.android.internal.systemui.navbar.threebutton`). Passes on older versions too.
 */
@RunWith(AndroidJUnit4::class)
class FoodEditorBarsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val oats = FoodCandidate(
        id = "usda:test-oats", name = "Oatmeal", origin = Origin.USDA, per100g = mapOf("calories" to 76.0, "protein" to 2.2), defaultGrams = 234.0,
    )

    private fun draft() = ItemDraft(
        item = ItemParser.Item(said = "a bowl of oatmeal", food = "oatmeal", amount = 1.0, unit = "bowl"),
        key = "oatmeal", candidate = oats, grams = 234.0, portionNote = "one cup cooked",
        computed = mapOf("calories" to 178.0, "protein" to 5.1), status = Resolver.Status.NEW, weak = false,
        offerScan = false, offerBreakdown = false, alternates = emptyList(), untouched = false,
    )

    @Test
    fun doneSitsAboveTheNavigationBar() {
        org.junit.Assume.assumeTrue("needs API 29+ to find the dialog window", android.os.Build.VERSION.SDK_INT >= 29)
        compose.setContent {
            FoodEditor(
                draft = draft(), partOf = null, search = { emptyList() }, saveLabel = "Done",
                learnDefault = true, onSave = { _, _ -> }, onDismiss = {},
            )
        }
        compose.waitForIdle()
        val node = compose.onNodeWithText("Done").fetchSemanticsNode()
        // Where the dialog's window is on the screen (it is the topmost window).
        var windowTop = 0
        compose.runOnUiThread {
            val views = android.view.inspector.WindowInspector.getGlobalWindowViews()
            val loc = IntArray(2)
            views.last().getLocationOnScreen(loc)
            windowTop = loc[1]
        }
        val bottomOnScreen = windowTop + node.boundsInWindow.bottom

        val activity = compose.activity
        val screenHeight = activity.resources.displayMetrics.heightPixels.let { h ->
            if (android.os.Build.VERSION.SDK_INT >= 30) activity.windowManager.currentWindowMetrics.bounds.height() else h
        }
        val navBar = ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        assertTrue(
            "Done ends at y=$bottomOnScreen; the nav bar starts at ${screenHeight - navBar} (screen $screenHeight, bar $navBar)",
            bottomOnScreen <= (screenHeight - navBar + 1).toFloat(),
        )
    }
}
