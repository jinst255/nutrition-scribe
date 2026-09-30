package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * The six failure modes of the old lookup that must never come back, run against
 * the real bundled food.db. (The seventh, the real-week replay, is WeekReplayTest.)
 */
class FailureModesTest {

    private fun fresh(labels: LabelPackSource? = null) = Resolver(InMemoryPersonalStore(), TestDbs.usda, labels)

    private fun Resolver.Resolution.kcal() = total["calories"] ?: 0.0
    private fun Resolver.Resolution.protein() = total["protein"] ?: 0.0

    /** Tap Confirm on the card exactly as it was filled in. */
    private fun Resolver.confirmAsShown(r: Resolver.Resolution) = confirm(r.text, r.items.map {
        Resolver.Confirmed(it.item, it.key, it.displayName, it.choice, it.grams, it.nutrients,
            unchangedMemory = it.status == Resolver.Status.REMEMBERED)
    })

    // ------------------------------------------------------------------
    // 1. Branded / restaurant foods not in USDA
    // ------------------------------------------------------------------

    @Test
    fun `a brand USDA has never heard of is shown as a guess, never logged silently`() {
        for (text in listOf("wendy's baconator", "built bar", "kodiak protein pancakes", "premier protein shake", "a lunchable")) {
            val r = fresh().resolve(text)
            assertFalse("$text logged silently", r.silent)
            val item = r.items.single()
            assertNotEquals(Resolver.Status.REMEMBERED, item.status)
            assertTrue("$text should offer a scan or be marked weak", item.weak || item.offerScan)
        }
    }

    @Test
    fun `with the label pack, a branded food resolves to the real label`() {
        val r = fresh(tinyLabelPack()).resolve("built bar")
        val item = r.items.single()
        assertEquals(Origin.LABEL, item.choice?.origin)
        assertEquals(130.0, r.kcal(), 1.0)
        assertEquals(17.0, r.protein(), 0.5)
        assertFalse("still shown the first time", r.silent)
    }

    @Test
    fun `a barcode finds its product in any of the forms scanners report`() {
        val labels = tinyLabelPack()
        for (code in listOf("850001886021", "0850001886021", "00850001886021")) {
            assertEquals("Built", labels.byBarcode(code)?.brand)
        }
        assertNull(labels.byBarcode("123"))
    }

    @Test
    fun `a named brand wins over a generic match, and the count is read against the label`() {
        val r = fresh(tinyLabelPack()).resolve("3 oreos")
        val item = r.items.single()
        assertEquals(Origin.LABEL, item.choice?.origin)
        // Label: 3 cookies (34 g) = 160 kcal.
        assertEquals(160.0, r.kcal(), 5.0)
    }

    // ------------------------------------------------------------------
    // 2. Multi-item quantity misparsing
    // ------------------------------------------------------------------

    @Test
    fun `milk, two cups, whole milk is two cups of whole milk`() {
        val r = fresh().resolve("milk, two cups, whole milk")
        val item = r.items.single()
        assertEquals(2.0, item.item.amount!!, 0.0)
        assertEquals("cup", item.item.unit)
        assertTrue(item.choice!!.name.startsWith("Milk, whole"))
        assertEquals(298.0, r.kcal(), 15.0)
    }

    @Test
    fun `an amount belongs to the food next to it, not the first food in the sentence`() {
        val r = fresh().resolve("chicken, rice, and two cups of milk")
        assertEquals(3, r.items.size)
        val (chicken, rice, milk) = r.items
        assertNull("chicken got an amount", chicken.item.amount)
        assertNull("rice got an amount", rice.item.amount)
        assertEquals(2.0, milk.item.amount!!, 0.0)
        assertEquals("cup", milk.item.unit)
    }

