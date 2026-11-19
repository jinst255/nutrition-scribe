package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.LogQuery
import com.nutritionscribe.shared.model.LoggedItem
import com.nutritionscribe.shared.model.MyFoodsQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ask with a live key, on the owner's own questions from their notes (Oct 26 to
 * Nov 13), against a made-up three-week log. Skipped unless given an output file:
 *
 *   OPENAI_API_KEY=... ./gradlew :shared:testDebugUnitTest --rerun --tests '*AskLiveChecksTest*' -Dlive=<out.txt> [-DliveModel=gpt-6-luna]
 */
class AskLiveChecksTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val zone = ZoneId.of("America/Denver")
    private val today = LocalDate.of(2026, 11, 14)

    private fun item(name: String, kcal: Double, protein: Double, sodium: Double) =
        LoggedItem(name = name, said = name, key = name, origin = "USDA",
            nutrients = mapOf("calories" to kcal, "protein" to protein, "sodium" to sodium))

    /** Three weeks of a made-up person's meals. */
    private val log: List<Entry> = (0 until 21).flatMap { d ->
        val day = today.minusDays(d.toLong())
        fun at(h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        fun e(h: Int, vararg items: LoggedItem) = Entry(id = "$d-$h", timestampEpochMillis = at(h), rawText = "", name = items.joinToString { it.name },
            source = "test", confidence = Confidence.ESTIMATED, items = items.toList(),
            nutrients = listOf("calories", "protein", "sodium").associateWith { k -> items.sumOf { it.nutrients[k] ?: 0.0 } })
        listOfNotNull(
            e(8, item("Cottage cheese, NFS", 180.0, 24.0, 700.0), item("Pineapple, raw", 40.0, 0.4, 1.0)),
            e(12, item("Great Value 100% Whole Wheat Bread", 220.0, 10.0, 340.0), item("Avocado", 120.0, 1.5, 5.0)),
            if (d % 3 == 0) e(18, item("Burrito, bean and cheese", 480.0, 18.0, 1100.0)) else e(18, item("Chicken breast, grilled", 280.0, 52.0, 120.0), item("Rice, white, cooked", 205.0, 4.0, 2.0)),
            if (d % 2 == 0) e(21, item("Great Value Real Bacon Pieces", 60.0, 6.0, 380.0)) else null,
        )
    }

    private val foods = listOf(
        PersonalFood(key = "bread", phrase = "two pieces of bread", name = "100% Whole Wheat Round Top Bread", timesUsed = 30, amount = 2.0, unit = "slice", grams = 50.0,
            candidate = FoodCandidate(id = "label:1", name = "100% Whole Wheat Round Top Bread", brand = "Great Value", origin = Origin.LABEL),
            nutrients = mapOf("calories" to 220.0, "protein" to 10.0, "carbs" to 40.0, "fat" to 3.0, "fiber" to 6.0, "sodium" to 340.0)),
        PersonalFood(key = "avocado", phrase = "half an avocado", name = "Avocado", timesUsed = 22, amount = 0.5, unit = null, grams = 75.0,
            nutrients = mapOf("calories" to 120.0, "protein" to 1.5, "carbs" to 6.4, "fat" to 11.0, "fiber" to 5.0, "sodium" to 5.0)),
        PersonalFood(key = "bacon", phrase = "bacon bits", name = "Real Bacon Pieces", timesUsed = 11, amount = 1.0, unit = "tbsp", grams = 7.0,
            candidate = FoodCandidate(id = "label:2", name = "Real Bacon Pieces", brand = "Great Value", origin = Origin.LABEL),
            nutrients = mapOf("calories" to 30.0, "protein" to 3.0, "sodium" to 190.0)),
        PersonalFood(key = "milk", phrase = "glass of milk", name = "Vitamin D Whole Milk", timesUsed = 15, amount = 1.0, unit = "cup", grams = 244.0,
            candidate = FoodCandidate(id = "label:3", name = "Vitamin D Whole Milk", brand = "Great Value", origin = Origin.LABEL),
            nutrients = mapOf("calories" to 150.0, "protein" to 8.0, "sodium" to 125.0)),
        PersonalFood(key = "cottage", phrase = "cottage cheese", name = "Cottage cheese", timesUsed = 40, amount = 1.0, unit = "cup", grams = 226.0,
            nutrients = mapOf("calories" to 180.0, "protein" to 24.0, "sodium" to 700.0)),
        PersonalFood(key = "tuna", phrase = "can of tuna", name = "Tuna, canned light", timesUsed = 6, amount = 1.0, unit = "can", grams = 142.0,
            nutrients = mapOf("calories" to 120.0, "protein" to 26.0, "sodium" to 360.0)),
    )

    private val context = """
        Now: Saturday, 2026-11-14, 1:10 PM.
        Logged today:
        - 8:00 AM Cottage cheese, Pineapple (220 kcal)
        - 12:00 PM Great Value Whole Wheat Bread, Avocado (340 kcal)
        Today vs their targets (goal = they chose it):
        - Calories: 560 of at most 2400 kcal (goal)
        - Protein: 36 of 140 g (goal)
        - Sodium: 1045 of at most 2300 mg
        - Fiber: 11 of 34 g
        Foods they often log (use these when you can):
        Cottage cheese; Whole wheat bread; Avocado; Chicken breast; White rice; Bean and cheese burrito; Bacon pieces; Tuna, canned light; Whole milk
    """.trimIndent()

    private class Q(val text: String, val check: (AskClient.Reply, List<String>) -> String?)

    private val questions = listOf(
        Q("What are the biggest contributing factors to my sodium intake, across all the food I've ever logged?") { r, tools ->
            when {
                tools.none { it.startsWith("query_log") } -> "never looked at the log"
                !r.text.contains("cottage", true) && !r.text.contains("burrito", true) -> "didn't name the top sources"
                r.text.contains("can't see", true) -> "said it can't see the log"
                else -> null
            }
        },
        Q("My plasma protein was 0.58, the minimum is 0.6. How much protein did I eat the past four days?") { r, tools ->
            if (tools.none { it.startsWith("query_log") }) "never looked at the log" else if (!Regex("\\d").containsMatchIn(r.text)) "no numbers" else null
        },
        Q("Give me a complete list of all the Great Value foods I eat.") { r, tools ->
            when {
                tools.none { it.startsWith("find_my_foods") } -> "never searched their foods"
                !(r.text.contains("bacon", true) && r.text.contains("milk", true) && r.text.contains("bread", true)) -> "missed one of the three"
                else -> null
            }
        },
        Q("Let's say I ate my typical bread with my typical avocado. What would my daily nutrients look like? Just four or five of them.") { r, tools ->
            if (tools.none { it.startsWith("find_my_foods") }) "never looked up their bread" else if (!Regex("\\d").containsMatchIn(r.text)) "no numbers" else null
        },
        Q("I have the opportunity to eat some free waffles for dinner. What should I eat?") { r, _ ->
            val bad = r.ideas.filter { i -> i.name.contains("waffle", true) && (i.name.contains("tuna", true) || i.parts.any { it.name.contains("tuna", true) || it.name.contains("spinach", true) }) }
            if (bad.isNotEmpty()) "paired waffles with ${bad.map { it.name }}" else null
        },
        Q("Something quick for lunch with pepper?") { r, _ ->
            if (r.ideas.isEmpty() && r.text.contains("bell", true)) null else "didn't ask which pepper (${r.ideas.size} ideas)"
        },
        Q("A snack with 20 g protein") { r, _ ->
            when {
                r.ideas.size < 2 -> "only ${r.ideas.size} idea"
                r.ideas.any { it.name.contains("burrito", true) || it.name.contains("chicken breast", true) && (it.nutrients["calories"] ?: 0.0) > 400 } -> "a meal as a snack"
                r.ideas.any { (it.nutrients["protein"] ?: 0.0) < 15 } -> "an idea under 15 g protein: ${r.ideas.map { it.name to it.nutrients["protein"] }}"
                else -> null
            }
        },
    )

    @Test
    fun run() {
        val out = System.getProperty("live")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val key = System.getenv("OPENAI_API_KEY").orEmpty().ifBlank { return }
        val model = System.getProperty("liveModel")?.takeIf { it.isNotBlank() } ?: AskClient.OPENAI_DEFAULT
        val report = StringBuilder("Model: $model\n\n")
        var tokensIn = 0L; var tokensCached = 0L; var tokensOut = 0L; var passed = 0
        for ((n, q) in questions.withIndex()) {
            if (System.getProperty("liveOnly")?.isNotBlank() == true && (n + 1).toString() !in System.getProperty("liveOnly")!!.split(",")) continue
            val used = mutableListOf<String>()
            var calls = 0
            val transport = OpenAiItemLookup.Transport { url, headers, body ->
                calls++
                val (status, response) = OpenAiItemLookup.HttpTransport.post(url, headers, body)
                if (status !in 200..299) used += "HTTP $status: ${response.take(400)}"
                val u = runCatching { json.parseToJsonElement(response).jsonObject["usage"] as? JsonObject }.getOrNull()
                tokensIn += (u?.get("prompt_tokens") ?: u?.get("input_tokens"))?.jsonPrimitive?.longOrNull ?: 0
                tokensCached += ((u?.get("prompt_tokens_details") ?: u?.get("input_tokens_details")) as? JsonObject)?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0
                tokensOut += (u?.get("completion_tokens") ?: u?.get("output_tokens"))?.jsonPrimitive?.longOrNull ?: 0
                status to response
            }
            val tools = object : AskClient.Tools {
                override fun queryLog(request: LogQuery.Request) = LogQuery.run(log, request, zone, today).also {
                    used += "query_log(${request.metric}, ${request.from}..${request.to}, ${request.groupBy}, top ${request.top}${if (request.lowest) ", lowest" else ""})"
                }
                override fun findMyFoods(query: String) = MyFoodsQuery.run(foods, query).also { used += "find_my_foods(\"$query\")" }
            }
            val t0 = System.currentTimeMillis()
            val r = runCatching { AskClient(key, AskClient.Provider.OPENAI, model, transport).ask(context, listOf(AskClient.Turn("user", q.text)), tools) }
            val ms = System.currentTimeMillis() - t0
            val verdict = r.fold({ q.check(it, used) }, { "threw ${it.message}" })
            if (verdict == null) passed++
            report.append("${n + 1}. ${if (verdict == null) "PASS" else "FAIL: $verdict"} · ${ms} ms · $calls calls\n   Q: ${q.text}\n")
            used.forEach { report.append("   tool: $it\n") }
            r.getOrNull()?.let { a ->
                report.append("   A: ${a.text}\n")
                a.ideas.forEach { i -> report.append("   idea: ${i.name} · ${i.nutrients["calories"]?.toInt()} kcal · ${i.nutrients["protein"]?.toInt()} g protein\n") }
            }
            report.append("\n")
        }
        val cost = (tokensIn - tokensCached) * 0.10e-6 + tokensCached * 0.01e-6 + tokensOut * 0.50e-6
        report.append("$passed of ${System.getProperty("liveOnly")?.takeIf { it.isNotBlank() }?.split(",")?.size ?: questions.size} passed · in $tokensIn (cached $tokensCached) out $tokensOut · about \$${"%.4f".format(cost)} at GPT-6 Luna rates\n")
        out.writeText(report.toString())
    }
}
