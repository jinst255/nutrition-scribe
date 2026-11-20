package com.nutritionscribe.app.ui.home

import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.LoggedItem
import com.nutritionscribe.shared.stores.StoreSuggest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The store suggestion counts foods, not entries: "eggs, Great Value bread and
 * Great Value milk" is two Great Value foods. An old entry with no items still
 * counts once, by its own name.
 */
class StoreFoodsTest {
    private val now = 1_795_000_000_000L

    private fun item(name: String, id: String) = LoggedItem(name = name, said = name, key = name.lowercase(), origin = "LABEL", candidateId = id)

    @Test
    fun `each item of an entry is its own food`() {
        val e = Entry("1", now, "breakfast", "Breakfast", source = "label", confidence = Confidence.ESTIMATED,
            items = listOf(item("Eggs", "usda:1"), item("Great Value — Bread", "scan:1"), item("Great Value — 2% Milk", "label:2")))
        val foods = StoreFoods.of(listOf(e))
        assertEquals(3, foods.size)
        assertEquals(listOf("usda:1", "scan:1", "label:2"), foods.map { it.candidateId })
        assertEquals(setOf(now), foods.map { it.at }.toSet())
    }

    @Test
    fun `an entry without items counts once under its own name`() {
        val e = Entry("2", now, "gv pie", "Great Value — Pumpkin Pie", source = "label", confidence = Confidence.ESTIMATED)
        val foods = StoreFoods.of(listOf(e))
        assertEquals(listOf(StoreSuggest.Food("Great Value — Pumpkin Pie", null, "LABEL", now)), foods)
    }

    @Test
    fun `a week of Great Value entries leads to the suggestion`() {
        val entries = (1..6).map { i ->
            Entry("$i", now - i * 3_600_000L, "x", "Great Value — Thing $i", source = "label", confidence = Confidence.ESTIMATED,
                items = listOf(item("Great Value — Thing $i", "label:$i")))
        }
        val s = StoreSuggest.suggest(StoreFoods.of(entries), now, emptySet(), emptyMap())
        assertNotNull(s)
        assertEquals("great-value", s!!.store.id)
    }

    @Test
    fun `sizes read the way people say them`() {
        assertEquals("1 KB", mb(200))
        assertEquals("950 KB", mb(950_000))
        assertEquals("2.1 MB", mb(2_100_000))
    }
}
