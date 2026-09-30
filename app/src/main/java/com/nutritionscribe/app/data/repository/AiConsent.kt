package com.nutritionscribe.app.data.repository

/**
 * What the user is told before anything of theirs leaves the phone for an AI
 * provider, one provider at a time: agreeing to OpenAI says nothing about
 * Anthropic, whose promises are different.
 *
 * The provider statements are their published API terms as of 2026-09-29:
 *   - OpenAI, "Data controls in the OpenAI platform"
 *     (developers.openai.com/api/docs/guides/your-data): API data is not used to
 *     train models unless the account opts in; abuse-monitoring logs are kept up
 *     to 30 days, longer if the law requires it.
 *   - Anthropic, "API and data retention" and the Privacy Center's commercial
 *     retention article (privacy.claude.com/en/articles/7996866): API inputs and
 *     outputs are not used for training and are deleted within 30 days; content
 *     flagged under the Usage Policy up to 2 years; longer where the law says.
 * Bump [VERSION] whenever these or what the app sends change, and everyone is
 * asked again.
 */
object AiConsent {
    const val VERSION = 1

    data class Provider(
        val kind: AiBackendKind,
        val name: String,
        /** What goes to this provider from this app. */
        val sends: List<String>,
        val training: String,
        val retention: String,
        val policyUrl: String,
    )

    fun provider(kind: AiBackendKind): Provider = when (kind) {
        AiBackendKind.OPENAI -> Provider(
            kind, "OpenAI",
            sends = listOf(
                "The words you say or type to log food (from the phone or the watch), when the phone can't match them itself",
                "Recordings of your voice when you use the microphone, so OpenAI can turn them into text",
            ),
            training = "OpenAI says it does not use data sent through its API to train its models unless the account owner opts in.",
            retention = "OpenAI says it keeps API requests for up to 30 days to watch for abuse, and longer when the law requires it or it's needed to protect its services or anyone else from harm.",
            policyUrl = "https://developers.openai.com/api/docs/guides/your-data",
        )
        AiBackendKind.ANTHROPIC -> Provider(
            kind, "Anthropic",
            sends = listOf(
                "The words you say or type to log food (from the phone or the watch), when the phone can't match them itself",
            ),
            training = "Anthropic says it does not train its models on data sent through its API.",
            retention = "Anthropic says it deletes API requests within 30 days. Requests it flags under its usage policy can be kept for up to 2 years, and anything the law requires it to keep is kept longer.",
            policyUrl = "https://platform.claude.com/docs/en/manage-claude/api-and-data-retention",
        )
    }

    /** The honest caveat, the same for every provider. */
    const val CAVEAT =
        "These are the provider's own promises. Nutrition Scribe can't check that they keep them, " +
            "and any company can have a data breach. Nothing is sent until you agree, and you can " +
            "switch this off in Settings at any time. Without it the app looks food up on your phone only."

    fun agreed(consent: Set<String>, kind: AiBackendKind): Boolean = "${kind.name}:$VERSION" in consent

    /** Answered either way at this version. */
    fun answered(consent: Set<String>, kind: AiBackendKind): Boolean =
        agreed(consent, kind) || "${kind.name}:no:$VERSION" in consent

    /**
     * The providers this setup would send something to that haven't been asked
     * yet: the lookup provider, and OpenAI again for speech, which only it does.
     */
    fun pending(consent: Set<String>, aiOn: Boolean, backend: AiBackendKind, hasKey: (AiBackendKind) -> Boolean): List<AiBackendKind> {
        if (!aiOn) return emptyList()
        val used = buildList {
            if (hasKey(backend)) add(backend)
            if (hasKey(AiBackendKind.OPENAI) && AiBackendKind.OPENAI !in this) add(AiBackendKind.OPENAI)
        }
        return used.filterNot { answered(consent, it) }
    }
}
