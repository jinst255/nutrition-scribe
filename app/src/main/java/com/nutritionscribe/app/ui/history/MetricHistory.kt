package com.nutritionscribe.app.ui.history

import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.GoalDirection
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import com.nutritionscribe.shared.model.NutrientDay
import com.nutritionscribe.shared.model.NutrientTotal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min

/**
 * One day's total for one metric.
 *
 * [value] is null when nothing was recorded for this metric that day — which is
 * not the same as zero. The old chart filled every unlogged day with 0, so a
 * weekend off pulled the average down as if it had been a weekend of fasting.
 */
data class DayValue(
    val epochDay: Long,
    val value: Double?,
    /**
     * Food was logged but too little of it lists this nutrient to total it (a
     * micronutrient on a day of package labels). Shown as unknown, not as low.
     */
    val patchy: Boolean = false,
)

/** A bar on the chart: one day, one week, or one month. */
data class Bucket(
    val startEpochDay: Long,
    /** Inclusive. */
    val endEpochDay: Long,
    /** Average per recorded day in the bucket, or null if none were recorded. */
    val value: Double?,
    val recordedDays: Int,
    /** Days in the bucket that were logged but too patchy to total. */
    val patchyDays: Int = 0,
) {
    val days: Long get() = endEpochDay - startEpochDay + 1
}

enum class BucketSize { DAY, WEEK, MONTH }

/** How a day or bucket did against its goal: [com.nutritionscribe.shared.model.Limits.State]. */
typealias Standing = com.nutritionscribe.shared.model.Limits.State

/**
 * The part of the timeline on screen, in days.
 *
 * [endDay] is exclusive and fractional: a pan of half a bar is a real position,
 * not something to round away, or dragging would move in visible steps.
 */
data class Viewport(val endDay: Double, val spanDays: Double) {
    val startDay: Double get() = endDay - spanDays
}

object MetricHistory {

    const val MIN_SPAN_DAYS = 7.0

    fun dayOf(entry: Entry, zone: ZoneId): Long =
        com.nutritionscribe.shared.model.LogDays.dayOf(entry.timestampEpochMillis, zone).toEpochDay()

    /**
     * A contiguous run of days, from the first day this metric was ever recorded
     * up to [today], one element per day. Empty when it was never recorded.
     */
    fun daily(entries: List<Entry>, metric: Metric, zone: ZoneId, today: LocalDate): List<DayValue> {
        val byDay = HashMap<Long, Double>()
        for (entry in entries) {
            // Sugar is charted as ADDED sugar, the part its limit is for. Fruit and
            // milk made every day look over. Unsplit sugar counts as added, as on Home.
            val amount = if (metric == Metric.SUGAR) {
                if (Metric.SUGAR.key !in entry.nutrients) continue
                com.nutritionscribe.shared.resolve.Sugars.splitEntry(entry).let { it.added + it.unknown }
            } else entry.amountOf(metric) ?: continue
            val day = dayOf(entry, zone)
            byDay[day] = (byDay[day] ?: 0.0) + amount
        }
        // A micronutrient's day only counts when enough of its food lists it;
        // otherwise a day of labelled snacks reads as a deficiency.
        val patchy = HashSet<Long>()
        if (metric.isMicro) {
            entries.groupBy { dayOf(it, zone) }.forEach { (day, list) ->
                if (list.none { it.nutrients.isNotEmpty() }) return@forEach
                if (NutrientDay.coverage(list, metric) < NutrientTotal.TRUSTED_COVERAGE) {
                    patchy += day
                    byDay.remove(day)
                }
            }
        }
        if (byDay.isEmpty() && patchy.isEmpty()) return emptyList()
        val known = byDay.keys + patchy
        val first = known.min()
        val last = max(today.toEpochDay(), known.max())
        return (first..last).map { DayValue(it, byDay[it], patchy = it in patchy) }
    }

    /** Mean over the days that were actually recorded. Null when there were none. */
    fun average(days: List<DayValue>): Double? {
        val recorded = days.mapNotNull { it.value }
        return if (recorded.isEmpty()) null else recorded.average()
    }

    /** The last [n] days (by calendar, not by count of recorded days). */
    fun lastDays(days: List<DayValue>, n: Int): List<DayValue> = days.takeLast(n)

    /**
     * Bars wider than a day once the view is wide enough that daily bars would be
     * slivers. The thresholds keep roughly 7–60 bars on screen at every zoom.
     */
    fun bucketSizeFor(spanDays: Double): BucketSize = when {
        spanDays <= 62 -> BucketSize.DAY
        spanDays <= 400 -> BucketSize.WEEK
        else -> BucketSize.MONTH
    }

