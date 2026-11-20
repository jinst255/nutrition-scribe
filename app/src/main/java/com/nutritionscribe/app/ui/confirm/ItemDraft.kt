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
    /** [alternates] are their other brands of the same food (see Resolver.brandsFor). */
    val otherBrands: Boolean = false,
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

    fun withAmount(amount: Double): ItemDraft {
        // With no amount, a label's line is one serving; with one and no unit, a count
        // of pieces. Stepping must not switch between them (30 g went to 7.5 g on −):
        // the first step names what was being counted.
        val unit = item.unit ?: "serving".takeIf {
            item.amount == null && candidate?.origin == Origin.LABEL && candidate.labelServingGrams != null &&
                grams != null && kotlin.math.abs(grams - candidate.labelServingGrams!!) < 0.01
        }
        return recompute(item.copy(amount = amount.coerceAtLeast(0.0), unit = unit), sameUnit = true)
    }

    fun withUnit(unit: String?): ItemDraft = recompute(item.copy(unit = unit))

    /** A new amount in a new unit, as said: "make the milk 300 mL". */
    fun withAmountIn(amount: Double, unit: String?): ItemDraft =
        if (canConvert) recompute(item.copy(amount = amount.coerceAtLeast(0.0), unit = unit)) else withAmount(amount)

    /**
     * The same amount of food counted in another unit: 1 whole avocado becomes
     * 136 g, 1 cup of milk 235 mL. The steppers then move in that unit's steps.
     */
    fun withUnitKept(unit: String?): ItemDraft {
        val c = candidate ?: return this
        val g = grams ?: return this
        val amount = com.nutritionscribe.shared.resolve.Units.convert(c, item, g, unit) ?: return this
        return recompute(item.copy(unit = unit, amount = amount, scale = 1.0))
    }

    /** Units can change only when the food's weight is known; an AI answer with no weight scales by count. */
    val canConvert: Boolean get() = candidate?.per100g != null && grams != null

    /** The units this line can be counted in, the current one first. */
    val unitOptions: List<com.nutritionscribe.shared.resolve.Units.Option>
        get() = if (canConvert) com.nutritionscribe.shared.resolve.Units.optionsFor(candidate, item, perPiece)
        else listOf(com.nutritionscribe.shared.resolve.Units.Option(item.unit, unitLabel))

    /** "slice", "mL", "whole": the current unit as a stepper shows it. */
    val unitLabel: String get() = com.nutritionscribe.shared.resolve.Units.label(item.unit, com.nutritionscribe.shared.resolve.Units.pieceOf(candidate, perPiece))

    /** What one of it weighs on this line, when it's counted ("2 eggs", 100 g: 50 g each). */
    private val perPiece: Double? get() = if (item.unit == null) grams?.let { g -> item.amount?.takeIf { it > 0 }?.let { g / it } ?: g } else null

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

    /**
     * A label scanned onto this line. The same product: only the numbers it was
     * missing are filled in (a barcode with no potassium), and those keys come back
     * so the screen can say so and offer the rest. Another product: it becomes that.
     */
    fun withLabelScan(c: FoodCandidate): Pair<ItemDraft, Set<String>> {
        val mine = candidate
        if (mine == null || computed.isEmpty() || !sameProduct(mine, c)) return withCandidate(c) to emptySet()
        val theirs = withCandidate(c).computed
        val filled = theirs.keys - computed.keys
        return copy(computed = computed + theirs.filterKeys { it in filled }, untouched = false) to filled
    }

    fun withNutrient(key: String, value: Double?): ItemDraft =
        copy(overrides = if (value == null) overrides - key else overrides + (key to value), untouched = false)

    /**
     * Numbers typed by hand, carried to a new amount. They were kept as they were,
     * so after the owner typed in a nutrient (11-16) the amount stopped changing
     * that number: "When I manually edit in one of the nutrients the values it stops
     * scaling." A number typed for 2 slices is half as much for 1.
     */
    private fun scaledOverrides(newGrams: Double?, newAmount: Double): Map<String, Double> {
        if (overrides.isEmpty()) return overrides
        val oldAmount = item.amount ?: 1.0
        val r = when {
            grams != null && grams > 0 && newGrams != null && newGrams > 0 -> newGrams / grams
            oldAmount > 0 -> newAmount / oldAmount
            else -> 1.0
        }
        return if (r == 1.0) overrides else overrides.mapValues { it.value * r }
    }

    /** [sameUnit]: only the amount changed, whatever the unit is now called. */
    private fun recompute(newItem: ItemParser.Item, sameUnit: Boolean = newItem.unit == item.unit): ItemDraft {
        val c = candidate ?: return copy(item = newItem, untouched = false,
            overrides = scaledOverrides(null, newItem.amount ?: 1.0))
        // A label with no weight printed (vitamin gummies: "2 gummies") is counted in its
        // servings. Before, a scan put on an empty line scaled that line's own empty
        // numbers, and the entry saved with no nutrients at all under a made-up 50 g.
        if (c.per100g == null && c.perServing != null && c.origin == Origin.LABEL) {
            val servings = servingsOf(c, newItem)
            var n = c.nutrientsFor(null, servings)
            newItem.statedProteinG?.let { n = n + ("protein" to it) }
            val note = (if (servings == 1.0) "1 serving" else "${fmtServings(servings)} servings") +
                (c.labelServingText?.trim()?.trimStart(':', ' ')?.takeIf { it.isNotBlank() }?.let { " ($it each)" } ?: "")
            return copy(item = newItem, grams = null, portionNote = note, computed = n, untouched = false,
                overrides = scaledOverrides(null, newItem.amount ?: 1.0))
        }
        var portion = Portions.gramsFor(c, newItem)
        // Same unit, new amount: the weight follows the amount, always. A line's weight
        // per unit is already settled (by their usual, a label, the AI); looking it up
        // again could only change it. A food rebuilt from a logged entry with no egg
        // on record counted "1 egg" at 100 g, and an AI's peanut butter re-weighed by
        // the rough 0.55 g/mL guess lost half its weight, learned for every log after.
        val oldAmount = item.amount ?: 1.0
        val newAmount = newItem.amount ?: 1.0
        if (grams != null && grams > 0 && sameUnit && newAmount != oldAmount && oldAmount > 0) {
            val g = grams * newAmount / oldAmount
            portion = Portions.Answer(g, amountText(g))
        }
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
        val newGrams = portion?.grams ?: grams
        return copy(item = newItem, grams = newGrams, portionNote = portion?.how ?: portionNote,
            computed = stated, untouched = false, overrides = scaledOverrides(newGrams, newItem.amount ?: 1.0))
    }

    companion object {
        private val PIECES = setOf("gummy", "gummies", "capsule", "capsules", "tablet", "tablets", "softgel", "softgels",
            "pill", "pills", "chew", "chews", "piece", "pieces", "lozenge", "lozenges")

        /**
         * Servings of a weightless label for what was said: "half" is half a serving,
         * but "3 gummies" of a "2 gummies" serving is one and a half.
         */
        fun servingsOf(c: FoodCandidate, item: ItemParser.Item): Double {
            val amount = item.amount ?: 1.0
            val m = Regex("(\\d+(?:\\.\\d+)?)\\s*([a-z]+)").find(c.labelServingText.orEmpty().lowercase())
            val perServing = m?.groupValues?.get(1)?.toDoubleOrNull()
            val saidPieces = item.unit in PIECES || PIECES.any { Regex("\\b$it\\b").containsMatchIn(item.said.lowercase()) }
            return if (perServing != null && perServing > 0 && m.groupValues[2] in PIECES && saidPieces) amount / perServing else amount
        }

        private fun fmtServings(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.2g", v)

        fun from(r: Resolver.ResolvedItem) = ItemDraft(
            item = r.item, key = r.key, candidate = r.choice, grams = r.grams, portionNote = r.portionNote,
            computed = r.nutrients, status = r.status, weak = r.weak, offerScan = r.offerScan,
            offerBreakdown = r.offerBreakdown,
            // A food of theirs it was close to ("normal vitamin supliments" and their
            // "Vitamin D supplements") is the first swap offered. It was worked out,
            // logged, and never shown.
            alternates = (listOfNotNull(r.nearMiss?.candidate?.takeIf { r.status != Resolver.Status.REMEMBERED && it.id != r.choice?.id }) +
                r.alternates).distinctBy { it.id },
            untouched = r.status == Resolver.Status.REMEMBERED, otherBrands = r.otherBrands,
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

        /**
         * One product, whatever its source: the same brand, and one name says nothing
         * the other doesn't ("Chobani strawberry greek yogurt" is the barcode's
         * "Chobani — Strawberry Blended Nonfat Greek Yogurt").
         *
         * It used to be "most of the same words", brand words included. The owner's
         * scan of their sliced low-sodium bacon (11-18) shared "great value bacon" with
         * the line's Great Value Real Bacon Pieces, so it only filled gaps and kept the
         * bits' 7 g serving: 5 slices logged as 17.5 g and 75 kcal. Their multi-grain
         * bread's label was kept as 100% Whole Wheat the same way. A label they scan for
         * a line is the food in their hand; another kind becomes it.
         */
        fun sameProduct(a: FoodCandidate, b: FoodCandidate): Boolean {
            if (a.id == b.id) return true
            if (!a.brand.orEmpty().trim().equals(b.brand.orEmpty().trim(), ignoreCase = true)) return false
            val wa = com.nutritionscribe.shared.food.FoodQuery.contentWords(a.name).toSet()
            val wb = com.nutritionscribe.shared.food.FoodQuery.contentWords(b.name).toSet()
            if (wa.isEmpty() || wb.isEmpty()) return false
            return (wa intersect wb).size >= minOf(2, minOf(wa.size, wb.size)) && (wa.containsAll(wb) || wb.containsAll(wa))
        }

        /** "Sun-maid — California Raisins", not "Sun-maid — Sun-maid California Raisins". */
        fun withBrand(c: FoodCandidate, sep: String): String {
            val b = c.brand?.trim()?.takeIf { it.isNotBlank() && !c.name.contains(it, ignoreCase = true) }
            return if (b != null) "$b$sep${c.name}" else c.name
        }

        /** Where a food's numbers came from, as the card says it. */
        fun label(c: FoodCandidate?): String = when {
            c?.id?.startsWith("web:") == true -> "Found online"
            // A scan the store pack answered says so (owner, 11-19: "Found in your Great Value pack").
            else -> com.nutritionscribe.shared.stores.StoreCatalog.forCandidate(c?.id)?.let { "Package label · ${it.title} pack" } ?: label(c?.origin)
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
