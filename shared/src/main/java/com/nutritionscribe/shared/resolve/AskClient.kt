package com.nutritionscribe.shared.resolve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/**
 * Ask, the chat on Home: one call per message, over the user's own key, with the
 * whole conversation and today's numbers each time. The answer is structured so
 * the app can draw it: short words, a few ideas with their numbers, and a plan
 * when the person has picked one. A plan is only ever a plan; nothing here logs.
 *
 * Plain HTTP through [OpenAiItemLookup.Transport], so it is metered like lookups
 * and runs in JVM tests.
 */
class AskClient(
    private val apiKey: String,
    private val provider: Provider,
    private val model: String,
    private val transport: OpenAiItemLookup.Transport = OpenAiItemLookup.HttpTransport,
) {
    enum class Provider { OPENAI, ANTHROPIC }

    data class Turn(val role: String, val text: String)

    data class Part(val name: String, val grams: Double)

    data class Idea(val name: String, val nutrients: Map<String, Double>, val parts: List<Part>)

    data class Plan(val ideaName: String, val meal: String, val hour: Int?, val minute: Int?)

    data class Reply(val text: String, val title: String, val ideas: List<Idea>, val plan: Plan?)

    fun ask(context: String, turns: List<Turn>): Reply {
        if (apiKey.isBlank()) throw OpenAiItemLookup.Failure.Auth()
        val system = SYSTEM + "\n\n" + context
        val (url, headers, body) = when (provider) {
            Provider.OPENAI -> Triple(
                "https://api.openai.com/v1/chat/completions",
                mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json"),
                buildJsonObject {
                    put("model", model)
                    put("reasoning_effort", "low")
                    put("max_completion_tokens", 4000)
                    putJsonObject("response_format") {
                        put("type", "json_schema")
                        putJsonObject("json_schema") { put("name", "ask_reply"); put("strict", true); put("schema", SCHEMA) }
                    }
                    putJsonArray("messages") {
                        add(buildJsonObject { put("role", "system"); put("content", system) })
                        turns.forEach { t -> add(buildJsonObject { put("role", t.role); put("content", t.text) }) }
                    }
                }.toString(),
            )
            Provider.ANTHROPIC -> Triple(
                "https://api.anthropic.com/v1/messages",
                mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01", "Content-Type" to "application/json"),
                buildJsonObject {
                    put("model", model)
                    put("max_tokens", 3000)
                    putJsonObject("output_config") { put("effort", "low") }
                    put("system", system)
                    putJsonArray("tools") {
                        add(buildJsonObject {
                            put("name", "ask_reply")
                            put("description", "Answer the person.")
                            put("input_schema", SCHEMA)
                        })
                    }
                    putJsonObject("tool_choice") { put("type", "tool"); put("name", "ask_reply") }
                    putJsonArray("messages") {
                        turns.forEach { t -> add(buildJsonObject { put("role", t.role); put("content", t.text) }) }
                    }
                }.toString(),
            )
        }
        val (status, text) = try {
            transport.post(url, headers, body)
        } catch (e: IOException) {
            throw OpenAiItemLookup.Failure.NoNetwork()
        }
        if (status == 401 || status == 403) throw OpenAiItemLookup.Failure.Auth()
        if (status !in 200..299) throw OpenAiItemLookup.Failure.Api(status)
        return parse(text, provider) ?: throw OpenAiItemLookup.Failure.Malformed()
    }

    companion object {
        const val OPENAI_DEFAULT = "gpt-6-luna"
        const val ANTHROPIC_DEFAULT = "claude-haiku-5-5"

        val NUTRIENT_KEYS = listOf(
            "calories", "protein", "carbs", "fat", "fiber", "sugar", "added_sugar", "saturated_fat", "sodium", "caffeine",
            "calcium", "iron", "potassium", "magnesium", "zinc", "selenium", "vitamin_a", "vitamin_c", "vitamin_d", "vitamin_e",
            "vitamin_k", "vitamin_b12", "folate", "omega3", "cholesterol",
        )

        val SYSTEM = """
            You are Ask, the helper inside Nutrition Scribe, a food-logging app. The person asks what to eat or about their day.
            Answer in plain, friendly words, at most 70 words, with no lists, headings or markdown in "reply": the app draws ideas itself from "ideas".
            When ideas fit, give 2 or 3 in "ideas", built from foods the person already eats (listed below) where you can, each with its parts in grams and its nutrients for the whole idea.
            In "reply", say briefly what they are short on today and why the ideas help; name ideas by number only if needed ("the first one").
            "nutrients" keys must come from: ${NUTRIENT_KEYS.joinToString(", ")}. Give calories, protein, carbs, fat, fiber, sodium and sugar for every idea, and any other key the person is short on.
            When the person picks an idea or says what they will eat, fill "plan" with that idea's exact name, the meal (breakfast, lunch, dinner or a snack), and the hour and minute they said (24-hour), or null hour and minute if they didn't say. Otherwise "plan" is null.
            Never say you logged anything: picking an idea only saves a plan, and the app asks after the meal what they actually had.
            "title" is 2 to 4 words naming the chat, like "Lunch ideas".
            Don't give medical advice; for a condition, allergy or medication question, say a doctor or dietitian is the one to ask.
        """.trimIndent()

        val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { add("reply"); add("title"); add("ideas"); add("plan") }
            putJsonObject("properties") {
                putJsonObject("reply") { put("type", "string") }
                putJsonObject("title") { put("type", "string") }
                putJsonObject("ideas") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        put("additionalProperties", false)
                        putJsonArray("required") { add("name"); add("parts"); add("nutrients") }
                        putJsonObject("properties") {
                            putJsonObject("name") { put("type", "string") }
                            putJsonObject("parts") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "object")
                                    put("additionalProperties", false)
                                    putJsonArray("required") { add("name"); add("grams") }
                                    putJsonObject("properties") {
                                        putJsonObject("name") { put("type", "string") }
                                        putJsonObject("grams") { put("type", "number") }
                                    }
                                }
                            }
                            putJsonObject("nutrients") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "object")
                                    put("additionalProperties", false)
                                    putJsonArray("required") { add("key"); add("amount") }
                                    putJsonObject("properties") {
                                        putJsonObject("key") { put("type", "string"); put("enum", buildJsonArray { NUTRIENT_KEYS.forEach { add(it) } }) }
                                        putJsonObject("amount") { put("type", "number") }
                                    }
                                }
                            }
                        }
                    }
                }
                putJsonObject("plan") {
                    put("type", buildJsonArray { add("object"); add("null") })
                    put("additionalProperties", false)
                    putJsonArray("required") { add("idea_name"); add("meal"); add("hour"); add("minute") }
                    putJsonObject("properties") {
                        putJsonObject("idea_name") { put("type", "string") }
                        putJsonObject("meal") { put("type", "string") }
                        putJsonObject("hour") { put("type", buildJsonArray { add("integer"); add("null") }) }
                        putJsonObject("minute") { put("type", buildJsonArray { add("integer"); add("null") }) }
                    }
                }
            }
        }

        fun parse(responseBody: String, provider: Provider): Reply? = runCatching {
            val root = Json.parseToJsonElement(responseBody).jsonObject
            val obj = when (provider) {
                Provider.OPENAI -> Json.parseToJsonElement(
                    root["choices"]!!.jsonArray.first().jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content,
                ).jsonObject
                Provider.ANTHROPIC -> root["content"]!!.jsonArray
                    .first { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" }.jsonObject["input"] as JsonObject
            }
            fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
            val ideas = (obj["ideas"] as? JsonArray).orEmpty().map { i ->
                val io = i.jsonObject
                Idea(
                    name = str(io, "name"),
                    nutrients = (io["nutrients"] as? JsonArray).orEmpty().mapNotNull { n ->
                        val no = n.jsonObject
                        val v = (no["amount"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                        str(no, "key").takeIf { it in NUTRIENT_KEYS }?.let { it to v }
                    }.toMap(),
                    parts = (io["parts"] as? JsonArray).orEmpty().mapNotNull { p ->
                        val po = p.jsonObject
                        val g = (po["grams"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                        Part(str(po, "name"), g)
                    },
                )
            }.filter { it.name.isNotBlank() }
            val plan = (obj["plan"] as? JsonObject)?.let { p ->
                Plan(str(p, "idea_name"), str(p, "meal").ifBlank { "meal" },
                    (p["hour"] as? JsonPrimitive)?.intOrNull, (p["minute"] as? JsonPrimitive)?.intOrNull)
            }?.takeIf { it.ideaName.isNotBlank() }
            Reply(str(obj, "reply").trim(), str(obj, "title").trim(), ideas, plan)
        }.getOrNull()
    }
}
