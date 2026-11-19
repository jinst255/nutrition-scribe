package com.nutritionscribe.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineModeTest {

    /**
     * Owner, 2026-11-14: "Highest quality mode" was AI on/off under a misleading
     * name; a tester skipped the key and ran offline all week without knowing it.
     * Offline is what the app is without a key, whatever was chosen before.
     */
    @Test
    fun `without a key the app is offline, and turning that off asks for a key`() {
        assertTrue(OfflineMode.isOffline(LookupSource.AI, hasKey = false))
        assertFalse(OfflineMode.isOffline(LookupSource.AI, hasKey = true))
        assertTrue(OfflineMode.isOffline(LookupSource.OFFLINE, hasKey = true))
        assertNull("no key: the switch can't turn off yet", OfflineMode.sourceFor(offlineOn = false, hasKey = false))
        assertEquals(LookupSource.AI, OfflineMode.sourceFor(offlineOn = false, hasKey = true))
        assertEquals(LookupSource.OFFLINE, OfflineMode.sourceFor(offlineOn = true, hasKey = true))
    }

    @Test
    fun `web search needs AI on and the person's say-so`() {
        assertTrue(OfflineMode.webSearchActive(offline = false, webSearchAllowed = true))
        assertFalse(OfflineMode.webSearchActive(offline = true, webSearchAllowed = true))
        assertFalse(OfflineMode.webSearchActive(offline = false, webSearchAllowed = false))
    }
}
