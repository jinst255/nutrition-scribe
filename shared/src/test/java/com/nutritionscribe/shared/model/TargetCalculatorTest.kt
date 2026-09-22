package com.nutritionscribe.shared.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calculator hands people the numbers they will be scored against every day,
 * so the failure that matters is not "slightly off" — it is producing a target
 * that is harmful or obviously absurd for a real body.
 */
class TargetCalculatorTest {

    private val activeMan = TargetCalculator.Body(
        sex = TargetCalculator.Sex.MALE,
        ageYears = 30,
        heightCm = 180.0,
        weightKg = 80.0,
        activity = TargetCalculator.Activity.MODERATE,
        aim = TargetCalculator.Aim.MAINTAIN,
    )

    private val smallWoman = TargetCalculator.Body(
        sex = TargetCalculator.Sex.FEMALE,
        ageYears = 55,
        heightCm = 157.0,
        weightKg = 54.0,
        activity = TargetCalculator.Activity.SEDENTARY,
        aim = TargetCalculator.Aim.MAINTAIN,
    )

    @Test
    fun `resting energy follows Mifflin-St Jeor`() {
        // 10*80 + 6.25*180 - 5*30 + 5 = 1780
        assertEquals(1780.0, TargetCalculator.restingEnergy(activeMan), 0.5)
        // 10*54 + 6.25*157 - 5*55 - 161 = 1085.25
        assertEquals(1085.25, TargetCalculator.restingEnergy(smallWoman), 0.5)
    }

    @Test
    fun `two different bodies do not get the same targets`() {
        // The whole point. The shipped defaults handed both of these people
        // 2200 kcal and 160 g of protein.
        val a = TargetCalculator.targetsFor(activeMan)
        val b = TargetCalculator.targetsFor(smallWoman)
        assertTrue(a[Metric.CALORIES]!! > b[Metric.CALORIES]!! + 500)
        assertTrue(a[Metric.PROTEIN]!! > b[Metric.PROTEIN]!!)
    }

    @Test
    fun `a weight-loss aim never prescribes starvation`() {
        // A 500 kcal deficit on a small sedentary person would otherwise land
        // under 800 kcal/day. The floor is resting energy, which is the line
        // between a helpful default and a harmful one.
        val cutting = smallWoman.copy(aim = TargetCalculator.Aim.LOSE)
        val kcal = TargetCalculator.targetsFor(cutting)[Metric.CALORIES]!!
        assertTrue(
            "a loss target must not fall below resting energy: $kcal",
            kcal >= TargetCalculator.restingEnergy(cutting) - 10,
        )
        assertTrue("and never below 1200: $kcal", kcal >= 1200)
    }

    @Test
    fun `macros roughly add back up to the calorie target`() {
        val t = TargetCalculator.targetsFor(activeMan)
        val fromMacros = t[Metric.PROTEIN]!! * 4 + t[Metric.CARBS]!! * 4 + t[Metric.FAT]!! * 9
        // Rounding to the nearest 5 g moves this a little; 8% is generous but
        // still tight enough to catch a real arithmetic mistake.
        val drift = kotlin.math.abs(fromMacros - t[Metric.CALORIES]!!) / t[Metric.CALORIES]!!
        assertTrue("macros drifted $drift from the calorie target", drift < 0.08)
    }

    @Test
    fun `targets land on numbers a person would write down`() {
        val t = TargetCalculator.targetsFor(activeMan)
        assertEquals(0.0, t[Metric.CALORIES]!! % 10, 0.001)
        assertEquals(0.0, t[Metric.PROTEIN]!! % 5, 0.001)
    }

    @Test
    fun `every metric gets a target`() {
        val t = TargetCalculator.targetsFor(activeMan)
        for (metric in Metric.entries) {
            assertNotNull("no target for ${metric.key}", t[metric])
            assertTrue("${metric.key} target must be positive", t[metric]!! > 0)
        }
    }

    @Test
    fun `only a reach metric gets a baseline`() {
        assertNotNull(TargetCalculator.baselineFor(Metric.PROTEIN, 160.0))
        // A limit's target already IS the line; a second threshold under it
        // would mean nothing.
        assertNull(TargetCalculator.baselineFor(Metric.SODIUM, 2300.0))
        assertEquals(120.0, TargetCalculator.baselineFor(Metric.PROTEIN, 160.0)!!, 0.001)
    }

    @Test
    fun `imperial input converts the way a bathroom scale does`() {
        assertEquals(81.6, TargetCalculator.poundsToKg(180.0), 0.1)
        assertEquals(177.8, TargetCalculator.feetInchesToCm(5, 10.0), 0.1)
    }

    @Test
    fun `moving more raises the calorie target`() {
        val still = TargetCalculator.targetsFor(
            activeMan.copy(activity = TargetCalculator.Activity.SEDENTARY),
        )[Metric.CALORIES]!!
        val moving = TargetCalculator.targetsFor(
            activeMan.copy(activity = TargetCalculator.Activity.VERY),
        )[Metric.CALORIES]!!
        assertTrue(moving > still)
    }
}
