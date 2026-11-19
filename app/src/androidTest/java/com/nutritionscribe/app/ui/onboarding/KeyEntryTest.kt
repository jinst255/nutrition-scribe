package com.nutritionscribe.app.ui.onboarding

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutritionscribe.app.network.openai.KeyCheck
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup refused a working key ("That key didn't work") that Settings then accepted
 * (found on the emulator, 2026-11-14). The buttons called a reference to a function
 * declared in the composable, which Compose kept from an earlier recomposition, so
 * the check was sent the key as it stood when only part of it was typed. The key
 * is now read when the button is pressed. Needs the emulator.
 */
@RunWith(AndroidJUnit4::class)
class KeyEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theWholeKeyIsChecked_evenWhenTypedInPieces() {
        var checked: String? = null
        compose.setContent {
            ReadyToGo(keptKey = false, onStart = {}, checkKey = { k -> checked = k; KeyCheck.Result.Works })
        }
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("sk-proj-aaaa")
        compose.waitForIdle()
        field.performTextInput("bbbb")
        compose.waitForIdle()
        field.performTextInput("cccc")
        compose.waitForIdle()
        compose.onNodeWithText("Use this key").performClick()
        compose.waitUntil(5_000) { checked != null }
        assertEquals("sk-proj-aaaabbbbcccc", checked)
    }
}
