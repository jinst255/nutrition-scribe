package com.nutritionscribe.app.data.repository

/**
 * Opens the encrypted key store without letting an unreadable one crash the app.
 *
 * Why this exists: on Android 12+, `allowBackup="false"` stops cloud backup but not
 * a phone-to-phone transfer. A transfer can copy the encrypted prefs file to a new
 * phone without the Android Keystore key that decrypts it (Keystore keys never
 * leave the device). `EncryptedSharedPreferences.create()` then throws, and every
 * read of the API key crashed the app. The file is also now kept out of transfers
 * (res/xml/data_extraction_rules.xml); this covers phones that already got one,
 * and a Keystore that breaks for any other reason.
 *
 * What it does: try to open it; if that fails, wipe the store (its contents
 * can't be decrypted anyway, so nothing readable is lost) and try once more; if
 * that fails too, return null so the app runs without a saved key (offline
 * lookups still work) instead of crashing.
 *
 * Plain Kotlin with no Android types, so it is tested on the JVM (SecureStoreOpenerTest).
 */
object SecureStoreOpener {

    /** What happened, so the caller can tell the person their key needs entering again. */
    enum class Outcome { OPENED, RESET, UNAVAILABLE }

    fun <T : Any> open(create: () -> T, wipe: () -> Unit): Pair<T?, Outcome> {
        runCatching(create).onSuccess { return it to Outcome.OPENED }
        runCatching(wipe)
        return runCatching(create).fold(
            onSuccess = { it to Outcome.RESET },
            onFailure = { null to Outcome.UNAVAILABLE },
        )
    }

    /** A read that can't be decrypted means "no key", not a crash. */
    inline fun <T> read(block: () -> T?): T? = try { block() } catch (e: Exception) { null }
}
