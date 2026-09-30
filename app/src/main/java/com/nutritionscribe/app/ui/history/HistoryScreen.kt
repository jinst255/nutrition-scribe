package com.nutritionscribe.app.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.common.ManualEntryInput
import com.nutritionscribe.app.ui.common.ManualEntrySheet
import com.nutritionscribe.app.ui.common.resolveTimestamp
import com.nutritionscribe.app.ui.common.timeLabel
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Shared by the History tab and the full-size chart it opens. */
class HistoryViewModel : ViewModel() {
    private val repository = ServiceLocator.currentEntryRepository()

    val searchQuery = MutableStateFlow("")
    val allEntries = repository.observeEntries()
    val trackedGoals = ServiceLocator.goalsRepository.enabledGoals
    val allGoals = ServiceLocator.goalsRepository.goals
    val frequentFoods = repository.observeFrequentFoods()

    val filteredEntries = combine(allEntries, searchQuery) { entries, query ->
        if (query.isBlank()) entries else entries.filter {
            it.name.contains(query, ignoreCase = true) || it.rawText.contains(query, ignoreCase = true)
        }
    }

    fun deleteMany(ids: List<String>) {
        viewModelScope.launch { repository.deleteMany(ids) }
    }

    /** Adds an entry onto an arbitrary past day (the per-day + button). */
    fun addTo(date: LocalDate, input: ManualEntryInput) {
        val (timestamp, timeUnspecified) = input.resolveTimestamp(date)
        viewModelScope.launch {
            repository.logManual(
                name = input.name,
                proteinGrams = input.proteinGrams,
                calories = input.calories,
                carbsGrams = input.carbsGrams,
                fatGrams = input.fatGrams,
                timestampEpochMillis = timestamp,
                timeUnspecified = timeUnspecified,
            )
        }
    }
}

internal fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> if (date.year == today.year) {
            date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
        } else {
            date.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
        }
    }
}

private sealed interface HistoryListItem {
    data class Header(val date: LocalDate, val count: Int) : HistoryListItem
    data class EntryItem(val entry: Entry) : HistoryListItem
}

