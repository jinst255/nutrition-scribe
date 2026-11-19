package com.nutritionscribe.app.data.export

import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.GoalDirection
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import com.nutritionscribe.shared.model.Profile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.LocalDate

/**
 * The JSON backup: the log, what the app has learned from it (My foods), and the
 * person's own settings that make the numbers mean anything: goals, About you and
 * plasma days. Without the last part a restore onto a new phone kept every entry
 * and lost every target (review S2). Never the API key.
 *
 * A backup with none of the extras keeps the plain-array shape older versions read.
 */
object BackupJson {

    @Serializable
    data class Goal(
        val metric: String, val enabled: Boolean, val target: Double? = null, val direction: String? = null,
        val floor: Double? = null, val ceiling: Double? = null, val sortOrder: Int = 0, val pinned: Boolean = false,
    )

    @Serializable
    data class Extras(val goals: List<Goal> = emptyList(), val profile: Profile? = null, val plasma: List<String> = emptyList()) {
        fun goals(): List<MetricGoal> = goals.mapNotNull { g ->
            val m = Metric.fromKey(g.metric) ?: return@mapNotNull null
            MetricGoal(m, g.enabled, g.target, g.direction?.let { d -> runCatching { GoalDirection.valueOf(d) }.getOrNull() },
                g.floor, g.ceiling, g.sortOrder, g.pinned)
        }

        fun plasmaDays(): Set<LocalDate> = plasma.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()

        val isEmpty: Boolean get() = goals.isEmpty() && profile == null && plasma.isEmpty()

        companion object {
            fun of(goals: List<MetricGoal>, profile: Profile?, plasma: Set<LocalDate>) = Extras(
                goals = goals.map { Goal(it.metric.key, it.enabled, it.target, it.direction?.name, it.floorValue, it.ceilingValue, it.sortOrder, it.pinned) },
                profile = profile,
                plasma = plasma.sorted().map { it.toString() },
            )
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(entries: List<Entry>, myFoods: List<JsonElement>, extras: Extras?): String {
        val list = json.encodeToJsonElement(ListSerializer(Entry.serializer()), entries)
        if (myFoods.isEmpty() && (extras == null || extras.isEmpty)) return list.toString()
        return buildJsonObject {
            put("format", JsonPrimitive("nutrition-scribe/2"))
            put("entries", list)
            if (myFoods.isNotEmpty()) put("myFoods", JsonArray(myFoods))
            if (extras != null && !extras.isEmpty) put("settings", json.encodeToJsonElement(Extras.serializer(), extras))
        }.toString()
    }

    private fun root(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /** The goals, About you and plasma days in a backup; null for older files without them. */
    fun extrasOf(text: String): Extras? =
        root(text)?.get("settings")?.let { runCatching { json.decodeFromJsonElement(Extras.serializer(), it) }.getOrNull() }

    /** The current format's entries (older shapes are read by ExportImportManager). */
    fun entriesOf(text: String): List<Entry> {
        val list = root(text)?.get("entries")?.toString() ?: text
        return json.decodeFromString(ListSerializer(Entry.serializer()), list)
    }
}
