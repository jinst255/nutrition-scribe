package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What broke (found 11-19 testing store packs on the emulator): "great value cottage
 * cheese" was parsed as brand "great" and the food "value cottage cheese". The
 * brand-up-front rule takes leading brand words, but labels ignore the word "value",
 * so it stopped at "great"; the label search then looked for a Great-branded "value
 * cottage cheese", found nothing, and the line fell back to USDA cottage cheese.
 * The same split made "It was great value" a food called "value" (11-18 note N13).
 *
 * The fix: a store brand of two or more words (StoreCatalog's aliases) is taken
 * whole, wherever it is said.
 */
class StoreBrandParseTest {

    // The label pack's brand words: "great" is one, "value" isn't (it's ignored).
    private val brands = setOf("great", "kirkland", "trader", "joe", "member", "mark")

    private fun one(text: String): ItemParser.Item {
        val items = ItemParser.parse(text, { w -> TestDbs.usda.knowsWord(w) }) { it in brands }
        assertEquals("$text -> $items", 1, items.size)
        return items.single()
    }

    @Test
    fun `great value is one brand, not great plus a food called value`() {
        val i = one("great value cottage cheese")
        assertEquals("great value", i.brand)
        assertEquals("cottage cheese", i.food)
    }

    @Test
    fun `other store brands of more than one word`() {
        for ((text, brand, food) in listOf(
            Triple("2 slices of great value sharp cheddar", "great value", "sharp cheddar"),
            Triple("a handful of member's mark almonds", "member's mark", "almonds"),
            Triple("trader joe's mandarin orange chicken", "trader joe's", "mandarin orange chicken"),
            Triple("a cup of simple truth oat milk", "simple truth", "oat milk"),
            Triple("kirkland signature cottage cheese", "kirkland signature", "cottage cheese"),
        )) {
            val i = one(text)
            assertEquals(text, brand, i.brand)
            assertEquals(text, food, i.food)
            assertFalse(text, "value" in i.food.split(" "))
        }
    }

    @Test
    fun `the amount still comes through`() {
        val i = one("2 slices of great value sharp cheddar")
        assertEquals(2.0, i.amount)
    }

    @Test
    fun `value on its own is still just a word`() {
        val i = one("a value meal cheeseburger")
        assertEquals(null, i.brand)
    }
}
