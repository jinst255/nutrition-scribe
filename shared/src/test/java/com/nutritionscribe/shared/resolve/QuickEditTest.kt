package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickEditTest {

    private val bread = Changes.Line("Wheat bread", "wheat bread", "slice")
    private val butter = Changes.Line("Salted butter", "butter", "tbsp")
    private val cheese = Changes.Line("Cheddar slices", "cheddar cheese", "slice")
    private val avocado = Changes.Line("Avocado, raw", "avocado", null)
    private val milk = Changes.Line("Whole milk", "whole milk", "cup")

    private fun interpret(text: String, vararg lines: Changes.Line) =
        Changes.interpret(text, lines.toList(), { w -> TestDbs.usda.knowsWord(w) })

    @Test
    fun `steps fit the unit`() {
        assertEquals(1.5, Units.bump("slice", 2.0, -1), 0.0)
        assertEquals(3.0, Units.bump("slice", 2.0, +1), 0.0)
        assertEquals(0.5, Units.bump("cup", 0.25, +1), 0.0)
        assertEquals(1.5, Units.bump("tbsp", 1.0, +1), 0.0)
        assertEquals(110.0, Units.bump("g", 100.0, +1), 0.0)
        assertEquals(95.0, Units.bump("g", 100.0, -1), 0.0)
        assertEquals(275.0, Units.bump("ml", 250.0, +1), 0.0)
        assertEquals(1.0, Units.bump("g", 1.0, -1), 0.0)
        assertEquals(0.5, Units.bump(null, 0.5, -1), 0.0)
    }

    @Test
    fun `switching units keeps the amount and lands on the new grid`() {
        val milkFood = FoodCandidate(id = "t:milk", name = "Milk, whole", origin = Origin.USDA,
            per100g = mapOf("calories" to 61.0), portions = listOf(Portion("1 cup", 244.0)), defaultGrams = 244.0)
        val item = ItemParser.Item(said = "milk", food = "milk", amount = 1.0, unit = "cup")
        val ml = Units.convert(milkFood, item, 244.0, "ml")!!
        assertTrue("one cup is about 240 mL, got $ml", ml in 230.0..250.0)
        val opts = Units.optionsFor(milkFood, item).map { it.unit }
        assertTrue(opts.toString(), "ml" in opts && "floz" in opts && opts.first() == "cup")
    }

    @Test
    fun `an amount said for a line changes that line`() {
        assertEquals(listOf(Changes.Change.SetAmount(0, 2.0, "slice", true)), interpret("actually 2 slices of bread", bread, butter))
        assertEquals(listOf(Changes.Change.SetAmount(1, 0.5, "tbsp", true)), interpret("the butter was half a tbsp", bread, butter))
        assertEquals(listOf(Changes.Change.SetAmount(0, 300.0, "ml", true)), interpret("make the milk 300 ml", milk))
        assertEquals(listOf(Changes.Change.SetAmount(0, 2.0, "cup", true)), interpret("2 cups", milk))
    }

    @Test
    fun `remove takes a line out`() {
        assertEquals(listOf(Changes.Change.Remove(2)), interpret("remove the cheese", bread, butter, cheese))
        assertEquals(listOf(Changes.Change.Remove(1)), interpret("no butter", bread, butter, cheese))
    }

    @Test
    fun `a new food is added`() {
        val c = interpret("add a tablespoon of honey", bread, butter)
        assertEquals(1, c.size)
        assertTrue(c.toString(), (c.single() as Changes.Change.Add).text.contains("honey"))
    }

    @Test
    fun `pointing at lines already there adds nothing`() {
        // The fix that logged a sandwich's butter, avocado and bread twice, and a
        // 600 g generic sandwich on top.
        val c = interpret(
            "also that butter I toasted into the bread so it's toasted bread with that amount of butter. " +
                "and that avocado. I also had some spinach about as much as I could fit on the two sandwiches",
            bread, butter, avocado,
        )
        val added = c.filterIsInstance<Changes.Change.Add>().joinToString { it.text }
        assertTrue("only the spinach is new: $c", "spinach" in added)
        assertTrue("nothing but the spinach: $c", "bread" !in added && "butter" !in added && "avocado" !in added && "sandwich" !in added)
        assertTrue("no amount changed: $c", c.none { it is Changes.Change.SetAmount })
    }

    @Test
    fun `a line said again with a count sets that count`() {
        val c = interpret("there were two slices of cheese", bread, cheese)
        assertEquals(listOf(Changes.Change.SetAmount(1, 2.0, "slice", true)), c)
    }
}
