package com.nutritionscribe.app.ui.settings

import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenDebugLog: () -> Unit,
    onOpenExportImport: () -> Unit,
    onOpenMyFoods: () -> Unit = {},
    onOpenSpending: () -> Unit = {},
    onOpenAiModels: () -> Unit = {},
    onOpenFeedback: () -> Unit = {},
    onOpenMyLabels: () -> Unit = {},
    onOpenStoreFoods: () -> Unit = {},
    onOpenWatch: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenIdea: () -> Unit = {},
    onOpenSent: () -> Unit = {},
    onOpenCheckIn: () -> Unit = {},
    onOpenGuide: () -> Unit = {},
    onOpenLogContents: () -> Unit = {},
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
    val offline = com.nutritionscribe.app.data.repository.OfflineMode.isOffline(lookupSource, hasKey)
    val consent by settings.aiConsent.collectAsState(initial = emptySet())
    var askConsent by remember { mutableStateOf<AiBackendKind?>(null) }
    var turningOn by remember { mutableStateOf(false) }
    // Turning AI on for a provider they haven't agreed to asks first; nothing is
    // switched on, and nothing sent, until they say yes.
    var needKeyNoteSignal by remember { mutableStateOf(0) }
    val setOffline: (Boolean) -> Unit = { on ->
        events.event("setting_changed", "setting" to "offline_mode", "value" to on)
        when (val source = com.nutritionscribe.app.data.repository.OfflineMode.sourceFor(on, hasKey)) {
            null -> needKeyNoteSignal++
            LookupSource.AI -> if (!com.nutritionscribe.app.data.repository.AiConsent.agreed(consent, backend)) { turningOn = true; askConsent = backend }
                else scope.launch { settings.setLookupSource(source) }
            else -> scope.launch { settings.setLookupSource(source) }
        }
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
    // unreachable rather than merely cramped. Grouped in cards (note S1): the Beta
    // card set the look, and everything under it was a plain list.
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val testing = remember(com.nutritionscribe.app.ui.feedback.BetaMode.left.value) { com.nutritionscribe.app.ui.feedback.isTestBuild(ServiceLocator.appContext) }
        if (testing) {
            SectionHeader("Beta testing")
            com.nutritionscribe.app.ui.feedback.BetaSection(
                onTellUs = { events.event("beta_open", "what" to "tell_us"); onOpenFeedback() },
                onIdea = { events.event("beta_open", "what" to "idea"); onOpenIdea() },
                onSent = { events.event("beta_open", "what" to "sent"); onOpenSent() },
                onCheckIn = { events.event("check_in_open", "where" to "settings"); onOpenCheckIn() },
                onGuide = { events.event("beta_open", "what" to "guide"); onOpenGuide() },
                onLogContents = { events.event("beta_open", "what" to "log_contents"); onOpenLogContents() },
            )
        }

        // ---- Their food -----------------------------------------------------
        SectionHeader("Your food")
        val saved by remember {
            ServiceLocator.database.personalFoodDao().observeAll().map { rows -> rows.mapNotNull { ServiceLocator.personalStore.decode(it) } }
        }.collectAsState(initial = emptyList())
        val meals = saved.count { it.isMeal }
        val recipes = saved.count { it.isRecipe }
        val labelCount = saved.count { !it.isMeal && it.candidate?.origin == com.nutritionscribe.shared.resolve.Origin.LABEL }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FoodTile(Icons.Outlined.Restaurant, "My foods",
                if (saved.isEmpty()) "Foods you confirm land here" else "${saved.size} saved" +
                    (if (recipes > 0) " \u00B7 $recipes recipe" + (if (recipes == 1) "" else "s") else "") +
                    (if (meals > 0) " \u00B7 $meals meal" + (if (meals == 1) "" else "s") else ""),
                Modifier.weight(1f), onOpenMyFoods)
            FoodTile(Icons.Outlined.ReceiptLong, "My labels", if (labelCount == 0) "Scan a label to keep it" else "$labelCount labels",
                Modifier.weight(1f), onOpenMyLabels)
        }
        val stores by ServiceLocator.storePacks.installed.collectAsState()
        SettingsCard {
            IconRow(
                Icons.Outlined.Storefront, "Store foods",
                if (stores.isEmpty()) "Extra labels for the stores you shop at, like Costco or Walmart"
                else stores.joinToString(", ") { it.store.title } + " on this phone",
                onClick = { ServiceLocator.debugEvents.event("store_foods_open", "where" to "settings"); onOpenStoreFoods() },
            )
        }

        // ---- Watch (P1, 11-20) ---------------------------------------------------
        SectionHeader("Watch")
        val watch by com.nutritionscribe.app.data.WatchLink.status.collectAsState()
        val watchCtx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.LaunchedEffect(Unit) { com.nutritionscribe.app.data.WatchLink.refresh(watchCtx.applicationContext) }
        SettingsCard {
            IconRow(
                Icons.Outlined.Watch, "Watch",
                when {
                    watch.connected -> "${watch.name ?: "Watch"} connected · its home and watch-face shortcuts"
                    watch.paired -> "${watch.name ?: "Watch"} not in reach · its home and watch-face shortcuts"
                    else -> "What the watch home shows, and its watch-face shortcuts"
                },
                onClick = { ServiceLocator.debugEvents.event("watch_settings_open", "where" to "settings"); onOpenWatch() },
            )
        }

        // ---- AI -------------------------------------------------------------
        SectionHeader("AI")
        var keyOpen by remember { mutableStateOf(false) }
        var needKeyNote by remember { mutableStateOf(false) }
        LaunchedEffect(needKeyNoteSignal) { if (needKeyNoteSignal > 0) needKeyNote = true }
        var checking by remember { mutableStateOf(false) }
        var checked by remember { mutableStateOf<String?>(null) }
        val webSearch by settings.webSearch.collectAsState(initial = true)
        SettingsCard {
            IconRow(Icons.Outlined.CloudOff, "Run in offline mode",
                when {
                    offline -> "On. Foods come from the built-in list and your own foods, and nothing leaves the phone. Free."
                    else -> "Off. AI looks up foods it hasn't seen and turns speech into text \u00B7 about 0.1\u00A2 an entry"
                },
                onClick = { setOffline(!offline) },
            ) { Switch(checked = offline, onCheckedChange = { setOffline(it) }) }
            if (needKeyNote && !hasKey) {
                // Gentle, in place: no dialog. The key box opens right below.
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Offline until there's an AI key.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { keyOpen = true; needKeyNote = false }) { Text("Add a key") }
                }
            }
            if (!offline) {
                CardDivider()
                IconRow(Icons.Outlined.TravelExplore, "Search the web",
                    if (webSearch) "On. When a restaurant or brand item isn't known, the AI looks it up online, in logging and in Ask (with an OpenAI key). A few cents more for those."
                    else "Off. Unknown restaurant and brand items are the AI's best guess.",
                    onClick = { scope.launch { settings.setWebSearch(!webSearch) } },
                ) {
                    Switch(checked = webSearch, onCheckedChange = {
                        events.event("setting_changed", "setting" to "web_search", "value" to it)
                        scope.launch { settings.setWebSearch(it) }
                    })
                }
            }
            CardDivider()
            IconRow(Icons.Outlined.Key, "Provider and API key",
                (if (backend == AiBackendKind.OPENAI) "OpenAI" else "Anthropic") + if (hasKey) " \u00B7 key saved on this phone" else " \u00B7 no key yet",
                onClick = { keyOpen = !keyOpen }) {
                Icon(if (keyOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.secondary)
            }
            if (keyOpen) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                OutlinedTextField(
                    value = apiKeyText,
                    onValueChange = {
                        apiKeyText = it
                        settings.setApiKey(backend, it.filterNot { c -> c.isWhitespace() })
                    },
                    // singleLine is the fix for a key that wrapped to four lines and took over the screen.
                    singleLine = true,
                    label = { Text("API key") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrect = false,
                    ),
                    placeholder = { Text("Optional \u2014 sk-\u2026") },
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
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
                Hint("Stored encrypted on this phone. Never sent anywhere but the provider.")
                if (backend == AiBackendKind.OPENAI && apiKeyText.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.OutlinedButton(enabled = !checking, onClick = {
                            checking = true
                            scope.launch {
                                val r = com.nutritionscribe.app.network.openai.KeyCheck.check(apiKeyText.trim())
                                events.event("key_check", "where" to "settings", "result" to r.javaClass.simpleName)
                                checked = com.nutritionscribe.app.ui.settings.KeyCheckText.of(r)
                                checking = false
                            }
                        }) { Text(if (checking) "Checking…" else "Check key") }
                        checked?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f)) }
                    }
                }
                if (backend == AiBackendKind.ANTHROPIC) {
                    Hint("With an Anthropic key, AI checks foods but speech stays on the phone: better speech needs an OpenAI key.")
                }
            }
            if (hasKey) {
                CardDivider()
                val agreedTo = AiBackendKind.entries.filter { com.nutritionscribe.app.data.repository.AiConsent.agreed(consent, it) }
                IconRow(Icons.Outlined.Shield, "AI privacy",
                    if (agreedTo.isEmpty()) "Nothing is sent to an AI provider. Tap to see what would be."
                    else "What is sent, and to whom: ${agreedTo.joinToString(" and ") { com.nutritionscribe.app.data.repository.AiConsent.provider(it).name }}",
                    onClick = { turningOn = false; askConsent = backend })
            }
            if (hasKey || everSpent > 0) {
                CardDivider()
                IconRow(Icons.Outlined.Savings, "AI spending", "${com.nutritionscribe.app.ui.cost.formatUsd(monthCost)} this month", onClick = onOpenSpending)
            }
        }

        // ---- Microphone -----------------------------------------------------
        SectionHeader("Microphone")
        SettingsCard {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                com.nutritionscribe.app.ui.speech.MicModeSwitch(
                    micMode,
                    {
                        events.event("setting_changed", "setting" to "mic_mode", "value" to it)
                        scope.launch { settings.setMicMode(it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(
                    if (micMode == com.nutritionscribe.app.data.repository.MicMode.TAP)
                        "Tap the microphone to start, and again when you're done. Pauses never end it."
                    else "Hold the microphone while you talk and let go when you're done. Pauses never end it.",
                )
                if (manualMode) Hint("\u201CEnter the numbers myself\u201D is on (Advanced), so lookups and the microphone are off.")
            }
            val speechOnPhone by settings.speechOnPhone.collectAsState(initial = com.nutritionscribe.app.data.speech.SpeechEngine.PHONE_BY_DEFAULT)
            val openAiKey = remember(backend, hasKey) { !settings.openAiKey().isNullOrBlank() }
            if (openAiKey) {
                CardDivider()
                IconRow(Icons.Outlined.Tune, "Speech to text",
                    if (speechOnPhone) "On the phone: words show as you talk, nothing is sent, and it's free."
                    else "OpenAI: better with brand names and numbers, but it waits until you stop (about 0.1\u00A2 a minute).",
                    onClick = {
                        events.event("setting_changed", "setting" to "speech_on_phone", "value" to !speechOnPhone)
                        scope.launch { settings.setSpeechOnPhone(!speechOnPhone) }
                    },
                ) {
                    Text(if (speechOnPhone) "Phone" else "OpenAI", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // ---- Your data ------------------------------------------------------
        SectionHeader("Your data")
        val context = androidx.compose.ui.platform.LocalContext.current
        val sentAt by settings.logSentAt.collectAsState(initial = null)
        SettingsCard {
            val lastExport by settings.lastExportAt.collectAsState(initial = null)
            IconRow(Icons.Outlined.CloudUpload, "Back up & restore", com.nutritionscribe.app.ui.exportimport.BackupAge.line(lastExport, System.currentTimeMillis()),
                onClick = onOpenExportImport)
            if (!testing) {
                CardDivider()
                IconRow(Icons.Outlined.ChatBubbleOutline, "Something's wrong? Send feedback", "A few taps: what bothered you, and whether you'd keep using it.",
                    onClick = onOpenFeedback)
                CardDivider()
                IconRow(Icons.AutoMirrored.Outlined.Send, "Send my log",
                    "For whoever gave you the app, so they can fix problems." +
                        (sentAt?.let { " Last sent ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it))}." } ?: ""),
                    onClick = { scope.launch { com.nutritionscribe.app.ui.debuglog.LogShare.share(context, "settings") } })
            }
        }

        // ---- Appearance -----------------------------------------------------
        SectionHeader("Appearance")
        SettingsCard {
            // Review F9: beside the buttons, "Theme" got one letter a line at large text. The buttons
            // now sit under the label, with the whole width.
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 14.dp))
                    Text("Theme", style = MaterialTheme.typography.bodyLarge)
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = mode == theme,
                            onClick = { scope.launch { settings.setTheme(mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                            icon = {},
                        ) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, maxLines = 1, fontSize = com.nutritionscribe.app.ui.common.cappedSp(13f)) }
                    }
                }
            }
        }

        SettingsCard {
            IconRow(Icons.Outlined.Tune, "Show advanced settings", "AI models, the label pack, how long data is kept, debug mode and reset.",
                onClick = { scope.launch { settings.setShowAdvanced(!showAdvanced) } },
            ) { Switch(checked = showAdvanced, onCheckedChange = { scope.launch { settings.setShowAdvanced(it) } }) }
        }

        if (!showAdvanced) {
            LeaveBetaRow()
            AboutRow(onOpenAbout)
            VersionLine()
            Spacer(Modifier.padding(bottom = 24.dp))
            return@Column
        }

        // =====================================================================
        // Advanced
        // =====================================================================
        SectionHeader("Advanced")
        SettingsCard {
            IconRow(Icons.Outlined.Memory, "AI models", "Which model does each job, with prices", onClick = onOpenAiModels)
            CardDivider()
            IconRow(Icons.Outlined.Keyboard, "Enter the numbers myself", "Skip lookups and type the amounts by hand. Works with no key and no connection.",
                onClick = { scope.launch { settings.setManualMode(!manualMode) } }) {
                Switch(checked = manualMode, onCheckedChange = {
                    events.event("setting_changed", "setting" to "manual_mode", "value" to it)
                    scope.launch { settings.setManualMode(it) }
                })
            }
        }

        SectionHeader("Label pack")
        SettingsCard {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                LabelPackRow(advanced = true)
                Hint("Built in too: 13,400 USDA foods and a hand-checked list of everyday ones. Works offline, costs nothing.")
            }
        }

        // These were display-only, which made them look like settings and behave
        // like decoration. They are the one place a user can stop the app
        // throwing their own history away, so they have to be reachable.
        SectionHeader("Keep my data for")
        SettingsCard {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                RetentionRow(
                    title = "Entries",
                    subtitle = "Everything you've logged. Older entries are deleted.",
                    window = totalsWindow,
                    what = { n, label -> "$n ${if (n == 1) "entry is" else "entries are"} older than $label. They'll be deleted, and so will each entry that reaches $label from now on." },
                    onChange = { scope.launch { settings.setRetainProteinTotals(it) } },
                )
                RetentionRow(
                    title = "What you said",
                    subtitle = "Your own words and the foods they matched. Older entries keep their name and numbers.",
                    window = detailsWindow,
                    what = { n, label -> "$n ${if (n == 1) "entry is" else "entries are"} older than $label. They'll lose your words and their food matches, and keep their name and numbers." },
                    onChange = { scope.launch { settings.setRetainFoodDetails(it) } },
                )
            }
        }

        SectionHeader("Debugging")
        SettingsCard {
            IconRow(Icons.Outlined.BugReport, "Debug mode",
                "Keeps a full record of what you do and what the app did back, for your notes. It stays on this phone until you send it.",
                onClick = {
                    scope.launch {
                        settings.setDebugMode(!debugMode)
                        events.enabled = !debugMode
                        if (!debugMode) events.event("debug_mode_on", ServiceLocator.appSnapshot())
                    }
                }) {
                Switch(checked = debugMode, onCheckedChange = { on ->
                    scope.launch {
                        settings.setDebugMode(on)
                        events.enabled = on
                        if (on) events.event("debug_mode_on", ServiceLocator.appSnapshot())
                    }
                })
            }
            CardDivider()
            IconRow(Icons.Outlined.Description, "Debug log",
                if (debugMode) "Recording. Save it to Downloads, share it, or clear it." else "Errors only. Turn on debug mode for the full record.",
                onClick = onOpenDebugLog)
        }

        HorizontalDivider(modifier = Modifier.padding(top = 20.dp, bottom = 12.dp))
        ResetSection(reset = viewModel.reset, onOpenExportImport = onOpenExportImport)
        LeaveBetaRow()
        AboutRow(onOpenAbout)
        VersionLine()
        Spacer(Modifier.padding(bottom = 32.dp))
    }
}

