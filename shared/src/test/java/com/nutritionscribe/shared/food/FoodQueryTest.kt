package com.nutritionscribe.shared.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- vague input ------------------------------------------------------

    @Test
    fun `a meal word with no food is vague`() {
        // Offline lookup used to answer "a solid breakfast" with "Breakfast bar,
        // NFS" — a number the user would have to notice was invented.
        assertTrue(FoodQuery.isVague("a solid breakfast"))
        assertTrue(FoodQuery.isVague("leftovers"))
        assertTrue(FoodQuery.isVague("the usual"))
        assertTrue(FoodQuery.isVague("lunch"))
        assertTrue(FoodQuery.isVague("a good dinner"))
        assertTrue(FoodQuery.isVague(""))
    }

    @Test
    fun `a meal word alongside a real food is not vague`() {
        // Over-eager vagueness detection would refuse perfectly good entries.
        assertFalse(FoodQuery.isVague("chicken dinner"))
        assertFalse(FoodQuery.isVague("breakfast burrito"))
        assertFalse(FoodQuery.isVague("banana"))
        assertFalse(FoodQuery.isVague("2 eggs and toast"))
    }

    // --- multi-item entries -----------------------------------------------

    @Test
    fun `commas and the word and split an entry into items`() {
        assertEquals(
            listOf("chicken", "rice", "broccoli"),
            FoodQuery.splitItems("chicken, rice and broccoli"),
        )
        assertEquals(listOf("eggs", "bacon"), FoodQuery.splitItems("eggs + bacon"))
    }

    @Test
    fun `the word with does not split an item`() {
        // Splitting on "with" would tear these into nonsense, and the offline
        // lookup would then log only the sauce.
        assertEquals(
            listOf("spaghetti with meat sauce"),
            FoodQuery.splitItems("spaghetti with meat sauce"),
        )
        assertEquals(
            listOf("tuna sandwich on wheat"),
            FoodQuery.splitItems("tuna sandwich on wheat"),
        )
    }

    @Test
    fun `a single food is returned unchanged`() {
        assertEquals(listOf("greek yogurt"), FoodQuery.splitItems("greek yogurt"))
        assertEquals(listOf("2 eggs"), FoodQuery.splitItems("2 eggs"))
    }

    // --- stated amounts ----------------------------------------------------

    @Test
    fun `a stated protein figure is read back out`() {
        // The user's own number beats a database estimate for a food the database
        // may not even contain.
        assertEquals(16.0, FoodQuery.statedProteinGrams("Built bar, 16g protein")!!, 0.0001)
        assertEquals(20.0, FoodQuery.statedProteinGrams("shake with 20 grams of protein")!!, 0.0001)
        assertEquals(12.0, FoodQuery.statedProteinGrams("protein: 12g")!!, 0.0001)
    }

    @Test
    fun `no stated figure returns null rather than zero`() {
        assertNull(FoodQuery.statedProteinGrams("chicken breast"))
        assertNull(FoodQuery.statedProteinGrams("2 eggs"))
        // Absurd values are not amounts, they are noise.
        assertNull(FoodQuery.statedProteinGrams("9000g protein"))
    }

    // --- coverage ----------------------------------------------------------

    @Test
    fun `coverage separates a real match from an invented one`() {
        // Score alone cannot do this: "Big Mac" scores badly yet is right, while
        // "a solid breakfast" scores well against "Breakfast bar" and is wrong.
        val bigMac = FoodQuery.contentWords("big mac")
        assertEquals(1.0, FoodQuery.coverage(bigMac, "McDONALD'S, BIG MAC"), 0.0001)

        val breakfast = FoodQuery.contentWords("a solid breakfast")
        assertTrue(FoodQuery.coverage(breakfast, "Breakfast bar, NFS") < 1.0)

        val coke = FoodQuery.contentWords("diet coke")
        assertTrue(FoodQuery.coverage(coke, "Roll, diet") < 1.0)
    }

    @Test
    fun `coverage of nothing is zero not a crash`() {
        assertEquals(0.0, FoodQuery.coverage(emptyList(), "Banana, raw"), 0.0001)
    }
}