    /**
     * Groups [days] into calendar-aligned buckets.
     *
     * Aligned to the calendar — Monday-start weeks, real months — rather than
     * counted back from today, so a bucket's edges never move while panning. A
     * bucket window that slides with the view makes every bar change height as
     * you drag, which reads as the data changing.
     */
    fun buckets(days: List<DayValue>, size: BucketSize): List<Bucket> {
        if (days.isEmpty()) return emptyList()
        if (size == BucketSize.DAY) {
            return days.map { Bucket(it.epochDay, it.epochDay, it.value, if (it.value != null) 1 else 0, if (it.patchy) 1 else 0) }
        }
        return days.groupBy { bucketStart(it.epochDay, size) }.map { (start, group) ->
            val recorded = group.mapNotNull { it.value }
            Bucket(
                startEpochDay = start,
                endEpochDay = bucketEnd(start, size),
                value = if (recorded.isEmpty()) null else recorded.average(),
                recordedDays = recorded.size,
                patchyDays = group.count { it.patchy },
            )
        }
    }

    fun bucketStart(epochDay: Long, size: BucketSize): Long {
        val date = LocalDate.ofEpochDay(epochDay)
        return when (size) {
            BucketSize.DAY -> epochDay
            BucketSize.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()
            BucketSize.MONTH -> date.withDayOfMonth(1).toEpochDay()
        }
    }

    private fun bucketEnd(start: Long, size: BucketSize): Long = when (size) {
        BucketSize.DAY -> start
        BucketSize.WEEK -> start + 6
        BucketSize.MONTH -> LocalDate.ofEpochDay(start).let { it.withDayOfMonth(it.lengthOfMonth()) }.toEpochDay()
    }

    /**
     * Where a finished day stands, judged only by numbers the user chose (see
     * Limits): their own lines first, then a goal's target in its direction. Not a
     * goal and no lines is never judged, and is drawn grey.
     */
    fun standing(goal: MetricGoal?, value: Double): Standing =
        com.nutritionscribe.shared.model.Limits.judge(value, goal, dayOver = true)

    /**
     * [goal] as it stood on [day]: the target it had then (GoalTimeline), plus the
     * plasma extra for protein on a donation day and the day after. A metric that
     * isn't a goal keeps its reference line as it is.
     */
    fun goalOn(
        goal: MetricGoal,
        day: Long,
        changes: List<com.nutritionscribe.shared.model.GoalTimeline.Change>,
        plasmaExtra: (Long) -> Double = { 0.0 },
    ): MetricGoal {
        if (!goal.enabled) return goal
        val t = com.nutritionscribe.shared.model.GoalTimeline.targetOn(goal.metric.key, day, goal.target, changes) ?: return goal.copy(target = null)
        val extra = if (goal.metric == com.nutritionscribe.shared.model.Metric.PROTEIN) plasmaExtra(day) else 0.0
        return goal.copy(target = t + extra)
    }

    /** The key's line for a hatched stub (owner, 11-10: it looked like a greyed-out high bar). */
    const val PATCHY_KEY = "Hatched: logged, but too little of the day's food lists it"

    /** One target for a week or a month bar: the mean of its days' targets. */
    fun bucketTarget(start: Long, end: Long, goalOn: (Long) -> MetricGoal): Double? {
        val ts = (start..end).mapNotNull { goalOn(it).target }
        return if (ts.isEmpty()) null else ts.average()
    }

    /**
     * History's count line (review Hi1). "5 full days" was jargon, and nothing said
     * why a logged day was missing from it. Now it counts the days with entries,
     * and says how many of them are averaged and why the rest are hollow.
     * [logged] and [full] are epoch days; the range is the [range] days before [today].
     */
    fun daysLine(range: Int, today: LocalDate, logged: Set<Long>, full: Set<Long>): String {
        val days = (1..range).map { today.minusDays(it.toLong()).toEpochDay() }
        val nLogged = days.count { it in logged }
        val nFull = days.count { it in full }
        val loggedText = if (nLogged == 1) "1 day logged" else "$nLogged days logged"
        return when {
            // On the emulator, with three entries today, it said "No days logged".
            nLogged == 0 && today.toEpochDay() in logged -> "Only today so far · it joins once it's over"
            nLogged == 0 -> "No days logged in the last $range days"
            nFull == nLogged -> "$loggedText · today joins once it's over"
            else -> "$loggedText · $nFull averaged; the rest had under half your calories logged, so their bars are hollow"
        }
    }

    /**
     * Which colours the cards on screen actually use, so History's key lists only
     * those (review Hi3: it always listed all five, "Past your line" included for
     * people with no lines). Only finished, full days are coloured.
     */
    fun standingsInUse(
        cards: List<Pair<MetricGoal, List<DayValue>>>,
        full: Set<Long>,
        goalOn: (MetricGoal, Long) -> MetricGoal = { g, _ -> g },
    ): Set<Standing> =
        cards.flatMap { (g, days) -> days.filter { it.epochDay in full && it.value != null && !it.patchy }.map { standing(goalOn(g, it.epochDay), it.value!!) } }.toSet()

