package com.nutritionscribe.app.ui.confirm

import com.nutritionscribe.shared.model.Liquids
import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.ItemParser
import com.nutritionscribe.shared.resolve.Origin
import com.nutritionscribe.shared.resolve.Portions
import com.nutritionscribe.shared.resolve.Resolver

/**
 * One line of a confirm card while the user is looking at it.
 *
 * Plain data plus the arithmetic: change the amount, the unit, the food or a
 * number, and everything else follows. A number the user typed over is theirs and
 * stays put when the portion changes — they asked for that number.
 */
data class ItemDraft(
    val item: ItemParser.Item,
    val key: String,
    val candidate: FoodCandidate?,
    val grams: Double?,
    val portionNote: String?,
    /** The numbers as computed from the food and portion. */
    val computed: Map<String, Double>,
    /** Numbers the user typed over, which win. */
    val overrides: Map<String, Double> = emptyMap(),
    val status: Resolver.Status,
    val weak: Boolean,
    val offerScan: Boolean,
    val offerBreakdown: Boolean,
    val alternates: List<FoodCandidate>,
    /** Remembered and untouched: nothing to write back. */
    val untouched: Boolean,
) {
    val name: String get() = candidate?.let { c -> withBrand(c, " — ") } ?: item.food
    val nutrients: Map<String, Double> get() = computed + overrides

    /** A drink: its amount is shown and typed in mL (see [Liquids]). */
    val liquid: Boolean get() = Liquids.isLiquid(candidate?.name ?: item.food, candidate?.labelServingText)

    /** "240 mL · 8 fl oz" for a drink, "32 g" for anything else. */
    fun amountText(g: Double): String = Liquids.amount(g, candidate?.name ?: item.food, candidate?.labelServingText)

    fun toConfirmed() = Resolver.Confirmed(
        item = item, key = key, name = name, candidate = candidate, grams = grams,
        nutrients = nutrients, unchangedMemory = untouched,
    )

    /** "2", "0.5" — the amount as said, or one. */
    val amount: Double get() = item.amount ?: 1.0

    fun withAmount(amount: Double): ItemDraft = recompute(item.copy(amount = amount.coerceAtLeast(0.0)))

    fun withUnit(unit: String?): ItemDraft = recompute(item.copy(unit = unit))

    /** Exact grams, typed or picked from a listed portion. */
    fun withGrams(grams: Double): ItemDraft {
        val c = candidate
        val n = when {
            c?.per100g != null -> c.per100g!!.mapValues { it.value * grams / 100.0 }
            this.grams != null && this.grams > 0 -> computed.mapValues { it.value * grams / this.grams }
            else -> computed
        }
        return copy(item = item.copy(amount = grams, unit = "g"), grams = grams, portionNote = amountText(grams),
            computed = n, untouched = false)
    }

    fun withCandidate(c: FoodCandidate): ItemDraft = copy(candidate = c, alternates = (listOfNotNull(candidate) + alternates - c).distinctBy { it.id },
        weak = false, untouched = false, overrides = emptyMap()).recompute(item)

    fun withNutrient(key: String, value: Double?): ItemDraft =
        copy(overrides = if (value == null) overrides - key else overrides + (key to value), untouched = false)

    private fun recompute(newItem: ItemParser.Item): ItemDraft {
        val c = candidate ?: return copy(item = newItem, untouched = false)
        val portion = Portions.gramsFor(c, newItem)
        val n = when {
            c.per100g != null && portion != null -> c.nutrientsFor(portion.grams)
            // A weightless answer (AI, typed by hand) scales with the amount.
            else -> {
                val ratio = (newItem.amount ?: 1.0) / (item.amount ?: 1.0)
                computed.mapValues { it.value * ratio }
            }
        }
        var stated = n
        newItem.statedProteinG?.let { stated = stated + ("protein" to it) }
        return copy(item = newItem, grams = portion?.grams ?: grams, portionNote = portion?.how ?: portionNote,
            computed = stated, untouched = false)
    }

    companion object {
        fun from(r: Resolver.ResolvedItem) = ItemDraft(
            item = r.item, key = r.key, candidate = r.choice, grams = r.grams, portionNote = r.portionNote,
            computed = r.nutrients, status = r.status, weak = r.weak, offerScan = r.offerScan,
            offerBreakdown = r.offerBreakdown, alternates = r.alternates,
            untouched = r.status == Resolver.Status.REMEMBERED,
        )

        fun from(c: Resolver.Confirmed) = ItemDraft(
            item = c.item, key = c.key, candidate = c.candidate, grams = c.grams, portionNote = null,
            computed = c.nutrients, status = Resolver.Status.REMEMBERED, weak = false, offerScan = false,
            offerBreakdown = false, alternates = emptyList(), untouched = false,
        )

        /** A food found by scanning, as the one line of a new entry: one label serving. */
        fun fromCandidate(c: FoodCandidate): ItemDraft {
            val name = withBrand(c, " ").ifBlank { c.name }
            val grams = c.labelServingGrams ?: c.defaultGrams ?: c.portions.firstOrNull()?.grams
            val n = if (c.per100g != null && grams != null) c.nutrientsFor(grams) else c.perServing.orEmpty()
            return ItemDraft(
                item = ItemParser.Item(said = name, food = name),
                key = com.nutritionscribe.shared.resolve.PersonalKeys.of(name),
                candidate = c, grams = grams,
                portionNote = c.labelServingText ?: grams?.let { Liquids.amount(it, c.name, c.labelServingText) },
                computed = n, status = Resolver.Status.NEW, weak = false, offerScan = true,
                offerBreakdown = false, alternates = emptyList(), untouched = false,
            )
        }

        /** "Sun-maid — California Raisins", not "Sun-maid — Sun-maid California Raisins". */
        fun withBrand(c: FoodCandidate, sep: String): String {
            val b = c.brand?.trim()?.takeIf { it.isNotBlank() && !c.name.contains(it, ignoreCase = true) }
            return if (b != null) "$b$sep${c.name}" else c.name
        }

        fun label(origin: Origin?): String = when (origin) {
            Origin.PERSONAL -> "Your usual"
            Origin.LABEL -> "Package label"
            Origin.USDA -> "USDA"
            Origin.CURATED -> "Typical label"
            Origin.AI -> "AI estimate"
            Origin.MANUAL -> "Typed in"
            null -> "Not found"
        }
    }
}
