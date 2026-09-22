package com.proteinscribe.app.ui.debuglog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.proteinscribe.app.di.ServiceLocator

@Composable
fun DebugLogScreen() {
    val context = LocalContext.current
    val logger = ServiceLocator.debugLogger
    var content by remember { mutableStateOf(logger.readAll()) }
    var confirmingClear by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { copyToClipboard(context, content) }, enabled = content.isNotBlank()) {
                Text("Copy to clipboard")
            }
            OutlinedButton(onClick = { confirmingClear = true }, enabled = content.isNotBlank()) {
                Text("Clear log")
            }
        }
        Text(
            content.ifBlank { "No errors logged yet." },
            modifier = Modifier.padding(top = 16.dp).verticalScroll(rememberScrollState()),
        )
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear debug log?") },
            text = { Text("This deletes all logged errors. Your food entries aren't affected.") },
            confirmButton = {
                TextButton(onClick = {
                    logger.clear()
                    content = logger.readAll()
                    confirmingClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("Cancel") } },
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Protein Scribe debug log", text))
}
