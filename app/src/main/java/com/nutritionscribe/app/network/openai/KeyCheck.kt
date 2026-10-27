package com.nutritionscribe.app.network.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Whether an OpenAI key works, checked at setup with a free call (listing models
 * costs nothing), so a typo shows there and not as a failed lookup later.
 */
object KeyCheck {
    sealed interface Result {
        data object Works : Result
        /** 401: OpenAI doesn't know the key (a typo, or it was deleted). */
        data object Refused : Result
        /** 429 with insufficient_quota: a real key with no credit or budget left. */
        data object NoCredit : Result
        /** No answer: offline, or OpenAI is down. The key may still be fine. */
        data class Unreachable(val why: String) : Result
    }

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }

    suspend fun check(key: String): Result = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $key").get().build()
        runCatching { client.newCall(req).execute() }.fold(
            onSuccess = { r -> r.use { classify(it.code, it.body?.string().orEmpty()) } },
            onFailure = { Result.Unreachable(it.javaClass.simpleName) },
        )
    }

    /**
     * 403 counts as working: testers get restricted keys, and a key without the
     * "models" permission is refused this list while lookups still work.
     */
    internal fun classify(code: Int, body: String): Result = when {
        code in 200..299 || code == 403 -> Result.Works
        code == 401 -> Result.Refused
        code == 429 && body.contains("insufficient_quota") -> Result.NoCredit
        code == 429 -> Result.Works
        else -> Result.Unreachable("HTTP $code")
    }
}
