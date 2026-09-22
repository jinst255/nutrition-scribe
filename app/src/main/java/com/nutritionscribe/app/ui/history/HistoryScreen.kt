package com.nutritionscribe.app.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.common.ManualEntryInput
import com.nutritionscribe.app.ui.common.ManualEntrySheet
import com.nutritionscribe.app.ui.common.resolveTimestamp
import com.nutritionscribe.app.ui.common.timeLabel
import com.nutritionscribe.shared.model.Entry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HistoryViewModel : ViewModel() {
    private val repository = ServiceLocator.currentEntryRepository()
    private val settings = ServiceLocator.settingsRepository

    val searchQuery = MutableStateFlow("")
    val allEntries = repository.observeEntries()
    val dailyGoal = settings.dailyGoalGrams
    val dailyBaseline = settings.dailyBaselineGrams
    val frequentFoods = repository.observeFrequentFoods()

    val filteredEntries = combine(allEntries, searchQuery) { entries, query ->
        if (query.isBlank()) entries else entries.filter {
            it.name.contains(query, ignoreCase = true) || it.rawText.contains(query, ignoreCase = true)
        }
    }

    /**
     * Daily totals for the chart — always built from the FULL history, never the
     * search-filtered list. The chart previously used the filtered entries, so typing
     * into search silently zeroed out days whose entries didn't match the query,
     * corrupting the visible history. Search should only ever narrow the list below,
     * not the chart data.
     */
    fun dailySeries(entries: List<Entry>): List<DailyPoint> {
        if (entries.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val byDay = entries.groupBy { Instant.ofEpochMilli(it.timestampEpochMillis).atZone(zone).toLocalDate().toEpochDay() }
        val minDay = byDay.keys.min()
        val today = LocalDate.now(zone).toEpochDay()
        return (minDay..today).map { day ->
            DailyPoint(day, byDay[day]?.sumOf { it.proteinGrams } ?: 0.0)
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

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("MMMM d"))
    }
}

/**
 * The grouped list is flattened into one indexed list so a chart tap can resolve a
 * date to an exact LazyColumn index. Nested forEach/items blocks make that index
 * impossible to compute without re-deriving the layout by hand.
 */
private sealed interface HistoryListItem {
    data class Header(val date: LocalDate, val totalProtein: Double) : HistoryListItem
    data class EntryItem(val entry: Entry) : HistoryListItem
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = viewModel()) {
    val allEntries by viewModel.allEntries.collectAsState(initial = emptyList())
    val entries by viewModel.filteredEntries.collectAsState(initial = emptyList())
    val goal by viewModel.dailyGoal.collectAsState(initial = 160.0)
    val baseline by viewModel.dailyBaseline.collectAsState(initial = 112.0)
    val frequentFoods by viewModel.frequentFoods.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var zoom by remember { mutableStateOf(0) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selecting = selectedIds.isNotEmpty()
    var tappedPoint by remember { mutableStateOf<BucketPoint?>(null) }
    var focusedDate by remember { mutableStateOf<LocalDate?>(null) }
    var addingToDate by remember { mutableStateOf<LocalDate?>(null) }

    val daily = remember(allEntries) { viewModel.dailySeries(allEntries) }
    val buckets = remember(daily, zoom) { ChartBucketing.visibleBuckets(daily, zoom) }
    val average = if (buckets.isNotEmpty()) buckets.map { it.averageProtein }.average() else 0.0
    val zone = remember { ZoneId.systemDefault() }
    val listState = rememberLazyListState()

    val listItems = remember(entries) {
        val grouped = entries.groupBy { Instant.ofEpochMilli(it.timestampEpochMillis).atZone(zone).toLocalDate() }
        buildList {
            grouped.forEach { (date, dayEntries) ->
                add(HistoryListItem.Header(date, dayEntries.sumOf { it.proteinGrams }))
                dayEntries.forEach { add(HistoryListItem.EntryItem(it)) }
            }
        }
    }

    // A chart tap moves the list, not just the caption. Without this the selected day
    // was announced above a list still sitting wherever it happened to be scrolled.
    LaunchedEffect(tappedPoint, listItems) {
        val point = tappedPoint ?: return@LaunchedEffect
        val range = point.startEpochDay..point.endEpochDay
        val index = listItems.indexOfFirst {
            it is HistoryListItem.Header && it.date.toEpochDay() in range
        }.takeIf { it >= 0 }
        // A multi-day bucket may contain no logged days at all; fall back to the nearest
        // older day that does exist so the tap always lands somewhere sensible.
            ?: listItems.indexOfFirst {
                it is HistoryListItem.Header && it.date.toEpochDay() <= point.endEpochDay
            }.takeIf { it >= 0 }

        if (index != null) {
            focusedDate = (listItems[index] as HistoryListItem.Header).date
            listState.animateScrollToItem(index)
        } else {
            focusedDate = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; viewModel.searchQuery.value = it },
            label = { Text("Search entries") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Avg ${average.toInt()}g", modifier = Modifier.padding(vertical = 8.dp))

        AndroidView(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            factory = { context ->
                HistoryChartView(context).apply {
                    goalGrams = goal
                    baselineGrams = baseline
                    onZoomChanged = { zoom = it }
                    onPointTapped = { tappedPoint = it }
                }
            },
            update = { view ->
                view.goalGrams = goal
                view.baselineGrams = baseline
                view.setZoom(zoom)
                view.setPoints(buckets)
            },
        )

        tappedPoint?.let { point ->
            val start = LocalDate.ofEpochDay(point.startEpochDay)
            val end = LocalDate.ofEpochDay(point.endEpochDay)
            val formatter = DateTimeFormatter.ofPattern("MMM d")
            val label = if (start == end) {
                start.format(DateTimeFormatter.ISO_DATE)
            } else {
                "${start.format(formatter)} – ${end.format(formatter)} (avg)"
            }
            val suffix = if (focusedDate == null && query.isNotBlank()) " · no matching entries" else ""
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$label: ${point.averageProtein.toInt()}g$suffix",
                    color = MaterialTheme.colorScheme.secondary,
                )
                // A day with nothing logged has no list header, and therefore no per-day
                // + button. Without this, a forgotten day is unreachable: you can see the
                // gap on the chart but have no way to fill it in.
                if (start == end) {
                    TextButton(onClick = { addingToDate = start }) {
                        Icon(
                            Icons.Filled.AddCircleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(" Add", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (selecting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${selectedIds.size} selected", modifier = Modifier.padding(top = 12.dp))
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

        LazyColumn(state = listState) {
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
                        totalProtein = item.totalProtein,
                        focused = item.date == focusedDate,
                        onAdd = { addingToDate = item.date },
                    )
                    is HistoryListItem.EntryItem -> {
                        val entry = item.entry
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (selecting) {
                                            selectedIds = if (selectedIds.contains(entry.id)) {
                                                selectedIds - entry.id
                                            } else {
                                                selectedIds + entry.id
                                            }
                                        }
                                    },
                                    onLongClick = { selectedIds = selectedIds + entry.id },
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selecting) {
                                Checkbox(
                                    checked = selectedIds.contains(entry.id),
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) selectedIds + entry.id else selectedIds - entry.id
                                    },
                                )
                            }
                            Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                                Text(entry.name)
                                Text(
                                    entry.timeLabel(zone),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            Text(
                                "${entry.proteinGrams.toInt()}g",
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
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

@Composable
private fun DayHeader(
    date: LocalDate,
    totalProtein: Double,
    focused: Boolean,
    onAdd: () -> Unit,
) {
    val background = if (focused) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                dayLabel(date),
                style = MaterialTheme.typography.labelLarge,
                color = if (focused) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${totalProtein.toInt()}g",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                IconButton(onClick = onAdd, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.AddCircleOutline,
                        contentDescription = "Add an entry to ${dayLabel(date)}",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}
