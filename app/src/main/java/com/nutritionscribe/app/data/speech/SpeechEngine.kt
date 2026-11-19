package com.nutritionscribe.app.data.speech

/**
 * Which engine writes out what was said.
 *
 * Owner, 11-14 and 11-16: "I click the audio record button and it's super slow. It
 * should probably use the built-in speech to text engine", and "we should just do
 * that speech-to-text on device". With an OpenAI key, every recording went to
 * OpenAI after the person stopped (1 to 2.5 s more each time, and nothing shown
 * while they talked). The phone's own recogniser is now the default: words appear
 * as they speak, nothing is sent, and it costs nothing. OpenAI stays a choice in
 * Settings › Microphone, for brand names and numbers.
 */
object SpeechEngine {
    enum class Engine { PHONE, OPENAI }

    const val PHONE_BY_DEFAULT = true

    fun pick(onPhone: Boolean, aiOn: Boolean, openAiAllowed: Boolean, hasOpenAiKey: Boolean): Engine =
        if (!onPhone && aiOn && openAiAllowed && hasOpenAiKey) Engine.OPENAI else Engine.PHONE
}
