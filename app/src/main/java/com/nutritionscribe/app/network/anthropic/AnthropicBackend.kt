package com.nutritionscribe.app.network.anthropic

import com.nutritionscribe.app.network.backend.AiBackend
import com.nutritionscribe.app.network.backend.LookupOutcome
import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.NutritionLookupResult
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.nutritionscribe.app.network.openai.STATED_VS_ESTIMATED_INSTRUCTIONS
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@JsonClass(generateAdapter = true)
data class AnthropicNutritionJson(
    val name: String,
    val protein_grams: Double,
    val calories: Double?,
    val carbs_grams: Double?,
    val fat_grams: Double?,
    val is_fixed_food: Boolean,
    val source: String,
    val confidence: String,
)

/**
 * Claude Haiku-class backend (Section 5), same structured-output contract as OpenAiBackend
 * so the rest of the app is backend-agnostic (Section 5: "pluggable AI backend interface").
 */
class AnthropicBackend(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : AiBackend {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val jsonAdapter = moshi.adapter(AnthropicNutritionJson::class.java)

    override suspend fun lookup(rawText: String): LookupOutcome =
        withContext(Dispatchers.IO) {
            lookupBlocking(rawText)
        }

    private fun lookupBlocking(rawText: String): LookupOutcome {
        if (apiKey.isBlank()) return LookupOutcome.Failure.AuthError

        val instructions = buildString {
            append(
                "You are a nutrition lookup assistant. Reply with ONLY a JSON object with keys: " +
                    "name, protein_grams, calories, carbs_grams, fat_grams, is_fixed_food, source, confidence " +
                    "(confidence is \"estimated\" or \"stated\"). No prose, no markdown fences. ",
            )
            append(STATED_VS_ESTIMATED_INSTRUCTIONS)
        }

        val stringAdapter = moshi.adapter(String::class.java)
        val bodyJson = """
            {
              "model": "claude-haiku-4-5",
              "max_tokens": 300,
              "system": ${stringAdapter.toJson(instructions)},
              "messages": [
                {"role": "user", "content": ${stringAdapter.toJson(rawText)}}
              ]
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
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
            val envelope = moshi.adapter(AnthropicMessageResponse::class.java).fromJson(rawBody)
            val text = envelope?.content?.firstOrNull { it.type == "text" }?.text
                ?: return LookupOutcome.Failure.MalformedResponse
            val parsed = jsonAdapter.fromJson(text) ?: return LookupOutcome.Failure.MalformedResponse
            LookupOutcome.Success(
                NutritionLookupResult(
                    name = parsed.name,
                    proteinGrams = parsed.protein_grams,
                    calories = parsed.calories,
                    carbsGrams = parsed.carbs_grams,
                    fatGrams = parsed.fat_grams,
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
data class AnthropicMessageResponse(val content: List<AnthropicContentBlock>)

@JsonClass(generateAdapter = true)
data class AnthropicContentBlock(val type: String, val text: String?)
