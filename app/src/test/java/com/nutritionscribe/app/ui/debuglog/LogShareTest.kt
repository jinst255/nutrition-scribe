package com.nutritionscribe.app.ui.debuglog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The week-later reminder a friend testing the app gets on Home. */
class LogShareTest {
    private val day = 24L * 60 * 60 * 1000
    private val installed = 1_790_000_000_000L

    @Test
    fun `asks a week after install, not before`() {
        assertFalse(LogShare.due(installed, null, 0, installed + 6 * day))
        assertTrue(LogShare.due(installed, null, 0, installed + 7 * day))
    }

    @Test
    fun `a sent log resets the week, and later waits`() {
        val sent = installed + 8 * day
        assertFalse(LogShare.due(installed, sent, 0, sent + 3 * day))
        assertTrue(LogShare.due(installed, sent, 0, sent + 7 * day))
        val now = installed + 7 * day
        assertFalse(LogShare.due(installed, null, now + 2 * day, now))
        assertTrue(LogShare.due(installed, null, now + 2 * day, now + 2 * day))
    }

    @Test
    fun `no install date, no reminder`() {
        assertFalse(LogShare.due(null, null, 0, installed))
    }
}
