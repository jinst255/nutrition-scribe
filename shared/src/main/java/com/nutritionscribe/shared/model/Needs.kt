package com.nutritionscribe.shared.model

import kotlin.math.roundToInt

/**
 * How much of each nutrient one particular person needs in a day: the number a
 * bar on Home fills toward when the metric is not one of their goals.
 *
 * WHY NOT THE LABEL'S DAILY VALUE
 * -------------------------------
 * The %DV on a package is one figure for every adult, set high enough to cover
 * almost anyone. So it is the right number for a label and the wrong one for a
 * person: 18 mg of iron is what a menstruating woman needs, more than twice a
 * man's 8 mg; 250 g of carbohydrate is a 2,000 kcal day, not a 3,000 kcal one.
 * And 50 g of sugar is the limit for ADDED sugar, while everything the app knows
 * (USDA, labels, the AI) is TOTAL sugar, so a bowl of oats with raisins and honey
 * filled the sugar bar on fruit alone.
 *
 * SOURCES
 * -------
 *   - Vitamins and minerals: the National Academies' Dietary Reference Intakes,
 *     the RDA where there is one and the AI otherwise, by sex, age band and
 *     pregnancy or breastfeeding. Potassium is the 2019 revision (3,400 / 2,600 mg).
 *   - Vegetarian and vegan: iron x 1.8 and zinc x 1.5, the adjustments the same
 *     report gives, because plant iron and zinc absorb so much worse.
 *   - Total sugars: 18% of energy, the UK/EU Reference Intake (90 g at 2,000 kcal).
 *     The US publishes no reference for total sugars at all.
 *   - Everything else: [TargetCalculator].
 *
 * Without a profile the label figures stand in, except sugar, which uses the
 * total-sugar reference for 2,000 kcal.
 */
object Needs {

    /** Total sugars at 2,000 kcal (UK/EU Reference Intake). */
    const val TOTAL_SUGAR_REFERENCE = 90.0
    private const val TOTAL_SUGAR_SHARE = 0.18

    /** A reference for every metric. */
    fun forProfile(profile: Profile?): Map<Metric, Double> {
        if (profile == null) return Metric.entries.associateWith { fallback(it) }
        val macros = TargetCalculator.targetsFor(profile.body, profile.stage, profile.diet)
        val calories = macros.getValue(Metric.CALORIES)
        val micros = micros(profile)
        return Metric.entries.associateWith { m ->
            when {
                m == Metric.SUGAR -> round5(calories * TOTAL_SUGAR_SHARE / 4.0)
                m == Metric.CAFFEINE && profile.stage == Profile.LifeStage.PREGNANT -> 200.0
                m.isMicro -> micros[m] ?: m.defaultTarget
                else -> macros[m] ?: m.defaultTarget
            }
        }
    }

    /** The reference for one metric with no profile. */
    fun fallback(metric: Metric): Double =
        if (metric == Metric.SUGAR) TOTAL_SUGAR_REFERENCE else metric.defaultTarget

    /** The Dietary Reference Intakes for this person. Omega-3 and cholesterol keep the label figure. */
    fun micros(profile: Profile): Map<Metric, Double> {
        val age = profile.ageYears
        val teen = age < 19
        val male = profile.sex == TargetCalculator.Sex.MALE
        val base: Map<Metric, Double> = when {
            profile.stage == Profile.LifeStage.PREGNANT && !male -> mapOf(
                Metric.CALCIUM to if (teen) 1300.0 else 1000.0,
                Metric.IRON to 27.0,
                Metric.POTASSIUM to if (teen) 2600.0 else 2900.0,
                Metric.MAGNESIUM to when { teen -> 400.0; age <= 30 -> 350.0; else -> 360.0 },
                Metric.ZINC to if (teen) 12.0 else 11.0,
                Metric.SELENIUM to 60.0,
                Metric.VITAMIN_A to if (teen) 750.0 else 770.0,
                Metric.VITAMIN_C to if (teen) 80.0 else 85.0,
                Metric.VITAMIN_D to 15.0,
                Metric.VITAMIN_E to 15.0,
                Metric.VITAMIN_K to if (teen) 75.0 else 90.0,
                Metric.VITAMIN_B12 to 2.6,
                Metric.FOLATE to 600.0,
            )
            profile.stage == Profile.LifeStage.BREASTFEEDING && !male -> mapOf(
                Metric.CALCIUM to if (teen) 1300.0 else 1000.0,
                Metric.IRON to if (teen) 10.0 else 9.0,
                Metric.POTASSIUM to if (teen) 2500.0 else 2800.0,
                Metric.MAGNESIUM to when { teen -> 360.0; age <= 30 -> 310.0; else -> 320.0 },
                Metric.ZINC to if (teen) 13.0 else 12.0,
                Metric.SELENIUM to 70.0,
                Metric.VITAMIN_A to if (teen) 1200.0 else 1300.0,
                Metric.VITAMIN_C to if (teen) 115.0 else 120.0,
                Metric.VITAMIN_D to 15.0,
                Metric.VITAMIN_E to 19.0,
                Metric.VITAMIN_K to if (teen) 75.0 else 90.0,
                Metric.VITAMIN_B12 to 2.8,
                Metric.FOLATE to 500.0,
            )
            male -> mapOf(
                Metric.CALCIUM to when { teen -> 1300.0; age <= 70 -> 1000.0; else -> 1200.0 },
                Metric.IRON to if (teen) 11.0 else 8.0,
                Metric.POTASSIUM to if (teen) 3000.0 else 3400.0,
                Metric.MAGNESIUM to when { teen -> 410.0; age <= 30 -> 400.0; else -> 420.0 },
                Metric.ZINC to 11.0,
                Metric.SELENIUM to 55.0,
                Metric.VITAMIN_A to 900.0,
                Metric.VITAMIN_C to if (teen) 75.0 else 90.0,
                Metric.VITAMIN_D to if (age > 70) 20.0 else 15.0,
                Metric.VITAMIN_E to 15.0,
                Metric.VITAMIN_K to if (teen) 75.0 else 120.0,
                Metric.VITAMIN_B12 to 2.4,
                Metric.FOLATE to 400.0,
            )
            else -> mapOf(
                Metric.CALCIUM to when { teen -> 1300.0; age <= 50 -> 1000.0; else -> 1200.0 },
                Metric.IRON to when { teen -> 15.0; age <= 50 -> 18.0; else -> 8.0 },
                Metric.POTASSIUM to if (teen) 2300.0 else 2600.0,
                Metric.MAGNESIUM to when { teen -> 360.0; age <= 30 -> 310.0; else -> 320.0 },
                Metric.ZINC to if (teen) 9.0 else 8.0,
                Metric.SELENIUM to 55.0,
                Metric.VITAMIN_A to 700.0,
                Metric.VITAMIN_C to if (teen) 65.0 else 75.0,
                Metric.VITAMIN_D to if (age > 70) 20.0 else 15.0,
                Metric.VITAMIN_E to 15.0,
                Metric.VITAMIN_K to if (teen) 75.0 else 90.0,
                Metric.VITAMIN_B12 to 2.4,
                Metric.FOLATE to 400.0,
            )
        }
        if (profile.diet == Profile.Diet.ANY) return base
        return base.mapValues { (m, v) ->
            when (m) {
                Metric.IRON -> round1(v * 1.8)
                Metric.ZINC -> round1(v * 1.5)
                else -> v
            }
        }
    }

    private fun round5(v: Double) = ((v / 5).roundToInt() * 5).toDouble()
    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
}
