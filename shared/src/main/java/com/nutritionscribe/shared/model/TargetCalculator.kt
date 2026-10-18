package com.nutritionscribe.shared.model

import kotlin.math.roundToInt

/**
 * Working out sensible daily targets from someone's body and activity.
 *
 * WHY THIS EXISTS
 * ---------------
 * Every metric ships with a default — 2,200 kcal, 160 g of protein — and those
 * numbers are right for roughly nobody. They are a 30-year-old man's numbers.
 * Someone who is 5'2" and sedentary is handed a calorie goal 700 too high and a
 * protein goal they will never reach, and a tracker whose targets are wrong is
 * worse than one with no targets at all, because it scores every day against
 * fiction.
 *
 * These are the standard published estimates, not medical advice, and they are
 * offered as a starting point the user can edit. The UI says so.
 *
 * Sources, all mainstream and uncontroversial:
 *   - Resting energy: Mifflin-St Jeor, which is the one most dietetic practice
 *     guidelines recommend over Harris-Benedict.
 *   - Protein: 1.0 g/kg sitting to 1.8 g/kg training hard (the RDA's 0.8 is the
 *     floor for a sedentary adult, not a target), on adjusted weight above BMI 30.
 *   - Fibre: 14 g per 1,000 kcal (US Dietary Guidelines).
 *   - Added sugar and saturated fat: under 10% of energy (WHO / US DGA).
 *   - Sodium: 2,300 mg (US DGA upper limit).
 */
object TargetCalculator {

    enum class Sex { MALE, FEMALE }

    /**
     * How much someone moves, and what that multiplies resting energy by.
     * The labels are what the user picks from, so they describe days rather
     * than gym sessions.
     */
    enum class Activity(val label: String, val detail: String, val factor: Double, val proteinPerKg: Double) {
        SEDENTARY("Mostly sitting", "desk job, little exercise", 1.2, 1.0),
        LIGHT("Lightly active", "some walking, 1–3 workouts a week", 1.375, 1.3),
        MODERATE("Active", "on your feet, 3–5 workouts a week", 1.55, 1.6),
        VERY("Very active", "hard training or physical work", 1.725, 1.8),
    }

    /** What the user is trying to do, which only moves the calorie figure. */
    enum class Aim(val label: String, val calorieDelta: Int) {
        LOSE("Lose weight", -500),
        MAINTAIN("Stay the same", 0),
        GAIN("Gain weight", 350),
    }

    data class Body(
        val sex: Sex,
        val ageYears: Int,
        val heightCm: Double,
        val weightKg: Double,
        val activity: Activity,
        val aim: Aim,
    )

    const val KG_PER_LB = 0.45359237
    const val CM_PER_INCH = 2.54

    fun poundsToKg(lb: Double): Double = lb * KG_PER_LB
    fun feetInchesToCm(feet: Int, inches: Double): Double = (feet * 12 + inches) * CM_PER_INCH

    /** Mifflin-St Jeor resting energy, kcal/day. */
    fun restingEnergy(body: Body): Double {
        val base = 10 * body.weightKg + 6.25 * body.heightCm - 5 * body.ageYears
        return if (body.sex == Sex.MALE) base + 5 else base - 161
    }

    /** Resting energy scaled for movement, before any deficit or surplus. */
    fun maintenanceEnergy(body: Body): Double = restingEnergy(body) * body.activity.factor

