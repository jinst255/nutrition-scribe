package com.nutritionscribe.app.ui.debuglog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.nutritionscribe.app.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug mode's switch, and the way the file gets off the phone.
 *
 * "Save to Downloads" puts it where it can be pulled over USB or opened from
 * Files; "Share" hands it to anything — a message, an email, Drive. The plain
 * error log (always on, errors only) is included at the end of the file.
 */
@Composable
fun DebugLogScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = ServiceLocator.settingsRepository
    val events = ServiceLocator.debugEvents
    val logger = ServiceLocator.debugLogger
    val debugMode by settings.debugMode.collectAsState(initial = false)
    var errors by remember { mutableStateOf(logger.readAll()) }
    var lines by remember { mutableIntStateOf(0) }
    var bytes by remember { mutableStateOf(0L) }
    var preview by remember { mutableStateOf<List<String>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmingClear by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var scans by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh, debugMode) {
        withContext(Dispatchers.IO) {
            val p = events.tail(30)
            val n = events.lineCount()
            val b = events.sizeBytes()
            val sc = events.scanCount()
            withContext(Dispatchers.Main) { preview = p; lines = n; bytes = b; errors = logger.readAll(); scans = sc }
        }
    }

    suspend fun export(): java.io.File = withContext(Dispatchers.IO) {
        ServiceLocator.buildDebugExport()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Debug mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Records what you say and type, what the app looked up, what you changed, label scans (with the photo), " +
                        "AI calls and errors. It stays on this phone until you save or share it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Switch(checked = debugMode, onCheckedChange = { on ->
                scope.launch {
                    settings.setDebugMode(on)
                    events.enabled = on
                    if (on) events.event("debug_mode_on", ServiceLocator.appSnapshot())
                    refresh++
                }
            })
        }

        Text(
            "$lines events · ${"%.1f".format(bytes / 1_000_000.0)} MB" +
                (if (scans > 0) " · $scans label photos" else "") +
                if (errors.isNotBlank()) " · plus the error log" else "",
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            "The saved file also has your last 45 days of entries as stored, your saved foods, your settings, " +
                "and why the app last closed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        // A note at the moment something looks off is what makes it findable later.
        var note by remember { mutableStateOf("") }
        androidx.compose.material3.OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note something that went wrong") },
            placeholder = { Text("e.g. the scan read sugar as 49 g") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(enabled = note.isNotBlank(), onClick = {
                    events.always("user_note", "note" to note.trim())
                    note = ""
                    message = "Noted. It's in the log with the time, so it can be matched to what happened."
                    refresh++
                }) { Text("Add") }
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Button(
                enabled = true,
                onClick = {
                    scope.launch {
                        val f = export()
                        val uri = withContext(Dispatchers.IO) { runCatching { events.saveToDownloads(f) }.getOrNull() }
                        message = if (uri != null) "Saved to Downloads as ${f.name}" else "Couldn't save to Downloads on this phone. Use Share instead."
                    }
                },
            ) { Text("Save to Downloads") }
            OutlinedButton(
                enabled = true,
                onClick = {
                    scope.launch { LogShare.share(context, "debug_log_screen") }
                },
            ) { Text("Share") }
            TextButton(onClick = { confirmingClear = true }, enabled = lines > 0 || errors.isNotBlank()) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        HorizontalDivider()
        Text("Latest events", style = MaterialTheme.typography.titleSmall)
        if (preview.isEmpty()) {
            Text(
                if (debugMode) "Nothing recorded yet. Log something and come back." else "Debug mode is off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    preview.asReversed().forEach { line ->
                        Text(
                            readable(line),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Error log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { copyToClipboard(context, errors) }, enabled = errors.isNotBlank()) { Text("Copy") }
        }
        Text(
            errors.ifBlank { "No errors logged." },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
        )
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear the debug log?") },
            text = { Text("This deletes the recorded events and the error log. Your food entries aren't affected.") },
            confirmButton = {
                TextButton(onClick = {
                    events.clear()
                    logger.clear()
                    confirmingClear = false
                    message = "Cleared."
                    refresh++
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("Cancel") } },
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Nutrition Scribe error log", text))
}

/** "17:50:42  resolution  a banana" — the file keeps everything; the screen shows the gist. */
private fun readable(line: String): String = runCatching {
    val o = kotlinx.serialization.json.Json.parseToJsonElement(line) as kotlinx.serialization.json.JsonObject
    fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val time = str("t")?.substringAfter('T')?.take(8).orEmpty()
    val gist = listOf("note", "text", "name", "route", "message", "reason", "kind", "setting", "where").firstNotNullOfOrNull { str(it) }.orEmpty()
    "$time  ${str("type")}  ${gist.take(60)}"
}.getOrDefault(line.take(120))
