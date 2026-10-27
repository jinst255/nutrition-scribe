package com.nutritionscribe.app.data.usage

import com.nutritionscribe.app.data.repository.AiBackendKind
import com.nutritionscribe.app.data.repository.AiJob

/**
 * The models Advanced › AI models offers, per job and provider. Only models the
 * [PriceBook] has a rate for are listed, so every call is costed at its own
 * model's price, never a guess.
 */
object AiModels {
    data class Option(val id: String, val name: String, val note: String?)

    fun options(job: AiJob, kind: AiBackendKind): List<Option> = when (job) {
        AiJob.SPEECH -> listOf(
            Option("gpt-4o-mini-transcribe", "GPT-4o mini Transcribe", "The default. Gets brand names right"),
            Option("gpt-transcribe", "GPT Transcribe", null),
            Option("gpt-4o-transcribe", "GPT-4o Transcribe", null),
        )
        else -> when (kind) {
            AiBackendKind.OPENAI -> listOf(
                Option("gpt-6-luna", "GPT-6 Luna", if (job == AiJob.LOOKUP) "The default. The only one food lookups were tested on" else "The default"),
                Option("gpt-5.4-nano", "GPT-5.4 nano", null),
                Option("gpt-5.6-luna", "GPT-5.6 Luna", null),
                Option("gpt-5.4-mini", "GPT-5.4 mini", null),
                Option("gpt-5.6-sol", "GPT-5.6 Sol", "Slower and costs much more"),
            )
            AiBackendKind.ANTHROPIC -> listOf(
                Option("claude-haiku-5-5", "Claude Haiku 5.5", "The default"),
                Option("claude-haiku-4-5", "Claude Haiku 4.5", "Costs ten times as much"),
            )
        }
    }

    fun default(job: AiJob, kind: AiBackendKind): String = options(job, kind).first().id

    /** The chosen model when it belongs to this provider, otherwise the default. */
    fun pick(job: AiJob, kind: AiBackendKind, chosen: String?): String =
        chosen?.takeIf { c -> options(job, kind).any { it.id == c } } ?: default(job, kind)

    /** "0.010¢ in · 0.050¢ out per 1,000 tokens", or per minute for speech. */
    fun priceLine(id: String, job: AiJob): String {
        if (job == AiJob.SPEECH) return "${cents(PriceBook.audioCost(id, 60.0))} a minute of speech"
        val r = PriceBook.rateFor(id)
        return "${cents(r.input / 1000)} in · ${cents(r.output / 1000)} out per 1,000 tokens"
    }

    /** A typical call in cents: about 2,500 tokens in and 400 out. */
    fun typicalCall(id: String, job: AiJob): String =
        if (job == AiJob.SPEECH) cents(PriceBook.audioCost(id, 8.0)) + " for 8 seconds"
        else cents(PriceBook.tokenCost(id, 2500, 0, 400)) + " a typical call"

    fun cents(usd: Double): String {
        val c = usd * 100
        return when {
            c >= 1 -> "%.1f¢".format(java.util.Locale.ROOT, c)
            c >= 0.01 -> "%.2f¢".format(java.util.Locale.ROOT, c)
            else -> "%.3f¢".format(java.util.Locale.ROOT, c)
        }
    }
}
