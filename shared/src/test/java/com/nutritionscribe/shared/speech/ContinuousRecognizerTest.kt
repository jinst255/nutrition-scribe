package com.nutritionscribe.shared.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuousRecognizerTest {
    /** Stretches of speech split at pauses must read back as one sentence. */
    @Test
    fun stretchesJoinIntoOneSentence() {
        assertEquals(
            "two eggs and a slice of toast and a coffee",
            ContinuousRecognizer.joined(listOf(" two eggs ", "", "and a slice of toast", "  and a coffee")),
        )
        assertEquals("", ContinuousRecognizer.joined(listOf("", " ")))
    }
}
