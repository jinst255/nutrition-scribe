package com.nutritionscribe.app.data.usage

import com.nutritionscribe.app.ui.debuglog.DebugEvents
import com.nutritionscribe.shared.resolve.OpenAiItemLookup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * Every paid AI call goes past here: what it cost, and — in debug mode — exactly
 * what was sent and what came back.
 *
 * The provider's own usage numbers in each response are the source; nothing is
 * guessed from text length. The cost is those numbers times [PriceBook].
 */
class UsageMeter(
    private val dao: AiUsageDao,
    private val events: DebugEvents,
) {
    /**
     * The logging session the next calls belong to. Set around a lookup (the
     * resolver is blocking, so a ThreadLocal follows it), then [attach]ed to the
     * entry once there is one.
     */
    private val tag = ThreadLocal<String?>()

    fun <T> withTag(t: String?, block: () -> T): T {
        val before = tag.get()
        tag.set(t)
        try { return block() } finally { tag.set(before) }
    }

    suspend fun attach(t: String?, entryId: String) {
        if (t != null) dao.attach(t, entryId)
    }

    /** Parses the usage block of an OpenAI or Anthropic response and records it. */
    fun recordHttp(
        url: String,
        requestBody: String,
        status: Int,
        responseBody: String,
        millis: Long,
        kind: UsageKind = UsageKind.FOOD_LOOKUP,
        explicitTag: String? = null,
    ) {
        val provider = if (url.contains("anthropic")) "anthropic" else "openai"
        val request = runCatching { Json.parseToJsonElement(requestBody).jsonObject }.getOrNull()
        val response = runCatching { Json.parseToJsonElement(responseBody).jsonObject }.getOrNull()
        val model = (response?.get("model") as? JsonPrimitive)?.content
            ?: (request?.get("model") as? JsonPrimitive)?.content ?: "unknown"
        val usage = response?.get("usage") as? JsonObject
        fun n(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.longOrNull ?: 0L
        val input: Long
        val cached: Long
        val output: Long
        if (provider == "anthropic") {
            // Anthropic counts cache reads separately from input_tokens.
            cached = n(usage, "cache_read_input_tokens")
            input = n(usage, "input_tokens") + cached + n(usage, "cache_creation_input_tokens")
            output = n(usage, "output_tokens")
        } else {
            input = n(usage, "prompt_tokens")
            cached = n(usage?.get("prompt_tokens_details") as? JsonObject, "cached_tokens")
            output = n(usage, "completion_tokens")
        }
        val cost = PriceBook.tokenCost(model.stripDate(), input, cached, output)
        val row = AiUsageRow(
            timestampEpochMillis = System.currentTimeMillis(),
            provider = provider,
            model = model.stripDate(),
            kind = kind.name,
            inputTokens = input,
            cachedInputTokens = cached,
            outputTokens = output,
            costUsd = cost,
            durationMillis = millis,
            status = status,
            tag = explicitTag ?: tag.get(),
        )
        // A failed call with no usage block cost nothing; it is still worth a debug line.
        if (usage != null) runCatching { dao.insertBlocking(row) }
        events.event(
            "ai_call",
            "kind" to kind, "url" to url, "status" to status, "ms" to millis, "model" to model,
            "input_tokens" to input, "cached_tokens" to cached, "output_tokens" to output,
            "cost_usd" to cost, "tag" to row.tag,
            "request" to (request ?: requestBody.take(20_000)),
            "response" to (response ?: responseBody.take(20_000)),
        )
    }

    fun recordAudio(model: String, seconds: Double, status: Int, millis: Long, text: String?, explicitTag: String?) {
        val cost = if (status in 200..299) PriceBook.audioCost(model, seconds) else 0.0
        val row = AiUsageRow(
            timestampEpochMillis = System.currentTimeMillis(),
            provider = "openai",
            model = model,
            kind = UsageKind.SPEECH.name,
            audioSeconds = seconds,
            costUsd = cost,
            durationMillis = millis,
            status = status,
            tag = explicitTag ?: tag.get(),
        )
        if (status in 200..299) runCatching { dao.insertBlocking(row) }
        events.event(
            "ai_call", "kind" to UsageKind.SPEECH, "model" to model, "status" to status, "ms" to millis,
            "audio_seconds" to seconds, "cost_usd" to cost, "tag" to row.tag, "text" to text,
        )
    }

    /** The transport the shared lookups use, metered. */
    fun transport(kind: UsageKind = UsageKind.FOOD_LOOKUP): OpenAiItemLookup.Transport =
        OpenAiItemLookup.Transport { url, headers, body ->
            val t0 = System.currentTimeMillis()
            try {
                val (status, text) = OpenAiItemLookup.HttpTransport.post(url, headers, body)
                recordHttp(url, body, status, text, System.currentTimeMillis() - t0, kind)
                status to text
            } catch (e: java.io.IOException) {
                events.event("ai_call_failed", "url" to url, "ms" to System.currentTimeMillis() - t0, "error" to e,
                    "request" to runCatching { Json.parseToJsonElement(body) }.getOrNull())
                throw e
            }
        }

    /** The same, for the older OkHttp backends the retry queue still uses. */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val req = chain.request()
        val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
        val t0 = System.currentTimeMillis()
        val resp: Response = chain.proceed(req)
        val text = resp.peekBody(2_000_000).string()
        recordHttp(req.url.toString(), body, resp.code, text, System.currentTimeMillis() - t0)
        resp
    }

    private fun String.stripDate(): String = replace(Regex("-\\d{4}-\\d{2}-\\d{2}$"), "")
}
