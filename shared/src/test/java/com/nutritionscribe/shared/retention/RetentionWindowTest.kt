package com.nutritionscribe.shared.retention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetentionWindowTest {

    /**
     * Review F2 (2026-11-04): by default a daily job renamed entries older than 3
     * months "Compacted entry" and deleted those older than a year, and nobody was
     * told. A year of logs is a few MB; nothing is dropped unless someone asks.
     */
    @Test
    fun `nothing is dropped by default`() {
        assertNull(RetentionWindow.DEFAULT_PROTEIN_TOTALS.cutoffEpochMillis(1_800_000_000_000))
        assertNull(RetentionWindow.DEFAULT_FOOD_DETAILS.cutoffEpochMillis(1_800_000_000_000))
        assertEquals(RetentionWindow.FOREVER, RetentionWindow.DEFAULT_FOOD_DETAILS)
    }

    @Test
    fun `a chosen window still cuts off where it says`() {
        val now = 1_800_000_000_000
        assertEquals(now - 90L * 86_400_000, RetentionWindow(3, RetentionUnit.MONTHS).cutoffEpochMillis(now))
    }
}
