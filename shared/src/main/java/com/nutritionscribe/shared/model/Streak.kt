package com.nutritionscribe.shared.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Days logged in a row.
 *
 * Built to encourage, not to punish: a missed day uses a rest day by itself (one a
 * week unless changed), "best" sits next to the current run so a reset never
 * erases what was done, and today is never a miss until it is over.
 */
object Streak {
    /** What makes a day count. [key] is persisted. */
    enum class Rule(val key: String, val label: String, val sub: String?) {
        ANY("any", "Log anything", "The default"),
        THREE("three", "Log 3 times, 2 hours apart", null),
        PROTEIN("protein", "Reach my protein goal", null),
        HEALTHY("healthy", "Have a healthy day", "Every goal in range, judged when the day ends");

        companion object {
            fun fromKey(k: String?): Rule = entries.firstOrNull { it.key == k } ?: ANY
        }
    }

    enum class DayState { COUNTED, REST, MISSED, PENDING, BEFORE }

    data class Result(
        val current: Int,
        val best: Int,
        /** The month the best run ended in, or null when there is no run yet. */
        val bestMonth: YearMonth?,
        val days: Map<LocalDate, DayState>,
        val restPerWeek: Int,
    ) {
        fun state(d: LocalDate): DayState = days[d] ?: DayState.BEFORE
    }

    /**
     * [counted] is every day that met the rule. Today counts if it already has;
     * if not, it is [DayState.PENDING] and the run from yesterday still stands.
     */
    fun compute(counted: Set<LocalDate>, today: LocalDate, restPerWeek: Int): Result {
        val first = counted.minOrNull() ?: return Result(0, 0, null, emptyMap(), restPerWeek)
        val days = LinkedHashMap<LocalDate, DayState>()
        var run = 0
        var best = 0
        var bestEnd: LocalDate? = null
        val restUsed = HashMap<LocalDate, Int>()
        var d = first
        while (!d.isAfter(today)) {
            val week = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            when {
                d in counted -> { run++; days[d] = DayState.COUNTED }
                d == today -> days[d] = DayState.PENDING
                run > 0 && (restUsed[week] ?: 0) < restPerWeek -> {
                    restUsed[week] = (restUsed[week] ?: 0) + 1
                    run++
                    days[d] = DayState.REST
                }
                else -> { run = 0; days[d] = DayState.MISSED }
            }
            if (run > best) { best = run; bestEnd = d }
            d = d.plusDays(1)
        }
        return Result(run, best, bestEnd?.let { YearMonth.from(it) }, days, restPerWeek)
    }

    /** The days that meet [rule], from the log. [goalsMet] judges a finished day for [Rule.HEALTHY]. */
    fun countedDays(
        entries: List<Entry>,
        rule: Rule,
        goals: List<MetricGoal>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Set<LocalDate> {
        val byDay = entries.groupBy { LogDays.dayOf(it.timestampEpochMillis, zone) }
        return byDay.filter { (day, list) ->
            when (rule) {
                Rule.ANY -> list.isNotEmpty()
                Rule.THREE -> {
                    val times = list.filterNot { it.timeUnspecified }.map { it.timestampEpochMillis }.sorted()
                    var n = 0
                    var last = Long.MIN_VALUE
                    for (t in times) if (n == 0 || t - last >= 2 * 3_600_000L) { n++; last = t }
                    n >= 3
                }
                Rule.PROTEIN -> {
                    val target = goals.firstOrNull { it.metric == Metric.PROTEIN }?.target ?: Metric.PROTEIN.defaultTarget
                    list.sumOf { it.amountOf(Metric.PROTEIN) ?: 0.0 } >= target
                }
                Rule.HEALTHY -> day != today && healthy(list, goals)
            }
        }.keys
    }

    private fun healthy(list: List<Entry>, goals: List<MetricGoal>): Boolean {
        val on = goals.filter { it.enabled }
        if (on.isEmpty()) return list.isNotEmpty()
        val totals = NutrientDay.totals(list)
        return on.all { g ->
            val amount = totals[g.metric.goalMeasure]?.amount ?: 0.0
            val st = Limits.judge(amount, g, dayOver = true)
            st == Limits.State.IN_RANGE || st == Limits.State.NOT_JUDGED
        }
    }
}
