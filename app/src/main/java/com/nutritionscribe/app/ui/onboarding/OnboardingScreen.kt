package com.nutritionscribe.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val goals = ServiceLocator.goalsRepository
    var selectedMetrics by remember { mutableStateOf(MetricGoal.STARTER_METRICS.toSet()) }
    var apiKey by remember { mutableStateOf("") }
    var goalText by remember { mutableStateOf("160") }
    // Which path the user ended up on, for the closing message only.
    var offlineOnly by remember { mutableStateOf(false) }
    var showSkipNotice by remember { mutableStateOf(false) }
    var showKeyHelp by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settings = ServiceLocator.settingsRepository

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (step) {
            0 -> {
                Text("Add your API key", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "With a key, you just say what you ate and the app works out the protein. " +
                        "A key is not the same thing as a Claude or ChatGPT subscription.",
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.secondary,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("sk-...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        settings.setApiKey(AiBackendKind.OPENAI, apiKey.trim())
                        scope.launch { settings.setManualMode(false) }
                        offlineOnly = false
                        step = 1
                    },
                    enabled = apiKey.isNotBlank(),
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Continue") }

                TextButton(onClick = { showSkipNotice = true }) {
                    Text("Skip — I'll enter it myself")
                }
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
                    Metric.entries.forEach { metric ->
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
                                Text(metric.label, style = MaterialTheme.typography.bodyLarge)
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

                    if (Metric.PROTEIN in selectedMetrics) {
                        Text(
                            "Daily protein target",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        OutlinedTextField(
                            value = goalText,
                            onValueChange = { goalText = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                        scope.launch {
                            val protein = goalText.toDoubleOrNull() ?: 160.0
                            goals.applySelection(selectedMetrics)
                            if (Metric.PROTEIN in selectedMetrics) {
                                goals.setTarget(Metric.PROTEIN, protein)
                            }
                            // Kept in step for the screens that still read it.
                            settings.setDailyGoalGrams(protein)
                        }
                        step = 2
                    },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Continue") }
            }
            else -> {
                Text("You're set", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (offlineOnly) {
                        "Type what you ate and the app finds it in its built-in food list. " +
                            "Foods you log often become one-tap buttons."
                    } else {
                        "Type or speak what you ate."
                    },
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Button(
                    onClick = { scope.launch { settings.setOnboarded(true); onFinished() } },
                ) { Text("Start logging") }
            }
        }
    }

    if (showSkipNotice) {
        AlertDialog(
            onDismissRequest = { showSkipNotice = false },
            title = { Text("You'll use the built-in food list") },
            text = {
                Text(
                    "No key needed. The app looks food up in its own list of about 13,000 " +
                        "foods, built in. It's instant, it works with no signal, and nothing " +
                        "you eat ever leaves your phone.\n\n" +
                        "It won't know brand names or restaurant dishes. For those you type " +
                        "the numbers in yourself — off the packet, usually.\n\n" +
                        "Everything else works exactly the same: history, charts, export. " +
                        "Foods you log often become one-tap buttons.\n\n" +
                        "You can add a key any time in Settings.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Skipping the key no longer means typing every number by
                        // hand — it means using the bundled food database. Setting
                        // manualMode here would switch off the very thing that makes
                        // the no-key path worth having.
                        scope.launch { settings.setLookupSource(LookupSource.OFFLINE) }
                        offlineOnly = true
                        showSkipNotice = false
                        step = 1
                    },
                ) { Text("That's fine, continue") }
            },
            dismissButton = {
                TextButton(onClick = { showSkipNotice = false }) { Text("Go back") }
            },
        )
    }
}
