package com.nutritionscribe.shared.model

import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.FoodRepair
import com.nutritionscribe.shared.resolve.Origin
import com.nutritionscribe.shared.resolve.PersonalFood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Needs judged against the wrong yardstick: total sugar against the 50 g
 * added-sugar DV, everyone's iron against a woman's 18 mg, carbs against a
 * 2,000 kcal day; and scans saved with impossible numbers.
 */
class NeedsTest {

    private val man = Profile(
        TargetCalculator.Sex.MALE, 30, 180.0, 90.0,
        TargetCalculator.Activity.MODERATE, TargetCalculator.Aim.MAINTAIN,
    )

    @Test
    fun `a man's minerals are a man's, not the label's`() {
        val n = Needs.forProfile(man)
        assertEquals(8.0, n[Metric.IRON]!!, 0.0)
        assertEquals(400.0, n[Metric.MAGNESIUM]!!, 0.0)
        assertEquals(3400.0, n[Metric.POTASSIUM]!!, 0.0)
        val woman = Needs.forProfile(man.copy(sex = TargetCalculator.Sex.FEMALE, heightCm = 165.0, weightKg = 60.0))
        assertEquals(18.0, woman[Metric.IRON]!!, 0.0)
        assertEquals(8.0, woman[Metric.ZINC]!!, 0.0)
        // After menopause, iron drops to a man's figure and calcium rises.
        val older = Needs.forProfile(man.copy(sex = TargetCalculator.Sex.FEMALE, ageYears = 60))
        assertEquals(8.0, older[Metric.IRON]!!, 0.0)
        assertEquals(1200.0, older[Metric.CALCIUM]!!, 0.0)
    }

    @Test
    fun `sugar is judged as total sugar, and scales with the day`() {
        // Fruit sugar counts in every source the app has, so the 50 g added-sugar
        // DV filled on a bowl of oats with raisins.
        assertEquals(90.0, Needs.forProfile(null)[Metric.SUGAR]!!, 0.0)
        val n = Needs.forProfile(man)
        val kcal = n[Metric.CALORIES]!!
        assertTrue(kcal > 2800)
        assertEquals(kcal * 0.18 / 4, n[Metric.SUGAR]!!, 5.0)
        assertTrue("carbs scale to the day: ${n[Metric.CARBS]}", n[Metric.CARBS]!! > 300)
    }

    @Test
    fun `protein is not asked of fat mass`() {
        val heavy = TargetCalculator.Body(TargetCalculator.Sex.MALE, 40, 175.0, 130.0,
            TargetCalculator.Activity.LIGHT, TargetCalculator.Aim.LOSE)
        val p = TargetCalculator.targetsFor(heavy)[Metric.PROTEIN]!!
        assertTrue("1.5 g/kg of 130 kg would be 195 g; got $p", p in 120.0..150.0)
        // Sitting all day needs less than training does.
        val still = TargetCalculator.targetsFor(man.body.copy(activity = TargetCalculator.Activity.SEDENTARY))[Metric.PROTEIN]!!
        val hard = TargetCalculator.targetsFor(man.body.copy(activity = TargetCalculator.Activity.VERY))[Metric.PROTEIN]!!
        assertTrue(hard > still + 50)
    }

    @Test
    fun `pregnancy and plant diets move the right numbers`() {
        val her = man.copy(sex = TargetCalculator.Sex.FEMALE, ageYears = 29, heightCm = 165.0, weightKg = 62.0)
        val base = Needs.forProfile(her)
        val pregnant = Needs.forProfile(her.copy(stage = Profile.LifeStage.PREGNANT))
        assertEquals(27.0, pregnant[Metric.IRON]!!, 0.0)
        assertEquals(600.0, pregnant[Metric.FOLATE]!!, 0.0)
        assertEquals(200.0, pregnant[Metric.CAFFEINE]!!, 0.0)
        assertEquals(base[Metric.PROTEIN]!! + 25, pregnant[Metric.PROTEIN]!!, 5.0)
        assertTrue(pregnant[Metric.CALORIES]!! > base[Metric.CALORIES]!! + 300)
        // A pregnant "lose weight" does not cut.
        val cutting = Needs.forProfile(her.copy(stage = Profile.LifeStage.PREGNANT, aim = TargetCalculator.Aim.LOSE))
        assertEquals(pregnant[Metric.CALORIES]!!, cutting[Metric.CALORIES]!!, 0.0)

        val vegan = Needs.forProfile(man.copy(diet = Profile.Diet.VEGAN))
        assertEquals(14.4, vegan[Metric.IRON]!!, 0.01)
        assertEquals(16.5, vegan[Metric.ZINC]!!, 0.01)
        // Pregnancy is ignored for a man, rather than trusted.
        assertEquals(8.0, Needs.forProfile(man.copy(stage = Profile.LifeStage.PREGNANT))[Metric.IRON]!!, 0.0)
    }

