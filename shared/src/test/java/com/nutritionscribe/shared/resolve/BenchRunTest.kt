package com.nutritionscribe.shared.resolve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import java.io.File

/**
 * Runs a benchmark set through the real resolver, AI included, and writes what it
 * logged. Not an assertion: the grading happens outside, against researched truth.
 *
 *   OPENAI_API_KEY=... ./gradlew :shared:testDebugUnitTest --tests '*BenchRunTest*' \
 *     -Dbench=<prompts.jsonl> -DbenchOut=<results.jsonl> -DlabelPack=<labels.db> \
 *     -DpersonalFoods=<my_foods.jsonl>
 *
 * Each line of the set is {"id", "text", "store": "owner"|"empty"}. "owner" cases
 * start from the saved foods in -DpersonalFoods, "empty" ones from nothing, and no
 * case learns from another. The set and the results hold real food logs: they live
 * outside the repo, and this test only ever reads and writes the paths it is given.
 */
class BenchRunTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun run() {
        val set = System.getProperty("bench")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() } ?: return
        val out = File(System.getProperty("benchOut")?.takeIf { it.isNotBlank() } ?: return)
        val key = System.getenv("OPENAI_API_KEY").orEmpty()
        val model = System.getProperty("benchModel")?.takeIf { it.isNotBlank() } ?: OpenAiItemLookup.DEFAULT_MODEL
        val only = System.getProperty("benchOnly")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        val ownerFoods = System.getProperty("personalFoods")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
            ?.readLines()?.mapNotNull { line -> runCatching { json.decodeFromString(PersonalFood.serializer(), line) }.getOrNull() }
            .orEmpty()
        // Already answered, so a rerun after a crash or a key expiry carries on.
        val done = if (out.exists()) out.readLines().mapNotNull { runCatching { json.parseToJsonElement(it).jsonObject["id"]?.jsonPrimitive?.content }.getOrNull() }.toSet() else emptySet()

        val cases = set.readLines().filter { it.isNotBlank() }.map { json.parseToJsonElement(it).jsonObject }
        for (case in cases) {
            val id = case["id"]!!.jsonPrimitive.content
            if (id in done || (only.isNotEmpty() && id !in only)) continue
            val text = case["text"]!!.jsonPrimitive.content
            val store = InMemoryPersonalStore().apply {
                if (case["store"]?.jsonPrimitive?.content == "owner") ownerFoods.forEach { put(it) }
            }
            val calls = mutableListOf<JsonObject>()
            val transport = OpenAiItemLookup.Transport { url, headers, body ->
                val t0 = System.currentTimeMillis()
                val (status, response) = OpenAiItemLookup.HttpTransport.post(url, headers, body)
                val usage = runCatching { json.parseToJsonElement(response).jsonObject["usage"]?.jsonObject }.getOrNull()
                calls += buildJsonObject {
                    put("status", status)
                    put("ms", System.currentTimeMillis() - t0)
                    put("in", usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0)
                    put("cached", usage?.get("prompt_tokens_details")?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0)
                    put("out", usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0)
                    // What the model said, to tell its mistakes from the app's.
                    runCatching {
                        json.parseToJsonElement(response).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!
                            .jsonObject["content"]!!.jsonPrimitive.content
                    }.getOrNull()?.let { put("answer", it) }
                }
                status to response
            }
            val ai = if (key.isNotBlank()) OpenAiItemLookup(key, model, transport) else null
            val t0 = System.currentTimeMillis()
            val r = runCatching { Resolver(store, TestDbs.usda, TestDbs.labels, ai).resolve(text) }
            val ms = System.currentTimeMillis() - t0
            val line = buildJsonObject {
                put("id", id)
                put("model", if (ai == null) "offline" else model)
                put("ms", ms)
                putJsonArray("calls") { calls.forEach { add(it) } }
                r.exceptionOrNull()?.let { put("crash", it.toString()) }
                r.getOrNull()?.let { res ->
                    put("used_ai", res.usedAi)
                    res.aiProblem?.let { put("ai_problem", it) }
                    res.meal?.let { put("meal", it.name) }
                    putJsonObject("total") { res.total.forEach { (k, v) -> put(k, v) } }
                    putJsonArray("items") {
                        res.items.forEach { i ->
                            add(buildJsonObject {
                                put("said", i.item.said)
                                put("name", i.displayName)
                                put("origin", i.choice?.origin?.name ?: "NONE")
                                put("status", i.status.name)
                                put("weak", i.weak)
                                i.grams?.let { put("grams", it) }
                                i.portionNote?.let { put("portion", it) }
                                putJsonObject("nutrients") { i.nutrients.forEach { (k, v) -> put(k, v) } }
                            })
                        }
                    }
                }
            }
            out.appendText(line.toString() + "\n")
        }
    }
}
