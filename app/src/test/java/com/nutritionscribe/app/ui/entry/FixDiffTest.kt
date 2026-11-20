package com.nutritionscribe.app.ui.entry

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the "Fixed by AI" card says changed (owner, 11-19: the bare snackbar wasn't enough). */
class FixDiffTest {
    @Test
    fun `calories first, then what moved, at most two`() {
        val before = mapOf("calories" to 420.0, "protein" to 30.0, "sodium" to 1100.0)
        val after = mapOf("calories" to 840.0, "protein" to 30.4, "sodium" to 760.0)
        assertEquals(listOf("420 → 840 kcal", "1,100 → 760 mg sodium"), FixDiff.lines(before, after))
    }

    @Test
    fun `nothing moved, nothing said`() {
        assertEquals(emptyList<String>(), FixDiff.lines(mapOf("calories" to 420.0), mapOf("calories" to 421.0)))
        assertEquals(emptyList<String>(), FixDiff.lines(emptyMap(), emptyMap()))
    }

    @Test
    fun `small numbers keep a decimal`() {
        assertEquals(listOf("2.5 → 6 g fiber"), FixDiff.lines(mapOf("fiber" to 2.5), mapOf("fiber" to 6.0)))
    }
}
