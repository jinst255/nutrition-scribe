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
    // Nullable so that a model returning nulls for input it judged too vague is
    // not treated as a parse failure — see the note in OpenAiBackend.
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
    val source: String = "anthropic",
    val confidence: String = "estimated",
    val needs_detail: Boolean = false,
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
                    "name, protein_grams, calories, carbs_grams, fat_grams, fiber_grams, sugar_grams, " +
                    "saturated_fat_grams, sodium_mg, caffeine_mg, is_fixed_food, source, confidence, " +
                    "needs_detail (confidence is \"estimated\" or \"stated\"). Use null for any nutrient " +
                    "you cannot estimate. No prose, no markdown fences. ",
            )
            append(STATED_VS_ESTIMATED_INSTRUCTIONS)
        }

        val stringAdapter = moshi.adapter(String::class.java)
        val bodyJson = """
            {
              "model": "${com.nutritionscribe.shared.resolve.AnthropicItemLookup.DEFAULT_MODEL}",
              "max_tokens": 1000,
              "thinking": {"type": "disabled"},
              "output_config": {"effort": "low"},
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
data class AnthropicMessageResponse(val content: List<AnthropicContentBlock>)

@JsonClass(generateAdapter = true)
data class AnthropicContentBlock(val type: String, val text: String?)
