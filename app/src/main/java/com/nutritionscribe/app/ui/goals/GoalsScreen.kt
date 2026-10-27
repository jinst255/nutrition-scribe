package com.nutritionscribe.app.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.data.repository.SettingsRepository
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.history.formatAmount
import com.nutritionscribe.app.ui.home.endText
import com.nutritionscribe.app.ui.home.restText
import com.nutritionscribe.app.ui.theme.NumberStyle
import com.nutritionscribe.app.ui.theme.limitColors
import com.nutritionscribe.shared.model.GoalDirection
import com.nutritionscribe.shared.model.GoalFill
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import com.nutritionscribe.shared.model.Streak
import kotlinx.coroutines.launch

class GoalsViewModel : ViewModel() {
    val goals = ServiceLocator.goalsRepository
    val profile = ServiceLocator.settingsRepository.profile
}

/** ⓘ in Goals' top bar: the full explanation the one-line tip stands in for. */
object GoalsInfo {
    val open = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/**
 * The Goals tab: which metrics the user tracks, and what they're aiming for.
 *
 * The list is every metric, not only the chosen ones, so turning something on is
 * one tap rather than a hunt through a separate "add" screen, and so a number
 * typed months ago is still there when a metric is switched back on. Search finds
 * one without scrolling (note S26); Fill in for me fills the boxes (note S38).
 */
@Composable
fun GoalsScreen(onStreak: () -> Unit = {}, viewModel: GoalsViewModel = viewModel()) {
    val repo = viewModel.goals
    val settings = ServiceLocator.settingsRepository
    val scope = rememberCoroutineScope()
    val goals by repo.goals.collectAsState(initial = emptyList())
    val profile by viewModel.profile.collectAsState(initial = null)
    val tipClosed by settings.goalsTipClosed.collectAsState(initial = true)
    val streak by settings.streak.collectAsState(initial = SettingsRepository.StreakSettings(true, Streak.Rule.ANY, 1, 3))
    val info by GoalsInfo.open.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filling by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val needs = remember(profile) { com.nutritionscribe.shared.model.Needs.forProfile(profile) }
    val enabledCount = goals.count { it.enabled }
    var showMicros by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(goals.any { it.enabled && it.metric.isMicro }) {
        if (goals.any { it.enabled && it.metric.isMicro }) showMicros = true
    }
    val onlyProtein = enabledCount == 1 && goals.firstOrNull { it.enabled }?.metric == Metric.PROTEIN
    val q = query.trim().lowercase()
    fun matches(g: MetricGoal) = q.isEmpty() || q in g.metric.goalLabel.lowercase() || q in g.metric.label.lowercase() || q in g.metric.shortLabel.lowercase()
    // An extra is listed once it's switched on in More to track (or already a goal).
    val extrasOn by settings.extras.collectAsState(initial = com.nutritionscribe.shared.model.Extras.on)
    fun listed(g: MetricGoal) = g.enabled || com.nutritionscribe.shared.model.Extras.shown(g.metric, extrasOn)
    val macros = goals.filter { !it.metric.isMicro && it.metric.partOf == null && matches(it) && listed(it) }
    val micros = goals.filter { it.metric.isMicro && matches(it) && listed(it) }

    if (info) InfoDialog { GoalsInfo.open.value = false }

    Box {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search goals, like iron") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") } }) else null,
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            if (q.isEmpty()) {
                if (!tipClosed) item {
                    Tip("Ticked goals go to the top of Home.") { scope.launch { settings.closeGoalsTip() } }
                }
                item {
                    ProfileCard(
                        profile = profile,
                        onSave = { p ->
                            ServiceLocator.debugEvents.event("profile_saved", "sex" to p.sex.name, "age" to p.ageYears,
                                "activity" to p.activity.name, "aim" to p.aim.name, "stage" to p.stage.name, "diet" to p.diet.name, "plasma" to p.plasmaDonor)
                            scope.launch { settings.setProfile(p) }
                        },
                        onUseForGoals = { p ->
                            val n = com.nutritionscribe.shared.model.Needs.forProfile(p)
                            val on = goals.filter { it.enabled }
                            ServiceLocator.debugEvents.event("goals_from_profile", "metrics" to on.map { it.metric.key })
                            scope.launch { on.forEach { g -> repo.setTarget(g.metric, n.getValue(g.metric.goalMeasure)) } }
                        },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { ServiceLocator.debugEvents.event("goal_fill_open"); filling = true }) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Fill in for me")
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth().clip(CardDefaults.shape).clickable(onClick = onStreak)) {
                        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text("Streak", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    (if (streak.show) "" else "Hidden on Home · ") +
                                        "${streak.rule.label} · ${restText(streak.restDays).lowercase()} · day ends ${endText(streak.dayEndHour)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                if (enabledCount == 0) {
                    item { GoalNotice("Nothing is being tracked, so the home screen has nothing to show. Pick at least one.", error = true) }
                } else if (onlyProtein) {
                    // A nudge, not a block: the user's app, the user's call.
                    item {
                        GoalNotice(
                            "Only protein is on. Protein alone says very little about a day; " +
                                "calories or sugar alongside it usually tells you much more.",
                            error = false,
                        )
                    }
                }
            } else {
                item {
                    val n = macros.size + micros.size
                    Text(
                        when (n) { 0 -> "No goal matches “${query.trim()}”"; 1 -> "1 goal matches “${query.trim()}”"; else -> "$n goals match “${query.trim()}”" },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            items(macros, key = { it.metric.key }) { goal -> Goal(goal, needs[goal.metric.goalMeasure], profile != null, repo, scope) }

            // Fifteen more rows would bury the macros, so they fold away until wanted.
            if (q.isEmpty()) item(key = "micros_header") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable { showMicros = !showMicros }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Minerals & vitamins", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(if (showMicros) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (showMicros) "Hide" else "Show")
                }
            }
            if (showMicros || q.isNotEmpty()) {
                items(micros, key = { it.metric.key }) { goal -> Goal(goal, needs[goal.metric.goalMeasure], profile != null, repo, scope) }
            }
            if (q.isEmpty()) item(key = "more") { MoreToTrack() }

            item { Spacer(Modifier.height(24.dp)) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (filling) FillSheet(
        goals = goals,
        needs = needs,
        profile = profile,
        onApply = { changes ->
            filling = false
            val before = goals
            ServiceLocator.debugEvents.event("goal_fill_apply", "changes" to changes.size)
            scope.launch {
                repo.putAll(GoalFill.apply(before, changes))
                val r = snackbar.showSnackbar("Filled in ${changes.size} ${if (changes.size == 1) "box" else "boxes"}", "Undo",
                    duration = androidx.compose.material3.SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) {
                    ServiceLocator.debugEvents.event("goal_fill_undo")
                    repo.putAll(before.filter { b -> changes.any { it.metric == b.metric } })
                }
            }
        },
        onDismiss = { filling = false },
    )
}

@Composable
private fun Goal(goal: MetricGoal, need: Double?, personal: Boolean, repo: com.nutritionscribe.app.data.repository.GoalsRepository, scope: kotlinx.coroutines.CoroutineScope) {
    GoalRow(
        goal = goal,
        need = need,
        personal = personal,
        onToggle = { on -> scope.launch { repo.setEnabled(goal.metric, on) } },
        onTarget = { value -> scope.launch { repo.setTarget(goal.metric, value) } },
        onFloor = { value -> scope.launch { repo.setFloor(goal.metric, value) } },
        onCeiling = { value -> scope.launch { repo.setCeiling(goal.metric, value) } },
        onDirection = { d -> scope.launch { repo.setDirection(goal.metric, d) } },
    )
}

/** A one-line tip, closed for good with ×. The rest lives behind ⓘ. */
@Composable
internal fun Tip(text: String, onClose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Hide this tip", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun InfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How goals work") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tick what you want as a goal: it goes to the top of Home, scored against your number. Everything is recorded either way.")
                Text("Green is at or under your target. Over a stay-under target is sand. Rose means past a line you set, and nothing turns rose unless you set one: only you know how much sugar is too much for you.")
                Text("Grey means there's nothing to judge it by. In History, ▼ marks a finished day under a target to reach or under a too-low line.")
                Text("Bars fill toward what you need, from About you, or the label's Daily Value until you fill that in.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun GoalNotice(text: String, error: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalRow(
    goal: MetricGoal,
    need: Double?,
    personal: Boolean,
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
    val unit = goal.metric.unit

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = goal.enabled, onCheckedChange = onToggle)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        goal.metric.goalLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (goal.enabled) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    // On, the boxes below say the lines; the summary only needs saying when folded.
                    Text(
                        if (goal.enabled) "Goal" else lineSummary(goal) ?: "Not a goal",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                // Only while the goal is on. The defaults are right for most
                // people (protein to reach, sodium to stay under) but not for
                // everyone: someone cutting eats to a calorie LIMIT while someone
                // bulking eats to a calorie target.
                if (goal.enabled) {
                    SingleChoiceSegmentedButtonRow(Modifier.width(180.dp)) {
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
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            if (goal.enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = {
                            targetText = it
                            // Blank is a real answer: track it, don't judge it.
                            onTarget(if (it.isBlank()) null else it.toDoubleOrNull() ?: goal.target)
                        },
                        label = { Text("Daily target") },
                        suffix = { Text(unit) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    // What About you works out (or the label figure), one tap to use.
                    if (need != null && need > 0) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.weight(1f).padding(top = 8.dp).clip(RoundedCornerShape(4.dp)).clickable(
                                enabled = goal.target != need, onClickLabel = "Use ${formatTarget(need)}",
                            ) {
                                ServiceLocator.debugEvents.event("goal_use_need", "metric" to goal.metric.key, "from" to goal.target, "to" to need)
                                targetText = formatTarget(need)
                                onTarget(need)
                            },
                        ) {
                            Column(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(if (personal) "Your need" else "Label figure", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${formatTarget(need)} $unit", style = NumberStyle, modifier = Modifier.weight(1f))
                                    if (goal.target != need) Text("Use", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                if (targetText.isBlank()) {
                    Text(
                        "No number set: it'll be tracked and charted, just not scored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            // Lines are separate from the goal: someone can want to know when
            // sugar passes 60 g without making sugar something they aim at.
            if (!showLines) {
                TextButton(onClick = { showLines = true }) { Text("Set a too-high or too-low line") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LineField("Too low below", goal.floorValue, unit, onFloor, Modifier.weight(1f))
                    LineField("Too high above", goal.ceilingValue, unit, onCeiling, Modifier.weight(1f))
                }
                GoalFill.upperLimit(goal.metric, null)?.let { ul ->
                    if (goal.ceilingValue == null) Text(
                        "${formatTarget(ul)} $unit is the safe upper limit for ${goal.metric.label.lowercase()} from food and supplements.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            if (goal.enabled && goal.target != null && goal.effectiveDirection == GoalDirection.LIMIT) {
                ColourScale(goal)
            }
        }
    }
}

/**
 * Where each colour starts for a "stay under" goal, as one small bar with its
 * numbers (the owner kept this from the colours decision: "this color thingy is good").
 */
@Composable
private fun ColourScale(goal: MetricGoal) {
    val c = limitColors()
    val target = goal.target ?: return
    val line = goal.ceilingValue?.takeIf { it > target }
    val m = goal.metric
    fun f(v: Double) = formatAmount(v, m, withUnit = false)
    val parts = if (line != null) listOf(Triple(23f, c.inRange, "0"), Triple(7f, c.over, f(target)), Triple(5f, c.past, f(line) + "+"))
    else listOf(Triple(23f, c.inRange, "0"), Triple(12f, c.over, f(target) + "+"))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.forEach { (w, col, _) -> Box(Modifier.weight(w).height(8.dp).clip(RoundedCornerShape(2.dp)).background(col)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.forEach { (w, _, t) -> Text(t, style = NumberStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize), color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(w)) }
        }
        Text(
            if (line != null) "Under ${f(target)} is green. Over it is sand, past ${f(line)} is rose."
            else "Under ${f(target)} is green, over it is sand. Set a too-high line to mark where it turns rose.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
        )
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

/** Goals › Fill in for me: choose what to fill, see each change, apply. Only empty boxes by default. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FillSheet(
    goals: List<MetricGoal>,
    needs: Map<Metric, Double>,
    profile: com.nutritionscribe.shared.model.Profile?,
    onApply: (List<GoalFill.Change>) -> Unit,
    onDismiss: () -> Unit,
) {
    var targets by remember { mutableStateOf(true) }
    var lines by remember { mutableStateOf(true) }
    var onlyEmpty by remember { mutableStateOf(true) }
    val changes = remember(goals, needs, targets, lines, onlyEmpty) { GoalFill.plan(goals, needs, profile, targets, lines, onlyEmpty) }
    com.nutritionscribe.app.ui.feedback.ReportableSheet("Fill in for me", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Fill in for me", style = MaterialTheme.typography.titleLarge)
            Check(if (profile != null) "Daily targets from my needs" else "Daily targets from the label figures", targets) { targets = it }
            Check("Too-high lines at the safe upper limit", lines) { lines = it }
            Check("Only boxes that are empty", onlyEmpty) { onlyEmpty = it }
            com.nutritionscribe.app.ui.home.SectionLabel(
                if (changes.isEmpty()) "Nothing to change" else "Preview · ${changes.size} ${if (changes.size == 1) "change" else "changes"}",
                Modifier.padding(top = 8.dp),
            )
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                changes.forEach { c ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${c.metric.goalLabel} ${if (c.box == GoalFill.Box.TARGET) "target" else "too high above"}",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(c.old?.let { formatTarget(it) } ?: "—", style = NumberStyle, color = MaterialTheme.colorScheme.secondary)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "to", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                        Text("${formatTarget(c.new)} ${c.metric.unit}", style = NumberStyle, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text(
                (if (onlyEmpty) "Nothing you typed is changed. " else "") + "Undo stays on screen for a few seconds after.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 6.dp),
            )
            Button(onClick = { onApply(changes) }, enabled = changes.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(50.dp)) {
                Text(if (changes.isEmpty()) "Nothing to apply" else "Apply ${changes.size} ${if (changes.size == 1) "change" else "changes"}")
            }
        }
    }
}

@Composable
private fun Check(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** "Too high above 60 g", "Too low below 20 g · too high above 40 g", or null with no lines. */
private fun lineSummary(goal: MetricGoal): String? {
    val parts = listOfNotNull(
        goal.floorValue?.let { "too low below ${formatTarget(it)} ${goal.metric.unit}" },
        goal.ceilingValue?.let { "too high above ${formatTarget(it)} ${goal.metric.unit}" },
    )
    if (parts.isEmpty()) return null
    val joined = parts.joinToString(" · ")
    return if (goal.enabled) "Goal · $joined" else joined.replaceFirstChar { it.uppercase() }
}

/** 160.0 -> "160", 2.5 -> "2.5". Trailing ".0" on a goal reads like false precision. */
internal fun formatTarget(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
