package com.nutritionscribe.app.ui.confirm

import com.nutritionscribe.shared.model.Metric
import org.junit.Assert.assertEquals
import org.junit.Test

class NumbersLineTest {
    /** Review F7: the card's line read "40 g carbohy · 3 g saturat", the first 7 letters of each name. */
    @Test
    fun `the numbers line uses each nutrient's short label`() {
        val line = numbersLine(mapOf("calories" to 200.0, "protein" to 8.0, "carbs" to 40.0, "saturated_fat" to 3.0),
            listOf(Metric.CARBS, Metric.SATURATED_FAT))
        assertEquals("200 kcal · 8 g protein · 40 g carbs · 3 g sat. fat", line)
        assertEquals("no numbers yet", numbersLine(emptyMap(), emptyList()))
    }
}
