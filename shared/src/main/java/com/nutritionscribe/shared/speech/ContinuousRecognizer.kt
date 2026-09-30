package com.nutritionscribe.shared.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The phone's (or watch's) own speech recogniser, kept listening until the person
 * says they are done.
 *
 * The system "speak now" dialog this replaces ends at the first pause, which is
 * exactly when someone listing a meal stops to remember what else was on the
 * plate. Android's recogniser has the same habit, and the silence-length extras
 * that are meant to stretch it are ignored by most recognition services, so this
 * does not fight it: when a stretch of speech ends while the person is still
 * holding the button (or has not tapped stop), the text so far is kept and a new
 * stretch starts straight away. Stopping is only ever the person's decision.
 *
 * Every call must come from the main thread; SpeechRecognizer requires it.
 */
class ContinuousRecognizer(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        /** Everything heard so far, including the stretch still being spoken. */
        fun onText(committed: String, partial: String) {}
        /** Loudness, roughly 0..1, for the level meter. */
        fun onLevel(level: Float) {}
        /** The whole thing, after [stop]. Blank when nothing was heard. */
        fun onFinished(text: String, stats: Stats)
        /** A problem that ended listening for good (no permission, no service). */
        fun onFailed(code: Int, message: String) {}
        /** For the debug log: one stretch ended and why. */
        fun onEvent(name: String, detail: Map<String, Any?>) {}
    }

    data class Stats(
        val millis: Long,
        val segments: Int,
        val restarts: Int,
        val errors: List<String>,
    )

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val segments = mutableListOf<String>()
    private var partial = ""
    private var wanted = false
    private var finishing = false
    private var startedAt = 0L
    private var restarts = 0
    private val errors = mutableListOf<String>()

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (wanted) return
        segments.clear(); partial = ""; restarts = 0; errors.clear()
        wanted = true
        finishing = false
        startedAt = System.currentTimeMillis()
        listener.onEvent("speech_engine_start", mapOf("available" to isAvailable))
        listenAgain()
    }

    /** The person is done: finish the stretch in progress, then report. */
    fun stop() {
        if (!wanted) return
        wanted = false
        finishing = true
        listener.onEvent("speech_stop_requested", mapOf("partial" to partial))
        val r = recognizer
        if (r == null) { finish(); return }
        r.stopListening()
        // A recogniser that never answers stopListening must not strand the screen.
        main.postDelayed({ if (finishing) finish() }, 2_500)
    }

    /** Thrown away, nothing reported (the screen closed, or Cancel). */
    fun cancel() {
        wanted = false
        finishing = false
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        destroyRecognizer()
    }

    val text: String get() = joined(segments + partial)

    private fun listenAgain() {
        if (!wanted) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(callbacks)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Asked for; honoured by some services, ignored by most. The restart
            // loop is what actually stops a pause ending the entry.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10_000L)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            fail(-1, e.message ?: "Couldn't start listening")
        }
    }

    private fun commitPartial(final: String?) {
        val t = (final ?: partial).trim()
        if (t.isNotEmpty()) segments += t
        partial = ""
    }

    private fun finish() {
        if (!finishing && wanted) return
        finishing = false
        main.removeCallbacksAndMessages(null)
        if (partial.isNotBlank()) commitPartial(null)
        destroyRecognizer()
        val stats = Stats(System.currentTimeMillis() - startedAt, segments.size, restarts, errors.toList())
        listener.onFinished(joined(segments), stats)
    }

    private fun fail(code: Int, message: String) {
        wanted = false
        finishing = false
        destroyRecognizer()
        listener.onEvent("speech_failed", mapOf("code" to code, "message" to message))
        listener.onFailed(code, message)
    }

    private fun destroyRecognizer() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            // RMS arrives as roughly -2..10 dB.
            listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (p.isBlank()) return
            partial = p
            listener.onText(joined(segments), partial)
        }

        override fun onResults(results: Bundle?) {
            val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            listener.onEvent("speech_segment", mapOf("text" to best, "partial" to partial, "ms" to System.currentTimeMillis() - startedAt))
            commitPartial(best)
            listener.onText(joined(segments), "")
            if (wanted) {
                restarts++
                listenAgain()
            } else {
                finish()
            }
        }

        override fun onError(error: Int) {
            val name = errorName(error)
            listener.onEvent("speech_error", mapOf("code" to error, "name" to name, "wanted" to wanted, "partial" to partial))
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> return fail(error, "Microphone permission is off")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (wanted) {
                    // Another stretch is still closing; try again in a moment.
                    main.postDelayed({ recognizer?.cancel(); listenAgain() }, 250)
                    return
                }
            }
            errors += name
            // Whatever was half-heard when the stretch ended is still what they said.
            commitPartial(null)
            listener.onText(joined(segments), "")
            if (wanted) {
                if (errors.size > 25 && segments.isEmpty()) return fail(error, "Couldn't hear anything ($name)")
                restarts++
                // Recreate after a real error: a recogniser that errored is often stuck.
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) destroyRecognizer()
                main.postDelayed({ listenAgain() }, 120)
            } else {
                finish()
            }
        }
    }

    companion object {
        /** Stretches joined into one sentence, without doubled spaces or a stray comma. */
        fun joined(parts: List<String>): String =
            parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "audio"
            SpeechRecognizer.ERROR_CLIENT -> "client"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
            SpeechRecognizer.ERROR_NETWORK -> "network"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
            SpeechRecognizer.ERROR_SERVER -> "server"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"
            else -> "error_$code"
        }
    }
}
