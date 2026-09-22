package com.proteinscribe.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.proteinscribe.app.data.repository.AiBackendKind
import com.proteinscribe.app.di.ServiceLocator
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var apiKey by remember { mutableStateOf("") }
    var goalText by remember { mutableStateOf("160") }
    var manualMode by remember { mutableStateOf(false) }
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
                        manualMode = false
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
                Text("Set your daily goal", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Grams of protein per day.",
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                OutlinedTextField(
                    value = goalText,
                    onValueChange = { goalText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        scope.launch { settings.setDailyGoalGrams(goalText.toDoubleOrNull() ?: 160.0) }
                        step = 2
                    },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Continue") }
            }
            else -> {
                Text("You're set", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (manualMode) {
                        "Tap + to add what you ate and its protein. The app remembers what " +
                            "you log often, so repeat meals are one tap."
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
            title = { Text("You'll be entering things by hand") },
            text = {
                Text(
                    "Without a key, the app can't look up nutrition for you. Every time you " +
                        "log something you'll type the food and its protein in grams yourself " +
                        "— you can read the grams off the package or look it up.\n\n" +
                        "Everything else works exactly the same: history, charts, export. " +
                        "Foods you log often become one-tap buttons.\n\n" +
                        "You can add a key any time in Settings.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { settings.setManualMode(true) }
                        manualMode = true
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
