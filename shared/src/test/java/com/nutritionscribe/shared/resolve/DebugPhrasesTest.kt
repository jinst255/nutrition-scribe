package com.nutritionscribe.shared.resolve

import org.junit.Test

/** Not an assertion: details for phrases under investigation -> build/reports/resolver/debug.txt */
class DebugPhrasesTest {
    @Test
    fun debug() {
        val phrases = (System.getProperty("phrases") ?: "").split("|").filter { it.isNotBlank() }.ifEmpty { return }
        val r = Resolver(InMemoryPersonalStore(), TestDbs.usda, TestDbs.labels)
        val out = StringBuilder()
        for (p in phrases) {
            out.append("\n>> $p\n")
            val items = ItemParser.parse(p, { w -> TestDbs.usda.knowsWord(w) || TestDbs.labels?.knowsWord(w) == true }) { w -> TestDbs.labels?.isBrandWord(w) == true }
            for (i in items) out.append("   item: food='${i.food}' amt=${i.amount} unit=${i.unit} size=${i.size} brand=${i.brand} sp=${i.statedProteinG} top=${i.topping} group=${i.group}\n")
            for (ri in r.resolve(p).items) {
                out.append("   -> [${ri.status}${if (ri.weak) " weak" else ""}] ${ri.choice?.origin} ${ri.choice?.brand} | ${ri.choice?.name} | ${ri.grams?.toInt()} g (${ri.portionNote}) kcal ${ri.nutrients["calories"]?.toInt()} P ${ri.nutrients["protein"]}\n")
                for (a in ri.alternates.take(4)) out.append("        alt ${a.origin} ${a.brand} | ${a.name} | ${a.labelServingText}\n")
            }
            TestDbs.labels?.let { l ->
                for (i in items) for (c in l.search(i.food, brand = i.brand, limit = 5)) {
                    out.append("        label? ${c.brand} | ${c.name} | ${c.labelServingText} | kcal/serv ${c.nutrientsFor(c.labelServingGrams)["calories"]?.toInt()}\n")
                }
            }
        }
        TestDbs.report("debug.txt", out.toString())
    }
}
