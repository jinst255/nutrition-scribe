package com.nutritionscribe.shared.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the phone and watch say to each other (watch design, approved 11-19).
 *
 * The watch asks; the phone answers. The watch never resolves food itself: it
 * sends the words (or a Log again tap) and shows what the phone worked out.
 *
 *   watch: resolve "two eggs and toast"  → phone: heard [Eggs 2 large 143 kcal, …]
 *   watch: log (same id)                 → phone: logged [entry ids]   (Undo sends them back)
 *   watch: resolve "<a long order>"      → phone: tricky   (kept on the phone to fix with AI)
 *
 * The phone also keeps one [WatchState] data item up to date: the goals, what
 * is furthest behind with a tip, the Log again list and usual meals, and whether
 * debug mode is on (then the watch sends its own events, see [MESSAGE_DEBUG]).
 */
object WatchProtocol {
    const val MESSAGE_REQUEST = "/nutrition-scribe/request"
    const val MESSAGE_REPLY = "/nutrition-scribe/reply"
    const val MESSAGE_DEBUG = "/nutrition-scribe/debug"
    const val DATA_STATE = "/nutrition-scribe/state"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(r: Request): ByteArray = json.encodeToString(Request.serializer(), r).toByteArray()
    fun decodeRequest(b: ByteArray): Request? = runCatching { json.decodeFromString(Request.serializer(), String(b)) }.getOrNull()
    fun encode(r: Reply): ByteArray = json.encodeToString(Reply.serializer(), r).toByteArray()
    fun decodeReply(b: ByteArray): Reply? = runCatching { json.decodeFromString(Reply.serializer(), String(b)) }.getOrNull()
    fun encode(s: WatchState): String = json.encodeToString(WatchState.serializer(), s)
    fun decodeState(s: String?): WatchState? = s?.let { runCatching { json.decodeFromString(WatchState.serializer(), it) }.getOrNull() }
    fun encode(d: DebugBatch): ByteArray = json.encodeToString(DebugBatch.serializer(), d).toByteArray()
    fun decodeDebug(b: ByteArray): DebugBatch? = runCatching { json.decodeFromString(DebugBatch.serializer(), String(b)) }.getOrNull()

    object Kind {
        const val RESOLVE = "resolve"
        const val LOG = "log"
        const val LOG_AGAIN = "log_again"
        const val UNDO = "undo"
        const val HEARD = "heard"
        const val LOGGED = "logged"
        const val TRICKY = "tricky"
        const val FAILED = "failed"
    }

    @Serializable
    data class Request(
        /** One id per thing said; "log" repeats the id of its "resolve". */
        val id: String,
        val kind: String,
        val text: String? = null,
        /** When it was said on the watch: the entry keeps this time, never the time it synced. */
        val at: Long,
        /** Entry ids, for Log again (one or a whole usual meal) and Undo. */
        val ids: List<String> = emptyList(),
        /**
         * The person saw the foods on the watch's check screen before Log it: as good
         * as the phone's confirm card. False for words kept while the phone was away,
         * which nobody has looked at yet: those wait on the phone (From your watch).
         */
        val seen: Boolean = false,
    )

    @Serializable
    data class Line(val name: String, val sub: String, val kcal: Int)

    @Serializable
    data class Reply(
        val id: String,
        val kind: String,
        val heard: String? = null,
        val lines: List<Line> = emptyList(),
        /** Looked up from a barcode said out loud. */
        val barcode: Boolean = false,
        val loggedIds: List<String> = emptyList(),
        val message: String? = null,
    ) {
        val kcal: Int get() = lines.sumOf { it.kcal }
    }

    @Serializable
    data class DebugBatch(val events: List<DebugEvent>)

    @Serializable
    data class DebugEvent(val at: Long, val name: String, val data: Map<String, String> = emptyMap())
}

@Serializable
data class WatchState(
    val goals: List<Goal> = emptyList(),
    /** What is furthest behind today, with a food from their own log that covers it. */
    val need: Need? = null,
    val again: List<Again> = emptyList(),
    val meals: List<Meal> = emptyList(),
    /** Phone in debug mode: the watch sends its events too. */
    val debug: Boolean = false,
    val updatedAt: Long = 0,
    /** What the watch home shows, picked on the phone (Settings › Watch): [Home]. Null: the watch's own choice. */
    val home: String? = null,
    /** For [Home.ONE]: the one goal it shows. */
    val one: Goal? = null,
    /** When the latest entry was logged, for the "since you logged" slot. */
    val lastAt: Long? = null,
    /** Watch entries waiting on the phone for a check. */
    val toCheck: Int = 0,
) {
    object Home {
        const val GOALS = "goals"
        const val NEED = "need"
        const val ONE = "one"
    }

    @Serializable
    data class Goal(val name: String, val value: Double, val target: Double, val unit: String, val limit: Boolean) {
        val fraction: Float get() = if (target <= 0) 0f else (value / target).toFloat().coerceIn(0f, 1f)
    }

    @Serializable
    data class Need(
        val name: String, val value: Double, val target: Double, val unit: String, val tip: String? = null,
        /** Their own foods that close part of the gap, most eaten first: one tap logs one. */
        val foods: List<Fill> = emptyList(),
    ) {
        val left: Double get() = (target - value).coerceAtLeast(0.0)
    }

    /** A food that closes the gap: the entry it copies and what it gives ("Protein shake", 30 g). */
    @Serializable
    data class Fill(val id: String, val name: String, val amount: Double, val kcal: Int = 0)

    /** One food to log again: the entry it copies. [hour]: when in the day it's usually logged. */
    @Serializable
    data class Again(val id: String, val name: String, val sub: String, val kcal: Int = 0, val hour: Double? = null)

    /** "Your usual lunch": its entries, logged with one tap. [hour]: its usual time of day. */
    @Serializable
    data class Meal(val ids: List<String>, val label: String, val names: List<String>, val kcal: Int, val hour: Double? = null)
}
