package com.nutritionscribe.app.ui.exportimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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

@Composable
fun ExportImportScreen() {
    val manager = ServiceLocator.exportImportManager
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(ExportFormat.JSON) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(if (format == ExportFormat.JSON) "application/json" else "text/csv"),
    ) { uri -> uri?.let { scope.launch { manager.exportTo(it, format) } } }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { manager.importFrom(it, format) } }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Format")
        Row {
            FilterChip(
                selected = format == ExportFormat.CSV,
                onClick = { format = ExportFormat.CSV },
                label = { Text("CSV") },
            )
            FilterChip(
                selected = format == ExportFormat.JSON,
                onClick = { format = ExportFormat.JSON },
                label = { Text("JSON") },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(modifier = Modifier.padding(top = 24.dp)) {
            Button(onClick = {
                val name = if (format == ExportFormat.JSON) "protein-scribe-export.json" else "protein-scribe-export.csv"
                exportLauncher.launch(name)
            }) { Text("Export") }
            Button(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.padding(start = 8.dp)) {
                Text("Import")
            }
        }
        Text("Import accepts files exported from this app.", modifier = Modifier.padding(top = 16.dp))
    }
}
