package com.nutritionscribe.app.ui.confirm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.Resolver
import com.nutritionscribe.shared.resolve.sumNutrients
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What the confirm screen is showing: a fresh lookup, or an entry being fixed.
 * Held outside navigation for the same reason PendingEntryHolder is — it is a
 * rich object, and only one is ever in flight.
 */
object LogSession {
    var resolution: Resolver.Resolution? = null
    var editing: Entry? = null
    var timestamp: Long? = null
    /** A scan finished: which line it was for, and what it found. */
    var scanned: Pair<Int, FoodCandidate>? = null
}

class ConfirmViewModel : ViewModel() {
    private val pipeline = ServiceLocator.logPipeline

    val tracked = ServiceLocator.goalsRepository.enabledGoals.map { goals -> goals.map { it.metric } }

    val editing: Entry? = LogSession.editing
    val text: String = LogSession.resolution?.text ?: editing?.rawText.orEmpty()
    val aiProblem: String? = LogSession.resolution?.aiProblem
    val drafts = mutableStateListOf<ItemDraft>().apply {
        val r = LogSession.resolution
        when {
            r != null -> addAll(r.items.map { ItemDraft.from(it) })
            editing != null -> addAll(pipeline.toConfirmed(editing).map { ItemDraft.from(it) })
        }
    }
    var busy by mutableStateOf(false)
        private set
    var saveAsMine by mutableStateOf(false)
    var mineName by mutableStateOf("")

    fun update(i: Int, d: ItemDraft) { if (i in drafts.indices) drafts[i] = d }
    fun remove(i: Int) { if (i in drafts.indices) drafts.removeAt(i) }

    /** "What was in it?" — the parts replace the dish, and the dish can be saved as theirs. */
    fun breakDown(i: Int, parts: String) {
        if (parts.isBlank() || i !in drafts.indices) return
        val dish = drafts[i]
        busy = true
        viewModelScope.launch {
            val found = pipeline.resolveItem(parts).map { ItemDraft.from(it) }
            if (found.isNotEmpty()) {
                drafts.removeAt(i)
                drafts.addAll(i, found)
                saveAsMine = true
                if (mineName.isBlank()) mineName = dish.item.food.replaceFirstChar { it.uppercase() }
            }
            busy = false
        }
    }

    fun add(text: String) {
        if (text.isBlank()) return
        busy = true
        viewModelScope.launch {
            drafts.addAll(pipeline.resolveItem(text).map { ItemDraft.from(it) })
            busy = false
        }
    }

    /** Re-search one line by a different name, for the editor's "not this" list. */
    suspend fun search(text: String): List<FoodCandidate> =
        pipeline.resolveItem(text).flatMap { listOfNotNull(it.choice) + it.alternates }.distinctBy { it.id }

    fun applyScan() {
        val (i, c) = LogSession.scanned ?: return
        LogSession.scanned = null
        if (i in drafts.indices) drafts[i] = drafts[i].withCandidate(c)
    }

