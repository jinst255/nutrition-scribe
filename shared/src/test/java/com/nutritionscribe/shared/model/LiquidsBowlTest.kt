package com.nutritionscribe.shared.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidsBowlTest {
    /**
     * On the emulator (2026-11-14) the AI's oatmeal came with "1 bowl (240 ml)" and
     * the card said "234 mL · 7.9 fl oz": a serving in mL made any food a drink.
     */
    @Test
    fun `a bowl measured in ml is still food`() {
        assertFalse(Liquids.isLiquid("Oatmeal", "1 bowl (240 ml)"))
        assertEquals("234 g", Liquids.amount(234.0, "Oatmeal", "1 bowl (240 ml)"))
        assertFalse(Liquids.isLiquid("Chili with beans", "1 cup (240 mL)"))
        // Drinks stay drinks.
        assertTrue(Liquids.isLiquid("Orange juice", "8 fl oz (240 ml)"))
        assertTrue(Liquids.isLiquid("Premier Protein shake", "1 bottle (325 ml)"))
        assertTrue(Liquids.isLiquid("Kombucha", "1 bottle (480 ml)"))
    }
}
