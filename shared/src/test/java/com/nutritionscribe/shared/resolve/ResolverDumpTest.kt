package com.nutritionscribe.shared.resolve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not an assertion: writes what the resolver makes of every real logged entry to
 * build/reports/resolver/dump.txt, for reading while tuning the parser.
 */
class ResolverDumpTest {
    @Test
    fun dump() {
        val export = File(System.getProperty("exportJson"))
        assumeTrue("no export on this machine", export.exists())
        val texts = Json.parseToJsonElement(export.readText()).jsonArray
            .map { it.jsonObject["rawText"]!!.jsonPrimitive.content }
        val resolver = Resolver(InMemoryPersonalStore(), TestDbs.usda, TestDbs.labels)
        val out = StringBuilder()
        for (t in texts) {
            out.append("\n>> ").append(t.replace("\n", " ")).append('\n')
            val r = resolver.resolve(t)
            for (i in r.items) {
                val kcal = i.nutrients["calories"]?.let { "%.0f".format(it) } ?: "-"
                val p = i.nutrients["protein"]?.let { "%.1f".format(it) } ?: "-"
                out.append(
                    "   [${i.status}${if (i.weak) " weak" else ""}${if (i.offerScan) " scan" else ""}${if (i.offerBreakdown) " split" else ""}] " +
                        "'${i.item.food}' amt=${i.item.amount} unit=${i.item.unit} size=${i.item.size} sp=${i.item.statedProteinG} brand=${i.item.brand} top=${i.item.topping}" +
                        "\n        -> ${i.choice?.name} | ${i.grams?.let { "%.0f g".format(it) }} (${i.portionNote}) | kcal $kcal P $p\n",
                )
            }
            val tot = r.total
            out.append("   TOTAL kcal ${"%.0f".format(tot["calories"] ?: 0.0)} P ${"%.1f".format(tot["protein"] ?: 0.0)}\n")
        }
        TestDbs.report("dump.txt", out.toString())
    }
}