/**
 * The very last row of Settings on a test build: "Leave beta testing", or
 * "Rejoin beta testing" once left (see [com.nutritionscribe.app.ui.feedback.BetaMode]).
 */
@Composable
private fun LeaveBetaRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (!remember { com.nutritionscribe.app.ui.feedback.isTestVersion(context) }) return
    val left = com.nutritionscribe.app.ui.feedback.BetaMode.left.value ?: com.nutritionscribe.app.ui.feedback.BetaMode.hasLeft(context)
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    SettingsCard {
        IconRow(Icons.Outlined.Tune,
            if (left) "Rejoin beta testing" else "Leave beta testing",
            if (left) "Bring back the Beta testing section, shake to report and debug mode."
            else "Use the app as a normal copy: no tester screens, shake to report or debug log.",
            onClick = {
                if (left) {
                    com.nutritionscribe.app.ui.feedback.BetaMode.setLeft(context, false)
                    ServiceLocator.debugEvents.always("beta_rejoined")
                    scope.launch { ServiceLocator.settingsRepository.setDebugMode(true) }
                    com.nutritionscribe.app.ui.common.AppMessages.say("Welcome back to beta testing.")
                } else asking = true
            },
        ) {}
    }
    if (asking) androidx.compose.material3.AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text("Leave beta testing?") },
        text = {
            Text("The Beta testing section, shake to report and the week-later \"send your log\" card go away, and debug mode turns off. " +
                "Your food log, goals and saved foods stay as they are. You can rejoin here any time.")
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                asking = false
                ServiceLocator.debugEvents.always("beta_left")
                com.nutritionscribe.app.ui.feedback.BetaMode.setLeft(context, true)
                scope.launch { ServiceLocator.settingsRepository.setDebugMode(false) }
                com.nutritionscribe.app.ui.common.AppMessages.say("You've left beta testing.")
            }) { Text("Leave") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = false }) { Text("Cancel") } },
    )
}

