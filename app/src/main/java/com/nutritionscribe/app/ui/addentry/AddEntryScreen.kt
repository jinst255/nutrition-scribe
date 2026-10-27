package com.nutritionscribe.app.ui.addentry

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.QrCodeScanner
import com.nutritionscribe.app.data.db.toEntry
import kotlinx.coroutines.flow.map
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.data.repository.LogEntryResult
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.PendingEntryHolder
import com.nutritionscribe.app.ui.common.ManualEntryForm
import com.nutritionscribe.app.ui.common.ManualEntryInput
import com.nutritionscribe.app.ui.common.resolveTimestamp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

sealed class AddEntryUiState {
    data object Editing : AddEntryUiState()
    data object Processing : AddEntryUiState()
    data class Failed(val message: String) : AddEntryUiState()
}

class AddEntryViewModel : ViewModel() {
    private val repository = ServiceLocator.currentEntryRepository()
    private val settings = ServiceLocator.settingsRepository
    private val pipeline = ServiceLocator.logPipeline

    var uiState by mutableStateOf<AddEntryUiState>(AddEntryUiState.Editing)
        private set

    val manualMode = settings.manualMode
    val micMode = settings.micMode
    val frequentFoods = repository.observeFrequentFoods()

    /** "The same again" in one tap: what is usually eaten around now, then the most frequent. */
    val recent = ServiceLocator.database.entryDao().observeAll().map { rows ->
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        com.nutritionscribe.shared.model.LogAgain.smart(
            com.nutritionscribe.shared.model.LogAgain.choices(rows.map { it.toEntry() }, now, zone), now,
        ).map { it.entry }
    }

    fun setMicMode(mode: com.nutritionscribe.app.data.repository.MicMode) {
        ServiceLocator.debugEvents.event("mic_mode_changed", "mode" to mode, "where" to "log_food")
        viewModelScope.launch { settings.setMicMode(mode) }
    }

    /** No key means no lookup is possible, whatever the manual-mode preference says. */
    fun hasApiKey(): Boolean = settings.hasAnyApiKey()

    /**
     * Resolve, then either log right away (every part is the user's own) or hand
     * over to the confirm card. There is no failure path any more: an AI that
     * cannot be reached falls back to the built-in list, and a food nothing knows
     * still reaches the card with the user's words on it.
     */
    fun submit(
        rawText: String,
        onReadyForReview: () -> Unit,
        onLogged: () -> Unit = onReadyForReview,
        how: String = "typed",
        tag: String? = null,
    ) {
        if (rawText.isBlank()) return
        uiState = AddEntryUiState.Processing
        viewModelScope.launch {
            when (val out = LogFlow.submit(rawText, how, tag)) {
                is LogFlow.Outcome.Logged -> {
                    uiState = AddEntryUiState.Editing
                    pastDayToast(out.entry.name)
                    onLogged()
                }
                LogFlow.Outcome.Review -> { uiState = AddEntryUiState.Editing; onReadyForReview() }
                is LogFlow.Outcome.Failed -> uiState = AddEntryUiState.Failed(out.message)
            }
        }
    }

    fun logAgain(entry: com.nutritionscribe.shared.model.Entry, onDone: () -> Unit) {
        viewModelScope.launch {
            LogFlow.logAgain(entry, where = "log_food")
            pastDayToast(entry.name)
            onDone()
        }
    }

