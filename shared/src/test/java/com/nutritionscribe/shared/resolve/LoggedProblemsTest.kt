package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Problems found in real debug logs, each pinned down with a made-up sentence
 * shaped like the one that caused it (no one's log is quoted).
 */
class LoggedProblemsTest {

    private class FakeAi(private val answer: (List<ItemParser.Item>) -> List<AiItemAnswer>) : AiItemLookup, SentenceLookup {
        var known: List<String> = emptyList()
        override fun lookup(fullText: String, items: List<ItemParser.Item>): List<AiItemAnswer?> = items.map { null }
        override fun lookupSentence(fullText: String, known: List<ItemParser.Item>): List<AiItemAnswer> {
            this.known = known.map { it.said }
            return answer(known)
        }
    }

    private fun Resolver.confirmAsShown(r: Resolver.Resolution) = confirm(r.text, r.items.map {
        Resolver.Confirmed(it.item, it.key, it.displayName, it.choice, it.grams, it.nutrients,
            unchangedMemory = it.status == Resolver.Status.REMEMBERED)
    })

    private fun answer(name: String, grams: Double, kcal: Double, protein: Double, carbs: Double, fat: Double) =
        AiItemAnswer(name, null, grams, mapOf("calories" to kcal, "protein" to protein, "carbs" to carbs, "fat" to fat))

    @Test
    fun `a remark after a remembered food is not logged as a food`() {
        val personal = InMemoryPersonalStore()
        val first = Resolver(personal, TestDbs.usda, ai = FakeAi { listOf(answer("kiwi", 69.0, 42.0, 0.8, 10.1, 0.4)) })
        first.confirmAsShown(first.resolve("a kiwi"))

        // The model, told the kiwi is already logged, has nothing to add.
        val r = Resolver(personal, TestDbs.usda, ai = FakeAi { emptyList() }).resolve("a kiwi. Though it was a bit sour.")
        assertEquals(listOf("kiwi"), r.items.map { it.displayName })
        assertTrue(r.silent)
    }

    @Test
    fun `cheese that came on the burger is asked about with the burger`() {
        val personal = InMemoryPersonalStore()
        val first = Resolver(personal, TestDbs.usda, ai = FakeAi { listOf(answer("cheese", 80.0, 320.0, 20.0, 2.0, 26.0)) })
        first.confirmAsShown(first.resolve("cheese"))

        val ai = FakeAi { listOf(answer("diner cheeseburger", 230.0, 600.0, 32.0, 41.0, 33.0)) }
        val r = Resolver(personal, TestDbs.usda, ai = ai).resolve("I had a hamburger from the diner and it had cheese.")
        assertFalse("the remembered 80 g of cheese was pinned", ai.known.any { "cheese" in it })
        assertEquals(listOf("diner cheeseburger"), r.items.map { it.displayName })
    }

    @Test
    fun `misspelled spoons are still spoons`() {
        val item = ItemParser.parse("maple syrup - 1 Tbps").single()
        assertEquals("tbsp", item.unit)
        assertEquals("maple syrup", item.food)
        assertEquals("tsp", ItemParser.parse("2 tsps of sugar").single().unit)
    }

    @Test
    fun `fluid ounces and liters are volumes`() {
        val juice = ItemParser.parse("8 fl oz of apple juice").single()
        assertEquals("floz", juice.unit)
        assertEquals(8.0, juice.amount!!, 0.0)
        assertTrue(juice.food, "apple juice" in juice.food)
        assertEquals("l", ItemParser.parse("3 liters of sparkling water").single().unit)
        assertEquals("floz", ItemParser.parse("a 20 fl. oz. iced coffee").single().unit)
    }

    @Test
    fun `a barcode the pack does not have is read from Open Food Facts`() {
        val body = """{"status":1,"product":{"product_name":"Creamy Peanut Butter","brands":"Food Club, Topco",
            "serving_size":"2 tbsp (32 g)","serving_quantity":32,"nutriments":{"energy-kcal_100g":594,
            "proteins_100g":21.9,"carbohydrates_100g":25,"fat_100g":50,"sugars_100g":9.4,"sodium_100g":0.406,
            "calcium_100g":0.047,"vitamin-d_100g":0}}}"""
        var asked = ""
        val c = OpenFoodFactsSource { url -> asked = url; 200 to body }.byBarcode("0036800123456")!!
        assertTrue(asked, "/0036800123456.json" in asked)
        assertEquals("Food Club", c.brand)
        assertEquals(32.0, c.labelServingGrams!!, 0.0)
        val serving = c.nutrientsFor(32.0)
        assertEquals(190.0, serving["calories"]!!, 1.0)
        assertEquals(130.0, serving["sodium"]!!, 1.0)
        assertEquals(15.0, serving["calcium"]!!, 0.5)
        // Missing products and pages with no label are misses, not zeros.
        assertEquals(null, OpenFoodFactsSource { 200 to """{"status":0}""" }.byBarcode("0036800123456"))
        assertEquals(null, OpenFoodFactsSource { 200 to """{"status":1,"product":{"product_name":"X","nutriments":{}}}""" }.byBarcode("0036800123456"))
    }
}
