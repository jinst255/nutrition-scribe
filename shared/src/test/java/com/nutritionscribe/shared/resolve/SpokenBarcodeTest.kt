package com.nutritionscribe.shared.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "barcode 0 78742 05731 4" said on the watch or the phone (owner, 11-19): read
 * the number off the package aloud and it is looked up like a scan. Speech gives
 * digits in groups, as words ("oh seven eight"), or mixed, so all of those count.
 * The last digit is a check digit, which catches most misheard numbers.
 */
class SpokenBarcodeTest {
    @Test
    fun `digits in groups, as said`() {
        val b = SpokenBarcode.parse("barcode 0 78742 05731 4")!!
        assertEquals("078742057314", b.digits)
    }

    @Test
    fun `digit words, oh for zero, and bar code as two words`() {
        assertEquals("078742057314", SpokenBarcode.parse("bar code oh seven eight seven four two zero five seven three one four")!!.digits)
        assertEquals("078742057314", SpokenBarcode.parse("Barcode: 078742-057314.")!!.digits)
    }

    @Test
    fun `the check digit says whether it was heard right`() {
        assertTrue(SpokenBarcode.parse("barcode 036000291452")!!.valid)
        assertFalse(SpokenBarcode.parse("barcode 036000291453")!!.valid)
        assertTrue(SpokenBarcode.parse("barcode 4006381333931")!!.valid)   // EAN-13
        assertTrue(SpokenBarcode.parse("barcode 96385074")!!.valid)        // EAN-8
    }

    @Test
    fun `food sentences are not barcodes`() {
        assertNull(SpokenBarcode.parse("2 eggs and a slice of toast"))
        assertNull(SpokenBarcode.parse("a barcode-scanned protein bar"))
        assertNull(SpokenBarcode.parse("barcode 12345"))                     // too short
        assertNull(SpokenBarcode.parse("1200 calories of barcode"))
        assertNull(SpokenBarcode.parse(""))
    }
}

/** The resolver end: a spoken barcode is looked up, never guessed and never sent to the AI. */
class SpokenBarcodeResolveTest {
    private val cottage = FoodCandidate(
        id = "off:078742057316", name = "Small Curd Cottage Cheese", brand = "Great Value", origin = Origin.LABEL,
        per100g = mapOf("calories" to 97.0, "protein" to 11.0), labelServingGrams = 113.0, labelServingText = "1/2 cup (113 g)",
        barcode = "078742057316",
    )
    private val asked = mutableListOf<String>()
    private fun resolver() = Resolver(InMemoryPersonalStore(), TestDbs.usda, null,
        barcodeOnline = { code -> asked += code; if (code == "078742057316") cottage else null })

    @Test
    fun `a spoken barcode finds the product`() {
        val r = resolver().resolve("barcode 0 78742 05731 6")
        val item = r.items.single()
        assertEquals(Resolver.Status.NEW, item.status)
        assertEquals("off:078742057316", item.choice?.id)
        assertEquals(113.0, item.grams!!, 1e-9)
        assertEquals(109.61, item.nutrients["calories"]!!, 0.01)
        assertEquals(listOf("078742057316"), asked)
        assertFalse(resolver().wantsAi("barcode 0 78742 05731 6"))
    }

    @Test
    fun `a misheard or unknown barcode is a line to scan, not a guess`() {
        val bad = resolver().resolve("barcode 0 78742 05731 4").items.single()
        assertEquals(Resolver.Status.UNKNOWN, bad.status)
        assertTrue(bad.offerScan)
        assertTrue(asked.isEmpty())   // a failed check digit isn't even looked up
        val unknown = resolver().resolve("barcode 036000291452").items.single()
        assertEquals(Resolver.Status.UNKNOWN, unknown.status)
        assertEquals("barcode 036000291452", unknown.item.said)
    }
}
