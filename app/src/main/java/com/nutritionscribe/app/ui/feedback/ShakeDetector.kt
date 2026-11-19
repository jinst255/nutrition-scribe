package com.nutritionscribe.app.ui.feedback

import kotlin.math.sqrt

/**
 * Shake to report (replaces hold to report, owner 2026-11-14). Holding a finger on
 * the screen for a second also held up the app's own long presses, scrolling and
 * hold-to-talk. A shake can't be confused with any of those.
 *
 * A shake is [peaks] sharp jolts (over [thresholdG] times gravity) within [windowMs].
 * One bump, putting the phone down, or walking (a jolt a step, far apart and gentle)
 * is not a shake. After one fires, nothing more for [cooldownMs].
 */
class ShakeDetector(
    private val thresholdG: Double = 2.3,
    private val windowMs: Long = 1000,
    private val peaks: Int = 3,
    private val cooldownMs: Long = 2500,
) {
    private val times = ArrayDeque<Long>()
    private var lastFired = Long.MIN_VALUE / 2
    private var inPeak = false

    /** One accelerometer sample in m/s² (gravity included) at [tMs]. True when it completes a shake. */
    fun onSample(x: Float, y: Float, z: Float, tMs: Long): Boolean {
        val g = sqrt((x * x + y * y + z * z).toDouble()) / GRAVITY
        if (g < thresholdG) { inPeak = false; return false }
        // Samples come every ~20 ms: one jolt is several samples over the line; count it once.
        if (inPeak) return false
        inPeak = true
        if (tMs - lastFired < cooldownMs) return false
        times.addLast(tMs)
        while (times.isNotEmpty() && tMs - times.first() > windowMs) times.removeFirst()
        if (times.size >= peaks) {
            times.clear()
            lastFired = tMs
            return true
        }
        return false
    }

    companion object {
        const val GRAVITY = 9.80665
    }
}
