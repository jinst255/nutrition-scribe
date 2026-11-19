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
        assertEquals(Standing.IN_RANGE, MetricHistory.standing(protein, 160.0))
        assertEquals(Standing.LOW, MetricHistory.standing(protein, 120.0))
        assertEquals(Standing.LOW, MetricHistory.standing(protein, 60.0))
    }

    @Test
    fun `limit goals score less as better`() {
        assertEquals(Standing.IN_RANGE, MetricHistory.standing(sodium, 1800.0))
        // Ceiling equal to target: there is no in-between band.
        assertEquals(Standing.PAST, MetricHistory.standing(sodium, 2400.0))
        val soft = sodium.copy(ceilingValue = 3000.0)
        assertEquals(Standing.OVER, MetricHistory.standing(soft, 2400.0))
    }

    @Test
    fun `a flipped direction is honoured`() {
        // Someone cutting treats calories as a limit.
        val cutting = MetricGoal(Metric.CALORIES, enabled = true, target = 1800.0, direction = GoalDirection.LIMIT)
        assertEquals(Standing.IN_RANGE, MetricHistory.standing(cutting, 1500.0))
        assertEquals(Standing.OVER, MetricHistory.standing(cutting, 2500.0))
    }

    @Test
    fun `no target means no judgement, except by a line the user typed`() {
        assertEquals(Standing.NOT_JUDGED, MetricHistory.standing(protein.copy(target = null, floorValue = null), 10.0))
        assertEquals(Standing.LOW, MetricHistory.standing(protein.copy(target = null), 10.0))
    }

    @Test
    fun `a metric that is not a goal is never red unless the user drew the line`() {
        // Sugar is a limit by default, but nobody said how much is too much.
        val sugar = MetricGoal(Metric.SUGAR, enabled = false, target = 50.0)
        assertEquals(Standing.NOT_JUDGED, MetricHistory.standing(sugar, 130.0))
        val theirs = sugar.copy(ceilingValue = 60.0)
        assertEquals(Standing.IN_RANGE, MetricHistory.standing(theirs, 40.0))
        assertEquals(Standing.PAST, MetricHistory.standing(theirs, 70.0))
        // A floor works the same way without the goal switched on.
        val fiber = MetricGoal(Metric.FIBER, enabled = false, target = 28.0, floorValue = 20.0)
        assertEquals(Standing.LOW, MetricHistory.standing(fiber, 12.0))
        assertEquals(Standing.IN_RANGE, MetricHistory.standing(fiber, 22.0))
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

    /**
     * Review Hi3: History's key always listed five colours ("Past your line",
     * "No line set"…) whatever the cards showed. It lists only the ones in use.
     */
    @Test
    fun `the history key lists only the colours the cards use`() {
        val days = listOf(DayValue(1, 160.0), DayValue(2, 90.0), DayValue(3, 999.0))
        val inUse = MetricHistory.standingsInUse(listOf(protein to days), full = setOf(1L, 2L))
        // Day 3 is not a full day (today, or half-logged): it isn't coloured, so it doesn't count.
        assertEquals(setOf(Standing.IN_RANGE, Standing.LOW), inUse)
    }

    @Test
    fun `no cards or no full days means an empty key`() {
        assertEquals(emptySet<Standing>(), MetricHistory.standingsInUse(emptyList(), setOf(1L)))
        assertEquals(emptySet<Standing>(), MetricHistory.standingsInUse(listOf(protein to listOf(DayValue(1, 160.0))), emptySet()))
    }

    @Test
    fun `days with nothing recorded or too little data are not coloured`() {
        val days = listOf(DayValue(1, null), DayValue(2, 3000.0, patchy = true))
        assertEquals(emptySet<Standing>(), MetricHistory.standingsInUse(listOf(sodium to days), setOf(1L, 2L)))
    }

    @Test
    fun `a metric with no goal or lines shows as no line set`() {
        val plain = MetricGoal(Metric.CARBS, enabled = false)
        assertEquals(setOf(Standing.NOT_JUDGED), MetricHistory.standingsInUse(listOf(plain to listOf(DayValue(1, 200.0))), setOf(1L)))
    }

    /**
     * Review Hi1: History said "5 full days" with nothing to say why a day that was
     * logged didn't count, or what the hollow bars meant.
     */
    @Test
    fun `the days line counts logged days and says why some are hollow`() {
        val today = LocalDate.of(2026, 11, 14)
        val d = { n: Int -> today.minusDays(n.toLong()).toEpochDay() }
        val logged = setOf(d(1), d(2), d(3), d(4))
        assertEquals("4 days logged · 3 averaged; the rest had under half your calories logged, so their bars are hollow",
            MetricHistory.daysLine(7, today, logged, setOf(d(1), d(2), d(3))))
        assertEquals("4 days logged · today joins once it's over", MetricHistory.daysLine(7, today, logged, logged))
        assertEquals("1 day logged · today joins once it's over", MetricHistory.daysLine(7, today, setOf(d(1)), setOf(d(1))))
        assertEquals("No days logged in the last 7 days", MetricHistory.daysLine(7, today, emptySet(), emptySet()))
    }

    @Test
    fun `days outside the range are not counted`() {
        val today = LocalDate.of(2026, 11, 14)
        val logged = setOf(today.minusDays(30).toEpochDay())
        assertEquals("No days logged in the last 7 days", MetricHistory.daysLine(7, today, logged, logged))
    }

    /** On the emulator, three entries today and nothing before: it said "No days logged". */
    @Test
    fun `only today logged says so`() {
        val today = LocalDate.of(2026, 11, 14)
        assertEquals("Only today so far · it joins once it's over",
            MetricHistory.daysLine(14, today, setOf(today.toEpochDay()), emptySet()))
    }

    /**
     * Owner, 11-04: "this line isn't actually correct on days I don't need plasma,
     * or if I were to change my goals." Each day is judged by its own target now.
     */
    @Test
    fun `a past day is judged by the target it had then`() {
        val goal = MetricGoal(Metric.PROTEIN, enabled = true, target = 160.0)
        val raised = listOf(com.nutritionscribe.shared.model.GoalTimeline.Change("protein", 200, 140.0, 160.0))
        // 150 g on day 150 met the 140 g target of the time; today's 160 g would call it short.
        assertEquals(140.0, MetricHistory.goalOn(goal, 150, raised).target!!, 0.0)
        assertEquals(Standing.IN_RANGE, MetricHistory.standing(MetricHistory.goalOn(goal, 150, raised), 150.0))
        assertEquals(160.0, MetricHistory.goalOn(goal, 250, raised).target!!, 0.0)
    }

    @Test
    fun `the plasma extra is only added on donation days, and only to protein`() {
        val protein = MetricGoal(Metric.PROTEIN, enabled = true, target = 160.0)
        val extra = { day: Long -> if (day == 10L) 18.0 else 0.0 }
        assertEquals(178.0, MetricHistory.goalOn(protein, 10, emptyList(), extra).target!!, 0.0)
        assertEquals(160.0, MetricHistory.goalOn(protein, 11, emptyList(), extra).target!!, 0.0)
        val calories = MetricGoal(Metric.CALORIES, enabled = true, target = 2200.0)
        assertEquals(2200.0, MetricHistory.goalOn(calories, 10, emptyList(), extra).target!!, 0.0)
    }

    @Test
    fun `a metric that isn't a goal keeps its line`() {
        val iron = MetricGoal(Metric.IRON, enabled = false, target = 8.0)
        assertEquals(8.0, MetricHistory.goalOn(iron, 10, listOf(com.nutritionscribe.shared.model.GoalTimeline.Change("iron", 50, 18.0, 8.0))).target!!, 0.0)
    }

    @Test
    fun `a week bar's target is the mean of its days`() {
        val goal = MetricGoal(Metric.PROTEIN, enabled = true, target = 160.0)
        val plasma = { day: Long -> if (day in 3L..4L) 21.0 else 0.0 }
        val t = MetricHistory.bucketTarget(0, 6) { MetricHistory.goalOn(goal, it, emptyList(), plasma) }!!
        assertEquals(160.0 + 42.0 / 7, t, 1e-9)
    }

    /** Owner, 11-10: a hatched stub "seemed pretty high, why is it greyed out?" The key now says. */
    @Test
    fun `the key explains the hatched stub`() {
        assertTrue(MetricHistory.PATCHY_KEY.startsWith("Hatched"))
    }

    /** On the emulator the card said "11 of 12 days at 160 g" while judging by each day's own target. */
    @Test
    fun `when the target moved, the card counts days that met their own goal`() {
        val goal = MetricGoal(Metric.PROTEIN, enabled = true, target = 160.0)
        val days = listOf(DayValue(1, 150.0), DayValue(2, 165.0), DayValue(3, 120.0))
        val raisedOnDay3 = listOf(com.nutritionscribe.shared.model.GoalTimeline.Change("protein", 3, 140.0, 160.0))
        val line = cardStatus(goal, days, setOf(1L, 2L, 3L)) { MetricHistory.goalOn(goal, it, raisedOnDay3) }
        assertEquals("2 of 3 days met that day's goal", line)
        // Steady target: the old wording, with the number.
        assertEquals("1 of 3 days at 160 g or more", cardStatus(goal, days, setOf(1L, 2L, 3L)))
    }
}
