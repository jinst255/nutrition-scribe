package com.nutritionscribe.app.ui.ideas

import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.data.db.toEntry
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.home.SectionLabel
import com.nutritionscribe.shared.model.Ideas
import com.nutritionscribe.shared.model.Needs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** This week's ideas, worked out from the log, the person's needs and what they said no to. */
object IdeasSource {
    private val usdaCache = java.util.concurrent.ConcurrentHashMap<Long, Map<String, Double>>()

    fun flow(variant: Flow<Int> = kotlinx.coroutines.flow.flowOf(0)): Flow<Ideas.Result> = combine(
        ServiceLocator.database.entryDao().observeAll(),
        ServiceLocator.settingsRepository.profile,
        ServiceLocator.settingsRepository.ideasDismissed,
        variant,
    ) { rows, profile, dismissed, v ->
        val usda = ServiceLocator.foodDatabase.reader()?.let { com.nutritionscribe.shared.resolve.UsdaFoodSource(it) }
        Ideas.build(
            entries = rows.map { it.toEntry() },
            needs = Needs.forProfile(profile),
            today = LocalDate.now(),
            zone = ZoneId.systemDefault(),
            per100g = { id -> usdaCache[id] ?: usda?.byId(id)?.per100g?.also { usdaCache[id] = it } },
            dismissed = dismissed,
            variant = v,
        )
    }.flowOn(Dispatchers.Default)
}

class IdeasViewModel : ViewModel() {
    private val variant = kotlinx.coroutines.flow.MutableStateFlow(0)
    val result = IdeasSource.flow(variant)

    /** Other foods for the same gaps: the next ones down the list. */
    fun refresh() {
        variant.value += 1
        ServiceLocator.debugEvents.event("ideas_refresh", "variant" to variant.value)
    }

    fun dismiss(idea: Ideas.Idea) {
        ServiceLocator.debugEvents.event("idea_dismissed", "id" to idea.id, "title" to idea.title)
        viewModelScope.launch { ServiceLocator.settingsRepository.dismissIdea(idea.id) }
    }
}

