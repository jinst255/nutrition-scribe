package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.NutrientSanity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Problems from the second week of alpha logging (2026-10-17), each pinned down
 * with a made-up food of the same shape (no one's log is quoted).
 */
class Round8ProblemsTest {

    private fun parse(text: String) =
        ItemParser.parse(text, { w -> TestDbs.usda.knowsWord(w) }) { false }

    /** A label they scanned: [perServing] for one [servingG] serving. */
    private fun scanned(key: String, name: String, servingG: Double?, perServing: Map<String, Double>, servingText: String? = null) =
        PersonalFood(
            key = key, phrase = name, name = name,
            candidate = FoodCandidate(
                id = "scan:$key", name = name, origin = Origin.LABEL, perServing = perServing,
                per100g = servingG?.let { g -> perServing.mapValues { it.value * 100 / g } },
                labelServingGrams = servingG, labelServingText = servingText,
                portions = listOfNotNull(servingG?.let { Portion("1 serving", it) }),
            ),
            grams = servingG, nutrients = perServing, amount = 1.0, updatedAt = 1,
        )

    private val tuna = scanned("tuna", "tuna", 85.0, mapOf("calories" to 70.0, "protein" to 17.0))
    private val yogurt = scanned("chobani greek protein yogurt", "Chobani 20g protein greek yogurt", 190.0,
        mapOf("calories" to 140.0, "protein" to 20.0, "carbs" to 8.0))
    private val gummies = scanned("supplement vitamin", "Vitamin D supplements", null,
        mapOf("calories" to 10.0, "vitamin_d" to 50.0), servingText = "2 gummies")
    private val spinach = scanned("dole spinach baby", "Dole Baby Spinach", 85.0, mapOf("calories" to 20.0))

    private fun store(vararg foods: PersonalFood) = InMemoryPersonalStore().apply { foods.forEach { put(it) } }

    /** An AI that answers every item with a big guess, to prove theirs wins. */
    private val greedy = AiItemLookup { _, items ->
        items.map { AiItemAnswer(name = it.food, portion = "340 g", grams = 340.0, nutrients = mapOf("calories" to 394.0, "protein" to 80.0)) }
    }

    @Test
    fun `one third of a pepper is a third`() {
        assertEquals(1.0 / 3, parse("one third bell pepper").single().amount!!, 0.001)
        assertEquals(2.0 / 3, parse("two thirds of a cucumber").single().amount!!, 0.001)
        assertEquals(0.75, parse("three quarters of a banana").single().amount!!, 0.001)
        assertEquals("bell pepper", parse("one third bell pepper").single().food)
    }

    @Test
    fun `their scanned label beats a guess when the amount is in servings`() {
        val r = Resolver(store(tuna), TestDbs.usda, null, greedy, aiMode = Resolver.AiMode.ALL_NEW).resolve("two servings of tuna")
        val item = r.items.single()
        assertTrue(item.mine)
        assertEquals(140.0, item.nutrients.getValue("calories"), 1.0)
        assertEquals(170.0, item.grams!!, 1.0)
    }

    @Test
    fun `their label is found by brand and the protein they read off it`() {
        val r = Resolver(store(yogurt), TestDbs.usda, null).resolve("my typical 20 gram protein chobani greek yogurt")
        assertEquals("scan:chobani greek protein yogurt", r.items.single().choice?.id)
        // The protein has to agree: a 15 g one is another yogurt.
        val other = Resolver(store(yogurt), TestDbs.usda, null).resolve("a 12 gram protein chobani greek yogurt")
        assertFalse(other.items.single().choice?.id == "scan:chobani greek protein yogurt")
    }

    @Test
    fun `a typo and normal still find their supplement`() {
        val r = Resolver(store(gummies), TestDbs.usda, null).resolve("half of my normal vitamin D supliments")
        val item = r.items.single()
        assertEquals("scan:supplement vitamin", item.choice?.id)
        assertEquals(25.0, item.nutrients.getValue("vitamin_d"), 0.5)
    }

    @Test
    fun `baby carrots are not their baby spinach`() {
        val r = Resolver(store(spinach), TestDbs.usda, null).resolve("a couple baby carrots")
        assertFalse(r.items.single().choice?.id == "scan:dole spinach baby")
    }

    @Test
    fun `a decent amount scales the weight with the numbers`() {
        val butter = PersonalFood(key = "butter", phrase = "butter", name = "Butter", grams = 14.0,
            nutrients = mapOf("calories" to 100.0, "fat" to 11.5), updatedAt = 1)
        val item = Resolver(store(butter), TestDbs.usda, null).resolve("a decent amount of butter").items.single()
        val kcalPerGram = item.nutrients.getValue("calories") / item.grams!!
        assertTrue("$kcalPerGram kcal/g", kcalPerGram < 9.5)
    }

    @Test
    fun `forgetting keeps their own scans`() {
        val learned = PersonalFood(key = "baby carrot", phrase = "baby carrots", name = "Dole Baby Spinach",
            candidate = FoodCandidate(id = "usda:1", name = "Spinach", origin = Origin.USDA), grams = 85.0,
            nutrients = mapOf("calories" to 20.0), updatedAt = 1)
        val s = store(spinach, learned)
        val n = Resolver(s, TestDbs.usda, null).forget("a couple baby carrots", listOf("baby carrot", "dole spinach baby"))
        assertEquals(1, n)
        assertNull(s.get("baby carrot"))
        assertNotNull(s.get("dole spinach baby"))
    }

    @Test
    fun `incl read as inci still finds added sugars`() {
        val p = NutritionFactsParser.parse("Total Sugars 16g\nInci. 6g Added Sugars 12%\nProtein 0g")
        assertEquals(6.0, p.perServing.getValue(Metric.ADDED_SUGAR.key), 0.0)
    }

    @Test
    fun `servings per container and ingredients are never values`() {
        val p = NutritionFactsParser.parse(
            "Ingredients: whole wheat flour, water, sugar, yeast\n17 servings per container\nServing size 1 slice (40g)\n" +
                "Calories 100\nTotal Sugars 3g\nProtein 5g\nCholesterol 20ng\nLet product stand 2 minutes, folic acid 2",
        )
        assertEquals(3.0, p.perServing.getValue(Metric.SUGAR.key), 0.0)
        assertEquals(20.0, p.perServing.getValue(Metric.CHOLESTEROL.key), 0.0)
        assertFalse(Metric.FOLATE.key in p.perServing)
    }

    @Test
    fun `a scanned bread knows its slice`() {
        assertEquals(40.0, NutritionFactsParser.unitPortion("1 slice (40g)", 40.0)!!.grams, 0.0)
        assertEquals("1 cup", NutritionFactsParser.unitPortion("2/3 cup (88g)", 88.0)!!.label)
        assertEquals(132.0, NutritionFactsParser.unitPortion("2/3 cup (88g)", 88.0)!!.grams, 0.1)
    }

    @Test
    fun `parts that outgrow their whole are flagged`() {
        val codes = NutrientSanity.codes(mapOf("sugar" to 16.0, "added_sugar" to 24.0, "fat" to 5.0, "saturated_fat" to 4.0, "trans_fat" to 2.5))
        assertTrue("added_over_sugar" in codes)
        assertTrue("parts_over_fat" in codes)
    }

    @Test
    fun `the sugar goal is measured against added sugar`() {
        assertEquals(Metric.ADDED_SUGAR, Metric.SUGAR.goalMeasure)
        assertEquals("Total sugars", Metric.SUGAR.panelLabel)
        assertEquals("Added sugars", Metric.ADDED_SUGAR.panelLabel)
    }

    // From the 300-entry benchmark (2026-10-18).

    /** An AI that answers each item with [answers] by its first said word. */
    private fun ai(vararg answers: Pair<String, AiItemAnswer>) = AiItemLookup { _, items ->
        items.map { i -> answers.firstOrNull { (w, _) -> w in i.food }?.second ?: AiItemAnswer(i.food, null, null, emptyMap()) }
    }

    @Test
    fun `cups glued to the number are cups`() {
        val item = parse("1.5c penne").single()
        assertEquals(1.5, item.amount!!, 0.0)
        assertEquals("cup", item.unit)
        assertEquals("penne", item.food)
    }

    @Test
    fun `a splash or a drizzle is a little`() {
        val milk = parse("a splash of milk").single()
        assertTrue(milk.little || milk.topping)
        assertTrue(parse("a dab of hummus").single().let { it.little || it.topping })
    }

    @Test
    fun `a drizzle of their honey is not their usual jar`() {
        val honey = scanned("honey wildflower", "Wildflower Honey", 100.0, mapOf("calories" to 304.0, "sugar" to 81.0))
        val answer = AiItemAnswer("honey", "1 tsp", 7.0, mapOf("calories" to 21.0))
        val item = Resolver(store(honey), TestDbs.usda, null, ai("honey" to answer), aiMode = Resolver.AiMode.ALL_NEW)
            .resolve("a little drizzle of honey").items.single()
        assertTrue("${item.grams}", item.grams!! <= 10.0)
    }

    @Test
    fun `a plate the model gave no numbers asks instead of logging nothing`() {
        val blank = AiItemAnswer("Regular Shaka plate", "1 plate", 0.0, emptyMap())
        val r = Resolver(store(), TestDbs.usda, null, ai("shaka" to blank), aiMode = Resolver.AiMode.ALL_NEW)
            .resolve("the regular shaka plate")
        val item = r.items.single()
        assertTrue(item.nutrients.isNotEmpty() || (item.weak && item.status == Resolver.Status.UNKNOWN))
    }

    @Test
    fun `a gala apple is not sour apple candy`() {
        assumeTrue(TestDbs.labels != null)
        val apple = AiItemAnswer("Gala apple", "1 apple (182 g)", 182.0, mapOf("calories" to 95.0, "carbs" to 25.0))
        val item = Resolver(store(), TestDbs.usda, TestDbs.labels, ai("apple" to apple), aiMode = Resolver.AiMode.ALL_NEW)
            .resolve("and later a gala apple").items.single()
        assertTrue("${item.choice?.name} ${item.nutrients["calories"]}", item.nutrients.getValue("calories") < 150.0)
    }
}
