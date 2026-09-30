package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionFactsParserTest {

    @Test
    fun `reads a standard panel as OCR returns it`() {
        val ocr = """
            Nutrition Facts
            8 servings per container
            Serving size 2/3 cup (55g)
            Amount per serving
            Calories 230
            % Daily Value*
            Total Fat 8g 10%
            Saturated Fat 1g 5%
            Trans Fat 0g
            Cholesterol 0mg 0%
            Sodium 160mg 7%
            Total Carbohydrate 37g 13%
            Dietary Fiber 4g 14%
            Total Sugars 12g
            Includes 10g Added Sugars 20%
            Protein 3g
        """.trimIndent()
        val p = NutritionFactsParser.parse(ocr)
        assertEquals(55.0, p.servingGrams!!, 0.0)
        assertEquals(230.0, p.perServing["calories"]!!, 0.0)
        assertEquals(8.0, p.perServing["fat"]!!, 0.0)
        assertEquals(1.0, p.perServing["saturated_fat"]!!, 0.0)
        assertEquals(160.0, p.perServing["sodium"]!!, 0.0)
        assertEquals(37.0, p.perServing["carbs"]!!, 0.0)
        assertEquals(4.0, p.perServing["fiber"]!!, 0.0)
        assertEquals(12.0, p.perServing["sugar"]!!, 0.0)
        assertEquals(3.0, p.perServing["protein"]!!, 0.0)
        assertTrue(p.looksComplete)
    }

    @Test
    fun `copes with values on the next line and run-together text`() {
        val ocr = "Calories\n190\nProtein7g\nSodium 0.14g\nTotal Fat 16 g"
        val p = NutritionFactsParser.parse(ocr)
        assertEquals(190.0, p.perServing["calories"]!!, 0.0)
        assertEquals(7.0, p.perServing["protein"]!!, 0.0)
        assertEquals(140.0, p.perServing["sodium"]!!, 0.01)
        assertEquals(16.0, p.perServing["fat"]!!, 0.0)
    }

    @Test
    fun `a panel becomes a label candidate that scales by grams`() {
        val p = NutritionFactsParser.parse("Serving size 1 bar (60g)\nCalories 200\nProtein 20g")
        val c = p.asCandidate("Protein bar")
        assertEquals(Origin.LABEL, c.origin)
        assertEquals(400.0, c.nutrientsFor(120.0)["calories"]!!, 0.01)
    }

    @Test
    fun `reads the label micronutrients in their own units`() {
        val ocr = """
            Serving size 1 bottle (414mL)
            Calories 230
            Total Fat 3.5g 4%
            Cholesterol 15mg 5%
            Sodium 250mg 11%
            Protein 42g
            Vitamin D3 2mcg 10%
            Calcium 900mg 70%
            Iron 0.1mg 0%
            Potassium 750mg 15%
        """.trimIndent()
        val p = NutritionFactsParser.parse(ocr)
        assertEquals(15.0, p.perServing["cholesterol"]!!, 0.0)
        assertEquals(2.0, p.perServing["vitamin_d"]!!, 0.0)
        assertEquals(900.0, p.perServing["calcium"]!!, 0.0)
        assertEquals(0.1, p.perServing["iron"]!!, 1e-9)
        assertEquals(750.0, p.perServing["potassium"]!!, 0.0)
        assertEquals(3.5, p.perServing["fat"]!!, 0.0)
    }

    @Test
    fun `an old label's percent-only micronutrient becomes an amount`() {
        val p = NutritionFactsParser.parse("Calories 100\nProtein 5g\nCalcium 20%\nVitamin D 400 IU")
        assertEquals(260.0, p.perServing["calcium"]!!, 0.01)
        assertEquals(10.0, p.perServing["vitamin_d"]!!, 0.01)
    }

    @Test
    fun `a g read as 9 is taken back off, and the log says so`() {
        // A curved peanut butter jar: every "g" came out as "9".
        val ocr = """
            Serving size 2 tbsp (329)
            Calories 190
            Total Fat 169
            Saturated Fat 39
            Sodium 140m9
            Total Carbohydrate 79
            Dietary Fiber 29
            Total Sugars 49
            Protein 79
        """.trimIndent()
        val p = NutritionFactsParser.parse(ocr)
        assertEquals(32.0, p.servingGrams!!, 0.0)
        assertEquals(16.0, p.perServing["fat"]!!, 0.0)
        assertEquals(3.0, p.perServing["saturated_fat"]!!, 0.0)
        assertEquals(140.0, p.perServing["sodium"]!!, 0.0)
        assertEquals(7.0, p.perServing["carbs"]!!, 0.0)
        assertEquals(2.0, p.perServing["fiber"]!!, 0.0)
        assertEquals(4.0, p.perServing["sugar"]!!, 0.0)
        assertEquals(7.0, p.perServing["protein"]!!, 0.0)
        assertTrue(p.issues.isEmpty())
        val sugar = p.reads.first { it.metric == "sugar" }
        assertEquals("g_read_as_9", sugar.fix)
        assertEquals(49.0, sugar.asRead!!, 0.0)
    }

    @Test
    fun `a real 9 at the end stays when the panel only adds up with it`() {
        // The "g" was dropped, not misread: 19 g of carbs, 12 g of sugar.
        val ocr = "Calories 150\nTotal Fat 5g\nTotal Carbohydrate 19\nTotal Sugars 12g\nProtein 6g"
        val p = NutritionFactsParser.parse(ocr)
        assertEquals(19.0, p.perServing["carbs"]!!, 0.0)
        assertEquals(null, p.reads.first { it.metric == "carbs" }.fix)
    }

    @Test
    fun `a misread that does not add up is reported`() {
        val p = NutritionFactsParser.parse("Serving size 1 cup (240g)\nCalories 110\nTotal Fat 0g\nTotal Carbohydrate 7g\nTotal Sugars 45g\nProtein 20g")
        assertEquals(listOf("sugar_over_carbs"), p.issues.map { it.code })
    }

    // From the 2026-09 phone log: rolled oats logged at 3.5 calories (the fat under
    // a "Calories" printed apart from its number), and a bread whose cholesterol
    // came out as its sodium, 135 mg, because "0mg" was read as "Omg".
    @Test
    fun `calories printed apart from the word are found, not the fat below`() {
        val ocr = """
            Nutrition Facts
            Serving size 1/2 cup (40g)
            Amount per serving
            Calories
            Total Fat 3.5g 4%
            Saturated Fat 0.5g 3%
            Cholesterol Omg 0%
            Sodium 0mg 0%
            Total Carbohydrate 27g 10%
            Dietary Fiber 4g 14%
            Total Sugars 1g
            Protein 5g
            150
        """.trimIndent()
        val p = NutritionFactsParser.parse(ocr)
        assertEquals(150.0, p.perServing["calories"]!!, 0.0)
        assertEquals(3.5, p.perServing["fat"]!!, 0.0)
        assertEquals(0.0, p.perServing["cholesterol"]!!, 0.0)
        assertEquals(0.0, p.perServing["sodium"]!!, 0.0)
        assertTrue(p.issues.isEmpty())
    }

    @Test
    fun `a nutrient never takes the next nutrient's number`() {
        val p = NutritionFactsParser.parse("Calories 80\nTotal Fat 1.5g\nCholesterol\nSodium 135mg\nTotal Carbohydrate 15g\nProtein 4g")
        assertEquals(null, p.perServing["cholesterol"])
        assertEquals(135.0, p.perServing["sodium"]!!, 0.0)
        assertEquals(0.0, NutritionFactsParser.parse("Cholesterol Omg\nSodium 135mg").perServing["cholesterol"]!!, 0.0)
    }

    @Test
    fun `a stray number that does not fit the macros is not taken as calories`() {
        val p = NutritionFactsParser.parse("Calories\nTotal Fat 3g\nTotal Carbohydrate 9g\nProtein 20g\n12")
        assertEquals(null, p.perServing["calories"])
    }
}
