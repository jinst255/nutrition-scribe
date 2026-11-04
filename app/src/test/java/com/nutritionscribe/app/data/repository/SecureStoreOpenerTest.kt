package com.nutritionscribe.app.data.repository

import com.nutritionscribe.app.data.repository.SecureStoreOpener.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What broke (security review, 2026-11-04): after a phone-to-phone transfer, the
 * encrypted key store arrived without the Keystore key that decrypts it.
 * EncryptedSharedPreferences.create() threw, nothing caught it, and every read of
 * the API key crashed the app. SecureStoreOpener now wipes the unreadable store and
 * retries, and falls back to "no store" rather than crashing.
 *
 * If one of these fails, SettingsRepository can crash again on a broken Keystore:
 * check that it still opens the store through SecureStoreOpener.open and reads keys
 * through SecureStoreOpener.read.
 */
class SecureStoreOpenerTest {

    private class Unreadable : java.security.GeneralSecurityException("AEADBadTagException: tag mismatch")

    @Test
    fun `a healthy store opens first time and is never wiped`() {
        var wiped = 0
        val (store, outcome) = SecureStoreOpener.open(create = { "store" }, wipe = { wiped++ })
        assertEquals("store", store)
        assertEquals(Outcome.OPENED, outcome)
        assertEquals(0, wiped)
    }

    @Test
    fun `a store copied from another phone is wiped and reopened instead of crashing`() {
        // The transferred file can't be decrypted until it's deleted.
        var fileIsForeign = true
        var wiped = 0
        val (store, outcome) = SecureStoreOpener.open(
            create = { if (fileIsForeign) throw RuntimeException(Unreadable()) else "fresh" },
            wipe = { wiped++; fileIsForeign = false },
        )
        assertEquals("fresh", store)
        assertEquals(Outcome.RESET, outcome)
        assertEquals(1, wiped)
    }

    @Test
    fun `a keystore that never works gives no store rather than a crash`() {
        var tries = 0
        val (store, outcome) = SecureStoreOpener.open<String>(
            create = { tries++; throw IllegalStateException("Keystore unavailable") },
            wipe = {},
        )
        assertNull(store)
        assertEquals(Outcome.UNAVAILABLE, outcome)
        assertEquals("exactly one retry, not a loop", 2, tries)
    }

    @Test
    fun `a wipe that itself fails still gets a second try`() {
        var tries = 0
        val (store, outcome) = SecureStoreOpener.open(
            create = { if (tries++ == 0) throw IllegalStateException("bad") else "ok" },
            wipe = { throw SecurityException("can't delete") },
        )
        assertEquals("ok", store)
        assertEquals(Outcome.RESET, outcome)
    }

    @Test
    fun `a key that can't be decrypted reads as no key`() {
        assertNull(SecureStoreOpener.read<String> { throw SecurityException(Unreadable()) })
        assertEquals("sk-test", SecureStoreOpener.read { "sk-test" })
        assertNull(SecureStoreOpener.read<String> { null })
    }
}
