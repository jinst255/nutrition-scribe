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
        return wav(data)
    }

    fun cancel() { stop() }

    companion object {
        const val RATE = 16_000
        /** Two minutes: longer than any meal description, short enough to upload quickly. */
        private const val MAX_BYTES = RATE * 2 * 120

        fun seconds(wav: ByteArray): Double = (wav.size - 44).coerceAtLeast(0) / (RATE * 2.0)

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
