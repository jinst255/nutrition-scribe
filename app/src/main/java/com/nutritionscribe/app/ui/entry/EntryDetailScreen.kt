package com.nutritionscribe.app.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.common.timeLabel
import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.Metric
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class EntryDetailViewModel : ViewModel() {
    private val repository = ServiceLocator.currentEntryRepository()

    fun entry(id: String) = repository.observeEntries().map { all -> all.firstOrNull { it.id == id } }

    suspend fun save(entry: Entry, before: Entry? = null) {
        val keys = (before?.nutrients.orEmpty().keys + entry.nutrients.keys).distinct()
        ServiceLocator.debugEvents.event("entry_edited", "entry_id" to entry.id, "name" to entry.name, "nutrients" to entry.nutrients,
            "name_before" to before?.name,
            "changed" to before?.let { b -> keys.filter { b.nutrients[it] != entry.nutrients[it] }
                .associateWith { k -> mapOf("before" to b.nutrients[k], "after" to entry.nutrients[k]) } },
            "items" to entry.items.map { mapOf("name" to it.name, "grams" to it.grams, "candidate" to it.candidateId, "origin" to it.origin) })
        repository.update(entry)
    }
    suspend fun delete(entry: Entry) {
        ServiceLocator.debugEvents.event("entry_deleted", "where" to "entry_detail", "entry_id" to entry.id, "name" to entry.name)
        repository.delete(entry)
    }
}

/**
 * One entry, in full.
 *
 * The log used to be write-only: an entry could be created and deleted, and
 * nothing in between. Everything the app worked out about a meal — which foods
 * it matched, what it was unsure about, the other five nutrients it recorded but
 * no one had asked to track — existed in the database and nowhere on screen.
 *
 * What the person actually SAID leads, because that is the one part of the
 * record the app did not invent, and it is what makes a wrong number
 * recognisable as wrong.
 */
