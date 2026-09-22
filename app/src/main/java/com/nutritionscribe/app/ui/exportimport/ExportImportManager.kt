package com.nutritionscribe.app.ui.exportimport

import android.content.Context
import android.net.Uri
import com.nutritionscribe.app.data.db.EntryDao
import com.nutritionscribe.app.data.export.EntryCsv
import com.nutritionscribe.app.data.db.toEntry
import com.nutritionscribe.app.data.db.toNutrientRows
import com.nutritionscribe.app.data.db.toRow
import com.nutritionscribe.shared.model.Entry
import kotlinx.coroutines.flow.first

enum class ExportFormat { CSV, JSON }

/** What actually happened, so the screen can say so. */
sealed class TransferResult {
    data class Exported(val count: Int, val format: ExportFormat) : TransferResult()
    data class Imported(val count: Int, val format: ExportFormat) : TransferResult()
    data class Failed(val message: String) : TransferResult()
}

/** Export/import (Section 12/19): the only way to move or back up data, since there's no cloud sync. */
class ExportImportManager(private val context: Context, private val entryDao: EntryDao) {

    /**
     * Both of these used to return Unit, and the screen threw the result away.
     * So an export that wrote nothing, and an import that read a file the app
     * could not parse, both looked exactly like success: the sheet closed and
     * nothing else happened. For the ONLY route a user's data has in or out of
     * this app, with no cloud copy behind it, silence is the worst possible
     * answer.
     */
    suspend fun exportTo(uri: Uri, format: ExportFormat): TransferResult = try {
        val entries = entryDao.observeAll().first().map { it.toEntry() }
        val text = when (format) {
            ExportFormat.JSON -> toJson(entries)
            ExportFormat.CSV -> EntryCsv.encode(entries)
        }
        val stream = context.contentResolver.openOutputStream(uri)
            ?: return TransferResult.Failed("Couldn't write to that file.")
        stream.use { it.write(text.toByteArray()) }
        TransferResult.Exported(entries.size, format)
    } catch (e: Exception) {
        TransferResult.Failed(e.message ?: "Export failed.")
    }

    /**
     * The format is detected from the file, not taken from whatever chip happened
     * to be selected. Picking the wrong one was an error the user had no way to
     * see coming, and there is no reason to make them get it right.
     */
    suspend fun importFrom(uri: Uri): TransferResult = try {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
        when {
            text == null -> TransferResult.Failed("Couldn't read that file.")
            text.isBlank() -> TransferResult.Failed("That file is empty.")
            else -> {
                val first = text.trimStart().firstOrNull()
                val looksJson = first == '[' || first == '{'
                val entries = if (looksJson) fromJson(text) else EntryCsv.decode(text)
                if (entries.isEmpty()) {
                    TransferResult.Failed("No entries found in that file.")
                } else {
                    entries.forEach { entryDao.upsert(it.toRow(), it.toNutrientRows()) }
                    TransferResult.Imported(
                        entries.size,
                        if (looksJson) ExportFormat.JSON else ExportFormat.CSV,
                    )
                }
            }
        }
    } catch (e: Exception) {
        TransferResult.Failed(
            "That doesn't look like a Nutrition Scribe export." +
                (e.message?.let { " ($it)" } ?: ""),
        )
    }

    private fun toJson(entries: List<Entry>): String =
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Entry.serializer()),
            entries,
        )

    /**
     * Reads the current format, falling back to the pre-rename one.
     *
     * v1 wrote each nutrient as its own field (`proteinGrams`, `calories`, ...);
     * v2 writes a `nutrients` map. Since the applicationId changed, exporting from
     * the old app and importing here is the ONLY route a user's existing log has
     * into this app — and without this fallback, choosing JSON rather than CSV
     * would throw and take their whole history with it.
     */
    private fun fromJson(text: String): List<Entry> {
        // The format is DETECTED, not guessed at with try/catch. Every field of
        // the current Entry has a default, so a v1 file decodes "successfully" as
        // v2 and silently yields entries with no nutrients at all — an import that
        // reports success and quietly discards every number in the file.
        val looksLegacy = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text)
                .let { it as? kotlinx.serialization.json.JsonArray }
                ?.firstOrNull()
                ?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.let { "nutrients" !in it && "proteinGrams" in it }
                ?: false
        }.getOrDefault(false)

        val serializer = kotlinx.serialization.builtins.ListSerializer(
            if (looksLegacy) LegacyEntry.serializer() else Entry.serializer(),
        )
        val decoded = lenientJson.decodeFromString(serializer, text)
        return decoded.map { if (it is LegacyEntry) it.toEntry() else it as Entry }
    }

    private val lenientJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }

    /** The v1 on-disk shape, kept only so that old exports can still be read. */
    @kotlinx.serialization.Serializable
    private data class LegacyEntry(
        val id: String,
        val timestampEpochMillis: Long,
        val rawText: String = "",
        val name: String,
        val proteinGrams: Double = 0.0,
        val calories: Double? = null,
        val carbsGrams: Double? = null,
        val fatGrams: Double? = null,
        val isFixedFood: Boolean = false,
        val source: String = "import",
        val confidence: com.nutritionscribe.shared.model.Confidence =
            com.nutritionscribe.shared.model.Confidence.ESTIMATED,
        val timeUnspecified: Boolean = false,
    ) {
        fun toEntry(): Entry = Entry(
            id = id,
            timestampEpochMillis = timestampEpochMillis,
            rawText = rawText.ifBlank { name },
            name = name,
            nutrients = com.nutritionscribe.shared.model.nutrientsOf(
                protein = proteinGrams,
                calories = calories,
                carbs = carbsGrams,
                fat = fatGrams,
            ),
            isFixedFood = isFixedFood,
            source = source,
            confidence = confidence,
            timeUnspecified = timeUnspecified,
        )
    }

}
