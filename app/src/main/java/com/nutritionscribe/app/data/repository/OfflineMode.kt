package com.nutritionscribe.app.data.repository

/**
 * "Run in offline mode" (owner, 2026-11-14), which replaced "Highest quality
 * mode": that was really AI on or off, and the name promised a quality setting
 * that did not exist. Offline is the honest name for the off side, and it is what
 * the app does without a key whatever was chosen.
 */
object OfflineMode {

    fun isOffline(source: LookupSource, hasKey: Boolean): Boolean = source == LookupSource.OFFLINE || !hasKey

    /**
     * What flipping the switch stores. Null when it cannot happen yet: offline off
     * with no key to use. The switch then stays on and a short note offers the key.
     */
    fun sourceFor(offlineOn: Boolean, hasKey: Boolean): LookupSource? = when {
        offlineOn -> LookupSource.OFFLINE
        hasKey -> LookupSource.AI
        else -> null
    }

    /** Web search runs only with AI on and the person's say-so (on by default). */
    fun webSearchActive(offline: Boolean, webSearchAllowed: Boolean): Boolean = !offline && webSearchAllowed
}
