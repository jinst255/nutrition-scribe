package com.nutritionscribe.shared.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Owner, 2026-11-02: Ask was asked for "the biggest sodium sources across
 * everything I've logged" and answered that it couldn't see past logs. The phone
 * now works the answer out of the whole log for it.
 */
class LogQueryTest {
    private val zone = ZoneId.of("America/Denver")
    private val today = LocalDate.of(2026, 11, 14)

    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun entry(id: String, day: LocalDate, hour: Int, vararg items: Pair<String, Double>) = Entry(
        id = id, timestampEpochMillis = at(day, hour), rawText = "", name = items.joinToString { it.first }, source = "test",
        confidence = Confidence.ESTIMATED, nutrients = mapOf("sodium" to items.sumOf { it.second }),
        items = items.map { (n, s) -> LoggedItem(name = n, said = n, key = n, origin = "USDA", nutrients = mapOf("sodium" to s)) },
    )

    private val log = listOf(
        entry("1", today.minusDays(40), 8, "Cottage cheese, NFS" to 450.0, "Pineapple, raw" to 1.0),
        entry("2", today.minusDays(20), 12, "Cottage cheese, NFS" to 450.0, "Bread, whole wheat" to 260.0),
        entry("3", today.minusDays(2), 18, "Bacon, cooked" to 580.0),
        entry("4", today, 19, "Cottage cheese, NFS" to 450.0),
    )

    @Test
    fun `the biggest sources across everything, by food, with short names`() {
        val out = LogQuery.run(log, LogQuery.Request("sodium"), zone, today)
        val lines = out.lines()
        assertTrue(out, lines[0].contains("4 entries on 4 logged days"))
        assertTrue(out, lines[1].startsWith("1. Cottage cheese: 1,350 mg over 3×"))
        assertTrue(out, lines[2].startsWith("2. Bacon"))
        assertTrue("an all-time question isn't cut to today", out.contains("Whole wheat bread"))
    }

    @Test
    fun `a span of days, the lowest days, by week and by meal time`() {
        val lastWeek = LogQuery.run(log, LogQuery.Request("sodium", from = today.minusDays(7)), zone, today)
        assertTrue(lastWeek, !lastWeek.contains("bread") && lastWeek.contains("Bacon"))
        val low = LogQuery.run(log, LogQuery.Request("sodium", groupBy = LogQuery.GroupBy.DAY, lowest = true, top = 1), zone, today)
        assertTrue(low, low.lines()[1].contains("Sat Nov 14: 450 mg"))
        assertTrue(LogQuery.run(log, LogQuery.Request("sodium", groupBy = LogQuery.GroupBy.WEEK), zone, today).contains("Week of"))
        assertTrue(LogQuery.run(log, LogQuery.Request("sodium", groupBy = LogQuery.GroupBy.MEAL_TIME), zone, today).contains("Dinner"))
    }

    @Test
    fun `nothing logged, or a metric that doesn't exist, says so`() {
        assertTrue(LogQuery.run(emptyList(), LogQuery.Request("sodium"), zone, today).startsWith("No entries"))
        assertTrue(LogQuery.run(log, LogQuery.Request("salt"), zone, today).startsWith("Unknown metric"))
    }
}
