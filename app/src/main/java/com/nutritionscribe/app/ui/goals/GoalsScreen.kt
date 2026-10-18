package com.nutritionscribe.app.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.model.GoalDirection
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import kotlinx.coroutines.launch

class GoalsViewModel : ViewModel() {
    val goals = ServiceLocator.goalsRepository
    val profile = ServiceLocator.settingsRepository.profile
}

/**
 * The Goals tab: which metrics the user tracks, and what they're aiming for.
 *
 * The list is every metric, not only the chosen ones, so turning something on is
 * one tap rather than a hunt through a separate "add" screen — and so a number
 * typed months ago is still there when a metric is switched back on.
 */
@Composable
fun GoalsScreen(viewModel: GoalsViewModel = viewModel()) {
    val repo = viewModel.goals
    val scope = rememberCoroutineScope()
    val goals by repo.goals.collectAsState(initial = emptyList())
    val profile by viewModel.profile.collectAsState(initial = null)

    // What About you works out, to point at a saved goal that is far from it (an
    // old 20 mg of iron for a man who needs 8).
    val needs = remember(profile) { profile?.let { com.nutritionscribe.shared.model.Needs.forProfile(it) } }
    val enabledCount = goals.count { it.enabled }
    var showMicros by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(goals.any { it.enabled && it.metric.isMicro }) {
        if (goals.any { it.enabled && it.metric.isMicro }) showMicros = true
    }
    val onlyProtein = enabledCount == 1 && goals.firstOrNull { it.enabled }?.metric == Metric.PROTEIN

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                Text(
                    "Tick what you want as a goal: it goes to the top of Home, scored against your number. " +
                        "Everything is recorded either way. Nothing turns red unless you set a line for it — " +
                        "only you know how much sugar is too much for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        item {
            ProfileCard(
                profile = profile,
                onSave = { p ->
                    ServiceLocator.debugEvents.event("profile_saved", "sex" to p.sex.name, "age" to p.ageYears,
                        "activity" to p.activity.name, "aim" to p.aim.name, "stage" to p.stage.name, "diet" to p.diet.name, "plasma" to p.plasmaDonor)
                    scope.launch { ServiceLocator.settingsRepository.setProfile(p) }
                },
                onUseForGoals = { p ->
                    val needs = com.nutritionscribe.shared.model.Needs.forProfile(p)
                    val on = goals.filter { it.enabled }
                    ServiceLocator.debugEvents.event("goals_from_profile", "metrics" to on.map { it.metric.key })
                    scope.launch { on.forEach { g -> repo.setTarget(g.metric, needs.getValue(g.metric.goalMeasure)) } }
                },
            )
        }

        if (enabledCount == 0) {
            item { GoalNotice("Nothing is being tracked, so the home screen has nothing to show. Pick at least one.", error = true) }
        } else if (onlyProtein) {
            // A nudge, not a block: the user's app, the user's call.
            item {
                GoalNotice(
                    "Only protein is on. Protein alone says very little about a day — " +
                        "calories or sugar alongside it usually tells you much more.",
                    error = false,
                )
            }
        }

        items(goals.filter { !it.metric.isMicro && it.metric.partOf == null }, key = { it.metric.key }) { goal ->
            GoalRow(
                goal = goal,
                need = needs?.get(goal.metric.goalMeasure),
                onToggle = { on -> scope.launch { repo.setEnabled(goal.metric, on) } },
                onTarget = { value -> scope.launch { repo.setTarget(goal.metric, value) } },
                onFloor = { value -> scope.launch { repo.setFloor(goal.metric, value) } },
                onCeiling = { value -> scope.launch { repo.setCeiling(goal.metric, value) } },
                onDirection = { d -> scope.launch { repo.setDirection(goal.metric, d) } },
            )
        }

        // Fifteen more rows would bury the macros, so they fold away until wanted.
        item(key = "micros_header") {
            Column(
                Modifier.fillMaxWidth().padding(top = 16.dp)
                    .clickable { showMicros = !showMicros },
            ) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Minerals & vitamins", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Icon(
                        if (showMicros) androidx.compose.material.icons.Icons.Filled.ExpandLess else androidx.compose.material.icons.Icons.Filled.ExpandMore,
                        contentDescription = if (showMicros) "Hide" else "Show",
                    )
                }
                Text(
                    "Home shows all of these either way. Turn one on to make it a goal: it moves to the top and " +
                        "is scored against your own number. Bars fill toward what you need (from About you), or the label's Daily Value until you fill that in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        if (showMicros) {
            items(goals.filter { it.metric.isMicro }, key = { it.metric.key }) { goal ->
                GoalRow(
                    goal = goal,
                    need = needs?.get(goal.metric.goalMeasure),
                    onToggle = { on -> scope.launch { repo.setEnabled(goal.metric, on) } },
                    onTarget = { value -> scope.launch { repo.setTarget(goal.metric, value) } },
                    onFloor = { value -> scope.launch { repo.setFloor(goal.metric, value) } },
                    onCeiling = { value -> scope.launch { repo.setCeiling(goal.metric, value) } },
                    onDirection = { d -> scope.launch { repo.setDirection(goal.metric, d) } },
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun GoalNotice(text: String, error: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
            color = if (error) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun GoalRow(
    goal: MetricGoal,
    need: Double?,
    onToggle: (Boolean) -> Unit,
    onTarget: (Double?) -> Unit,
    onFloor: (Double?) -> Unit,
    onCeiling: (Double?) -> Unit,
    onDirection: (GoalDirection) -> Unit,
) {
    // Keyed on the stored value so an edit elsewhere is reflected, but not so
    // eagerly that it fights the user mid-keystroke.
    var targetText by remember(goal.target, goal.enabled) {
        mutableStateOf(goal.target?.let { formatTarget(it) } ?: "")
    }
    val hasLines = goal.floorValue != null || goal.ceilingValue != null
    var showLines by remember(goal.metric) { mutableStateOf(hasLines) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = goal.enabled, onCheckedChange = onToggle)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        goal.metric.goalLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (goal.enabled) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        lineSummary(goal) ?: if (goal.enabled) "Goal" else "Not a goal",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }

                // Only while the goal is on. The defaults are right for most
                // people — protein to reach, sodium to stay under — but not for
                // everyone: someone cutting eats to a calorie LIMIT while someone
                // bulking eats to a calorie target.
                if (goal.enabled) {
                    SingleChoiceSegmentedButtonRow {
                        GoalDirection.entries.forEachIndexed { index, direction ->
                            SegmentedButton(
                                selected = goal.effectiveDirection == direction,
                                onClick = { onDirection(direction) },
                                shape = SegmentedButtonDefaults.itemShape(index, GoalDirection.entries.size),
                                icon = {},
                                label = {
                                    Text(
                                        when (direction) {
                                            GoalDirection.REACH -> "Reach"
                                            GoalDirection.LIMIT -> "Stay under"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            if (goal.enabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                ) {
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = {
                            targetText = it
                            // Blank is a real answer: track it, don't judge it.
                            onTarget(if (it.isBlank()) null else it.toDoubleOrNull() ?: goal.target)
                        },
                        label = { Text("Daily target") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(160.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(goal.metric.unit, style = MaterialTheme.typography.bodyMedium)
                }
                val t = goal.target
                if (t != null && need != null && need > 0 && kotlin.math.abs(t - need) > maxOf(0.25 * need, 1.0)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            "About you works out ${formatTarget(need)} ${goal.metric.unit}" +
                                if (goal.metric.direction == GoalDirection.LIMIT) " as the limit" else " a day",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.TextButton(onClick = {
                            ServiceLocator.debugEvents.event("goal_use_need", "metric" to goal.metric.key, "from" to t, "to" to need)
                            targetText = formatTarget(need)
                            onTarget(need)
                        }) { Text("Use ${formatTarget(need)}") }
                    }
                }
                if (targetText.isBlank()) {
                    Text(
                        "No number set — it'll be tracked and charted, just not scored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                    )
                }
            }

            // Red lines are separate from the goal: someone can want to know when
            // sugar passes 60 g without making sugar something they aim at.
            if (!showLines) {
                androidx.compose.material3.TextButton(
                    onClick = { showLines = true },
                    modifier = Modifier.padding(start = 4.dp),
                ) { Text("Set a red line") }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LineField("Red below", goal.floorValue, goal.metric.unit, onFloor, Modifier.weight(1f))
                    LineField("Red above", goal.ceilingValue, goal.metric.unit, onCeiling, Modifier.weight(1f))
                }
                Text(
                    "Above shows red on Home as soon as today passes it. Below shows red in History for days that ended under it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun LineField(label: String, value: Double?, unit: String, onChange: (Double?) -> Unit, modifier: Modifier) {
    var text by remember(value) { mutableStateOf(value?.let { formatTarget(it) } ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            if (it.isBlank()) onChange(null) else it.toDoubleOrNull()?.let(onChange)
        },
        label = { Text(label) },
        suffix = { Text(unit) },
        placeholder = { Text("none") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** "Red above 60 g", "Red below 20 g · above 40 g", or null with no lines. */
private fun lineSummary(goal: MetricGoal): String? {
    val parts = listOfNotNull(
        goal.floorValue?.let { "below ${formatTarget(it)} ${goal.metric.unit}" },
        goal.ceilingValue?.let { "above ${formatTarget(it)} ${goal.metric.unit}" },
    )
    if (parts.isEmpty()) return null
    return (if (goal.enabled) "Goal · red " else "Red ") + parts.joinToString(" or ")
}

/** 160.0 -> "160", 2.5 -> "2.5". Trailing ".0" on a goal reads like false precision. */
internal fun formatTarget(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
