package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What broke (found 11-20 taking README screenshots): "2 eggs and toast" showed
 * "Egg · 2 slice" and "White bread · 1 cubic inch" on the check card. The weights
 * were right (100 g, 25 g), but the word for "one of it" was simply USDA's first
 * listed portion, and egg lists "1 slice" first and bread "1 cubic inch". The word
 * now comes from the portion that weighs what one of them was counted as.
 */
class PieceWordTest {
    private fun food(name: String, vararg p: Pair<String, Double>) =
        FoodCandidate(id = "usda:1", name = name, origin = Origin.USDA, portions = p.map { Portion(it.first, it.second) })

    private val egg = food("Egg, whole, cooked", "1 slice" to 10.0, "1 large" to 50.0, "1 medium" to 44.0, "1 cup, chopped" to 136.0)
    private val bread = food("Bread, white", "1 cubic inch" to 7.0, "1 slice" to 25.0, "1 cup, crumbs" to 45.0)

    @Test
    fun `the word matches the weight one was counted as`() {
        assertEquals("large", Units.pieceOf(egg, perPiece = 50.0))
        assertEquals("slice", Units.pieceOf(bread, perPiece = 25.0))
        assertEquals("medium", Units.pieceOf(egg, perPiece = 45.0))
    }

    @Test
    fun `with no weight to go by, an odd measure like a cubic inch is never the word`() {
        assertEquals("slice", Units.pieceOf(bread))
        // Nothing near the weight: the plain fallback, still not the cubic inch.
        assertEquals("slice", Units.pieceOf(bread, perPiece = 300.0))
    }

    @Test
    fun `the unit options say the same word`() {
        val item = ItemParser.Item(said = "2 eggs", food = "eggs", amount = 2.0)
        assertEquals("large", Units.optionsFor(egg, item, perPiece = 50.0).first { it.unit == null }.label)
    }

    @Test
    fun `USDA's long slice names come out as slice`() {
        // The real "Bread, white, toasted" portions: "2 eggs and toast" showed "1 whole".
        val toast = food("Bread, white, toasted", "1 medium or regular slice" to 25.0, "1 cup" to 40.0,
            "1 large or thick slice" to 39.0, "1 small or thin/very thin slice" to 22.0, "1 cubic inch" to 3.9)
        assertEquals("slice", Units.pieceOf(toast, perPiece = 25.0))
        assertEquals("slice", Units.pieceOf(toast, perPiece = 39.0))
    }
}
