package com.nutritionscribe.app.ui.exportimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.di.ServiceLocator
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun ExportImportScreen() {
    val manager = ServiceLocator.exportImportManager
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(ExportFormat.JSON) }
    var result by remember { mutableStateOf<TransferResult?>(null) }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            if (format == ExportFormat.JSON) "application/json" else "text/csv",
        ),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            result = manager.exportTo(uri, format)
            com.nutritionscribe.app.di.ServiceLocator.debugEvents.event("export", "result" to result.toString())
            busy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            result = manager.importFrom(uri)
            // An import that was never run looked, in the log, exactly like one that worked.
            com.nutritionscribe.app.di.ServiceLocator.debugEvents.event("import", "result" to result.toString())
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Everything lives on this phone and nowhere else. An export is the only " +
                "copy you will have.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )

        Text("Export as", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = format == ExportFormat.JSON,
                onClick = { format = ExportFormat.JSON },
                label = { Text("JSON") },
            )
            FilterChip(
                selected = format == ExportFormat.CSV,
                onClick = { format = ExportFormat.CSV },
                label = { Text("CSV") },
            )
        }
        Text(
            when (format) {
                ExportFormat.JSON -> "Keeps every nutrient. Use this to move to another phone."
                ExportFormat.CSV -> "Opens in a spreadsheet. Easier to read, same numbers."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                enabled = !busy,
                onClick = {
                    result = null
                    val stamp = LocalDate.now()
                    val extension = if (format == ExportFormat.JSON) "json" else "csv"
                    exportLauncher.launch("nutrition-scribe-$stamp.$extension")
                },
                modifier = Modifier.weight(1f),
            ) { Text("Export") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    result = null
                    // Anything, because the format is read from the file itself.
                    importLauncher.launch(arrayOf("*/*"))
                },
                modifier = Modifier.weight(1f),
            ) { Text("Import") }
        }
        Text(
            "Import reads either format — it works out which from the file. Entries " +
                "with the same id are replaced, so importing the same file twice is safe.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        // The whole point of this screen's rewrite: say what happened. It used to
        // discard the result, so a failed import and a successful one looked
        // identical — nothing at all.
        result?.let { outcome ->
            val failed = outcome is TransferResult.Failed
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (failed) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        when (outcome) {
                            is TransferResult.Exported -> "Exported"
                            is TransferResult.Imported -> "Imported"
                            is TransferResult.Failed -> "Nothing happened"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (failed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                    Text(
                        when (outcome) {
                            is TransferResult.Exported ->
                                "${plural(outcome.count)} written as ${outcome.format.name}. " +
                                    "Keep it somewhere that isn't this phone."
                            is TransferResult.Imported ->
                                "${plural(outcome.count)} read from a ${outcome.format.name} " +
                                    "file and added to your log."
                            is TransferResult.Failed -> outcome.message
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (failed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
            }
        }
    }
}

private fun plural(count: Int): String = if (count == 1) "1 entry" else "$count entries"
