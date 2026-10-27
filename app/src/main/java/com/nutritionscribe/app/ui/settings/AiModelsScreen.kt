package com.nutritionscribe.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.data.repository.AiBackendKind
import com.nutritionscribe.app.data.repository.AiJob
import com.nutritionscribe.app.data.usage.AiModels
import com.nutritionscribe.app.data.usage.PriceBook
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.theme.NumberStyle
import kotlinx.coroutines.launch

/**
 * Advanced › AI models: a model for each job, with its price in cents. Spending
 * costs every call at its own model's rate and splits by job and model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModelsScreen() {
    val s = ServiceLocator.settingsRepository
    val scope = rememberCoroutineScope()
    val backend by s.backend.collectAsState(initial = AiBackendKind.OPENAI)
    val lookup by s.modelLookup.collectAsState(initial = null)
    val ask by s.modelAsk.collectAsState(initial = null)
    val speech by s.modelSpeech.collectAsState(initial = null)
    var picking by remember { mutableStateOf<AiJob?>(null) }
    val chosen = mapOf(AiJob.LOOKUP to lookup, AiJob.ASK to ask, AiJob.SPEECH to speech)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Provider: ${if (backend == AiBackendKind.OPENAI) "OpenAI" else "Anthropic"}. Change it under Provider and key.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Card(Modifier.fillMaxWidth()) {
            AiJob.entries.forEachIndexed { i, job ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val kind = if (job == AiJob.SPEECH) AiBackendKind.OPENAI else backend
                val id = AiModels.pick(job, kind, chosen[job])
                val opt = AiModels.options(job, kind).first { it.id == id }
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { picking = job }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(job.label, style = MaterialTheme.typography.titleSmall)
                        Text(opt.name + " · " + AiModels.typicalCall(id, job), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        if (backend == AiBackendKind.ANTHROPIC) {
            Text("Speech always uses OpenAI, and only with an OpenAI key.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Text("Food lookups were tested on GPT-6 Luna only; another model may read portions differently. Prices are each provider's list prices as of ${PriceBook.CHECKED}.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
    picking?.let { job ->
        val kind = if (job == AiJob.SPEECH) AiBackendKind.OPENAI else backend
        val current = AiModels.pick(job, kind, chosen[job])
        com.nutritionscribe.app.ui.feedback.ReportableSheet("Pick a model", onDismissRequest = { picking = null }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(job.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                AiModels.options(job, kind).forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected = o.id == current, role = Role.RadioButton) {
                            ServiceLocator.debugEvents.event("ai_model_set", "job" to job.name, "model" to o.id)
                            scope.launch { s.setModel(job, if (o.id == AiModels.default(job, kind)) null else o.id) }
                            picking = null
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o.id == current, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(o.name, style = MaterialTheme.typography.bodyLarge)
                            Text(AiModels.priceLine(o.id, job), style = NumberStyle, color = MaterialTheme.colorScheme.secondary)
                            o.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                        }
                    }
                }
            }
        }
    }
}
