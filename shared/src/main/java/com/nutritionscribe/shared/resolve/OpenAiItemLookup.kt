package com.nutritionscribe.shared.resolve

import com.nutritionscribe.shared.model.Metric
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Layer 4 over the user's own OpenAI key: one itemised call.
 *
 * Measured before building (tools/ai_lookup.py, "ai_hybrid_fast"): asking for one
 * item per food, with the model's own numbers for each, as accurate as any of the
 * slower multi-call designs on generic food; on packaged food the resolver then
 * swaps in the real label wherever the label pack has it, because the model's
 * memory of labels runs salty. reasoning_effort "none" with temperature 0 and a
 * fixed seed — the only setting this model accepts those at — cut the wobble
 * between identical requests, and keeps a lookup around two seconds and a
 * fraction of a cent.
 *
 * Plain HttpURLConnection so the same class runs in the app and in JVM tests.
 */
class OpenAiItemLookup(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val transport: Transport = HttpTransport,
    /**
     * Look items up on the web when the model says it isn't sure of them (owner,
     * 2026-11-14: "I had the double burger from the campus food court" should find
     * the real menu item). Off, nothing is searched.
     */
    private val webSearch: Boolean = false,
    /** The web search goes through its own metered transport, so it is costed as a web search. */
    private val webTransport: Transport = transport,
) : AiItemLookup, SentenceLookup {

    fun interface Transport {
        /** POST [body] to [url]; returns (status, response body). Throws IOException offline. */
        fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String>
    }

    /** Why the call did not give answers, for the entry's error line. */
    sealed class Failure(message: String) : Exception(message) {
        class NoNetwork : Failure("No connection")
        class Auth : Failure("API key rejected")
        class Api(val status: Int) : Failure("API error $status")
        class Malformed : Failure("Unreadable answer")
    }

    override fun lookup(fullText: String, items: List<ItemParser.Item>): List<AiItemAnswer?> {
        if (apiKey.isBlank()) throw Failure.Auth()
        val user = userMessage(fullText, items)
        val body = buildJsonObject {
            put("model", model)
            put("reasoning_effort", "none")
            put("temperature", 0)
            put("seed", 7)
            put("max_completion_tokens", 3000)
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "food_items")
                    put("strict", true)
                    put("schema", SCHEMA)
                }
            }
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", SYSTEM) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            }
        }.toString()

        val (status, text) = try {
            transport.post(
                "https://api.openai.com/v1/chat/completions",
                mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json"),
                body,
            )
        } catch (e: IOException) {
            throw Failure.NoNetwork()
        }
        if (status == 401 || status == 403) throw Failure.Auth()
        if (status !in 200..299) throw Failure.Api(status)
        val answers = parse(text, items.size) ?: throw Failure.Malformed()
        return withRecheck(fullText, withWeb(fullText, answers))
    }

    /**
     * The whole sentence, read by the model itself: its own items, amounts and
     * numbers. The shipped single-call prompt scored 97.5% on the blind answer key
     * this way; handing it the app's own split capped it at the parser's mistakes.
     * [known] are parts already remembered, so it leaves them out.
     */
    override fun lookupSentence(fullText: String, known: List<ItemParser.Item>): List<AiItemAnswer> {
        if (apiKey.isBlank()) throw Failure.Auth()
        val user = buildString {
            append(fullText.trim())
            if (known.isNotEmpty()) {
                append("\n\nAlready logged separately, so leave out: ").append(known.joinToString("; ") { it.said })
            }
        }
        val body = requestBody(SENTENCE_SYSTEM, user)
        val (status, text) = try {
            transport.post("https://api.openai.com/v1/chat/completions",
                mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json"), body)
        } catch (e: IOException) {
            throw Failure.NoNetwork()
        }
        if (status == 401 || status == 403) throw Failure.Auth()
        if (status !in 200..299) throw Failure.Api(status)
        val answers = parseAll(text) ?: throw Failure.Malformed()
        return withRecheck(fullText, withWeb(fullText, answers)).filterNotNull()
    }

    /**
     * The items the model flagged as not known for sure (a restaurant's menu item,
     * a brand it doesn't know), searched on the web in one call and swapped in
     * where the search gave numbers. Everything else is untouched, and a search
     * that fails leaves the model's own answer: it never costs the entry its log.
     */
    fun withWeb(fullText: String, answers: List<AiItemAnswer?>): List<AiItemAnswer?> {
        if (!webSearch) return answers
        val unsure = answers.withIndex().filter { (_, a) -> a != null && a.lookUpOnline && !a.needsDetail }
        if (unsure.isEmpty()) return answers
        val found = runCatching { searchOnline(fullText, unsure.map { it.value!! }) }.getOrNull() ?: return answers
        val out = answers.toMutableList()
        unsure.forEachIndexed { n, (i, mine) ->
            val web = found.getOrNull(n) ?: return@forEachIndexed
            if (web.nutrients[Metric.CALORIES.key] == null || web.needsDetail) return@forEachIndexed
            out[i] = web.copy(fromWeb = true, lookUpOnline = false, brand = web.brand ?: mine?.brand,
                productSearch = web.productSearch ?: mine?.productSearch)
        }
        return out
    }

    /**
     * Answers whose numbers can't all be true (calories that don't match the
     * macros, more than 9 kcal a gram) get one more look, with thinking on and the
     * problem named. The second answer stays only if it has fewer problems; a call
     * that fails leaves the first.
     */
    fun withRecheck(fullText: String, answers: List<AiItemAnswer?>): List<AiItemAnswer?> {
        val bad = answers.withIndex().mapNotNull { (i, a) ->
            if (a == null || a.needsDetail || a.nutrients.isEmpty()) return@mapNotNull null
            val issues = com.nutritionscribe.shared.model.NutrientSanity.check(a.nutrients, a.grams)
            if (issues.isEmpty()) null else Triple(i, a, issues)
        }
        if (bad.isEmpty()) return answers
        val user = buildString {
            append("They said: ").append(fullText.trim()).append("\n\nThese answers don't add up. Check each one and answer again:\n")
            bad.forEachIndexed { n, (_, a, issues) ->
                append("${n + 1}. ${a.name}").append(a.portion?.let { " [$it]" } ?: "")
                append(" had ").append(a.nutrients.entries.joinToString(", ") { "${it.key} ${"%.0f".format(java.util.Locale.ROOT, it.value)}" })
                append(". Problem: ").append(issues.joinToString("; ") { it.message }).append("\n")
            }
        }
        val body = buildJsonObject {
            put("model", model)
            // Thinking on, so no temperature or seed: the model takes those only with it off.
            put("reasoning_effort", "medium")
            put("max_completion_tokens", 6000)
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") { put("name", "food_items"); put("strict", true); put("schema", SCHEMA) }
            }
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", SYSTEM) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            }
        }.toString()
        val (status, text) = runCatching {
            transport.post("https://api.openai.com/v1/chat/completions", mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json"), body)
        }.getOrNull() ?: return answers
        if (status !in 200..299) return answers
        val again = parse(text, bad.size) ?: return answers
        val out = answers.toMutableList()
        bad.forEachIndexed { n, (i, first, issues) ->
            val second = again.getOrNull(n) ?: return@forEachIndexed
            if (second.nutrients[Metric.CALORIES.key] == null) return@forEachIndexed
            val left = com.nutritionscribe.shared.model.NutrientSanity.check(second.nutrients, second.grams)
            if (left.size < issues.size) out[i] = second.copy(fromWeb = first.fromWeb)
        }
        return out
    }

    /** One Responses API call with the web search tool, for [items] in order. */
    private fun searchOnline(fullText: String, items: List<AiItemAnswer>): List<AiItemAnswer?>? {
        val input = webInput(fullText, items)
        val headers = mapOf("Authorization" to "Bearer $apiKey", "Content-Type" to "application/json")
        var (status, text) = webTransport.post(RESPONSES_URL, headers, webRequest(input, structured = true))
        // Not documented whether a strict schema combines with web search on every
        // model: if it is refused, ask for the same JSON in plain text instead.
        if (status == 400) {
            val retry = webTransport.post(RESPONSES_URL, headers, webRequest(input, structured = false))
            status = retry.first; text = retry.second
        }
        if (status !in 200..299) return null
        val json = responsesText(text)?.let { t -> t.substring(t.indexOf('{').coerceAtLeast(0), t.lastIndexOf('}') + 1) } ?: return null
        val asChat = buildJsonObject { putJsonArray("choices") { add(buildJsonObject { putJsonObject("message") { put("content", json) } }) } }
        return parse(asChat.toString(), items.size)
    }

    fun webRequest(input: String, structured: Boolean): String = buildJsonObject {
        put("model", model)
        if (structured) putJsonObject("reasoning") { put("effort", "low") }
        putJsonArray("tools") { add(buildJsonObject { put("type", "web_search"); put("search_context_size", "low") }) }
        put("tool_choice", "auto")
        put("max_output_tokens", 3000)
        put("instructions", if (structured) WEB_SYSTEM else WEB_SYSTEM + "\n\n" + JSON_ONLY)
        // Nothing kept at OpenAI after the answer (the Responses API stores by default).
        put("store", false)
        put("input", input)
        if (structured) putJsonObject("text") {
            putJsonObject("format") {
                put("type", "json_schema"); put("name", "food_items"); put("strict", true); put("schema", SCHEMA)
            }
        }
    }.toString()

    private fun webInput(fullText: String, items: List<AiItemAnswer>): String = buildString {
        append("They said: ").append(fullText.trim()).append("\n\nLook these up and give the published numbers for the amount they ate:\n")
        items.forEachIndexed { i, a ->
            append("${i + 1}. ${a.name}")
            a.brand?.let { append(" [brand: $it]") }
            a.portion?.let { append(" [amount: $it]") }
            append("\n")
        }
    }

    /** The answer text of a Responses API reply: the message after any web_search_call items. */
    fun responsesText(response: String): String? = runCatching {
        Json.parseToJsonElement(response).jsonObject["output"]!!.jsonArray
            .map { it.jsonObject }.lastOrNull { (it["type"] as? JsonPrimitive)?.content == "message" }
            ?.get("content")?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { (it["type"] as? JsonPrimitive)?.content == "output_text" }
            ?.get("text")?.jsonPrimitive?.content
    }.getOrNull()

    private fun requestBody(system: String, user: String) = buildJsonObject {
        put("model", model)
        put("reasoning_effort", "none")
        put("temperature", 0)
        put("seed", 7)
        put("max_completion_tokens", 3000)
        putJsonObject("response_format") {
            put("type", "json_schema")
            putJsonObject("json_schema") {
                put("name", "food_items")
                put("strict", true)
                put("schema", SCHEMA)
            }
        }
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            add(buildJsonObject { put("role", "user"); put("content", user) })
        }
    }.toString()

    /** Every item the model returned, in its order (the "item" numbers ignored). */
    fun parseAll(response: String): List<AiItemAnswer>? {
        val count = runCatching {
            val content = Json.parseToJsonElement(response).jsonObject["choices"]!!.jsonArray[0]
                .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
            Json.parseToJsonElement(content).jsonObject["items"]!!.jsonArray.size
        }.getOrNull() ?: return null
        // Renumber so parse() reads them all in order.
        val renumbered = runCatching {
            val root = Json.parseToJsonElement(response).jsonObject
            val content = root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
            val out = Json.parseToJsonElement(content).jsonObject
            val items = out["items"]!!.jsonArray.mapIndexed { i, el ->
                JsonObject(el.jsonObject + ("item" to JsonPrimitive(i + 1)))
            }
            val newContent = JsonObject(out + ("items" to JsonArray(items))).toString()
            buildJsonObject {
                putJsonArray("choices") { add(buildJsonObject { putJsonObject("message") { put("content", newContent) } }) }
            }.toString()
        }.getOrNull() ?: return null
        return parse(renumbered, count)?.filterNotNull()
    }

    /**
     * The app's own reading of the sentence goes with it, so every answer lines up
     * with an item the user will see — and is remembered under the user's words,
     * not the model's name for the food, which would never match again.
     */
    fun userMessage(fullText: String, items: List<ItemParser.Item>): String = buildString {
        append("They said: ").append(fullText.trim()).append("\n\nItems to fill, as I read them:\n")
        items.forEachIndexed { i, it ->
            val amount = listOfNotNull(
                it.amount?.let { a -> if (a == a.toLong().toDouble()) a.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, a) },
                it.unit, it.size,
            ).joinToString(" ").ifBlank { "no amount said" }
            append("${i + 1}. ${it.food} [$amount]")
            it.brand?.let { b -> append(" [brand: $b]") }
            it.statedProteinG?.let { p -> append(" [they said ${p.toInt()} g protein]") }
            append("\n")
        }
    }

    fun parse(response: String, expected: Int): List<AiItemAnswer?>? = runCatching {
        val content = Json.parseToJsonElement(response).jsonObject["choices"]!!.jsonArray[0]
            .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        val out = Json.parseToJsonElement(content).jsonObject
        val answers = arrayOfNulls<AiItemAnswer>(expected)
        out["items"]?.jsonArray.orEmpty().forEach { el ->
            val o = el.jsonObject
            val index = ((o["item"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: return@forEach) - 1
            if (index !in 0 until expected) return@forEach
            fun num(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            val nutrients = buildMap {
                for ((field, metric) in FIELDS) num(field)?.let { put(metric.key, it) }
            }
            val count = num("count")
            val unit = str("unit")
            answers[index] = AiItemAnswer(
                name = str("food") ?: "Food",
                portion = describe(count, unit, num("grams")),
                grams = num("grams"),
                nutrients = nutrients,
                needsDetail = (o["needs_detail"] as? JsonPrimitive)?.booleanOrNull == true,
                lookUpOnline = (o["look_up_online"] as? JsonPrimitive)?.booleanOrNull == true,
                brand = str("brand"),
                count = count,
                unit = unit,
                productSearch = str("product_search"),
            )
        }
        answers.toList()
    }.getOrNull()

    private fun describe(count: Double?, unit: String?, grams: Double?): String? {
        val c = count?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else "%.2g".format(it) }
        val amount = listOfNotNull(c, unit).joinToString(" ").ifBlank { null }
        val g = grams?.let { "${it.toInt()} g" }
        return when {
            amount != null && g != null && unit != "g" -> "$amount ($g)"
            amount != null -> amount
            else -> g
        }
    }

    companion object {
        /**
         * GPT-6 Luna (September 2026): half GPT-5.6 Luna's price ($0.10 / $0.50 per
         * million tokens against $0.20 / $1.20) and takes the same request.
         */
        const val DEFAULT_MODEL = "gpt-6-luna"

        private val FIELDS = listOf(
            "kcal" to Metric.CALORIES, "protein_g" to Metric.PROTEIN, "carbs_g" to Metric.CARBS,
            "fat_g" to Metric.FAT, "fiber_g" to Metric.FIBER, "sugar_g" to Metric.SUGAR,
            "sat_fat_g" to Metric.SATURATED_FAT, "sodium_mg" to Metric.SODIUM, "caffeine_mg" to Metric.CAFFEINE,
        )

        private fun nullable(type: String) = buildJsonObject {
            put("type", buildJsonArray { add(JsonPrimitive(type)); add(JsonPrimitive("null")) })
        }

        val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("items") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        put("additionalProperties", false)
                        val props = linkedMapOf<String, JsonElement>(
                            "item" to buildJsonObject { put("type", "integer") },
                            "needs_detail" to buildJsonObject { put("type", "boolean") },
                            "look_up_online" to buildJsonObject { put("type", "boolean") },
                            "food" to buildJsonObject { put("type", "string") },
                            "brand" to nullable("string"),
                            "product_search" to nullable("string"),
                            "count" to nullable("number"),
                            "unit" to nullable("string"),
                            "grams" to buildJsonObject { put("type", "number") },
                        )
                        for ((f, _) in FIELDS) props[f] = nullable("number")
                        put("properties", JsonObject(props))
                        put("required", JsonArray(props.keys.map { JsonPrimitive(it) }))
                    }
                }
            }
            put("required", buildJsonArray { add(JsonPrimitive("items")) })
        }

        /** The prompt that measured best; see tools/ai_lookup.py (ITEM_RULES, HYBRID_SYSTEM). */
        /** The research prompt that measured best on whole sentences (tools/ai_lookup.py HYBRID_SYSTEM). */
        val SENTENCE_SYSTEM = """
You turn a plain-language description of food someone ate into structured data for a nutrition log. Accuracy is what matters most.

Rules for reading the description:
- One item per distinct food actually eaten. A sandwich is ONE item only if it is a single known thing (a Big Mac); a homemade sandwich is its parts (bread, filling, spread), because the parts are what decide the numbers.
- Never count the same food twice. "A PB&J with 2 tbsp of peanut butter" is bread + 2 tbsp peanut butter + jelly, not a sandwich plus peanut butter.
- grams is the edible weight actually eaten, as eaten (cooked weight for cooked food). Convert the stated amount: 1 cup cooked rice = 158 g, 1 cup milk = 244 g, 1 tbsp peanut butter = 16 g, 1 tbsp oil = 13.5 g, 1 large egg = 50 g, 1 slice bread = 28-32 g, 1 oz = 28.35 g. With no amount, use one ordinary serving of that food as a person would eat it (a medium banana 118 g, one chicken breast 170 g).
- A container is an amount, not a food: "a sandwich bag of baby carrots" is baby carrots (a snack bag holds about 85 g); "a ziploc of grapes" is grapes. Never an item for the bag. A bag of several foods is one item per food, the bag's amount shared among them.
- A sauce or dish named before what it went on ("Alfredo Florentine with 2 stuffed shells") is its own item: the sauce, as well as the shells.
- A dish described by its parts ("a fruit salad that has some pineapple, grapes, apples...", "a stir fry with chicken, peppers and rice") is ONE ordinary serving of that dish shared among its parts, not a token amount of each: a bowl of fruit salad is about 1.5 cups (about 250 g) in all.
- "a little bit of", "a little", "a splash" = a small amount, never a serving: about a teaspoon (5 g) of butter, oil, mayo or a spread on toast; about a tablespoon of anything else. "A decent amount", "a lot of", "a generous" = about one and a half of the usual amount.
- When they read words off a package ("it says ...", "is what it says", "the label says"), those words are the name and flavour of ONE product, not separate foods.
- Water, ice, or "in water" used to make something (a shake, oatmeal) is not an item. Words in parentheses describe the item before them.
- "N servings" of a packaged product is N times its label serving.
- Fat level words matter: "0%", "nonfat", "2%", "4%", "85/15", "lean".
- needs_detail is true for an item only when it names no identifiable food at all ("a solid breakfast", "leftovers", "the usual") - then give it null nutrients. "Leftover pot roast" names a food: estimate it. Every other item gets numbers, never null: a restaurant plate or combo you do not know is its parts (the meat, the rice, each side) as separate items, estimated.
- For every item give its nutrients for the WHOLE amount eaten (kcal; grams for macros; mg for sodium and caffeine) from your nutrition knowledge -- USDA for generic food, the published label for a brand or chain. Home-cooked plain food has no added salt unless the person said so.
- A number of grams next to a protein product ("38 g protein shake", "17g built bar") is its protein, not its weight: protein_g is that number. A number the person stated for an item always wins over your estimate.
- brand: the brand of a packaged product ("Quaker", "Premier Protein"), else null. product_search: brand + product + flavour for finding its label ("quaker chewy chocolate chip granola bar"), else null. Restaurant food has brand null.
- count + unit: the amount as said, unit singular ("bar", "cookie", "cup", "tbsp", "oz", "g", "container"); count null when no amount was said.
- look_up_online: true only for a specific restaurant or chain menu item, or a branded product, whose exact published numbers you do not reliably know (still give your best estimate). False for generic food and for products you know well.
- item: number the items 1, 2, 3 in the order they were eaten or said.
        """.trimIndent()

        val SYSTEM = """
You turn a plain-language description of food someone ate into structured data for a nutrition log. Accuracy is what matters most.

You get what the person said and a numbered list of the items the app read out of it. Answer every numbered item exactly once, with "item" set to its number, in the same order.
- Use the whole sentence to understand each item: amounts, brands, and details said elsewhere in it apply.
- The amount in brackets is how the app read it. If it misread, use what the person actually said.
- Never count the same food twice. If two listed items are really one thing (a dish and its own ingredient), give the full numbers on the first and zeros on the other.
- A generic item like "sandwich" or "casserole" is the typical homemade version unless the sentence says more.
- grams is the edible weight actually eaten, as eaten (cooked weight for cooked food). Convert the stated amount: 1 cup cooked rice = 158 g, 1 cup milk = 244 g, 1 tbsp peanut butter = 16 g, 1 tbsp oil = 13.5 g, 1 large egg = 50 g, 1 slice bread = 28-32 g, 1 oz = 28.35 g. With no amount, use one ordinary serving of that food as a person would eat it (a medium banana 118 g, one chicken breast 170 g).
- A container is an amount, not a food: "a sandwich bag of baby carrots" is baby carrots (a snack bag holds about 85 g); "a ziploc of grapes" is grapes. Never an item for the bag. A bag of several foods is one item per food, the bag's amount shared among them.
- A sauce or dish named before what it went on ("Alfredo Florentine with 2 stuffed shells") is its own item: the sauce, as well as the shells.
- A dish described by its parts ("a fruit salad that has some pineapple, grapes, apples...", "a stir fry with chicken, peppers and rice") is ONE ordinary serving of that dish shared among its parts, not a token amount of each: a bowl of fruit salad is about 1.5 cups (about 250 g) in all.
- "a little bit of", "a little", "a splash" = a small amount, never a serving: about a teaspoon (5 g) of butter, oil, mayo or a spread on toast; about a tablespoon of anything else. "A decent amount", "a lot of", "a generous" = about one and a half of the usual amount.
- When they read words off a package ("it says ...", "is what it says", "the label says"), those words are the name and flavour of ONE product, not separate foods.
- Water, ice, or "in water" used to make something (a shake, oatmeal) is not an item. Words in parentheses describe the item before them.
- "N servings" of a packaged product is N times its label serving.
- Fat level words matter: "0%", "nonfat", "2%", "4%", "85/15", "lean".
- needs_detail is true for an item only when it names no identifiable food at all ("a solid breakfast", "leftovers", "the usual") - then give it null nutrients. "Leftover pot roast" names a food: estimate it. Every other item gets numbers, never null: a restaurant plate or combo you do not know is its parts (the meat, the rice, each side) as separate items, estimated.
- For every item give its nutrients for the WHOLE amount eaten (kcal; grams for macros; mg for sodium and caffeine) from your nutrition knowledge -- USDA for generic food, the published label for a brand or chain. Home-cooked plain food has no added salt unless the person said so.
- A number of grams next to a protein product ("38 g protein shake", "17g built bar") is its protein, not its weight: protein_g is that number. A number the person stated for an item always wins over your estimate.
- brand: the brand of a packaged product ("Quaker", "Premier Protein"), else null. product_search: brand + product + flavour for finding its label ("quaker chewy chocolate chip granola bar"), else null. Restaurant food has brand null.
- count + unit: the amount as said, unit singular ("bar", "cookie", "cup", "tbsp", "oz", "g", "container"); count null when no amount was said.
- look_up_online: true only for a specific restaurant or chain menu item, or a branded product, whose exact published numbers you do not reliably know (still give your best estimate). False for generic food and for products you know well.
        """.trimIndent()

        const val RESPONSES_URL = "https://api.openai.com/v1/responses"

        /** For the web search call: find the real numbers, for the amount eaten, in the lookup's own format. */
        val WEB_SYSTEM = """
You find the real nutrition numbers for foods someone ate, for a nutrition log. Search the web for each numbered item: the restaurant's or maker's own nutrition page first, then a reliable nutrition site. Give the numbers for the WHOLE amount they ate (kcal; grams for macros; mg for sodium and caffeine), and grams as the edible weight eaten. Answer every numbered item exactly once, with "item" set to its number. If a search finds nothing credible, give your best estimate. "food" is the item's plain name as a menu or package says it ("Chicken Bake", "Brownie"): never notes, sources, sizes or whether it was estimated. look_up_online is false in your answers.
        """.trimIndent()

        private const val JSON_ONLY = "Reply with only a JSON object: {\"items\": [{\"item\", \"needs_detail\", \"look_up_online\", \"food\", \"brand\", \"product_search\", \"count\", \"unit\", \"grams\", \"kcal\", \"protein_g\", \"carbs_g\", \"fat_g\", \"fiber_g\", \"sugar_g\", \"sat_fat_g\", \"sodium_mg\", \"caffeine_mg\"}]}, numbers or null."

        val HttpTransport = Transport { url, headers, body ->
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 45_000
                conn.doOutput = true
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
            } finally {
                conn.disconnect()
            }
        }
    }
}
