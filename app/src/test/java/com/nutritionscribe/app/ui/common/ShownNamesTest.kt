package com.nutritionscribe.app.ui.common

import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.LoggedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShownNamesTest {
    private fun item(name: String) = LoggedItem(name = name, said = name, key = name, origin = "USDA")

    /** Review L2: History and Home showed "Apples, raw, with skin (Includes foods for USDA's…". */
    @Test
    fun `an entry named by the app shows short names, and one named by a person keeps theirs`() {
        val items = listOf(item("Apples, raw, with skin (Includes foods for USDA's Food Distribution Program)"), item("Cheese, cottage, NFS"))
        val auto = Entry(id = "1", timestampEpochMillis = 0, rawText = "apple and cottage cheese",
            name = items.joinToString(", ") { it.name.substringBefore(",") }, source = "usda", confidence = Confidence.ESTIMATED, items = items)
        assertEquals("Apples, Cottage cheese", auto.shownName())
        assertEquals("Snack plate", auto.copy(name = "Snack plate").shownName())
        val one = auto.copy(items = items.take(1), name = items[0].name)
        assertEquals("Apples", one.shownName())
    }

    /** Review L1: the Log again chip for cottage cheese with pineapple read "Cheese + Pineapple". */
    @Test
    fun `log again chips say the food, not USDA's first word`() {
        val items = listOf(item("Cheese, cottage, NFS"), item("Pineapple, raw, all varieties"))
        val e = Entry(id = "1", timestampEpochMillis = 0, rawText = "", name = "Cheese, Pineapple", source = "usda",
            confidence = Confidence.ESTIMATED, items = items)
        assertEquals("Cottage cheese + Pineapple", logAgainName(e))
        assertEquals("Banana", logAgainName(e.copy(items = listOf(item("Banana, raw")), name = "Banana, raw")))
        assertEquals("Peanut butter and jelly sa…", logAgainName(e.copy(items = emptyList(), name = "Peanut butter and jelly sandwich, my usual way")))
    }

    /** On the emulator a chip read "Ghirardelli Chocolate Comp…": the brand used all the room. */
    @Test
    fun `log again chips name the product, not the maker`() {
        val choc = item("Ghirardelli Chocolate Company — Dark Chocolate Sea Salt Caramel Squares, Dark Chocolate Sea Salt Caramel")
        val e = Entry(id = "1", timestampEpochMillis = 0, rawText = "", name = choc.name, source = "label",
            confidence = Confidence.ESTIMATED, items = listOf(choc))
        val chip = logAgainName(e)
        assertTrue(chip, chip.startsWith("Dark Chocolate Sea Salt"))
        assertFalse(chip, "Ghirardelli" in chip)
        assertTrue(chip, chip.length <= 28)
    }
}
