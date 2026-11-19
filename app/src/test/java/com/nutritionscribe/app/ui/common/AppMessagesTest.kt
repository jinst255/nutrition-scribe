package com.nutritionscribe.app.ui.common

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review L4: nine messages ("Logged again", "Saved to My foods"…) were Toasts:
 * gone in two seconds, no Undo, often missed by TalkBack, and cut short on
 * Android 12+. They are snackbars now, through AppMessages.
 */
class AppMessagesTest {
    @Test
    fun `the app shows no Toasts`() {
        val src = File("src/main/java")
        assertTrue("run from the app module", src.isDirectory)
        val offenders = src.walkTopDown().filter { it.extension == "kt" }
            .filter { "Toast.makeText" in it.readText() }.map { it.name }.toList()
        assertEquals("use AppMessages.say(), not a Toast", emptyList<String>(), offenders)
    }

    @Test
    fun `a plain message has no action`() = runBlocking {
        val next = async(start = CoroutineStart.UNDISPATCHED) { AppMessages.messages.first() }
        AppMessages.say("Saved to My foods")
        val m = next.await()
        assertEquals("Saved to My foods", m.text)
        assertNull(m.action)
    }

    @Test
    fun `logged with nothing added offers no Undo`() = runBlocking {
        val next = async(start = CoroutineStart.UNDISPATCHED) { AppMessages.messages.first() }
        AppMessages.logged("Logged again", emptyList())
        assertNull(next.await().action)
    }
}