@Composable
fun EntryDetailScreen(
    entryId: String,
    onFix: () -> Unit = {},
    viewModel: EntryDetailViewModel = viewModel(),
) {
    val entry by remember(entryId) { viewModel.entry(entryId) }.collectAsState(initial = null)
    val current = entry

    if (current == null) {
        // Either still loading, or deleted from under us by the delete button.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Entry not found", color = MaterialTheme.colorScheme.secondary)
        }
        return
    }

    var editing by remember { mutableStateOf(false) }
    // Back while editing means "stop editing", not "leave and lose my changes
    // without being asked".
    androidx.activity.compose.BackHandler(enabled = editing) { editing = false }
    if (editing) {
        EntryEditor(
            entry = current,
            onCancel = { editing = false },
            onSaved = { editing = false },
            viewModel = viewModel,
        )
    } else {
        val usage by remember(entryId) { ServiceLocator.database.aiUsageDao().observeForEntry(entryId) }
            .collectAsState(initial = emptyList())
        val scope = rememberCoroutineScope()
        val context = androidx.compose.ui.platform.LocalContext.current
        EntryReader(
            entry = current,
            onEdit = { editing = true },
            onFix = onFix,
            onLogAgain = {
                ServiceLocator.debugEvents.event("log_again_tap", "where" to "entry_detail", "entry_id" to current.id)
                scope.launch {
                    ServiceLocator.logPipeline.logAgain(current)
                    android.widget.Toast.makeText(context, "Logged again", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            aiCost = usage.sumOf { it.costUsd },
        )
    }
}

@Composable
private fun EntryReader(entry: Entry, onEdit: () -> Unit, onFix: () -> Unit, onLogAgain: () -> Unit = {}, aiCost: Double? = null) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            // timeLabel() already says "Time not set" for a backdated entry, so
            // the date is the only part that always applies.
            "${dateLabel(entry.timestampEpochMillis)} \u00B7 ${entry.timeLabel()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )

        // Their words, first and unedited.
        if (entry.rawText.isNotBlank() && entry.rawText != entry.name) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "WHAT YOU SAID",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        entry.rawText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Text(entry.name, style = MaterialTheme.typography.headlineSmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry.needsDetail) {
                Badge("Needs detail", MaterialTheme.colorScheme.error)
            } else {
                Badge(
                    when (entry.confidence) {
                        Confidence.STATED -> "You gave the amount"
                        Confidence.ESTIMATED -> "Estimated"
                    },
                    MaterialTheme.colorScheme.secondary,
                )
            }
            Badge(sourceLabel(entry), MaterialTheme.colorScheme.secondary)
        }

        if (entry.needsConfirm) {
            Text(
                "Logged from your watch on a best guess. Check it once and it will be remembered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // What it was made of, and where each number came from.
        if (entry.items.isNotEmpty()) {
            HorizontalDivider()
            Text("What's in it", style = MaterialTheme.typography.titleMedium)
            entry.items.forEach { item ->
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(item.nutrients[Metric.CALORIES.key]?.let { "${format(it)} kcal" } ?: "", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        listOfNotNull(
                            com.nutritionscribe.app.ui.confirm.ItemDraft.label(
                                runCatching { com.nutritionscribe.shared.resolve.Origin.valueOf(item.origin) }.getOrNull(),
                            ),
                            item.portion,
                        ).joinToString(" \u00B7 "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }

        HorizontalDivider()

        // Every nutrient, including the ones the user does not track: they were
        // recorded anyway, and this is the only place they can be seen.
        Text("Nutrition", style = MaterialTheme.typography.titleMedium)
        val knownMicros = Metric.MICROS.filter { it.key in entry.nutrients }
        (Metric.MACROS + knownMicros).forEachIndexed { i, metric ->
            if (i == Metric.MACROS.size) {
                Text("Minerals & vitamins", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            val amount = entry.nutrients[metric.key]
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    metric.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (amount == null) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    if (amount == null) "not known" else "${format(amount)} ${metric.unit}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (amount == null) FontWeight.Normal else FontWeight.Medium,
                    color = if (amount == null) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }

        val unknownMicros = Metric.MICROS.filter { it.key !in entry.nutrients }
        if (unknownMicros.isNotEmpty()) {
            Text(
                (if (knownMicros.isEmpty()) "Minerals & vitamins: not known for this food."
                else "Not known: ${unknownMicros.joinToString(", ") { it.shortLabel }}."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        // Back lives in the title bar now; a second Back button here was the
        // same control twice.
        // Fix goes through the same card as logging, so a correction is also
        // remembered: this food will never be wrong the same way again.
        Button(onClick = onFix, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(if (entry.needsConfirm) "Check it" else "Fix it")
        }
        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
            Text("Edit the numbers only")
        }
        OutlinedButton(onClick = onLogAgain, modifier = Modifier.fillMaxWidth()) {
            Text("Log again now")
        }
        ReportProblem(entry)
        if (aiCost != null && aiCost > 0) {
            Text(
                "AI cost for this entry: ${com.nutritionscribe.app.ui.cost.formatUsd(aiCost)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun EntryEditor(
    entry: Entry,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
    viewModel: EntryDetailViewModel,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(entry.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    // The macros always; a micronutrient only when this entry has one to edit.
    val editable = remember { Metric.MACROS + Metric.MICROS.filter { it.key in entry.nutrients } }
    val amounts = remember {
        mutableStateMapOf<Metric, String>().apply {
            editable.forEach { put(it, entry.nutrients[it.key]?.let(::format).orEmpty()) }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Edit entry", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Food") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Deliberately not editable. It is the record of what was said; changing
        // it would make the entry claim the person said something they did not,
        // and it is the only thing here the app did not derive.
        if (entry.rawText.isNotBlank() && entry.rawText != entry.name) {
            Text(
                "Logged from: “${entry.rawText}”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        Text("Nutrition", style = MaterialTheme.typography.titleMedium)
        editable.forEach { metric ->
            OutlinedTextField(
                value = amounts[metric].orEmpty(),
                onValueChange = { amounts[metric] = it },
                label = { Text(metric.label) },
                suffix = { Text(metric.unit) },
                placeholder = { Text("not known") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    // A blank box means "not known", which is not zero: it drops
                    // the nutrient rather than recording a number nobody meant.
                    val edited = editable.mapNotNull { metric ->
                        amounts[metric]?.trim()?.takeIf { it.isNotEmpty() }
                            ?.toDoubleOrNull()?.let { metric.key to it }
                    }.toMap()
                    scope.launch {
                        viewModel.save(
                            entry.copy(
                                name = name.ifBlank { entry.name },
                                nutrients = edited,
                                // Corrected by hand, so it is no longer unfinished.
                                needsDetail = false,
                            ),
                            before = entry,
                        )
                        onSaved()
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }

        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 4.dp)) {
            Text("Delete entry", color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("“${entry.name}” will be removed from your log. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            viewModel.delete(entry)
                            confirmDelete = false
                            onSaved()
                        }
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(text, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(disabledLabelColor = color),
    )
}

/** Where the numbers came from, in words rather than in storage terms. */
private fun sourceLabel(entry: Entry): String = when {
    entry.items.isNotEmpty() -> entry.items.map {
        com.nutritionscribe.app.ui.confirm.ItemDraft.label(runCatching { com.nutritionscribe.shared.resolve.Origin.valueOf(it.origin) }.getOrNull())
    }.distinct().joinToString(" + ")
    entry.foodDbId != null -> "Offline food list"
    entry.isFixedFood -> "Saved food"
    entry.source.isBlank() -> "Typed in"
    entry.source == "usda" -> "Offline food list"
    entry.source.startsWith("manual") -> "Typed in"
    else -> "AI lookup"
}

private val DATE_FORMAT: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")

private fun dateLabel(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(DATE_FORMAT)

private fun format(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(java.util.Locale.ROOT, "%.1f", value)

/**
 * "Something's wrong with this one": a note in the debug log with the entry as
 * stored, so the problem can be found by what it is about rather than by when.
 */
@Composable
private fun ReportProblem(entry: Entry) {
    var open by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Something's wrong with this entry", color = MaterialTheme.colorScheme.secondary)
    }
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("What's wrong?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This goes in the debug log with everything the app knows about this entry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    OutlinedTextField(
                        value = note, onValueChange = { note = it },
                        placeholder = { Text("e.g. sugar should be 4 g, not 49") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    // A report is worth recording even with debug mode off.
                    ServiceLocator.debugEvents.always(
                        "user_report", "note" to note, "entry_id" to entry.id,
                        "entry" to kotlinx.serialization.json.Json.encodeToJsonElement(Entry.serializer(), entry),
                        "suspect" to com.nutritionscribe.shared.model.NutrientSanity.codes(entry.nutrients),
                    )
                    open = false; note = ""
                    android.widget.Toast.makeText(context, "Noted in the debug log", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Save note") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}
