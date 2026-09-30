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

/** How a day or bucket did against its goal. Drives every colour on the History tab. */
enum class Standing { MET, SHORT, MISSED, NO_GOAL }

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
        Instant.ofEpochMilli(entry.timestampEpochMillis).atZone(zone).toLocalDate().toEpochDay()

    /**
     * A contiguous run of days, from the first day this metric was ever recorded
     * up to [today], one element per day. Empty when it was never recorded.
     */
    fun daily(entries: List<Entry>, metric: Metric, zone: ZoneId, today: LocalDate): List<DayValue> {
        val byDay = HashMap<Long, Double>()
        for (entry in entries) {
            val amount = entry.amountOf(metric) ?: continue
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
     * Where a value stands, judged only by numbers the user chose.
     *
     * Their own lines first: under a floor or over a ceiling they typed is MISSED,
     * whether or not the metric is a goal. Then, for a goal with a target, its
     * direction: for something to reach, at the target is MET and under it SHORT;
     * for something to stay under, at or below is MET, over it SHORT when they
     * set a ceiling above it (still under that) and MISSED when they did not.
     * A metric that is not a goal and has no lines is never judged: NO_GOAL, which
     * is drawn the same green as a good day.
     */
    fun standing(goal: MetricGoal?, value: Double): Standing {
        if (goal == null) return Standing.NO_GOAL
        goal.floorValue?.let { if (value < it) return Standing.MISSED }
        goal.ceilingValue?.let { if (value > it) return Standing.MISSED }
        val target = goal.target?.takeIf { goal.enabled }
            ?: return if (goal.floorValue != null || goal.ceilingValue != null) Standing.MET else Standing.NO_GOAL
        return when (goal.effectiveDirection) {
            GoalDirection.REACH -> if (value >= target) Standing.MET else Standing.SHORT
            GoalDirection.LIMIT -> when {
                value <= target -> Standing.MET
                goal.ceilingValue != null -> Standing.SHORT
                else -> Standing.MISSED
            }
        }
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
