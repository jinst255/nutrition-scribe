package com.nutritionscribe.app.data.resolve

import com.nutritionscribe.app.data.db.EntryDao
import com.nutritionscribe.app.data.db.toEntry
import com.nutritionscribe.app.data.db.toNutrientRows
import com.nutritionscribe.app.data.db.toRow
import com.nutritionscribe.app.ui.debuglog.DebugEvents
import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.LabelPackSource
import com.nutritionscribe.shared.resolve.MicroFill
import com.nutritionscribe.shared.resolve.UsdaFoodSource

/**
 * The one pass that gives foods logged before micronutrients existed their
 * micros (see [MicroFill]): remembered foods first, so the next "log again" has
 * them, then every entry, so History reaches back past today.
 *
 * Runs once per [VERSION]; bump it when a data source gains micronutrients (a new
 * label pack format does NOT need a bump: [run] is also called after an install).
 */
class MicroBackfill(
    private val entryDao: EntryDao,
    private val personal: RoomPersonalStore,
    private val usda: () -> UsdaFoodSource?,
    private val labels: () -> LabelPackSource?,
    private val events: DebugEvents?,
) {
    data class Result(val foods: Int, val entries: Int)

    suspend fun run(): Result {
        val usda = usda()
        val labels = labels()
        val cache = HashMap<String, FoodCandidate?>()
        val lookup: (String) -> FoodCandidate? = { id ->
            cache.getOrPut(id) {
                when {
                    id.startsWith("usda:") -> id.removePrefix("usda:").toLongOrNull()?.let { usda?.byId(it) }
                    id.startsWith("label:") -> id.removePrefix("label:").toLongOrNull()?.let { labels?.byId(it) }
                    else -> null
                }
            }
        }
        // For foods whose numbers came from the AI: offline foods by name, used only
        // when they match on calories and protein per gram (MicroFill.sameFood).
        val found = HashMap<String, List<FoodCandidate>>()
        val search: (String) -> List<FoodCandidate> = { name ->
            found.getOrPut(name.trim().lowercase()) {
                runCatching { usda?.search(name, limit = 3).orEmpty() + labels?.search(name, limit = 2).orEmpty() }.getOrDefault(emptyList())
            }
        }
        var foods = 0
        for (food in personal.all()) {
            MicroFill.fill(food, lookup, search)?.let { personal.put(it); foods++ }
        }
        var entries = 0
        for (row in entryDao.all()) {
            val entry = row.toEntry()
            MicroFill.fill(entry, lookup, search)?.let {
                entryDao.upsert(it.toRow(), it.toNutrientRows())
                entries++
            }
        }
        events?.event("micro_backfill", "foods" to foods, "entries" to entries,
            "usda" to (usda != null), "labels" to (labels != null))
        return Result(foods, entries)
    }

    companion object {
        /** 2: foods the AI answered, which version 1 could not fill. 3: labels, whose five micros stopped the rest being borrowed. 4: More to track. */
        const val VERSION = 4
    }
}
