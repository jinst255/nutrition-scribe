package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.Metric

/**
 * Micronutrients for foods confirmed before the app tracked them.
 *
 * Everything logged or remembered before micronutrients existed carries only the
 * macros, even when it came from a USDA row or a label that knows its calcium. The
 * food's row still exists, and so does the weight that was confirmed, so the
 * micros can be worked out exactly as they would have been on the day.
 *
 * Strictly additive: a key that is already present is never touched, and nothing
 * but micronutrients is ever added. The macros the user confirmed are the record;
 * this only fills in columns that did not exist when they were written.
 */
object MicroFill {

    /** Micro keys [have] lacks, for [grams] of a food with [per100g]. */
    fun missing(have: Map<String, Double>, per100g: Map<String, Double>?, grams: Double?): Map<String, Double> {
        if (per100g == null || grams == null || grams <= 0) return emptyMap()
        return buildMap {
            for (m in Metric.MICROS) {
                if (m.key in have) continue
                per100g[m.key]?.let { put(m.key, it * grams / 100.0) }
            }
        }
    }

    /**
     * The offline food among [candidates] that is plausibly what [nutrients] (for
     * [grams]) describe, for borrowing its micronutrients; or null.
     *
     * The AI answers calories and macros but not calcium, so its foods borrow the
     * micros of the offline match for the same words. "Same words" is not enough:
     * "chicken" can match a fried thigh or a plain breast. So the match must also
     * be close in calories per gram and, where both say, protein per gram. That
     * leaves a food of the same kind and preparation, which is as close as a
     * micronutrient estimate gets anyway.
     */
    fun sameFood(candidates: List<FoodCandidate>, nutrients: Map<String, Double>, grams: Double?): FoodCandidate? {
        if (grams == null || grams <= 0) return null
        val kcal = nutrients[Metric.CALORIES.key] ?: return null
        val density = kcal / grams * 100.0
        val proteinDensity = nutrients[Metric.PROTEIN.key]?.let { it / grams * 100.0 }
        return candidates.firstOrNull { c ->
            val per = c.per100g ?: return@firstOrNull false
            if (Metric.MICROS.none { it.key in per }) return@firstOrNull false
            val k = per[Metric.CALORIES.key] ?: return@firstOrNull false
            val kcalClose = kotlin.math.abs(k - density) <= maxOf(15.0, 0.25 * maxOf(k, density))
            val p = per[Metric.PROTEIN.key]
            val proteinClose = p == null || proteinDensity == null ||
                kotlin.math.abs(p - proteinDensity) <= maxOf(3.0, 0.35 * maxOf(p, proteinDensity))
            kcalClose && proteinClose
        }
    }

    /** [c] with [add] folded into its per-100 g and per-serving figures, so a portion change keeps them. */
    fun withMicros(c: FoodCandidate, from: FoodCandidate): FoodCandidate {
        val src = from.per100g ?: return c
        val extra = src.filterKeys { k -> Metric.fromKey(k)?.isMicro == true }
        if (extra.isEmpty()) return c
        val per100 = c.per100g?.let { own -> own + extra.filterKeys { it !in own } }
        val grams = c.defaultGrams
        val perServing = c.perServing?.let { own ->
            if (grams == null || grams <= 0) own
            else own + extra.filterKeys { it !in own }.mapValues { it.value * grams / 100.0 }
        }
        return c.copy(per100g = per100, perServing = perServing)
    }

    /**
     * The entry with its items' micros filled in and summed onto the entry, or null
     * when nothing could be added. [lookup] resolves a food id ("usda:123",
     * "label:456", "mine:<key>") to the food it names. [search], when given, finds
     * offline foods by name for items whose own food has no micros (an AI answer);
     * only a [sameFood] match is used.
     */
    fun fill(entry: Entry, lookup: (String) -> FoodCandidate?, search: ((String) -> List<FoodCandidate>)? = null): Entry? {
        if (entry.items.isEmpty()) return null
        var changed = false
        val items = entry.items.map { item ->
            val own = item.candidateId?.let(lookup)?.takeIf { c -> Metric.MICROS.any { c.per100g?.containsKey(it.key) == true } }
            val c = own ?: search?.let { sameFood(it(item.name), item.nutrients, item.grams) }
            val add = missing(item.nutrients, c?.per100g, item.grams)
            if (add.isEmpty()) item else {
                changed = true
                item.copy(nutrients = item.nutrients + add)
            }
        }
        if (!changed) return null
        val totals = buildMap {
            for (m in Metric.MICROS) {
                if (m.key in entry.nutrients) continue
                val parts = items.mapNotNull { it.nutrients[m.key] }
                if (parts.isNotEmpty()) put(m.key, parts.sum())
            }
        }
        return entry.copy(items = items, nutrients = entry.nutrients + totals)
    }

    /** A remembered food with its micros, or null when nothing could be added. */
    fun fill(food: PersonalFood, lookup: (String) -> FoodCandidate?, search: ((String) -> List<FoodCandidate>)? = null): PersonalFood? {
        if (food.isMeal) return fillMeal(food, search)
        val stored = food.candidate
        val own = stored?.let { lookup(it.id) }?.takeIf { c -> Metric.MICROS.any { c.per100g?.containsKey(it.key) == true } }
        val source = own ?: search?.let { s -> sameFood(s(food.name).ifEmpty { s(food.phrase) }, food.nutrients, food.grams) } ?: return null
        val add = missing(food.nutrients, source.per100g, food.grams)
        if (add.isEmpty()) return null
        // The stored candidate is scaled from when a portion changes; give it the
        // micros too, or the next "2 cups" loses them again.
        return food.copy(nutrients = food.nutrients + add, candidate = stored?.let { withMicros(it, source) })
    }

    /** A saved meal: each part by name, and the meal's total from the parts. */
    private fun fillMeal(meal: PersonalFood, search: ((String) -> List<FoodCandidate>)?): PersonalFood? {
        search ?: return null
        var changed = false
        val parts = meal.parts.map { part ->
            val add = missing(part.nutrients, sameFood(search(part.name), part.nutrients, part.grams)?.per100g, part.grams)
            if (add.isEmpty()) part else { changed = true; part.copy(nutrients = part.nutrients + add) }
        }
        if (!changed) return null
        val totals = buildMap {
            for (m in Metric.MICROS) {
                if (m.key in meal.nutrients) continue
                val have = parts.mapNotNull { it.nutrients[m.key] }
                if (have.isNotEmpty()) put(m.key, have.sum())
            }
        }
        return meal.copy(parts = parts, nutrients = meal.nutrients + totals)
    }
}
