package com.nutritionscribe.app.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.common.timeLabel
import com.nutritionscribe.app.ui.confirm.FoodEditor
import com.nutritionscribe.app.ui.confirm.ItemDraft
import com.nutritionscribe.app.ui.confirm.LogSession
import com.nutritionscribe.app.ui.confirm.applyChangeTo
import com.nutritionscribe.app.ui.confirm.parseAmount
import com.nutritionscribe.app.ui.home.SectionLabel
import com.nutritionscribe.app.ui.theme.NumberStyle
import com.nutritionscribe.app.ui.theme.limitColors
import com.nutritionscribe.shared.model.Confidence
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.LogDays
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.Kinds
import com.nutritionscribe.shared.resolve.Origin
import com.nutritionscribe.shared.resolve.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class EntryDetailViewModel : ViewModel() {
    private val repository = ServiceLocator.currentEntryRepository()
    private val pipeline = ServiceLocator.logPipeline
    private val events = ServiceLocator.debugEvents
    /** Groups this page's AI calls (a change typed, a food added), so their cost is pinned to the entry. */
    private val tag = java.util.UUID.randomUUID().toString()

    fun entry(id: String) = repository.observeEntries().map { all -> all.firstOrNull { it.id == id } }

    var busy by mutableStateOf(false)
        private set
    var lastChange by mutableStateOf<String?>(null)
        private set

    /** The entry's foods as editor lines. */
    fun draftsOf(entry: Entry): List<ItemDraft> = pipeline.toConfirmed(entry).map { ItemDraft.from(it) }

    /** Which of the entry's foods are the person's own (for "Your usual" rather than the food list). */
    suspend fun mineOf(entry: Entry): Set<String> = withContext(Dispatchers.IO) {
        entry.items.map { it.key }.filter { runCatching { ServiceLocator.personalStore.get(it) }.getOrNull() != null }.toSet()
    }

    /** Other kinds of the same product: theirs first, then the label list's. */
    suspend fun kindsOf(d: ItemDraft): List<FoodCandidate> = withContext(Dispatchers.IO) {
        val c = d.candidate ?: return@withContext emptyList()
        val mine = runCatching { ServiceLocator.personalStore.get(d.key)?.takeIf { !it.isMeal }?.allKinds }.getOrNull().orEmpty()
        val pack = if (c.id.startsWith("label:")) runCatching { ServiceLocator.labelPack.source()?.siblings(c, limit = 4) }.getOrNull().orEmpty() else emptyList()
        (mine + pack).filter { it.id != c.id }.distinctBy { Kinds.label(it).lowercase() }.take(5)
    }

    suspend fun search(query: String): List<FoodCandidate> =
        pipeline.resolveItem(query, tag = tag, why = "search").flatMap { listOfNotNull(it.choice) + it.alternates }.distinctBy { it.id }

    /**
     * Saves [lines] as the entry, at once, and teaches them as a fix does. Returns
     * the entry as it was, for Undo. A line in [oneOff] changes this entry only.
     */
    suspend fun save(entry: Entry, lines: List<ItemDraft>, why: String, oneOff: Set<String> = emptySet()): Entry {
        events.event("entry_quick_change", "entry_id" to entry.id, "why" to why,
            "before" to entry.items.map { mapOf("name" to it.name, "grams" to it.grams, "amount" to it.amount, "unit" to it.unit) },
            "after" to lines.map { mapOf("name" to it.name, "grams" to it.grams, "amount" to it.item.amount, "unit" to it.item.unit) })
        pipeline.confirm(
            text = entry.rawText.ifBlank { entry.name },
            items = lines.map { d -> d.toConfirmed().let { if (d.key in oneOff) it.copy(unchangedMemory = true) else it } },
            replacing = entry,
            tag = tag,
            how = "entry_$why",
        )
        return entry
    }

    /** Puts back the entry as it was before a quick change, numbers and memory both. */
    fun undo(before: Entry) {
        events.event("entry_undo", "entry_id" to before.id)
        viewModelScope.launch {
            pipeline.confirm(text = before.rawText.ifBlank { before.name }, items = pipeline.toConfirmed(before), replacing = before, tag = tag, how = "entry_undo")
            repository.update(before)
        }
    }

    fun change(entry: Entry, said: String, how: String, onDone: (Entry?) -> Unit) {
        if (said.isBlank()) return
        busy = true
        events.event("entry_change_box", "entry_id" to entry.id, "how" to how, "text" to said)
        viewModelScope.launch {
            val (lines, note) = applyChangeTo(draftsOf(entry), said, tag)
            lastChange = note
            val before = if (lines.isEmpty()) null else save(entry, lines, "change")
            busy = false
            onDone(before)
        }
    }

    fun add(entry: Entry, what: String, onDone: (Entry?) -> Unit) {
        if (what.isBlank()) return
        busy = true
        events.event("entry_add_food", "entry_id" to entry.id, "text" to what)
        viewModelScope.launch {
            val found = pipeline.resolveItem(what, tag = tag, why = "add").map { ItemDraft.from(it) }
            val before = if (found.isEmpty()) null else save(entry, draftsOf(entry) + found, "add")
            lastChange = if (found.isEmpty()) "Couldn't find \"$what\". Try other words." else "Added " + found.joinToString(", ") { shortName(it.name) }
            busy = false
            onDone(before)
        }
    }

    fun rename(entry: Entry, name: String) {
        if (name.isBlank() || name == entry.name) return
        events.event("entry_renamed", "entry_id" to entry.id, "from" to entry.name, "to" to name)
        viewModelScope.launch { repository.update(entry.copy(name = name.trim())) }
    }

    /** A new time on the same log day, or "I don't know the time" (noon, said so: never a made-up time). */
    fun retime(entry: Entry, time: LocalTime?) {
        val zone = ZoneId.systemDefault()
        val day = LogDays.dayOf(entry.timestampEpochMillis, zone)
        val stamp = if (time == null) day.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        else (if (time.hour < LogDays.endHour) day.plusDays(1) else day).atTime(time).atZone(zone).toInstant().toEpochMilli()
        events.event("entry_retimed", "entry_id" to entry.id, "from" to entry.timestampEpochMillis, "to" to stamp, "unknown" to (time == null))
        viewModelScope.launch { repository.update(entry.copy(timestampEpochMillis = stamp, timeUnspecified = time == null)) }
    }

    suspend fun save(entry: Entry, before: Entry? = null) {
        val keys = (before?.nutrients.orEmpty().keys + entry.nutrients.keys).distinct()
        events.event("entry_edited", "entry_id" to entry.id, "name" to entry.name, "nutrients" to entry.nutrients,
            "name_before" to before?.name,
            "changed" to before?.let { b -> keys.filter { b.nutrients[it] != entry.nutrients[it] }
                .associateWith { k -> mapOf("before" to b.nutrients[k], "after" to entry.nutrients[k]) } },
            "items" to entry.items.map { mapOf("name" to it.name, "grams" to it.grams, "candidate" to it.candidateId, "origin" to it.origin) })
        repository.update(entry)
    }
    suspend fun delete(entry: Entry) {
        events.event("entry_deleted", "where" to "entry_detail", "entry_id" to entry.id, "name" to entry.name)
        repository.delete(entry)
    }
}

