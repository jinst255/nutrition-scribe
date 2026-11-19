package com.nutritionscribe.app.ui.confirm

/**
 * When Log it logs. Owner, 11-16: the card opens on the built-in list's quick guess
 * while the AI checks (a campus burger showed a candy bar, a drink-mix
 * packet showed raspberries and honey), and "if the user didn't know
 * what was actually going on, they'd very likely just hit log". Log it logged the
 * guess, and the AI's answer that came a few seconds later was thrown away.
 *
 * Now a tap while the AI is still checking waits for it and logs its answer the
 * moment it comes; nobody has to wait by the screen, and the guess is never logged
 * by accident. "Log these now" still logs what's shown. Once they've changed a
 * line by hand, their lines are what they mean, so it logs at once.
 */
object LogGate {
    enum class Tap { LOG_NOW, WAIT_FOR_AI }

    fun onTap(aiChecking: Boolean, handEdited: Boolean, editing: Boolean): Tap =
        if (aiChecking && !handEdited && !editing) Tap.WAIT_FOR_AI else Tap.LOG_NOW

    /** The AI's answer came: log it now when they asked to. */
    fun logOnArrival(waiting: Boolean, handEdited: Boolean): Boolean = waiting && !handEdited

    /** The button's words. */
    fun label(aiChecking: Boolean, handEdited: Boolean, waiting: Boolean, base: String): String = when {
        waiting -> "Logs when checked…"
        aiChecking && !handEdited && base == "Log it" -> "Log it when checked"
        else -> base
    }
}
