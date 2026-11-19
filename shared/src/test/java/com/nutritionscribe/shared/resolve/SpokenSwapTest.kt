package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What broke (beta 11-16 and 11-18): saying what a line IS, in the "Say or type a
 * change" box, added the words as a new food.
 * - "That bacon is low sodium bacon, previously scanned." added a line called
 *   "previously scanned" and left the wrong bacon in place.
 * - "It was great value." about a pumpkin pie added a food called "value".
 *
 * The fix: "X is Y" where Y is the same food (or only a brand or kind) is a Swap
 * of line X; a brand or kind said alone swaps the only line (or the one just
 * named); talk about scanning is never a food. If the words can't be pinned to a
 * line, it's Unclear, never a new food.
 */
class SpokenSwapTest {

    private val bread = Changes.Line("Great Value — 100% Whole Wheat Round Top Bread", "bread", "slice")
    private val eggs = Changes.Line("Eggs", "egg", null)
    private val cheese = Changes.Line("Cheddar Cheese Slices", "cheese", "slice")
    private val bacon = Changes.Line("Great Value — Real Bacon Pieces", "bacon", "slice")
    private val pie = Changes.Line("pumpkin pie", "pumpkin pie", null)

    private val brands = setOf("great", "value", "kirkland", "nuvara")

    private fun interpret(text: String, vararg lines: Changes.Line) =
        Changes.interpret(text, lines.toList(), { w -> TestDbs.usda.knowsWord(w) }, { w -> w in brands })

    @Test
    fun `saying what a line is swaps it instead of adding a food`() {
        val c = interpret("That bacon is low sodium bacon, previously scanned.", bread, eggs, cheese, bacon)
        assertEquals(listOf(Changes.Change.Swap(3, "low sodium bacon")), c)
    }

    @Test
    fun `a brand said alone renames the only line`() {
        assertEquals(listOf(Changes.Change.Swap(0, "great value pumpkin pie")), interpret("It was great value.", pie))
        assertEquals(listOf(Changes.Change.Swap(0, "great value pumpkin pie")), interpret("great value", pie))
    }

    @Test
    fun `a kind said for a line swaps that line`() {
        assertEquals(listOf(Changes.Change.Swap(1, "low sodium bacon")), interpret("the bacon was low sodium", bread, bacon))
        assertEquals(listOf(Changes.Change.Swap(0, "multi grain bread")), interpret("the bread was multi grain", bread, eggs))
    }

    @Test
    fun `a brand with several lines and nothing named is unclear, never a food`() {
        val c = interpret("it was great value", bread, eggs, cheese)
        assertTrue(c.toString(), c.none { it is Changes.Change.Add })
        assertTrue(c.toString(), c.single() is Changes.Change.Unclear)
    }

    @Test
    fun `a brand right after naming a line goes to that line`() {
        val c = interpret("the bacon, it was great value", bread, eggs, bacon)
        assertEquals(listOf(Changes.Change.Swap(2, "great value bacon")), c)
    }

    @Test
    fun `scan talk alone changes nothing`() {
        for (t in listOf("previously scanned", "I scanned it", "I already scanned it before", "the one I scanned")) {
            val c = interpret(t, bread, bacon)
            assertTrue("$t -> $c", c.isEmpty())
        }
    }

    @Test
    fun `adding and amounts still work`() {
        assertEquals(listOf(Changes.Change.SetAmount(1, 3.0, "slice", true)), interpret("the bacon was 3 slices", bread, bacon))
        val add = interpret("add a cup of great value milk", bread, bacon)
        assertTrue(add.toString(), add.single() is Changes.Change.Add)
        // Pointing at a line with another line's food is not a swap.
        val c = interpret("the butter was toasted into the bread", bread, Changes.Line("Butter", "butter", "tbsp"))
        assertTrue(c.toString(), c.none { it is Changes.Change.Swap })
    }

    @Test
    fun `the swap keeps the line's amount in its lookup words`() {
        assertEquals("5 slice low sodium bacon", Changes.swapQuery("low sodium bacon", 5.0, "slice"))
        assertEquals("0.5 great value pumpkin pie", Changes.swapQuery("great value pumpkin pie", 0.5, null))
        assertEquals("great value pumpkin pie", Changes.swapQuery("great value pumpkin pie", null, null))
    }
}
