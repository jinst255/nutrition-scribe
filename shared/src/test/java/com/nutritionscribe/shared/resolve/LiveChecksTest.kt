package com.nutritionscribe.shared.resolve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Test
import java.io.File

/**
 * The checks in docs/MANUAL_CHECKS.md that need a live key, run through the real
 * resolver with the app's own settings (sentence mode, web search on when unsure).
 * Skipped unless given an output file and a key:
 *
 *   OPENAI_API_KEY=$(cat ~/.config/nutrition-scribe/openai-key) ./gradlew :shared:testDebugUnitTest --rerun \
 *     --tests '*LiveChecksTest*' -Dlive=<out.txt> [-DlabelPack=<labels.db>] [-DliveBudget=1.0]
 *
 * It stops before the running cost (from the API's own token counts and web
 * searches) passes the budget. The sentences are made up; no one's log is used.
 */
class LiveChecksTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class Check(val text: String, val web: Boolean = true, val pass: (Resolver.Resolution) -> String?)

    private fun kcal(r: Resolver.Resolution) = r.total["calories"] ?: 0.0
    private fun names(r: Resolver.Resolution) = r.items.map { it.displayName.lowercase() }

    private val checks = listOf(
        Check("a fruit salad that has some pineapple, grapes, apples and blackberries") { r ->
            if (kcal(r) in 120.0..320.0) null else "total ${kcal(r).toInt()} kcal, want 120–320"
        },
        Check("a sandwich bag of baby carrots, celery sticks, snap peas and broccoli") { r ->
            when {
                names(r).any { "sandwich" in it } -> "made a sandwich line"
                r.items.size < 4 -> "only ${r.items.size} lines"
                kcal(r) > 200 -> "${kcal(r).toInt()} kcal for a bag of vegetables"
                else -> null
            }
        },
        Check("Alfredo Florentine with 2 ricotta stuffed shells") { r ->
            if (names(r).any { "alfredo" in it || "sauce" in it || "florentine" in it }) null else "no sauce line: ${names(r)}"
        },
        Check("1/3 of a Digiorno 4 cheese rising crust pizza") { r ->
            when {
                r.items.size != 1 -> "${r.items.size} lines: ${names(r)}"
                kcal(r) !in 350.0..800.0 -> "${kcal(r).toInt()} kcal for a third of a pizza"
                else -> null
            }
        },
        Check("cottage cheese with pineapple") { r ->
            val p = r.items.firstOrNull { "pineapple" in it.displayName.lowercase() }
            if (p == null) "no pineapple line" else if ((p.grams ?: 0.0) > 300) "pineapple ${p.grams?.toInt()} g" else null
        },
        Check("a bowl of oats with brown sugar") { r -> if (kcal(r) in 150.0..450.0) null else "${kcal(r).toInt()} kcal" },
        Check("a protein powder serving of whole milk") { r ->
            if (r.items.size >= 2) null else "one line: ${names(r)}"
        },
        Check("1½ cups of white rice") { r -> if (kcal(r) in 250.0..450.0) null else "${kcal(r).toInt()} kcal" },
        Check("pancakes with syrup") { r -> if (kcal(r) in 200.0..900.0) null else "${kcal(r).toInt()} kcal" },
        Check("a Hilltop double burger from the campus food court") { r ->
            when {
                kcal(r) <= 300 -> "${kcal(r).toInt()} kcal"
                r.items.any { Regex("estimat|source|found|\\(").containsMatchIn(it.item.food + " " + it.displayName.lowercase()) } -> "notes in the name: ${names(r)}"
                else -> null
            }
        },
        Check("a Hilltop double burger from the campus food court", web = false) { r -> if (kcal(r) > 300) null else "${kcal(r).toInt()} kcal" },
        Check("a Chick-fil-A spicy deluxe sandwich and medium waffle fries") { r -> if (kcal(r) in 800.0..1300.0) null else "${kcal(r).toInt()} kcal" },
        Check("2.5 brownies from the campus food court") { r ->
            when {
                kcal(r) !in 300.0..1200.0 -> "${kcal(r).toInt()} kcal"
                r.items.any { Regex("estimat|credible|found").containsMatchIn(it.displayName.lowercase()) } -> "notes in the name: ${names(r)}"
                else -> null
            }
        },
        Check("a Costco chicken bake") { r -> if (kcal(r) in 600.0..1000.0) null else "${kcal(r).toInt()} kcal" },
        Check("a ziploc bag of grapes") { r -> if (r.items.size == 1 && kcal(r) in 40.0..200.0) null else "${names(r)} ${kcal(r).toInt()} kcal" },
        Check("half a zucchini") { r -> if (kcal(r) in 5.0..40.0) null else "${kcal(r).toInt()} kcal" },
    )

    @Test
    fun run() {
        val out = System.getProperty("live")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val key = System.getenv("OPENAI_API_KEY").orEmpty().ifBlank { return }
        val budget = System.getProperty("liveBudget")?.toDoubleOrNull() ?: 1.0
        // "2,10,13": only those checks, by number.
        val only = System.getProperty("liveOnly")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet().orEmpty()
        val labels = runCatching { TestDbs.labels }.getOrNull()
        var spent = 0.0
        val report = StringBuilder()
        var passed = 0
        for ((n, c) in checks.withIndex()) {
            if (only.isNotEmpty() && (n + 1) !in only) continue
            if (spent > budget * 0.9) { report.append("STOPPED: \$${"%.4f".format(spent)} spent, budget \$$budget\n"); break }
            val calls = mutableListOf<String>()
            var caseCost = 0.0
            val transport = OpenAiItemLookup.Transport { url, headers, body ->
                val t0 = System.currentTimeMillis()
                val (status, response) = OpenAiItemLookup.HttpTransport.post(url, headers, body)
                val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull()
                val u = root?.get("usage") as? JsonObject
                fun num(o: JsonObject?, k: String) = (o?.get(k))?.jsonPrimitive?.longOrNull ?: 0L
                val input = num(u, "prompt_tokens") + num(u, "input_tokens")
                val cached = num(u?.get("prompt_tokens_details") as? JsonObject, "cached_tokens") +
                    num(u?.get("input_tokens_details") as? JsonObject, "cached_tokens")
                val output = num(u, "completion_tokens") + num(u, "output_tokens")
                val searches = (root?.get("output"))?.let { runCatching { it.jsonArray.count { o -> o.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "web_search_call" } }.getOrNull() } ?: 0
                // GPT-6 Luna: $0.10 in, $0.01 cached, $0.50 out per million; a web search $0.01.
                val cost = (input - cached) * 0.10e-6 + cached * 0.01e-6 + output * 0.50e-6 + searches * 0.01
                caseCost += cost
                val effort = Regex("\"reasoning_effort\"\\s*:\\s*\"(\\w+)\"").find(body)?.groupValues?.get(1)
                calls += "${url.substringAfterLast('/')} $status ${System.currentTimeMillis() - t0}ms in=$input cached=$cached out=$output" +
                    (if (searches > 0) " searches=$searches" else "") + (effort?.let { " effort=$it" } ?: "") + " \$${"%.4f".format(cost)}"
                status to response
            }
            val ai = OpenAiItemLookup(key, OpenAiItemLookup.DEFAULT_MODEL, transport, webSearch = c.web)
            val t0 = System.currentTimeMillis()
            val r = runCatching { Resolver(InMemoryPersonalStore(), TestDbs.usda, labels, ai).resolve(c.text) }
            spent += caseCost
            val verdict = r.fold({ c.pass(it) }, { "threw ${it.message}" })
            if (verdict == null) passed++
            report.append("${n + 1}. ${if (verdict == null) "PASS" else "FAIL: $verdict"} · ${c.text}${if (!c.web) " [web off]" else ""}" +
                " · ${System.currentTimeMillis() - t0} ms · \$${"%.4f".format(caseCost)}\n")
            r.getOrNull()?.let { res ->
                if (res.aiProblem != null) report.append("   ai problem: ${res.aiProblem}\n")
                res.items.forEach { i ->
                    report.append("   - ${i.displayName} · ${i.grams?.toInt()} g · ${i.nutrients["calories"]?.toInt()} kcal · ${i.choice?.origin} ${i.choice?.id?.substringBefore(':')} · ${i.portionNote ?: ""}\n")
                }
            }
            calls.forEach { report.append("   call: $it\n") }
        }
        val ran = if (only.isEmpty()) checks.size else only.size
        report.append("\n$passed of $ran passed · spent \$${"%.4f".format(spent)}\n")
        out.writeText(report.toString())
    }
}
