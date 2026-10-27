package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitPanelsTest {
    private fun line(t: String, x: Int, y: Int, w: Int = 90) = NutritionFactsParser.OcrLine(t, x, y, x + w, y + 12)

    private fun panel(x: Int, title: String, kcal: Int, fat: Int, protein: Int) = listOf(
        line(title, x, 0),
        line("Nutrition Facts", x, 20),
        line("Serving size 1 bag (34g)", x, 40),
        line("Calories $kcal", x, 60),
        line("Total Fat ${fat}g", x, 80),
        line("Total Carbohydrate 15g", x, 100),
        line("Protein ${protein}g", x, 120),
    )

    @Test
    fun `three labels side by side are three panels`() {
        val lines = panel(0, "Berry mix", 170, 11, 5) + panel(120, "Nut mix", 190, 14, 6) + panel(240, "Tropical mix", 160, 9, 3)
        val parts = NutritionFactsParser.splitPanels(lines)
        assertEquals(3, parts.size)
        assertEquals(listOf("Berry mix", "Nut mix", "Tropical mix"), parts.map { it.title })
        assertEquals(listOf(170.0, 190.0, 160.0), parts.map { NutritionFactsParser.parse(it.lines).perServing["calories"] })
    }

    @Test
    fun `one label is left whole`() {
        val lines = panel(0, "Oats", 150, 3, 5)
        val parts = NutritionFactsParser.splitPanels(lines)
        assertEquals(1, parts.size)
        assertEquals(lines.size, parts.single().lines.size)
    }
}