/** "Rolled oats" from "Quaker — Rolled oats, Old Fashioned". */
internal fun shortName(name: String): String = name.substringAfter(" — ").substringBefore(",").trim().take(28)

/** The page title: the food names, "Oats, milk, peanut butter +1" (note ENTRY-5: times, not meals). */
internal fun entryTitle(entry: Entry): String {
    val names = entry.items.map { shortName(it.name) }.filter { it.isNotBlank() }
    val derived = entry.items.joinToString(", ") { it.name.substringBefore(",") }.take(80)
    return when {
        names.size <= 1 || entry.name != derived -> entry.name
        names.size <= 3 -> names.joinToString(", ")
        else -> names.take(3).joinToString(", ") + " +${names.size - 3}"
    }
}

/** "Today · 8:10 AM", "Yesterday · Time not set", "Sat, Oct 24 · 9:05 AM". */
internal fun entrySub(entry: Entry): String {
    val day = LogDays.dayOf(entry.timestampEpochMillis, ZoneId.systemDefault())
    val today = LogDays.today()
    val d = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE, MMM d" else "EEE, MMM d, yyyy"))
    }
    return "$d · ${entry.timeLabel()}"
}

/** Where a food's numbers came from, and whether that is a best guess (shown in amber). */
internal fun sourceTag(origin: Origin?, mine: Boolean): Pair<String, Boolean> = when {
    mine && origin == Origin.LABEL -> "Your label" to false
    mine -> "Your usual" to false
    origin == Origin.LABEL -> "Package label" to false
    origin == Origin.MANUAL -> "Typed in" to false
    origin == Origin.AI -> "AI estimate" to true
    origin == Origin.USDA || origin == Origin.CURATED -> "Food list · best guess" to true
    else -> "Not found" to true
}

