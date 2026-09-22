package com.nutritionscribe.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.data.repository.AiBackendKind
import com.nutritionscribe.app.data.repository.ThemeMode
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.retention.RetentionWindow
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    val settings = ServiceLocator.settingsRepository
}

@Composable
fun SettingsScreen(
    onOpenDebugLog: () -> Unit,
    onOpenExportImport: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings = viewModel.settings
    val scope = rememberCoroutineScope()
    val goal by settings.dailyGoalGrams.collectAsState(initial = 160.0)
    val baseline by settings.dailyBaselineGrams.collectAsState(initial = 112.0)
    val backend by settings.backend.collectAsState(initial = AiBackendKind.OPENAI)
    val theme by settings.theme.collectAsState(initial = ThemeMode.SYSTEM)
    val totalsWindow by settings.retainProteinTotals.collectAsState(initial = RetentionWindow.DEFAULT_PROTEIN_TOTALS)
    val detailsWindow by settings.retainFoodDetails.collectAsState(initial = RetentionWindow.DEFAULT_FOOD_DETAILS)

    var goalText by remember(goal) { mutableStateOf(goal.toInt().toString()) }
    var baselineText by remember(baseline) { mutableStateOf(baseline.toInt().toString()) }
    var apiKeyText by remember { mutableStateOf(settings.getApiKey(backend).orEmpty()) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    val manualMode by settings.manualMode.collectAsState(initial = false)

    // The screen has outgrown one phone height; without this the lower rows are
    // unreachable rather than merely cramped.
    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Daily goal", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = goalText,
            onValueChange = {
                goalText = it
                it.toDoubleOrNull()?.let { g -> scope.launch { settings.setDailyGoalGrams(g) } }
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )

        Text("Daily baseline", style = MaterialTheme.typography.titleMedium)
        Text(
            "Second reference line on the History chart — days below this read red.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        OutlinedTextField(
            value = baselineText,
            onValueChange = {
                baselineText = it
                it.toDoubleOrNull()?.let { b -> scope.launch { settings.setDailyBaselineGrams(b) } }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enter protein myself", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Skip AI lookup and type the grams by hand. Works with no API key, " +
                        "and works offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Switch(
                checked = manualMode,
                onCheckedChange = { scope.launch { settings.setManualMode(it) } },
            )
        }

        if (!manualMode && apiKeyText.isBlank()) {
            Text(
                "No API key set, so entries are logged by hand until you add one below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        Text("API key", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = apiKeyText,
            onValueChange = {
                apiKeyText = it
                settings.setApiKey(backend, it)
            },
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (apiKeyVisible) "Hide API key" else "Show API key",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )

        Text(
            "Keep protein totals: ${totalsWindow.amount} ${totalsWindow.unit.name.lowercase()}",
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            "Keep food details: ${detailsWindow.amount} ${detailsWindow.unit.name.lowercase()}",
            modifier = Modifier.padding(bottom = 16.dp),
        )

        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.padding(bottom = 24.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == theme,
                    onClick = { scope.launch { settings.setTheme(mode) } },
                    label = { Text(mode.name) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        Text(
            "Debug log",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onOpenDebugLog),
        )
        Text(
            "Export and import",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickable(onClick = onOpenExportImport),
        )
    }
}
