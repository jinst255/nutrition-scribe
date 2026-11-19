package com.nutritionscribe.shared.resolve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * "Something's wrong with this entry": the AI reads what the person said is wrong
 * and says what to change, line by line. The app applies it the way a spoken change
 * is applied (a new amount, another food looked up, a line removed or added, or
 * numbers typed in), shows what changed, and offers Undo.
 *
 * Owner, 11-18: "When I click something is wrong with this entry, AI should be able
 * to go in and fix whatever I say." Before, the note only went in the debug log.
 */
object EntryFix {

    /** One line of the entry as the AI is shown it. */
    data class Line(
        val name: String,
        val said: String,
        val amount: Double?,
        val unit: String?,
        val grams: Double?,
        val nutrients: Map<String, Double>,
    )

    sealed class Action {
        data class Amount(val line: Int, val amount: Double, val unit: String?) : Action()
        /** Another food for the line: [food] are words to look it up by, with its brand and kind. */
        data class Food(val line: Int, val food: String, val amount: Double?, val unit: String?) : Action()
        data class Remove(val line: Int) : Action()
        data class Add(val food: String, val amount: Double?, val unit: String?) : Action()
        /** Numbers for the line's whole portion, as the person said them or as the label has them. */
        data class Numbers(val line: Int, val nutrients: Map<String, Double>) : Action()
    }

    data class Fix(val summary: String, val actions: List<Action>)

    /** The nutrients it may set directly. */
    val KEYS = listOf("calories", "protein", "carbs", "fat", "fiber", "sugar", "added_sugar", "saturated_fat", "sodium")

    const val SYSTEM = "You fix one logged meal in a nutrition app. The person says what is wrong with it. " +
        "Change only what they say is wrong, and what follows from it. For each change pick one action:\n" +
        "- amount: the line's food is right but the amount is wrong (\"it was 5 slices, not 2\").\n" +
        "- food: the line is the wrong food or wrong kind or brand (\"that's sliced bacon, not bacon bits\", \"multi grain, not whole wheat\"). " +
        "Give words to look it up by: brand, kind and food (\"great value low sodium sliced bacon\"), and the amount they ate in amount and unit.\n" +
        "- numbers: the food is right but its numbers are wrong, and they told you (or a label clearly has) the right ones. " +
        "Give the numbers for the line's whole amount, not per serving. Never invent numbers you aren't sure of; use food instead.\n" +
        "- remove: they didn't eat it.\n- add: they ate something that isn't a line. line is -1.\n" +
        "Lines are numbered from 0. Units are words like slice, piece, serving, cup, tbsp, g, oz, ml; null for a count. " +
        "Speech-to-text artefacts like [BLANK_AUDIO] are noise. If nothing they said is a change you can make, return no changes. " +
        "summary: one short sentence for the person saying what you changed (or why nothing)."

    /** What the AI is shown: the entry, numbered, with each line's numbers. */
    fun prompt(said: String, lines: List<Line>, complaint: String): String = buildString {
        append("They first said: \"").append(said.trim()).append("\"\n\nThe entry now:\n")
        lines.forEachIndexed { i, l ->
            append(i).append(". ").append(l.name)
            append(" — ").append(listOfNotNull(l.amount?.let { Units.format(it) }, l.unit).joinToString(" ").ifBlank { "1" })
            l.grams?.let { append(" (").append(Units.format(it)).append(" g)") }
            append(": ")
            append(KEYS.mapNotNull { k -> l.nutrients[k]?.let { "$k ${Units.format(Math.round(it * 10) / 10.0)}" } }.joinToString(", "))
            if (l.said.isNotBlank()) append(" [they said \"").append(l.said.trim()).append("\"]")
            append('\n')
        }
        append("\nWhat they say is wrong: \"").append(clean(complaint)).append('"')
    }

