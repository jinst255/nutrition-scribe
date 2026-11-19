package com.nutritionscribe.app.ui.feedback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nutritionscribe.app.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shake the phone to report a problem (owner, 2026-11-14). It replaced "press and
 * hold anything for a second", which sat in front of every touch and got in the
 * way of the app's own long presses, scrolling and hold-to-talk.
 *
 * Settings › Beta testing › "Shake to report a problem" turns it off (on by
 * default). The sensor is only listened to while the app is on screen, and never
 * while the mic is listening (talking while walking isn't a report).
 *
 * What is kept: the screen's name, the chips and words, and a screenshot, all in
 * the debug log on the phone until the log is sent.
 */
object ShakeReport {
    /** Set while the mic is listening. */
    @Volatile var voiceBusy: Boolean = false
    const val DEFAULT_ON = true

    /** Settings' tip under Beta testing: it must not say "shake" once shaking is off. */
    fun tip(shakeOn: Boolean): String =
        if (shakeOn) "shake your phone when something looks wrong, then tap what it was. It stays on your phone until you send your log."
        else "shaking is off, so use Something's wrong? Tell us above. Switch shaking on to report from any screen."

    /** The screen's name on a report. Home's title is the date, which says nothing in a report. */
    fun screenName(route: String?, title: String): String = when (route) {
        "home" -> "Home ($route)"
        else -> "$title ($route)"
    }
}

/**
 * Listens for a shake around [content] while [enabled]; a shake opens "What's
 * wrong here?" for the screen [screen] names.
 */
@Composable
fun ShakeToReport(
    screen: () -> String,
    enabled: Boolean,
    /** Setup's practice page: saves nothing, and calls this once the note is "saved". */
    practice: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    @Suppress("DEPRECATION") val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }
    var shot by remember { mutableStateOf<String?>(null) }
    val openNow by rememberUpdatedState(open)
    val screenNow by rememberUpdatedState(screen)

    DisposableEffect(enabled, lifecycle) {
        if (!enabled) return@DisposableEffect onDispose { }
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val detector = ShakeDetector()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (openNow != null || ShakeReport.voiceBusy) return
                if (!detector.onSample(e.values[0], e.values[1], e.values[2], e.timestamp / 1_000_000)) return
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                // The screen as it was, before the dialog covers it.
                val bmp = runCatching {
                    android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                        .also { view.draw(android.graphics.Canvas(it)) }
                }.getOrNull()
                shot = null
                if (bmp != null && practice == null) scope.launch { shot = withContext(Dispatchers.IO) { ServiceLocator.debugEvents.saveScan(bmp, prefix = "spot") } }
                open = screenNow()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        var listening = false
        fun listen(on: Boolean) {
            if (accel == null || on == listening) return
            listening = on
            if (on) sensors.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
            else sensors.unregisterListener(listener)
        }
        val observer = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> listen(true)
                Lifecycle.Event.ON_PAUSE -> listen(false)
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) listen(true)
        onDispose { lifecycle.removeObserver(observer); listen(false) }
    }

    content()

    open?.let { name ->
        ReportDialog(name, shot = { shot }, practice = practice != null, onDone = { saved ->
            open = null
            if (saved) practice?.invoke()
        })
    }
}

/** Kept so sheets read the same as before; a shake works over them without a wrapper. */
@Composable
fun SheetFeedback(@Suppress("UNUSED_PARAMETER") name: String, content: @Composable () -> Unit) = content()

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReportDialog(screen: String, shot: () -> String?, practice: Boolean = false, onDone: (saved: Boolean) -> Unit) {
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
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("What happened? (optional)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                Text(if (practice) "Practice: this one isn't saved." else "Saved on your phone with a screenshot. It goes with your log when you send it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (practice) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = {
            // "Save note", not "Send": nothing leaves the phone until the log is sent.
            TextButton(enabled = picked.isNotEmpty() || note.isNotBlank(), onClick = {
                if (practice) ServiceLocator.debugEvents.event("shake_practice_done", "reasons" to picked.toList())
                else ServiceLocator.debugEvents.always(
                    "spot_feedback", "screen" to screen, "how" to "shake",
                    "reasons" to picked.toList(), "note" to note.trim(), "shot" to shot(),
                )
                onDone(true)
            }) { Text("Save note") }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("Cancel") } },
    )
}

/** A bottom sheet (note S32: sheets are reportable; a shake covers them now too). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReportableSheet(
    @Suppress("UNUSED_PARAMETER") name: String,
    onDismissRequest: () -> Unit,
    sheetState: androidx.compose.material3.SheetState = androidx.compose.material3.rememberModalBottomSheetState(),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}