    @Test
    fun `amounts said after the food, and two foods with no and between them`() {
        val a = fresh().resolve("carrot cake 2 servings").items.single()
        assertEquals(2.0, a.item.amount!!, 0.0)
        val b = fresh().resolve("one pork chop a serving of rice and a half cup of refried beans")
        assertEquals(listOf(1.0, 1.0, 0.5), b.items.map { it.item.amount })
        assertEquals(listOf(null, "serving", "cup"), b.items.map { it.item.unit })
    }

    @Test
    fun `a stated protein figure stays with its food`() {
        val r = fresh().resolve("protein drink with milk the protein drink itself is 30 g of protein and the milk")
        assertEquals(2, r.items.size)
        assertEquals(30.0, r.items[0].item.statedProteinG!!, 0.0)
        assertNull(r.items[1].item.statedProteinG)
    }

    // ------------------------------------------------------------------
    // 3. Naive smallest-unit defaults
    // ------------------------------------------------------------------

    @Test
    fun `a bag of potato chips is a bag, not one chip`() {
        val r = fresh().resolve("a bag of potato chips")
        val grams = r.items.single().grams!!
        assertTrue("grams $grams", grams >= 28.0)
        assertTrue("kcal ${r.kcal()}", r.kcal() in 130.0..180.0)
    }

    @Test
    fun `an uncounted food is a serving, and a counted one is that many of it`() {
        assertTrue(fresh().resolve("potato chips").items.single().grams!! >= 28.0)
        val ten = fresh().resolve("10 potato chips").items.single().grams!!
        assertTrue("10 chips = $ten g", ten in 10.0..40.0)
        // "a banana" is a banana, not the 6 g slice USDA lists first.
        assertTrue(fresh().resolve("a banana").items.single().grams!! > 90.0)
        // "a slice of bread" is a normal slice, not the snack-size one.
        assertTrue(fresh().resolve("a slice of bread").items.single().grams!! in 25.0..35.0)
        // A topping is a spoonful.
        val syrup = fresh().resolve("two pancakes with a little bit of maple syrup").items.last()
        assertTrue("syrup ${syrup.grams}", syrup.grams!! <= 25.0)
    }

    // ------------------------------------------------------------------
    // 4. Word-order / parsing failures
    // ------------------------------------------------------------------

    @Test
    fun `raspberry jam is jam, not raspberries`() {
        val item = fresh().resolve("raspberry jam").items.single()
        assertTrue(item.choice!!.name, item.choice!!.name.contains("Jam", ignoreCase = true))
        assertFalse(item.choice!!.name.startsWith("Raspberries"))
    }

    @Test
    fun `PB and J is one sandwich however it is said`() {
        for (text in listOf("PB and J", "PB&J", "pb & j", "pbj", "peanut butter and jelly", "a peanut butter and jelly sandwich")) {
            val items = fresh().resolve(text).items
            assertEquals("$text split into ${items.map { it.item.food }}", 1, items.size)
            assertTrue(text, items.single().choice!!.name.startsWith("Peanut butter and jelly sandwich"))
        }
    }

    @Test
    fun `compound foods are not split on and`() {
        val mac = fresh().resolve("mac and cheese").items.single()
        assertTrue(mac.choice!!.name, mac.choice!!.name.contains("macaroni", ignoreCase = true))
        assertEquals(1, fresh().resolve("half and half").items.size)
        // ...but a real list still is.
        assertEquals(3, fresh().resolve("eggs, toast and bacon").items.size)
    }

    @Test
    fun `the head noun decides what the food is`() {
        assertTrue(fresh().resolve("apple pie").items.single().choice!!.name.startsWith("Pie"))
        assertTrue(fresh().resolve("bell pepper").items.single().choice!!.name.startsWith("Pepper"))
        assertTrue(fresh().resolve("shredded mozzarella cheese").items.single().choice!!.name.startsWith("Cheese, mozzarella", ignoreCase = true))
    }

    // ------------------------------------------------------------------
    // 5. Silent wrong answers
    // ------------------------------------------------------------------