    @Test
    fun `plasma donation follows the FDA rule and puts the protein back`() {
        val mon = LocalDate.of(2026, 9, 28)
        assertEquals(PlasmaDonation.Check.Allowed, PlasmaDonation.check(mon, emptyList()))
        assertEquals(PlasmaDonation.Check.AlreadyToday, PlasmaDonation.check(mon, listOf(mon)))
        // Tuesday after Monday is too soon; Wednesday is fine.
        val tooSoon = PlasmaDonation.check(mon.plusDays(1), listOf(mon)) as PlasmaDonation.Check.TooSoon
        assertEquals(mon.plusDays(2), tooSoon.earliest)
        assertEquals(PlasmaDonation.Check.Allowed, PlasmaDonation.check(mon.plusDays(2), listOf(mon)))
        // Monday and Wednesday done: the third must wait for next Monday.
        val third = PlasmaDonation.check(mon.plusDays(5), listOf(mon, mon.plusDays(2))) as PlasmaDonation.Check.TooSoon
        assertEquals(mon.plusDays(7), third.earliest)
        assertEquals(PlasmaDonation.Check.Allowed, PlasmaDonation.check(mon.plusDays(7), listOf(mon, mon.plusDays(2))))

        // 90 kg is the 800 mL band: 56 g of protein, half today and half tomorrow.
        assertEquals(56.0, PlasmaDonation.proteinLost(90.0), 0.0)
        assertEquals(44.0, PlasmaDonation.proteinLost(60.0), 0.0)
        assertEquals(28.0, PlasmaDonation.extraProtein(mon, listOf(mon), 90.0), 0.0)
        assertEquals(28.0, PlasmaDonation.extraProtein(mon.plusDays(1), listOf(mon), 90.0), 0.0)
        assertEquals(0.0, PlasmaDonation.extraProtein(mon.plusDays(2), listOf(mon), 90.0), 0.0)
    }

    @Test
    fun `a label saved with impossible numbers is repaired`() {
        // An oats label scanned with the fat line read as calories.
        val oats = mapOf("calories" to 3.5, "protein" to 7.0, "carbs" to 34.0, "fat" to 3.5, "sugar" to 7.0, "fiber" to 6.0)
        val r = NutrientRepair.repair(oats, 52.0)
        assertEquals(196.0, r.nutrients["calories"]!!, 0.0)
        assertEquals(7.0, r.nutrients["sugar"]!!, 0.0)
        // A peanut butter scan claiming 73.5 g of sugar in 12 g of carbs.
        val pb = NutrientRepair.repair(mapOf("calories" to 285.0, "protein" to 10.5, "carbs" to 12.0, "fat" to 22.5, "sugar" to 73.5), 48.0)
        assertNull(pb.nutrients["sugar"])
        assertEquals(285.0, pb.nutrients["calories"]!!, 0.0)
        // A consistent food is untouched.
        assertTrue(NutrientRepair.repair(mapOf("calories" to 190.0, "protein" to 7.0, "carbs" to 8.0, "fat" to 15.0, "sugar" to 4.0), 32.0).fixes.isEmpty())
    }

    @Test
    fun `a remembered scan is repaired all the way down, so log again is right`() {
        val per100 = mapOf("calories" to 6.73, "protein" to 13.46, "carbs" to 65.38, "fat" to 6.73)
        val food = PersonalFood(
            key = "oats", phrase = "bowl of oats", name = "bowl of oats",
            candidate = FoodCandidate("scan:1", "oats", Origin.LABEL, per100g = per100,
                perServing = mapOf("calories" to 3.5, "protein" to 7.0, "carbs" to 34.0, "fat" to 3.5), labelServingGrams = 52.0),
            grams = 52.0, nutrients = mapOf("calories" to 3.5, "protein" to 7.0, "carbs" to 34.0, "fat" to 3.5),
        )
        val (fixed, fixes) = FoodRepair.repair(food)!!
        assertFalse(fixes.isEmpty())
        assertEquals(196.0, fixed.nutrients["calories"]!!, 0.0)
        assertEquals(376.0, fixed.candidate!!.per100g!!["calories"]!!, 1.0)
        assertEquals(196.0, fixed.candidate!!.perServing!!["calories"]!!, 0.0)
        assertNull(FoodRepair.repair(fixed))
    }

    @Test
    fun `an entry's totals follow its repaired items`() {
        val e = Entry(
            id = "e", timestampEpochMillis = 0, rawText = "", name = "oats, peanut butter", source = "label",
            confidence = Confidence.ESTIMATED,
            nutrients = mapOf("calories" to 288.5, "protein" to 17.5, "carbs" to 46.0, "fat" to 26.0, "sugar" to 80.5),
            items = listOf(
                LoggedItem(name = "oats", said = "oats", key = "oats", origin = "LABEL", grams = 52.0, nutrients = mapOf("calories" to 3.5, "protein" to 7.0, "carbs" to 34.0, "fat" to 3.5, "sugar" to 7.0)),
                LoggedItem(name = "pb", said = "pb", key = "pb", origin = "LABEL", grams = 48.0, nutrients = mapOf("calories" to 285.0, "protein" to 10.5, "carbs" to 12.0, "fat" to 22.5, "sugar" to 73.5)),
            ),
        )
        val (fixed, _) = NutrientRepair.repair(e)!!
        assertEquals(196.0 + 285.0, fixed.nutrients["calories"]!!, 0.0)
        assertEquals(7.0, fixed.nutrients["sugar"]!!, 0.0)
        assertEquals(17.5, fixed.nutrients["protein"]!!, 0.0)
    }
}
