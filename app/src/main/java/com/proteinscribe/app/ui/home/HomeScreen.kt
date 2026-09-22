package com.proteinscribe.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.proteinscribe.app.di.ServiceLocator
import com.proteinscribe.app.ui.common.MacroRing
import com.proteinscribe.app.ui.common.MacroRingRow
import com.proteinscribe.app.ui.common.timeLabel
import com.proteinscribe.shared.model.Entry
import kotlinx.coroutines.flow.map
import java.util.Calendar

class HomeViewModel : androidx.lifecycle.ViewModel() {
    private val repository = ServiceLocator.currentEntryRepository()
    private val settings = ServiceLocator.settingsRepository

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    val todayEntries = repository.observeEntries().map { all ->
        val start = startOfTodayMillis()
        all.filter { it.timestampEpochMillis >= start }
    }

    val todayTotal = repository.observeProteinTotal(startOfTodayMillis(), Long.MAX_VALUE)
    val dailyGoal = settings.dailyGoalGrams
    val unprocessedCount = repository.observeUnprocessed().map { it.size }
}

@Composable
fun HomeScreen(
    onAddEntry: () -> Unit,
    onOpenUnprocessed: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val entries by viewModel.todayEntries.collectAsState(initial = emptyList())
    val total by viewModel.todayTotal.collectAsState(initial = 0.0)
    val goal by viewModel.dailyGoal.collectAsState(initial = 160.0)
    val unprocessedCount by viewModel.unprocessedCount.collectAsState(initial = 0)

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddEntry) {
                Icon(Icons.Filled.Add, contentDescription = "Add entry")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (unprocessedCount > 0) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { onOpenUnprocessed() },
                ) {
                    val text = if (unprocessedCount == 1) {
                        "1 entry needs attention"
                    } else {
                        "$unprocessedCount entries need attention"
                    }
                    Text(
                        text,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // The rings own the top of the screen. Only protein is tracked today, but
            // MacroRingRow already resizes for more, so adding a macro later is a data
            // change rather than a layout rewrite.
            MacroRingRow(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                rings = listOf { ringSize ->
                    MacroRing(
                        value = total,
                        goal = goal,
                        label = "Protein",
                        unit = "g",
                        color = MaterialTheme.colorScheme.primary,
                        size = ringSize,
                    )
                },
            )

            val remaining = (goal - total).coerceAtLeast(0.0)
            Text(
                if (remaining > 0) {
                    "${remaining.toInt()}g left today · ~${(goal / 3).toInt()}g per meal"
                } else {
                    "Goal met · ${(total - goal).toInt()}g over"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Today",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    if (entries.size == 1) "1 entry" else "${entries.size} entries",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 24.dp),
                    ) {
                        Text("Nothing logged yet", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Tap + to add what you ate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    items(entries, key = { it.id }) { entry -> CompactEntryRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun CompactEntryRow(entry: Entry) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    entry.timeLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Text(
                "${entry.proteinGrams.toInt()}g",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
