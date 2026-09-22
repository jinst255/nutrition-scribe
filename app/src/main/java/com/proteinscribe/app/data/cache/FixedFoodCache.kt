package com.proteinscribe.app.data.cache

import com.proteinscribe.app.data.db.FixedFoodCacheDao
import com.proteinscribe.app.data.db.FixedFoodCacheRow
import com.proteinscribe.shared.model.Confidence
import com.proteinscribe.shared.model.NutritionLookupResult

/**
 * Caches fixed-food (packaged, exact-serving) lookups by normalized name so a repeat
 * entry like "Built bar" is instant and free instead of re-calling the LLM (Section 4).
 * Variable foods are never cached, since portion/prep varies too much to trust a stored value.
 */
class FixedFoodCache(private val dao: FixedFoodCacheDao) {

    private fun normalize(name: String): String = name.trim().lowercase()

    suspend fun lookup(name: String): NutritionLookupResult? {
        val row = dao.find(normalize(name)) ?: return null
        return NutritionLookupResult(
            name = row.name,
            proteinGrams = row.proteinGrams,
            calories = row.calories,
            carbsGrams = row.carbsGrams,
            fatGrams = row.fatGrams,
            isFixedFood = true,
            source = row.source,
            confidence = Confidence.ESTIMATED,
        )
    }

    suspend fun store(result: NutritionLookupResult) {
        if (!result.isFixedFood) return
        dao.upsert(
            FixedFoodCacheRow(
                normalizedName = normalize(result.name),
                name = result.name,
                proteinGrams = result.proteinGrams,
                calories = result.calories,
                carbsGrams = result.carbsGrams,
                fatGrams = result.fatGrams,
                source = result.source,
            ),
        )
    }
}
