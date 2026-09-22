package com.nutritionscribe.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.R
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.common.MacroRing
import com.nutritionscribe.app.ui.common.MacroRingRow
import com.nutritionscribe.app.ui.common.timeLabel
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.GoalDirection
import com.nutritionscribe.shared.model.MetricGoal
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.util.Calendar

/** One tracked metric and where today stands against it. */
data class MetricToday(val goal: MetricGoal, val amount: Double) {
    val isOver: Boolean
        get() = goal.target != null &&
            goal.effectiveDirection == GoalDirection.LIMIT &&
            amount > goal.target!!
}

/** "42g left today" for a target to reach, "180mg to spare" for one to stay under. */
private fun summaryLine(item: MetricToday): String {
    val target = item.goal.target ?: return "${item.amount.toInt()}${item.goal.metric.unit} so far"
    val unit = item.goal.metric.unit
    val diff = target - item.amount
    return when {
        item.goal.effectiveDirection == GoalDirection.LIMIT && diff >= 0 ->
            "${diff.toInt()}$unit of ${item.goal.metric.label.lowercase()} to spare"
        item.goal.effectiveDirection == GoalDirection.LIMIT ->
            "${(-diff).toInt()}$unit over your ${item.goal.metric.label.lowercase()} limit"
        diff > 0 -> "${diff.toInt()}$unit left today · ~${(target / 3).toInt()}$unit per meal"
        else -> "Goal met · ${(-diff).toInt()}$unit over"
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    /**
     * Today's number for each metric the user actually tracks.
     *
     * Home used to show a protein ring and nothing else, no matter what was ticked
     * in Goals — so someone tracking only sugar saw a screen about a nutrient they
     * had switched off.
     */
    val trackedToday: kotlinx.coroutines.flow.Flow<List<MetricToday>> =
        ServiceLocator.goalsRepository.enabledGoals.flatMapLatest { goals ->
            if (goals.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(emptyList())
            } else {
                val start = startOfTodayMillis()
                combine(
                    goals.map { goal ->
                        repository.observeMetricTotal(goal.metric, start, Long.MAX_VALUE)
                            .map { amount -> MetricToday(goal, amount) }
                    },
                ) { it.toList() }
            }
        }
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
    val tracked by viewModel.trackedToday.collectAsState(initial = emptyList())

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

            // Rings for up to four tracked metrics; beyond that they would be too
            // small to read, so the rest appear as rows underneath.
            val ringed = tracked.take(4)
            val listed = tracked.drop(4)

            if (ringed.isEmpty()) {
                Text(
                    "Nothing is being tracked yet. Pick what matters to you in Goals.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            } else {
                MacroRingRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                    rings = ringed.map { item ->
                        { ringSize ->
                            MacroRing(
                                value = item.amount,
                                goal = item.goal.target,
                                label = item.goal.metric.label,
                                unit = item.goal.metric.unit,
                                // A LIMIT metric going over is a bad day, not a win,
                                // so it must not render in the same colour as hitting
                                // a protein target.
                                color = if (item.isOver) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                size = ringSize,
                            )
                        }
                    },
                )

                // A metric that has gone OVER its limit is the more urgent fact,
                // so it wins the summary line. Showing "577kcal left today" while
                // sodium sits at twice its limit buries the thing worth knowing.
                Text(
                    (tracked.firstOrNull { it.isOver } ?: tracked.firstOrNull())
                        ?.let { summaryLine(it) }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )

                listed.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(item.goal.metric.label, style = MaterialTheme.typography.bodySmall)
                        Text(
                            item.goal.target?.let { t ->
                                "${item.amount.toInt()} / ${t.toInt()} ${item.goal.metric.unit}"
                            } ?: "${item.amount.toInt()} ${item.goal.metric.unit}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.isOver) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                        )
                    }
                }
                Spacer(Modifier.padding(bottom = 8.dp))
            }

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
                        Image(
                            painter = painterResource(R.drawable.ic_empty_day),
                            contentDescription = null,
                            modifier = Modifier.size(96.dp).padding(bottom = 12.dp),
                        )
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
