package com.nutritionscribe.app.ui.history

import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.GoalDirection
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class MetricHistoryTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 9, 22) // a Tuesday

    private fun entry(date: LocalDate, protein: Double? = null, calories: Double? = null, hour: Int = 12) = Entry(
        id = "$date-$hour-$protein-$calories",
        timestampEpochMillis = date.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        rawText = "x",
        name = "x",
        nutrients = buildMap {
            protein?.let { put(Metric.PROTEIN.key, it) }
            calories?.let { put(Metric.CALORIES.key, it) }
        },
        source = "manual",
        confidence = Confidence.STATED,
    )

    @Test
    fun `a day with nothing logged is unknown, not zero`() {
        // The old chart wrote 0 for every unlogged day, so one day off halved a
        // two-day average.
        val days = MetricHistory.daily(
            listOf(entry(today.minusDays(2), protein = 100.0), entry(today, protein = 100.0)),
            Metric.PROTEIN, zone, today,
        )
        assertEquals(3, days.size)
        assertNull(days[1].value)
        assertEquals(100.0, MetricHistory.average(days)!!, 0.001)
    }

    @Test
    fun `entries on the same day add up`() {
        val days = MetricHistory.daily(
            listOf(entry(today, protein = 30.0, hour = 8), entry(today, protein = 45.0, hour = 19)),
            Metric.PROTEIN, zone, today,
        )
        assertEquals(75.0, days.last().value!!, 0.001)
    }

    @Test
    fun `each metric only counts entries that recorded it`() {
        // An old entry with protein but no calories must not appear as a 0 kcal day.
        val entries = listOf(entry(today.minusDays(1), protein = 40.0), entry(today, protein = 20.0, calories = 500.0))
        val cal = MetricHistory.daily(entries, Metric.CALORIES, zone, today)
        assertEquals(1, cal.size)
        assertEquals(500.0, MetricHistory.average(cal)!!, 0.001)
    }

    @Test
    fun `the series runs up to today even if today is empty`() {
        val days = MetricHistory.daily(listOf(entry(today.minusDays(3), protein = 10.0)), Metric.PROTEIN, zone, today)
        assertEquals(today.toEpochDay(), days.last().epochDay)
        assertNull(days.last().value)
    }

    @Test
    fun `weeks start on Monday and do not move with the view`() {
        val days = (0L..20L).map { DayValue(today.minusDays(it).toEpochDay(), 10.0) }.reversed()
        val weeks = MetricHistory.buckets(days, BucketSize.WEEK)
        weeks.forEach {
            assertEquals(java.time.DayOfWeek.MONDAY, LocalDate.ofEpochDay(it.startEpochDay).dayOfWeek)
            assertEquals(7, it.days)
        }
        // Dropping the oldest day changes only the first bucket's membership.
        val shifted = MetricHistory.buckets(days.drop(1), BucketSize.WEEK)
        assertEquals(weeks.last(), shifted.last())
    }

    @Test
    fun `a bucket averages only its recorded days`() {
        val monday = LocalDate.of(2026, 9, 14).toEpochDay()
        val days = (0L..6L).map { DayValue(monday + it, if (it < 2) 100.0 else null) }
        val week = MetricHistory.buckets(days, BucketSize.WEEK).single()
        assertEquals(100.0, week.value!!, 0.001)
        assertEquals(2, week.recordedDays)
    }

    @Test
    fun `months follow the calendar`() {
        val start = LocalDate.of(2026, 2, 1).toEpochDay()
        val days = (0L..40L).map { DayValue(start + it, 1.0) }
        val months = MetricHistory.buckets(days, BucketSize.MONTH)
        assertEquals(2, months.size)
        assertEquals(28, months[0].days)
        assertEquals(31, months[1].days)
    }

    @Test
    fun `bars get wider as the view widens`() {
        assertEquals(BucketSize.DAY, MetricHistory.bucketSizeFor(30.0))
        assertEquals(BucketSize.WEEK, MetricHistory.bucketSizeFor(180.0))
        assertEquals(BucketSize.MONTH, MetricHistory.bucketSizeFor(900.0))
    }

    // ---- standing: direction matters -------------------------------------

    private val protein = MetricGoal(Metric.PROTEIN, enabled = true, target = 150.0, floorValue = 110.0)
    private val sodium = MetricGoal(Metric.SODIUM, enabled = true, target = 2300.0, ceilingValue = 2300.0)

    @Test
    fun `reach goals score more as better`() {
        assertEquals(Standing.MET, MetricHistory.standing(protein, 160.0))
        assertEquals(Standing.SHORT, MetricHistory.standing(protein, 120.0))
        assertEquals(Standing.MISSED, MetricHistory.standing(protein, 60.0))
    }

    @Test
    fun `limit goals score less as better`() {
        assertEquals(Standing.MET, MetricHistory.standing(sodium, 1800.0))
        // Ceiling equal to target: there is no in-between band.
        assertEquals(Standing.MISSED, MetricHistory.standing(sodium, 2400.0))
        val soft = sodium.copy(ceilingValue = 3000.0)
        assertEquals(Standing.SHORT, MetricHistory.standing(soft, 2400.0))
    }

    @Test
    fun `a flipped direction is honoured`() {
        // Someone cutting treats calories as a limit.
        val cutting = MetricGoal(Metric.CALORIES, enabled = true, target = 1800.0, direction = GoalDirection.LIMIT)
        assertEquals(Standing.MET, MetricHistory.standing(cutting, 1500.0))
        assertEquals(Standing.MISSED, MetricHistory.standing(cutting, 2500.0))
    }

    @Test
    fun `no target means no judgement, except by a line the user typed`() {
        assertEquals(Standing.NO_GOAL, MetricHistory.standing(protein.copy(target = null, floorValue = null), 10.0))
        assertEquals(Standing.MISSED, MetricHistory.standing(protein.copy(target = null), 10.0))
    }

    @Test
    fun `a metric that is not a goal is never red unless the user drew the line`() {
        // Sugar is a limit by default, but nobody said how much is too much.
        val sugar = MetricGoal(Metric.SUGAR, enabled = false, target = 50.0)
        assertEquals(Standing.NO_GOAL, MetricHistory.standing(sugar, 130.0))
        val theirs = sugar.copy(ceilingValue = 60.0)
        assertEquals(Standing.MET, MetricHistory.standing(theirs, 40.0))
        assertEquals(Standing.MISSED, MetricHistory.standing(theirs, 70.0))
        // A floor works the same way without the goal switched on.
        val fiber = MetricGoal(Metric.FIBER, enabled = false, target = 28.0, floorValue = 20.0)
        assertEquals(Standing.MISSED, MetricHistory.standing(fiber, 12.0))
        assertEquals(Standing.MET, MetricHistory.standing(fiber, 22.0))
    }

    // ---- viewport ---------------------------------------------------------

    private val first = today.minusDays(199).toEpochDay()
    private val t = today.toEpochDay()

    @Test
    fun `pinching keeps the day under your fingers in place`() {
        val vp = MetricHistory.latest(60.0, first, t)
        val focus = 0.25f
        val before = vp.startDay + vp.spanDays * focus
        val zoomed = MetricHistory.zoom(vp, 2f, focus, first, t)
        assertEquals(30.0, zoomed.spanDays, 0.001)
        assertEquals(before, zoomed.startDay + zoomed.spanDays * focus, 0.001)
    }

    @Test
    fun `zoom stops at a week and at all of history`() {
        val vp = MetricHistory.latest(30.0, first, t)
        assertEquals(7.0, MetricHistory.zoom(vp, 100f, 0.5f, first, t).spanDays, 0.001)
        assertEquals(200.0, MetricHistory.zoom(vp, 0.001f, 0.5f, first, t).spanDays, 0.001)
    }

    @Test
    fun `panning cannot run past today or before the first day`() {
        val vp = MetricHistory.latest(30.0, first, t)
        assertEquals(t + 1.0, MetricHistory.pan(vp, -50.0, first, t).endDay, 0.001)
        val farBack = MetricHistory.pan(vp, 10_000.0, first, t)
        assertEquals(first.toDouble(), farBack.startDay, 0.001)
    }

    @Test
    fun `a short history stays pinned to today`() {
        // Four days logged, a week on screen: nothing to pan to.
        val recent = t - 3
        val vp = MetricHistory.latest(7.0, recent, t)
        assertEquals(t + 1.0, MetricHistory.pan(vp, 5.0, recent, t).endDay, 0.001)
        assertTrue(vp.spanDays >= 7.0)
    }
}
