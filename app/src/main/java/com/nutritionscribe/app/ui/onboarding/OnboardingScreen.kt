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
    var apiKey by remember { mutableStateOf("") }
    // Per-metric targets and baselines, filled either by hand or by the
    // calculator. Kept as text so a half-typed number is not thrown away.
    val targetText = remember { mutableStateMapOf<Metric, String>() }
    val baselineText = remember { mutableStateMapOf<Metric, String>() }
    var showKeyHelp by remember { mutableStateOf(false) }
    val keptKey = remember { ServiceLocator.settingsRepository.hasAnyApiKey() }
    val scope = rememberCoroutineScope()
    val settings = ServiceLocator.settingsRepository

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (step) {
            0 -> {
                // This screen used to open with "Add your API key" and hide the
                // offline database behind "Skip — I'll enter it myself", which
                // read as "give us a credit card or type your own numbers". The
                // app works with no key and no network, so that is the headline
                // and the key is the extra. Nobody should have to sign up for a
                // developer account to write down that they ate a banana.
                Text("Ready to go", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Say or type what you ate. The first time you log a food you check it once; " +
                        "after that it logs without asking. 13,400 foods and 230,000 package labels are built in — no " +
                        "account, key or connection needed, and without an AI key nothing you eat leaves your phone.",
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    "This is a test version. It keeps a log on this phone of what you log and how the app " +
                        "behaves, including label photos you scan. Nothing is sent unless you send it: after a week " +
                        "the app will ask you to, and you can switch the log off in Settings > Advanced.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Button(
                    onClick = {
                        scope.launch {
                            settings.setManualMode(false)
                            // A key kept through a reset keeps Highest quality mode on.
                            settings.setLookupSource(if (keptKey) LookupSource.AI else LookupSource.OFFLINE)
                        }
                        step = 1
                    },
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("Start logging") }

                // Recommended, never required: the single biggest accuracy gain for
                // packaged food, but ~40 MB, so it is offered rather than forced.
                val pack = ServiceLocator.labelPack
                val packStatus by pack.status.collectAsState()
                val packPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                ) { uri -> if (uri != null) scope.launch { pack.installFrom(uri) } }
                Card(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Recommended: the label pack", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when (packStatus) {
                                is com.nutritionscribe.app.data.resolve.LabelPack.Status.Installed ->
                                    "Installed. Branded food and barcode scans will use the real package label."
                                is com.nutritionscribe.app.data.resolve.LabelPack.Status.Working ->
                                    "Setting up the package labels that came with the app. This takes a few seconds; you can carry on."
                                else -> "Real Nutrition Facts for about 230,000 packaged products, so branded food and " +
                                    "barcode scans are exact instead of estimated. About 70 MB. You can add it later in Settings."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        if (packStatus !is com.nutritionscribe.app.data.resolve.LabelPack.Status.Installed &&
                            packStatus !is com.nutritionscribe.app.data.resolve.LabelPack.Status.Working) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                if (com.nutritionscribe.app.data.resolve.LabelPack.DOWNLOAD_URL != null) {
                                    Button(onClick = { scope.launch { pack.download() } }) { Text("Download") }
                                }
                                androidx.compose.material3.OutlinedButton(onClick = { packPicker.launch(arrayOf("*/*")) }) {
                                    Text("Install from file")
                                }
                            }
                        }
                    }
                }

                if (keptKey) {
                    // A reset that kept the key lands here too. Showing an empty key
                    // box then read as "your key is gone", and invited pasting it again.
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Your API key is still saved", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Highest quality mode is on: AI checks foods you haven't logged before and turns your speech into text. " +
                                    "You can switch it off or remove the key in Settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                } else {
                Text(
                    "Adding an API key is optional. It costs a fraction of a cent per " +
                        "entry and lets the app understand brands, restaurant meals and " +
                        "whole sentences.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 24.dp),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("sk-… (optional)") },
                    singleLine = true,
                    // A key typed by hand got a space from autocorrect; a key is never words.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(
                    onClick = {
                        settings.setApiKey(AiBackendKind.OPENAI, apiKey.filterNot { it.isWhitespace() })
                        scope.launch {
                            settings.setManualMode(false)
                            settings.setLookupSource(LookupSource.AI)
                        }
                        step = 1
                    },
                    enabled = apiKey.isNotBlank(),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Use this key") }
                TextButton(onClick = { showKeyHelp = !showKeyHelp }) {
                    Text(if (showKeyHelp) "Hide" else "How do I get an API key?")
                }

                if (showKeyHelp) {
                    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Getting a key", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Most people should skip this — the app works fine without it.\n\n" +
                                    "1. Go to platform.openai.com and make an account.\n" +
                                    "2. Add a payment method. Lookups cost a fraction of a cent " +
                                    "each; a few dollars lasts a very long time.\n" +
                                    "3. Open the API keys page and create a new secret key.\n" +
                                    "4. Copy it and paste it above. It starts with \"sk-\".\n\n" +
                                    "You can add this later in Settings — nothing is lost by " +
                                    "skipping now.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
                }
            }
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
                            settings.setOnboarded(true)
                            onFinished()
                        }
                    },
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                ) { Text("Start logging") }
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