/**
 * One entry, in full (notes S2, S3, R4).
 *
 * What the person said leads, because it is the one part of the record the app
 * did not invent. Every food is one tap from being changed: the amount at once,
 * with Undo, and everything else from the same sheet. Every nutrient is listed,
 * with "not known" where nothing was recorded — never a 0.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDetailScreen(
    entryId: String,
    onBack: () -> Unit = {},
    onFix: () -> Unit = {},
    onScan: (index: Int) -> Unit = {},
    viewModel: EntryDetailViewModel = viewModel(),
) {
    val entry by remember(entryId) { viewModel.entry(entryId) }.collectAsState(initial = null)
    val current = entry
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    if (current == null) {
        Column(Modifier.fillMaxSize()) {
            EntryTopBar("Entry", null, onBack) {}
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Entry not found", color = MaterialTheme.colorScheme.secondary)
            }
        }
        return
    }

    var editing by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = editing) { editing = false }
    if (editing) {
        Column(Modifier.fillMaxSize()) {
            EntryTopBar("Edit the numbers", entry?.let(::entrySub), { editing = false }) {}
            EntryEditor(entry = current, onCancel = { editing = false }, onSaved = { editing = false }, viewModel = viewModel)
        }
        return
    }

    /** Shows "what changed · Undo" after a quick change, and puts it back on Undo. */
    fun undoable(before: Entry?, message: String) {
        if (before == null) return
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(message, actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                viewModel.undo(before)
            }
        }
    }

    // A label scanned from this page's food sheet: it becomes that food.
    LaunchedEffect(current.id, LogSession.scanned) {
        val (i, c) = LogSession.scanned ?: return@LaunchedEffect
        if (LogSession.scanEntry != current.id) return@LaunchedEffect
        LogSession.scanned = null; LogSession.scanEntry = null
        val lines = viewModel.draftsOf(current).toMutableList()
        if (i in lines.indices) {
            lines[i] = lines[i].withCandidate(c)
            undoable(viewModel.save(current, lines, "scan"), "${shortName(c.name)} from its label")
        }
    }

    val usage by remember(entryId) { ServiceLocator.database.aiUsageDao().observeForEntry(entryId) }.collectAsState(initial = emptyList())
    val mine by produceState(emptySet<String>(), current.items.map { it.key }) { value = viewModel.mineOf(current) }
    var sheetFor by remember { mutableStateOf<Int?>(null) }
    var editorFor by remember { mutableStateOf<Int?>(null) }
    var swapFor by remember { mutableStateOf<Int?>(null) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var retiming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var saying by remember { mutableStateOf(false) }
    val drafts = remember(current) { viewModel.draftsOf(current) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            EntryTopBar(entryTitle(current), entrySub(current), onBack) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Log again now") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) }, onClick = {
                            menu = false
                            ServiceLocator.debugEvents.event("log_again_tap", "where" to "entry_detail", "entry_id" to current.id)
                            scope.launch {
                                ServiceLocator.logPipeline.logAgain(current)
                                android.widget.Toast.makeText(context, "Logged again", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        })
                        DropdownMenuItem(text = { Text("Change the time") }, leadingIcon = { Icon(Icons.Filled.Schedule, null) }, onClick = { menu = false; retiming = true })
                        DropdownMenuItem(text = { Text("Rename it") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; renaming = true })
                        if (current.items.isEmpty()) {
                            DropdownMenuItem(text = { Text("Edit the numbers") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) }, onClick = { menu = false; editing = true })
                        }
                        DropdownMenuItem(
                            text = { Text("Delete entry", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; deleting = true },
                        )
                    }
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (current.needsDetail || current.nutrients.isEmpty()) {
                    NoNumbersCard(onFix)
                } else {
                    Header(current)
                }
                if (current.needsConfirm) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Logged from your watch on a best guess. Check it once and it's remembered.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = onFix) { Text("Check it") }
                        }
                    }
                }
                if (current.rawText.isNotBlank() && current.rawText != current.name) {
                    Text("“${current.rawText}”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.secondary)
                }

                if (current.items.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("What's in it", Modifier.weight(1f))
                        Text("Tap one to change it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp)) {
                            current.items.forEachIndexed { i, item ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                val (tag, guess) = sourceTag(runCatching { Origin.valueOf(item.origin) }.getOrNull(), item.key in mine)
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { sheetFor = i }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Row {
                                            item.portion?.let { Text("$it · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                                            Text(tag, style = MaterialTheme.typography.bodySmall,
                                                color = if (guess) limitColors().over else MaterialTheme.colorScheme.secondary)
                                        }
                                    }
                                    Text(item.nutrients[Metric.CALORIES.key]?.let { "${format(it)} kcal" } ?: "— kcal", style = NumberStyle,
                                        color = if (item.nutrients.isEmpty()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !viewModel.busy) { adding = true },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Text("Add a food", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    EntryChangeBox(busy = viewModel.busy, last = viewModel.lastChange,
                        onSubmit = { t -> viewModel.change(current, t, "typed") { undoable(it, viewModel.lastChange ?: "Changed") } },
                        onSay = { saying = true })
                }

                SectionLabel("Nutrition", Modifier.padding(top = 8.dp))
                StatTiles(current.nutrients)
                AllNutrients(current.nutrients)

                if (usage.sumOf { it.costUsd } > 0) {
                    Text("AI cost for this entry: ${com.nutritionscribe.app.ui.cost.formatUsd(usage.sumOf { it.costUsd })}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                ReportProblem(current)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    sheetFor?.let { i ->
        val d = drafts.getOrNull(i)
        val item = current.items.getOrNull(i)
        if (d == null || item == null) { sheetFor = null; return@let }
        FoodSheet(
            draft = d,
            item = item,
            mine = item.key in mine,
            kinds = { viewModel.kindsOf(d) },
            onAmount = { nd, label ->
                sheetFor = null
                val lines = drafts.toMutableList().also { it[i] = nd }
                scope.launch { undoable(viewModel.save(current, lines, "amount"), "${shortName(nd.name)}: $label") }
            },
            onKind = { k ->
                sheetFor = null
                val lines = drafts.toMutableList().also { it[i] = d.withCandidate(k) }
                scope.launch { undoable(viewModel.save(current, lines, "kind"), "Now ${Kinds.label(k)}") }
            },
            onEditAll = { sheetFor = null; editorFor = i },
            onSwap = { sheetFor = null; swapFor = i },
            onScan = {
                sheetFor = null
                LogSession.scanHint = (d.item.brand?.let { "$it " }.orEmpty() + d.item.food).trim().ifBlank { d.name }
                LogSession.scanEntry = current.id
                onScan(i)
            },
            onRemove = {
                sheetFor = null
                if (drafts.size == 1) deleting = true
                else scope.launch {
                    val lines = drafts.toMutableList().also { it.removeAt(i) }
                    undoable(viewModel.save(current, lines, "remove"), "Removed ${shortName(d.name)}")
                }
            },
            onDismiss = { sheetFor = null },
        )
    }
    editorFor?.let { i ->
        drafts.getOrNull(i)?.let { d ->
            FoodEditor(
                draft = d,
                partOf = entryTitle(current).takeIf { drafts.size > 1 },
                search = viewModel::search,
                saveLabel = "Save",
                learnDefault = true,
                onSave = { nd, learn ->
                    editorFor = null
                    val lines = drafts.toMutableList().also { it[i] = nd }
                    scope.launch { undoable(viewModel.save(current, lines, "editor", if (learn) emptySet() else setOf(nd.key)), "${shortName(nd.name)} saved") }
                },
                onDismiss = { editorFor = null },
            )
        }
    }
    swapFor?.let { i ->
        val d = drafts.getOrNull(i)
        if (d == null) { swapFor = null; return@let }
        SwapDialog(start = d.item.food.ifBlank { shortName(d.name) }, search = viewModel::search, onPick = { c ->
            swapFor = null
            val lines = drafts.toMutableList().also { it[i] = d.withCandidate(c) }
            scope.launch { undoable(viewModel.save(current, lines, "swap"), "Swapped for ${shortName(ItemDraft.withBrand(c, " — "))}") }
        }, onDismiss = { swapFor = null })
    }
    if (adding) {
        TextAsk(title = "Add a food", hint = "e.g. a glass of milk", onDone = { t ->
            adding = false
            viewModel.add(current, t) { undoable(it, viewModel.lastChange ?: "Added") }
        }, onDismiss = { adding = false })
    }
    if (saying) {
        com.nutritionscribe.app.ui.speech.VoiceDialog(
            title = "Say a change",
            tag = null,
            onResult = { t -> saying = false; viewModel.change(current, t, "voice") { undoable(it, viewModel.lastChange ?: "Changed") } },
            onDismiss = { saying = false },
        )
    }
    if (renaming) {
        TextAsk(title = "Rename it", hint = "e.g. Overnight oats", start = current.name, onDone = { viewModel.rename(current, it); renaming = false },
            onDismiss = { renaming = false })
    }
    if (retiming) {
        TimeDialog(current, onPick = { viewModel.retime(current, it); retiming = false }, onDismiss = { retiming = false })
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this entry?") },
            text = { Text("“${current.name}” will be removed from your log. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { viewModel.delete(current); deleting = false; onBack() } }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep it") } },
        )
    }
}

/** The page's own top bar: the foods as the title, the day and time under it, and ⋮. */
@Composable
private fun EntryTopBar(title: String, sub: String?, onBack: () -> Unit, actions: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1) }
            }
            actions()
        }
    }
}

