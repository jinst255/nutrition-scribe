package com.nutritionscribe.shared.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline lookup is what makes the app usable without an API key, so its text
 * handling is worth pinning down. These run on the JVM in milliseconds; the ranking
 * itself is measured end-to-end against the real database by tools/bench_food_db.py.
 */
class FoodQueryTest {

    // --- quantity parsing -------------------------------------------------

    @Test
    fun `digit with attached unit splits into amount unit and food`() {
        val p = FoodQuery.parseQuantity("8oz chicken breast")
        assertEquals(8.0, p.amount!!, 0.0001)
        assertEquals("oz", p.unit)
        assertEquals("chicken breast", p.food)
    }

    @Test
    fun `digit with spaced unit and the word of`() {
        val p = FoodQuery.parseQuantity("2 cups of white rice")
        assertEquals(2.0, p.amount!!, 0.0001)
        assertEquals("cups", p.unit)
        assertEquals("white rice", p.food)
    }

    @Test
    fun `a bare number is a count not a unit`() {
        val p = FoodQuery.parseQuantity("3 scrambled eggs")
        assertEquals(3.0, p.amount!!, 0.0001)
        assertNull(p.unit)
        assertEquals("scrambled eggs", p.food)
    }

    @Test
    fun `spelled out numbers are understood`() {
        val p = FoodQuery.parseQuantity("two eggs")
        assertEquals(2.0, p.amount!!, 0.0001)
        assertEquals("eggs", p.food)
    }

    @Test
    fun `half is a quantity`() {
        val p = FoodQuery.parseQuantity("half cup oatmeal")
        assertEquals(0.5, p.amount!!, 0.0001)
        assertEquals("cup", p.unit)
        assertEquals("oatmeal", p.food)
    }

    @Test
    fun `no quantity leaves the food untouched and the amount null`() {
        val p = FoodQuery.parseQuantity("greek yogurt")
        assertNull(p.amount)
        assertNull(p.unit)
        assertEquals("greek yogurt", p.food)
    }

    @Test
    fun `a food whose name starts with a number word is not eaten by the parser`() {
        // "a" maps to 1, but "a" alone with nothing after it must not strip the food.
        val p = FoodQuery.parseQuantity("an apple")
        assertEquals(1.0, p.amount!!, 0.0001)
        assertEquals("apple", p.food)
    }

    // --- word handling ----------------------------------------------------

    @Test
    fun `stop words are dropped so they cannot drive a match`() {
        // Without this, "I ate a bit of chicken" ranks on "ate" and "bit".
        assertEquals(listOf("chicken"), FoodQuery.contentWords("I ate a bit of chicken"))
    }

    @Test
    fun `plurals are stemmed onto their singular`() {
        assertEquals("egg", FoodQuery.stem("eggs"))
        assertEquals("almond", FoodQuery.stem("almonds"))
        assertEquals("berry", FoodQuery.stem("berries"))
        // "ss" endings are not plurals; stripping them breaks "glass" and "grass".
        assertEquals("glass", FoodQuery.stem("glass"))
    }

    @Test
    fun `the head is the part before the first comma`() {
        assertEquals("banana", FoodQuery.headOf("Banana, raw"))
        assertEquals("oil", FoodQuery.headOf("Oil, avocado"))
        assertEquals("cheddar cheese soup", FoodQuery.headOf("Cheddar cheese soup, home recipe"))
    }

    @Test
    fun `alias keys ignore word order and quantity`() {
        // "2 eggs", "eggs" and "Egg" are one request, not three.
        assertEquals(
            FoodQuery.normalise("cheddar cheese"),
            FoodQuery.normalise("Cheese, Cheddar"),
        )
        assertEquals(FoodQuery.normalise("eggs"), FoodQuery.normalise("Egg"))
    }

    // --- ranking ----------------------------------------------------------

    @Test
    fun `a head noun the person never said loses to the plain food`() {
        // The single most important behaviour: "apple" must not become apple pie.
        val q = FoodQuery.contentWords("apple")
        assertTrue(
            FoodQuery.score(q, "Apple, raw") > FoodQuery.score(q, "Pie, apple"),
        )
    }

    @Test
    fun `avocado is not avocado oil`() {
        val q = FoodQuery.contentWords("avocado")
        assertTrue(
            FoodQuery.score(q, "Avocado, raw") > FoodQuery.score(q, "Oil, avocado"),
        )
    }

    @Test
    fun `the generic form outranks a qualified one`() {
        val q = FoodQuery.contentWords("milk")
        assertTrue(
            FoodQuery.score(q, "Milk, NFS") > FoodQuery.score(q, "Milk, evaporated, whole"),
        )
    }

    @Test
    fun `a longer more specific description does not beat a direct hit`() {
        val q = FoodQuery.contentWords("cheddar cheese")
        assertTrue(
            FoodQuery.score(q, "Cheese, Cheddar") >
                FoodQuery.score(q, "Cheddar cheese soup, home recipe, canned or ready-to-serve"),
        )
    }
}