    @Test
    fun `with an empty cache, nothing logs without a card`() {
        val battery = listOf(
            "a banana", "2 eggs", "chicken breast", "rice", "PB&J", "a bag of potato chips",
            "wendy's baconator", "milk 2 cups whole milk", "a small cookie", "a solid breakfast",
        )
        for (text in battery) {
            val r = fresh().resolve(text)
            assertFalse("$text logged silently on first sight", r.silent)
            assertTrue(text, r.needsConfirm.isNotEmpty())
        }
    }

    @Test
    fun `a sentence with no food in it asks rather than invents`() {
        val r = fresh().resolve("a solid breakfast")
        assertFalse(r.silent)
        assertTrue(r.items.all { it.status == Resolver.Status.UNKNOWN || it.weak })
    }

    @Test
    fun `a near miss in the cache is offered, never used silently`() {
        val res = fresh()
        res.confirmAsShown(res.resolve("raspberries"))
        val r = res.resolve("raspberry jam")
        assertFalse(r.silent)
        assertEquals(Resolver.Status.NEW, r.items.single().status)
    }

    // ------------------------------------------------------------------
    // 6. Cache correctness over repetition
    // ------------------------------------------------------------------

    @Test
    fun `the same food five times asks once`() {
        val res = fresh()
        var cards = 0
        repeat(5) {
            val r = res.resolve("2 cups whole milk")
            if (!r.silent) { cards++; res.confirmAsShown(r) } else r.items.forEach { res.touch(it.key) }
        }
        assertEquals(1, cards)
    }

    @Test
    fun `a correction is permanent and never re-asked`() {
        val res = fresh()
        val first = res.resolve("rice")
        val item = first.items.single()
        // The user says their rice is 250 g, 320 kcal.
        res.confirm(first.text, listOf(Resolver.Confirmed(item.item, item.key, "My rice", item.choice, 250.0,
            mapOf("calories" to 320.0, "protein" to 6.5))))
        repeat(3) {
            val again = res.resolve("rice")
            assertTrue(again.silent)
            assertEquals(320.0, again.kcal(), 0.01)
            assertEquals(250.0, again.items.single().grams!!, 0.01)
        }
        // Their portion is now their default, and scales with what they say.
        val double = res.resolve("2 servings of rice")
        // "serving" was never confirmed as a unit, so this one asks — once.
        if (!double.silent) res.confirmAsShown(double)
        assertTrue(res.resolve("2 servings of rice").silent)
    }

    @Test
    fun `a remembered food scales with the amount said, in the same unit, silently`() {
        val res = fresh()
        res.confirmAsShown(res.resolve("2 cups whole milk"))
        val three = res.resolve("3 cups whole milk")
        assertTrue(three.silent)
        assertEquals(1.5 * res.resolve("2 cups whole milk").kcal(), three.kcal(), 0.5)
        val bare = res.resolve("whole milk")
        assertTrue("no amount = their usual", bare.silent)
        // A unit never confirmed for this food gets one card, then is remembered too.
        val glass = res.resolve("a glass of whole milk")
        assertFalse(glass.silent)
        res.confirmAsShown(glass)
        assertTrue(res.resolve("a glass of whole milk").silent)
    }

    @Test
    fun `a remembered brand is not another brand's product`() {
        val res = fresh(tinyLabelPack())
        res.confirmAsShown(res.resolve("a built bar"))
        assertTrue(res.resolve("a built bar").silent)
        assertFalse("a kind bar must not log as the Built bar", res.resolve("a kind bar").silent)
        assertFalse(res.resolve("a protein bar").silent)
    }

    @Test
    fun `a typo in a remembered food still hits, a different food does not`() {
        val res = fresh()
        res.confirmAsShown(res.resolve("greek yogurt"))
        assertTrue(res.resolve("greek yogrut").silent)
        assertFalse(res.resolve("greek salad").silent)
    }

