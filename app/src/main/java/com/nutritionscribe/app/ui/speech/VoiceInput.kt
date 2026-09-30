package com.nutritionscribe.app.ui.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nutritionscribe.app.data.repository.MicMode
import com.nutritionscribe.app.data.speech.AudioCapture
import com.nutritionscribe.app.data.speech.OpenAiTranscriber
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.speech.ContinuousRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One spoken entry, from the first word to the finished text.
 *
 * Two engines behind one face:
 *  - the phone's own recogniser, free and on the phone, kept listening across
 *    pauses by [ContinuousRecognizer];
 *  - in Highest quality mode with an OpenAI key, a plain recording sent to
 *    OpenAI's speech model when the person stops — better with brand names and
 *    numbers, and it cannot cut anyone off because it does not listen for pauses
 *    at all.
 *
 * Either way, listening ends only when the person taps stop or lets go.
 */
class VoiceController(
    private val context: Context,
    private val scope: CoroutineScope,
    tag: String? = null,
    /** The words, and the tag their speech-to-text cost was recorded under. */
    private val onResult: (String, String) -> Unit,
) {
    sealed class State {
        data object Idle : State()
        data class Listening(val committed: String, val partial: String, val cloud: Boolean) : State()
        data object Transcribing : State()
        data class Failed(val message: String, val canRetry: Boolean) : State()
    }

    var state by mutableStateOf<State>(State.Idle)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var startedAt by mutableLongStateOf(0L)
        private set

    /** Groups this entry's AI calls (speech and lookup) for the cost of the entry. */
    var tag: String = tag ?: java.util.UUID.randomUUID().toString()
        private set
    /** No tag given: each recording is its own entry (the mic on Home is used again and again). */
    private val freshTagEachTime = tag == null

    private val events = ServiceLocator.debugEvents
    private var device: ContinuousRecognizer? = null
    private var capture: AudioCapture? = null
    private var lastRecording: ByteArray? = null
    private var mode: MicMode = MicMode.TAP

    val isActive: Boolean get() = state is State.Listening

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(mode: MicMode) {
        if (isActive || state is State.Transcribing) return
        this.mode = mode
        if (freshTagEachTime) tag = java.util.UUID.randomUUID().toString()
        startedAt = System.currentTimeMillis()
        val transcriber = ServiceLocator.cloudTranscriber()
        events.event("speech_start", "mode" to mode, "engine" to if (transcriber != null) "openai" else "device")
        if (transcriber != null) startCloud() else startDevice()
    }

    fun stop(reason: String) {
        if (!isActive) return
        events.event("speech_stop", "reason" to reason, "held_ms" to System.currentTimeMillis() - startedAt)
        val s = state as State.Listening
        if (s.cloud) stopCloud() else device?.stop()
    }

    fun cancel(reason: String = "cancel") {
        if (state is State.Idle) return
        events.event("speech_cancel", "reason" to reason)
        device?.cancel(); device = null
        capture?.cancel(); capture = null
        level = 0f
        state = State.Idle
    }

    /** Send the same recording again, after a failed upload. */
    fun retry() {
        val wav = lastRecording ?: return
        transcribe(wav)
    }

    fun dismissError() { if (state is State.Failed) state = State.Idle }

    private fun startDevice() {
        val r = ContinuousRecognizer(context, object : ContinuousRecognizer.Listener {
            override fun onText(committed: String, partial: String) {
                state = State.Listening(committed, partial, cloud = false)
            }
            override fun onLevel(level: Float) { this@VoiceController.level = level }
            override fun onFinished(text: String, stats: ContinuousRecognizer.Stats) {
                device = null
                level = 0f
                events.event("speech_result", "engine" to "device", "text" to text, "ms" to stats.millis,
                    "segments" to stats.segments, "restarts" to stats.restarts, "errors" to stats.errors)
                if (text.isBlank()) {
                    state = State.Failed("Didn't catch anything. Try again, a little closer to the phone.", canRetry = false)
                } else {
                    state = State.Idle
                    onResult(text, tag)
                }
            }
            override fun onFailed(code: Int, message: String) {
                device = null
                level = 0f
                state = State.Failed(message, canRetry = false)
            }
            override fun onEvent(name: String, detail: Map<String, Any?>) { events.event(name, detail) }
        })
        if (!r.isAvailable) {
            events.event("speech_unavailable")
            state = State.Failed("Speech recognition isn't available on this phone. Type it instead.", canRetry = false)
            return
        }
        device = r
        state = State.Listening("", "", cloud = false)
        r.start()
    }

    private fun startCloud() {
        val c = AudioCapture { level = it }
        if (!c.start()) {
            events.event("speech_record_failed")
            // The microphone may be busy; the phone's recogniser is the next best thing.
            startDevice()
            return
        }
        capture = c
        state = State.Listening("", "", cloud = true)
    }

    private fun stopCloud() {
        val wav = capture?.stop() ?: return
        capture = null
        level = 0f
        lastRecording = wav
        events.event("speech_recorded", "seconds" to AudioCapture.seconds(wav), "bytes" to wav.size)
        transcribe(wav)
    }

    private fun transcribe(wav: ByteArray) {
        val t = ServiceLocator.cloudTranscriber() ?: run {
            state = State.Failed("Highest quality mode is off, so there's nothing to send this to.", canRetry = false)
            return
        }
        state = State.Transcribing
        scope.launch {
            val r = withContext(Dispatchers.IO) { t.transcribe(wav, tag) }
            when (r) {
                is OpenAiTranscriber.Result.Text -> {
                    events.event("speech_result", "engine" to "openai", "text" to r.text, "seconds" to AudioCapture.seconds(wav))
                    state = State.Idle
                    onResult(r.text, tag)
                }
                is OpenAiTranscriber.Result.Failed -> {
                    events.event("speech_transcribe_failed", "message" to r.message)
                    state = State.Failed("${r.message}. Your recording is kept — try sending it again, or type it.", canRetry = true)
                }
            }
        }
    }
}

