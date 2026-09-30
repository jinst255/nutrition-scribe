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
    val frequentFoods = repository.observeFrequentFoods()

    /** No key means no lookup is possible, whatever the manual-mode preference says. */
    fun hasApiKey(): Boolean = settings.hasAnyApiKey()

    /**
     * Resolve, then either log right away (every part is the user's own) or hand
     * over to the confirm card. There is no failure path any more: an AI that
     * cannot be reached falls back to the built-in list, and a food nothing knows
     * still reaches the card with the user's words on it.
     */
    fun submit(rawText: String, onReadyForReview: () -> Unit, onLogged: () -> Unit = onReadyForReview) {
        if (rawText.isBlank()) return
        uiState = AddEntryUiState.Processing
        viewModelScope.launch {
            try {
                val r = pipeline.resolve(rawText)
                if (r.silent) {
                    val entry = pipeline.logNow(r)
                    com.nutritionscribe.app.ui.home.LastLogged.entry.value = entry
                    uiState = AddEntryUiState.Editing
                    onLogged()
                } else {
                    com.nutritionscribe.app.ui.confirm.LogSession.resolution = r
                    com.nutritionscribe.app.ui.confirm.LogSession.editing = null
                    uiState = AddEntryUiState.Editing
                    onReadyForReview()
                }
            } catch (e: Exception) {
                ServiceLocator.debugLogger.log("AddEntry", "resolve failed: ${e.message}")
                uiState = AddEntryUiState.Failed("Something went wrong reading that. Your words are still here — try again, or enter it by hand.")
            }
        }
    }

    fun saveManual(input: ManualEntryInput, onDone: () -> Unit) {
        val (timestamp, timeUnspecified) = input.resolveTimestamp(LocalDate.now())
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
    viewModel: AddEntryViewModel = viewModel(),
) {
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
            ManualEntryForm(
                title = "Add an entry",
                frequentFoods = frequentFoods,
                defaultTime = LocalTime.now(),
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

    AiAddEntry(
        viewModel = viewModel,
        onReadyForReview = onReadyForReview,
        onLogged = onLogged,
        onEnterManually = { manualOverride = true },
    )
}

@Composable
private fun AiAddEntry(
    viewModel: AddEntryViewModel,
    onReadyForReview: () -> Unit,
    onLogged: () -> Unit,
    onEnterManually: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val state = viewModel.uiState

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            text = spoken
            viewModel.submit(spoken, onReadyForReview, onLogged)
        }
    }

    fun launchSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "What did you eat")
        }
        speechLauncher.launch(intent)
    }

    when (state) {
        is AddEntryUiState.Processing -> ProcessingView()
        is AddEntryUiState.Failed -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            TextButton(onClick = { viewModel.submit(text, onReadyForReview, onLogged) }, modifier = Modifier.padding(top = 12.dp)) {
                Text("Try again")
            }
            // A failed lookup shouldn't strand the entry: offer the manual path right here
            // rather than making the user back out and find it in Settings.
            TextButton(onClick = onEnterManually, modifier = Modifier.padding(top = 12.dp)) {
                Text("Enter it by hand instead")
            }
        }
        is AddEntryUiState.Editing -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("What did you eat", style = MaterialTheme.typography.headlineSmall)
            Column(modifier = Modifier.padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(onClick = { launchSpeech() }, modifier = Modifier.size(96.dp)) {
                    Icon(Icons.Filled.Mic, contentDescription = "Tap to speak", modifier = Modifier.size(48.dp))
                }
                Text("Tap to speak", modifier = Modifier.padding(top = 8.dp))
            }
            Text("or", color = MaterialTheme.colorScheme.secondary)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Type it instead") },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Button(
                onClick = { viewModel.submit(text, onReadyForReview, onLogged) },
                enabled = text.isNotBlank(),
                modifier = Modifier.padding(top = 16.dp),
            ) { Text("Continue") }
            TextButton(onClick = onEnterManually, modifier = Modifier.padding(top = 8.dp)) {
                Text("Enter the numbers myself")
            }
        }
    }
}

@Composable
private fun ProcessingView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text("Looking it up", modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        Text("Your own foods first, then the food list", color = MaterialTheme.colorScheme.secondary)
    }
}
