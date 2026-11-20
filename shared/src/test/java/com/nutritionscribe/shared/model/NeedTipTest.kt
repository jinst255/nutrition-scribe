package com.nutritionscribe.shared.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The watch's "A shake covers 30 g" tip comes from their own log, with no AI call (owner: must be cheap). */
class NeedTipTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private var n = 0
    private fun e(name: String, protein: Double, daysAgo: Int) = Entry(
        id = "e${++n}", timestampEpochMillis = now - daysAgo * day, rawText = name, name = name, source = "t",
        confidence = Confidence.STATED, nutrients = mapOf("protein" to protein),
    )

    @Test
    fun `their most eaten food that covers part of the gap`() {
        val log = listOf(e("Protein shake", 30.0, 1), e("Protein shake", 30.0, 3), e("Protein shake", 30.0, 9),
            e("Steak dinner", 70.0, 2), e("Almonds", 6.0, 1), e("Greek yogurt", 17.0, 4))
        assertEquals("Protein shake covers 30 g.", NeedTip.of(Metric.PROTEIN, 53.0, log, now))
    }

    @Test
    fun `nothing fitting, nothing left, or only old foods`() {
        assertNull(NeedTip.of(Metric.PROTEIN, 53.0, listOf(e("Almonds", 6.0, 1)), now))
        assertNull(NeedTip.of(Metric.PROTEIN, 0.0, listOf(e("Protein shake", 30.0, 1)), now))
        assertNull(NeedTip.of(Metric.PROTEIN, 53.0, listOf(e("Protein shake", 30.0, 45)), now))
    }

    @Test
    fun `the brand is dropped and a long name cut to fit the watch`() {
        assertEquals("Small Curd Cottage Chee… covers 22 g.",
            NeedTip.of(Metric.PROTEIN, 40.0, listOf(e("Great Value — Small Curd Cottage Cheese", 22.0, 1)), now))
    }
}
