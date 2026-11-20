package com.nutritionscribe.app.ui.confirm

import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.LoggedItem
import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Change offers "your usual" first (owner, 11-19, item 7): the foods logged before
 * for the same word, most eaten first, each with its brand, so Walmart cheddar
 * and Costco cheddar are told apart.
 */
class UsualChoicesTest {
    private var t = 0L
    private fun entry(vararg items: LoggedItem) = Entry(
        id = "e${++t}", timestampEpochMillis = t * 1000, rawText = "", name = items.first().name,
        source = "test", confidence = Confidence.STATED, items = items.toList(),
    )
    private fun item(name: String, said: String, kcal: Double, grams: Double? = 28.0, unit: String? = "slice", amount: Double = 1.0, id: String? = null) =
        LoggedItem(name = name, said = said, key = said, portion = "$amount $unit", grams = grams, origin = "LABEL",
            candidateId = id, nutrients = mapOf("calories" to kcal), amount = amount, unit = unit)

    private val log = listOf(
        entry(item("Great Value — Sharp cheddar", "sharp cheddar cheese", 110.0, id = "label:1000000005")),
        entry(item("Great Value — Sharp cheddar", "cheddar cheese", 110.0, id = "label:1000000005")),
        entry(item("Kirkland Signature — Medium cheddar", "cheddar cheese", 100.0, id = "label:2000000003")),
        entry(item("String cheese", "string cheese", 80.0, unit = "stick")),
        entry(item("Whole wheat bread", "wheat bread", 81.0)),
    )

    @Test
    fun `foods logged for the same word, most eaten first, with their brands`() {
        val c = UsualChoices.of("cheese", log, current = null)
        // Twice beats once; a tie goes to the one eaten most recently (string cheese came later).
        assertEquals(listOf("Sharp cheddar", "String cheese", "Medium cheddar"), c.map { it.product })
        assertEquals(listOf("Great Value", null, "Kirkland Signature"), c.map { it.brand })
        assertEquals(2, c.first().times)
    }

    @Test
    fun `the food already on the card is not offered again`() {
        val now = FoodCandidate(id = "label:1000000005", name = "Sharp cheddar", brand = "Great Value", origin = Origin.LABEL)
        val c = UsualChoices.of("cheese", log, current = now)
        assertTrue(c.none { it.product == "Sharp cheddar" })
    }

    @Test
    fun `plurals and nothing to go on`() {
        assertEquals(3, UsualChoices.of("cheeses", log, null).size)
        assertTrue(UsualChoices.of(null, log, null).isEmpty())
        assertTrue(UsualChoices.of("pizza", log, null).isEmpty())
        assertTrue(UsualChoices.of("cheese", emptyList(), null).isEmpty())
    }

    @Test
    fun `a past food keeps its numbers per slice`() {
        val two = item("Great Value — Sharp cheddar", "cheddar", 220.0, grams = 56.0, amount = 2.0)
        val c = UsualChoices.candidateOf(two)
        assertEquals("Great Value", c.brand)
        assertEquals(28.0, c.portions.single().grams, 1e-9)
        assertEquals(110.0, c.nutrientsFor(28.0)["calories"]!!, 1e-9)
    }

    @Test
    fun `a past food without a weight counts per serving`() {
        val bowl = item("Chicken burrito bowl", "burrito bowl", 640.0, grams = null, unit = "bowl")
        val c = UsualChoices.candidateOf(bowl)
        assertNull(c.per100g)
        assertEquals(640.0, c.nutrientsFor(null, 1.0)["calories"]!!, 1e-9)
    }
}
