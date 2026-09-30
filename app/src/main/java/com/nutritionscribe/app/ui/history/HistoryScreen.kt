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
import androidx.compose.material.icons.filled.Replay
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
        ServiceLocator.debugEvents.event("entries_deleted", "where" to "history", "ids" to ids)
        viewModelScope.launch { repository.deleteMany(ids) }
    }

    /** The selected entries again, now, oldest first so they keep their order. */
    fun logAgain(entries: List<com.nutritionscribe.shared.model.Entry>, onDone: (Int) -> Unit) {
        ServiceLocator.debugEvents.event("log_again_tap", "where" to "history", "ids" to entries.map { it.id })
        viewModelScope.launch {
            entries.sortedBy { it.timestampEpochMillis }.forEach { ServiceLocator.logPipeline.logAgain(it) }
            onDone(entries.size)
        }
    }

}

/**
 * What a chart compares against. The user's own goal when they have one; for a
 * micronutrient, what they need (see Needs); for any other macro, nothing — a
 * carbs line nobody asked for would read as a verdict.
 */
internal fun chartGoal(
    metric: Metric,
    goals: List<MetricGoal>,
    refs: Map<Metric, Double> = com.nutritionscribe.shared.model.Needs.forProfile(ServiceLocator.profileNow()),
): MetricGoal {
    val g = goals.firstOrNull { it.metric == metric } ?: MetricGoal.default(metric)
    if (g.enabled) return g
    // Not a goal: only lines the user typed judge it. A micronutrient keeps its
    // reference as a rule, which colours nothing.
    return g.copy(target = if (metric.isMicro) refs.getValue(metric) else null)
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
    onAddToDay: (LocalDate) -> Unit = {},
    viewModel: HistoryViewModel = viewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val allEntries by viewModel.allEntries.collectAsState(initial = emptyList())
    val entries by viewModel.filteredEntries.collectAsState(initial = emptyList())
    val goals by viewModel.trackedGoals.collectAsState(initial = emptyList())
    val allGoals by viewModel.allGoals.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Metric?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selecting = selectedIds.isNotEmpty()

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

    val shown = picked ?: goals.firstOrNull()?.metric ?: Metric.CALORIES
    // Always from the FULL history, never the search-filtered list: search
    // narrows the list, it must not rewrite the chart.
    val card = remember(allEntries, allGoals, shown) {
        val days = MetricHistory.daily(allEntries, shown, zone, today)
        chartGoal(shown, allGoals) to MetricHistory.lastDays(days, CARD_DAYS)
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
                    IconButton(onClick = {
                        val chosen = entries.filter { it.id in selectedIds }
                        selectedIds = emptySet()
                        viewModel.logAgain(chosen) { n ->
                            android.widget.Toast.makeText(context, if (n == 1) "Logged again" else "Logged $n entries again", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Filled.Replay, contentDescription = "Log again now")
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
            if (query.isBlank()) {
                item(key = "picker") {
                    MetricPicker(
                        selected = shown,
                        goals = allGoals,
                        onSelect = {
                            picked = it
                            ServiceLocator.debugEvents.event("history_metric", "metric" to it.key)
                        },
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                item(key = "card") {
                    MetricCard(
                        goal = card.first,
                        days = card.second,
                        onClick = { onOpenMetric(shown) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    )
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
                        onAdd = { onAddToDay(item.date) },
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
                    " ${goal.metric.unit}/day" + if (goal.metric.isMicro && average != null && goal.target != null)
                        " · ${Math.round(average / goal.target!! * 100)}% of ${if (goal.enabled) "your goal" else if (ServiceLocator.profileNow() != null) "what you need" else "the Daily Value"}" else "",
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
    if (!goal.enabled) return when (standing) {
        Standing.MISSED -> "past your line"
        Standing.NO_GOAL -> "30-day average"
        else -> "within your lines"
    }
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
