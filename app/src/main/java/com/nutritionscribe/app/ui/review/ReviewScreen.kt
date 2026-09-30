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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.PendingEntryHolder
import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.withNutrient
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ReviewViewModel : ViewModel() {
    private val repository = ServiceLocator.currentEntryRepository()

    /**
     * The metrics the user actually tracks. Review used to offer a protein box
     * and nothing else, so someone tracking calories or sodium could see a wrong
     * number on the home screen and have no way to correct it — the one screen
     * whose whole job is correcting numbers only let you correct one of them.
     */
    val trackedMetrics = ServiceLocator.goalsRepository.enabledGoals
        .map { goals -> goals.map { it.metric } }

    fun confirm(name: String, edited: Map<Metric, Double?>, onDone: () -> Unit) {
        val pending = PendingEntryHolder.entry ?: return
        var finalEntry = pending.copy(name = name)
        for ((metric, amount) in edited) finalEntry = finalEntry.withNutrient(metric, amount)
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
    val tracked by viewModel.trackedMetrics.collectAsState(initial = listOf(Metric.PROTEIN))

    // A needs-detail entry has NO numbers, and showing "0.0" would invite the
    // user to confirm a whole breakfast as zero grams of protein. Blank is honest.
    val fields = remember(tracked) {
        mutableStateMapOf<Metric, String>().apply {
            for (metric in tracked) {
                val value = pending.nutrients[metric.key]
                put(metric, if (pending.needsDetail || value == null) "" else format(value))
            }
        }
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
        for (metric in tracked) {
            OutlinedTextField(
                value = fields[metric].orEmpty(),
                onValueChange = { fields[metric] = it },
                label = { Text("${metric.label} (${metric.unit})") },
                placeholder = { if (pending.needsDetail) Text("unknown") else Unit },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        Button(
            onClick = {
                // A blank box means "I do not know", which is not the same as
                // zero: it leaves the metric absent so the entry stays visibly
                // unfinished rather than quietly counting as a real zero.
                val edited = tracked.associateWith { metric ->
                    fields[metric]?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                        ?: pending.nutrients[metric.key].takeIf { !pending.needsDetail }
                }
                viewModel.confirm(name, edited, onConfirmed)
            },
            modifier = Modifier.padding(top = 24.dp),
        ) { Text("Confirm") }
    }
}

/** "12" rather than "12.0", and "12.4" rather than "12.399999999999999". */
private fun format(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(java.util.Locale.ROOT, "%.1f", value)
