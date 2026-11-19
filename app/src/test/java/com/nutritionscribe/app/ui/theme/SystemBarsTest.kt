package com.nutritionscribe.app.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On Android 15 (emulator, API 35) the light theme showed white status-bar icons
 * on a near-white background: the clock and battery were invisible. Nothing set
 * the icons' colour; the theme now does, dark icons on light and light on dark.
 */
class SystemBarsTest {
    @Test
    fun `the light theme gets dark status-bar icons`() {
        assertTrue(SystemBars.darkIcons(useDark = false))
    }

    @Test
    fun `the dark theme gets light status-bar icons`() {
        assertFalse(SystemBars.darkIcons(useDark = true))
    }
}
