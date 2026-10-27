package com.nutritionscribe.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.data.repository.AiBackendKind
import com.nutritionscribe.app.data.repository.LookupSource
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.model.GoalDirection
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import com.nutritionscribe.shared.model.TargetCalculator
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val goals = ServiceLocator.goalsRepository
    var selectedMetrics by remember { mutableStateOf(MetricGoal.STARTER_METRICS.toSet()) }
    // Per-metric targets and baselines, filled either by hand or by the
    // calculator. Kept as text so a half-typed number is not thrown away.
    val targetText = remember { mutableStateMapOf<Metric, String>() }
    val baselineText = remember { mutableStateMapOf<Metric, String>() }
    val keptKey = remember { ServiceLocator.settingsRepository.hasAnyApiKey() }
    val scope = rememberCoroutineScope()
    val settings = ServiceLocator.settingsRepository
    val testing = remember { com.nutritionscribe.app.ui.feedback.isTestBuild(ServiceLocator.appContext) }
    var intro by remember { mutableStateOf(testing) }

    // Full-screen steps, laid out like the name step; the rest share the column below.
    if (intro) { TesterIntro(onDone = { intro = false; scope.launch { settings.setTesterIntroSeen() } }); return }
    if (step == 0) { ReadyToGo(keptKey) { step = 1 }; return }
    if (step == 3) {
        AllSet(onStart = {
            scope.launch {
                settings.setSetupDone(System.currentTimeMillis())
                settings.setOnboarded(true)
                onFinished()
            }
        })
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (step) {
            1 -> {
                Text("What do you want to track?", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Pick at least one. Everything you log is recorded either way, so you " +
                        "can switch one on months later and still see the days you already " +
                        "logged. Change these any time in Goals.",
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                ) {
                    com.nutritionscribe.shared.model.Extras.shown(Metric.MACROS).forEach { metric ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = metric in selectedMetrics,
                                onCheckedChange = { on ->
                                    selectedMetrics = if (on) {
                                        selectedMetrics + metric
                                    } else {
                                        selectedMetrics - metric
                                    }
                                },
                            )
                            Column {
                                Text(metric.goalLabel, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    when (metric.direction) {
                                        GoalDirection.REACH -> "reach \u2022 ${metric.unit}"
                                        GoalDirection.LIMIT -> "stay under \u2022 ${metric.unit}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }

                }

                if (selectedMetrics.isEmpty()) {
                    Text(
                        "Pick at least one to carry on \u2014 with nothing selected there's " +
                            "nothing for the app to show you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else if (selectedMetrics == setOf(Metric.PROTEIN)) {
                    // A nudge, not a block. Protein-only is allowed, just rarely
                    // what someone actually wants once they see the alternative.
                    Text(
                        "Protein on its own says very little about a day. Calories or " +
                            "sugar alongside it usually tells you a lot more.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Button(
                    enabled = selectedMetrics.isNotEmpty(),
                    onClick = {
                        // Seed each chosen metric with its own default so the
                        // next screen opens on real numbers rather than blanks.
                        for (metric in selectedMetrics) {
                            targetText.getOrPut(metric) { formatTarget(metric.defaultTarget) }
                            if (metric.direction == GoalDirection.REACH) {
                                baselineText.getOrPut(metric) {
                                    formatTarget(metric.defaultTarget * 0.75)
                                }
                            }
                        }
                        step = 2
                    },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Continue") }
            }
            else -> {
                // This used to be a "You're set" screen whose only control was
                // "Start logging" — a step that asked nothing and told the user
                // nothing they could act on. It is now where the numbers get set,
                // which is the thing that was missing from setup entirely.
                Text("Set your targets", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "These are rough defaults. Work them out from your own body, or " +
                        "just type what you already know — you can change them any time " +
                        "in Goals.",
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                BodyCalculator(
                    onCalculated = { targets ->
                        for (metric in selectedMetrics) {
                            val value = targets[metric.goalMeasure] ?: continue
                            targetText[metric] = formatTarget(value)
                            TargetCalculator.baselineFor(metric, value)?.let {
                                baselineText[metric] = formatTarget(it)
                            }
                        }
                    },
                )

                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    selectedMetrics.sortedBy { Metric.entries.indexOf(it) }.forEach { metric ->
                        TargetFields(
                            metric = metric,
                            target = targetText[metric].orEmpty(),
                            baseline = baselineText[metric].orEmpty(),
                            onTarget = { targetText[metric] = it },
                            onBaseline = { baselineText[metric] = it },
                        )
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            goals.applySelection(selectedMetrics)
                            for (metric in selectedMetrics) {
                                goals.setTarget(metric, targetText[metric]?.toDoubleOrNull())
                                if (metric.direction == GoalDirection.REACH) {
                                    goals.setFloor(metric, baselineText[metric]?.toDoubleOrNull())
                                }
                            }
                            // Kept in step for the screens that still read it.
                            targetText[Metric.PROTEIN]?.toDoubleOrNull()
                                ?.let { settings.setDailyGoalGrams(it) }
                            step = 3
                        }
                    },
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                ) { Text("Continue") }
            }
        }
    }

}

/** 160.0 -> "160", 2.5 -> "2.5". Trailing ".0" on a goal reads like false precision. */
private fun formatTarget(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(java.util.Locale.ROOT, "%.1f", value)

/**
 * Target, and for a reach metric a baseline underneath it.
 *
 * Setup used to ask for one number, the protein target, no matter what the user
 * had ticked — so someone tracking calories and sugar finished setup with the
 * shipped defaults and no idea they existed.
 */
@Composable
private fun TargetFields(
    metric: Metric,
    target: String,
    baseline: String,
    onTarget: (String) -> Unit,
    onBaseline: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(metric.goalLabel, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = target,
                onValueChange = onTarget,
                label = {
                    Text(
                        when (metric.direction) {
                            GoalDirection.REACH -> "Target"
                            GoalDirection.LIMIT -> "Limit"
                        },
                    )
                },
                suffix = { Text(metric.unit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            if (metric.direction == GoalDirection.REACH) {
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = baseline,
                    onValueChange = onBaseline,
                    label = { Text("Baseline") },
                    suffix = { Text(metric.unit) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Works targets out from the user's own body instead of handing everyone a
 * 30-year-old man's numbers.
 *
 * Folded away behind a button because it asks for weight and age, which not
 * everyone wants to type into an app, and nothing here needs it — the defaults
 * and hand-typed numbers both work without ever opening this.
 */
@Composable
private fun BodyCalculator(onCalculated: (Map<Metric, Double>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var metricUnits by remember { mutableStateOf(false) }
    var sex by remember { mutableStateOf(TargetCalculator.Sex.MALE) }
    var age by remember { mutableStateOf("30") }
    var feet by remember { mutableStateOf("5") }
    var inches by remember { mutableStateOf("10") }
    var cm by remember { mutableStateOf("178") }
    var pounds by remember { mutableStateOf("170") }
    var kg by remember { mutableStateOf("77") }
    var activity by remember { mutableStateOf(TargetCalculator.Activity.LIGHT) }
    var aim by remember { mutableStateOf(TargetCalculator.Aim.MAINTAIN) }
    var done by remember { mutableStateOf(false) }
    val saveScope = rememberCoroutineScope()

    if (!open) {
        // A button, not a link: every bar on Home is scaled to these answers, so a
        // friend handed the app should not skim past it.
        androidx.compose.material3.OutlinedButton(onClick = { open = true }) { Text("Work these out from my height and weight") }
        return
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("About you", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { metricUnits = !metricUnits }) {
                    Text(if (metricUnits) "cm / kg" else "ft / lb")
                }
            }

            LabelledChips(
                options = TargetCalculator.Sex.entries,
                selected = sex,
                labelOf = { if (it == TargetCalculator.Sex.MALE) "Male" else "Female" },
                onSelect = { sex = it },
            )

            Row(modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = age,
                    onValueChange = { age = it },
                    label = { Text("Age") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (metricUnits) {
                    OutlinedTextField(
                        value = cm,
                        onValueChange = { cm = it },
                        label = { Text("Height") },
                        suffix = { Text("cm") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1.4f),
                    )
                } else {
                    OutlinedTextField(
                        value = feet,
                        onValueChange = { feet = it },
                        label = { Text("Height") },
                        suffix = { Text("ft") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.8f),
                    )
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(
                        value = inches,
                        onValueChange = { inches = it },
                        label = { Text(" ") },
                        suffix = { Text("in") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.8f),
                    )
                }
            }

            OutlinedTextField(
                value = if (metricUnits) kg else pounds,
                onValueChange = { if (metricUnits) kg = it else pounds = it },
                label = { Text("Weight") },
                suffix = { Text(if (metricUnits) "kg" else "lb") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            Text(
                "How active are you?",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            LabelledChips(
                options = TargetCalculator.Activity.entries,
                selected = activity,
                labelOf = { it.label },
                onSelect = { activity = it },
            )
            Text(
                activity.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            Text(
                "What are you after?",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            LabelledChips(
                options = TargetCalculator.Aim.entries,
                selected = aim,
                labelOf = { it.label },
                onSelect = { aim = it },
            )

            Button(
                onClick = {
                    val heightCm = if (metricUnits) {
                        cm.toDoubleOrNull() ?: 178.0
                    } else {
                        TargetCalculator.feetInchesToCm(
                            feet.toIntOrNull() ?: 5,
                            inches.toDoubleOrNull() ?: 10.0,
                        )
                    }
                    val weightKg = if (metricUnits) {
                        kg.toDoubleOrNull() ?: 77.0
                    } else {
                        TargetCalculator.poundsToKg(pounds.toDoubleOrNull() ?: 170.0)
                    }
                    val body = TargetCalculator.Body(
                        sex = sex,
                        ageYears = age.toIntOrNull()?.coerceIn(13, 100) ?: 30,
                        heightCm = heightCm,
                        weightKg = weightKg,
                        activity = activity,
                        aim = aim,
                    )
                    // Kept, so Home's bars scale to this body too (Goals > About you).
                    saveScope.launch {
                        ServiceLocator.settingsRepository.setProfile(
                            com.nutritionscribe.shared.model.Profile(body.sex, body.ageYears, body.heightCm, body.weightKg, body.activity, body.aim),
                        )
                    }
                    onCalculated(com.nutritionscribe.shared.model.Needs.forProfile(
                        com.nutritionscribe.shared.model.Profile(body.sex, body.ageYears, body.heightCm, body.weightKg, body.activity, body.aim),
                    ))
                    done = true
                },
                modifier = Modifier.padding(top = 12.dp),
            ) { Text(if (done) "Update the numbers" else "Fill in my targets") }

            Text(
                "Estimates from your height, weight, age and activity — a starting " +
                    "point, not medical advice. Edit anything below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** A row of single-choice chips over any small enum. */
@Composable
private fun <T> LabelledChips(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(labelOf(option)) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}