/** How many days the cards summarise. A month: long enough to be a trend, short enough to be current. */
private const val CARD_DAYS = 30

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onOpenEntry: (String) -> Unit = {},
    onOpenMetric: (Metric) -> Unit = {},
    viewModel: HistoryViewModel = viewModel(),
) {
    val allEntries by viewModel.allEntries.collectAsState(initial = emptyList())
    val entries by viewModel.filteredEntries.collectAsState(initial = emptyList())
    val goals by viewModel.trackedGoals.collectAsState(initial = emptyList())
    val frequentFoods by viewModel.frequentFoods.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selecting = selectedIds.isNotEmpty()
    var addingToDate by remember { mutableStateOf<LocalDate?>(null) }

    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    // The number shown beside each entry: whatever the user tracks first. It
    // used to be protein regardless, even for someone who only tracks sugar.
    val lead = goals.firstOrNull()?.metric ?: Metric.PROTEIN

    val listItems = remember(entries) {
        val grouped = entries.groupBy { Instant.ofEpochMilli(it.timestampEpochMillis).atZone(zone).toLocalDate() }
        buildList {
            grouped.forEach { (date, dayEntries) ->
                add(HistoryListItem.Header(date, dayEntries.size))
                dayEntries.forEach { add(HistoryListItem.EntryItem(it)) }
            }
        }
    }

    // Always from the FULL history, never the search-filtered list: search
    // narrows the list, it must not rewrite the charts.
    val cards = remember(allEntries, goals) {
        goals.map { goal ->
            val days = MetricHistory.daily(allEntries, goal.metric, zone, today)
            goal to MetricHistory.lastDays(days, CARD_DAYS)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; viewModel.searchQuery.value = it },
            placeholder = { Text("Search entries") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; viewModel.searchQuery.value = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        if (selecting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${selectedIds.size} selected", style = MaterialTheme.typography.titleSmall)
                Row {
                    IconButton(onClick = { selectedIds = entries.map { it.id }.toSet() }) {
                        Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                    }
                    IconButton(onClick = { viewModel.deleteMany(selectedIds.toList()); selectedIds = emptySet() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                    IconButton(onClick = { selectedIds = emptySet() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                    }
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // The cards scroll away with the list rather than pinning to the top:
            // with four metrics they are half a screen, and the list below is
            // what most visits are for.
            if (query.isBlank() && cards.isNotEmpty()) {
                val rows = cards.chunked(2)
                items(rows.size, key = { "cards_$it" }) { i ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rows[i].forEach { (goal, days) ->
                            MetricCard(
                                goal = goal,
                                days = days,
                                onClick = { onOpenMetric(goal.metric) },
                                // A lone card in the last row takes the full width
                                // instead of sitting beside a hole.
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            if (listItems.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (query.isBlank()) "Nothing logged yet." else "No entries match “$query”.",
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            items(
                count = listItems.size,
                key = { index ->
                    when (val item = listItems[index]) {
                        is HistoryListItem.Header -> "header_${item.date}"
                        is HistoryListItem.EntryItem -> item.entry.id
                    }
                },
            ) { index ->
                when (val item = listItems[index]) {
                    is HistoryListItem.Header -> DayHeader(
                        date = item.date,
                        count = item.count,
                        onAdd = { addingToDate = item.date },
                    )
                    is HistoryListItem.EntryItem -> EntryRow(
                        entry = item.entry,
                        lead = lead,
                        zone = zone,
                        selecting = selecting,
                        selected = item.entry.id in selectedIds,
                        onClick = {
                            val id = item.entry.id
                            if (selecting) {
                                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                            } else {
                                onOpenEntry(id)
                            }
                        },
                        onLongClick = { selectedIds = selectedIds + item.entry.id },
                    )
                }
            }
        }
    }

    addingToDate?.let { date ->
        ManualEntrySheet(
            title = "Add to ${dayLabel(date)}",
            frequentFoods = frequentFoods,
            // Backdating defaults to no time rather than "now on that date", which would
            // write a timestamp the user never reported.
            defaultTime = null,
            onDismiss = { addingToDate = null },
            onSave = { input ->
                viewModel.addTo(date, input)
                addingToDate = null
            },
        )
    }
}

/**
 * One tracked metric: its month average, how that average stands against the
 * goal, and the month as a sparkline. Tapping opens the full chart.
 */
@Composable
private fun MetricCard(
    goal: MetricGoal,
    days: List<DayValue>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val average = MetricHistory.average(days)
    val standing = average?.let { MetricHistory.standing(goal, it) } ?: Standing.NO_GOAL
    val colors = standingColors()

    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)) {
            Text(
                goal.metric.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    average?.let { formatAmount(it, goal.metric, withUnit = false) } ?: "–",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    " ${goal.metric.unit}/day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                standingPhrase(standing, goal),
                style = MaterialTheme.typography.labelSmall,
                color = colors.of(standing),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Sparkline(
                days = days,
                goal = goal,
                color = colors.of(standing),
                modifier = Modifier.fillMaxWidth().height(34.dp).padding(top = 6.dp),
            )
        }
    }
}

/** A short verdict for the month, in the goal's own direction. */
internal fun standingPhrase(standing: Standing, goal: MetricGoal): String {
    val target = goal.target?.let { formatAmount(it, goal.metric) }
    return when (standing) {
        Standing.MET -> "on target · $target"
        Standing.SHORT -> if (goal.effectiveDirection == com.nutritionscribe.shared.model.GoalDirection.LIMIT) {
            "a little over · $target"
        } else {
            "under · $target"
        }
        Standing.MISSED -> if (goal.effectiveDirection == com.nutritionscribe.shared.model.GoalDirection.LIMIT) {
            "over · $target"
        } else {
            "well under · $target"
        }
        Standing.NO_GOAL -> "30-day average"
    }
}

@Composable
private fun DayHeader(date: LocalDate, count: Int, onAdd: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(dayLabel(date), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (count == 1) "1 entry" else "$count entries",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                IconButton(onClick = onAdd, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.AddCircleOutline,
                        contentDescription = "Add an entry to ${dayLabel(date)}",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: Entry,
    lead: Metric,
    zone: ZoneId,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        }
        Column(modifier = Modifier.padding(start = if (selecting) 0.dp else 4.dp).weight(1f)) {
            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                entry.timeLabel(zone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Text(
            entry.amountOf(lead)?.let { formatAmount(it, lead) } ?: "–",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
        )
    }
}