/** A group of settings on one card (note S1). */
@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(content = content) }
}

@Composable
private fun CardDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 52.dp))

/** An icon, a title with a line under it, and a switch or a chevron. */
@Composable
private fun IconRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 14.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** "My foods · 48 saved · 6 meals": the two places their own food lives. */
@Composable
private fun FoodTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** About, at the end of Settings with or without the advanced part (items 13–14). */
@Composable
private fun AboutRow(onOpen: () -> Unit) {
    SettingsCard {
        IconRow(Icons.Outlined.Info, "About", "Help, what leaves your phone, data sources and licences",
            onClick = { ServiceLocator.debugEvents.event("about_tap", "where" to "settings"); onOpen() })
    }
}

@Composable
private fun VersionLine() {
    val v = remember {
        runCatching { ServiceLocator.appContext.packageManager.getPackageInfo(ServiceLocator.appContext.packageName, 0).versionName }.getOrNull()
    }
    Text("Nutrition Scribe ${v.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                else -> if (advanced) "Recommended. Real package labels for about 230,000 products, so branded food and barcode scans are exact instead of estimated. " +
                    "${com.nutritionscribe.shared.resolve.LabelPackFacts.SIZE_TEXT.replaceFirstChar { it.uppercase() }}."
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RetentionRow(
    title: String,
    subtitle: String,
    window: RetentionWindow,
    /** The warning for a shorter window: how many entries it reaches now, and what happens to them. */
    what: (count: Int, label: String) -> String,
    onChange: (RetentionWindow) -> Unit,
) {
    val choices = listOf(
        RetentionWindow.FOREVER to "Forever",
        RetentionWindow(5, RetentionUnit.YEARS) to "5 years",
        RetentionWindow(1, RetentionUnit.YEARS) to "1 year",
        RetentionWindow(3, RetentionUnit.MONTHS) to "3 months",
    )
    // Nothing is dropped without a warning first (review F2), with the count it reaches today.
    var pending by remember { mutableStateOf<Pair<RetentionWindow, String>?>(null) }
    var count by remember { mutableStateOf<Int?>(null) }
    pending?.let { (w, label) ->
        LaunchedEffect(w) {
            count = w.cutoffEpochMillis(System.currentTimeMillis())?.let { ServiceLocator.database.entryDao().countBefore(it) } ?: 0
        }
        AlertDialog(
            onDismissRequest = { pending = null; count = null },
            title = { Text("Keep only $label?") },
            text = { Text(count?.let { what(it, label) + " Back up first if you might want them." } ?: "Counting…") },
            confirmButton = {
                TextButton(enabled = count != null, onClick = { onChange(w); pending = null; count = null }) { Text("Keep $label") }
            },
            dismissButton = { TextButton(onClick = { pending = null; count = null }) { Text("Keep everything") } },
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        // Wrapping, not scrolling off the edge ("Foreve…", review G4).
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { (candidate, label) ->
                FilterChip(
                    selected = candidate.unit == window.unit && candidate.amount == window.amount,
                    onClick = {
                        // Longer than now (or Forever) loses nothing: no need to ask.
                        val now = System.currentTimeMillis()
                        val newCut = candidate.cutoffEpochMillis(now)
                        val oldCut = window.cutoffEpochMillis(now)
                        if (newCut == null || (oldCut != null && newCut <= oldCut)) onChange(candidate) else pending = candidate to label
                    },
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
    var keepScanned by remember { mutableStateOf(true) }
    var keepLearned by remember { mutableStateOf(true) }
    var foodCounts by remember { mutableStateOf<Pair<Int, Int>?>(null) }
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
            foodCounts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { reset.savedFoodCounts() }.getOrNull() }
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
            keepFoodLog = true; keepScanned = true; keepLearned = true; keepSettings = true; keepApiKey = true; keepLabelPack = true
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
        scannedFoods = !keepScanned,
        learnedFoods = !keepLearned,
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
                        label = "Keep my logged entries",
                        detail = when (count) {
                            null -> "counting…"
                            1 -> "1 entry, with its AI spending history"
                            else -> "$count entries, with their AI spending history"
                        },
                        checked = keepFoodLog,
                        onChange = { keepFoodLog = it },
                    )
                    KeepRow(
                        label = "Keep labels I scanned",
                        detail = foodCounts?.first?.let { "$it foods from real labels, so your milk is still your milk" } ?: "counting…",
                        checked = keepScanned,
                        onChange = { keepScanned = it },
                    )
                    KeepRow(
                        label = "Keep other foods it learned",
                        detail = foodCounts?.second?.let { "$it remembered portions, estimates and saved meals" } ?: "counting…",
                        checked = keepLearned,
                        onChange = { keepLearned = it },
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
                            detail = "230,000 package labels, ${com.nutritionscribe.shared.resolve.LabelPackFacts.SIZE_TEXT} \u2014 nothing personal in it",
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
                    null, 0 -> "your logged entries"
                    1 -> "your logged entries (1 entry)"
                    else -> "your logged entries ($entryCount entries)"
                },
            )
        }
        if (scope.scannedFoods) add("the labels you scanned")
        if (scope.learnedFoods) add("the other foods it learned")
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