@Composable
fun rememberVoiceController(tag: String? = null, onResult: (text: String, tag: String) -> Unit): VoiceController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest = remember { mutableStateOf(onResult) }
    latest.value = onResult
    val c = remember { VoiceController(context.applicationContext, scope, tag) { t, g -> latest.value(t, g) } }
    DisposableEffect(Unit) { onDispose { c.cancel("left_screen") } }
    return c
}

/**
 * The big button. Tap mode: tap to start, tap to stop. Hold mode: press and hold
 * while talking, let go when done — a quick tap in hold mode explains itself
 * rather than recording half a word.
 */
@Composable
fun MicButton(
    controller: VoiceController,
    mode: MicMode,
    size: Dp = 112.dp,
    onHint: (String) -> Unit = {},
    /** Set for the dock's wide button: its own size, a rounded rectangle, and this caption under the icon. */
    modifier: Modifier? = null,
    caption: String? = null,
) {
    var askedOnce by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        ServiceLocator.debugEvents.event("mic_permission", "granted" to ok)
        if (!ok) onHint("The microphone is off for this app. Turn it on in Android settings, or type instead.")
        else if (mode == MicMode.TAP) controller.start(mode)
    }
    val listening = controller.isActive
    val busy = controller.state is VoiceController.State.Transcribing
    val pulse by animateFloatAsState(if (listening) 1f + controller.level * 0.18f else 1f, label = "pulse")
    val scheme = MaterialTheme.colorScheme
    val label = when {
        busy -> "Working out what you said"
        listening && mode == MicMode.TAP -> "Stop listening"
        listening -> "Recording — let go when you're done"
        mode == MicMode.HOLD -> "Hold to talk"
        else -> "Tap to talk"
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(modifier ?: Modifier.size(size))
            .scale(if (caption != null) 1f else pulse)
            .clip(if (caption != null) androidx.compose.foundation.shape.RoundedCornerShape(16.dp) else CircleShape)
            .background(if (listening) scheme.error else scheme.primary)
            .semantics {
                contentDescription = label
                onClick(label) {
                    if (mode == MicMode.TAP) { if (listening) controller.stop("tap") else controller.start(mode) }
                    true
                }
            }
            // Keyed on the mode only: restarting this while a finger is down (as a
            // key on "listening" would) cancels the hold the moment it begins.
            .pointerInput(mode) {
                detectTapGestures(
                    onPress = {
                        if (controller.state is VoiceController.State.Transcribing) return@detectTapGestures
                        if (!controller.hasPermission()) {
                            if (!askedOnce) { askedOnce = true }
                            permission.launch(Manifest.permission.RECORD_AUDIO)
                            return@detectTapGestures
                        }
                        when (mode) {
                            MicMode.TAP -> Unit
                            MicMode.HOLD -> {
                                val pressedAt = System.currentTimeMillis()
                                controller.start(mode)
                                val released = tryAwaitRelease()
                                val held = System.currentTimeMillis() - pressedAt
                                if (held < 450) {
                                    controller.cancel("too_short")
                                    onHint("Keep holding the button while you talk, and let go when you're done.")
                                } else {
                                    controller.stop(if (released) "release" else "drag_off")
                                }
                            }
                        }
                    },
                    onTap = {
                        if (mode == MicMode.TAP && controller.hasPermission()) {
                            if (controller.isActive) controller.stop("tap") else controller.start(mode)
                        }
                    },
                )
            },
    ) {
        val icon = if (listening && mode == MicMode.TAP) Icons.Filled.Stop else Icons.Filled.Mic
        val tint = if (listening) scheme.onError else scheme.onPrimary
        if (caption == null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.45f))
        } else {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // The level shows in the icon, since a wide button cannot pulse.
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp).scale(pulse))
                Text(caption, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
            }
        }
    }
}

