package com.proteinscribe.app.ui.unprocessed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.proteinscribe.app.data.repository.LogEntryResult
import com.proteinscribe.app.di.ServiceLocator
import com.proteinscribe.shared.model.UnprocessedEntry
import kotlinx.coroutines.launch

class UnprocessedViewModel : ViewModel() {
    private val repository = ServiceLocator.currentEntryRepository()
    val entries = repository.observeUnprocessed()

    fun retry(entry: UnprocessedEntry) {
        viewModelScope.launch {
            when (repository.retryUnprocessed(entry)) {
                is LogEntryResult.NeedsReview -> Unit
                is LogEntryResult.Failed -> Unit
            }
        }
    }

    fun discard(entry: UnprocessedEntry) {
        viewModelScope.launch { repository.discardUnprocessed(entry) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.clearUnprocessed() }
    }
}

@Composable
fun UnprocessedScreen(viewModel: UnprocessedViewModel = viewModel()) {
    val entries by viewModel.entries.collectAsState(initial = emptyList())

    var confirmingClear by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Needs attention", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (entries.isNotEmpty()) {
                TextButton(onClick = { confirmingClear = true }) { Text("Clear all") }
            }
        }
        if (entries.isEmpty()) {
            Text("Nothing needs attention.", modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.secondary)
        }
        LazyColumn {
            items(entries, key = { it.id }) { entry ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(entry.rawText)
                        Text(errorMessageFor(entry.errorCode), color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.retry(entry) }) { Text("Retry") }
                            OutlinedButton(onClick = { viewModel.discard(entry) }) { Text("Discard") }
                        }
                    }
                }
            }
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear all?") },
            text = { Text("These entries were never logged. Clearing deletes them for good.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    confirmingClear = false
                }) { Text("Clear all") }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("Cancel") } },
        )
    }
}

private fun errorMessageFor(code: String): String = when (code) {
    "NET_0012" -> "No connection. Err: NET_0012"
    "AUTH_0001" -> "Check your API key in Settings. Err: AUTH_0001"
    "PARSE_0001" -> "Couldn't understand the result. Err: PARSE_0001"
    else -> "Couldn't process that. Err: $code"
}
