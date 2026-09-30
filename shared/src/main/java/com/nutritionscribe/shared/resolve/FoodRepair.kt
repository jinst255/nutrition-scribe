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
}
