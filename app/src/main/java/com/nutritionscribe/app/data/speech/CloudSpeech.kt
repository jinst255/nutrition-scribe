package com.nutritionscribe.app.data.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.nutritionscribe.app.data.usage.UsageMeter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Records the microphone to memory, for Highest quality mode's transcription.
 *
 * Plain 16 kHz mono PCM: what speech models are trained on, about 1.9 MB a
 * minute, and a WAV header is all it needs to upload. There is no pause
 * detection at all — it records until the person stops it, which is the point.
 */
class AudioCapture(private val onLevel: (Float) -> Unit) {
    private var record: AudioRecord? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var running = false
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // checked by the screen before it starts
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, min * 4)
        } catch (e: Exception) { return false }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        record = r
        pcm.reset()
        running = true
        r.startRecording()
        worker = thread(name = "audio-capture") {
            val buf = ByteArray(min)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                synchronized(pcm) { if (pcm.size() < MAX_BYTES) pcm.write(buf, 0, n) }
                onLevel(level(buf, n))
            }
        }
        return true
    }

    /** Stops and returns the recording as a WAV file's bytes. */
    fun stop(): ByteArray {
        running = false
        worker?.join(500)
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        return wav(trimSilence(data))
    }

    fun cancel() { stop() }

    companion object {
        const val RATE = 16_000
        /** Two minutes: longer than any meal description, short enough to upload quickly. */
        private const val MAX_BYTES = RATE * 2 * 120

        fun seconds(wav: ByteArray): Double = (wav.size - 44).coerceAtLeast(0) / (RATE * 2.0)

        private const val FRAME = RATE / 50 * 2 // 20 ms of 16-bit samples, in bytes
        private const val KEEP_EDGE = 10 // frames: 200 ms kept either side of speech
        private const val LONG_PAUSE = 35 // frames: a pause over 0.7 s...
        private const val SHORT_PAUSE = 15 // ...is shortened to 0.3 s

        /**
         * The recording without the silence a person leaves around and inside a
         * sentence: the second before they start, the one after, and the long "um…"
         * in the middle. Transcription is billed by the second of audio, so this is
         * money, and the words are untouched (a pause still separates them).
         * Quiet is judged against the recording's own floor, so a noisy room is not
         * cut into pieces.
         */
        fun trimSilence(pcm: ByteArray): ByteArray {
            val frames = pcm.size / FRAME
            if (frames < KEEP_EDGE * 3) return pcm
            val rms = DoubleArray(frames) { f ->
                var sum = 0.0
                var i = f * FRAME
                while (i + 1 < (f + 1) * FRAME) {
                    val v = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
                    sum += v * v; i += 2
                }
                sqrt(sum / (FRAME / 2))
            }
            val floor = rms.sorted()[frames / 10]
            val loud = rms.map { it > maxOf(300.0, floor * 3.0) }
            val first = loud.indexOfFirst { it }
            val last = loud.indexOfLast { it }
            if (first < 0) return pcm
            val out = ByteArrayOutputStream(pcm.size)
            var quiet = 0
            for (f in maxOf(0, first - KEEP_EDGE)..minOf(frames - 1, last + KEEP_EDGE)) {
                quiet = if (loud[f]) 0 else quiet + 1
                // Inside a long pause, frames past the short one are dropped.
                if (quiet in (SHORT_PAUSE + 1)..Int.MAX_VALUE && f in first..last && runLength(loud, f) >= LONG_PAUSE) continue
                out.write(pcm, f * FRAME, FRAME)
            }
            return out.toByteArray()
        }

        /** The length of the quiet run [f] is in. */
        private fun runLength(loud: List<Boolean>, f: Int): Int {
            var a = f; while (a > 0 && !loud[a - 1]) a--
            var b = f; while (b < loud.size - 1 && !loud[b + 1]) b++
            return b - a + 1
        }

        private fun level(buf: ByteArray, n: Int): Float {
            var sum = 0.0
            var i = 0
            while (i + 1 < n) {
                val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
                sum += s.toDouble() * s
                i += 2
            }
            val rms = sqrt(sum / (n / 2).coerceAtLeast(1))
            return (rms / 6000.0).toFloat().coerceIn(0f, 1f)
        }

        fun wav(pcm: ByteArray): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(pcm.size)
            }.array()
            return header + pcm
        }
    }
}

/**
 * OpenAI speech-to-text over the user's own key.
 *
 * gpt-4o-mini-transcribe: $0.003 a minute, and on a test sentence full of brand
 * names ("Premier Protein", "Fage 0%") it got every one — which is where the
 * phone's own recogniser most often goes wrong. The prompt primes it for food
 * words and numbers; it is a hint, not an instruction, and cannot change what
 * was said.
 */
class OpenAiTranscriber(
    private val apiKey: String,
    private val meter: UsageMeter,
    private val model: String = MODEL,
) {
    sealed class Result {
        data class Text(val text: String) : Result()
        data class Failed(val message: String) : Result()
    }

    fun transcribe(wav: ByteArray, tag: String?): Result {
        val seconds = AudioCapture.seconds(wav)
        if (seconds < 0.3) return Result.Failed("Nothing was recorded")
        val boundary = "----ns" + System.nanoTime()
        val body = ByteArrayOutputStream().apply {
            fun field(name: String, value: String) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("model", model)
            field("response_format", "json")
            field("language", "en")
            field("prompt", PROMPT)
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            write(wav)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()

        val t0 = System.currentTimeMillis()
        val conn = URL("https://api.openai.com/v1/audio/transcriptions").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 45_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val said = runCatching {
                (Json.parseToJsonElement(text).jsonObject["text"] as? JsonPrimitive)?.content?.trim()
            }.getOrNull()
            meter.recordAudio(model, seconds, status, System.currentTimeMillis() - t0, said ?: text.take(500), tag)
            when {
                status == 401 || status == 403 -> Result.Failed("The API key was rejected")
                status !in 200..299 -> Result.Failed("OpenAI error $status")
                said.isNullOrBlank() -> Result.Failed("Nothing was heard")
                else -> Result.Text(said)
            }
        } catch (e: IOException) {
            meter.recordAudio(model, seconds, -1, System.currentTimeMillis() - t0, e.message, tag)
            Result.Failed("No connection")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MODEL = "gpt-4o-mini-transcribe"
        const val PROMPT = "Someone listing what they ate for a nutrition log: foods, brands, amounts and units, " +
            "e.g. \"2 eggs, a Premier Protein shake, half a cup of Fage 0% Greek yogurt, 6 oz chicken breast.\""
    }
}
