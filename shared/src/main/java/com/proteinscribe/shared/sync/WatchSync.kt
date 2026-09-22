package com.proteinscribe.shared.sync

/**
 * Wearable Data Layer message/path constants shared by the phone and watch modules,
 * so both sides agree on the wire protocol without duplicating string literals.
 */
object WatchSyncPaths {
    /** Watch -> phone: MessageClient message carrying a JSON-encoded raw entry to process. */
    const val MESSAGE_LOG_ENTRY = "/protein-scribe/log-entry"

    /** Phone -> watch: MessageClient message carrying a JSON-encoded confirmed Entry. */
    const val MESSAGE_ENTRY_CONFIRMED = "/protein-scribe/entry-confirmed"

    /** Phone -> watch: DataClient path for the small always-on cache (today's total + goal). */
    const val DATA_TODAY_SUMMARY = "/protein-scribe/today-summary"
}

/** Payload synced to the watch's local cache for fast cold-start rendering (Section 4). */
@kotlinx.serialization.Serializable
data class TodaySummary(
    val proteinGrams: Double,
    val goalGrams: Double,
    val lastUpdatedEpochMillis: Long,
)
