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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
import com.nutritionscribe.app.data.repository.LookupSource
import com.nutritionscribe.app.data.repository.ResetManager
import com.nutritionscribe.app.data.repository.ThemeMode
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.retention.RetentionWindow
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    val settings = ServiceLocator.settingsRepository
    val reset = ServiceLocator.resetManager
}

@Composable
fun SettingsScreen(
    onOpenDebugLog: () -> Unit,
    onOpenExportImport: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings = viewModel.settings
    val scope = rememberCoroutineScope()
    val backend by settings.backend.collectAsState(initial = AiBackendKind.OPENAI)
    val theme by settings.theme.collectAsState(initial = ThemeMode.SYSTEM)
    val lookupSource by settings.lookupSource.collectAsState(initial = LookupSource.AI)
    val totalsWindow by settings.retainProteinTotals.collectAsState(initial = RetentionWindow.DEFAULT_PROTEIN_TOTALS)
    val detailsWindow by settings.retainFoodDetails.collectAsState(initial = RetentionWindow.DEFAULT_FOOD_DETAILS)

    var apiKeyText by remember { mutableStateOf(settings.getApiKey(backend).orEmpty()) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    val manualMode by settings.manualMode.collectAsState(initial = false)

    // The screen has outgrown one phone height; without this the lower rows are
    // unreachable rather than merely cramped.
    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        // The daily goal and baseline moved to the Goals tab, where they sit
        // beside the other eight metrics instead of being a protein-shaped
        // special case in Settings.
        Text(
            "Daily targets live in the Goals tab.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enter the numbers myself", style = MaterialTheme.typography.titleMedium)
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

        if (!manualMode) {
            Text(
                "Look up nutrition with",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = lookupSource == LookupSource.AI,
                    onClick = { scope.launch { settings.setLookupSource(LookupSource.AI) } },
                    label = { Text("AI") },
                )
                FilterChip(
                    selected = lookupSource == LookupSource.OFFLINE,
                    onClick = { scope.launch { settings.setLookupSource(LookupSource.OFFLINE) } },
                    label = { Text("Offline food list") },
                )
            }
            Text(
                when (lookupSource) {
                    LookupSource.AI ->
                        "Understands anything you can say — brands, restaurant meals, whole " +
                            "sentences. Needs an API key and a connection."
                    LookupSource.OFFLINE ->
                        "13,400 USDA foods built into the app. Instant, free, works on a " +
                            "plane, and nothing you eat leaves your phone. Doesn't know brands."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
        }

        if (!manualMode && apiKeyText.isBlank() && lookupSource == LookupSource.AI) {
            Text(
                "No API key set, so the offline food list is being used instead. " +
                    "Add a key below, or pick \"Offline food list\" to stop seeing this.",
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

        HorizontalDivider(modifier = Modifier.padding(top = 28.dp, bottom = 20.dp))
        ResetSection(reset = viewModel.reset, onOpenExportImport = onOpenExportImport)
    }
}

/**
 * Starting again, without uninstalling.
 *
 * Phrased as what to KEEP rather than what to erase, because that is how people
 * describe it — "reset it but keep my log". Everything is kept by default, so the
 * dialog opens on the harmless setting and the destructive button is disabled
 * until the user actually asks for something to go.
 */
@Composable
private fun ResetSection(reset: ResetManager, onOpenExportImport: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var keepFoodLog by remember { mutableStateOf(true) }
    var keepSettings by remember { mutableStateOf(true) }
    var keepApiKey by remember { mutableStateOf(true) }
    var entryCount by remember { mutableStateOf<Int?>(null) }
    var hasKey by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Read the counts when the dialog opens, so the confirmation can say what it
    // is about to destroy in numbers rather than in the abstract.
    LaunchedEffect(showDialog) {
        if (showDialog) {
            entryCount = reset.loggedEntryCount()
            hasKey = reset.hasApiKey()
        }
    }

    Text("Reset", style = MaterialTheme.typography.titleMedium)
    Text(
        "Clear the app back to how it arrived — all of it, or just the parts you " +
            "choose.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    TextButton(
        onClick = {
            keepFoodLog = true; keepSettings = true; keepApiKey = true
            confirming = false
            showDialog = true
        },
    ) {
        Text("Reset app…", color = MaterialTheme.colorScheme.error)
    }

    if (!showDialog) return

    val erasing = ResetManager.Scope(
        foodLog = !keepFoodLog,
        settings = !keepSettings,
        apiKey = !keepApiKey,
    )
    val count = entryCount

    AlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text(if (confirming) "Erase this for good?" else "Reset app") },
        text = {
            Column {
                if (!confirming) {
                    Text(
                        "Anything you leave switched on is kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    KeepRow(
                        label = "Keep my food log",
                        detail = when (count) {
                            null -> "counting…"
                            1 -> "1 entry"
                            else -> "$count entries"
                        },
                        checked = keepFoodLog,
                        onChange = { keepFoodLog = it },
                    )
                    KeepRow(
                        label = "Keep goals and settings",
                        detail = "what you track, targets, theme",
                        checked = keepSettings,
                        onChange = { keepSettings = it },
                    )
                    if (hasKey) {
                        KeepRow(
                            label = "Keep my API key",
                            detail = "stays on this phone either way",
                            checked = keepApiKey,
                            onChange = { keepApiKey = it },
                        )
                    }
                    if (erasing.foodLog && (count ?: 0) > 0) {
                        Text(
                            "Export first if you might want this back — there is no undo " +
                                "and no cloud copy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        TextButton(onClick = { showDialog = false; onOpenExportImport() }) {
                            Text("Export first")
                        }
                    }
                } else {
                    Text(erasureSummary(erasing, count))
                    Text(
                        "This cannot be undone.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = erasing.erasesAnything,
                onClick = {
                    if (!confirming) {
                        confirming = true
                    } else {
                        scope.launch {
                            reset.reset(erasing)
                            showDialog = false
                        }
                    }
                },
            ) {
                Text(
                    if (confirming) "Erase" else "Continue",
                    color = if (erasing.erasesAnything) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { if (confirming) confirming = false else showDialog = false }) {
                Text(if (confirming) "Back" else "Cancel")
            }
        },
    )
}

@Composable
private fun KeepRow(
    label: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Names exactly what is about to go, in the user's own terms. */
private fun erasureSummary(scope: ResetManager.Scope, entryCount: Int?): String {
    val parts = buildList {
        if (scope.foodLog) {
            add(
                when (entryCount) {
                    null, 0 -> "your food log"
                    1 -> "your food log (1 entry)"
                    else -> "your food log ($entryCount entries)"
                },
            )
        }
        if (scope.settings) add("your goals and settings")
        if (scope.apiKey) add("your API key")
    }
    val list = when (parts.size) {
        0 -> return "Nothing is selected to erase."
        1 -> parts[0]
        2 -> "${parts[0]} and ${parts[1]}"
        else -> "${parts.dropLast(1).joinToString(", ")} and ${parts.last()}"
    }
    return "This erases $list."
}