    /** A past day's entry logs quietly onto that day; say so before going back. */
    private fun pastDayToast(name: String) {
        val day = com.nutritionscribe.app.ui.confirm.LogDay.label() ?: return
        android.widget.Toast.makeText(ServiceLocator.appContext, "Added $name to $day", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun saveManual(input: ManualEntryInput, onDone: () -> Unit) {
        val (timestamp, timeUnspecified) = input.resolveTimestamp(com.nutritionscribe.app.ui.confirm.LogDay.date ?: LocalDate.now())
        viewModelScope.launch {
            repository.logManual(
                name = input.name,
                proteinGrams = input.proteinGrams,
                calories = input.calories,
                carbsGrams = input.carbsGrams,
                fatGrams = input.fatGrams,
                timestampEpochMillis = timestamp,
                timeUnspecified = timeUnspecified,
            )
            onDone()
        }
    }
}

@Composable
fun AddEntryScreen(
    onReadyForReview: () -> Unit,
    onManualSaved: () -> Unit,
    onLogged: () -> Unit = onManualSaved,
    onScan: () -> Unit = {},
    /** From History's "+": the past day this is for. Null is today. */
    date: LocalDate? = null,
    /** Opened from "type it": the keyboard comes straight up. */
    startTyping: Boolean = false,
    viewModel: AddEntryViewModel = viewModel(),
) {
    // Set before anything below reads it, and again whenever the screen is opened.
    remember(date) { com.nutritionscribe.app.ui.confirm.LogDay.set(date); 0 }
    val manualPreference by viewModel.manualMode.collectAsState(initial = false)
    val frequentFoods by viewModel.frequentFoods.collectAsState(initial = emptyList())
    // Manual mode is now only ever CHOSEN, never forced. It used to be forced
    // whenever there was no API key, on the reasoning that there was nothing to
    // call — which stopped being true when the offline food database shipped.
    // Leaving it in made that database unreachable for precisely the people it
    // was built for: anyone who was handed the app without a key.
    val manual = manualPreference
    var manualOverride by remember { mutableStateOf(false) }

    if (manual || manualOverride) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp)) {
            if (com.nutritionscribe.app.ui.confirm.LogDay.isPast) {
                PastDayBanner(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            ManualEntryForm(
                title = com.nutritionscribe.app.ui.confirm.LogDay.label()?.let { "Add to $it" } ?: "Add an entry",
                frequentFoods = frequentFoods,
                // Backdating starts with no time rather than a made-up one.
                defaultTime = if (com.nutritionscribe.app.ui.confirm.LogDay.isPast) null else LocalTime.now(),
                onSave = { viewModel.saveManual(it, onManualSaved) },
            )
            if (!manual && manualOverride) {
                TextButton(
                    onClick = { manualOverride = false },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                ) { Text("Use voice or AI lookup instead") }
            }
        }
        return
    }

    VoiceAddEntry(
        viewModel = viewModel,
        onReadyForReview = onReadyForReview,
        onLogged = onLogged,
        onScan = onScan,
        onEnterManually = { manualOverride = true },
        startTyping = startTyping,
    )
}

/** One match for what's typed so far, and what a tap on it does. */
private data class Match(val name: String, val sub: String, val kcal: Double?, val section: String, val phrase: String?, val candidate: com.nutritionscribe.shared.resolve.FoodCandidate?)

/** Their own foods, then packaged foods, then the food list: what the typed words match so far (note LOG-1). */
private suspend fun matchesFor(q: String): List<Match> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val words = com.nutritionscribe.shared.food.FoodQuery.contentWords(q)
    if (words.isEmpty()) return@withContext emptyList()
    val mine = runCatching { ServiceLocator.personalStore.all() }.getOrDefault(emptyList())
        .filter { f -> val w = (com.nutritionscribe.shared.food.FoodQuery.contentWords(f.name) + com.nutritionscribe.shared.food.FoodQuery.contentWords(f.phrase)).toSet()
            words.all { q1 -> w.any { it.startsWith(q1) } } }
        .sortedByDescending { it.updatedAt }.take(3)
        .map { f -> Match(f.name, if (f.isMeal) "Your meal" else "Your usual" + (f.candidate?.labelServingText?.let { " · $it" } ?: ""),
            f.nutrients[com.nutritionscribe.shared.model.Metric.CALORIES.key], "Your foods", f.phrase, null) }
    val packed = runCatching { ServiceLocator.labelPack.source()?.search(q, limit = 3).orEmpty() }.getOrDefault(emptyList())
        .map { c -> Match(com.nutritionscribe.app.ui.confirm.ItemDraft.withBrand(c, " "), listOfNotNull(c.brand, c.labelServingText).joinToString(" · "),
            c.perServing?.get(com.nutritionscribe.shared.model.Metric.CALORIES.key)
                ?: c.labelServingGrams?.let { g -> c.nutrientsFor(g)[com.nutritionscribe.shared.model.Metric.CALORIES.key] }, "Packaged foods", null, c) }
    val usda = runCatching { ServiceLocator.foodDatabase.reader()?.let { com.nutritionscribe.shared.resolve.UsdaFoodSource(it) }?.search(q, limit = 3).orEmpty() }
        .getOrDefault(emptyList())
        .map { c -> Match(c.name, "per 100 g", c.per100g?.get(com.nutritionscribe.shared.model.Metric.CALORIES.key), "Food list", null, c) }
    mine + packed + usda
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VoiceAddEntry(
    viewModel: AddEntryViewModel,
    onReadyForReview: () -> Unit,
    onLogged: () -> Unit,
    onScan: () -> Unit,
    onEnterManually: () -> Unit,
    startTyping: Boolean = false,
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(startTyping) {
        if (startTyping) runCatching { kotlinx.coroutines.delay(150); focus.requestFocus() }
    }
    var hint by remember { mutableStateOf<String?>(null) }
    val state = viewModel.uiState
    val micMode by viewModel.micMode.collectAsState(initial = com.nutritionscribe.app.data.repository.MicMode.TAP)
    val recent by viewModel.recent.collectAsState(initial = emptyList())
    val voice = com.nutritionscribe.app.ui.speech.rememberVoiceController { spoken, tag ->
        text = spoken
        viewModel.submit(spoken, onReadyForReview, onLogged, how = "voice", tag = tag)
    }
    var matches by remember { mutableStateOf<List<Match>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(text) {
        if (text.trim().length < 2) { matches = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        matches = runCatching { matchesFor(text.trim()) }.getOrDefault(emptyList())
    }
    var time by remember { mutableStateOf(com.nutritionscribe.app.ui.confirm.LogDay.time) }
    var pickingTime by remember { mutableStateOf(false) }
    val aiOn = viewModel.hasApiKey()
    fun go(how: String = "typed") { if (text.isNotBlank()) viewModel.submit(text.trim(), onReadyForReview, onLogged, how = how) }

    when (state) {
        is AddEntryUiState.Processing -> ProcessingView(text)
        is AddEntryUiState.Failed -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            TextButton(onClick = { viewModel.submit(text, onReadyForReview, onLogged, how = "retry") }, modifier = Modifier.padding(top = 12.dp)) {
                Text("Try again")
            }
            // A failed lookup shouldn't strand the entry: offer the manual path right here.
            TextButton(onClick = onEnterManually, modifier = Modifier.padding(top = 12.dp)) {
                Text("Enter it by hand instead")
            }
        }
        is AddEntryUiState.Editing -> Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val past = com.nutritionscribe.app.ui.confirm.LogDay.isPast
            if (past) PastDayBanner()
            else androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // When it was eaten: today, now, unless a time is picked (note LOG-3: a time, not a meal).
                androidx.compose.material3.FilterChip(selected = true, onClick = {}, label = { Text("Today") },
                    leadingIcon = { Icon(Icons.Outlined.CalendarToday, null, Modifier.size(16.dp)) })
                androidx.compose.material3.FilterChip(selected = true, onClick = { pickingTime = true },
                    label = { Text((time?.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")) ?: "Now") + " ▾") },
                    leadingIcon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp)) })
                if (time != null) TextButton(onClick = { time = null; com.nutritionscribe.app.ui.confirm.LogDay.setTodayTime(null) }) { Text("Now") }
            }
            if (!voice.isActive) {
                OutlinedTextField(
                    value = text,
                    // Enter sends it: nobody types a line break into a food.
                    onValueChange = { v ->
                        if ('\n' in v) { text = v.replace('\n', ' ').trim(); go() } else text = v
                    },
                    placeholder = { Text("What did you eat?", style = MaterialTheme.typography.titleMedium) },
                    textStyle = MaterialTheme.typography.titleMedium,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).focusRequester(focus),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { go() }),
                    trailingIcon = {
                        if (text.isNotBlank()) TextButton(onClick = { go() }) { Text("Go") }
                        else com.nutritionscribe.app.ui.speech.MicButton(voice, micMode, size = 48.dp, onHint = { hint = it })
                    },
                )
            } else {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    com.nutritionscribe.app.ui.speech.MicButton(voice, micMode, size = 88.dp, onHint = { hint = it })
                }
            }
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
            if (voice.isActive || text.isBlank()) com.nutritionscribe.app.ui.speech.ListeningPanel(voice, micMode)

            if (text.isBlank() && !voice.isActive) {
                Text("Like “2 eggs and a slice of toast”. Your own foods first, then the food list.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 4.dp))
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LogTile(Icons.Filled.QrCodeScanner, "Scan", "A barcode or a label", Modifier.weight(1f), onScan)
                    LogTile(Icons.Outlined.Keyboard, "Enter it myself", "Type the numbers", Modifier.weight(1f), onEnterManually)
                }
                if (recent.isNotEmpty()) {
                    com.nutritionscribe.app.ui.home.SectionLabel("Log again")
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                            recent.take(6).forEachIndexed { i, e ->
                                if (i > 0) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(e.name.substringBefore(",").take(40), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                        Text(listOfNotNull(e.items.singleOrNull()?.portion, e.nutrients[com.nutritionscribe.shared.model.Metric.CALORIES.key]?.let { "${Math.round(it)} kcal" })
                                            .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                    }
                                    androidx.compose.material3.FilledTonalIconButton(onClick = { viewModel.logAgain(e, onLogged) }, modifier = Modifier.size(44.dp)) {
                                        Icon(Icons.Filled.Add, contentDescription = "Log ${e.name} again")
                                    }
                                }
                            }
                        }
                    }
                    Text(if (past) "Same food, same amount, added to that day." else "Same food, same amount, logged now.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                com.nutritionscribe.app.ui.speech.MicModeSwitch(micMode, { viewModel.setMicMode(it); hint = null })
            } else if (!voice.isActive) {
                // The sentence path first: it works out every food and amount, like saying it.
                androidx.compose.material3.Surface(
                    onClick = { go() }, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text("Log “${text.trim().take(28)}${if (text.trim().length > 28) "…" else ""}” as typed", style = MaterialTheme.typography.titleSmall)
                            Text("Works out each food and amount, like saying it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                if (!aiOn) Text("AI lookup is off, so new foods come from the food list. Add a key in Settings to turn it on.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                matches.groupBy { it.section }.forEach { (section, list) ->
                    com.nutritionscribe.app.ui.home.SectionLabel(section)
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp)) {
                            list.forEachIndexed { i, m ->
                                if (i > 0) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                androidx.compose.foundation.layout.Row(
                                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                                        ServiceLocator.debugEvents.event("log_food_match", "section" to section, "name" to m.name, "typed" to text)
                                        when {
                                            m.phrase != null -> { text = m.phrase; go("match_mine") }
                                            m.candidate != null -> { com.nutritionscribe.app.ui.confirm.LogSession.startFromScan(m.candidate); onReadyForReview() }
                                        }
                                    }.padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(m.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        if (m.sub.isNotBlank()) Text(m.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                                    }
                                    m.kcal?.let { Text("${Math.round(it)} kcal", style = com.nutritionscribe.app.ui.theme.NumberStyle) }
                                }
                            }
                        }
                    }
                }
                if (matches.isNotEmpty()) Text("Tap one to pick how much, then log it.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
    if (pickingTime) {
        val now = java.time.LocalTime.now()
        val st = androidx.compose.material3.rememberTimePickerState(initialHour = time?.hour ?: now.hour, initialMinute = time?.minute ?: now.minute)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    val t = java.time.LocalTime.of(st.hour, st.minute)
                    time = t; com.nutritionscribe.app.ui.confirm.LogDay.setTodayTime(t); pickingTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } },
            text = { androidx.compose.material3.TimePicker(state = st) },
        )
    }
}

@Composable
private fun LogTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) }
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun ProcessingView(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (text.isNotBlank()) {
            Text("“${text.trim()}”", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 20.dp))
        }
        CircularProgressIndicator()
        Text("Looking it up", modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        Text("Your own foods first, then the food list", color = MaterialTheme.colorScheme.secondary)
    }
}
