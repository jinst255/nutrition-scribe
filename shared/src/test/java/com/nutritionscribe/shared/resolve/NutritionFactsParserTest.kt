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
}
