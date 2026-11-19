package com.nutritionscribe.app.ui.debuglog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Label photos and shake-note screenshots shared one limit of 20. Screenshots sort
 * first, so 20 of them deleted every label photo, and the 21st deleted the oldest
 * screenshot (the 10-25 log lost 36 of 46 screenshots and all its label photos).
 * Each kind now keeps its own newest.
 */
class DebugPhotosTest {

    private fun scan(i: Int) = "scan-20261118-08%04d-000.jpg".format(i)
    private fun spot(i: Int) = "spot-20261118-15%04d-000.jpg".format(i)

    @Test
    fun `many screenshots no longer wipe out the label photos`() {
        val names = (1..5).map(::scan) + (1..25).map(::spot)
        val gone = DebugPhotos.toDelete(names, maxScans = 20, maxSpots = 60)
        assertTrue("nothing over either limit: $gone", gone.isEmpty())
    }

    @Test
    fun `each kind keeps its own newest`() {
        val names = (1..25).map(::scan) + (1..8).map(::spot)
        assertEquals(((1..5).map(::scan) + listOf(spot(1), spot(2))).sorted(),
            DebugPhotos.toDelete(names, maxScans = 20, maxSpots = 6).sorted())
        assertTrue(DebugPhotos.toDelete(emptyList(), 20, 60).isEmpty())
    }
}
