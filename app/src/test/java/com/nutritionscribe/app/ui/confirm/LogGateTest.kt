package com.nutritionscribe.app.ui.confirm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 11-16: the check card shows the built-in list's quick guess while the AI
 * checks, and Log it logged that guess; the AI's answer a few seconds later was
 * thrown away ("they'd very likely just hit log … that might log it as the wrong
 * items"). A tap while checking now waits for the AI and logs its answer.
 */
class LogGateTest {

    @Test
    fun `log it while the ai is checking waits for its answer`() {
        assertEquals(LogGate.Tap.WAIT_FOR_AI, LogGate.onTap(aiChecking = true, handEdited = false, editing = false))
        assertTrue(LogGate.logOnArrival(waiting = true, handEdited = false))
        assertEquals("Log it when checked", LogGate.label(aiChecking = true, handEdited = false, waiting = false, base = "Log it"))
        assertEquals("Logs when checked…", LogGate.label(aiChecking = true, handEdited = false, waiting = true, base = "Log it"))
    }

    @Test
    fun `their own edits, a fix, or no ai log at once`() {
        assertEquals(LogGate.Tap.LOG_NOW, LogGate.onTap(aiChecking = false, handEdited = false, editing = false))
        assertEquals(LogGate.Tap.LOG_NOW, LogGate.onTap(aiChecking = true, handEdited = true, editing = false))
        assertEquals(LogGate.Tap.LOG_NOW, LogGate.onTap(aiChecking = true, handEdited = false, editing = true))
        assertFalse(LogGate.logOnArrival(waiting = false, handEdited = false))
        assertFalse(LogGate.logOnArrival(waiting = true, handEdited = true))
        assertEquals("Log it", LogGate.label(aiChecking = false, handEdited = false, waiting = false, base = "Log it"))
        assertEquals("Save recipe", LogGate.label(aiChecking = true, handEdited = false, waiting = false, base = "Save recipe"))
    }
}
