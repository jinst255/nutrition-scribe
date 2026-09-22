package com.nutritionscribe.app.ui.review

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.PendingEntryHolder
import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.withNutrient
import kotlinx.coroutines.launch

class ReviewViewModel : ViewModel() {
    private val repository = ServiceLocator.currentEntryRepository()

    fun confirm(name: String, proteinGrams: Double, onDone: () -> Unit) {
        val pending = PendingEntryHolder.entry ?: return
        val finalEntry = pending.copy(name = name).withNutrient(Metric.PROTEIN, proteinGrams)
        viewModelScope.launch {
            repository.confirm(finalEntry)
            PendingEntryHolder.entry = null
            onDone()
        }
    }
}

@Composable
fun ReviewScreen(onConfirmed: () -> Unit, viewModel: ReviewViewModel = viewModel()) {
    val pending = PendingEntryHolder.entry ?: return
    var name by remember { mutableStateOf(pending.name) }
    // A needs-detail entry has NO numbers, and showing "0.0" would invite the user
    // to confirm a whole breakfast as zero grams of protein. Blank is honest.
    var proteinText by remember {
        mutableStateOf(if (pending.needsDetail) "" else pending.proteinGrams.toString())
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            if (pending.needsDetail) "Needs a bit more detail" else "Parsed from your entry",
            style = MaterialTheme.typography.headlineSmall,
        )
        when {
            pending.needsDetail -> Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    "\"${pending.rawText}\" doesn't say enough to work out the nutrition, " +
                        "so nothing has been guessed. Name the food and fill in what you know " +
                        "— or save it as-is and come back to it later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
            pending.confidence == Confidence.STATED ->
                Text("You gave the exact amount, so we used it as-is.", color = MaterialTheme.colorScheme.secondary)
            else ->
                Text("Estimated, tap to adjust", color = MaterialTheme.colorScheme.secondary)
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Food") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        OutlinedTextField(
            value = proteinText,
            onValueChange = { proteinText = it },
            label = { Text("Protein (g)") },
            placeholder = { if (pending.needsDetail) Text("unknown") else Unit },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Button(
            onClick = {
                // Leaving it blank on a needs-detail entry keeps it at zero and
                // keeps the flag, so the entry stays visibly unfinished rather
                // than quietly counting as a real zero.
                val grams = proteinText.toDoubleOrNull() ?: pending.proteinGrams
                viewModel.confirm(name, grams, onConfirmed)
            },
            modifier = Modifier.padding(top = 24.dp),
        ) { Text("Confirm") }
    }
}