    fun log(onDone: () -> Unit) {
        if (drafts.isEmpty()) return
        busy = true
        viewModelScope.launch {
            pipeline.confirm(
                text = text,
                items = drafts.map { it.toConfirmed() },
                timestamp = LogSession.timestamp ?: System.currentTimeMillis(),
                replacing = editing,
                saveAsMine = mineName.takeIf { saveAsMine && it.isNotBlank() },
            )
            LogSession.resolution = null
            LogSession.editing = null
            LogSession.timestamp = null
            busy = false
            onDone()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfirmScreen(
    onLogged: () -> Unit,
    onScan: (index: Int) -> Unit,
    viewModel: ConfirmViewModel = viewModel(),
) {
    val tracked by viewModel.tracked.collectAsState(initial = listOf(Metric.CALORIES, Metric.PROTEIN))
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var breakdownIndex by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    // A scan that came back while we were away.
    remember(LogSession.scanned) { viewModel.applyScan(); 0 }

    val drafts = viewModel.drafts
    val fresh = drafts.count { it.status != Resolver.Status.REMEMBERED }
    val total = sumNutrients(drafts.map { it.nutrients })

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (viewModel.text.isNotBlank()) {
                    Text("“${viewModel.text.trim()}”", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary)
                }
                Text(
                    when {
                        viewModel.editing != null -> "Fix it once and it stays fixed: the next time you log this, it uses what you set here."
                        fresh == 0 -> "All of these are your usual."
                        else -> "First time for ${if (fresh == 1) "this" else "these"} — check once. After this ${if (fresh == 1) "it logs" else "they log"} without asking."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                viewModel.aiProblem?.let {
                    Text("The AI helper couldn't be reached ($it), so these came from the built-in food list.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        itemsIndexed(drafts, key = { i, d -> "$i-${d.key}" }) { i, d ->
            DraftCard(
                draft = d,
                tracked = tracked,
                onChange = { editingIndex = i },
                onRemove = { viewModel.remove(i) },
                onScan = { onScan(i) },
                onBreakdown = { breakdownIndex = i },
            )
        }
        item {
            TextButton(onClick = { adding = true }) { Text("+ Something else") }
        }
        item {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total", fontWeight = FontWeight.SemiBold)
                Text(numbersLine(total, tracked), fontWeight = FontWeight.SemiBold)
            }
            if (drafts.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = viewModel.saveAsMine, onCheckedChange = { viewModel.saveAsMine = it })
                    Text("Save as one of my foods", modifier = Modifier.clickable { viewModel.saveAsMine = !viewModel.saveAsMine })
                }
                if (viewModel.saveAsMine) {
                    OutlinedTextField(
                        value = viewModel.mineName,
                        onValueChange = { viewModel.mineName = it },
                        label = { Text("Call it") },
                        placeholder = { Text("My usual lunch") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Say this name next time and it logs in one go.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
            Button(
                onClick = { viewModel.log(onLogged) },
                enabled = !viewModel.busy && drafts.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) {
                if (viewModel.busy) CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                else Text(if (viewModel.editing != null) "Save fix" else "Log it")
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }

    editingIndex?.let { i ->
        drafts.getOrNull(i)?.let { d ->
            ItemEditor(
                draft = d,
                tracked = tracked,
                search = viewModel::search,
                onDone = { viewModel.update(i, it); editingIndex = null },
                onDismiss = { editingIndex = null },
            )
        }
    }
    breakdownIndex?.let { i ->
        TextPrompt(
            title = "What was in it?",
            hint = "e.g. 2 slices wheat bread, 2 tbsp peanut butter, 1 tbsp jam",
            onDone = { viewModel.breakDown(i, it); breakdownIndex = null },
            onDismiss = { breakdownIndex = null },
        )
    }
    if (adding) {
        TextPrompt(
            title = "Add something",
            hint = "e.g. a glass of milk",
            onDone = { viewModel.add(it); adding = false },
            onDismiss = { adding = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftCard(
    draft: ItemDraft,
    tracked: List<Metric>,
    onChange: () -> Unit,
    onRemove: () -> Unit,
    onScan: () -> Unit,
    onBreakdown: () -> Unit,
) {
    val unknown = draft.candidate == null && draft.nutrients.isEmpty()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (draft.status == Resolver.Status.REMEMBERED) MaterialTheme.colorScheme.surfaceContainerLow
            else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onChange),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(if (unknown) "“${draft.item.said}”" else draft.name,
                        style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            if (draft.status == Resolver.Status.REMEMBERED) "Your usual" else ItemDraft.label(draft.candidate?.origin),
                            draft.portionNote?.takeIf { it != "your usual" },
                            draft.grams?.let { "${it.toInt()} g" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
            }
            if (unknown) {
                Text("Couldn't tell what this was. Tap to pick it, or break it down.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                Text(numbersLine(draft.nutrients, tracked), style = MaterialTheme.typography.bodyMedium)
                if (draft.weak && draft.status != Resolver.Status.REMEMBERED) {
                    Text("Best guess — worth a look", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onChange, label = { Text(if (unknown) "Pick it" else "Change") })
                if (draft.offerScan) AssistChip(onClick = onScan, label = { Text("Scan the package") })
                if (draft.offerBreakdown) AssistChip(onClick = onBreakdown, label = { Text("Break it down") })
            }
        }
    }
}

/** A portion adjuster and a short list of other matches — the whole "fix" surface. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemEditor(
    draft: ItemDraft,
    tracked: List<Metric>,
    search: suspend (String) -> List<FoodCandidate>,
    onDone: (ItemDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var d by remember { mutableStateOf(draft) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(draft.alternates) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onDone(d) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(d.name, style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("How much", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { d = d.withAmount(step(d.amount, -1)) }) { Text("−") }
                        // Counted in the food's own pieces where the label says what one is.
                        val piece = d.candidate?.portions?.firstOrNull { it.label != "1 serving" && !it.label.startsWith("1 cup") &&
                            !it.label.startsWith("1 tbsp") && !it.label.startsWith("1 tsp") }?.label?.removePrefix("1 ")
                        val unitWord = d.item.unit ?: piece ?: "serving"
                        Text(
                            "${fmt(d.amount)} ${if (d.amount == 1.0 || unitWord.endsWith("s")) unitWord else unitWord + "s"}",
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        OutlinedButton(onClick = { d = d.withAmount(step(d.amount, +1)) }) { Text("+") }
                    }
                    val portions = d.candidate?.portions.orEmpty().take(6)
                    if (portions.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            portions.forEach { p ->
                                AssistChip(onClick = { d = d.withGrams(p.grams) }, label = { Text("${p.label} (${p.grams.toInt()} g)") })
                            }
                        }
                    }
                    var gramsText by remember(d.grams) { mutableStateOf(d.grams?.let { it.toInt().toString() }.orEmpty()) }
                    OutlinedTextField(
                        value = gramsText,
                        onValueChange = { gramsText = it; it.toDoubleOrNull()?.let { g -> if (g > 0) d = d.withGrams(g) } },
                        label = { Text("Grams") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text("Numbers", style = MaterialTheme.typography.labelLarge)
                    tracked.forEach { m ->
                        var t by remember(d.candidate?.id, d.grams) { mutableStateOf(d.nutrients[m.key]?.let { fmt(it) }.orEmpty()) }
                        OutlinedTextField(
                            value = t,
                            onValueChange = { t = it; d = d.withNutrient(m.key, it.toDoubleOrNull()) },
                            label = { Text("${m.label} (${m.unit})") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Text("Not this? Pick another", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search foods") },
                        singleLine = true,
                        trailingIcon = {
                            TextButton(onClick = { scope.launch { results = search(query) } }) { Text("Go") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                itemsIndexed(results.take(8)) { _, c ->
                    Column(
                        Modifier.fillMaxWidth().clickable { d = d.withCandidate(c) }.padding(vertical = 6.dp),
                    ) {
                        Text(listOfNotNull(c.brand, c.name).distinct().joinToString(" — "), style = MaterialTheme.typography.bodyMedium)
                        Text(ItemDraft.label(c.origin) + (c.labelServingText?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        },
    )
}

@Composable
private fun TextPrompt(title: String, hint: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text(hint) },
                modifier = Modifier.fillMaxWidth())
        },
    )
}

internal fun numbersLine(n: Map<String, Double>, tracked: List<Metric>): String {
    val show = (listOf(Metric.CALORIES, Metric.PROTEIN) + tracked).distinct()
    return show.mapNotNull { m -> n[m.key]?.let { "${fmt(it)} ${if (m == Metric.CALORIES) "kcal" else "${m.unit} ${m.label.lowercase().take(7)}"}" } }
        .joinToString(" · ").ifBlank { "no numbers yet" }
}

private fun step(v: Double, dir: Int): Double {
    val s = if (v < 1.0 || (v < 2 && dir < 0 && v <= 1.0)) 0.25 else if (v < 4) 0.5 else 1.0
    return (v + dir * s).coerceAtLeast(0.25)
}

internal fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else if (v >= 10) String.format(java.util.Locale.ROOT, "%.0f", v)
    else String.format(java.util.Locale.ROOT, "%.1f", v)
