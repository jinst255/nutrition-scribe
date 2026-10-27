package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Problems from a week of alpha logging (2026-10-09), each pinned down with a
 * made-up sentence of the same shape (no one's log is quoted).
 */
class Round7ProblemsTest {

    private fun parse(text: String) =
        ItemParser.parse(text, { w -> TestDbs.usda.knowsWord(w) }) { false }

    private fun fresh(store: PersonalStore = InMemoryPersonalStore()) = Resolver(store, TestDbs.usda, null)

    @Test
    fun `a dish counted once multiplies what was in each`() {
        val items = parse("three tacos for lunch, each with some ground beef and a little bit of lettuce, and I put some salsa in each one")
        val beef = items.first { "beef" in it.food }
        assertEquals(3.0, beef.amount!!, 0.0)
        val lettuce = items.first { "lettuce" in it.food }
        assertTrue("a little bit", lettuce.little)
        assertTrue("every part belongs to the tacos", items.all { it.group != null })
        assertFalse("the meal word is not a food", items.any { "lunch" in it.food })
    }

    @Test
    fun `a little less than one in each is a fraction of each`() {
        val items = parse("two wraps, each with some turkey, about an egg, a little less than an egg in each one")
        assertEquals(1.6, items.first { it.food == "egg" }.amount!!, 0.01)
    }

    @Test
    fun `a half after a is the amount`() {
        val items = parse("rye toast with a ½ banana")
        assertEquals(listOf("rye toast", "banana"), items.map { it.food })
        assertEquals(0.5, items[1].amount!!, 0.0)
    }

    @Test
    fun `a food said twice keeps the better measure`() {
        val items = parse("a bowl of granola, maybe a cup of milk and probably about one and a half cups of granola")
        assertEquals(listOf("granola", "milk"), items.map { it.food })
        assertEquals("cup", items[0].unit)
        assertEquals(1.5, items[0].amount!!, 0.0)
    }

    @Test
    fun `some starts a new food`() {
        assertEquals(listOf("egg", "kale", "ham"), parse("a fried egg some kale and some ham").map { it.food.removePrefix("fried ") })
    }

    @Test
    fun `the one said after a food says which one`() {
        val items = parse("plain greek yogurt, the 15 gram protein vanilla one")
        assertEquals(1, items.size)
        assertTrue(items[0].food, "vanilla" in items[0].food)
        assertEquals(15.0, items[0].statedProteinG!!, 0.0)
    }

    @Test
    fun `a filling is a filling's worth, not a bag's serving`() {
        val r = fresh().resolve("two wraps, each with some spinach")
        val spinach = r.items.first { "spinach" in it.item.food }
        assertEquals(40.0, spinach.grams!!, 1.0)
    }

    @Test
    fun `a plate of greens is a salad's worth`() {
        val g = fresh().resolve("a plateful of lettuce").items.single().grams!!
        assertTrue("$g g", g in 60.0..120.0)
    }

    @Test
    fun `a little bit of a remembered food is a little of it`() {
        val store = InMemoryPersonalStore()
        store.put(PersonalFood(key = "cheddar cheese", phrase = "cheddar cheese", name = "Cheddar", grams = 80.0,
            nutrients = mapOf("calories" to 320.0)))
        val r = fresh(store).resolve("a little bit of cheddar cheese").items.single()
        assertEquals(80.0 * Portions.LITTLE, r.grams!!, 0.5)
    }

    private fun label(key: String, name: String, grams: Double, kcal: Double, at: Long) = PersonalFood(
        key = key, phrase = name, name = name, grams = grams, nutrients = mapOf("calories" to kcal), updatedAt = at,
        candidate = FoodCandidate(id = "label:$at", name = name, origin = Origin.LABEL, per100g = mapOf("calories" to kcal * 100 / grams),
            labelServingGrams = grams, labelServingText = "1 slice (${grams.toInt()} g)", portions = listOf(Portion("1 slice", grams))),
    )

    @Test
    fun `a staple uses the brand they had last, with the others offered`() {
        val store = InMemoryPersonalStore()
        store.put(label(PersonalKeys.of("acme white bread"), "White Bread", 28.0, 70.0, at = 1))
        store.put(label(PersonalKeys.of("brandx whole wheat bread"), "Whole Wheat Bread", 40.0, 100.0, at = 2))
        store.put(label(PersonalKeys.of("cheesy bean burrito"), "Bean and cheese (burrito)", 140.0, 300.0, at = 3))
        val r = fresh(store).resolve("2 slices of bread").items.single()
        assertEquals("Whole Wheat Bread", r.choice!!.name)
        assertEquals(80.0, r.grams!!, 0.5)
        assertTrue(r.alternates.any { it.name == "White Bread" })
        // "cheese" is not the bean and cheese burrito.
        val cheese = fresh(store).resolve("some cheese").items.single()
        assertFalse(cheese.choice?.name.orEmpty(), cheese.choice?.name.orEmpty().contains("burrito"))
    }

    @Test
    fun `a scanned label is not replaced by a guess under the same words`() {
        val store = InMemoryPersonalStore()
        val scanned = label("cereal oat", "Oat cereal", 42.0, 160.0, at = 1)
        store.put(scanned)
        val res = fresh(store)
        res.learn(ItemParser.Item(said = "a bowl of oat cereal", food = "oat cereal", amount = 1.0, unit = "bowl"), "oat cereal",
            FoodCandidate(id = "ai:1", name = "oat cereal", origin = Origin.AI), 67.0, mapOf("calories" to 270.0), key = "cereal oat")
        val kept = store.get("cereal oat")!!
        assertEquals(Origin.LABEL, kept.candidate!!.origin)
        assertEquals(160.0 * 67.0 / 42.0, kept.nutrients["calories"]!!, 0.5)
    }

    @Test
    fun `their usual with one part swapped`() {
        val store = InMemoryPersonalStore()
        store.put(PersonalFood(key = "milk powder protein", phrase = "protein powder and milk", name = "Protein powder, milk",
            nutrients = mapOf("calories" to 300.0), updatedAt = 5,
            parts = listOf(MealPart("Protein powder", "protein powder", 40.0, mapOf("calories" to 150.0)),
                MealPart("Whole milk", "milk", 240.0, mapOf("calories" to 150.0)))))
        val r = fresh(store).resolve("my usual protein shake, but instead of milk I used water")
        val names = r.items.map { it.displayName.lowercase() }
        assertTrue(names.toString(), names.any { "powder" in it })
        assertFalse(names.toString(), names.any { "milk" in it })
        assertTrue(names.toString(), names.any { "water" in it })
        assertEquals(150.0, r.total["calories"]!!, 1.0)
    }

    @Test
    fun `typically my usual, said the way it is already saved, is the saved meal`() {
        // Note S40: the saved shake was the one made with milk, and "typically" missed it.
        val store = InMemoryPersonalStore()
        store.put(PersonalFood(key = "instead milk powder protein shake used water", phrase = "my typical protein shake, but instead of water I used milk",
            name = "Whole milk, protein powder", nutrients = mapOf("calories" to 300.0), updatedAt = 5,
            parts = listOf(MealPart("Protein powder", "protein powder", 40.0, mapOf("calories" to 150.0)),
                MealPart("Whole milk", "milk", 240.0, mapOf("calories" to 150.0)))))
        val r = fresh(store).resolve("typically protein shake, but with milk instead of water")
        assertTrue(r.items.toString(), r.items.isNotEmpty() && r.items.all { it.status == Resolver.Status.REMEMBERED })
        assertEquals(300.0, r.total["calories"]!!, 1.0)
    }

    @Test
    fun `a supplement is never a protein powder or a milk`() {
        val r = fresh().resolve("vitamin d supplements").items.single()
        val name = r.choice?.name.orEmpty().lowercase()
        assertFalse(name, "protein" in name || "milk" in name)
    }

    @Test
    fun `a cup of a solid with no cup on record is not weighed as water`() {
        val c = FoodCandidate(id = "ai:x", name = "toasted oat cereal", origin = Origin.AI, per100g = mapOf("calories" to 380.0))
        val p = Portions.gramsFor(c, ItemParser.Item(said = "2.5 cups", food = "toasted oat cereal", amount = 2.5, unit = "cup"))
        assertNotNull(p)
        assertTrue("${p!!.grams} g", p.grams < 150)
    }
}
