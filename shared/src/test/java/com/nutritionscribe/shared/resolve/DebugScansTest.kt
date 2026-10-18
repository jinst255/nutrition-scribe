package com.nutritionscribe.shared.resolve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Not an assertion: every label photo in a debug export's events.jsonl
 * (-DscanEvents=…), read again by today's parser -> build/reports/resolver/scans.txt.
 * The export is someone's log: it is read where it lies and never copied here.
 */
class DebugScansTest {
    @Test
    fun scans() {
        val path = System.getProperty("scanEvents")?.takeIf { it.isNotBlank() } ?: return
        val json = Json { ignoreUnknownKeys = true }
        val out = StringBuilder()
        for (line in java.io.File(path).readLines()) {
            if (!line.contains("\"scan_ocr\"")) continue
            val e = runCatching { json.parseToJsonElement(line) as JsonObject }.getOrNull() ?: continue
            val lines = (e["lines"] as? JsonArray)?.mapNotNull { l ->
                val o = l as? JsonObject ?: return@mapNotNull null
                val text = o["text"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val box = o["box"]?.jsonPrimitive?.content?.split(" ")?.mapNotNull { it.toIntOrNull() }
                if (box == null || box.size != 4) null else NutritionFactsParser.OcrLine(text, box[0], box[1], box[2], box[3])
            }.orEmpty()
            val p = NutritionFactsParser.parse(lines)
            out.append("\n== ${e["t"]?.jsonPrimitive?.content} serving='${p.servingText}' g=${p.servingGrams} issues=${p.issues.map { it.code }}\n")
            out.append("   ${p.perServing.entries.joinToString(", ") { "${it.key}=${"%.1f".format(it.value)}" }}\n")
            for (r in p.reads.filter { it.fix != null }) out.append("   fix ${r.metric}: ${r.fix} raw='${r.raw}' -> ${r.value}\n")
        }
        TestDbs.report("scans.txt", out.toString())
    }
}
