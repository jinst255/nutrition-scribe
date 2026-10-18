package com.nutritionscribe.shared.model

import com.nutritionscribe.shared.resolve.TestDbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class IdeasTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 3, 10)

    private fun at(daysAgo: Long, hour: Int) = today.minusDays(daysAgo).atTime(hour, 0).toInstant(zone).toEpochMilli()

    private fun entry(id: String, daysAgo: Long, hour: Int, name: String, n: Map<String, Double>, items: Int = 2) = Entry(
        id = id, timestampEpochMillis = at(daysAgo, hour), rawText = name, name = name, nutrients = n,
        source = "test", confidence = Confidence.ESTIMATED,
        items = (1..items).map { LoggedItem(name = "$name part $it", said = name, key = "k$it", origin = "USDA", nutrients = n.mapValues { v -> v.value / items }) },
    )

    private val per100 = { id: Long -> TestDbs.usda.byId(id)?.per100g }
    private val needs = Needs.forProfile(null)

    /** A made-up week: the same low-fibre oats breakfast, a soda each day, and a big dinner. */
    private fun week(days: Int) = (1..days).flatMap { d ->
        listOf(
            entry("b$d", d.toLong(), 8, "Overnight oats", mapOf("calories" to 600.0, "fiber" to 6.0, "sugar" to 30.0, "protein" to 20.0)),
            entry("s$d", d.toLong(), 15, "Cola", mapOf("calories" to 200.0, "sugar" to 52.0, "caffeine" to 450.0), items = 1),
            entry("d$d", d.toLong(), 19, "Pasta dinner", mapOf("calories" to 900.0, "fiber" to 4.0, "protein" to 30.0, "sodium" to 900.0)),
        )
    }

    @Test
    fun `nothing is said before three full days`() {
        val r = Ideas.build(week(2), needs, today, zone, per100)
        assertFalse(r.ready)
        assertTrue(r.ideas.isEmpty())
        assertEquals(2, r.fullDays)
    }

    @Test
    fun `a week short on fibre and over on caffeine says so, with sources`() {
        val r = Ideas.build(week(5), needs, today, zone, per100)
        assertTrue(r.ready)
        val fibre = r.ideas.first { it.id == "short:fiber" }
        assertTrue(fibre.title, fibre.title.startsWith("Fiber averaged 10"))
        assertTrue(fibre.foods.toString(), fibre.foods.isNotEmpty())
        assertEquals(10.0, fibre.average!!, 1.0)
        assertTrue(fibre.source.contains("Dietary Guidelines"))
        val caffeine = r.ideas.first { it.id == "over:caffeine" }
        assertTrue(caffeine.foods.toString(), caffeine.foods.any { "Cola" in it })
        assertTrue(caffeine.source.contains("Food and Drug"))
        assertTrue(r.store.isNotEmpty())
    }

    @Test
    fun `a refresh suggests other foods`() {
        val a = Ideas.build(week(5), needs, today, zone, per100).ideas.first { it.id == "short:fiber" }.foods
        val b = Ideas.build(week(5), needs, today, zone, per100, variant = 1).ideas.first { it.id == "short:fiber" }.foods
        assertTrue("$a / $b", a != b)
    }

    @Test
    fun `a usual breakfast gets fruit, not broccoli`() {
        val r = Ideas.build(week(5), needs, today, zone, per100)
        val meal = r.ideas.firstOrNull { it.kind == Ideas.Kind.MEAL && it.title.startsWith("Overnight oats") }
        assertTrue(r.ideas.map { it.title }.toString(), meal != null)
        assertTrue(meal!!.title, Ideas.FOODS.filter { it.kind == Ideas.Food.Kind.FRUIT || it.kind == Ideas.Food.Kind.SEED }
            .any { it.name.lowercase() in meal.title.lowercase() })
    }

    @Test
    fun `an idea said no to stays gone`() {
        val r = Ideas.build(week(5), needs, today, zone, per100, dismissed = setOf("over:caffeine"))
        assertTrue(r.ideas.none { it.id == "over:caffeine" })
    }

    @Test
    fun `a half-logged day is not a shortage`() {
        // Five days with only a 300 kcal breakfast: not full days, so nothing to judge.
        val sparse = (1..5).map { d -> entry("x$d", d.toLong(), 8, "Toast", mapOf("calories" to 300.0, "fiber" to 1.0)) }
        assertFalse(Ideas.build(sparse, needs, today, zone, per100).ready)
    }
}
