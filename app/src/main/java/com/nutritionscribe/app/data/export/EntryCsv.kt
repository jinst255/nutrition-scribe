package com.nutritionscribe.app.data.export

import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.nutrientsOf
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

    /**
     * Micronutrients go on the END, named with their unit, so every export made
     * before them still imports (columns are read by header) and a spreadsheet
     * never has to guess whether a number is mg or µg. Blank means not known.
     */
    val MICRO_COLUMNS: List<Pair<String, Metric>> = Metric.MICROS.map { m ->
        "${m.key}_${m.unit.replace("µ", "u")}" to m
    }

    val HEADER = "id,timestamp,local_datetime,time_unspecified,needs_detail,raw_text,name," +
        "protein_grams,calories,carbs_grams,fat_grams,fiber_grams,sugar_grams," +
        "saturated_fat_grams,sodium_mg,caffeine_mg,is_fixed_food,source,confidence," +
        MICRO_COLUMNS.joinToString(",") { it.first }


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
                text(e.id),
                e.timestampEpochMillis.toString(),
                localDateTime,
                e.timeUnspecified.toString(),
                e.needsDetail.toString(),
                text(e.rawText),
                text(e.name),
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
                text(e.source),
                e.confidence.name,
                *MICRO_COLUMNS.map { (_, m) -> e.amountOf(m)?.toString().orEmpty() }.toTypedArray(),
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
            fun textCol(name: String): String? = col(name)?.let(::unguard)

            val id = textCol("id").orEmpty()
            if (id.isBlank()) return@mapNotNull null
            val ts = col("timestamp")?.toLongOrNull() ?: return@mapNotNull null

            Entry(
                id = id,
                timestampEpochMillis = ts,
                rawText = textCol("raw_text").orEmpty(),
                name = textCol("name").orEmpty(),
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
                ) + MICRO_COLUMNS.mapNotNull { (name, m) -> col(name)?.toDoubleOrNull()?.let { m.key to it } },
                isFixedFood = col("is_fixed_food").toBoolean(),
                source = textCol("source").orEmpty(),
                confidence = runCatching { Confidence.valueOf(col("confidence").orEmpty()) }
                    .getOrDefault(Confidence.ESTIMATED),
                timeUnspecified = col("time_unspecified").toBoolean(),
                needsDetail = col("needs_detail").toBoolean(),
            )
        }
    }

    /**
     * Characters that make a spreadsheet read a cell as a formula. A food name is
     * free text (typed, spoken, from the AI or from Open Food Facts), so a name like
     * `=HYPERLINK("https://…?"&A2,"x")` would run when the export is opened in Excel
     * or Sheets and could send the log somewhere. See OWASP "CSV Injection".
     */
    private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')

    /**
     * A text cell made safe for spreadsheets, then CSV-escaped. A cell that could
     * start a formula gets a leading `'`, which spreadsheets show as plain text.
     * A cell that already starts with `'` gets one too, so [unguard] can take
     * exactly one off and every value round-trips unchanged.
     */
    fun text(value: String): String = escape(guard(value))

    fun guard(value: String): String {
        val first = value.firstOrNull() ?: return value
        return if (first in FORMULA_START || first == '\'') "'$value" else value
    }

    /**
     * Undoes [guard] on import. It only strips a `'` that [guard] could have
     * added (one followed by a formula character or another `'`), so a name in an
     * export made before this fix that happens to start with `'` keeps it.
     */
    fun unguard(value: String): String =
        if (value.length >= 2 && value[0] == '\'' && (value[1] in FORMULA_START || value[1] == '\'')) value.substring(1)
        else value

    fun escape(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
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
