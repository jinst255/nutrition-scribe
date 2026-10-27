package com.nutritionscribe.app.ui.feedback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug mode only: press and hold anything for a second and a bit, and say what
 * is wrong with it. Testers will not write an essay in a form, but they will hold
 * a finger on the number that looks off.
 *
 * The hold is watched before the screen's own handlers see it, so it works on
 * buttons and rows too, and nothing is taken from them: a tap is still a tap, and
 * the app's own long presses (0.4 s) fire long before this one (1.2 s). Holding
 * the mic to talk never counts.
 *
 * What is sent: the screen, where the finger was, the chips and words, and a
 * picture of the screen with the spot marked, all into the debug log.
 */
object HoldFeedback {
    /** Set while the mic is held: a long hold there is talking, not feedback. */
    @Volatile var voiceBusy: Boolean = false
    /** Set while a press that began on a hold-to-talk button is down (see [holdFeedbackExempt]). */
    @Volatile var exemptPress: Boolean = false
    const val HOLD_MS = 1200L
    private const val RING_FROM_MS = 450L
    internal const val RING_SPAN = (HOLD_MS - RING_FROM_MS).toFloat()
    internal const val RING_START = RING_FROM_MS
}

@Composable
fun HoldToFeedback(
    screen: () -> String,
    modifier: Modifier = Modifier.fillMaxSize(),
    /** Setup's practice card: works with debug mode off, saves nothing, and calls this once the note is "saved". */
    practice: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val debugOn by ServiceLocator.settingsRepository.debugMode.collectAsState(initial = false)
    val debug = debugOn || practice != null
    val haptic = LocalHapticFeedback.current
    var spot by remember { mutableStateOf<Offset?>(null) }
    var ring by remember { mutableStateOf<Offset?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    val view = LocalView.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var shot by remember { mutableStateOf<String?>(null) }

    Box(
        modifier.then(
            if (!debug) Modifier else Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val start = down.uptimeMillis
                    var held = false
                    while (true) {
                        // Woken every 40 ms while the finger rests, so the ring can grow.
                        val ev = withTimeoutOrNull(40) { awaitPointerEvent(PointerEventPass.Initial) }
                        if (ev != null) {
                            val c = ev.changes.firstOrNull { it.id == down.id }
                            if (c == null || !c.pressed || ev.changes.size > 1 ||
                                (c.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) break
                        }
                        // Pointer times are on the uptime clock.
                        val elapsed = android.os.SystemClock.uptimeMillis() - start
                        if (HoldFeedback.voiceBusy || HoldFeedback.exemptPress) break
                        if (elapsed >= HoldFeedback.RING_START) {
                            ring = down.position
                            progress = ((elapsed - HoldFeedback.RING_START) / HoldFeedback.RING_SPAN).coerceIn(0f, 1f)
                        }
                        if (elapsed >= HoldFeedback.HOLD_MS) { held = true; break }
                    }
                    ring = null; progress = 0f
                    if (held) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val at = down.position
                        spot = at
                        // The screen as it was, the spot circled, before the dialog covers it.
                        val w = view.width; val h = view.height
                        val bmp = runCatching {
                            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                                val c = android.graphics.Canvas(b)
                                view.draw(c)
                                val p = android.graphics.Paint().apply {
                                    color = android.graphics.Color.RED; style = android.graphics.Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true
                                }
                                c.drawCircle(at.x, at.y, 60f, p)
                            }
                        }.getOrNull()
                        shot = null
                        if (bmp != null && practice == null) scope.launch { shot = withContext(Dispatchers.IO) { ServiceLocator.debugEvents.saveScan(bmp, prefix = "spot") } }
                        // The rest of this press is ours: lifting the finger must not tap.
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            ev.changes.forEach { it.consume() }
                        } while (ev.changes.any { it.pressed })
                    }
                }
            },
        ),
    ) {
        content()
        ring?.let { at ->
            val color = MaterialTheme.colorScheme.primary
            Canvas(Modifier.matchParentSize()) {
                drawArc(color, -90f, 360f * progress, useCenter = false,
                    topLeft = Offset(at.x - 70f, at.y - 70f), size = androidx.compose.ui.geometry.Size(140f, 140f), style = Stroke(width = 10f))
            }
        }
    }

    spot?.let { at ->
        SpotDialog(screen(), at, shot = { shot }, practice = practice != null, onDone = { saved ->
            spot = null
            if (saved) practice?.invoke()
        })
    }
}

/**
 * Hold-to-report inside a bottom sheet (note S32). A sheet is its own window, so
 * the hold watcher around the screen never sees a press on it.
 */
@Composable
fun SheetFeedback(name: String, content: @Composable () -> Unit) =
    HoldToFeedback(screen = { "$name (sheet)" }, modifier = Modifier.fillMaxWidth(), content = content)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SpotDialog(screen: String, at: Offset, shot: () -> String?, practice: Boolean = false, onDone: (saved: Boolean) -> Unit) {
    val reasons = listOf("Wrong number", "Wrong food", "Confusing", "Looks off", "Missing something", "Too slow", "Hard to tap")
    var picked by remember { mutableStateOf(setOf<String>()) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text("What's wrong here?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("On ${screen.substringBefore(" (")}. A tap or a word is plenty.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    reasons.forEach { r ->
                        FilterChip(selected = r in picked, onClick = { picked = if (r in picked) picked - r else picked + r }, label = { Text(r) })
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Anything else? (optional)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                Text(if (practice) "Practice: this one isn't saved." else "Saved on your phone. It goes with your log when you send it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (practice) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = {
            // "Save note", not "Send": nothing leaves the phone until the log is sent.
            TextButton(enabled = picked.isNotEmpty() || note.isNotBlank(), onClick = {
                if (practice) ServiceLocator.debugEvents.event("hold_practice_done", "reasons" to picked.toList())
                else ServiceLocator.debugEvents.always(
                    "spot_feedback", "screen" to screen, "x" to at.x.toInt(), "y" to at.y.toInt(),
                    "reasons" to picked.toList(), "note" to note.trim(), "shot" to shot(),
                )
                onDone(true)
            }) { Text("Save note") }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("Cancel") } },
    )
}

/**
 * A control whose long hold means something already (hold to talk): a press that
 * starts on it is never feedback, granted the mic or not.
 */
fun Modifier.holdFeedbackExempt(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        HoldFeedback.exemptPress = true
        try {
            do {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
            } while (ev.changes.any { it.pressed })
        } finally {
            HoldFeedback.exemptPress = false
        }
    }
}

/** A bottom sheet that takes hold-to-report like any screen (note S32). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReportableSheet(
    name: String,
    onDismissRequest: () -> Unit,
    sheetState: androidx.compose.material3.SheetState = androidx.compose.material3.rememberModalBottomSheetState(),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        SheetFeedback(name) { Column(Modifier.fillMaxWidth()) { content() } }
    }
}
