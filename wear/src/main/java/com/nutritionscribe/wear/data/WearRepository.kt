package com.nutritionscribe.wear.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.Wearable
import com.nutritionscribe.shared.sync.TodaySummary
import com.nutritionscribe.shared.sync.WatchProtocol
import com.nutritionscribe.shared.sync.WatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private val Context.wearDataStore by preferencesDataStore(name = "protein_scribe_wear_cache")

/**
 * Watch-side data (watch design approved 11-19). The phone is the source of truth:
 * it resolves every word and owns every entry. The watch keeps only a cache of
 * the phone's [WatchState], its own two settings, and what was said while the
 * phone was away, so nothing said is lost.
 */
class WearRepository(private val context: Context) {

    private object Keys {
        val PROTEIN = doublePreferencesKey("protein_grams")
        val GOAL = doublePreferencesKey("goal_grams")
        val UPDATED = longPreferencesKey("last_updated")
        val STATE = stringPreferencesKey("watch_state")

        /** "time|text" said while the phone was away; sent as a log at that time. */
        val PENDING = stringSetPreferencesKey("pending_entries")

        /** What the watch home shows: "goals" (default) or "need". */
        val HOME_MODE = stringPreferencesKey("home_mode")

        /** The one-time "how Say works" tip has been seen. */
        val SAY_INTRO = booleanPreferencesKey("say_intro_seen")
    }

    val state: Flow<WatchState> = context.wearDataStore.data.map { p ->
        WatchProtocol.decodeState(p[Keys.STATE]) ?: legacyState(p[Keys.PROTEIN], p[Keys.GOAL])
    }

    /** An older phone app only sends protein and its goal. */
    private fun legacyState(protein: Double?, goal: Double?): WatchState =
        if (protein == null || goal == null) WatchState()
        else WatchState(goals = listOf(WatchState.Goal("Protein", protein, goal, "g", false)))

    val todaySummary: Flow<TodaySummary> = context.wearDataStore.data.map {
        TodaySummary(it[Keys.PROTEIN] ?: 0.0, it[Keys.GOAL] ?: 160.0, it[Keys.UPDATED] ?: 0L)
    }

    val homeShowsNeed: Flow<Boolean> = context.wearDataStore.data.map { it[Keys.HOME_MODE] == "need" }
    suspend fun setHomeShowsNeed(need: Boolean) { context.wearDataStore.edit { it[Keys.HOME_MODE] = if (need) "need" else "goals" } }

    val sayIntroSeen: Flow<Boolean> = context.wearDataStore.data.map { it[Keys.SAY_INTRO] == true }
    suspend fun setSayIntroSeen() { context.wearDataStore.edit { it[Keys.SAY_INTRO] = true } }

    suspend fun cacheState(json: String) { context.wearDataStore.edit { it[Keys.STATE] = json } }

    suspend fun cacheTodaySummary(summary: TodaySummary) {
        context.wearDataStore.edit {
            it[Keys.PROTEIN] = summary.proteinGrams
            it[Keys.GOAL] = summary.goalGrams
            it[Keys.UPDATED] = summary.lastUpdatedEpochMillis
        }
    }

    val pendingCount: Flow<Int> = context.wearDataStore.data.map { (it[Keys.PENDING] ?: emptySet()).size }

    /** Keeps what was said for when the phone is back; it logs at the time it was said. */
    suspend fun keepForLater(text: String, at: Long) {
        context.wearDataStore.edit {
            // Timestamped so two identical foods on the same day are two entries,
            // not one — a Set would otherwise silently swallow the second.
            it[Keys.PENDING] = (it[Keys.PENDING] ?: emptySet()) + "$at|$text"
        }
        debug("kept_for_later", "chars" to text.length.toString())
    }

    /** Sends everything said while the phone was away. Called when the app opens. */
    suspend fun flushPending(): Int {
        val pending = context.wearDataStore.data.map { it[Keys.PENDING] ?: emptySet() }.first()
        if (pending.isEmpty()) return 0
        val sent = mutableSetOf<String>()
        for (item in pending) {
            val at = item.substringBefore("|").toLongOrNull() ?: System.currentTimeMillis()
            val req = WatchProtocol.Request(java.util.UUID.randomUUID().toString(), WatchProtocol.Kind.LOG, item.substringAfter("|"), at)
            if (send(WatchProtocol.MESSAGE_REQUEST, WatchProtocol.encode(req))) sent += item else break
        }
        if (sent.isNotEmpty()) context.wearDataStore.edit { it[Keys.PENDING] = (it[Keys.PENDING] ?: emptySet()) - sent }
        debug("flushed", "count" to sent.size.toString())
        return sent.size
    }

    /** False when no phone took it. */
    suspend fun request(r: WatchProtocol.Request): Boolean {
        debug("request", "kind" to r.kind, "chars" to (r.text?.length ?: 0).toString(), "ids" to r.ids.size.toString())
        return send(WatchProtocol.MESSAGE_REQUEST, WatchProtocol.encode(r))
    }

    private suspend fun send(path: String, data: ByteArray): Boolean = try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) false
        else { nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, path, data).await() }; true }
    } catch (e: Exception) {
        false
    }

    /**
     * The watch's own events, sent to the phone's debug log only while the phone is
     * in debug mode (owner, 11-19), so watch screens can be improved from real use.
     * Never the words said: counts and timings only.
     */
    fun debug(name: String, vararg data: Pair<String, String>) {
        bgScope.launch {
            val on = runCatching { state.first().debug }.getOrDefault(false)
            if (!on) return@launch
            val batch = WatchProtocol.DebugBatch(listOf(WatchProtocol.DebugEvent(System.currentTimeMillis(), name, data.toMap())))
            send(WatchProtocol.MESSAGE_DEBUG, WatchProtocol.encode(batch))
        }
    }

    companion object {
        private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Replies from the phone, for whichever screen is waiting. */
        private val _replies = MutableSharedFlow<WatchProtocol.Reply>(extraBufferCapacity = 8)
        val replies: SharedFlow<WatchProtocol.Reply> = _replies
        fun onReply(r: WatchProtocol.Reply) { _replies.tryEmit(r) }

        /** The last Log it, for the home's "Logged · Undo" chip. */
        val lastLogged = MutableStateFlow<Logged?>(null)
    }

    data class Logged(val requestId: String, val kcal: Int, val at: Long, val ids: List<String> = emptyList())
}