    /** Speech-to-text noise out: Whisper writes "[BLANK_AUDIO]" for silence. */
    fun clean(text: String): String =
        text.replace(Regex("\\[\\s*BLANK_AUDIO\\s*]|\\(\\s*blank_audio\\s*\\)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("[ \\t]{2,}"), " ").trim()

    val SCHEMA: JsonObject = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        putJsonArray("required") { add("summary"); add("changes") }
        putJsonObject("properties") {
            putJsonObject("summary") { put("type", "string") }
            putJsonObject("changes") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object"); put("additionalProperties", false)
                    putJsonArray("required") { listOf("line", "action", "food", "amount", "unit", "nutrients").forEach { add(it) } }
                    putJsonObject("properties") {
                        putJsonObject("line") { put("type", "integer") }
                        putJsonObject("action") { put("type", "string"); putJsonArray("enum") { listOf("amount", "food", "numbers", "remove", "add").forEach { add(it) } } }
                        putJsonObject("food") { putJsonArray("type") { add("string"); add("null") } }
                        putJsonObject("amount") { putJsonArray("type") { add("number"); add("null") } }
                        putJsonObject("unit") { putJsonArray("type") { add("string"); add("null") } }
                        putJsonObject("nutrients") {
                            putJsonArray("type") { add("object"); add("null") }
                            put("additionalProperties", false)
                            putJsonArray("required") { KEYS.forEach { add(it) } }
                            putJsonObject("properties") { KEYS.forEach { k -> putJsonObject(k) { putJsonArray("type") { add("number"); add("null") } } } }
                        }
                    }
                }
            }
        }
    }

    /** The fix from its JSON; lines out of range and empty actions are dropped. */
    fun parse(json: String, lineCount: Int): Fix? = runCatching {
        val o = Json.parseToJsonElement(json).jsonObject
        val summary = (o["summary"] as? JsonPrimitive)?.content.orEmpty()
        val actions = (o["changes"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e.jsonObject
            val line = (c["line"] as? JsonPrimitive)?.intOrNull ?: -1
            val food = (c["food"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotBlank() }
            val amount = (c["amount"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }
            val unit = (c["unit"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?.let { u -> ItemParser.UNITS[u] ?: ItemParser.UNITS[u.removeSuffix("s")] ?: u }
            val inRange = line in 0 until lineCount
            when ((c["action"] as? JsonPrimitive)?.content) {
                "amount" -> if (inRange && amount != null) Action.Amount(line, amount, unit) else null
                "food" -> if (inRange && food != null) Action.Food(line, food, amount, unit) else null
                "remove" -> if (inRange) Action.Remove(line) else null
                "add" -> food?.let { Action.Add(it, amount, unit) }
                "numbers" -> {
                    val n = (c["nutrients"] as? JsonObject)?.mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.doubleOrNull?.takeIf { k in KEYS && it >= 0 }?.let { k to it }
                    }?.toMap().orEmpty()
                    if (inRange && n.isNotEmpty()) Action.Numbers(line, n) else null
                }
                else -> null
            }
        }
        Fix(summary.ifBlank { if (actions.isEmpty()) "Nothing to change." else "Fixed." }, actions)
    }.getOrNull()

    /** Words to look up a food by, with the amount to keep: "5 slice great value low sodium bacon". */
    fun query(food: String, amount: Double?, unit: String?): String = Changes.swapQuery(food, amount, unit)

    fun openAiBody(model: String, prompt: String): String = buildJsonObject {
        put("model", model)
        put("instructions", SYSTEM)
        putJsonArray("input") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) }
        put("store", false)
        putJsonObject("reasoning") { put("effort", "low") }
        put("max_output_tokens", 2000)
        putJsonObject("text") {
            putJsonObject("format") { put("type", "json_schema"); put("name", "entry_fix"); put("strict", true); put("schema", SCHEMA) }
        }
    }.toString()

    fun anthropicBody(model: String, prompt: String): String = buildJsonObject {
        put("model", model)
        put("max_tokens", 2000)
        put("system", SYSTEM)
        putJsonArray("tools") { add(buildJsonObject { put("name", "entry_fix"); put("description", "The changes to make."); put("input_schema", SCHEMA) }) }
        putJsonObject("tool_choice") { put("type", "tool"); put("name", "entry_fix") }
        putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) }
    }.toString()

    /** The JSON text of the answer, from either provider's reply. */
    fun answerOf(body: String, provider: AskClient.Provider): String? = runCatching {
        val o = Json.parseToJsonElement(body).jsonObject
        when (provider) {
            AskClient.Provider.OPENAI -> o["output"]!!.jsonArray.map { it.jsonObject }
                .lastOrNull { (it["type"] as? JsonPrimitive)?.content == "message" }
                ?.get("content")?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { (it["type"] as? JsonPrimitive)?.content == "output_text" }
                ?.get("text")?.let { (it as JsonPrimitive).content }
            AskClient.Provider.ANTHROPIC -> o["content"]!!.jsonArray.map { it.jsonObject }
                .firstOrNull { (it["type"] as? JsonPrimitive)?.content == "tool_use" }?.get("input")?.toString()
        }
    }.getOrNull()
}
