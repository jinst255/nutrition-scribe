package com.nutritionscribe.app.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class FrontOfferTest {
    /** Owner, 2026-10-27, on the front-of-pack match: "almost always wrong … correct one time" in two weeks. */
    @Test
    fun `only their own label is asked about, the pack's guess is a choice`() {
        assertEquals(FrontOffer.ASK, FrontOffer.of(yours = true, newKind = false))
        assertEquals(FrontOffer.ASK, FrontOffer.of(yours = false, newKind = true))
        assertEquals(FrontOffer.CHOICES, FrontOffer.of(yours = false, newKind = false))
    }
}
