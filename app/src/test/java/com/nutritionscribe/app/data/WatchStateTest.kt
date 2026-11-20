package com.nutritionscribe.app.data

import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import com.nutritionscribe.shared.sync.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * What the phone tells the watch for its shortcuts (watch canvas B2/B3 and P1,
 * approved 11-20): one-tap foods that close the gap, the usual hour of each Log
 * again food and meal, when the last entry was, how many wait on the phone, and
 * the watch home picked in Settings › Watch.
 */
class WatchStateTest {
    private val zone = ZoneOffset.UTC
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, day, h, m).toInstant(zone).toEpochMilli()
    private var n = 0
    private fun e(name: String, t: Long, protein: Double, kcal: Double, confirm: Boolean = false) = Entry(
        id = "e${++n}", timestampEpochMillis = t, rawText = name, name = name, source = "test", confidence = Confidence.STATED,
        nutrients = mapOf("protein" to protein, "calories" to kcal), needsConfirm = confirm,
    )
    private val goals = listOf(
        MetricGoal(Metric.CALORIES, true, 2200.0, sortOrder = 0),
        MetricGoal(Metric.PROTEIN, true, 100.0, sortOrder = 1),
    )
    private val now = at(20, 13)

    private val log = listOf(
        e("Coffee", at(17, 8), 0.0, 5.0), e("Coffee", at(18, 8, 30), 0.0, 5.0), e("Coffee", at(19, 7, 30), 0.0, 5.0),
        e("Protein shake", at(18, 16), 30.0, 160.0), e("Protein shake", at(19, 16), 30.0, 160.0),
        e("Eggs", at(20, 8), 20.0, 150.0),
        e("Mystery bowl", at(20, 12), 0.0, 0.0, confirm = true),
    )

    @Test
    fun `the gap's one-tap foods, with their calories`() {
        val s = WatchSync.state(log, goals, debug = false, now = now, zone = zone)
        assertEquals("Protein", s.need!!.name)
        val fill = s.need!!.foods.first()
        assertEquals("Protein shake", fill.name)
        assertEquals(30.0, fill.amount, 1e-9)
        assertEquals(160, fill.kcal)
    }

    @Test
    fun `each log again food carries its usual hour and calories`() {
        val s = WatchSync.state(log, goals, debug = false, now = now, zone = zone)
        val coffee = s.again.first { it.name == "Coffee" }
        assertEquals(8.0, coffee.hour!!, 1e-9)   // 7:30, 8:00, 8:30: the middle one
        assertEquals(5, coffee.kcal)
    }

    @Test
    fun `last logged and the phone queue`() {
        val s = WatchSync.state(log, goals, debug = false, now = now, zone = zone)
        assertEquals(at(20, 12), s.lastAt)
        assertEquals(1, s.toCheck)
    }

    @Test
    fun `the watch home picked on the phone, with the one goal it shows`() {
        assertNull(WatchSync.state(log, goals, false, now, zone = zone).home)
        val one = WatchSync.state(log, goals, false, now, home = WatchState.Home.ONE, oneKey = Metric.PROTEIN.key, zone = zone)
        assertEquals(WatchState.Home.ONE, one.home)
        assertEquals("Protein", one.one!!.name)
        // Not picked yet: the first goal.
        val first = WatchSync.state(log, goals, false, now, home = WatchState.Home.ONE, zone = zone)
        assertEquals(s0Name(), first.one!!.name)
    }

    private fun s0Name() = WatchSync.state(log, goals, false, now, zone = zone).goals.first().name
}
