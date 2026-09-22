package com.nutritionscribe.wear.data

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.Wearable
import com.nutritionscribe.shared.sync.TodaySummary
import com.nutritionscribe.shared.sync.WatchSyncPaths
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Sends the raw entry text to the phone for processing (Section 4, watch flow). */
    suspend fun relayToPhone(rawText: String): Boolean {
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
