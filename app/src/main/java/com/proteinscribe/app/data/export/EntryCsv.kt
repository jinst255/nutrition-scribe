package com.proteinscribe.app.data.export

import com.proteinscribe.shared.model.Confidence
import com.proteinscribe.shared.model.Entry
import com.proteinscribe.shared.model.Metric
import com.proteinscribe.shared.model.nutrientsOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure CSV encoding/decoding for entries — no Android types, so it can be tested on
 * the JVM without a device. The export is the only way data leaves this app, and it
 * is the point of the app for anyone doing analysis on their log, so it gets a real
 * round-trip test rather than being trusted because it looks obvious.
 */
object EntryCsv {

    const val HEADER = "id,timestamp,local_datetime,time_unspecified,needs_detail,raw_text,name," +
        "protein_grams,calories,carbs_grams,fat_grams,fiber_grams,sugar_grams," +
        "saturated_fat_grams,sodium_mg,caffeine_mg,is_fixed_food,source,confidence"

    fun encode(entries: List<Entry>, zone: ZoneId = ZoneId.systemDefault()): String {
        val rows = entries.joinToString("\n") { e ->
            // local_datetime duplicates the epoch column on purpose: raw millis are
            // unreadable in a spreadsheet and meal-timing analysis wants local wall
            // clock. It is blank when the user never gave a time, so an analysis
            // cannot mistake the stored noon placeholder for something they reported.
            val localDateTime = if (e.timeUnspecified) {
                ""
            } else {
                Instant.ofEpochMilli(e.timestampEpochMillis).atZone(zone)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            }
            listOf(
                e.id,
                e.timestampEpochMillis.toString(),
                localDateTime,
                e.timeUnspecified.toString(),
                e.needsDetail.toString(),
                escape(e.rawText),
                escape(e.name),
                e.proteinGrams.toString(),
                e.calories?.toString().orEmpty(),
                e.carbsGrams?.toString().orEmpty(),
                e.fatGrams?.toString().orEmpty(),
                e.amountOf(Metric.FIBER)?.toString().orEmpty(),
                e.amountOf(Metric.SUGAR)?.toString().orEmpty(),
                e.amountOf(Metric.SATURATED_FAT)?.toString().orEmpty(),
                e.amountOf(Metric.SODIUM)?.toString().orEmpty(),
                e.amountOf(Metric.CAFFEINE)?.toString().orEmpty(),
                e.isFixedFood.toString(),
                escape(e.source),
                e.confidence.name,
            ).joinToString(",")
        }
        return "$HEADER\n$rows"
    }

    /**
     * Reads columns by header NAME rather than position, so a file exported before
     * the local_datetime/time_unspecified columns existed still imports. A positional
     * parser would silently shift every field by two on those older files.
     */
    fun decode(text: String): List<Entry> {
        // Parsed as records, not lines: a quoted field may legally contain a newline
        // (a food name pasted from somewhere, a multi-line raw transcription).
        // Splitting on "\n" first turns one such row into two malformed ones and
        // silently corrupts the import.
        val records = splitRecords(text).filter { r -> r.any { it.isNotBlank() } }
        if (records.size <= 1) return emptyList()
        val index = records.first().withIndex()
            .associate { (i, name) -> name.trim() to i }
        return records.drop(1).mapNotNull { cols ->
            fun col(name: String): String? = index[name]?.let { cols.getOrNull(it) }

            val id = col("id").orEmpty()
            if (id.isBlank()) return@mapNotNull null
            val ts = col("timestamp")?.toLongOrNull() ?: return@mapNotNull null

            Entry(
                id = id,
                timestampEpochMillis = ts,
                rawText = col("raw_text").orEmpty(),
                name = col("name").orEmpty(),
                nutrients = nutrientsOf(
                    protein = col("protein_grams")?.toDoubleOrNull() ?: 0.0,
                    calories = col("calories")?.toDoubleOrNull(),
                    carbs = col("carbs_grams")?.toDoubleOrNull(),
                    fat = col("fat_grams")?.toDoubleOrNull(),
                    fiber = col("fiber_grams")?.toDoubleOrNull(),
                    sugar = col("sugar_grams")?.toDoubleOrNull(),
                    saturatedFat = col("saturated_fat_grams")?.toDoubleOrNull(),
                    sodium = col("sodium_mg")?.toDoubleOrNull(),
                    caffeine = col("caffeine_mg")?.toDoubleOrNull(),
                ),
                isFixedFood = col("is_fixed_food").toBoolean(),
                source = col("source").orEmpty(),
                confidence = runCatching { Confidence.valueOf(col("confidence").orEmpty()) }
                    .getOrDefault(Confidence.ESTIMATED),
                timeUnspecified = col("time_unspecified").toBoolean(),
                needsDetail = col("needs_detail").toBoolean(),
            )
        }
    }

    fun escape(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    /**
     * Splits a whole CSV document into records of fields, honouring quoted fields
     * that span newlines. Handles both "\n" and "\r\n" endings.
     */
    fun splitRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { fields.add(current.toString()); current.clear() }
                (c == '\n' || c == '\r') && !inQuotes -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    fields.add(current.toString()); current.clear()
                    records.add(fields); fields = mutableListOf()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        if (fields.size > 1 || fields[0].isNotEmpty()) records.add(fields)
        return records
    }

    /** Single-record convenience, used by tests and by header parsing. */
    fun splitLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { result.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }
}
