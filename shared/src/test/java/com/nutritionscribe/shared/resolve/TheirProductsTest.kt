package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner, 11-18: "I ate half of pumpkin pie yesterday. Why does that pumpkin pie not
 * show up here? It's great value." The pie had been found online the day before
 * (a web search) and saved as theirs, but "your brands" only looked at scanned and
 * barcode labels, so "pumpkin pie" got a generic pie. A branded product they saved
 * from a web search is theirs too.
 */
class TheirProductsTest {

    private val pie = FoodCandidate(
        id = "web:-15833", name = "No Sugar Added Pumpkin Pie", brand = "Brand Co", origin = Origin.AI,
        per100g = mapOf("calories" to 235.0, "protein" to 3.7), defaultGrams = 680.0,
    )
    private val saved = PersonalFood(
        key = "added brand co no pie pumpkin sugar", phrase = "no sugar added brand co pumpkin pie",
        name = "Brand Co — No Sugar Added Pumpkin Pie", candidate = pie, amount = 0.5, grams = 340.0,
        nutrients = mapOf("calories" to 800.0, "protein" to 12.5), unitGrams = mapOf("each" to 680.0), updatedAt = 1,
    )

    @Test
    fun `a branded food found online and saved is offered for its plain name`() {
        val store = InMemoryPersonalStore().apply { put(saved) }
        val r = Resolver(store, TestDbs.usda, null)
        val item = r.resolve("a little less than half of a pumpkin pie").items.single()
        assertEquals(item.toString(), "web:-15833", item.choice?.id)
        assertTrue("about half a pie, not a slice: ${item.grams}", item.grams!! in 250.0..360.0)
    }

    @Test
    fun `an unbranded ai guess is not a product of theirs`() {
        val guess = saved.copy(key = "pie pumpkin", name = "pumpkin pie", candidate = pie.copy(id = "ai:1", brand = null, name = "pumpkin pie"))
        val store = InMemoryPersonalStore().apply { put(guess) }
        assertTrue(Resolver(store, TestDbs.usda, null).brandsFor(ItemParser.Item(said = "a slice of cherry pie", food = "cherry pie"), "cherry pie").isEmpty())
    }
}