@Composable
private fun Header(entry: Entry) {
    val n = entry.nutrients
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(n[Metric.CALORIES.key]?.let { format(it) } ?: "—", style = NumberStyle.copy(fontSize = 30.sp, fontWeight = FontWeight.Medium))
            Text(
                listOfNotNull("kcal", n[Metric.PROTEIN.key]?.let { "${format(it)} g protein" }, n[Metric.FIBER.key]?.let { "${format(it)} g fiber" })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Text(
            listOf(sourceLabel(entry), if (entry.confidence == Confidence.STATED) "You gave the amount" else "Estimated").joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/** An entry with nothing counted (R4): says so, and the fix is one tap away. */
@Composable
private fun NoNumbersCard(onFix: () -> Unit) {
    val amber = limitColors().over
    Surface(
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, amber), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.WarningAmber, null, tint = amber)
                Text("No numbers yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text("It couldn't tell what or how much, so nothing was counted. Fix it once and it's remembered.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            Button(onClick = onFix, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Fix it") }
        }
    }
}

/** Six numbers at a glance, then every nutrient under them (note ENTRY-7). */
@Composable
private fun StatTiles(n: Map<String, Double>) {
    val tiles = listOf(Metric.CALORIES, Metric.PROTEIN, Metric.CARBS, Metric.FAT, Metric.FIBER, Metric.SODIUM)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(n[m.key]?.let { format(it) } ?: "—", style = NumberStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium))
                                Text(" " + if (m == Metric.CALORIES) "kcal" else m.unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            Text(m.shortLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Every nutrient, parts under their whole as on a label, "not known" where nothing was recorded. */
@Composable
private fun AllNutrients(n: Map<String, Double>) {
    val rows = listOf(
        Metric.SUGAR to 0, Metric.ADDED_SUGAR to 1, Metric.SATURATED_FAT to 0, Metric.TRANS_FAT to 0,
        Metric.CHOLESTEROL to 0, Metric.CAFFEINE to 0,
    ) + Metric.MICROS.filter { it.key !in setOf(Metric.CHOLESTEROL.key) && (com.nutritionscribe.shared.model.Extras.shown(it) || it.key in n) }.map { it to 0 } +
        listOfNotNull(Metric.ALCOHOL.takeIf { com.nutritionscribe.shared.model.Extras.shown(it) || it.key in n }?.let { it to 0 })
    Column {
        rows.forEach { (m, depth) ->
            val v = n[m.key]
            Row(
                Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = (depth * 14).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (m == Metric.ADDED_SUGAR) "of which added" else m.label, style = MaterialTheme.typography.bodyMedium,
                    color = if (v == null || depth > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f))
                Text(if (v == null) "not known" else "${format(v)} ${m.unit}", style = if (v == null) MaterialTheme.typography.bodySmall else NumberStyle,
                    color = if (v == null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
    }
}

/**
 * A food tapped on its entry (note ENTRY-4): the amount changes at once from the
 * chips, with Undo; its kinds; and everything else from here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FoodSheet(
    draft: ItemDraft,
    item: com.nutritionscribe.shared.model.LoggedItem,
    mine: Boolean,
    kinds: suspend () -> List<FoodCandidate>,
    onAmount: (ItemDraft, String) -> Unit,
    onKind: (FoodCandidate) -> Unit,
    onEditAll: () -> Unit,
    onSwap: () -> Unit,
    onScan: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val others by produceState(emptyList<FoodCandidate>(), draft.key) { value = runCatching { kinds() }.getOrDefault(emptyList()) }
    val (tag, guess) = sourceTag(draft.candidate?.origin, mine)
    var typing by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        com.nutritionscribe.app.ui.feedback.SheetFeedback("Food sheet: ${draft.name}") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(draft.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(item.portion, item.nutrients[Metric.CALORIES.key]?.let { "${format(it)} kcal" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (guess) Icon(Icons.Outlined.WarningAmber, null, tint = limitColors().over, modifier = Modifier.size(16.dp))
                Text(if (guess) "$tag. Check the amount and numbers." else tag, style = MaterialTheme.typography.bodySmall,
                    color = if (guess) limitColors().over else MaterialTheme.colorScheme.secondary)
            }
            SectionLabel("How much")
            val unit = draft.unitLabel
            val amounts = (listOf(0.5, 1.0, 1.5, 2.0) + draft.amount).distinct().sorted()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                amounts.forEach { a ->
                    val label = "${amountWords(a)} $unit"
                    FilterChip(selected = a == draft.amount, onClick = { if (a != draft.amount) onAmount(draft.withAmount(a), label) },
                        label = { Text(label) }, modifier = Modifier.heightIn(min = 44.dp))
                }
                FilterChip(selected = false, onClick = { typing = true }, label = { Text("Other…") }, modifier = Modifier.heightIn(min = 44.dp))
            }
            Text("A tap changes it at once, with Undo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            if (others.isNotEmpty() && draft.candidate != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                    Text("Kind", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.align(Alignment.CenterVertically).padding(end = 2.dp))
                    FilterChip(selected = true, onClick = {}, label = { Text(Kinds.label(draft.candidate)) })
                    others.forEach { k ->
                        FilterChip(selected = false, onClick = {
                            ServiceLocator.debugEvents.event("kind_pick", "where" to "entry_sheet", "from" to draft.candidate.id, "to" to k.id)
                            onKind(k)
                        }, label = { Text(Kinds.label(k)) })
                    }
                    FilterChip(selected = false, onClick = onScan, label = { Text("+ Add a kind", color = MaterialTheme.colorScheme.primary) })
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 6.dp)) {
                    SheetAction(Icons.AutoMirrored.Filled.List, "See and edit all its nutrients", onEditAll)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SheetAction(Icons.Filled.SwapHoriz, "Swap for another food", onSwap)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SheetAction(Icons.Outlined.CameraAlt, "Scan its label", onScan)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SheetAction(Icons.Filled.Delete, "Remove from this entry", onRemove, MaterialTheme.colorScheme.error)
                }
            }
        }
        }
    }
    if (typing) {
        var t by remember { mutableStateOf(Units.format(draft.amount)) }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text("How many ${draft.unitLabel}?") },
            text = {
                OutlinedTextField(value = t, onValueChange = { t = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("Fractions work too: 1/2, 1 1/2. Other units are under See and edit.") })
            },
            confirmButton = {
                TextButton(enabled = parseAmount(t) != null, onClick = {
                    typing = false
                    parseAmount(t)?.let { a -> onAmount(draft.withAmount(a), "${amountWords(a)} ${draft.unitLabel}") }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit, color: Color? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = color ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

/** "Say or type a change…": the same box as Check it once, saved at once with Undo. */
@Composable
private fun EntryChangeBox(busy: Boolean, last: String?, onSubmit: (String) -> Unit, onSay: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, enabled = !busy,
            placeholder = { Text("Say or type a change…") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { onSubmit(text.trim()); text = "" } }),
            trailingIcon = {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    text.isNotBlank() -> TextButton(onClick = { onSubmit(text.trim()); text = "" }) { Text("Go") }
                    else -> IconButton(onClick = onSay) { Icon(Icons.Filled.Mic, contentDescription = "Say a change") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        last?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun TextAsk(title: String, hint: String, start: String = "", onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(start) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }, enabled = text.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text(hint) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
    )
}

/** "Swap for another food": search by other words and pick. */
@Composable
private fun SwapDialog(start: String, search: suspend (String) -> List<FoodCandidate>, onPick: (FoodCandidate) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf(start) }
    var results by remember { mutableStateOf<List<FoodCandidate>?>(null) }
    var looking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun go() { if (query.isBlank()) return; looking = true; scope.launch { results = runCatching { search(query.trim()) }.getOrDefault(emptyList()); looking = false } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Swap for another food") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("What was it?") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go() }),
                    trailingIcon = { TextButton(onClick = { go() }, enabled = !looking) { Text("Find") } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (looking) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                results?.let { list ->
                    if (list.isEmpty()) Text("Nothing found. Try other words.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        list.take(8).forEach { c ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 8.dp)) {
                                Text(ItemDraft.withBrand(c, " — "), style = MaterialTheme.typography.bodyMedium)
                                Text(ItemDraft.label(c.origin), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
        },
    )
    LaunchedEffect(Unit) { go() }
}

/** "Change the time": a time on the same day, or "I don't know the time" (never a made-up one). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(entry: Entry, onPick: (LocalTime?) -> Unit, onDismiss: () -> Unit) {
    val at = Instant.ofEpochMilli(entry.timestampEpochMillis).atZone(ZoneId.systemDefault()).toLocalTime()
    val state = rememberTimePickerState(initialHour = at.hour, initialMinute = at.minute)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("What time?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onPick(null) }) { Text("I don't know") }
                    Row {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") }
                    }
                }
            }
        }
    }
}

/** Where the numbers came from, in words rather than in storage terms. */
private fun sourceLabel(entry: Entry): String = when {
    entry.items.isNotEmpty() -> entry.items.map {
        ItemDraft.label(runCatching { Origin.valueOf(it.origin) }.getOrNull())
    }.distinct().joinToString(" + ")
    entry.foodDbId != null -> "Offline food list"
    entry.isFixedFood -> "Saved food"
    entry.source.isBlank() -> "Typed in"
    entry.source == "usda" -> "Offline food list"
    entry.source.startsWith("manual") -> "Typed in"
    else -> "AI lookup"
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

private val DATE_FORMAT: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")

private fun dateLabel(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(DATE_FORMAT)

/** As the rest of the app reads: whole numbers from 10 up, one decimal below, two below 1. */
private fun format(value: Double): String = when {
    value >= 10 -> Math.round(value).toString()
    value == value.toLong().toDouble() -> value.toLong().toString()
    value >= 1 -> String.format(java.util.Locale.ROOT, "%.1f", value).removeSuffix(".0")
    else -> String.format(java.util.Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
}

/** "½", "1½", "2": an amount as a person says it. */
private fun amountWords(a: Double): String {
    val whole = a.toLong()
    val frac = a - whole
    val f = when { kotlin.math.abs(frac - 0.5) < 0.01 -> "\u00BD"; kotlin.math.abs(frac - 0.25) < 0.01 -> "\u00BC"; kotlin.math.abs(frac - 0.75) < 0.01 -> "\u00BE"; else -> null }
    return when {
        f != null && whole == 0L -> f
        f != null -> "$whole$f"
        else -> Units.format(a)
    }
}

/**
 * "Something's wrong with this one": a note in the debug log with the entry as
 * stored, so the problem can be found by what it is about rather than by when.
 */
@Composable
internal fun ReportProblem(entry: Entry) {
    var open by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val reportScope = androidx.compose.runtime.rememberCoroutineScope()
    // Outlined, not grey text: it went unnoticed for days as a text link.
    androidx.compose.material3.OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Something's wrong with this entry? Tell us")
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
                    // Something in it is wrong: what these words were taken to mean is
                    // forgotten, so the same wrong answer is not logged silently again.
                    reportScope.launch {
                        ServiceLocator.logPipeline.forget(entry.rawText, entry.items.map { it.key }, "user_report")
                    }
                    android.widget.Toast.makeText(context, "Noted. It will check these foods with you next time.", android.widget.Toast.LENGTH_LONG).show()
                }) { Text("Save note") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}
