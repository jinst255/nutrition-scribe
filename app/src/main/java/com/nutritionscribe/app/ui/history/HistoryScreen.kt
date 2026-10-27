package com.nutritionscribe.app.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.ui.unit.sp
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
    val today = com.nutritionscribe.shared.model.LogDays.today()
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

/** History's filters (note S30): each with a one-line meaning under the chips. */
private enum class HistoryFilter(val label: String, val meaning: String) {
    ALL("All", "Everything you track"),
    OFF("Off target", "What had days past a line or off target"),
    LIMITS("Limits", "What you keep under"),
    GOALS("Goals", "Only your goals"),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onOpenEntry: (String) -> Unit = {},
    onOpenMetric: (Metric) -> Unit = {},
    onAddToDay: (LocalDate) -> Unit = {},
    onOpenDay: (LocalDate) -> Unit = {},
    viewModel: HistoryViewModel = viewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val allEntries by viewModel.allEntries.collectAsState(initial = emptyList())
    val entries by viewModel.filteredEntries.collectAsState(initial = emptyList())
    val goals by viewModel.trackedGoals.collectAsState(initial = emptyList())
    val allGoals by viewModel.allGoals.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selecting = selectedIds.isNotEmpty()

    val zone = remember { ZoneId.systemDefault() }
    val today = com.nutritionscribe.shared.model.LogDays.today(zone)
    var range by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(14) }
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    val counted by ServiceLocator.settingsRepository.historyCounted.collectAsState(initial = emptySet())
    val calTarget = allGoals.firstOrNull { it.metric == Metric.CALORIES && it.enabled }?.target
        ?: com.nutritionscribe.shared.model.Needs.forProfile(ServiceLocator.profileNow()).getValue(Metric.CALORIES)
    val full = remember(allEntries, counted, calTarget) { MetricHistory.fullDays(allEntries, zone, today, calTarget, counted) }
    // The number shown beside each entry: whatever the user tracks first. It
    // used to be protein regardless, even for someone who only tracks sugar.
    val lead = goals.firstOrNull()?.metric ?: Metric.PROTEIN

    val listItems = remember(entries) {
        val grouped = entries.groupBy { com.nutritionscribe.shared.model.LogDays.dayOf(it.timestampEpochMillis, zone) }
        buildList {
            grouped.forEach { (date, dayEntries) ->
                add(HistoryListItem.Header(date, dayEntries.size))
                dayEntries.forEach { add(HistoryListItem.EntryItem(it)) }
            }
        }
    }

    var showAll by remember { mutableStateOf(false) }
    // Every chart on one page: goals first, then the main macros, then the rest on
    // request. Picking one metric at a time to see it meant a tap per nutrient.
    // Always from the FULL history, never the search-filtered list: search
    // narrows the list, it must not rewrite the charts.
    val shownMetrics = remember(goals, showAll, allEntries) {
        val mine = goals.map { it.metric }
        val main = listOf(Metric.CALORIES, Metric.PROTEIN, Metric.CARBS, Metric.FAT, Metric.FIBER, Metric.SUGAR, Metric.SODIUM)
        val logged = com.nutritionscribe.shared.model.Extras.shown(Metric.MACROS + Metric.MICROS).filter { m -> allEntries.any { m.key in it.nutrients } }
        val first = (mine + main).distinct()
        if (showAll) (first + logged).distinct() else first.take(maxOf(6, mine.size))
    }
    val allCards = remember(allEntries, allGoals, shownMetrics, range) {
        shownMetrics.map { m -> chartGoal(m, allGoals) to MetricHistory.lastDays(MetricHistory.daily(allEntries, m, zone, today), range) }
    }
    val cards = allCards.filter { (g, days) ->
        when (filter) {
            HistoryFilter.ALL -> true
            HistoryFilter.GOALS -> g.enabled
            HistoryFilter.LIMITS -> g.effectiveDirection == com.nutritionscribe.shared.model.GoalDirection.LIMIT && (g.enabled || g.ceilingValue != null)
            HistoryFilter.OFF -> days.any { d -> d.epochDay in full && d.value?.let { MetricHistory.standing(g, it) }.let { it == Standing.OVER || it == Standing.PAST || it == Standing.LOW } }
        }
    }
    val fullInRange = (1..range).count { today.minusDays(it.toLong()).toEpochDay() in full }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
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
                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            var rangeMenu by remember { mutableStateOf(false) }
                            androidx.compose.foundation.layout.Box {
                                FilterChip(
                                    selected = true, onClick = { rangeMenu = true },
                                    label = { Text("$range days") },
                                    leadingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                )
                                androidx.compose.material3.DropdownMenu(expanded = rangeMenu, onDismissRequest = { rangeMenu = false }) {
                                    listOf(7, 14, 30, 90).forEach { n ->
                                        androidx.compose.material3.DropdownMenuItem(text = { Text("$n days") }, onClick = {
                                            rangeMenu = false; range = n
                                            ServiceLocator.debugEvents.event("history_range", "days" to n)
                                        })
                                    }
                                }
                            }
                            HistoryFilter.entries.forEach { f ->
                                FilterChip(selected = filter == f, onClick = {
                                    filter = f
                                    ServiceLocator.debugEvents.event("history_filter", "filter" to f.name)
                                }, label = { Text(f.label) })
                            }
                        }
                        Text(filter.meaning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        HistoryLegend()
                    }
                }
                if (cards.isEmpty()) item(key = "nocards") {
                    Text(
                        if (filter == HistoryFilter.OFF) "Nothing was off target in the last $range days." else "Nothing to show here yet.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                cards.chunked(2).forEachIndexed { row, pair ->
                    item(key = "cards_$row") {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { (goal, days) ->
                                MetricCard(
                                    goal = goal,
                                    days = days,
                                    full = full,
                                    today = today.toEpochDay(),
                                    compact = true,
                                    onClick = {
                                        ServiceLocator.debugEvents.event("history_metric", "metric" to goal.metric.key)
                                        onOpenMetric(goal.metric)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        }
                    }
                }
                item(key = "all") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${if (fullInRange == 1) "1 full day" else "$fullInRange full days"} · today is left out until it's over",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.TextButton(onClick = {
                            showAll = !showAll
                            ServiceLocator.debugEvents.event("history_show_all", "open" to showAll)
                        }) { Text(if (showAll) "Show fewer" else "Show all") }
                    }
                }
            }
            // Below the charts: the charts are what History opens for, the search is
            // for the list under it. At the top it sat over charts it never filters.
            item(key = "search") {
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
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
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
                        onOpen = { onOpenDay(item.date) },
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

/** The key for every card: ▼ and ▲ so it isn't colour alone. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HistoryLegend() {
    val c = standingColors()
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(c.low to "▼ Too low", c.inRange to "In range", c.over to "Over target", c.past to "▲ Past your line", c.none to "No line set").forEach { (col, t) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(Modifier.padding(end = 5.dp).size(10.dp).background(col, RoundedCornerShape(2.dp)))
                Text(t, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

/**
 * One tracked metric over the range: its average over full days, a sentence on how
 * those days did, and each day as a small bar in its own colour (note S29, S31:
 * one colour for a whole card hid the good days). Tapping opens the same bars, big.
 */
@Composable
private fun MetricCard(
    goal: MetricGoal,
    days: List<DayValue>,
    full: Set<Long>,
    today: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Half-width, two to a row: the number, the line, and a few words. */
    compact: Boolean = false,
) {
    val average = MetricHistory.averageFull(days, full)
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 10.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (goal.metric == Metric.SUGAR) "Added sugar" else if (compact) goal.metric.shortLabel else goal.metric.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    average?.let { formatAmount(it, goal.metric, withUnit = false) } ?: "–",
                    style = com.nutritionscribe.app.ui.theme.NumberStyle.copy(fontSize = 22.sp),
                )
                Text(" ${goal.metric.unit}/day", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 3.dp))
            }
            Text(
                cardStatus(goal, days, full),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            DayStrip(days, goal, full, today, Modifier.fillMaxWidth().height(30.dp).padding(top = 4.dp))
        }
    }
}

