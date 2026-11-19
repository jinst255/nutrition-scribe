package com.nutritionscribe.app.ui.confirm

import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.ItemParser
import com.nutritionscribe.shared.resolve.Origin
import com.nutritionscribe.shared.resolve.Resolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemDraftTest {

    /** An AI answer: 3 tbsp of peanut butter at 48 g and 282 kcal, with no tablespoon on record. */
    private val pb = FoodCandidate(
        id = "ai:pb", name = "Peanut butter", origin = Origin.AI,
        perServing = mapOf("calories" to 282.0), per100g = mapOf("calories" to 282.0 * 100 / 48), defaultGrams = 48.0,
    )

    private fun draft(amount: Double, grams: Double, kcal: Double) = ItemDraft(
        item = ItemParser.Item(said = "$amount tbsp peanut butter", food = "peanut butter", amount = amount, unit = "tbsp"),
        key = "butter peanut", candidate = pb, grams = grams, portionNote = "your usual",
        computed = mapOf("calories" to kcal), status = Resolver.Status.REMEMBERED, weak = false,
        offerScan = false, offerBreakdown = false, alternates = emptyList(), untouched = true,
    )

    /**
     * A tester's peanut butter and honey lost half their weight. Their line was
     * half a tablespoon at the right 8 g; pressing + re-weighed it through the
     * rough "a solid by volume" guess of 0.55 g/mL, so 1 tbsp became 8.1 g instead
     * of 16, and that was learned and logged every breakfast after (about 110 kcal
     * short each time). Changing only the amount must keep the weight of one unit.
     */
    @Test
    fun `stepping the amount keeps the weight of one unit`() {
        val half = draft(0.5, 8.0, 47.0)
        val one = half.withAmount(1.0)
        assertEquals(16.0, one.grams!!, 0.01)
        assertEquals(94.0, one.nutrients.getValue("calories"), 0.5)
        val oneAndHalf = one.withAmount(1.5)
        assertEquals(24.0, oneAndHalf.grams!!, 0.01)
        assertEquals(141.0, oneAndHalf.nutrients.getValue("calories"), 0.5)
        // And back down again lands where it started.
        assertEquals(8.0, oneAndHalf.withAmount(0.5).grams!!, 0.01)
    }

    /**
     * The owner logged 4 chocolate squares (15 g each, 60 g), opened the entry and
     * made it 5: it saved 500 g and 2,333 kcal. A line rebuilt from a logged entry
     * has no portions (a barcode found online keeps none), so one square was looked
     * up as a generic 100 g. Rebuilt the way LogPipeline.toConfirmed does it.
     */
    @Test
    fun `changing a logged count keeps the weight of one piece`() {
        val per100 = mapOf("calories" to 466.7)
        val rebuilt = ItemDraft.from(Resolver.Confirmed(
            item = ItemParser.Item(said = "dark chocolate caramel", food = "dark chocolate caramel", amount = 4.0),
            key = "caramel chocolate dark", name = "Dark Chocolate Caramel",
            candidate = FoodCandidate(id = "off:747", name = "Dark Chocolate Caramel", origin = Origin.LABEL,
                per100g = per100, perServing = mapOf("calories" to 280.0)),
            grams = 60.0, nutrients = mapOf("calories" to 280.0),
        ))
        val five = rebuilt.withAmount(5.0)
        assertEquals(75.0, five.grams!!, 0.01)
        assertEquals(350.0, five.nutrients.getValue("calories"), 0.5)
    }

    /**
     * Owner, 2026-10-27: a barcode found a candy with no potassium; "I was just
     * gonna scan [the label] for that. I didn't want to overwrite everything." A
     * label scanned onto the same product fills what was missing and keeps the
     * rest; "use all its numbers" is a separate, deliberate step.
     */
    @Test
    fun `scanning the same product's label fills the gaps and keeps the rest`() {
        val barcode = FoodCandidate(id = "off:123", name = "Sour Gummies", brand = "Sweets Co", origin = Origin.LABEL,
            per100g = mapOf("calories" to 350.0, "sugar" to 60.0), labelServingGrams = 40.0)
        val line = ItemDraft.fromCandidate(barcode)
        val label = FoodCandidate(id = "scan:9", name = "Sour Gummies", brand = "Sweets Co", origin = Origin.LABEL,
            per100g = mapOf("calories" to 340.0, "sugar" to 58.0, "potassium" to 25.0), labelServingGrams = 40.0)
        val (merged, filled) = line.withLabelScan(label)
        assertEquals(setOf("potassium"), filled)
        assertEquals(10.0, merged.nutrients.getValue("potassium"), 0.01)
        assertEquals("the barcode's own calories stay", 140.0, merged.nutrients.getValue("calories"), 0.01)
        assertEquals(136.0, merged.withCandidate(label).nutrients.getValue("calories"), 0.01)
        // Another product altogether is a swap, as before.
        val other = label.copy(id = "scan:10", name = "Chocolate Bar", brand = "Other Co")
        val (swapped, _) = line.withLabelScan(other)
        assertEquals("scan:10", swapped.candidate?.id)
    }

    /**
     * A scanned label opens as one serving, 30 g. Pressing − made it 7.5 g, not 15:
     * with no amount the line meant a serving, with one it meant a piece (15 g), so
     * the owner saw "30" turn into a quarter. The first step names the unit it was
     * counting all along.
     */
    @Test
    fun `the first step on a scanned label counts servings`() {
        val label = FoodCandidate(
            id = "label:sq", name = "Caramel Squares", origin = Origin.LABEL,
            per100g = mapOf("calories" to 467.0), labelServingGrams = 30.0, labelServingText = "3 pieces (30 g)",
            portions = listOf(com.nutritionscribe.shared.resolve.Portion("1 piece", 15.0), com.nutritionscribe.shared.resolve.Portion("1 serving", 30.0)),
        )
        val d = ItemDraft.fromCandidate(label)
        assertEquals(30.0, d.grams!!, 0.01)
        val half = d.withAmount(0.5)
        assertEquals(15.0, half.grams!!, 0.01)
        assertEquals("serving", half.item.unit)
        assertEquals(30.0, half.withAmount(1.0).grams!!, 0.01)
    }

    /**
     * Owner, 11-18: they scanned the label of their sliced low-sodium bacon onto a
     * line that had matched Great Value Real Bacon Pieces (bacon bits). The names
     * shared "great value bacon", so the scan counted as the same product: it only
     * filled gaps and kept the bits' 7 g serving, and "5 slices" logged as 2.5
     * servings, 17.5 g, 75 kcal. Their multi-grain bread label was kept as 100% Whole
     * Wheat the same way. A label of another kind replaces the line, amount kept.
     */
    @Test
    fun `a scanned label of another kind replaces the line and keeps the amount`() {
        val bits = FoodCandidate(id = "label:bits", name = "Great Value — Real Bacon Pieces", origin = Origin.LABEL,
            per100g = mapOf("calories" to 428.6, "protein" to 42.9), labelServingGrams = 7.0, labelServingText = "1 tbsp (7 g)")
        val slices = ItemDraft(
            item = ItemParser.Item(said = "five slices of low sodium great value bacon", food = "low sodium great value bacon", amount = 5.0, unit = "slice"),
            key = "bacon great low sodium value", candidate = bits, grams = 40.0, portionNote = "one cooked strip",
            computed = bits.nutrientsFor(40.0), status = Resolver.Status.NEW, weak = false,
            offerScan = true, offerBreakdown = false, alternates = emptyList(), untouched = false,
        )
        val scanned = FoodCandidate(id = "scan:bacon", name = "low sodium great value bacon", origin = Origin.LABEL,
            per100g = mapOf("calories" to 500.0, "protein" to 38.9), perServing = mapOf("calories" to 90.0, "protein" to 7.0),
            labelServingGrams = 18.0)
        val (line, filled) = slices.withLabelScan(scanned)
        assertEquals("scan:bacon", line.candidate?.id)
        assertEquals(emptySet<String>(), filled)
        assertEquals(5.0, line.amount, 0.001)
        assertEquals("slice", line.item.unit)
        assertTrue("5 slices of bacon is more than the bits' 17.5 g: ${line.grams}", line.grams!! >= 35.0)
        assertTrue(line.nutrients.getValue("calories") > 150.0)

        val wholeWheat = FoodCandidate(id = "label:ww", name = "Great Value — 100% Whole Wheat Round Top Bread, 100% Whole Wheat",
            origin = Origin.LABEL, per100g = mapOf("calories" to 240.0), labelServingGrams = 25.0)
        val multiGrain = FoodCandidate(id = "scan:mg", name = "Great value multi-grain bread", origin = Origin.LABEL,
            per100g = mapOf("calories" to 250.0), perServing = mapOf("calories" to 100.0), labelServingGrams = 40.0)
        assertFalse(ItemDraft.sameProduct(wholeWheat, multiGrain))
        assertEquals("scan:mg", ItemDraft.fromCandidate(wholeWheat).withLabelScan(multiGrain).first.candidate?.id)
        // The same product named more briefly is still the same product.
        val chobani = FoodCandidate(id = "off:c", name = "Strawberry Blended Nonfat Greek Yogurt, Strawberry", brand = "Chobani", origin = Origin.LABEL)
        assertTrue(ItemDraft.sameProduct(chobani, chobani.copy(id = "scan:c", name = "strawberry greek yogurt")))
    }

    /**
     * Owner, 11-16: "When I manually edit in one of the nutrients the values it
     * stops scaling." A typed number stayed fixed while the amount changed: 3 slices
     * with protein typed as 12 g still said 12 g at 6 slices.
     */
    @Test
    fun `a number typed by hand scales with the amount`() {
        val typed = draft(2.0, 32.0, 188.0).withNutrient("protein", 8.0).withNutrient("calories", 200.0)
        val four = typed.withAmount(4.0)
        assertEquals(16.0, four.nutrients.getValue("protein"), 0.01)
        assertEquals(400.0, four.nutrients.getValue("calories"), 0.01)
        val one = four.withAmount(1.0)
        assertEquals(4.0, one.nutrients.getValue("protein"), 0.01)
        // Clearing the typed number goes back to the computed one.
        assertEquals(94.0, one.withNutrient("calories", null).nutrients.getValue("calories"), 0.5)
    }
}