    /**
     * The days that count toward an average: over, and at least half the calorie
     * target logged (or counted anyway by the person). Today is left out until it
     * ends, and so is a day with a breakfast and nothing else: it made every
     * average read low (note S46).
     */
    fun fullDays(entries: List<Entry>, zone: ZoneId, today: LocalDate, calorieTarget: Double, countAnyway: Set<Long> = emptySet()): Set<Long> {
        val kcal = HashMap<Long, Double>()
        for (e in entries) kcal[dayOf(e, zone)] = (kcal[dayOf(e, zone)] ?: 0.0) + (e.calories ?: 0.0)
        val t = today.toEpochDay()
        return kcal.filter { (d, v) -> d < t && (v >= calorieTarget / 2 || d in countAnyway) }.keys
    }

    /** Mean over [days] that are both recorded and full. */
    fun averageFull(days: List<DayValue>, full: Set<Long>): Double? {
        val recorded = days.filter { it.epochDay in full }.mapNotNull { it.value }
        return if (recorded.isEmpty()) null else recorded.average()
    }

    /**
     * History round 2 (approved 11-20): the average over the last [range] days against
     * the [range] days before, in percent (+6 means up 6%). Full days only, like the
     * averages themselves; null when either stretch has none, or the earlier one was 0.
     */
    fun change(daily: List<DayValue>, full: Set<Long>, range: Int, today: LocalDate): Double? {
        val t = today.toEpochDay()
        val now = daily.filter { it.epochDay in (t - range + 1)..t }
        val before = daily.filter { it.epochDay in (t - 2 * range + 1)..(t - range) }
        val a = averageFull(now, full) ?: return null
        val b = averageFull(before, full)?.takeIf { it > 0 } ?: return null
        return (a - b) / b * 100
    }

    /** "▼ 4%", "▲ 6%", "same": arrows as well as colour (UI guidelines §5). */
    fun changeText(pct: Double): String = when {
        Math.round(pct) == 0L -> "same"
        pct < 0 -> "▼ ${Math.round(-pct)}%"
        else -> "▲ ${Math.round(pct)}%"
    }

    /** Longest span worth zooming out to: all of history, but never under a week. */
    fun maxSpan(firstDay: Long, today: Long): Double = max(MIN_SPAN_DAYS, (today - firstDay + 1).toDouble())

    /**
     * Pinch. [factor] > 1 spreads the fingers and zooms IN (fewer days).
     * [focus] is where between the chart's left (0) and right (1) edges the
     * fingers are, and the day under them stays under them — zooming around the
     * middle of the screen instead makes whatever you were pinching slide away.
     */
    fun zoom(vp: Viewport, factor: Float, focus: Float, firstDay: Long, today: Long): Viewport {
        if (factor <= 0f) return vp
        val newSpan = (vp.spanDays / factor).coerceIn(MIN_SPAN_DAYS, maxSpan(firstDay, today))
        val anchor = vp.startDay + vp.spanDays * focus
        val newEnd = anchor + newSpan * (1 - focus)
        return clamp(Viewport(newEnd, newSpan), firstDay, today)
    }

    /** Drag. Positive [deltaDays] moves the view back in time. */
    fun pan(vp: Viewport, deltaDays: Double, firstDay: Long, today: Long): Viewport =
        clamp(vp.copy(endDay = vp.endDay - deltaDays), firstDay, today)

    /**
     * Keeps the view on the data: never past today, and never so far back that
     * the first day leaves the right-hand side of the screen.
     */
    fun clamp(vp: Viewport, firstDay: Long, today: Long): Viewport {
        val latestEnd = today + 1.0
        val earliestEnd = min(firstDay + vp.spanDays, latestEnd)
        return vp.copy(endDay = vp.endDay.coerceIn(earliestEnd, latestEnd))
    }

    /** The most recent [span] days. */
    fun latest(span: Double, firstDay: Long, today: Long): Viewport {
        val s = span.coerceIn(MIN_SPAN_DAYS, maxSpan(firstDay, today))
        return Viewport(today + 1.0, s)
    }
}

/**
 * A number the way a person would say it: "1,940 kcal", "134 g", "4.5 g".
 * One decimal only under 10, where it still means something. Display only; the
 * default locale's grouping is what the reader expects to see.
 */
fun formatAmount(value: Double, metric: Metric, withUnit: Boolean = true): String {
    val number = if (value < 10 && metric.unit != "kcal" && value != Math.floor(value)) {
        String.format("%.1f", value)
    } else {
        String.format("%,d", Math.round(value))
    }
    return if (withUnit) "$number ${metric.unit}" else number
}
