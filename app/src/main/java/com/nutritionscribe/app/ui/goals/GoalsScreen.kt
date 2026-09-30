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

    val enabledCount = goals.count { it.enabled }
    val onlyProtein = enabledCount == 1 && goals.firstOrNull { it.enabled }?.metric == Metric.PROTEIN

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                Text(
                    "Pick what you want to pay attention to. Everything you log is " +
                        "recorded either way, so you can turn something on later and " +
                        "still see the months you already logged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
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

        items(goals, key = { it.metric.key }) { goal ->
            GoalRow(
                goal = goal,
                onToggle = { on -> scope.launch { repo.setEnabled(goal.metric, on) } },
                onTarget = { value -> scope.launch { repo.setTarget(goal.metric, value) } },
                onFloor = { value -> scope.launch { repo.setFloor(goal.metric, value) } },
                onDirection = { d -> scope.launch { repo.setDirection(goal.metric, d) } },
            )
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
    onToggle: (Boolean) -> Unit,
    onTarget: (Double?) -> Unit,
    onFloor: (Double?) -> Unit,
    onDirection: (GoalDirection) -> Unit,
) {
    // Keyed on the stored value so an edit elsewhere is reflected, but not so
    // eagerly that it fights the user mid-keystroke.
    var targetText by remember(goal.target, goal.enabled) {
        mutableStateOf(goal.target?.let { formatTarget(it) } ?: "")
    }
    var floorText by remember(goal.floorValue, goal.enabled) {
        mutableStateOf(goal.floorValue?.let { formatTarget(it) } ?: "")
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = goal.enabled, onCheckedChange = onToggle)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        goal.metric.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (goal.enabled) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (!goal.enabled) {
                        Text(
                            when (goal.effectiveDirection) {
                                GoalDirection.REACH -> "Aim to reach this"
                                GoalDirection.LIMIT -> "Aim to stay under this"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }

                // Only while the goal is on. The defaults are right for most
                // people — protein to reach, sodium to stay under — but not for
                // everyone: someone cutting eats to a calorie LIMIT while someone
                // bulking eats to a calorie target, and the app used to score
                // both as "more is better".
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
                if (targetText.isBlank()) {
                    Text(
                        "No number set — it'll be tracked and charted, just not scored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                    )
                }

                // Only a REACH metric has a meaningful floor. For a LIMIT metric the
                // target already IS the line that must not be crossed, so a second
                // threshold underneath it would mean nothing.
                if (goal.effectiveDirection == GoalDirection.REACH) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                    ) {
                        OutlinedTextField(
                            value = floorText,
                            onValueChange = {
                                floorText = it
                                onFloor(if (it.isBlank()) null else it.toDoubleOrNull() ?: goal.floorValue)
                            },
                            label = { Text("Baseline") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(160.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "days below this read red",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
    }
}

/** 160.0 -> "160", 2.5 -> "2.5". Trailing ".0" on a goal reads like false precision. */
internal fun formatTarget(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
