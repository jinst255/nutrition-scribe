package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.model.NutrientRepair

/**
 * [NutrientRepair] for a remembered food, so the next "log again" stops repeating
 * a misread label. The stored candidate is repaired too, per 100 g and per
 * serving, or the next "2 servings" would scale the bad numbers back in.
 */
object FoodRepair {

    fun repair(food: PersonalFood): Pair<PersonalFood, List<NutrientRepair.Fix>>? {
        val fixes = mutableListOf<NutrientRepair.Fix>()
        val parts = food.parts.map { part ->
            val r = NutrientRepair.repair(part.nutrients, part.grams)
            fixes += r.fixes
            if (r.fixes.isEmpty()) part else part.copy(nutrients = r.nutrients)
        }
        // A recipe: its parts repaired, its serving worked out from them again.
        if (food.isRecipe) {
            if (fixes.isEmpty()) return null
            return Recipes.withServing(food.copy(parts = parts), System.currentTimeMillis()) to fixes
        }
        val own = if (food.isMeal) {
            NutrientRepair.Result(NutrientRepair.resum(food.nutrients, parts.map { it.nutrients }, fixes.map { it.metric }.toSet()), emptyList())
        } else NutrientRepair.repair(food.nutrients, food.grams)
        fixes += own.fixes

        val c = food.candidate
        val candidate = c?.let {
            val per100 = it.per100g?.let { p -> NutrientRepair.repair(p, 100.0) }
            val serving = it.perServing?.let { s -> NutrientRepair.repair(s, it.labelServingGrams ?: it.defaultGrams) }
            fixes += per100?.fixes.orEmpty() + serving?.fixes.orEmpty()
            it.copy(per100g = per100?.nutrients ?: it.per100g, perServing = serving?.nutrients ?: it.perServing)
        }
        if (fixes.isEmpty()) return null
        return food.copy(nutrients = own.nutrients, parts = parts, candidate = candidate) to fixes.distinctBy { it.code to it.metric }
    }

    /** What [roughWeight] decided: the food with a real weight, or forget it so it is looked up afresh. */
    sealed interface WeightFix {
        data class Fixed(val food: PersonalFood) : WeightFix
        data object Forget : WeightFix
    }

    /**
     * A remembered food whose weight is the rough volume guess ([Portions.isRoughVolume]),
     * learned before 1.7: a tablespoon of peanut butter at 8.13 g, logged at half its
     * calories every time after. Its numbers were worked out from that weight, so
     * both scale back together once a real weight is known: from the food's own
     * measures (a label's "1 tbsp (21 g)"), or one label serving when it was logged
     * with no measure at all. With nothing to go on (an AI answer kept no measure),
     * it is forgotten, so the next time it is said it gets a card and a fresh answer.
     * Null when its weights are fine.
     */
    fun roughWeight(food: PersonalFood): WeightFix? {
        // A recipe's weight is a serving of a pot, never a rough volume guess.
        if (food.isMeal || food.isRecipe) return null
        val unitGrams = food.unitGrams.filterValues { !Portions.isRoughVolume(it) }
        val perUnit = food.grams?.let { it / (food.amount ?: 1.0) }
        val mainRough = perUnit != null && Portions.isRoughVolume(perUnit)
        if (!mainRough) return if (unitGrams.size != food.unitGrams.size) WeightFix.Fixed(food.copy(unitGrams = unitGrams)) else null

        val c = food.candidate
        val item = ItemParser.Item(said = food.phrase, food = food.phrase, amount = food.amount, unit = food.unit)
        val real = c?.let { Portions.gramsForOne(it, item) }?.grams?.takeIf { !Portions.isRoughVolume(it) }
            ?: c?.labelServingGrams?.takeIf { food.unit == null && it > 0 }
            ?: return WeightFix.Forget
        val grams = real * (food.amount ?: 1.0)
        val f = grams / food.grams!!
        val ug = unitGrams + ((food.unit ?: "each") to real)
        return WeightFix.Fixed(food.copy(grams = grams, nutrients = food.nutrients.mapValues { it.value * f }, unitGrams = ug))
    }
}
