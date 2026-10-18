package com.nutritionscribe.app.data.speech

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TrimSilenceTest {
    private val rate = AudioCapture.RATE

    /** [seconds] of a 200 Hz tone (speech stand-in) or near-silence with a little hiss. */
    private fun part(seconds: Double, loud: Boolean): ByteArray {
        val n = (seconds * rate).toInt()
        val out = ByteArray(n * 2)
        val rnd = java.util.Random(1)
        for (i in 0 until n) {
            val v = if (loud) (8000 * sin(2 * PI * 200 * i / rate)).toInt() else rnd.nextInt(120) - 60
            out[2 * i] = (v and 0xff).toByte(); out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    @Test
    fun `silence around and inside a sentence is cut, the words kept`() {
        val pcm = part(1.0, false) + part(1.0, true) + part(2.0, false) + part(1.0, true) + part(1.0, false)
        val trimmed = AudioCapture.trimSilence(pcm)
        val seconds = trimmed.size / (rate * 2.0)
        // 0.2 lead + 1 + 0.3 pause + 1 + 0.2 tail
        assertEquals(2.7, seconds, 0.1)
    }

    @Test
    fun `a short pause between words stays`() {
        val pcm = part(1.0, true) + part(0.4, false) + part(1.0, true)
        assertEquals(pcm.size.toDouble(), AudioCapture.trimSilence(pcm).size.toDouble(), rate * 2 * 0.05)
    }
}
