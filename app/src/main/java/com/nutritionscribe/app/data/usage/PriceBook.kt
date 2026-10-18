package com.nutritionscribe.app.data.usage

/**
 * List prices, in US dollars, as published by each provider.
 *
 * Checked 2026-10-17 against OpenAI's GPT-6 Sol and Luna announcement and
 * Anthropic's model table (Haiku 5.5's rate is for prompts up to 100K tokens,
 * which every call here is). Providers change these; the Spending screen says the
 * figures are estimates and names this date. A model missing from the table is
 * costed at the most expensive rate the app uses, so an unknown model can make
 * the estimate too high but never quietly zero.
 */
object PriceBook {
    const val CHECKED = "October 2026"

    /** Per million tokens. */
    data class TokenRate(val input: Double, val cachedInput: Double, val output: Double)

    private val tokenRates = mapOf(
        "gpt-5.6-luna" to TokenRate(0.20, 0.02, 1.20),
        "gpt-6-luna" to TokenRate(0.10, 0.01, 0.50),
        "gpt-5.6-sol" to TokenRate(2.00, 0.20, 10.00),
        "gpt-5.6-terra" to TokenRate(2.00, 0.20, 12.00),
        "gpt-5.4-mini" to TokenRate(0.75, 0.075, 4.50),
        "gpt-5.4-nano" to TokenRate(0.20, 0.02, 1.25),
        "claude-haiku-5-5" to TokenRate(0.10, 0.01, 0.50),
        "claude-haiku-4-5" to TokenRate(1.00, 0.10, 5.00),
    )

    /** Speech is billed by the minute of audio. */
    private val perMinute = mapOf(
        "gpt-4o-mini-transcribe" to 0.003,
        "gpt-transcribe" to 0.0045,
        "gpt-4o-transcribe" to 0.006,
        "whisper-1" to 0.006,
    )

    private val FALLBACK = TokenRate(2.00, 0.20, 12.00)

    fun rateFor(model: String): TokenRate =
        tokenRates[model] ?: tokenRates.entries.firstOrNull { model.startsWith(it.key) }?.value ?: FALLBACK

    fun isKnown(model: String): Boolean =
        tokenRates.containsKey(model) || perMinute.containsKey(model) ||
            tokenRates.keys.any { model.startsWith(it) } || perMinute.keys.any { model.startsWith(it) }

    /** Cached tokens are part of the input count on OpenAI, so they are charged at the cached rate instead. */
    fun tokenCost(model: String, input: Long, cachedInput: Long, output: Long): Double {
        val r = rateFor(model)
        val uncached = (input - cachedInput).coerceAtLeast(0)
        return (uncached * r.input + cachedInput * r.cachedInput + output * r.output) / 1_000_000.0
    }

    fun audioCost(model: String, seconds: Double): Double =
        (perMinute[model] ?: perMinute.entries.firstOrNull { model.startsWith(it.key) }?.value ?: 0.006) * seconds / 60.0

    /** The table as shown on the Spending screen. */
    fun rows(): List<Pair<String, String>> =
        tokenRates.filterKeys { it == "gpt-6-luna" || it == "claude-haiku-5-5" }.map { (m, r) ->
            m to "\$${fmt(r.input)} in · \$${fmt(r.output)} out per 1M tokens"
        } + ("gpt-4o-mini-transcribe" to "\$${fmt(perMinute.getValue("gpt-4o-mini-transcribe"))} per minute of speech")

    private fun fmt(v: Double) = if (v >= 0.1) "%.2f".format(java.util.Locale.ROOT, v) else "%.3f".format(java.util.Locale.ROOT, v)
}
