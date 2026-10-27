package com.nutritionscribe.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.data.db.toEntry
import com.nutritionscribe.app.data.repository.HomeSort
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.common.timeLabel
import com.nutritionscribe.app.ui.history.formatAmount
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.NutrientDay
import com.nutritionscribe.shared.model.NutrientTotal
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One past day, the way Home shows today: the same two pages of bars, scored
 * against the same goals and needs, and that day's entries under them. Opened
 * from a day in History.
 */
@Composable
fun DayScreen(
    date: LocalDate,
    onOpenEntry: (String) -> Unit,
    onOpenGoals: () -> Unit,
    onOpenNutrient: (Metric, Double, Double) -> Unit = { _, _, _ -> },
) {
    val zone = remember { ZoneId.systemDefault() }
    val all by remember { ServiceLocator.database.entryDao().observeAll().map { rows -> rows.map { it.toEntry() } } }
        .collectAsState(initial = emptyList())
    val entries = remember(all, date) {
        all.filter { com.nutritionscribe.shared.model.LogDays.dayOf(it.timestampEpochMillis, zone) == date }.sortedBy { it.timestampEpochMillis }
    }
    val goals by ServiceLocator.goalsRepository.goals.collectAsState(initial = emptyList())
    val profile by ServiceLocator.settingsRepository.profile.collectAsState(initial = null)
    val donations by ServiceLocator.settingsRepository.plasmaDonations.collectAsState(initial = emptySet())
    val sort by ServiceLocator.settingsRepository.homeSort.collectAsState(initial = HomeSort.GOALS)
    val scope = rememberCoroutineScope()
    LaunchedEffect(date) { ServiceLocator.debugEvents.event("day_open", "day" to date.toString()) }

    val plasmaExtra = profile?.takeIf { it.plasmaDonor }?.let {
        com.nutritionscribe.shared.model.PlasmaDonation.extraProtein(date, donations, it.weightKg)
    } ?: 0.0
    val references = remember(profile) { com.nutritionscribe.shared.model.Needs.forProfile(profile) }
    val refs = remember(references, plasmaExtra) {
        if (plasmaExtra > 0) references + (Metric.PROTEIN to references.getValue(Metric.PROTEIN) + plasmaExtra) else references
    }
    val totals = remember(entries) { NutrientDay.totals(entries) }
    val splits = remember(entries, totals) {
        Splits(
            com.nutritionscribe.shared.resolve.Sugars.split(entries),
            com.nutritionscribe.shared.resolve.Fats.split(totals.mapKeys { it.key.key }.mapValues { it.value.amount }),
        )
    }
    val goalOf = goals.associateBy { it.metric }
    val lead = goals.firstOrNull { it.enabled }?.metric ?: Metric.CALORIES
    var detail by remember { mutableStateOf<Metric?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            NutrientPager(
                totals, goalOf, refs, plasmaExtra,
                hasFood = entries.any { it.nutrients.isNotEmpty() }, onOpen = { detail = it },
                splits = splits, sort = sort,
                onSort = { s -> scope.launch { ServiceLocator.settingsRepository.setHomeSort(s) } },
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Entries", Modifier.weight(1f))
                Text(
                    if (entries.size == 1) "1 entry" else "${entries.size} entries",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        if (entries.isEmpty()) item {
            Text("Nothing logged this day.", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(vertical = 12.dp))
        }
        items(entries, key = { it.id }) { e ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenEntry(e.id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(e.timeLabel(zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                Text(
                    e.nutrients[lead.key]?.let { formatAmount(it, lead) } ?: "–",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }

    val naturalOpen by NaturalSugar.open.collectAsState()
    if (naturalOpen) NaturalSheet(
        entries,
        onAboutAdded = {
            val sp = com.nutritionscribe.shared.resolve.Sugars.split(entries)
            onOpenNutrient(Metric.SUGAR, sp.added + sp.unknown, refs.getValue(Metric.SUGAR))
        },
        onOpenEntry = onOpenEntry,
        onDismiss = { NaturalSugar.open.value = false },
    )
    detail?.let { m ->
        NutrientSheet(
            metric = m,
            total = totals[m] ?: NutrientTotal(m, 0.0, 0.0),
            goal = goalOf[m],
            reference = refs.getValue(m),
            personal = profile != null,
            extra = if (m == Metric.PROTEIN) plasmaExtra else 0.0,
            entries = entries,
            onToggleGoal = { on -> scope.launch { ServiceLocator.goalsRepository.setEnabled(m, on) } },
            onOpenGoals = { detail = null; onOpenGoals() },
            onLearnMore = { have, target -> detail = null; onOpenNutrient(m, have, target) },
            onOpenEntry = { id -> detail = null; onOpenEntry(id) },
            onDismiss = { detail = null },
        )
    }
}
