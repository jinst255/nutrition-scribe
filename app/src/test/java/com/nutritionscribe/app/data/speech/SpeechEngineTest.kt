package com.nutritionscribe.app.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 11-14 / 11-16: speech was slow because, with an OpenAI key, every
 * recording was sent to OpenAI after they stopped talking. The phone's own
 * recogniser is the default now; OpenAI only when chosen.
 */
class SpeechEngineTest {
    @Test
    fun `speech stays on the phone unless openai is chosen`() {
        assertTrue(SpeechEngine.PHONE_BY_DEFAULT)
        assertEquals(SpeechEngine.Engine.PHONE, SpeechEngine.pick(onPhone = SpeechEngine.PHONE_BY_DEFAULT, aiOn = true, openAiAllowed = true, hasOpenAiKey = true))
        assertEquals(SpeechEngine.Engine.OPENAI, SpeechEngine.pick(onPhone = false, aiOn = true, openAiAllowed = true, hasOpenAiKey = true))
    }

    @Test
    fun `openai speech needs ai on, consent and a key`() {
        assertEquals(SpeechEngine.Engine.PHONE, SpeechEngine.pick(onPhone = false, aiOn = false, openAiAllowed = true, hasOpenAiKey = true))
        assertEquals(SpeechEngine.Engine.PHONE, SpeechEngine.pick(onPhone = false, aiOn = true, openAiAllowed = false, hasOpenAiKey = true))
        assertEquals(SpeechEngine.Engine.PHONE, SpeechEngine.pick(onPhone = false, aiOn = true, openAiAllowed = true, hasOpenAiKey = false))
    }
}
