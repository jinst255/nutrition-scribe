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
) {
    val body: TargetCalculator.Body
        get() = TargetCalculator.Body(sex, ageYears, heightCm, weightKg, activity, aim)

    @Serializable
    enum class LifeStage(val label: String) { NONE("Neither"), PREGNANT("Pregnant"), BREASTFEEDING("Breastfeeding") }

    @Serializable
    enum class Diet(val label: String) { ANY("Eat meat"), VEGETARIAN("Vegetarian"), VEGAN("Vegan") }
}