/** "8 of 13 days at 100 g or more", "6 under 2,300 · 4 over · 3 past 3,000", or how to judge it. */
internal fun cardStatus(goal: MetricGoal, days: List<DayValue>, full: Set<Long>): String {
    val m = goal.metric
    fun f(v: Double) = formatAmount(v, m, withUnit = m.unit == "g")
    val judged = days.filter { it.epochDay in full }.mapNotNull { d -> d.value?.let { MetricHistory.standing(goal, it) } }
    val n = judged.size
    if (n == 0) return "No full days yet"
    val target = goal.target?.takeIf { goal.enabled }
    val line = goal.ceilingValue
    val floor = goal.floorValue
    if (target == null && line == null && floor == null) return "No line set · set one in Goals"
    val inRange = judged.count { it == Standing.IN_RANGE }
    val over = judged.count { it == Standing.OVER }
    val past = judged.count { it == Standing.PAST }
    val low = judged.count { it == Standing.LOW }
    return when {
        target != null && goal.effectiveDirection == com.nutritionscribe.shared.model.GoalDirection.LIMIT && line != null ->
            listOfNotNull("$inRange under ${f(target)}", over.takeIf { it > 0 }?.let { "$it over" }, past.takeIf { it > 0 }?.let { "$it past ${f(line)}" },
                low.takeIf { it > 0 }?.let { "$it too low" }).joinToString(" · ")
        target != null && goal.effectiveDirection == com.nutritionscribe.shared.model.GoalDirection.LIMIT -> "$inRange of $n under ${f(target)}"
        target != null -> "${inRange + over} of $n days at ${f(target)} or more" + (past.takeIf { it > 0 }?.let { " · $it past your line" } ?: "")
        line != null -> "${n - past} of $n under your ${f(line)} line"
        else -> "${n - low} of $n above your ${f(floor!!)} line"
    }
}

@Composable
private fun DayHeader(date: LocalDate, count: Int, onAdd: () -> Unit, onOpen: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The day itself opens: that day's nutrients, the way Home shows today.
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "See this day's nutrients", onClick = onOpen).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(dayLabel(date), style = MaterialTheme.typography.titleSmall)
                Text("  Nutrients ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
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
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        }
        Text(
            entry.timeLabel(zone),
            style = com.nutritionscribe.app.ui.theme.NumberStyle.copy(fontSize = 12.5.sp),
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = if (selecting) 0.dp else 4.dp).width(70.dp),
        )
        Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            entry.amountOf(lead)?.let { formatAmount(it, lead) } ?: "–",
            style = com.nutritionscribe.app.ui.theme.NumberStyle,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
        )
    }
}