    @Test
    fun `a multi-part sentence confirmed once is one silent entry after`() {
        val res = fresh()
        val text = "one pork chop, a serving of rice and a half cup of refried beans"
        val first = res.resolve(text)
        assertEquals(3, first.needsConfirm.size)
        res.confirmAsShown(first)
        val again = res.resolve(text)
        assertTrue(again.silent)
        assertEquals(first.kcal(), again.kcal(), 0.5)
    }

    @Test
    fun `save as mine turns a breakdown into one food with its own name`() {
        val res = fresh()
        val text = "1/3 bell pepper, 10 baby carrots, 1.5 stalks of celery"
        val r = res.resolve(text)
        res.saveMeal(text, "My veggie bag", r.items.map { MealPart(it.displayName, it.item.said, it.grams, it.nutrients) },
            asName = "my usual bag of veggies")
        val byName = res.resolve("my usual bag of veggies")
        assertTrue(byName.silent)
        assertNotNull(byName.meal)
        assertEquals(r.kcal(), byName.kcal(), 0.5)
        assertEquals(3, byName.meal!!.parts.size)
    }

    // ------------------------------------------------------------------

    /** Three real labels, in the label pack's own schema. */
    private fun tinyLabelPack(): LabelPackSource {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE product (id INTEGER PRIMARY KEY, brand TEXT, description TEXT, serving_g REAL, " +
                    "serving_text TEXT, count REAL, count_unit TEXT, measure REAL, measure_unit TEXT, kcal INTEGER, protein_g INTEGER, carbs_g INTEGER, " +
                    "fat_g INTEGER, fiber_g INTEGER, sugar_g INTEGER, sat_fat_g INTEGER, sodium_mg INTEGER, caffeine_mg INTEGER, pop INTEGER, owner TEXT)",
            )
            st.executeUpdate("CREATE TABLE gtin (gtin TEXT PRIMARY KEY, id INTEGER)")
            st.executeUpdate("CREATE TABLE pword (word TEXT, id INTEGER, in_brand INTEGER)")
            st.executeUpdate("CREATE TABLE brand_word (word TEXT PRIMARY KEY)")
            // Per serving x10.
            st.executeUpdate("INSERT INTO product VALUES (1, 'Built', 'COCONUT BAR, COCONUT', 49, '1 bar (49g)', 1, 'bar', NULL, NULL, 1300, 170, 180, 45, 10, 60, 30, 1200, NULL, 12, NULL)")
            st.executeUpdate("INSERT INTO product VALUES (2, 'Oreo', 'CHOCOLATE SANDWICH COOKIES', 34, '3 cookies (34g)', 3, 'cooky', NULL, NULL, 1600, 10, 250, 70, 10, 140, 20, 1350, NULL, 40, NULL)")
            st.executeUpdate("INSERT INTO product VALUES (3, 'Oreo', 'FUDGE COVERED CHOCOLATE SANDWICH COOKIES', 21, '1 cookie (21g)', 1, 'cooky', NULL, NULL, 1100, 10, 140, 50, 5, 100, 30, 700, NULL, 1, NULL)")
            st.executeUpdate("INSERT INTO gtin VALUES ('850001886021', 1)")
            fun index(id: Int, brand: String, desc: String) {
                val b = LabelPackSource.words(brand).toSet()
                for (w in b + LabelPackSource.words(desc)) {
                    st.executeUpdate("INSERT INTO pword VALUES ('$w', $id, ${if (w in b) 1 else 0})")
                }
                b.forEach { st.executeUpdate("INSERT OR IGNORE INTO brand_word VALUES ('$it')") }
            }
            index(1, "Built", "COCONUT BAR, COCONUT")
            index(2, "Oreo", "CHOCOLATE SANDWICH COOKIES")
            index(3, "Oreo", "FUDGE COVERED CHOCOLATE SANDWICH COOKIES")
        }
        return LabelPackSource(JdbcSql(conn))
    }
}
