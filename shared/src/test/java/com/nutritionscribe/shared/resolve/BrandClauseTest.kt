package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 11-16 ("I only said one item"): a single packet of a drink mix (its
 * product line, flavour "raspberry lemonade", "from" the line, "and the brand is"
 * the maker) came out as two foods: the drink (as raspberries) and the brand,
 * which matched a honey. (Names here are made up.) Saying whose brand it is names the brand of the food
 * before it; it is never a food. And "a single packet" is one packet.
 */
class BrandClauseTest {

    private fun parse(text: String, brands: Set<String> = emptySet()) =
        ItemParser.parse(text, { w -> TestDbs.usda.knowsWord(w) }) { it in brands }

    @Test
    fun `the brand is X names the food before it`() {
        val items = parse("I also had a single packet of Hydro Boost of the flavor of raspberry lemonade from Zestline and the brand is Nuvara.")
        assertEquals(items.toString(), 1, items.size)
        val i = items.single()
        assertEquals("nuvara", i.brand)
        assertTrue(i.food, "lemonade" in i.food)
        assertFalse(i.food, "nuvara" in i.food)
    }

    @Test
    fun `other ways of saying the brand`() {
        for (t in listOf("a granola bar, the brand was Nature Valley", "a granola bar and it's made by Nature Valley",
            "a granola bar, brand is nature valley", "a granola bar. It's from Nature Valley.")) {
            val items = parse(t)
            assertEquals("$t -> $items", 1, items.size)
            assertEquals(t, "nature valley", items.single().brand)
        }
    }

    @Test
    fun `a single packet is one packet`() {
        val i = parse("a single packet of raspberry lemonade drink mix").single()
        assertEquals(1.0, i.amount!!, 0.001)
        assertEquals("packet", i.unit)
        assertFalse(i.food, "single" in i.food || "packet" in i.food)
    }
}