/**
 * Ideas from this week's food: what was short, what went over, meals already made
 * with one thing added, and a list for the store. Behind a button on Home, so it
 * is there when wanted and never in the way. Pull down (or tap Refresh) for other
 * foods for the same gaps.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun IdeasScreen(viewModel: IdeasViewModel = viewModel()) {
    val r by viewModel.result.collectAsState(initial = null)
    LaunchedEffect(r?.ideas?.map { it.id }) {
        r?.let { ServiceLocator.debugEvents.event("ideas_open", "ready" to it.ready, "days" to it.fullDays, "ideas" to it.ideas.map { i -> i.id }) }
    }
    val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    if (pull.isRefreshing) {
        LaunchedEffect(true) {
            viewModel.refresh()
            kotlinx.coroutines.delay(350)
            pull.endRefresh()
        }
    }
    val result = r
    Box(Modifier.fillMaxSize().nestedScroll(pull.nestedScrollConnection)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (result == null) {
                item { Text("Working it out…", color = MaterialTheme.colorScheme.secondary) }
                return@LazyColumn
            }
            item { WeekHeader(result, onRefresh = { viewModel.refresh() }) }
            if (!result.ready) return@LazyColumn
            val short = result.ideas.filter { it.kind == Ideas.Kind.SHORT }
            val over = result.ideas.filter { it.kind == Ideas.Kind.OVER }
            val meals = result.ideas.filter { it.kind == Ideas.Kind.MEAL }
            if (short.isEmpty() && over.isEmpty()) item {
                Text("Nothing stood out: your week was within what you need.", style = MaterialTheme.typography.bodyMedium)
            }
            if (short.isNotEmpty()) {
                item { SectionLabel("Short on") }
                items(short, key = { it.id }) { IdeaCard(it, onDismiss = { viewModel.dismiss(it) }) }
            }
            if (over.isNotEmpty()) {
                item { SectionLabel("Over the limit", Modifier.padding(top = 4.dp)) }
                items(over, key = { it.id }) { IdeaCard(it, onDismiss = { viewModel.dismiss(it) }) }
            }
            if (meals.isNotEmpty()) {
                item { SectionLabel("Meals you already make", Modifier.padding(top = 4.dp)) }
                items(meals, key = { it.id }) { IdeaCard(it, onDismiss = { viewModel.dismiss(it) }) }
            }
            if (result.store.isNotEmpty()) {
                item { SectionLabel("Next time at the store", Modifier.padding(top = 4.dp)) }
                item { StoreList(result.store) }
            }
            item {
                Text(
                    "General guidelines, not medical advice. Numbers come from USDA FoodData Central and the sources named on each card.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        androidx.compose.material3.pulltorefresh.PullToRefreshContainer(state = pull, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun WeekHeader(result: Ideas.Result, onRefresh: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = scheme.primaryContainer.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Your last 7 days", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (result.ready) "From ${result.fullDays} fully logged days. Pull down for other foods."
                    else "Log everything you eat for ${result.needed} days and ideas appear here. So far ${result.fullDays} of ${result.needed}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (result.ready) {
                androidx.compose.material3.IconButton(onClick = onRefresh) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Refresh, contentDescription = "Other ideas")
                }
            }
        }
    }
}

@Composable
private fun IdeaCard(idea: Ideas.Idea, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val over = idea.kind == Ideas.Kind.OVER
    val accent = if (over) scheme.error else scheme.primary
    Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    when (idea.kind) {
                        Ideas.Kind.MEAL -> idea.title
                        else -> idea.metric?.goalLabel ?: idea.title
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(top = 10.dp),
                )
                androidx.compose.material3.IconButton(onClick = onDismiss) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Not for me",
                        tint = scheme.secondary, modifier = Modifier.size(18.dp))
                }
            }
            val avg = idea.average
            val target = idea.target
            val m = idea.metric
            if (avg != null && target != null && m != null && target > 0) {
                Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    com.nutritionscribe.app.ui.home.HomeBar((avg / target).toFloat(), accent, unknown = false, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${com.nutritionscribe.app.ui.history.formatAmount(avg, m)} a day · " +
                            (if (over) "limit ${com.nutritionscribe.app.ui.history.formatAmount(target, m)}" else "you need ${com.nutritionscribe.app.ui.history.formatAmount(target, m)}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (over) scheme.error else scheme.onSurfaceVariant,
                    )
                }
            }
            if (idea.body.isNotBlank()) Text(idea.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
            if (idea.foods.isNotEmpty() && idea.kind != Ideas.Kind.MEAL) {
                Text(if (over) "Most of it came from" else "Easy ways to get more",
                    style = MaterialTheme.typography.labelMedium, color = scheme.secondary, modifier = Modifier.padding(top = 2.dp))
                idea.foods.forEach { f ->
                    val parts = f.split(" · ")
                    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(accent))
                        Text(parts.first(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp).weight(1f))
                        Text(parts.drop(1).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = scheme.secondary)
                    }
                }
            }
            Text("Source: ${idea.source}", style = MaterialTheme.typography.labelSmall, color = scheme.secondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        }
    }
}

/** The store list as a checklist, ticked in the aisle. Not saved: it is for one trip. */
@Composable
private fun StoreList(items: List<String>) {
    val ticked = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(setOf<String>()) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            items.forEach { line ->
                val food = line.substringBefore(" (")
                val why = line.substringAfter(" (", "").removeSuffix(")")
                val on = line in ticked.value
                Row(
                    Modifier.fillMaxWidth().clickable { ticked.value = if (on) ticked.value - line else ticked.value + line }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { ticked.value = if (on) ticked.value - line else ticked.value + line })
                    Column(Modifier.weight(1f)) {
                        Text(food, style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (on) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                        if (why.isNotBlank()) Text("for $why", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}
