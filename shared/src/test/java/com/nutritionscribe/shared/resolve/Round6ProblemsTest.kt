package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.model.Metric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Problems from the alpha debug log of 2026-09-30, each pinned down with a
 * made-up sentence of the same shape (no one's log is quoted).
 */
class Round6ProblemsTest {

    private fun parse(text: String) =
        ItemParser.parse(text, { w -> TestDbs.usda.knowsWord(w) }) { false }

    @Test
    fun `less than a serving starts a new food`() {
        val items = parse("three servings of granola a little less than a serving of dried cranberries probably almost 2 servings of almond butter")
        assertEquals(listOf("granola", "dried cranberries", "almond butter"), items.map { it.food })
        assertEquals(3.0, items[0].amount!!, 0.0)
        assertTrue("a little less than one", items[1].amount!! in 0.7..0.9)
        assertTrue("almost two", items[2].amount!! in 1.6..1.9)
    }

    @Test
    fun `a hedge before a number does not glue two foods together`() {
        val items = parse("half a cucumber probably three cherry tomatoes")
        assertEquals(listOf("cucumber", "cherry tomatoes"), items.map { it.food })
        assertEquals(3.0, items[1].amount!!, 0.0)
    }

    @Test
    fun `a bag that consists of things is those things`() {
        val items = parse("my lunch bag of veggies which consists of about half a cucumber probably three stalks of celery baby carrots")
        val foods = items.map { it.food }
        assertTrue(foods.toString(), "cucumber" in foods)
        assertTrue(foods.toString(), "celery" in foods)
        assertTrue(foods.toString(), "baby carrots" in foods)
        assertEquals(3, items.size)
        assertEquals(3.0, items.first { it.food == "celery" }.amount!!, 0.0)
        assertEquals("stalk", items.first { it.food == "celery" }.unit)
    }

    @Test
    fun `two vegetables said back to back are two foods`() {
        assertEquals(listOf("celery", "baby carrots"), parse("celery baby carrots").map { it.food })
        // ...but a dish named after its fruit is still one food.
        assertEquals(1, parse("strawberry banana smoothie").size)
        assertEquals(1, parse("carrot cake").size)
        assertEquals(1, parse("apple pie").size)
    }

    @Test
    fun `the dish name said after its parts is not another helping`() {
        val items = parse("2 servings of granola, a cup of milk. over night granola")
        assertEquals(listOf("granola", "milk"), items.map { FoodQuery_last(it.food) })
    }

    private fun FoodQuery_last(food: String) = com.nutritionscribe.shared.food.FoodQuery.contentWords(food).last()

    @Test
    fun `flavoring in a drink is syrup`() {
        val items = parse("a 20 oz root beer with flavoring in it vanilla")
        assertEquals(2, items.size)
        assertTrue(items[1].food, "syrup" in items[1].food && "vanilla" in items[1].food)
        assertTrue(items[1].topping)
    }

    @Test
    fun `ounces of a drink are fluid ounces`() {
        val soda = FoodCandidate(id = "t:soda", name = "Cola", origin = Origin.USDA,
            per100g = mapOf("calories" to 42.0), portions = emptyList(), defaultGrams = 368.0)
        val g = Portions.gramsFor(soda, ItemParser.parse("16 oz cola").single())!!.grams
        assertTrue("16 fl oz is about 473 mL, not 454 g; got $g", g in 470.0..500.0)
        val steak = FoodCandidate(id = "t:steak", name = "Steak", origin = Origin.USDA,
            per100g = mapOf("calories" to 250.0), portions = emptyList(), defaultGrams = 85.0)
        assertEquals(16 * 28.3495, Portions.gramsFor(steak, ItemParser.parse("16 oz steak").single())!!.grams, 0.1)
    }

    @Test
    fun `a parent company's name is not the brand`() {
        val labels = TestDbs.labels
        assumeTrue("needs -DlabelPack", labels != null)
        val top = labels!!.search("dr pepper").first()
        assertTrue("${top.brand} | ${top.name}", "pepper" in "${top.brand} ${top.name}".lowercase().replace("seven up", ""))
        assertFalse("${top.brand} | ${top.name}", "root beer" in top.name.lowercase())
    }

    @Test
    fun `a supplement facts panel in two columns is paired by row`() {
        // Names in one column, amounts in another: OCR returns all the names, then
        // all the amounts. The line boxes put each back on its row.
        val lines = listOf(
            NutritionFactsParser.OcrLine("Supplement Facts", 100, 100, 700, 150),
            NutritionFactsParser.OcrLine("Serving Size: 2 Gummies", 100, 160, 400, 190),
            NutritionFactsParser.OcrLine("Calories", 100, 260, 220, 290),
            NutritionFactsParser.OcrLine("Total Carbohydrate", 100, 300, 330, 330),
            NutritionFactsParser.OcrLine("Total Sugars", 120, 340, 290, 370),
            NutritionFactsParser.OcrLine("Includes 3 g Added Sugars", 140, 380, 420, 410),
            NutritionFactsParser.OcrLine("Vitamin C (as ascorbic acid)", 100, 420, 420, 450),
            NutritionFactsParser.OcrLine("Sodium", 100, 460, 200, 490),
            NutritionFactsParser.OcrLine("15", 450, 262, 490, 288),
            NutritionFactsParser.OcrLine("4 g", 450, 302, 500, 328),
            NutritionFactsParser.OcrLine("3 g", 450, 342, 500, 368),
            NutritionFactsParser.OcrLine("250 mg", 450, 422, 540, 448),
            NutritionFactsParser.OcrLine("10 mg", 450, 462, 530, 488),
        )
        val p = NutritionFactsParser.parse(lines)
        assertEquals(15.0, p.perServing[Metric.CALORIES.key]!!, 0.0)
        assertEquals(4.0, p.perServing[Metric.CARBS.key]!!, 0.0)
        assertEquals(3.0, p.perServing[Metric.SUGAR.key]!!, 0.0)
        assertEquals(3.0, p.perServing[Metric.ADDED_SUGAR.key]!!, 0.0)
        assertEquals(250.0, p.perServing[Metric.VITAMIN_C.key]!!, 0.0)
        assertEquals(10.0, p.perServing[Metric.SODIUM.key]!!, 0.0)
        assertNull("no potassium on this label", p.perServing[Metric.POTASSIUM.key])
    }

    @Test
    fun `added sugars and trans fat are read from a label`() {
        val p = NutritionFactsParser.parse("""
            Nutrition Facts
            Serving size 1 bar (40g)
            Calories 190
            Total Fat 8g
            Saturated Fat 3g
            Trans Fat 0.5g
            Total Carbohydrate 24g
            Total Sugars 12g
            Includes 10g Added Sugars
            Protein 6g
        """.trimIndent())
        assertEquals(12.0, p.perServing[Metric.SUGAR.key]!!, 0.0)
        assertEquals(10.0, p.perServing[Metric.ADDED_SUGAR.key]!!, 0.0)
        assertEquals(0.5, p.perServing[Metric.TRANS_FAT.key]!!, 0.0)
        assertEquals(8.0, p.perServing[Metric.FAT.key]!!, 0.0)
    }

    @Test
    fun `rotated text is left as it was read`() {
        // Lines taller than wide (a label shot sideways) are not regrouped by row.
        val lines = listOf(
            NutritionFactsParser.OcrLine("Calories 160, Total Fat 3.5g", 600, 400, 640, 1400),
            NutritionFactsParser.OcrLine("Protein 15g, Sodium 100mg", 560, 400, 600, 1400),
        )
        val p = NutritionFactsParser.parse(lines)
        assertEquals(160.0, p.perServing[Metric.CALORIES.key]!!, 0.0)
        assertEquals(15.0, p.perServing[Metric.PROTEIN.key]!!, 0.0)
    }

    @Test
    fun `natural and added sugar are split by what the food is`() {
        assertEquals(0.0, Sugars.addedFraction("Raspberries, raw", Origin.USDA)!!, 0.0)
        assertEquals(0.0, Sugars.addedFraction("Milk, whole, 3.25% milkfat", Origin.USDA)!!, 0.0)
        assertEquals(1.0, Sugars.addedFraction("Honey", Origin.USDA)!!, 0.0)
        assertEquals(1.0, Sugars.addedFraction("Raspberry Syrup", Origin.LABEL)!!, 0.0)
        assertEquals(1.0, Sugars.addedFraction("Dr Pepper Soda", Origin.LABEL)!!, 0.0)
        assertEquals(0.0, Sugars.addedFraction("Sun-dried raisins", Origin.LABEL)!!, 0.0)
        assertNull("a cookie could be anything", Sugars.addedFraction("Chocolate chip cookie", Origin.AI))
    }

    @Test
    fun `nothing is invented for a label that did not say`() {
        // A scanned label with no added-sugar line: unknown, not zero.
        val n = Sugars.withAddedSugar(mapOf("sugar" to 8.0), "Protein bar", Origin.LABEL)
        assertNull(n["added_sugar"])
        assertNotNull(Sugars.withAddedSugar(mapOf("sugar" to 8.0), "Honey", Origin.USDA)["added_sugar"])
    }
}
