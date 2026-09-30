package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The bundled food.db and (when given) a format 4 label pack carry micronutrients in the right places. */
class MicronutrientSourcesTest {

    @Test
    fun `usda rows carry their micronutrients, per 100 g`() {
        val egg = TestDbs.usda.byId(2342629)!! // Egg, whole, boiled or poached
        val per = egg.per100g!!
        assertEquals(48.0, per["calcium"]!!, 0.01)
        assertEquals(411.0, per["cholesterol"]!!, 0.01)
        assertEquals(2.5, per["vitamin_d"]!!, 0.01)
        // The macros did not move.
        assertEquals(12.6, per["protein"]!!, 0.5)
        val salmon = TestDbs.usda.byId(2341700)!!
        assertTrue("salmon omega-3 ${salmon.per100g!!["omega3"]}", salmon.per100g!!["omega3"]!! > 1000)
    }

    @Test
    fun `a format 4 label pack reads calcium off the label`() {
        val labels = TestDbs.labels
        assumeTrue("no label pack given", labels != null)
        val shake = labels!!.search("core power elite chocolate", limit = 3).firstOrNull()
        assertNotNull(shake)
        val per = shake!!.per100g!!
        assertTrue("no calcium: is this a format 4 pack?", "calcium" in per)
        // A 414 mL bottle printing about 900 mg of calcium.
        val bottle = shake.nutrientsFor(shake.labelServingGrams)
        assertTrue("calcium per bottle ${bottle["calcium"]}", bottle["calcium"]!! in 600.0..1200.0)
        assertTrue("kcal per bottle ${bottle["calories"]}", bottle["calories"]!! in 200.0..260.0)
    }

    @Test
    fun `an AI answer borrows the micronutrients of the same food offline`() {
        // The AI gives calories and macros only.
        val ai = AiItemLookup { _, items -> items.map {
            AiItemAnswer("whole milk", "1 cup (244 g)", 244.0,
                mapOf("calories" to 149.0, "protein" to 7.7, "carbs" to 11.7, "fat" to 7.9))
        } }
        val r = Resolver(InMemoryPersonalStore(), TestDbs.usda, ai = ai, aiMode = Resolver.AiMode.ALL_NEW).resolve("a cup of milk")
        val milk = r.items.single()
        assertEquals(Origin.AI, milk.choice!!.origin)
        assertEquals(149.0, milk.nutrients["calories"]!!, 0.0) // the AI's own numbers stand
        assertTrue("calcium ${milk.nutrients["calcium"]}", milk.nutrients["calcium"]!! in 250.0..350.0)
        // And on the food itself, so changing the portion keeps them.
        assertTrue("calcium" in milk.choice!!.per100g!!)
    }

    @Test
    fun `a food that only shares the words lends nothing`() {
        // "Milk" at five times milk's calories is not milk; borrowing its calcium would be a guess.
        val ai = AiItemLookup { _, items -> items.map {
            AiItemAnswer("milk", "1 cup (244 g)", 244.0, mapOf("calories" to 760.0, "protein" to 2.0, "carbs" to 90.0, "fat" to 40.0))
        } }
        val r = Resolver(InMemoryPersonalStore(), TestDbs.usda, ai = ai, aiMode = Resolver.AiMode.ALL_NEW).resolve("a cup of milk")
        assertTrue(r.items.single().nutrients.keys.none { com.nutritionscribe.shared.model.Metric.fromKey(it)?.isMicro == true })
    }
}
