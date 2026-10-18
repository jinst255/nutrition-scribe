package com.nutritionscribe.shared.resolve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/**
 * Layer 4 over an Anthropic key, for people who set one of those instead.
 *
 * Same prompt and item contract as [OpenAiItemLookup]; the structured answer comes
 * back as a forced tool call, which is how the Messages API guarantees the shape.
 * NOTE: unlike the OpenAI path, this one has not been measured against the answer
 * keys (no key was available when it was written).
 */
class AnthropicItemLookup(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val transport: OpenAiItemLookup.Transport = OpenAiItemLookup.HttpTransport,
) : AiItemLookup {

    override fun lookup(fullText: String, items: List<ItemParser.Item>): List<AiItemAnswer?> {
        if (apiKey.isBlank()) throw OpenAiItemLookup.Failure.Auth()
        // Reuse the OpenAI class to build the same user message and to read items.
        val shared = OpenAiItemLookup(apiKey)
        val user = shared.userMessage(fullText, items)
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 3000)
            // Haiku 5.5 refuses any temperature but the default (HTTP 400). The forced
            // tool call below skips its thinking, so a lookup stays quick and cheap.
            putJsonObject("output_config") { put("effort", "low") }
            put("system", OpenAiItemLookup.SYSTEM)
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("name", "food_items")
                    put("description", "Record the nutrition of each numbered item.")
                    put("input_schema", OpenAiItemLookup.SCHEMA)
                })
            }
            putJsonObject("tool_choice") { put("type", "tool"); put("name", "food_items") }
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "user"); put("content", user) })
            }
        }.toString()
        val (status, text) = try {
            transport.post(
                "https://api.anthropic.com/v1/messages",
                mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01", "Content-Type" to "application/json"),
                body,
            )
        } catch (e: IOException) {
            throw OpenAiItemLookup.Failure.NoNetwork()
        }
        if (status == 401 || status == 403) throw OpenAiItemLookup.Failure.Auth()
        if (status !in 200..299) throw OpenAiItemLookup.Failure.Api(status)
        val input = runCatching {
            Json.parseToJsonElement(text).jsonObject["content"]!!.jsonArray
                .first { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" }
                .jsonObject["input"] as JsonObject
        }.getOrNull() ?: throw OpenAiItemLookup.Failure.Malformed()
        // Wrap it in the OpenAI envelope so there is one reader.
        val envelope = buildJsonObject {
            putJsonArray("choices") {
                add(buildJsonObject { putJsonObject("message") { put("content", JsonPrimitive(input.toString())) } })
            }
        }
        return shared.parse(envelope.toString(), items.size) ?: throw OpenAiItemLookup.Failure.Malformed()
    }

    companion object {
        /** Claude Haiku 5.5: $0.10 / $0.50 per million tokens, a tenth of Haiku 4.5. */
        const val DEFAULT_MODEL = "claude-haiku-5-5"
    }
}
