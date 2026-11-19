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
    /** OpenAI answers stream through this when set, so the reply shows as it is written. */
    private val stream: AskStream.StreamTransport? = null,
    /**
     * OpenAI may search the web (Settings › AI › Search the web). Owner, 11-16: Ask
     * couldn't look up a burger at a campus food court; "it would make sense
     * to have a similar feature in chat".
     */
    private val webSearch: Boolean = false,
) {
    enum class Provider { OPENAI, ANTHROPIC }

    /** What Ask may look at on the phone. Only each answer's summary is sent. */
    interface Tools {
        fun queryLog(request: com.nutritionscribe.shared.model.LogQuery.Request): String
        fun findMyFoods(query: String): String
    }

    data class Turn(val role: String, val text: String)

    data class Part(val name: String, val grams: Double)

    data class Idea(val name: String, val nutrients: Map<String, Double>, val parts: List<Part>)

    data class Plan(val ideaName: String, val meal: String, val hour: Int?, val minute: Int?)

    data class Reply(val text: String, val title: String, val ideas: List<Idea>, val plan: Plan?)

    /**
     * One answer. [queryLog] runs on the phone when the model asks to look at the
     * whole log (see [com.nutritionscribe.shared.model.LogQuery]); it may ask up to
     * [MAX_TOOL_ROUNDS] times before it answers. Null: today's numbers only.
     */
    fun ask(context: String, turns: List<Turn>, queryLog: Tools? = null, onProgress: ((AskStream.Progress) -> Unit)? = null): Reply {
        if (apiKey.isBlank()) throw OpenAiItemLookup.Failure.Auth()
        val searching = webSearch && provider == Provider.OPENAI
        val system = SYSTEM + (if (queryLog != null) "\n" + TOOL_RULES else "") + (if (searching) "\n" + WEB_RULES else "") + "\n\n" + context
        val sent = conversation(turns)
        return when (provider) {
            Provider.OPENAI -> askOpenAi(system, sent, queryLog, searching, onProgress)
            Provider.ANTHROPIC -> { onProgress?.invoke(AskStream.Progress.Status("Thinking…")); askAnthropic(system, sent, queryLog) }
        }
    }

    /** One /v1/responses call: streamed when a stream transport is set. */
    private fun respond(headers: Map<String, String>, body: String, onProgress: ((AskStream.Progress) -> Unit)?): String {
        val s = stream ?: return post(RESPONSES_URL, headers, body)
        val streamed = body.dropLast(1) + ",\"stream\":true}"
        val answer = StringBuilder()
        val (status, text) = try {
            s.stream(RESPONSES_URL, headers, streamed) { data -> AskStream.progressOf(data, answer)?.let { p -> onProgress?.invoke(p) } }
        } catch (e: IOException) {
            throw OpenAiItemLookup.Failure.NoNetwork()
        }
        if (status == 401 || status == 403) throw OpenAiItemLookup.Failure.Auth()
        if (status !in 200..299) throw OpenAiItemLookup.Failure.Api(status)
        return text
    }

    /** "Something's wrong with this entry": what to change, from what they said (see [EntryFix]). */
    fun fixEntry(said: String, lines: List<EntryFix.Line>, complaint: String): EntryFix.Fix {
        if (apiKey.isBlank()) throw OpenAiItemLookup.Failure.Auth()
        val prompt = EntryFix.prompt(said, lines, complaint)
        val text = when (provider) {
            Provider.OPENAI -> post(RESPONSES_URL, mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json"),
                EntryFix.openAiBody(model, prompt))
            Provider.ANTHROPIC -> post("https://api.anthropic.com/v1/messages",
                mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01", "Content-Type" to "application/json"),
                EntryFix.anthropicBody(model, prompt))
        }
        val answer = EntryFix.answerOf(text, provider) ?: throw OpenAiItemLookup.Failure.Malformed()
        return EntryFix.parse(answer, lines.size) ?: throw OpenAiItemLookup.Failure.Malformed()
    }

    private fun post(url: String, headers: Map<String, String>, body: String): String {
        val (status, text) = try {
            transport.post(url, headers, body)
        } catch (e: IOException) {
            throw OpenAiItemLookup.Failure.NoNetwork()
        }
        if (status == 401 || status == 403) throw OpenAiItemLookup.Failure.Auth()
        if (status !in 200..299) throw OpenAiItemLookup.Failure.Api(status)
        return text
    }

    /**
     * OpenAI through /v1/responses: GPT-6 Luna refuses function tools with
     * reasoning on chat completions (a 400 on every message once Ask had tools).
     * Stateless: nothing is stored at OpenAI, so each round sends back what came
     * before, its reasoning included in encrypted form.
     */
    private fun askOpenAi(system: String, turns: List<Turn>, tools: Tools?, searching: Boolean = false,
                          onProgress: ((AskStream.Progress) -> Unit)? = null): Reply {
        val input = mutableListOf<kotlinx.serialization.json.JsonElement>()
        turns.forEach { t -> input += buildJsonObject { put("role", t.role); put("content", t.text) } }
        val headers = mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json")
        repeat(MAX_TOOL_ROUNDS + 1) { round ->
            val withTools = tools != null && round < MAX_TOOL_ROUNDS
            val withSearch = searching && round < MAX_TOOL_ROUNDS
            val body = buildJsonObject {
                put("model", model)
                put("instructions", system)
                put("input", JsonArray(input))
                put("store", false)
                putJsonArray("include") { add("reasoning.encrypted_content") }
                putJsonObject("reasoning") { put("effort", "low") }
                put("max_output_tokens", 4000)
                putJsonObject("text") {
                    putJsonObject("format") { put("type", "json_schema"); put("name", "ask_reply"); put("strict", true); put("schema", SCHEMA) }
                }
                if (withTools || withSearch) putJsonArray("tools") {
                    if (withTools) add(buildJsonObject {
                        put("type", "function"); put("name", "query_log"); put("description", QUERY_DESCRIPTION); put("strict", true); put("parameters", QUERY_SCHEMA)
                    })
                    if (withTools) add(buildJsonObject {
                        put("type", "function"); put("name", "find_my_foods"); put("description", FOODS_DESCRIPTION); put("strict", true); put("parameters", FOODS_SCHEMA)
                    })
                    if (withSearch) add(buildJsonObject { put("type", "web_search"); put("search_context_size", "low") })
                }
            }.toString()
            val text = respond(headers, body, onProgress)
            val output = runCatching { Json.parseToJsonElement(text).jsonObject["output"]!!.jsonArray }.getOrNull()
                ?: throw OpenAiItemLookup.Failure.Malformed()
            val calls = output.filter { (it.jsonObject["type"] as? JsonPrimitive)?.content == "function_call" }
            if (calls.isEmpty() || tools == null) return parseResponses(text) ?: throw OpenAiItemLookup.Failure.Malformed()
            output.forEach { input += it }
            for (c in calls) {
                val co = c.jsonObject
                val args = runCatching { Json.parseToJsonElement(co["arguments"]!!.jsonPrimitive.content).jsonObject }.getOrNull()
                input += buildJsonObject {
                    put("type", "function_call_output"); put("call_id", (co["call_id"] as? JsonPrimitive)?.content.orEmpty())
                    put("output", runTool((co["name"] as? JsonPrimitive)?.content, args, tools))
                }
            }
        }
        throw OpenAiItemLookup.Failure.Malformed()
    }

    private fun askAnthropic(system: String, turns: List<Turn>, queryLog: (Tools)?): Reply {
        val messages = mutableListOf<kotlinx.serialization.json.JsonElement>()
        turns.forEach { t -> messages += buildJsonObject { put("role", t.role); put("content", t.text) } }
        val headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01", "Content-Type" to "application/json")
        repeat(MAX_TOOL_ROUNDS + 1) { round ->
            val tools = queryLog != null && round < MAX_TOOL_ROUNDS
            val body = buildJsonObject {
                put("model", model)
                put("max_tokens", 3000)
                putJsonObject("output_config") { put("effort", "low") }
                put("system", system)
                putJsonArray("tools") {
                    add(buildJsonObject { put("name", "ask_reply"); put("description", "Answer the person."); put("input_schema", SCHEMA) })
                    if (tools) add(buildJsonObject { put("name", "query_log"); put("description", QUERY_DESCRIPTION); put("input_schema", QUERY_SCHEMA) })
                    if (tools) add(buildJsonObject { put("name", "find_my_foods"); put("description", FOODS_DESCRIPTION); put("input_schema", FOODS_SCHEMA) })
                }
                // It must call a tool: the reply is itself one, so it is "any" while it may look first.
                putJsonObject("tool_choice") { if (tools) put("type", "any") else { put("type", "tool"); put("name", "ask_reply") } }
                put("messages", JsonArray(messages))
            }.toString()
            val text = post("https://api.anthropic.com/v1/messages", headers, body)
            val content = runCatching { Json.parseToJsonElement(text).jsonObject["content"]!!.jsonArray }.getOrNull()
                ?: throw OpenAiItemLookup.Failure.Malformed()
            fun named(n: String) = content.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" && it.jsonObject["name"]?.jsonPrimitive?.content == n }
            if (named("ask_reply").isNotEmpty() || queryLog == null) return parse(text, Provider.ANTHROPIC) ?: throw OpenAiItemLookup.Failure.Malformed()
            val queries = (named("query_log") + named("find_my_foods")).ifEmpty { throw OpenAiItemLookup.Failure.Malformed() }
            messages += buildJsonObject { put("role", "assistant"); put("content", content) }
            messages += buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    queries.forEach { q ->
                        add(buildJsonObject {
                            put("type", "tool_result"); put("tool_use_id", q.jsonObject["id"]!!.jsonPrimitive.content)
                            put("content", runTool(q.jsonObject["name"]?.jsonPrimitive?.content, q.jsonObject["input"] as? JsonObject, queryLog))
                        })
                    }
                }
            }
        }
        throw OpenAiItemLookup.Failure.Malformed()
    }

    private fun runTool(name: String?, args: JsonObject?, tools: Tools): String = when (name) {
        "find_my_foods" -> {
            val q = (args?.get("query") as? JsonPrimitive)?.content.orEmpty()
            runCatching { tools.findMyFoods(q) }.getOrElse { "Their foods couldn't be read just now." }
        }
        else -> {
            val req = args?.let { queryOf(it) }
            if (req == null) "That query couldn't be read. Give metric, from, to, group_by, top and lowest."
            else runCatching { tools.queryLog(req) }.getOrElse { "The log couldn't be read just now." }
        }
    }

    companion object {
        /**
         * The turns a provider accepts: user and assistant only, none blank, no two in
         * a row from the same side, starting with the user.
         *
         * A failed message is kept in the chat as an "error" line. It went to OpenAI as
         * a turn with role "error", which it refuses: after one dropped connection
         * (11-14), every message in that chat failed with API error 400. Sending the
         * question again also put it in twice in a row, which Anthropic refuses.
         */
        fun conversation(turns: List<Turn>): List<Turn> {
            val out = mutableListOf<Turn>()
            for (t in turns) {
                if (t.role != "user" && t.role != "assistant") continue
                if (t.text.isBlank()) continue
                val last = out.lastOrNull()
                when {
                    last == null && t.role == "assistant" -> continue
                    last?.role == t.role && t.role == "user" ->
                        out[out.lastIndex] = if (last.text.trim() == t.text.trim()) t else Turn("user", last.text + "\n\n" + t.text)
                    last?.role == t.role -> out[out.lastIndex] = Turn("assistant", last.text + "\n\n" + t.text)
                    else -> out += t
                }
            }
            return out
        }

        const val MAX_TOOL_ROUNDS = 4

        const val WEB_RULES = "You can search the web. Use it for a restaurant's menu item, a product or a fact you don't know " +
            "the numbers for (\"the double burger at a campus food court\"): the maker's or restaurant's own page first. " +
            "Say briefly where the numbers came from. Don't search for what their log or saved foods already answer."

        const val RESPONSES_URL = "https://api.openai.com/v1/responses"

        /** The final answer from a /v1/responses reply. */
        fun parseResponses(responseBody: String): Reply? {
            val text = runCatching {
                Json.parseToJsonElement(responseBody).jsonObject["output"]!!.jsonArray
                    .map { it.jsonObject }.lastOrNull { (it["type"] as? JsonPrimitive)?.content == "message" }
                    ?.get("content")?.jsonArray?.map { it.jsonObject }
                    ?.firstOrNull { (it["type"] as? JsonPrimitive)?.content == "output_text" }
                    ?.get("text")?.jsonPrimitive?.content
            }.getOrNull() ?: return null
            return parse(buildJsonObject {
                putJsonArray("choices") { add(buildJsonObject { putJsonObject("message") { put("content", text) } }) }
            }.toString(), Provider.OPENAI)
        }

        const val QUERY_DESCRIPTION = "Read the person's whole food log, worked out on their phone: one nutrient's totals over any span of days, " +
            "grouped by food (its biggest or smallest sources), day, week, month or meal time. Dates are YYYY-MM-DD; null from means since " +
            "the first entry, null to means today."

        const val FOODS_DESCRIPTION = "Search the person's saved foods (what they usually eat: scanned labels, usual amounts, saved meals and " +
            "recipes) by words in the name or brand, e.g. \"great value\" or \"bread\". An empty query lists their most used. Gives each " +
            "one's usual amount and its calories, protein, carbs, fat, fiber and sodium."

        val FOODS_SCHEMA: JsonObject by lazy { buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { add("query") }
            putJsonObject("properties") { putJsonObject("query") { put("type", "string") } }
        } }

        private val GROUPS = listOf("food", "day", "week", "month", "meal_time")

        val QUERY_SCHEMA: JsonObject by lazy { buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { listOf("metric", "from", "to", "group_by", "top", "lowest").forEach { add(it) } }
            putJsonObject("properties") {
                putJsonObject("metric") { put("type", "string"); put("enum", buildJsonArray { NUTRIENT_KEYS.forEach { add(it) } }) }
                putJsonObject("from") { put("type", buildJsonArray { add("string"); add("null") }) }
                putJsonObject("to") { put("type", buildJsonArray { add("string"); add("null") }) }
                putJsonObject("group_by") { put("type", "string"); put("enum", buildJsonArray { GROUPS.forEach { add(it) } }) }
                putJsonObject("top") { put("type", "integer") }
                putJsonObject("lowest") { put("type", "boolean") }
            }
        } }

        /** The tool's arguments as a request; null when they don't make one. */
        fun queryOf(o: JsonObject): com.nutritionscribe.shared.model.LogQuery.Request? = runCatching {
            fun date(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { java.time.LocalDate.parse(it.take(10)) }
            com.nutritionscribe.shared.model.LogQuery.Request(
                metric = (o["metric"] as JsonPrimitive).content,
                from = date("from"), to = date("to"),
                groupBy = com.nutritionscribe.shared.model.LogQuery.GroupBy.valueOf(((o["group_by"] as? JsonPrimitive)?.content ?: "food").uppercase()),
                top = (o["top"] as? JsonPrimitive)?.intOrNull ?: 10,
                lowest = (o["lowest"] as? JsonPrimitive)?.content == "true",
            )
        }.getOrNull()

        val TOOL_RULES = """
            find_my_foods searches the foods they have saved, with each one's usual amount and numbers: use it for "my typical X", a brand they buy, or what they could make with what they have. When an idea uses their saved foods, look them up and build its numbers from theirs. Their foods are a starting point, not the only choices: give 2 or 3 ideas, add ordinary foods anyone can get when theirs don't fit, and a snack is a snack (never a cut-down portion of a big restaurant meal).
            "What if I ate X" or "let's say I had X" means X on top of what is already logged today: add it to today's totals and give the new totals.
            query_log reads their whole log, every day they ever logged, worked out on the phone. Call it whenever the question is about anything beyond today: past days, a week, a month, trends, where a nutrient comes from, how often they eat something. Never say you can't see past logs. Quote its numbers; don't invent others.
        """.trimIndent()

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
            Each idea is one meal whose parts go together, the way people really eat them: never tuna with dessert waffles, never a savory part in a sweet snack.
            When a word could mean different foods ("pepper": black pepper or a bell pepper; "a shake": protein shake or milkshake) and the answer depends on it, ask which in "reply" and give no ideas yet.
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
