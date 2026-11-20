package com.nutritionscribe.app.ui.confirm

import com.nutritionscribe.shared.food.FoodQuery
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.LoggedItem
import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.Origin
import com.nutritionscribe.shared.resolve.Portion

/**
 * "Which cheese was it?": the foods they've logged before for the same word, most
 * eaten first, each with its brand (owner, 11-19: someone buys cheddar at Walmart
 * and sometimes at Costco). Change opens on these before the full search.
 */
object UsualChoices {
    data class Choice(
        val product: String,
        val brand: String?,
        val portion: String?,
        val calories: Double?,
        val times: Int,
        val candidate: FoodCandidate,
    )

    /** The word the line is about: "cheese" in "2 slices of sharp cheddar cheese". */
    fun headWord(food: String): String? = FoodQuery.contentWords(food).lastOrNull()

    /** "cheeses" ~ "cheese", "berries" ~ "berry", "tomatoes" ~ "tomato". */
    private fun forms(w: String): Set<String> {
        val l = w.lowercase()
        return setOfNotNull(l, l.removeSuffix("s"), l.removeSuffix("es"), if (l.endsWith("ies")) l.dropLast(3) + "y" else null)
            .filter { it.length >= 2 }.toSet()
    }

    fun matches(item: LoggedItem, word: String): Boolean {
        val w = forms(word)
        if (w.isEmpty()) return false
        return (FoodQuery.contentWords(item.said) + FoodQuery.contentWords(item.name)).any { (forms(it) intersect w).isNotEmpty() }
    }

    fun of(word: String?, entries: List<Entry>, current: FoodCandidate?, limit: Int = 5): List<Choice> {
        if (word.isNullOrBlank()) return emptyList()
        val items = entries.sortedByDescending { it.timestampEpochMillis }
            .flatMap { it.items }
            .filter { it.nutrients.isNotEmpty() && matches(it, word) }
        val shown = current?.let { ItemDraft.withBrand(it, " — ").lowercase() }
        // The one on the card now is shown apart, as "now".
        return items.groupBy { it.name.trim().lowercase() }
            .filter { (name, same) -> name != shown && same.none { it.candidateId != null && it.candidateId == current?.id } }
            .map { (_, same) -> same.first() to same.size }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (latest, n) ->
                val (brand, product) = split(latest.name)
                Choice(product, brand, latest.portion, latest.nutrients["calories"], n, candidateOf(latest))
            }
    }

    /** "Great Value — Sharp cheddar" → brand and product. */
    fun split(name: String): Pair<String?, String> {
        val i = name.indexOf(" — ")
        return if (i > 0) name.substring(0, i).trim() to name.substring(i + 3).trim() else null to name.trim()
    }

    /**
     * The past item as a food to pick: its own numbers, scaled per gram when the
     * weight was known, and its unit as a portion so "2 slices" still means slices.
     */
    fun candidateOf(item: LoggedItem): FoodCandidate {
        val (brand, product) = split(item.name)
        val grams = item.grams?.takeIf { it > 0 }
        val amount = item.amount?.takeIf { it > 0 } ?: 1.0
        val origin = runCatching { Origin.valueOf(item.origin) }.getOrDefault(Origin.PERSONAL)
        return FoodCandidate(
            id = item.candidateId ?: "past:${item.name.lowercase()}",
            name = product,
            brand = brand,
            origin = origin,
            per100g = grams?.let { g -> item.nutrients.mapValues { (_, v) -> v * 100.0 / g } },
            portions = if (grams != null && item.unit != null) listOf(Portion(item.unit!!, grams / amount)) else emptyList(),
            defaultGrams = grams?.let { it / amount },
            perServing = if (grams == null) item.nutrients.mapValues { (_, v) -> v / amount } else null,
            servingLabel = if (grams == null) item.unit ?: "serving" else null,
        )
    }
}
