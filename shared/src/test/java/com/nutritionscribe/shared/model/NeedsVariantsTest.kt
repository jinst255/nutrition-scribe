package com.nutritionscribe.shared.model

import com.nutritionscribe.shared.model.TargetCalculator.Activity
import com.nutritionscribe.shared.model.TargetCalculator.Aim
import com.nutritionscribe.shared.model.TargetCalculator.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * About you, for eight made-up people across the range the form allows, checked
 * against the published figures each number is meant to come from. The table is
 * written to build/reports/needs.txt for reading by eye.
 */
class NeedsVariantsTest {

    private data class Person(val name: String, val p: Profile)

    private fun person(
        name: String, sex: Sex, age: Int, feet: Int, inches: Double, lb: Double, activity: Activity, aim: Aim,
        stage: Profile.LifeStage = Profile.LifeStage.NONE, periods: Boolean? = null,
    ) = Person(name, Profile(sex, age, TargetCalculator.feetInchesToCm(feet, inches), TargetCalculator.poundsToKg(lb),
        activity, aim, stage = stage, periods = periods))

    private val people = listOf(
        person("small sedentary woman, 34, losing", Sex.FEMALE, 34, 5, 2.0, 125.0, Activity.SEDENTARY, Aim.LOSE),
        person("active man, 22, gaining", Sex.MALE, 22, 6, 1.0, 175.0, Activity.MODERATE, Aim.GAIN),
        person("very active big man, 28", Sex.MALE, 28, 6, 4.0, 230.0, Activity.VERY, Aim.MAINTAIN),
        person("woman, 58, light activity", Sex.FEMALE, 58, 5, 5.0, 150.0, Activity.LIGHT, Aim.MAINTAIN),
        person("pregnant woman, 29", Sex.FEMALE, 29, 5, 6.0, 160.0, Activity.LIGHT, Aim.LOSE, stage = Profile.LifeStage.PREGNANT),
        person("man, 75, sitting, heavy", Sex.MALE, 75, 5, 9.0, 240.0, Activity.SEDENTARY, Aim.LOSE),
        person("girl, 15, active", Sex.FEMALE, 15, 5, 4.0, 115.0, Activity.MODERATE, Aim.MAINTAIN),
        person("woman, 41, no periods", Sex.FEMALE, 41, 5, 7.0, 140.0, Activity.MODERATE, Aim.MAINTAIN, periods = false),
        person("breastfeeding woman, 31", Sex.FEMALE, 31, 5, 4.0, 145.0, Activity.LIGHT, Aim.LOSE, stage = Profile.LifeStage.BREASTFEEDING),
    )

    private val shown = listOf(
        Metric.CALORIES, Metric.PROTEIN, Metric.CARBS, Metric.FAT, Metric.FIBER, Metric.ADDED_SUGAR, Metric.SATURATED_FAT,
        Metric.SODIUM, Metric.IRON, Metric.CALCIUM, Metric.VITAMIN_D, Metric.FOLATE, Metric.POTASSIUM, Metric.CAFFEINE,
    )

    @Test
    fun `every person gets numbers in the published ranges`() {
        val out = StringBuilder()
        for ((name, p) in people) {
            val n = Needs.forProfile(p)
            out.append(name.padEnd(36)).append(shown.joinToString("  ") { "${it.key}=${n.getValue(it).let { v -> if (v >= 100) v.toInt().toString() else "%.1f".format(v) }}" }).append('\n')
            val kcal = n.getValue(Metric.CALORIES)
            val kg = p.weightKg
            // Calories: never under resting energy or 1,200, never absurd.
            assertTrue("$name kcal $kcal", kcal >= maxOf(TargetCalculator.restingEnergy(p.body), 1200.0) - 10 && kcal <= 4500)
            // Protein: 0.8 g/kg RDA floor, at most 2.2 g/kg of the weight it is worked on.
            val prot = n.getValue(Metric.PROTEIN)
            assertTrue("$name protein $prot", prot >= 0.8 * TargetCalculator.proteinWeight(p.body) - 5 && prot <= 2.2 * kg + 30)
            // The three macros add back up to the calories (within rounding).
            val sum = 4 * prot + 4 * n.getValue(Metric.CARBS) + 9 * n.getValue(Metric.FAT)
            assertEquals("$name macros", kcal, sum, 40.0)
            // Carbs at least the 130 g RDA floor the brain runs on (175 g pregnant).
            assertTrue("$name carbs", n.getValue(Metric.CARBS) >= if (p.stage == Profile.LifeStage.PREGNANT) 175.0 else 130.0)
            // Limits are limits: 10% of calories, sodium 2,300.
            assertEquals(kcal * 0.10 / 4, n.getValue(Metric.ADDED_SUGAR), 5.0)
            assertEquals(if (p.ageYears <= 13) 1800.0 else 2300.0, n.getValue(Metric.SODIUM), 0.0)
            assertTrue("$name fiber", n.getValue(Metric.FIBER) in 17.0..60.0)
        }
        java.io.File(System.getProperty("reportDir") ?: "build/reports").apply { mkdirs() }.resolve("needs.txt").writeText(out.toString())
    }

    @Test
    fun `iron follows periods, pregnancy and age`() {
        fun iron(i: Int) = Needs.forProfile(people[i].p).getValue(Metric.IRON)
        assertEquals("woman with periods", 18.0, iron(0), 0.0)
        assertEquals("man", 8.0, iron(1), 0.0)
        assertEquals("after 50", 8.0, iron(3), 0.0)
        assertEquals("pregnant", 27.0, iron(4), 0.0)
        assertEquals("teen girl", 15.0, iron(6), 0.0)
        assertEquals("no periods", 8.0, iron(7), 0.0)
        assertEquals("breastfeeding", 9.0, iron(8), 0.0)
    }

    @Test
    fun `life stage figures come from the reference intakes`() {
        val preg = Needs.forProfile(people[4].p)
        assertEquals(600.0, preg.getValue(Metric.FOLATE), 0.0)
        assertEquals(200.0, preg.getValue(Metric.CAFFEINE), 0.0)
        val old = Needs.forProfile(people[5].p)
        assertEquals(20.0, old.getValue(Metric.VITAMIN_D), 0.0)
        assertEquals(1200.0, old.getValue(Metric.CALCIUM), 0.0)
        val teen = Needs.forProfile(people[6].p)
        assertEquals(1300.0, teen.getValue(Metric.CALCIUM), 0.0)
        assertEquals("a teen's caffeine", 100.0, teen.getValue(Metric.CAFFEINE), 0.0)
    }

    @Test
    fun `losing weight while pregnant changes nothing`() {
        val losing = people[4].p
        val same = losing.copy(aim = Aim.MAINTAIN)
        assertEquals(Needs.forProfile(same).getValue(Metric.CALORIES), Needs.forProfile(losing).getValue(Metric.CALORIES), 0.0)
        assertEquals(Needs.forProfile(same).getValue(Metric.PROTEIN), Needs.forProfile(losing).getValue(Metric.PROTEIN), 0.0)
    }

    @Test
    fun `a thirteen year old has the younger sodium limit`() {
        val kid = person("boy, 13", Sex.MALE, 13, 5, 1.0, 100.0, Activity.MODERATE, Aim.MAINTAIN).p
        assertEquals(1800.0, Needs.forProfile(kid).getValue(Metric.SODIUM), 0.0)
    }
}
