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

/** Export/import (Section 12/19): the only way to move or back up data, since there's no cloud sync. */
class ExportImportManager(private val context: Context, private val entryDao: EntryDao) {

    suspend fun exportTo(uri: Uri, format: ExportFormat) {
        val entries = entryDao.observeAll().first().map { it.toEntry() }
        val text = when (format) {
            ExportFormat.JSON -> toJson(entries)
            ExportFormat.CSV -> EntryCsv.encode(entries)
        }
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    }

    suspend fun importFrom(uri: Uri, format: ExportFormat) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
        val entries = when (format) {
            ExportFormat.JSON -> fromJson(text)
            ExportFormat.CSV -> EntryCsv.decode(text)
        }
        entries.forEach { entryDao.upsert(it.toRow(), it.toNutrientRows()) }
    }

    private fun toJson(entries: List<Entry>): String =
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Entry.serializer()),
            entries,
        )

    private fun fromJson(text: String): List<Entry> =
        kotlinx.serialization.json.Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Entry.serializer()),
            text,
        )
}
