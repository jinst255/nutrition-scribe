package com.nutritionscribe.shared.model

import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.MicroFill
import com.nutritionscribe.shared.resolve.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NutrientDayTest {

    private fun item(name: String, kcal: Double, vararg n: Pair<String, Double>) =
        LoggedItem(name = name, said = name, key = name, origin = "USDA", nutrients = mapOf("calories" to kcal) + n)

    private fun entry(vararg items: LoggedItem, at: Long = 0L, name: String = items.first().name) = Entry(
        id = name + at, timestampEpochMillis = at, rawText = name, name = name,
        nutrients = items.flatMap { it.nutrients.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() },
        source = "usda", confidence = Confidence.ESTIMATED, items = items.toList(),
    )

    @Test
    fun `coverage is the share of calories whose food lists the nutrient`() {
        val day = listOf(
            entry(item("Milk", 300.0, "calcium" to 600.0)),
            entry(item("Protein bar", 100.0)),
        )
        val t = NutrientDay.total(day, Metric.CALCIUM)
        assertEquals(600.0, t.amount, 0.0)
        assertEquals(0.75, t.coverage, 1e-9)
        assertTrue(t.trustworthy)
        assertEquals(listOf("Protein bar"), NutrientDay.unknownFor(day, Metric.CALCIUM))
    }

    @Test
    fun `a day of undocumented food is not a low number`() {
        val day = listOf(entry(item("Protein bar", 200.0)), entry(item("Egg", 70.0, "vitamin_d" to 1.0)))
        assertFalse(NutrientDay.total(day, Metric.VITAMIN_D).trustworthy)
    }

    @Test
    fun `sources are ranked largest first and items within an entry count separately`() {
        val day = listOf(entry(item("Milk", 150.0, "calcium" to 300.0), item("Cheese", 110.0, "calcium" to 200.0), name = "Snack"),
            entry(item("Milk", 150.0, "calcium" to 300.0), at = 5))
        assertEquals(listOf("Milk" to 600.0, "Cheese" to 200.0), NutrientDay.sources(day, Metric.CALCIUM))
    }

    @Test
    fun `micro fill adds only missing micronutrients and never touches macros`() {
        val usda = FoodCandidate(id = "usda:1", name = "Egg", origin = Origin.USDA,
            per100g = mapOf("calories" to 999.0, "protein" to 999.0, "calcium" to 50.0, "vitamin_d" to 2.0))
        val logged = LoggedItem(name = "Egg", said = "2 eggs", key = "egg", origin = "USDA", candidateId = "usda:1", grams = 100.0,
            nutrients = mapOf("calories" to 143.0, "protein" to 12.6, "vitamin_d" to 1.5))
        val e = entry(logged).copy(items = listOf(logged), nutrients = logged.nutrients)
        val filled = MicroFill.fill(e, { if (it == "usda:1") usda else null })!!
        assertEquals(143.0, filled.nutrients["calories"]!!, 0.0)
        assertEquals(12.6, filled.nutrients["protein"]!!, 0.0)
        assertEquals(1.5, filled.nutrients["vitamin_d"]!!, 0.0) // already there: kept
        assertEquals(50.0, filled.nutrients["calcium"]!!, 1e-9)
        assertNull(MicroFill.fill(filled, { if (it == "usda:1") usda else null }))
    }

    @Test
    fun `an old AI entry is filled from a same-food match by name, and only then`() {
        val banana = FoodCandidate(id = "usda:9", name = "Bananas, raw", origin = Origin.USDA,
            per100g = mapOf("calories" to 89.0, "protein" to 1.1, "potassium" to 358.0))
        val ai = LoggedItem(name = "banana", said = "a banana", key = "banana", origin = "AI", candidateId = "ai:1", grams = 118.0,
            nutrients = mapOf("calories" to 105.0, "protein" to 1.3))
        val e = entry(ai).copy(items = listOf(ai), nutrients = ai.nutrients)
        val filled = MicroFill.fill(e, { null }, { listOf(banana) })!!
        assertEquals(105.0, filled.nutrients["calories"]!!, 0.0)
        assertEquals(358.0 * 1.18, filled.nutrients["potassium"]!!, 1e-9)
        // Banana bread is not a banana, whatever the search returns.
        val bread = ai.copy(nutrients = mapOf("calories" to 390.0, "protein" to 5.0))
        assertNull(MicroFill.fill(entry(bread).copy(items = listOf(bread)), { null }, { listOf(banana) }))
    }

    @Test
    fun `log again puts what you usually have now ahead of this morning's breakfast`() {
        val zone = ZoneId.of("America/Chicago")
        fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 9, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
        val now = at(26, 18, 30)
        val entries = listOf(
            entry(item("Oatmeal", 300.0), at = at(26, 8)), // this morning
            entry(item("Oatmeal", 300.0), at = at(25, 8)),
            entry(item("Oatmeal", 300.0), at = at(24, 8)),
            entry(item("Chicken rice bowl", 610.0), at = at(24, 18, 45)),
            entry(item("Chicken rice bowl", 610.0), at = at(22, 19, 10)),
        )
        val ranked = LogAgain.smart(LogAgain.choices(entries, now, zone), now)
        assertEquals("Chicken rice bowl", ranked.first().entry.name)
        assertEquals("Oatmeal", LogAgain.recent(LogAgain.choices(entries, now, zone)).first().entry.name)
        assertEquals(3, LogAgain.often(LogAgain.choices(entries, now, zone)).first().count)
    }
}
