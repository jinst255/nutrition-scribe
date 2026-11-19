package com.nutritionscribe.app.ui.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner, 11-19: a "Leave beta testing" row at the very bottom of Settings, under Show advanced settings. */
class BetaModeTest {

    @Test
    fun `a test build is in beta until they leave`() {
        assertTrue(BetaMode.isOn("1.8-test", left = false))
        assertFalse(BetaMode.isOn("1.8-test", left = true))
        assertFalse(BetaMode.isOn("1.8", left = false))
        assertFalse(BetaMode.isOn(null, left = false))
    }

    @Test
    fun `the row is the last thing in settings, both with and without advanced`() {
        val src = java.io.File("src/main/java/com/nutritionscribe/app/ui/settings/SettingsScreen.kt").readText()
        val toggle = src.indexOf("\"Show advanced settings\"")
        assertTrue(toggle > 0)
        // Without advanced: right after the toggle, before the version line.
        val short = src.indexOf("LeaveBetaRow()", toggle)
        assertTrue(short in toggle..src.indexOf("return@Column", toggle))
        // With advanced: after Reset, the last row before the version line.
        val reset = src.indexOf("ResetSection(reset")
        val last = src.indexOf("LeaveBetaRow()", reset)
        assertTrue(last > reset)
        assertEquals(src.indexOf("VersionLine()", reset), src.indexOf("VersionLine()", last))
    }
}
