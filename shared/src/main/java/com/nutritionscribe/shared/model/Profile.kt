package com.nutritionscribe.shared.model

import kotlinx.serialization.Serializable

/**
 * What the user told the app about themselves, kept so every bar on Home can be
 * scaled to their own needs instead of a label's one-size Daily Value.
 *
 * Everything past [aim] is optional and rare, and asked for behind a fold.
 */
@Serializable
data class Profile(
    val sex: TargetCalculator.Sex,
    val ageYears: Int,
    val heightCm: Double,
    val weightKg: Double,
    val activity: TargetCalculator.Activity,
    val aim: TargetCalculator.Aim,
    val stage: LifeStage = LifeStage.NONE,
    val diet: Diet = Diet.ANY,
    /** Shows the "Donated plasma today" button on Home. */
    val plasmaDonor: Boolean = false,
    /**
     * Women only: whether they have periods, which is most of why a woman's iron
     * need (18 mg) is more than twice a man's. Null until asked: then it is taken
     * as yes up to 50 and no after.
     */
    val periods: Boolean? = null,
) {
    /** [periods] as answered, or what is usual for the age. */
    val hasPeriods: Boolean get() = sex == TargetCalculator.Sex.FEMALE && (periods ?: (ageYears <= 50))

    val body: TargetCalculator.Body
        get() = TargetCalculator.Body(sex, ageYears, heightCm, weightKg, activity, aim)

    @Serializable
    enum class LifeStage(val label: String) { NONE("Neither"), PREGNANT("Pregnant"), BREASTFEEDING("Breastfeeding") }

    /** No longer asked (2026-10); kept so profiles saved with it still load. */
    @Serializable
    enum class Diet(val label: String) { ANY("Eat meat"), VEGETARIAN("Vegetarian"), VEGAN("Vegan") }
}
