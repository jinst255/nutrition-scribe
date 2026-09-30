package com.nutritionscribe.shared.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutrientSanityTest {

    @Test
    fun `a consistent food has no issues`() {
        // Peanut butter, 32 g.
        val n = mapOf("calories" to 190.0, "protein" to 7.0, "carbs" to 7.0, "fat" to 16.0, "sugar" to 3.0, "fiber" to 2.0, "saturated_fat" to 3.0)
        assertTrue(NutrientSanity.check(n, 32.0).isEmpty())
    }

    @Test
    fun `parts bigger than their whole are caught`() {
        val n = mapOf("carbs" to 7.0, "sugar" to 49.0, "fiber" to 9.0, "fat" to 2.0, "saturated_fat" to 5.0)
        assertEquals(listOf("sugar_over_carbs", "fiber_over_carbs", "satfat_over_fat"), NutrientSanity.codes(n))
    }

    @Test
    fun `calories far from the macros are caught, and small rounding is not`() {
        val off = mapOf("calories" to 400.0, "protein" to 5.0, "carbs" to 10.0, "fat" to 2.0)
        assertEquals(listOf("kcal_mismatch"), NutrientSanity.codes(off))
        val rounded = mapOf("calories" to 110.0, "protein" to 20.0, "carbs" to 7.0, "fat" to 0.0)
        assertTrue(NutrientSanity.codes(rounded).isEmpty())
    }

    @Test
    fun `more macros than the weight is caught`() {
        val n = mapOf("calories" to 240.0, "protein" to 60.0, "carbs" to 0.0, "fat" to 0.0)
        assertTrue("macros_over_weight" in NutrientSanity.codes(n, 30.0))
    }
}
