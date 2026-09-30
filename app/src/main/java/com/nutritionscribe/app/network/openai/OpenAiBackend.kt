package com.nutritionscribe.app.network.openai

import com.nutritionscribe.app.network.backend.AiBackend
import com.nutritionscribe.app.network.backend.LookupOutcome
import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.NutritionLookupResult
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val STRUCTURED_OUTPUT_SCHEMA = """
{
  "name": "string",
  "protein_grams": "number",
  "calories": "number | null",
  "carbs_grams": "number | null",
  "fat_grams": "number | null",
  "fiber_grams": "number | null",
  "sugar_grams": "number | null",
  "saturated_fat_grams": "number | null",
  "sodium_mg": "number | null",
  "caffeine_mg": "number | null",
  "is_fixed_food": "boolean",
  "source": "string",
  "confidence": "estimated | stated",
  "needs_detail": "boolean"
}
"""

/**
 * Shared judgment call for both backends: only treat a stated gram amount as
 * authoritative when it clearly covers the food's ENTIRE protein content, not just
 * one ingredient. A regex can't tell "Built bar, 16g protein" (a whole item) apart
 * from "cheese on my burger had about 10g protein" (one component of a bigger meal) —
 * the model has to actually read the sentence.
 */
internal const val STATED_VS_ESTIMATED_INSTRUCTIONS =
    "If the message gives a protein amount that clearly covers the ENTIRE food or meal as a single, " +
        "wholly-described item (e.g. \"Built bar, 16g protein\"), use that number directly for protein_grams " +
        "and set confidence to \"stated\". If a protein amount is only given for ONE ingredient or component " +
        "of a larger, multi-part meal (e.g. only the cheese's protein was mentioned on a hamburger that also " +
        "has a bun, meat, and vegetables), do NOT use that number as the total — incorporate it into your own " +
        "estimate of the full meal's protein and set confidence to \"estimated\". When nothing is stated, " +
        "estimate protein_grams and the other fields from your nutrition knowledge and set confidence to \"estimated\". " +
        "If the description is too vague to estimate at all (e.g. \"a solid breakfast\", \"leftovers\"), set " +
        "needs_detail to true and leave the nutrient fields null rather than inventing numbers."

@JsonClass(generateAdapter = true)
data class OpenAiNutritionJson(
    val name: String,
    // NULLABLE, not just defaulted. The prompt tells the model to return nulls
    // when the description is too vague to estimate — and it does. A
    // non-nullable field then rejected the model's own correct answer, so
    // "a solid breakfast" came back to the user as "Couldn't understand the
    // result. Err: PARSE_0001" instead of a prompt to add detail.
    val protein_grams: Double? = null,
    val calories: Double? = null,
    val carbs_grams: Double? = null,
    val fat_grams: Double? = null,
    val fiber_grams: Double? = null,
    val sugar_grams: Double? = null,
    val saturated_fat_grams: Double? = null,
    val sodium_mg: Double? = null,
    val caffeine_mg: Double? = null,
    val is_fixed_food: Boolean = false,
    val source: String = "openai",
    val confidence: String = "estimated",
    val needs_detail: Boolean = false,
)

/**
 * GPT-5.6 Luna backend (Section 5). Uses Chat Completions with a JSON response format
 * and a system prompt describing the exact structured-output contract.
 *
 * GPT-5-family reasoning models reject `max_tokens` (HTTP 400) and require
 * `max_completion_tokens`, which also covers hidden reasoning tokens — so the budget
 * has to be well above the size of the JSON reply or the content comes back empty.
 */
class OpenAiBackend(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : AiBackend {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val jsonAdapter = moshi.adapter(OpenAiNutritionJson::class.java)

    override suspend fun lookup(rawText: String): LookupOutcome =
        withContext(Dispatchers.IO) {
            lookupBlocking(rawText)
        }

    private fun lookupBlocking(rawText: String): LookupOutcome {
        if (apiKey.isBlank()) return LookupOutcome.Failure.AuthError

        val systemPrompt = buildString {
            append("You are a nutrition lookup assistant for a food logging app. ")
            append("Given a plain-language description of what someone ate, respond with ONLY a JSON object ")
            append("matching this shape: $STRUCTURED_OUTPUT_SCHEMA. ")
            append(STATED_VS_ESTIMATED_INSTRUCTIONS)
        }

        val bodyJson = """
            {
              "model": "gpt-5.6-luna",
              "max_completion_tokens": 2000,
              "response_format": { "type": "json_object" },
              "messages": [
                {"role": "system", "content": ${moshi.adapter(String::class.java).toJson(systemPrompt)}},
                {"role": "user", "content": ${moshi.adapter(String::class.java).toJson(rawText)}}
              ]
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> LookupOutcome.Failure.AuthError
                    !response.isSuccessful -> LookupOutcome.Failure.ApiError(response.code, response.body?.string()?.take(500))
                    else -> parseContent(response.body?.string())
                }
            }
        } catch (e: IOException) {
            LookupOutcome.Failure.NoNetwork
        }
    }

    private fun parseContent(rawBody: String?): LookupOutcome {
        if (rawBody == null) return LookupOutcome.Failure.MalformedResponse
        return try {
            val envelope = moshi.adapter(OpenAiChatResponse::class.java).fromJson(rawBody)
            val content = envelope?.choices?.firstOrNull()?.message?.content
                ?: return LookupOutcome.Failure.MalformedResponse
            val parsed = jsonAdapter.fromJson(content) ?: return LookupOutcome.Failure.MalformedResponse
            LookupOutcome.Success(
                NutritionLookupResult(
                    name = parsed.name,
                    proteinGrams = parsed.protein_grams ?: 0.0,
                    calories = parsed.calories,
                    carbsGrams = parsed.carbs_grams,
                    fatGrams = parsed.fat_grams,
                    fiberGrams = parsed.fiber_grams,
                    sugarGrams = parsed.sugar_grams,
                    saturatedFatGrams = parsed.saturated_fat_grams,
                    sodiumMg = parsed.sodium_mg,
                    caffeineMg = parsed.caffeine_mg,
                    needsDetail = parsed.needs_detail,
                    isFixedFood = parsed.is_fixed_food,
                    source = parsed.source,
                    confidence = if (parsed.confidence == "stated") Confidence.STATED else Confidence.ESTIMATED,
                ),
            )
        } catch (e: Exception) {
            LookupOutcome.Failure.MalformedResponse
        }
    }
}

@JsonClass(generateAdapter = true)
data class OpenAiChatResponse(val choices: List<OpenAiChoice>)

@JsonClass(generateAdapter = true)
data class OpenAiChoice(val message: OpenAiMessage)

@JsonClass(generateAdapter = true)
data class OpenAiMessage(val content: String)
