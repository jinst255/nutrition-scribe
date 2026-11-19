package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recipes (round 10): the owner cooks chili for the week, and at their mom's house
 * gets a recipe with "about half a zucchini" added. A recipe is saved once, logged
 * a portion at a time, and a batch made differently counts for that week only
 * unless they keep the change.
 */
class RecipesTest {
    private val day = 24L * 60 * 60 * 1000
    private var now = 100 * day

    private val chiliParts = listOf(
        MealPart("Ground beef", "1 lb ground beef", 454.0, mapOf("calories" to 1150.0, "protein" to 77.0)),
        MealPart("Kidney beans", "2 cans kidney beans", 850.0, mapOf("calories" to 700.0, "protein" to 46.0)),
        MealPart("Onion", "1 onion", 150.0, mapOf("calories" to 60.0, "protein" to 2.0)),
        MealPart("Crushed tomatoes", "1 can crushed tomatoes", 794.0, mapOf("calories" to 290.0, "protein" to 13.0)),
    )

    private fun chili(servings: Double? = 8.0, pot: Double? = null) =
        Recipes.create("Mom's chili", chiliParts, servings, pot, now)

    private fun resolver(vararg foods: PersonalFood): Resolver {
        val store = InMemoryPersonalStore()
        foods.forEach { store.put(it) }
        return Resolver(store, TestDbs.usda, null, clock = { now })
    }

    @Test
    fun `a recipe's own numbers are one serving of the pot`() {
        val r = chili()
        assertEquals(2200.0 / 8, r.nutrients.getValue("calories"), 0.01)
        assertEquals(2248.0 / 8, r.grams!!, 0.01)
        assertTrue(r.isRecipe)
        assertFalse("a recipe is not logged whole like a saved meal", r.isMeal)
    }

