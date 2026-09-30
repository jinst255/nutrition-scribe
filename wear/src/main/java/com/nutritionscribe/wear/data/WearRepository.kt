package com.nutritionscribe.wear.data

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.Wearable
import com.nutritionscribe.shared.sync.TodaySummary
import com.nutritionscribe.shared.sync.WatchSyncPaths
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

private val Context.wearDataStore by preferencesDataStore(name = "protein_scribe_wear_cache")

/**
 * Watch-side repository (Section 4): the watch does not store data independently
 * beyond this small local cache used for fast cold-start rendering. The phone is
 * always the source of truth; every confirmed entry and relay goes through it.
 */
class WearRepository(private val context: Context) {

    private object Keys {
        val PROTEIN = doublePreferencesKey("protein_grams")
        val GOAL = doublePreferencesKey("goal_grams")
        val UPDATED = longPreferencesKey("last_updated")

        /**
         * Entries spoken while the phone was unreachable.
         *
         * The watch used to drop these on the floor: relayToPhone returned false
         * when no node was connected, the caller ignored it, and what the user
         * said was gone. On a watch, "out of range" is the normal case, not the
         * edge case — you leave your phone in another room and go for a run.
         */
        val PENDING = stringSetPreferencesKey("pending_entries")

        /** "tap" or "hold": how the mic button works, set on the watch itself. */
        val MIC_MODE = androidx.datastore.preferences.core.stringPreferencesKey("mic_mode")
    }

    val holdToTalk: Flow<Boolean> = context.wearDataStore.data.map { it[Keys.MIC_MODE] == "hold" }

    suspend fun setHoldToTalk(hold: Boolean) {
        context.wearDataStore.edit { it[Keys.MIC_MODE] = if (hold) "hold" else "tap" }
    }

    val todaySummary: Flow<TodaySummary> = context.wearDataStore.data.map {
        TodaySummary(
            proteinGrams = it[Keys.PROTEIN] ?: 0.0,
            goalGrams = it[Keys.GOAL] ?: 160.0,
            lastUpdatedEpochMillis = it[Keys.UPDATED] ?: 0L,
        )
    }

    /** Latest confirm-screen result, held in memory only — not persisted, per Section 4. */
    private val _lastConfirmed = MutableStateFlow<com.nutritionscribe.shared.model.Entry?>(null)
    val lastConfirmed = _lastConfirmed.asStateFlow()

    suspend fun cacheTodaySummary(summary: TodaySummary) {
        context.wearDataStore.edit {
            it[Keys.PROTEIN] = summary.proteinGrams
            it[Keys.GOAL] = summary.goalGrams
            it[Keys.UPDATED] = summary.lastUpdatedEpochMillis
        }
    }

    fun onEntryConfirmed(entry: com.nutritionscribe.shared.model.Entry) {
        _lastConfirmed.value = entry
    }

    /** Entries waiting for a phone to come back into range. */
    val pendingCount: Flow<Int> = context.wearDataStore.data.map { (it[Keys.PENDING] ?: emptySet()).size }

    private suspend fun queueLocally(rawText: String) {
        context.wearDataStore.edit {
            // Timestamped so two identical foods on the same day are two entries,
            // not one — a Set would otherwise silently swallow the second.
            val stamped = "${System.currentTimeMillis()}|$rawText"
            it[Keys.PENDING] = (it[Keys.PENDING] ?: emptySet()) + stamped
        }
    }

    /**
     * Retries everything spoken while the phone was away. Called when the watch
     * app opens, which is the cheap moment to notice the phone is back.
     */
    suspend fun flushPending(): Int {
        val pending = context.wearDataStore.data.map { it[Keys.PENDING] ?: emptySet() }.first()
        if (pending.isEmpty()) return 0

        val sent = mutableSetOf<String>()
        for (item in pending) {
            if (send(item.substringAfter("|"))) sent += item else break
        }
        if (sent.isNotEmpty()) {
            context.wearDataStore.edit {
                it[Keys.PENDING] = (it[Keys.PENDING] ?: emptySet()) - sent
            }
        }
        return sent.size
    }

    /** Sends the raw entry text to the phone for processing (Section 4, watch flow). */
    suspend fun relayToPhone(rawText: String): Boolean {
        val ok = send(rawText)
        // Never lose what was said. If the phone is not there, keep it on the
        // watch and try again next time the app is opened.
        if (!ok) queueLocally(rawText)
        return ok
    }

    private suspend fun send(rawText: String): Boolean {
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) return false
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, WatchSyncPaths.MESSAGE_LOG_ENTRY, rawText.toByteArray())
                    .await()
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