    /**
     * A daily target for every metric.
     *
     * Values are rounded to something a person would actually write down —
     * calories to the nearest 10, grams to the nearest 5 — because a protein
     * target of 137.4 g implies a precision none of this has.
     */
    fun targetsFor(
        body: Body,
        stage: Profile.LifeStage = Profile.LifeStage.NONE,
        diet: Profile.Diet = Profile.Diet.ANY,
        periods: Boolean? = null,
    ): Map<Metric, Double> {
        // Never prescribe a starvation diet: a deficit that takes someone below
        // roughly their resting energy is where a helpful default becomes a
        // harmful one, so the floor holds regardless of what was asked for.
        val floor = maxOf(restingEnergy(body), 1200.0)
        val pregnant = body.sex == Sex.FEMALE && stage == Profile.LifeStage.PREGNANT
        val nursing = body.sex == Sex.FEMALE && stage == Profile.LifeStage.BREASTFEEDING
        // The Dietary Reference Intakes' extra energy: +340 kcal in the second
        // trimester (the middle of pregnancy; the app doesn't ask which), +330
        // while breastfeeding. Nobody should cut while doing either.
        val extra = when { pregnant -> 340.0; nursing -> 330.0; else -> 0.0 }
        val delta = if ((pregnant || nursing) && body.aim == Aim.LOSE) 0 else body.aim.calorieDelta
        val calories = maxOf(maintenanceEnergy(body) + delta + extra, floor)

        // Protein by how much someone moves, on a weight that muscle can explain:
        // 1.6 g/kg of a 130 kg body asks for 210 g, most of it for fat that needs
        // none. Losing weight adds a little, to hold on to muscle. Pregnancy and
        // breastfeeding add the DRI's 25 g.
        // Only when the deficit is really applied: pregnant or nursing, it is not.
        val perKg = body.activity.proteinPerKg + if (body.aim == Aim.LOSE && delta < 0) 0.2 else 0.0
        val protein = perKg * proteinWeight(body) + if (pregnant || nursing) 25.0 else 0.0
        val fat = calories * 0.27 / 9.0
        val carbs = maxOf((calories - protein * 4 - fat * 9) / 4.0, 0.0)

        return mapOf(
            Metric.CALORIES to round(calories, 10),
            Metric.PROTEIN to round(protein, 5),
            Metric.FAT to round(fat, 5),
            Metric.CARBS to round(carbs, 5),
            Metric.FIBER to round(14.0 * calories / 1000.0, 1),
            // Added sugar, if someone makes sugar a goal. Home's reference bar
            // uses total sugar instead; see [Needs].
            Metric.SUGAR to round(calories * 0.10 / 4.0, 5),
            Metric.SATURATED_FAT to round(calories * 0.10 / 9.0, 1),
            Metric.ADDED_SUGAR to round(calories * 0.10 / 4.0, 5),
            Metric.TRANS_FAT to round(calories * 0.01 / 9.0, 1),
            // The sodium limit is 1,800 mg up to 13 (NASEM 2019), 2,300 after.
            Metric.SODIUM to if (body.ageYears <= 13) 1800.0 else 2300.0,
            // 400 mg is an adult's figure; for teens the paediatric advice is at most 100.
            Metric.CAFFEINE to when { pregnant -> 200.0; body.ageYears < 19 -> 100.0; else -> 400.0 },
        ) + Metric.MICROS.associateWith { it.defaultTarget } +
            Needs.micros(Profile(body.sex, body.ageYears, body.heightCm, body.weightKg, body.activity, body.aim, stage, diet, periods = periods))
    }

    /**
     * The weight protein is worked out on. Up to a BMI of 30 that is body weight;
     * above it, the dietitians' adjusted body weight: the weight at BMI 25 plus
     * 40% of the rest.
     */
    fun proteinWeight(body: Body): Double {
        val m = body.heightCm / 100.0
        val lean = 25.0 * m * m
        return if (body.weightKg <= 30.0 * m * m) body.weightKg else lean + 0.4 * (body.weightKg - lean)
    }

    /**
     * The number below which a day counts as a miss, for a metric you are trying
     * to reach. Three quarters of the target: close enough to be a real day, far
     * enough below that hitting it is still worth something.
     *
     * A LIMIT metric has no baseline — its target already IS the line.
     */
    fun baselineFor(metric: Metric, target: Double): Double? =
        if (metric.direction == GoalDirection.REACH) round(target * 0.75, 5) else null

    private fun round(value: Double, to: Int): Double =
        ((value / to).roundToInt() * to).toDouble()
}
