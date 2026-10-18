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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
            // A failed lookup shouldn't strand the entry: offer the manual path right here
            // rather than making the user back out and find it in Settings.
            TextButton(onClick = onEnterManually, modifier = Modifier.padding(top = 12.dp)) {
                Text("Enter it by hand instead")
            }
        }
        is AddEntryUiState.Editing -> Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (com.nutritionscribe.app.ui.confirm.LogDay.isPast) PastDayBanner()
            // The button above the words, so the words growing never move it.
            com.nutritionscribe.app.ui.speech.MicButton(voice, micMode, onHint = { hint = it })
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center)
            }
            com.nutritionscribe.app.ui.speech.ListeningPanel(voice, micMode)
            com.nutritionscribe.app.ui.speech.MicModeSwitch(micMode, { viewModel.setMicMode(it); hint = null })

            if (!voice.isActive) {
                OutlinedTextField(
                    value = text,
                    // Enter sends it: nobody types a line break into a food.
                    onValueChange = { v ->
                        if ('\n' in v) {
                            text = v.replace('\n', ' ').trim()
                            if (text.isNotBlank()) viewModel.submit(text, onReadyForReview, onLogged, how = "typed")
                        } else text = v
                    },
                    label = { Text("Or type it") },
                    placeholder = { Text("2 eggs and a slice of toast") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = {
                        if (text.isNotBlank()) viewModel.submit(text, onReadyForReview, onLogged, how = "typed")
                    }),
                    trailingIcon = {
                        TextButton(onClick = { viewModel.submit(text, onReadyForReview, onLogged, how = "typed") }, enabled = text.isNotBlank()) {
                            Text("Go")
                        }
                    },
                )

                androidx.compose.material3.OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Scan a barcode or label")
                }

                if (recent.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Log again", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            recent.forEach { e ->
                                androidx.compose.material3.AssistChip(
                                    onClick = { viewModel.logAgain(e, onLogged) },
                                    label = { Text(e.name.substringBefore(",").take(28), maxLines = 1) },
                                )
                            }
                        }
                        Text(if (com.nutritionscribe.app.ui.confirm.LogDay.isPast) "Same food, same amount, added to that day." else "Same food, same amount, logged now.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                }

                TextButton(onClick = onEnterManually) { Text("Enter the numbers myself") }
            }
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
