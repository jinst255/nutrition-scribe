package com.nutritionscribe.shared.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogAgainOneTest {
    private fun entry(amount: Double?, unit: String?, items: Int = 1): Entry {
        val item = LoggedItem(name = "Dark Chocolate Mint Squares", said = "4 squares", key = "k", origin = "LABEL",
            grams = 40.0, nutrients = mapOf("calories" to 220.0), amount = amount, unit = unit)
        return Entry(id = "e", timestampEpochMillis = 0, rawText = "4 squares", name = "Ghirardelli squares", source = "label",
            confidence = Confidence.ESTIMATED, nutrients = mapOf("calories" to 220.0 * items), items = List(items) { item })
    }

    /** Owner, 2026-10-31: Log again on "4 squares" could only log all 4 again, never one more. */
    @Test
    fun `one more of a counted food is a quarter of four`() {
        val one = LogAgain.oneOf(entry(4.0, "square"))!!
        assertEquals(55.0, one.nutrients.getValue("calories"), 0.001)
        assertEquals(10.0, one.items.single().grams!!, 0.001)
        assertEquals(1.0, one.items.single().amount!!, 0.0)
        assertEquals("1 square", one.items.single().portion)
        assertEquals(55.0, LogAgain.oneOf(entry(4.0, null))!!.nutrients.getValue("calories"), 0.001)
    }

    @Test
    fun `nothing to offer for one, a part, a measure or a meal`() {
        assertNull(LogAgain.oneOf(entry(1.0, "square")))
        assertNull(LogAgain.oneOf(entry(1.5, "square")))
        assertNull(LogAgain.oneOf(entry(2.0, "cup")))
        assertNull(LogAgain.oneOf(entry(null, null)))
        assertNull(LogAgain.oneOf(entry(4.0, "square", items = 2)))
    }
}
