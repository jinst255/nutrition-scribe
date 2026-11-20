package com.nutritionscribe.shared.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * "Your usual lunch" (owner, 11-19): the same foods logged one at a time, close
 * together, on different days, become one Log again tap. One burrito or two is
 * still the same lunch; the tap copies the latest amounts.
 */
class UsualMealsTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private var n = 0
    private fun at(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, day, h, m).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun e(name: String, t: Long, kcal: Double = 100.0) = Entry(
        id = "e${++n}", timestampEpochMillis = t, rawText = name, name = name, source = "test",
        confidence = Confidence.STATED, nutrients = mapOf("calories" to kcal),
    )
    private fun lunch(day: Int, burritos: Double) = listOf(
        e("Veggie bag", at(day, 12, 0), 60.0),
        e("Greek yogurt", at(day, 12, 4), 100.0),
        e("Bean burrito", at(day, 12, 9), 300.0 * burritos),
    )
    private val now = at(19, 11, 50)

    @Test
    fun `the same foods on two days are one usual lunch, with the latest amounts`() {
        val log = lunch(10, 1.0) + lunch(14, 1.0) + lunch(18, 2.0) + e("Apple", at(18, 16, 0))
        val m = UsualMeals.of(log, now, zone).single()
        assertEquals("Your usual lunch", m.label)
        assertEquals(listOf("Veggie bag", "Greek yogurt", "Bean burrito"), m.names)
        assertEquals(3, m.days)
        assertEquals(760.0, m.calories, 1e-9)   // two burritos, as last time
        assertEquals(12.0, m.hour, 1e-9)          // its usual time, for the watch's "usual meal now"
    }

    @Test
    fun `once is not a habit, and far apart is not a sitting`() {
        assertTrue(UsualMeals.of(lunch(18, 1.0), now, zone).isEmpty())
        val apart = listOf(e("Veggie bag", at(17, 8, 0)), e("Greek yogurt", at(17, 12, 0)),
            e("Veggie bag", at(18, 8, 0)), e("Greek yogurt", at(18, 12, 0)))
        assertTrue(UsualMeals.of(apart, now, zone).isEmpty())
    }

    @Test
    fun `a smaller set inside the usual one is not offered twice`() {
        val log = lunch(10, 1.0) + lunch(14, 1.0) +
            listOf(e("Veggie bag", at(16, 12, 0)), e("Greek yogurt", at(16, 12, 3)))
        val meals = UsualMeals.of(log, now, zone)
        assertEquals(1, meals.size)
        assertEquals(3, meals.single().entries.size)
    }

    @Test
    fun `older than 30 days doesn't count, and labels follow the clock`() {
        val old = lunch(1, 1.0).map { it.copy(timestampEpochMillis = it.timestampEpochMillis - 40L * 86_400_000) } + lunch(18, 1.0)
        assertTrue(UsualMeals.of(old, now, zone).isEmpty())
        assertEquals("Your usual breakfast", UsualMeals.labelFor(7.5))
        assertEquals("Your usual dinner", UsualMeals.labelFor(19.0))
        assertEquals("Frozen Broccoli Florets", UsualMeals.short("Great Value — Frozen Broccoli Florets"))
    }
}