/** Tap or hold, switchable right where it is used. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MicModeSwitch(mode: MicMode, onChange: (MicMode) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        MicMode.entries.forEachIndexed { i, m ->
            SegmentedButton(
                selected = m == mode,
                onClick = { onChange(m) },
                shape = SegmentedButtonDefaults.itemShape(i, MicMode.entries.size),
                label = { Text(if (m == MicMode.TAP) "Tap to talk" else "Hold to talk", maxLines = 1) },
            )
        }
    }
}

/**
 * What is being heard, as it is heard: the settled words in full colour, the
 * words still being worked out lighter, and a level meter so a silent microphone
 * is obvious straight away rather than after a minute of talking to nothing.
 */
@Composable
fun ListeningPanel(controller: VoiceController, mode: MicMode, modifier: Modifier = Modifier) {
    val state = controller.state
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state is VoiceController.State.Listening) {
        while (controller.isActive) {
            elapsed = System.currentTimeMillis() - controller.startedAt
            delay(200)
        }
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().heightIn(min = 120.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state) {
                is VoiceController.State.Listening -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LevelBars(controller.level)
                        Text(
                            "${if (state.cloud) "Recording" else "Listening"} · ${elapsed / 1000}s",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            if (state.cloud) "Highest quality" else "On this phone",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (state.cloud) {
                        Text(
                            "Take your time — pauses don't end it. The words appear when you ${if (mode == MicMode.TAP) "tap stop" else "let go"}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    } else if (state.committed.isBlank() && state.partial.isBlank()) {
                        Text(
                            "Say what you ate. Pauses are fine — ${if (mode == MicMode.TAP) "tap stop" else "let go"} when you're done.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    } else {
                        Text(
                            buildAnnotated(state.committed, state.partial),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                is VoiceController.State.Transcribing -> {
                    Text("Working out what you said…", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is VoiceController.State.Failed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.canRetry) {
                            androidx.compose.material3.Button(onClick = { controller.retry() }) { Text("Send again") }
                        }
                        androidx.compose.material3.TextButton(onClick = { controller.dismissError() }) { Text("OK") }
                    }
                }
                VoiceController.State.Idle -> Text(
                    if (mode == MicMode.TAP) "Tap the microphone and say what you ate. Tap again when you're done."
                    else "Hold the microphone while you say what you ate. Let go when you're done.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

@Composable
private fun buildAnnotated(committed: String, partial: String) = androidx.compose.ui.text.buildAnnotatedString {
    append(committed)
    if (partial.isNotBlank()) {
        if (committed.isNotBlank()) append(" ")
        pushStyle(androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.secondary))
        append(partial)
        pop()
    }
}

@Composable
private fun LevelBars(level: Float) {
    val color = MaterialTheme.colorScheme.error
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        val weights = listOf(0.5f, 0.8f, 1f, 0.7f, 0.4f)
        weights.forEach { w ->
            val h by animateFloatAsState((4f + 18f * (level * w).coerceIn(0f, 1f)), label = "bar")
            Box(Modifier.width(4.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

/**
 * Speaking again without leaving the screen: "say it again" on the check card,
 * or adding one more thing by voice.
 */
@Composable
fun VoiceDialog(
    title: String,
    tag: String?,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val mode by ServiceLocator.settingsRepository.micMode.collectAsState(initial = MicMode.TAP)
    var hint by remember { mutableStateOf<String?>(null) }
    val voice = rememberVoiceController(tag) { text, _ -> onResult(text) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { voice.cancel("dialog_dismissed"); onDismiss() },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = { voice.cancel("dialog_cancel"); onDismiss() }) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ListeningPanel(voice, mode)
                MicButton(voice, mode, size = 88.dp, onHint = { hint = it })
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
            }
        },
    )
}