    @Test
    fun `a bowl of their chili is one serving, with no AI`() {
        var asked = 0
        val ai = object : AiItemLookup {
            override fun lookup(fullText: String, items: List<ItemParser.Item>): List<AiItemAnswer?> { asked++; return items.map { null } }
        }
        val store = InMemoryPersonalStore().apply { put(chili()) }
        val res = Resolver(store, TestDbs.usda, null, ai, clock = { now })
        val bowl = res.resolve("a bowl of chili")
        assertEquals(0, asked)
        assertTrue(bowl.silent)
        val line = bowl.items.single()
        assertEquals(275.0, line.nutrients.getValue("calories"), 0.5)
        assertEquals("recipe:${Recipes.keyFor("Mom's chili")}", line.choice!!.id)
        assertEquals(137.5, resolver(chili()).resolve("half a bowl of mom's chili").items.single().nutrients.getValue("calories"), 0.5)
        assertEquals(550.0, resolver(chili()).resolve("2 servings of chili").items.single().nutrients.getValue("calories"), 0.5)
    }

    @Test
    fun `grams are that share of the pot's weight`() {
        val r = resolver(chili(servings = null, pot = 2000.0)).resolve("400 g of chili").items.single()
        assertEquals(2200.0 * 0.2, r.nutrients.getValue("calories"), 0.5)
        // No servings said and only a weight: about 300 g a serving.
        assertEquals(7.0, Recipes.servings(chili(servings = null, pot = 2000.0), now), 0.0)
    }

    @Test
    fun `a word of the recipe is not the recipe`() {
        val soup = Recipes.create("Chicken soup", chiliParts, 6.0, null, now)
        val r = resolver(soup).resolve("chicken").items.single()
        assertFalse(r.choice?.id.orEmpty().startsWith("recipe:"))
        assertNull(Recipes.find(listOf(chili()), "chili powder"))
        assertNotNull(Recipes.find(listOf(chili()), "mom's chili"))
        assertNotNull(Recipes.find(listOf(chili()), "chili"))
    }

    @Test
    fun `made chili with half a zucchini is this week's batch, with the change named`() {
        val said = Recipes.batchSaid("Made chili, added half a zucchini and no onion")!!
        assertEquals("chili", said.name)
        assertEquals("half a zucchini", said.added)
        assertEquals(listOf("onion"), said.without)
        val res = resolver(chili()).resolve("Made chili, added half a zucchini and no onion")
        assertNotNull(res.batchOf)
        assertFalse("a batch is checked, never logged silently", res.silent)
        val names = res.items.map { it.displayName.lowercase() }
        assertTrue(names.toString(), names.any { "zucchini" in it })
        assertTrue(names.toString(), names.none { "onion" in it })
        assertNull("not about making anything", Recipes.batchSaid("a bowl of chili"))
    }

    @Test
    fun `a batch counts for about five days, then the recipe again, unless kept`() {
        val zucchini = MealPart("Zucchini", "half a zucchini", 100.0, mapOf("calories" to 17.0))
        val batch = Recipes.startBatch(chili(), chiliParts + zucchini, now)
        assertEquals(listOf("added zucchini"), batch.recipe!!.batch!!.changes)
        assertEquals(2217.0 / 8, batch.nutrients.getValue("calories"), 0.01)
        assertTrue(Recipes.portion(batch, ItemParser.Item(said = "chili", food = "chili"), now + 2 * day).note.contains("this week's batch"))
        val later = Recipes.portion(batch, ItemParser.Item(said = "chili", food = "chili"), now + 6 * day)
        assertEquals(2200.0 / 8, later.nutrients.getValue("calories"), 0.01)
        val kept = Recipes.keepBatch(batch, now)
        assertNull(kept.recipe!!.batch)
        assertEquals(5, kept.parts.size)
        assertEquals(2217.0 / 8, Recipes.portion(kept, ItemParser.Item(said = "chili", food = "chili"), now + 30 * day).nutrients.getValue("calories"), 0.01)
    }

    @Test
    fun `changes say more, less, added and left out`() {
        val more = chiliParts.map { if (it.name == "Onion") it.copy(grams = 300.0) else it }.filter { it.name != "Kidney beans" }
        assertEquals(listOf("more onion", "no kidney beans"), Recipes.changes(chiliParts, more))
    }

    @Test
    fun `logging a portion never turns the recipe into a plain food`() {
        val store = InMemoryPersonalStore().apply { put(chili()) }
        val res = Resolver(store, TestDbs.usda, null, clock = { now })
        val line = res.resolve("a bowl of chili").items.single()
        res.learn(line.item, line.displayName, line.choice, line.grams, line.nutrients, key = line.key)
        val after = store.get(Recipes.keyFor("Mom's chili"))!!
        assertTrue(after.isRecipe)
        assertEquals(4, after.parts.size)
        assertEquals(2, after.timesUsed)
    }

    @Test
    fun `a recipe card's ingredient lines are found, not its title or steps`() {
        val card = """
            Mom's Chili
            Serves 8
            Ingredients
            1 lb ground beef
            2 cans kidney beans, drained
            ½ zucchini, diced
            - 1 onion
            salt and pepper to taste
            Directions
            1. Brown the beef.
            2. Add everything and simmer 1 hour.
        """.trimIndent()
        assertEquals(listOf("1 lb ground beef", "2 cans kidney beans, drained", "½ zucchini, diced", "1 onion", "salt and pepper to taste"),
            Recipes.cardIngredients(card))
        assertEquals(emptyList<String>(), Recipes.cardIngredients(""))
    }

    @Test
    fun `a personal food saved before recipes existed still reads, as no recipe`() {
        val json = """{"key":"chili","phrase":"chili","name":"Chili","nutrients":{"calories":300.0}}"""
        val f = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(PersonalFood.serializer(), json)
        assertFalse(f.isRecipe)
        val round = kotlinx.serialization.json.Json.encodeToString(PersonalFood.serializer(), chili())
        assertTrue(kotlinx.serialization.json.Json.decodeFromString(PersonalFood.serializer(), round).isRecipe)
    }

    @Test
    fun `saving a recipe learns its ingredients but never logs the pot as a meal`() {
        val store = InMemoryPersonalStore()
        val res = Resolver(store, TestDbs.usda, null, clock = { now })
        val lines = res.resolve("1 lb ground beef, 2 cans kidney beans, 1 onion").items
        val confirmed = lines.map { Resolver.Confirmed(it.item, it.key, it.displayName, it.choice, it.grams, it.nutrients) }
        val saved = res.saveRecipe("Chili", confirmed, servings = 6.0, potGrams = null)
        assertTrue(saved.isRecipe)
        assertEquals(3, saved.parts.size)
        assertTrue("no saved meal for the ingredient list", store.all().none { it.isMeal })
        assertFalse(res.resolve("1 lb ground beef, 2 cans kidney beans, 1 onion").silent && res.resolve("1 lb ground beef, 2 cans kidney beans, 1 onion").meal != null)
        // A batch on top of it, kept.
        val zuc = res.resolve("half a zucchini").items.single()
        val batch = res.saveRecipe("Chili", confirmed + Resolver.Confirmed(zuc.item, zuc.key, zuc.displayName, zuc.choice, zuc.grams, zuc.nutrients),
            servings = null, potGrams = null, batchOf = saved.key, keep = true)
        assertEquals(4, batch.parts.size)
        assertNull(batch.recipe!!.batch)
        assertEquals(6.0, batch.recipe!!.servings!!, 0.0)
    }

    @Test
    fun `makes is servings or the pot's weight, as typed`() {
        assertEquals(8.0 to null, Recipes.yieldOf("8"))
        assertEquals(6.0 to null, Recipes.yieldOf("6 servings"))
        assertEquals(null to 1800.0, Recipes.yieldOf("1800 g"))
        assertEquals(null to 1800.0, Recipes.yieldOf("1.8 kg"))
        assertEquals(453.592 * 4, Recipes.yieldOf("4 lb").second!!, 0.01)
        assertEquals(null to null, Recipes.yieldOf(""))
        assertEquals(null to null, Recipes.yieldOf("lots"))
        assertEquals(null to null, Recipes.yieldOf("0"))
    }

    /**
     * The repairs that run over saved foods on update read a recipe as one plain
     * food: its per-serving weight could be taken as a stuck rough weight and the
     * recipe forgotten, or its numbers summed back to the whole pot.
     */
    @Test
    fun `the saved-food repairs leave a recipe as a recipe`() {
        val r = chili()
        assertNull(FoodRepair.roughWeight(r))
        val fixed = FoodRepair.repair(r)?.first ?: r
        assertTrue(fixed.isRecipe)
        assertEquals(2200.0 / 8, fixed.nutrients.getValue("calories"), 1.0)
        assertNull(MicroFill.fill(r, { null }, { emptyList() }))
    }

    /** On the emulator a batch's lines read "1 whole" for "1 lb ground beef" and "2 cans kidney beans". */
    @Test
    fun `a recipe's lines keep the amounts that were said`() {
        val lines = resolver(chili()).resolve("made chili, added half a zucchini").items
        val beef = lines.first { "beef" in it.displayName.lowercase() }
        assertEquals("lb", beef.item.unit)
        assertEquals(1.0, beef.item.amount!!, 0.0)
        val beans = lines.first { "bean" in it.displayName.lowercase() }
        assertEquals(2.0, beans.item.amount!!, 0.0)
    }
}
