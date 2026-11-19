package com.nutritionscribe.shared.model

import com.nutritionscribe.shared.model.TargetCalculator.Activity
import com.nutritionscribe.shared.model.TargetCalculator.Aim
import com.nutritionscribe.shared.model.TargetCalculator.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SetupBodyTest {

    private fun typed(sex: Sex? = Sex.FEMALE, age: String = "41", metric: Boolean = false, feet: String = "5", inches: String = "4",
                      cm: String = "", pounds: String = "140", kg: String = "") =
        TargetCalculator.bodyFromTyped(sex, age, metric, feet, inches, cm, pounds, kg, Activity.LIGHT, Aim.MAINTAIN)

    /**
     * Review G3 (2026-11-04): setup's calculator opened as a 30-year-old man,
     * 5'10", 170 lb, and saved that as "About you" on Fill in my targets even when
     * nobody had typed a thing. Untouched, or half typed, it is nobody's body.
     */
    @Test
    fun `an untouched calculator is nobody's body`() {
        assertNull(typed(sex = null, age = "", feet = "", inches = "", pounds = ""))
        assertNull("no sex picked", typed(sex = null))
        assertNull("no age", typed(age = ""))
        assertNull("no weight", typed(pounds = ""))
        assertNull("not an age", typed(age = "4"))
    }

    @Test
    fun `a typed body is read in either units`() {
        val b = typed()!!
        assertEquals(162.6, b.heightCm, 0.1)
        assertEquals(63.5, b.weightKg, 0.1)
        assertNotNull(typed(metric = true, cm = "170", kg = "65", feet = "", pounds = ""))
        // "5 ft" with the inches left empty is 5 ft.
        assertEquals(152.4, typed(inches = "")!!.heightCm, 0.1)
    }
}
