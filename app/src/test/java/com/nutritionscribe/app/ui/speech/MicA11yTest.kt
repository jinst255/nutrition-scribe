package com.nutritionscribe.app.ui.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class MicA11yTest {
    /**
     * Review F8: with TalkBack in Hold mode the mic's click did nothing (it only
     * toggled in Tap mode), so a screen-reader user couldn't log by voice at all.
     */
    @Test
    fun `a screen reader's click starts and stops recording whatever the mode`() {
        assertEquals(MicA11y.Action.START, MicA11y.onClick(listening = false))
        assertEquals(MicA11y.Action.STOP, MicA11y.onClick(listening = true))
    }
}
