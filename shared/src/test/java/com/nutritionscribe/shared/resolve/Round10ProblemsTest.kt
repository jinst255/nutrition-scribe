package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.food.FoodQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Problems from the beta testers' week on 1.6-test (logs sent 2026-11-04) and the
 * owner's notes to 2026-11-14, each pinned down with a made-up food of the same
 * shape (no one's log is quoted).
 */
class Round10ProblemsTest {

    private fun parse(text: String) =
        ItemParser.parse(text, { w -> TestDbs.usda.knowsWord(w) }) { false }

    private fun store(vararg foods: PersonalFood) = InMemoryPersonalStore().apply { foods.forEach { put(it) } }

    /**
     * F1. A saved meal said again came back as the meal alone, with no lines, and
     * the pipeline builds an entry from its lines: a tester's usual oatmeal logged at
     * 0 kcal three mornings running. A remembered meal must come back as its parts,
     * adding up to the meal.
     */
    @Test
    fun `a saved meal said again comes back with its parts and their calories`() {
        val res = Resolver(store(), TestDbs.usda, null)
        res.saveMeal(
            "bowl of oatmeal with a spoon of brown sugar", "Oatmeal, Brown sugar",
            listOf(
                MealPart("Oatmeal", "bowl of oatmeal", 234.0, mapOf("calories" to 166.0, "protein" to 6.0)),
                MealPart("Brown sugar", "a spoon of brown sugar", 9.0, mapOf("calories" to 34.0)),
            ),
        )
        val r = res.resolve("bowl of oatmeal with a spoon of brown sugar")
        assertTrue("logs without a card", r.silent)
        assertEquals(2, r.items.size)
        assertEquals(200.0, sumNutrients(r.items.map { it.nutrients }).getValue("calories"), 0.01)
        assertTrue(r.items.all { it.status == Resolver.Status.REMEMBERED })
    }

    /**
     * "1½ tbsp peanut butter" was read as "1 0.5 tbsp": the 1 went into the food's
     * name and the amount came out as half a tablespoon. Then the tester stepped it
     * back up by hand, which is how their peanut butter lost half its weight (see
     * ItemDraftTest in the app). "1 1/2 cups" broke the same way.
     */
    @Test
    fun `a whole number and a fraction are one amount`() {
        val a = parse("1½ tbsp peanut butter").single()
        assertEquals(1.5, a.amount!!, 0.001)
        assertEquals("tbsp", a.unit)
        assertEquals("peanut butter", a.food)
        assertEquals(1.5, parse("1 ½ cups of rice").single().amount!!, 0.001)
        assertEquals(1.5, parse("1 1/2 cups of rice").single().amount!!, 0.001)
        assertEquals(2.25, parse("2¼ cups milk").single().amount!!, 0.001)
        // A bare fraction is still a fraction, and a lean ratio is still not one.
        assertEquals(0.5, parse("½ cup of rice").single().amount!!, 0.001)
        assertEquals(0.25, parse("1/4 cup of rice").single().amount!!, 0.001)
        assertTrue(FoodQuery.normaliseNumbers("1½ tbsp").contains("1.5"))
    }

    /**
     * Review F6, and a tester's week offline: "cottage cheese with pineapple" made
     * the pineapple a whole one, 905 g and 452 kcal, with no warning. Nothing said
     * about how much is a serving, never a whole melon; the card says how big a
     * whole one would have been.
     */
    @Test
    fun `nothing said about a huge food is a serving, not the whole thing`() {
        val r = Resolver(store(), TestDbs.usda, null)
        val p = r.resolve("cottage cheese with pineapple").items.first { "pineapple" in it.item.food }
        assertTrue("was ${p.grams}", p.grams!! < 300)
        assertTrue(p.portionNote!!.contains("whole one"))
        // A count said is that many, however big.
        assertTrue(r.resolve("2 pineapples").items.single().grams!! > 1000)
    }

    /**
     * A tester logged "bowl of oats" three mornings: raw oats weighed as a cooked
     * bowl, 234 g and 886 kcal each time. A bowl of oats is a bowl of oatmeal; a
     * cup of oats stays dry oats, which is how a recipe measures them.
     */
    @Test
    fun `a bowl of oats is cooked oatmeal`() {
        val r = Resolver(store(), TestDbs.usda, null)
        val bowl = r.resolve("bowl of oats").items.single()
        assertTrue("was ${bowl.nutrients["calories"]}", bowl.nutrients.getValue("calories") < 300)
        assertTrue(r.resolve("a bowl of overnight oats").items.single().nutrients.getValue("calories") < 400)
        assertTrue(r.resolve("1 cup of rolled oats").items.single().choice!!.name.contains("raw", ignoreCase = true) ||
            r.resolve("1 cup of rolled oats").items.single().nutrients.getValue("calories") > 250)
    }

    /**
     * A tester: "sandwhich bag of baby carrots, celery sticks, snap peas, and
     * broccoli" logged a blank sandwich. The bag is how much, not a food.
     */
    @Test
    fun `a bag of something is an amount, not a food`() {
        val carrots = parse("sandwhich bag of baby carrots, celery sticks, snap peas, and broccoli")
        assertEquals("baby carrots", carrots.first().food)
        assertEquals(4, carrots.size)
        assertEquals("grapes", parse("a ziploc bag of grapes").single().food)
        assertEquals("pretzels", parse("a snack bag of pretzels").single().food)
    }

    /**
     * A tester: "1/3 digiorno 4 cheese rising crust pizza" read the 4 as four of
     * something, and the pizza came apart. A number that names a kind of food
     * (four-cheese, 21-seed, three-bean) is part of its name.
     */
    @Test
    fun `a number in a product's name is not a count`() {
        val pizza = parse("1/3 digiorno 4 cheese rising crust pizza")
        assertEquals(1, pizza.size)
        assertEquals(1.0 / 3, pizza.single().amount!!, 0.001)
        assertEquals(null, parse("Dave's killer 21 seed bread").single().amount)
        assertEquals(null, parse("3 bean chili").single().amount)
        // A real count stays one.
        assertEquals(4.0, parse("4 cheese sticks").single().amount!!, 0.0)
        assertEquals(4.0, parse("I had 4 cheese sticks").single().amount!!, 0.0)
        assertEquals(2.0, parse("2 slices of 12 grain bread").single().amount!!, 0.0)
    }

    /**
     * Owner, 2026-10-28: "My typical protein shake with water instead of milk."
     * came back as nothing at all. Only "my typical X, but/except …" was read as
     * their usual with a swap; said without the "but", it fell through to a food
     * called "typical protein shake".
     */
    @Test
    fun `their usual with a swap, said without a but`() {
        val shake = PersonalFood(key = "milk powder protein", phrase = "protein powder and milk", name = "Protein powder, milk",
            nutrients = mapOf("calories" to 300.0), updatedAt = 5,
            parts = listOf(MealPart("Protein powder", "protein powder", 40.0, mapOf("calories" to 150.0)),
                MealPart("Whole milk", "milk", 240.0, mapOf("calories" to 150.0))))
        for (said in listOf("My typical protein shake with water instead of milk.", "my usual protein shake, with water instead of milk",
                "my normal protein shake using water instead of milk")) {
            val r = Resolver(store(shake), TestDbs.usda, null).resolve(said)
            val names = r.items.map { it.displayName.lowercase() }
            assertTrue("$said: $names", names.any { "powder" in it })
            assertTrue("$said: $names", names.none { "milk" in it })
            assertEquals(said, 150.0, r.total["calories"]!!, 1.0)
        }
    }

    /**
     * A tester's one complaint after a week: "Slow". Every card waited for the AI
     * (1.5 to 7 s). The local answer comes at once, without asking the AI, and the
     * app knows whether the full lookup would ask it at all.
     */
    @Test
    fun `the local answer is ready without the AI, and says whether the AI is wanted`() {
        var calls = 0
        val ai = object : AiItemLookup, SentenceLookup {
            override fun lookup(fullText: String, items: List<ItemParser.Item>): List<AiItemAnswer?> { calls++; return items.map { null } }
            override fun lookupSentence(fullText: String, known: List<ItemParser.Item>): List<AiItemAnswer> { calls++; return emptyList() }
        }
        val eggs = PersonalFood(key = "egg", phrase = "eggs", name = "Eggs", amount = 2.0, grams = 100.0,
            nutrients = mapOf("calories" to 143.0), updatedAt = 1)
        val res = Resolver(store(eggs), TestDbs.usda, null, ai)
        val quick = res.resolveLocalOnly("a bowl of oatmeal and 2 eggs")
        assertEquals(0, calls)
        assertEquals(2, quick.items.size)
        assertTrue("the oatmeal is new: the AI would fill it", res.wantsAi("a bowl of oatmeal and 2 eggs"))
        assertTrue("their eggs alone need nothing", !res.wantsAi("2 eggs"))
        assertEquals(0, calls)
    }

    /** A honey label: "1 tbsp (21 g)", 60 kcal. */
    private val honeyLabel = FoodCandidate(
        id = "label:honey", name = "Wildflower Honey", origin = Origin.LABEL,
        perServing = mapOf("calories" to 60.0), per100g = mapOf("calories" to 60.0 * 100 / 21),
        labelServingGrams = 21.0, labelServingText = "1 tbsp (21 g)",
        portions = listOf(Portion("1 serving", 21.0), Portion("1 tbsp", 21.0)),
    )

    /**
     * "A teaspoon of honey" against a label that only says tablespoons fell to the
     * rough 0.55 g/mL guess, 2.7 g, and was remembered: a third of the real 7 g.
     * Any volume on the label gives the food's own density.
     */
    @Test
    fun `a teaspoon is a third of the label's tablespoon`() {
        val tsp = Portions.gramsForOne(honeyLabel, ItemParser.Item(said = "1 tsp honey", food = "honey", amount = 1.0, unit = "tsp"))!!
        assertEquals(7.0, tsp.grams, 0.05)
        assertEquals(44.4, Portions.volumeMl("3 Tablespoons")!!, 0.1)
        assertEquals(null, Portions.volumeMl("1 slice"))
    }

    /** Learned before 1.7: 1.5 tbsp of an AI peanut butter at 8.13 g each, and the calories with it. */
    private val roughPb = PersonalFood(
        key = "butter peanut", phrase = "peanut butter", name = "Peanut butter",
        candidate = FoodCandidate(id = "ai:pb", name = "Peanut butter", origin = Origin.AI,
            perServing = mapOf("calories" to 72.0), per100g = mapOf("calories" to 587.5)),
        amount = 1.5, unit = "tbsp", grams = 12.20175, nutrients = mapOf("calories" to 71.7),
        unitGrams = mapOf("tbsp" to 8.1345),
    )

    /**
     * Owner, 2026-11-12: "Why is this an estimate? I literally scanned the nutrition
     * label yesterday." The label is called "Real Bacon Pieces"; they had saved it
     * as "low sodium Great Value bacon", and said "that low-sodium bacon". Only the
     * label's own name was searched, so "low" and "sodium" missed and the AI
     * guessed. The words they gave it count too: a short key, never a sentence's.
     */
    @Test
    fun `their scanned label is found by the words they gave it`() {
        val bacon = PersonalFood(
            key = "bacon great low sodium value", phrase = "low sodium great value bacon", name = "Great Value — Real Bacon Pieces",
            candidate = FoodCandidate(id = "label:bacon", name = "Real Bacon Pieces", brand = "Great Value", origin = Origin.LABEL,
                perServing = mapOf("calories" to 60.0), per100g = mapOf("calories" to 60.0 * 100 / 15), labelServingGrams = 15.0,
                labelServingText = "1 slice (15 g)", portions = listOf(Portion("1 slice", 15.0), Portion("1 serving", 15.0))),
            amount = 1.5, unit = "piece", grams = 22.5, nutrients = mapOf("calories" to 90.0), updatedAt = 1,
        )
        // A whole sentence saved under a label must not lend it its words ("baby carrots" became spinach).
        val sentence = PersonalFood(
            key = "baby bag broccoli carrot celery dole pea snap spinach", phrase = "a bag of baby carrots, celery, snap peas and dole spinach",
            name = "Dole Baby Spinach", candidate = FoodCandidate(id = "label:spinach", name = "Baby Spinach", brand = "Dole", origin = Origin.LABEL,
                perServing = mapOf("calories" to 20.0), labelServingGrams = 85.0), grams = 85.0, nutrients = mapOf("calories" to 20.0), updatedAt = 2,
        )
        val res = Resolver(store(bacon, sentence), TestDbs.usda, null)
        val item = res.resolveLocal(parse("two pieces of that low-sodium bacon").single())
        assertEquals("label:bacon", item.choice?.id)
        assertTrue(item.mine)
        assertEquals(120.0, item.nutrients.getValue("calories"), 1.0)
        val carrots = res.resolveLocal(parse("baby carrots").single())
        assertTrue(carrots.choice?.id != "label:spinach")
    }

    private class SentenceAi(private val answers: List<AiItemAnswer>) : AiItemLookup, SentenceLookup {
        override fun lookup(fullText: String, items: List<ItemParser.Item>): List<AiItemAnswer?> = items.map { null }
        override fun lookupSentence(fullText: String, known: List<ItemParser.Item>) = answers
    }

    /**
     * Owner, 2026-11-03: "There should be no need to estimate these things when
     * both labels are scanned." Said as "protein powder serving of whole milk", the
     * app read one food, the AI read two, and the AI's two were never checked
     * against the labels they had scanned. Every part the AI reads is looked for
     * among their own foods first: the AI may say how much, never what.
     */
    @Test
    fun `parts the AI splits out are their scanned labels when they have them`() {
        fun label(key: String, name: String, brand: String, servingG: Double, kcal: Double) = PersonalFood(
            key = key, phrase = name, name = "$brand — $name",
            candidate = FoodCandidate(id = "label:$key", name = name, brand = brand, origin = Origin.LABEL,
                perServing = mapOf("calories" to kcal), per100g = mapOf("calories" to kcal * 100 / servingG),
                labelServingGrams = servingG, portions = listOf(Portion("1 serving", servingG))),
            amount = 1.0, unit = "serving", grams = servingG, nutrients = mapOf("calories" to kcal), updatedAt = 1,
        )
        val milk = label("great milk value whole", "Vitamin D Whole Milk", "Great Value", 240.0, 150.0)
        val powder = label("powder protein", "Chocolate Protein Powder", "Metabolic", 32.0, 130.0)
        val ai = SentenceAi(listOf(
            AiItemAnswer("Protein powder", "1 scoop", 30.0, mapOf("calories" to 120.0), count = 1.0, unit = "scoop"),
            AiItemAnswer("Whole milk", "1 cup", 244.0, mapOf("calories" to 149.0), count = 1.0, unit = "serving"),
        ))
        val r = Resolver(store(milk, powder), TestDbs.usda, null, ai).resolve("Protein powder serving of whole milk.")
        assertEquals(listOf("label:powder protein", "label:great milk value whole"), r.items.map { it.choice?.id })
        assertTrue(r.items.all { it.mine })
        assertEquals(150.0, r.items[1].nutrients.getValue("calories"), 1.0)
    }

    @Test
    fun `a food remembered at the rough volume weight is mended or forgotten`() {
        // Nothing to say what a tablespoon really weighs: forgotten, asked afresh next time.
        assertEquals(FoodRepair.WeightFix.Forget, FoodRepair.roughWeight(roughPb))
        // A label that says: the weight and the calories scale back together.
        val honey = PersonalFood(key = "honey", phrase = "honey", name = "Wildflower Honey", candidate = honeyLabel,
            amount = 1.0, unit = "tsp", grams = 2.7115, nutrients = mapOf("calories" to 7.75), unitGrams = mapOf("tsp" to 2.7115))
        val fixed = (FoodRepair.roughWeight(honey) as FoodRepair.WeightFix.Fixed).food
        assertEquals(7.0, fixed.grams!!, 0.05)
        assertEquals(20.0, fixed.nutrients.getValue("calories"), 0.3)
        assertEquals(7.0, fixed.unitGrams.getValue("tsp"), 0.05)
        // A packaged meal remembered at 2.7 g with no measure: one label serving.
        val tray = PersonalFood(key = "meatloaf", phrase = "meatloaf", name = "Meatloaf dinner",
            candidate = honeyLabel.copy(id = "label:tray", name = "Meatloaf dinner", labelServingGrams = 283.0, labelServingText = "1 tray",
                portions = listOf(Portion("1 tray", 283.0))),
            grams = 2.7115, nutrients = mapOf("calories" to 3.0), unitGrams = mapOf("each" to 2.7115))
        assertEquals(283.0, (FoodRepair.roughWeight(tray) as FoodRepair.WeightFix.Fixed).food.grams!!, 0.01)
        // A food with real weights is left alone.
        assertEquals(null, FoodRepair.roughWeight(roughPb.copy(grams = 24.0, unitGrams = mapOf("tbsp" to 16.0))))
    }
}
