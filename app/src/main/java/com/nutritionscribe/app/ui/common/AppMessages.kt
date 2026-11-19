package com.nutritionscribe.app.ui.common

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Short messages shown as a snackbar at the bottom of the app (review L4). They
 * replace nine Toasts: a Toast can't be read again or undone, TalkBack often
 * misses it, and on Android 12+ it's cut to two lines beside the app icon.
 */
object AppMessages {
    data class Message(
        val text: String,
        val long: Boolean = false,
        /** "Undo" (or another word) and what it does; null for a plain note. */
        val action: String? = null,
        val onAction: (() -> Unit)? = null,
    )

    private val flow = MutableSharedFlow<Message>(extraBufferCapacity = 8)
    val messages: SharedFlow<Message> = flow

    fun say(text: String, long: Boolean = false) { flow.tryEmit(Message(text, long)) }

    fun sayWithAction(text: String, action: String, onAction: () -> Unit) {
        flow.tryEmit(Message(text, long = true, action = action, onAction = onAction))
    }

    /** "Logged again" with an Undo that removes what was just added. */
    fun logged(text: String, added: List<com.nutritionscribe.shared.model.Entry>) {
        if (added.isEmpty()) return say(text)
        sayWithAction(text, "Undo") {
            com.nutritionscribe.app.di.ServiceLocator.backgroundScope.launch {
                added.forEach { com.nutritionscribe.app.di.ServiceLocator.logPipeline.delete(it) }
            }
        }
    }
}
