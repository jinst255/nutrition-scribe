package com.nutritionscribe.app.ui.settings

import com.nutritionscribe.app.network.openai.KeyCheck

/** What Settings' Check key says (review S1: Settings never checked a key; only setup did). */
object KeyCheckText {
    fun of(r: KeyCheck.Result): String = when (r) {
        KeyCheck.Result.Works -> "The key works."
        KeyCheck.Result.Refused -> "OpenAI doesn't know this key. Check for a typo, or make a new one."
        KeyCheck.Result.NoCredit -> "The key is real but has no credit or budget left."
        is KeyCheck.Result.Unreachable -> "Couldn't reach OpenAI to check. The key may still be fine."
    }
}
