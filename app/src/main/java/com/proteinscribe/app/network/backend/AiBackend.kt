package com.proteinscribe.app.network.backend

import com.proteinscribe.shared.model.NutritionLookupResult

sealed class LookupOutcome {
    data class Success(val result: NutritionLookupResult) : LookupOutcome()
    sealed class Failure : LookupOutcome() {
        data object NoNetwork : Failure()
        /** [errorBody] is the provider's error JSON (truncated), kept for the debug log. */
        data class ApiError(val statusCode: Int, val errorBody: String? = null) : Failure()
        data object MalformedResponse : Failure()
        data object AuthError : Failure()
    }
}

/**
 * Pluggable AI backend interface (Section 5). Cloud APIs are the only implementations
 * in v1, but a v2 local-LLM backend (e.g. Ollama) is a new implementation of this
 * interface plus a Settings option — not a rewrite.
 */
interface AiBackend {
    /**
     * Full lookup: send the raw entry text, get back the structured nutrition contract.
     * Whether any part of the text already states a definitive protein amount (and for
     * how much of the entry that amount actually accounts) is a judgment call left to
     * the model itself via the prompt — a client-side heuristic can't reliably tell a
     * whole-item statement ("Built bar, 16g protein") from a partial one (only one
     * ingredient's protein mentioned in an otherwise multi-part meal).
     */
    suspend fun lookup(rawText: String): LookupOutcome
}
