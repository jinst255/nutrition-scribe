package com.nutritionscribe.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.data.repository.AiBackendKind
import com.nutritionscribe.app.data.repository.LookupSource
import com.nutritionscribe.app.data.repository.ResetManager
import com.nutritionscribe.app.data.repository.ThemeMode
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.retention.RetentionUnit
import com.nutritionscribe.shared.retention.RetentionWindow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    val settings = ServiceLocator.settingsRepository
    val reset = ServiceLocator.resetManager
}

@Composable
fun SettingsScreen(
    onOpenDebugLog: () -> Unit,
    onOpenExportImport: () -> Unit,
    onOpenMyFoods: () -> Unit = {},
    onOpenSpending: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings = viewModel.settings
    val scope = rememberCoroutineScope()
    val events = ServiceLocator.debugEvents
    val backend by settings.backend.collectAsState(initial = AiBackendKind.OPENAI)
    val theme by settings.theme.collectAsState(initial = ThemeMode.SYSTEM)
    val lookupSource by settings.lookupSource.collectAsState(initial = LookupSource.AI)
    val totalsWindow by settings.retainProteinTotals.collectAsState(initial = RetentionWindow.DEFAULT_PROTEIN_TOTALS)
    val detailsWindow by settings.retainFoodDetails.collectAsState(initial = RetentionWindow.DEFAULT_FOOD_DETAILS)
    val micMode by settings.micMode.collectAsState(initial = com.nutritionscribe.app.data.repository.MicMode.TAP)
    val showAdvanced by settings.showAdvanced.collectAsState(initial = false)
    val debugMode by settings.debugMode.collectAsState(initial = false)

    var apiKeyText by remember { mutableStateOf(settings.getApiKey(backend).orEmpty()) }
    // Re-read when the provider changes; hasAnyApiKey is not observable.
    LaunchedEffect(backend) { apiKeyText = settings.getApiKey(backend).orEmpty() }
    var apiKeyVisible by remember { mutableStateOf(false) }
    val manualMode by settings.manualMode.collectAsState(initial = false)
    val hasKey = remember(apiKeyText, backend) { settings.hasAnyApiKey() }
    val quality = lookupSource == LookupSource.AI && hasKey
    val consent by settings.aiConsent.collectAsState(initial = emptySet())
    var askConsent by remember { mutableStateOf<AiBackendKind?>(null) }
    var turningOn by remember { mutableStateOf(false) }
    // Turning AI on for a provider they haven't agreed to asks first; nothing is
    // switched on, and nothing sent, until they say yes.
    val setQuality: (Boolean) -> Unit = { on ->
        events.event("setting_changed", "setting" to "highest_quality", "value" to on)
        if (on && !com.nutritionscribe.app.data.repository.AiConsent.agreed(consent, backend)) { turningOn = true; askConsent = backend }
        else scope.launch { settings.setLookupSource(if (on) LookupSource.AI else LookupSource.OFFLINE) }
    }
    askConsent?.let { kind ->
        com.nutritionscribe.app.ui.common.AiConsentDialog(kind) { agreed ->
            askConsent = null
            events.always("ai_consent", "provider" to kind.name, "agreed" to agreed, "where" to "settings")
            scope.launch {
                settings.setAiConsent(kind, agreed)
                if (agreed && turningOn) settings.setLookupSource(LookupSource.AI)
                if (!agreed && kind == backend) settings.setLookupSource(LookupSource.OFFLINE)
                turningOn = false
            }
        }
    }

    val monthStart = remember {
        java.time.LocalDate.now().withDayOfMonth(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val monthCost by remember { ServiceLocator.database.aiUsageDao().observeCostSince(monthStart) }.collectAsState(initial = 0.0)
    val everSpent by remember { ServiceLocator.database.aiUsageDao().observeCostSince(0) }.collectAsState(initial = 0.0)

    // The screen has outgrown one phone height; without this the lower rows are
    // unreachable rather than merely cramped.
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            // No top padding: the first SectionHeader's own 26dp sits under
            // the title bar, and adding more left a gap that looked like a bug.
            .padding(horizontal = 16.dp),
    ) {
        // ---- The everyday settings. Everything a friend who was handed the
        // app might touch is up here, in their words; the machinery is under
        // Advanced.
        SectionHeader("Logging")

        SettingRow(
            title = "Highest quality mode",
            subtitle = when {
                !hasKey -> "Needs an API key. Whoever set up your app can add one under Advanced settings. Everything works without it."
                quality -> "On. AI checks foods you haven't logged before and turns your speech into text. About a tenth of a cent an entry. If it can't be reached, the app carries on without it."
                else -> "Off. Foods come from the built-in list and your own foods, and speech is recognised on the phone. Free."
            },
            onClick = if (hasKey) ({ setQuality(!quality) }) else null,
        ) {
            Switch(
                checked = quality,
                enabled = hasKey,
                onCheckedChange = { setQuality(it) },
            )
        }
        if (hasKey) {
            val agreedTo = AiBackendKind.entries.filter { com.nutritionscribe.app.data.repository.AiConsent.agreed(consent, it) }
            NavRow(
                title = "AI privacy",
                subtitle = if (agreedTo.isEmpty()) "Nothing is sent to an AI provider. Tap to see what would be."
                    else "You agreed to send food descriptions to ${agreedTo.joinToString(" and ") { com.nutritionscribe.app.data.repository.AiConsent.provider(it).name }}. Tap to review.",
                onClick = { turningOn = false; askConsent = backend },
            )
        }
        if (hasKey || everSpent > 0) {
            NavRow(
                title = "AI spending",
                subtitle = "${com.nutritionscribe.app.ui.cost.formatUsd(monthCost)} this month \u00B7 see what it costs over time",
                onClick = onOpenSpending,
            )
        }

        Text("Microphone", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        Hint(
            if (micMode == com.nutritionscribe.app.data.repository.MicMode.TAP)
                "Tap the microphone to start, and again when you're done. Pauses never end it."
            else "Hold the microphone while you talk and let go when you're done. Pauses never end it.",
        )
        com.nutritionscribe.app.ui.speech.MicModeSwitch(
            micMode,
            {
                events.event("setting_changed", "setting" to "mic_mode", "value" to it)
                scope.launch { settings.setMicMode(it) }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (manualMode) {
            Hint("\u201CEnter the numbers myself\u201D is on (Advanced settings), so lookups and the microphone are off.")
        }

        // ---- Food data: local, free, nothing to set up ------------------------
        SectionHeader("Food data")
        val myFoods by remember {
            ServiceLocator.database.personalFoodDao().observeAll().map { it.size }
        }.collectAsState(initial = 0)
        NavRow(
            title = "My foods",
            subtitle = if (myFoods == 0) "Foods you confirm are remembered here and log without asking."
            else "$myFoods remembered. They log without asking \u2014 view, change or forget them.",
            onClick = onOpenMyFoods,
        )
        LabelPackRow(advanced = false)
        Hint("Built in: 13,400 USDA foods and a hand-checked list of everyday ones. Works offline, costs nothing.")

        // ---- Appearance ---------------------------------------------------
        SectionHeader("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == theme,
                    onClick = { scope.launch { settings.setTheme(mode) } },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        // ---- Your data -----------------------------------------------------
        SectionHeader("Your data")
        NavRow(
            title = "Back up & restore",
            subtitle = "Save your log to a file, or bring one back. It's the only copy there is.",
            onClick = onOpenExportImport,
        )
        // Out here rather than under Debugging: someone testing the app is asked to
        // send this, and shouldn't have to find an advanced switch first.
        val context = androidx.compose.ui.platform.LocalContext.current
        val sentAt by settings.logSentAt.collectAsState(initial = null)
        NavRow(
            title = "Send my log",
            subtitle = "For whoever gave you the app: what you logged and how the app behaved, so they can fix problems." +
                (sentAt?.let { " Last sent ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it))}." } ?: ""),
            onClick = { scope.launch { com.nutritionscribe.app.ui.debuglog.LogShare.share(context, "settings") } },
        )

        HorizontalDivider(modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        SettingRow(
            title = "Show advanced settings",
            subtitle = "API key, label pack file, how long data is kept, debug mode and reset.",
            onClick = { scope.launch { settings.setShowAdvanced(!showAdvanced) } },
        ) {
            Switch(checked = showAdvanced, onCheckedChange = { scope.launch { settings.setShowAdvanced(it) } })
        }

        if (!showAdvanced) {
            Spacer(Modifier.padding(bottom = 32.dp))
            return@Column
        }

        // =====================================================================
        // Advanced
        // =====================================================================
        SectionHeader("Advanced \u00B7 Logging")
        SettingRow(
            title = "Enter the numbers myself",
            subtitle = "Skip lookup entirely and type the amounts by hand. " +
                "Works with no key and no connection.",
            onClick = { scope.launch { settings.setManualMode(!manualMode) } },
        ) {
            Switch(
                checked = manualMode,
                onCheckedChange = {
                    events.event("setting_changed", "setting" to "manual_mode", "value" to it)
                    scope.launch { settings.setManualMode(it) }
                },
            )
        }

        SectionHeader("Advanced \u00B7 AI key")
        Text(
            "API key",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        OutlinedTextField(
            value = apiKeyText,
            onValueChange = {
                apiKeyText = it
                settings.setApiKey(backend, it.filterNot { c -> c.isWhitespace() })
            },
            // singleLine is the fix for a key that wrapped to four lines and
            // took over the screen.
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrect = false,
            ),
            placeholder = { Text("Optional \u2014 sk-\u2026") },
            visualTransformation = if (apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (apiKeyVisible) "Hide API key" else "Show API key",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Hint("Stored encrypted on this phone. Never sent anywhere but the provider. With a key here, Highest quality mode (top of this screen) can be switched on.")

        Text(
            "Provider",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiBackendKind.entries.forEach { kind ->
                FilterChip(
                    selected = kind == backend,
                    onClick = {
                        scope.launch { settings.setBackend(kind) }
                        apiKeyText = settings.getApiKey(kind).orEmpty()
                    },
                    label = { Text(if (kind == AiBackendKind.OPENAI) "OpenAI" else "Anthropic") },
                )
            }
        }
        if (backend == AiBackendKind.ANTHROPIC) {
            Hint("With an Anthropic key, AI checks foods but speech stays on the phone: better speech needs an OpenAI key.")
        }

        SectionHeader("Advanced \u00B7 Label pack")
        LabelPackRow(advanced = true)

        // ---- How long things are kept -------------------------------------
        // These were display-only, which made them look like settings and behave
        // like decoration. They are the one place a user can stop the app
        // throwing their own history away, so they have to be reachable.
        SectionHeader("Advanced \u00B7 Keep my data for")
        RetentionRow(
            title = "Daily totals",
            subtitle = "The numbers behind the charts.",
            window = totalsWindow,
            onChange = { scope.launch { settings.setRetainProteinTotals(it) } },
        )
        RetentionRow(
            title = "Food details",
            subtitle = "What you actually said, and which food it matched.",
            window = detailsWindow,
            onChange = { scope.launch { settings.setRetainFoodDetails(it) } },
        )

        SectionHeader("Advanced \u00B7 Debugging")
        SettingRow(
            title = "Debug mode",
            subtitle = "Keeps a detailed record of everything you do and say in the app, and what it did back, " +
                "so problems can be found from the file. It stays on this phone until you save or share it.",
            onClick = {
                scope.launch {
                    settings.setDebugMode(!debugMode)
                    events.enabled = !debugMode
                    if (!debugMode) events.event("debug_mode_on", ServiceLocator.appSnapshot())
                }
            },
        ) {
            Switch(
                checked = debugMode,
                onCheckedChange = { on ->
                    scope.launch {
                        settings.setDebugMode(on)
                        events.enabled = on
                        if (on) events.event("debug_mode_on", ServiceLocator.appSnapshot())
                    }
                },
            )
        }
        NavRow(
            title = "Debug log",
            subtitle = if (debugMode) "Recording. Save it to Downloads, share it, or clear it." else "Errors only. Turn on debug mode for the full record.",
            onClick = onOpenDebugLog,
        )

        HorizontalDivider(modifier = Modifier.padding(top = 28.dp, bottom = 20.dp))
        ResetSection(reset = viewModel.reset, onOpenExportImport = onOpenExportImport)
        Spacer(Modifier.padding(bottom = 32.dp))
    }
}

/**
 * The optional label pack: install from a file now, download once there is a place
 * to download it from. Recommended, never required.
 */
@Composable
private fun LabelPackRow(advanced: Boolean) {
    val pack = ServiceLocator.labelPack
    val status by pack.status.collectAsState()
    val scope = rememberCoroutineScope()
    var lastError by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val r = pack.installFrom(uri)
            lastError = (r as? com.nutritionscribe.app.data.resolve.LabelPack.Status.Failed)?.message
        }
    }
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("Label pack", style = MaterialTheme.typography.bodyLarge)
        Text(
            when (val s = status) {
                is com.nutritionscribe.app.data.resolve.LabelPack.Status.Installed ->
                    "Installed: ${"%,d".format(s.products)} real package labels (${s.bytes / 1_000_000} MB). Branded food and barcode scans use the actual label."
                is com.nutritionscribe.app.data.resolve.LabelPack.Status.Working -> "${s.what}\u2026 ${if (s.bytes > 0) "${s.bytes / 1_000_000} MB" else ""}"
                is com.nutritionscribe.app.data.resolve.LabelPack.Status.Failed -> s.message
                else -> if (advanced) "Recommended. Real package labels for about 230,000 products, so branded food and barcode scans are exact instead of estimated. About 40 MB."
                else "Real package labels for 230,000 products, for exact branded food and barcode scans."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        val installed = status is com.nutritionscribe.app.data.resolve.LabelPack.Status.Installed
        if (!advanced && !installed && com.nutritionscribe.app.data.resolve.LabelPack.DOWNLOAD_URL == null) {
            Text(
                "Not installed. Whoever set up your app can add it (Advanced settings \u2192 Label pack).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (installed) {
                if (advanced) androidx.compose.material3.OutlinedButton(onClick = { pack.remove() }) { Text("Remove") }
            } else {
                if (com.nutritionscribe.app.data.resolve.LabelPack.DOWNLOAD_URL != null) {
                    androidx.compose.material3.Button(onClick = { scope.launch { pack.download() } }) { Text("Download") }
                }
                if (advanced) {
                    androidx.compose.material3.OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Install from file") }
                }
            }
        }
    }
}

/** A labelled break in a long screen. The settings list had none, so every row
 *  carried the same weight and nothing grouped with anything. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 26.dp, bottom = 8.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Title, explanation, and a control on the right. */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    /** Tapping anywhere on the row does what the switch does; only the switch used to respond. */
    onClick: (() -> Unit)? = null,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        control()
    }
}

/** A row that opens another screen. Previously these were bare headings, so
 *  nothing about them said they could be tapped. */
@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
    }
}

/**
 * A retention window the user can actually change.
 *
 * The choices are deliberately a short list rather than a number field plus a
 * unit picker: "how long do you want this kept" has about five sensible answers,
 * and asking someone to compose one out of two controls is more work for a worse
 * result.
 */
@Composable
private fun RetentionRow(
    title: String,
    subtitle: String,
    window: RetentionWindow,
    onChange: (RetentionWindow) -> Unit,
) {
    val choices = listOf(
        RetentionWindow(3, RetentionUnit.MONTHS) to "3 months",
        RetentionWindow(1, RetentionUnit.YEARS) to "1 year",
        RetentionWindow(5, RetentionUnit.YEARS) to "5 years",
        RetentionWindow(0, RetentionUnit.INDEFINITE) to "Forever",
    )
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { (candidate, label) ->
                FilterChip(
                    selected = candidate.unit == window.unit && candidate.amount == window.amount,
                    onClick = { onChange(candidate) },
                    label = { Text(label) },
                )
            }
        }
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
    var keepLabelPack by remember { mutableStateOf(true) }
    var hasPack by remember { mutableStateOf(false) }
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
            hasPack = reset.hasLabelPack()
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
            keepFoodLog = true; keepSettings = true; keepApiKey = true; keepLabelPack = true
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
        labelPack = hasPack && !keepLabelPack,
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
                            1 -> "1 entry, with your foods and AI spending history"
                            else -> "$count entries, with your foods and AI spending history"
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
                    if (hasPack) {
                        KeepRow(
                            label = "Keep the label pack",
                            detail = "230,000 package labels, about 70 MB \u2014 nothing personal in it",
                            checked = keepLabelPack,
                            onChange = { keepLabelPack = it },
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
                            ServiceLocator.debugEvents.event("reset", "scope" to erasing.toString(), "entries" to count)
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
        if (scope.labelPack) add("the label pack")
    }
    val list = when (parts.size) {
        0 -> return "Nothing is selected to erase."
        1 -> parts[0]
        2 -> "${parts[0]} and ${parts[1]}"
        else -> "${parts.dropLast(1).joinToString(", ")} and ${parts.last()}"
    }
    return "This erases $list."
}
