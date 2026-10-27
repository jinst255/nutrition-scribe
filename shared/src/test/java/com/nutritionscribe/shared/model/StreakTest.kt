package com.nutritionscribe.shared.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class StreakTest {
    private val today = LocalDate.of(2026, 10, 25) // a Sunday

    @After fun reset() { LogDays.endHour = 3 }

    @Test fun `a missed day uses the week's rest day and the run goes on`() {
        val counted = (6..25).map { LocalDate.of(2026, 10, it) }.filter { it.dayOfMonth != 9 }.toSet()
        val r = Streak.compute(counted, today, restPerWeek = 1)
        assertEquals(20, r.current)
        assertEquals(Streak.DayState.REST, r.state(LocalDate.of(2026, 10, 9)))
    }

    @Test fun `two misses in one week break the run but best remembers it`() {
        val counted = (1..20).map { LocalDate.of(2026, 10, it) }.filter { it.dayOfMonth !in setOf(21, 22) }.toSet() +
            setOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25))
        val r = Streak.compute(counted, today, restPerWeek = 1)
        assertEquals(2, r.current)
        assertEquals(21, r.best) // Oct 1-20 plus the rest day on the 21st
        assertEquals(Streak.DayState.MISSED, r.state(LocalDate.of(2026, 10, 22)))
    }

    @Test fun `today not logged yet is pending, never a miss`() {
        val counted = (20..24).map { LocalDate.of(2026, 10, it) }.toSet()
        val r = Streak.compute(counted, today, restPerWeek = 0)
        assertEquals(5, r.current)
        assertEquals(Streak.DayState.PENDING, r.state(today))
    }

    @Test fun `nothing logged is no streak`() {
        val r = Streak.compute(emptySet(), today, 1)
        assertEquals(0, r.current)
        assertNull(r.bestMonth)
    }

    @Test fun `a snack after midnight belongs to the day before`() {
        val zone = ZoneOffset.UTC
        val t = LocalDate.of(2026, 10, 26).atTime(0, 30).toInstant(zone).toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 25), LogDays.dayOf(t, zone))
        LogDays.endHour = 0
        assertEquals(LocalDate.of(2026, 10, 26), LogDays.dayOf(t, zone))
    }

    @Test fun `three times means two hours apart`() {
        val zone = ZoneOffset.UTC
        fun e(id: String, h: Int, m: Int = 0) = Entry(
            id = id, timestampEpochMillis = today.atTime(h, m).toInstant(zone).toEpochMilli(), rawText = id, name = id,
            nutrients = mapOf("calories" to 100.0), source = "test", confidence = Confidence.ESTIMATED,
        )
        val close = listOf(e("a", 8), e("b", 9), e("c", 10, 30))
        assertEquals(emptySet<LocalDate>(), Streak.countedDays(close, Streak.Rule.THREE, emptyList(), today, zone))
        val spread = listOf(e("a", 8), e("b", 12), e("c", 18))
        assertEquals(setOf(today), Streak.countedDays(spread, Streak.Rule.THREE, emptyList(), today, zone))
    }
}

class LimitsTest {
    private fun goal(target: Double?, dir: GoalDirection, ceiling: Double? = null, floor: Double? = null, enabled: Boolean = true) =
        MetricGoal(Metric.SODIUM, enabled, target, dir, floorValue = floor, ceilingValue = ceiling)

    @Test fun `stay under with a line goes green, sand, rose`() {
        val g = goal(2300.0, GoalDirection.LIMIT, ceiling = 3000.0)
        assertEquals(Limits.State.IN_RANGE, Limits.judge(2000.0, g, false))
        assertEquals(Limits.State.OVER, Limits.judge(2450.0, g, false))
        assertEquals(Limits.State.PAST, Limits.judge(3100.0, g, false))
    }

    @Test fun `no goal and no line is not judged`() {
        assertEquals(Limits.State.NOT_JUDGED, Limits.judge(9000.0, goal(2300.0, GoalDirection.LIMIT, enabled = false), true))
        assertEquals(Limits.State.NOT_JUDGED, Limits.judge(9000.0, null, true))
    }

    @Test fun `too low only once the day is over`() {
        val g = goal(120.0, GoalDirection.REACH)
        assertEquals(Limits.State.IN_RANGE, Limits.judge(60.0, g, dayOver = false))
        assertEquals(Limits.State.LOW, Limits.judge(60.0, g, dayOver = true))
    }

    @Test fun `ticks appear only once over the target`() {
        assertNull(Limits.bar(2000.0, 2300.0, 3000.0).targetTick)
        val b = Limits.bar(2450.0, 2300.0, 3000.0)
        assertEquals(2300.0 / 3240.0, b.targetTick!!.toDouble(), 0.01)
        assertEquals(3000.0 / 3240.0, b.lineTick!!.toDouble(), 0.01)
    }
}
