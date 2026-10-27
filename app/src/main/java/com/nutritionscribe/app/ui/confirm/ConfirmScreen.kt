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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.Liquids
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
    /** The entry page a scan was started from, which takes [scanned] when it comes back. */
    var scanEntry: String? = null
    /** The name of the line a scan was started from, to fill the scan form's name. */
    var scanHint: String? = null
    /** Lines to start from when there was no sentence: a scan from the Log screen. */
    var startDrafts: List<ItemDraft>? = null
    /** Groups this entry's AI calls, so what they cost is pinned to it. */
    var tag: String? = null
    /** voice, typed, scan… for the debug log. */
    var how: String = "typed"

    fun start(r: Resolver.Resolution, tag: String?, how: String) {
        clear()
        resolution = r
        this.tag = tag
        this.how = how
    }

    fun startFromScan(c: FoodCandidate) {
        clear()
        startDrafts = listOf(ItemDraft.fromCandidate(c))
        tag = java.util.UUID.randomUUID().toString()
        how = "scan"
    }

    fun startFix(entry: Entry?) {
        clear()
        editing = entry
        tag = java.util.UUID.randomUUID().toString()
        how = "fix"
    }

    fun clear() {
        resolution = null; editing = null; timestamp = null; startDrafts = null; tag = null; how = "typed"; scanEntry = null
    }
}

class ConfirmViewModel : ViewModel() {
    private val pipeline = ServiceLocator.logPipeline

    val tracked = ServiceLocator.goalsRepository.enabledGoals.map { goals -> goals.map { it.metric } }

    private val events = ServiceLocator.debugEvents
    val editing: Entry? = LogSession.editing
    val tag: String? = LogSession.tag
    private val how: String = LogSession.how
    var text by mutableStateOf(LogSession.resolution?.text ?: editing?.rawText.orEmpty())
        private set
    var aiProblem by mutableStateOf(LogSession.resolution?.aiProblem)
        private set
    val drafts = mutableStateListOf<ItemDraft>().apply {
        val r = LogSession.resolution
        when {
            r != null -> addAll(r.items.map { ItemDraft.from(it) })
            editing != null -> addAll(pipeline.toConfirmed(editing).map { ItemDraft.from(it) })
            LogSession.startDrafts != null -> addAll(LogSession.startDrafts!!)
        }
    }
    /** What the app offered before the person touched anything, for the debug log's before/after. */
    private var suggested = drafts.map { it.toConfirmed() }
    /** The person has changed a line by hand, so rebuilding from the words would lose that. */
    var handEdited by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var saveAsMine by mutableStateOf(false)
    var mineName by mutableStateOf("")

    init {
        events.startFlow(how, onlyIfIdle = true)
        events.event("confirm_open", "how" to how, "tag" to tag, "text" to text, "fixing" to editing?.id,
            "lines" to drafts.map { mapOf("said" to it.item.said, "name" to it.name, "status" to it.status, "nutrients" to it.nutrients) })
    }

    fun update(i: Int, d: ItemDraft) {
        if (i !in drafts.indices) return
        events.event("confirm_change", "index" to i, "before" to lineOf(drafts[i]), "after" to lineOf(d))
        drafts[i] = d
        handEdited = true
    }

    /** Remembered lines they took off the card: those words meant something else. */
    private val forgetKeys = mutableSetOf<String>()

    fun remove(i: Int) {
        if (i !in drafts.indices) return
        events.event("confirm_remove", "index" to i, "line" to lineOf(drafts[i]))
        if (drafts[i].status == Resolver.Status.REMEMBERED) forgetKeys += drafts[i].key
        drafts.removeAt(i)
        handEdited = true
    }

    fun note(action: String, vararg detail: Pair<String, Any?>) = events.event("confirm_$action", *detail)

    /** Lines whose fix is for this entry only: "Use these numbers next time too" was off. */
    private val oneOff = mutableSetOf<String>()

    fun setLearn(key: String, learn: Boolean) {
        events.event("confirm_learn", "key" to key, "learn" to learn)
        if (learn) oneOff -= key else oneOff += key
    }

    /**
     * New words — edited by hand or said again — and the lines are worked out from
     * them afresh. Anything changed by hand on the old lines is replaced; the
     * screen asks before doing that.
     */
    fun rebuild(newText: String, why: String) {
        if (newText.isBlank()) return
        events.event("confirm_rebuild", "why" to why, "old_text" to text, "new_text" to newText, "hand_edited" to handEdited)
        busy = true
        viewModelScope.launch {
            val r = pipeline.resolve(newText, tag = tag, how = why)
            text = newText
            aiProblem = r.aiProblem
            drafts.clear()
            drafts.addAll(r.items.map { ItemDraft.from(it) })
            suggested = drafts.map { it.toConfirmed() }
            handEdited = false
            busy = false
        }
    }

    private fun lineOf(d: ItemDraft) = mapOf("said" to d.item.said, "name" to d.name, "grams" to d.grams,
        "amount" to d.item.amount, "unit" to d.item.unit, "candidate" to d.candidate?.id, "nutrients" to d.nutrients)

    /** "What was in it?" — the parts replace the dish, and the dish can be saved as theirs. */
    fun breakDown(i: Int, parts: String) {
        if (parts.isBlank() || i !in drafts.indices) return
        val dish = drafts[i]
        busy = true
        handEdited = true
        events.event("confirm_breakdown", "index" to i, "dish" to dish.name, "parts" to parts)
        viewModelScope.launch {
            val found = pipeline.resolveItem(parts, tag = tag, why = "breakdown").map { ItemDraft.from(it) }
            if (found.isNotEmpty()) {
                drafts.removeAt(i)
                drafts.addAll(i, found)
                saveAsMine = true
                if (mineName.isBlank()) mineName = dish.item.food.replaceFirstChar { it.uppercase() }
            }
            busy = false
        }
    }

    /** What the last change did, in words, under the change box. */
    var lastChange by mutableStateOf<String?>(null)
        private set

    /** One tap of − or + on a line, in that line's unit's steps. */
    fun bump(i: Int, dir: Int) {
        val d = drafts.getOrNull(i) ?: return
        val next = com.nutritionscribe.shared.resolve.Units.bump(d.item.unit, d.amount, dir)
        events.event("confirm_step", "index" to i, "dir" to dir, "unit" to d.item.unit, "from" to d.amount, "to" to next)
        update(i, d.withAmount(next))
    }

    fun setAmount(i: Int, amount: Double) {
        val d = drafts.getOrNull(i) ?: return
        if (amount <= 0) return
        update(i, d.withAmount(amount))
    }

    /** Count the line in another unit, keeping how much food it is. */
    fun setUnit(i: Int, unit: String?) {
        val d = drafts.getOrNull(i) ?: return
        if (unit == d.item.unit) return
        update(i, d.withUnitKept(unit))
    }

    /**
     * "actually 2 slices", "remove the cheese", "make the milk 300 mL", "add a
     * tablespoon of honey": a change, typed or said. A food that is already a line
     * changes that line instead of being added again.
     */
    fun applyChange(said: String, how: String = "typed") {
        if (said.isBlank()) return
        busy = true
        handEdited = true
        events.event("confirm_add", "how" to how, "text" to said)
        viewModelScope.launch {
            val (lines, note) = applyChangeTo(drafts.toList(), said, tag)
            drafts.clear()
            drafts.addAll(lines)
            lastChange = note
            busy = false
        }
    }

    /** Re-search one line by a different name, for the editor's "not this" list. */
    suspend fun search(query: String): List<FoodCandidate> =
        pipeline.resolveItem(query, tag = tag, why = "search").flatMap { listOfNotNull(it.choice) + it.alternates }.distinctBy { it.id }

    fun applyScan() {
        val (i, c) = LogSession.scanned ?: return
        LogSession.scanned = null
        events.event("confirm_scan_result", "index" to i, "candidate" to mapOf("id" to c.id, "name" to c.name, "brand" to c.brand))
        if (i in drafts.indices) { drafts[i] = drafts[i].withCandidate(c); handEdited = true }
        else if (i < 0) { drafts.add(ItemDraft.fromCandidate(c)); handEdited = true }
    }

    fun log(onDone: () -> Unit) {
        if (drafts.isEmpty()) return
        busy = true
        val (stamp, unspecified) = LogSession.timestamp?.let { it to false } ?: LogDay.stamp()
        viewModelScope.launch {
            // Before confirming, so the lines kept are what is learned afresh.
            val keep = drafts.map { it.key }.toSet()
            (forgetKeys - keep).takeIf { it.isNotEmpty() }?.let { pipeline.forget(null, it, "removed_from_card") }
            pipeline.confirm(
                text = text,
                items = drafts.map { d -> d.toConfirmed().let { if (d.key in oneOff) it.copy(unchangedMemory = true) else it } },
                timestamp = stamp,
                timeUnspecified = unspecified,
                replacing = editing,
                saveAsMine = mineName.takeIf { saveAsMine && it.isNotBlank() },
                tag = tag,
                how = how,
                suggested = suggested,
            )
            LogSession.clear()
            if (editing == null) LogDay.today()
            busy = false
            onDone()
        }
    }
}

/**
 * "actually 2 slices", "remove the cheese", "add a tablespoon of honey" applied to
 * [drafts]: the new lines, and what changed in words. Shared by Check it once and
 * the entry page's change box.
 */
internal suspend fun applyChangeTo(drafts: List<ItemDraft>, said: String, tag: String?): Pair<List<ItemDraft>, String> {
    val pipeline = ServiceLocator.logPipeline
    val out = drafts.toMutableList()
    val lines = out.map { com.nutritionscribe.shared.resolve.Changes.Line(it.name, it.item.food.ifBlank { it.name }, it.item.unit) }
    val changes = pipeline.changes(said, lines)
    val notes = mutableListOf<String>()
    fun short(n: String) = n.substringAfter(" — ").substringBefore(",").take(28)
    for (c in changes.filterIsInstance<com.nutritionscribe.shared.resolve.Changes.Change.SetAmount>()) {
        val d = out.getOrNull(c.line) ?: continue
        val nd = if (c.unitSaid && c.unit != d.item.unit) d.withAmountIn(c.amount, c.unit) else d.withAmount(c.amount)
        out[c.line] = nd
        notes += "${short(nd.name)}: ${com.nutritionscribe.shared.resolve.Units.format(nd.amount)} ${nd.unitLabel}"
    }
    for (i in changes.filterIsInstance<com.nutritionscribe.shared.resolve.Changes.Change.Remove>().map { it.line }.distinct().sortedDescending()) {
        out.getOrNull(i)?.let { notes += "removed ${short(it.name)}"; out.removeAt(i) }
    }
    for (a in changes.filterIsInstance<com.nutritionscribe.shared.resolve.Changes.Change.Add>()) {
        val found = pipeline.resolveItem(a.text, tag = tag, why = "add").map { ItemDraft.from(it) }
        out.addAll(found)
        found.forEach { notes += "added ${short(it.name)}" }
    }
    val note = if (notes.isEmpty()) "Nothing changed. Try \"2 slices\", \"remove the cheese\" or \"add a cup of milk\"."
    else notes.joinToString(" · ").replaceFirstChar { it.uppercase() }
    return out to note
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
    var addingByVoice by remember { mutableStateOf(false) }
    var sayingAgain by remember { mutableStateOf(false) }
    /** New words waiting on "replace your changes?" */
    var pendingRebuild by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun requestRebuild(newText: String, why: String) {
        if (viewModel.handEdited) pendingRebuild = newText to why else viewModel.rebuild(newText, why)
    }
    // A scan that came back while we were away (one for an entry page is that page's).
    remember(LogSession.scanned) { if (LogSession.scanEntry == null) viewModel.applyScan(); 0 }

    val drafts = viewModel.drafts
    val fresh = drafts.count { it.status != Resolver.Status.REMEMBERED }
    val total = sumNutrients(drafts.map { it.nutrients })

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (viewModel.editing == null && LogDay.isPast) {
                    com.nutritionscribe.app.ui.addentry.PastDayBanner()
                }
                if (viewModel.text.isNotBlank() || viewModel.editing != null) {
                    YouSaid(
                        text = viewModel.text,
                        busy = viewModel.busy,
                        onEdited = { requestRebuild(it, "edited_words") },
                        onSayAgain = { viewModel.note("say_again_open"); sayingAgain = true },
                        onEditOpened = { viewModel.note("edit_words_open") },
                    )
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
                onScan = {
                    LogSession.scanEntry = null
                    // What they called it, so the scan form starts with a name.
                    LogSession.scanHint = (d.item.brand?.let { "$it " }.orEmpty() + d.item.food).trim().takeIf { it.isNotBlank() }
                    onScan(i)
                },
                onBreakdown = { breakdownIndex = i },
                onPick = { c -> viewModel.update(i, d.withCandidate(c)) },
                onBump = { dir -> viewModel.bump(i, dir) },
                onAmount = { viewModel.setAmount(i, it) },
                onUnit = { viewModel.setUnit(i, it) },
            )
        }
        item {
            ChangeBox(
                busy = viewModel.busy,
                last = viewModel.lastChange,
                onSubmit = { viewModel.applyChange(it, "typed") },
                onSay = { addingByVoice = true },
            )
            TextButton(onClick = { LogSession.scanEntry = null; onScan(-1) }) { Text("+ Scan another package") }
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
            // Right where a fix happens: the note travels with the entry as it was.
            viewModel.editing?.let { com.nutritionscribe.app.ui.entry.ReportProblem(it) }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }

    editingIndex?.let { i ->
        drafts.getOrNull(i)?.let { d ->
            FoodEditor(
                draft = d,
                partOf = viewModel.editing?.name?.takeIf { drafts.size > 1 },
                search = viewModel::search,
                saveLabel = "Done",
                learnDefault = true,
                onSave = { nd, learn -> viewModel.update(i, nd); viewModel.setLearn(nd.key, learn); editingIndex = null },
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
    if (sayingAgain) {
        com.nutritionscribe.app.ui.speech.VoiceDialog(
            title = "Say it again",
            tag = viewModel.tag,
            onResult = { sayingAgain = false; requestRebuild(it, "said_again") },
            onDismiss = { sayingAgain = false },
        )
    }
    if (addingByVoice) {
        com.nutritionscribe.app.ui.speech.VoiceDialog(
            title = "Say a change",
            tag = viewModel.tag,
            onResult = { addingByVoice = false; viewModel.applyChange(it, how = "voice") },
            onDismiss = { addingByVoice = false },
        )
    }
    pendingRebuild?.let { (newText, why) ->
        AlertDialog(
            onDismissRequest = { pendingRebuild = null },
            title = { Text("Start the list again?") },
            text = { Text("The foods will be worked out again from your new words, so the changes you made to them by hand will be replaced.") },
            confirmButton = { TextButton(onClick = { pendingRebuild = null; viewModel.rebuild(newText, why) }) { Text("Redo the list") } },
            dismissButton = { TextButton(onClick = { pendingRebuild = null }) { Text("Keep my changes") } },
        )
    }
    if (adding) {
        TextPrompt(
            title = "Add something",
            hint = "e.g. a glass of milk",
            onDone = { viewModel.applyChange(it); adding = false },
            onDismiss = { adding = false },
        )
    }
}

/**
 * What they said, where they can fix it. A misheard word is the commonest thing
 * wrong with a spoken entry, and it used to be a line of grey text with no way to
 * change it short of starting again.
 */
@Composable
private fun YouSaid(
    text: String,
    busy: Boolean,
    onEdited: (String) -> Unit,
    onSayAgain: () -> Unit,
    onEditOpened: () -> Unit,
) {
    var editingText by remember { mutableStateOf(false) }
    var draft by remember(text) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("You said", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            if (editingText) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { editingText = false; if (draft.text.trim() != text.trim()) onEdited(draft.text.trim()) },
                        enabled = draft.text.isNotBlank(),
                    ) { Text("Done") }
                    TextButton(onClick = { editingText = false; draft = androidx.compose.ui.text.input.TextFieldValue(text) }) { Text("Cancel") }
                }
            } else {
                Text(
                    text.trim().ifBlank { "(nothing said — typed by hand)" },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { editingText = true; onEditOpened() },
                )
                if (busy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { editingText = true; onEditOpened() },
                        enabled = !busy,
                        label = { Text("Edit words") },
                        leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Edit, contentDescription = null, modifier = Modifier.width(18.dp)) },
                    )
                    AssistChip(
                        onClick = onSayAgain,
                        enabled = !busy,
                        label = { Text("Say it again") },
                        leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Mic, contentDescription = null, modifier = Modifier.width(18.dp)) },
                    )
                }
            }
        }
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
    onPick: (FoodCandidate) -> Unit,
    onBump: (Int) -> Unit = {},
    onAmount: (Double) -> Unit = {},
    onUnit: (String?) -> Unit = {},
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
                        // The amount is on the stepper below; here, only where it came from.
                        listOfNotNull(
                            if (draft.status == Resolver.Status.REMEMBERED) "Your usual" else ItemDraft.label(draft.candidate?.origin),
                            draft.grams.takeIf { unknown }?.let { draft.amountText(it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
            }
            if (unknown) {
                // "4 burritos" sat here unanswered and was never logged: the choices
                // were one dialog away. The likeliest ones are right on the card now.
                Text(if (draft.alternates.isNotEmpty()) "Which one?" else "Couldn't tell what this was. Tap to pick it, scan it, or break it down.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (draft.alternates.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        draft.alternates.take(4).forEach { c ->
                            androidx.compose.material3.SuggestionChip(
                                onClick = { onPick(c) },
                                label = { Text(ItemDraft.withBrand(c, " "), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            } else {
                AmountStepper(draft, onBump, onAmount, onUnit)
                Text(numbersLine(draft.nutrients, tracked), style = MaterialTheme.typography.bodyMedium)
                KindChips(draft, onPick, onOther = onChange)
                // Their other brands of the same food (bread changes every shop):
                // the latest is picked, the rest are a tap away.
                if (draft.otherBrands && draft.alternates.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Or:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.align(Alignment.CenterVertically))
                        draft.alternates.take(3).forEach { c ->
                            androidx.compose.material3.SuggestionChip(
                                onClick = {
                                    ServiceLocator.debugEvents.event("brand_swap", "from" to draft.candidate?.id, "to" to c.id)
                                    onPick(c)
                                },
                                label = { Text(ItemDraft.withBrand(c, " "), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
                if (draft.weak && draft.status != Resolver.Status.REMEMBERED) {
                    Text("Best guess — worth a look", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onChange, label = { Text(if (unknown) "Pick it" else "Change") })
                AssistChip(onClick = onScan, label = { Text("Scan its label") })
                if (draft.offerBreakdown) AssistChip(onClick = onBreakdown, label = { Text("Break it down") })
            }
        }
    }
}

/**
 * "Which kind? Optional": a packaged food's other kinds (the strawberry and
 * blueberry of the vanilla yogurt), right under it. Never needed: when they are
 * within a few calories it says so, and the line logs fine as it is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindChips(draft: ItemDraft, onPick: (FoodCandidate) -> Unit, onOther: () -> Unit) {
    val c = draft.candidate ?: return
    if (draft.status == Resolver.Status.REMEMBERED || !c.id.startsWith("label:")) return
    val kinds by androidx.compose.runtime.produceState<List<FoodCandidate>>(emptyList(), c.id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { ServiceLocator.labelPack.source()?.siblings(c, limit = 4).orEmpty() }.getOrDefault(emptyList())
        }
    }
    if (kinds.isEmpty()) return
    val numbers = (listOf(c) + kinds).map { com.nutritionscribe.shared.resolve.Kinds.nutrientsFor(it, draft.grams, draft.amount) }
    val spread = com.nutritionscribe.shared.resolve.Kinds.spread(numbers)
    val differ = com.nutritionscribe.shared.resolve.Kinds.differ(numbers)
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            if (differ) "Which kind? They differ by up to ${Math.round(spread)} kcal"
            else "Which kind? Optional, they're within ${maxOf(5L, Math.round(spread / 5.0) * 5)} kcal",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            androidx.compose.material3.FilterChip(selected = true, onClick = {}, label = { Text(com.nutritionscribe.shared.resolve.Kinds.label(c)) })
            kinds.forEach { k ->
                androidx.compose.material3.FilterChip(
                    selected = false,
                    onClick = {
                        ServiceLocator.debugEvents.event("kind_pick", "where" to "confirm", "from" to c.id, "to" to k.id)
                        onPick(k)
                    },
                    label = { Text(com.nutritionscribe.shared.resolve.Kinds.label(k)) },
                )
            }
            androidx.compose.material3.FilterChip(selected = false, onClick = onOther, label = { Text("+ Other", color = MaterialTheme.colorScheme.primary) })
        }
    }
}

/**
 * − amount unit +. The steps fit the unit (½ slice, ¼ cup, 5 g, 25 mL); the amount
 * can be typed, and the unit switched without changing how much food it is.
 */
@Composable
private fun AmountStepper(draft: ItemDraft, onBump: (Int) -> Unit, onAmount: (Double) -> Unit, onUnit: (String?) -> Unit) {
    var typing by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { onBump(-1) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            modifier = Modifier.width(40.dp).height(36.dp)) { Text("−") }
        Text(
            com.nutritionscribe.shared.resolve.Units.format(draft.amount),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.clickable { typing = true }.padding(horizontal = 6.dp, vertical = 6.dp)
                .androidx_semantics("Amount, tap to type"),
        )
        androidx.compose.foundation.layout.Box {
            AssistChip(
                onClick = { unitMenu = true },
                enabled = draft.unitOptions.size > 1,
                label = { Text("${draft.unitLabel} ▾") },
            )
            androidx.compose.material3.DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                draft.unitOptions.forEach { o ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(o.label, fontWeight = if (o.unit == draft.item.unit) FontWeight.SemiBold else FontWeight.Normal) },
                        onClick = { unitMenu = false; onUnit(o.unit) },
                    )
                }
            }
        }
        OutlinedButton(onClick = { onBump(+1) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            modifier = Modifier.width(40.dp).height(36.dp)) { Text("+") }
        draft.grams?.let { g ->
            Text(draft.amountText(g), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
    if (typing) {
        var t by remember { mutableStateOf(com.nutritionscribe.shared.resolve.Units.format(draft.amount)) }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text("How many ${draft.unitLabel}?") },
            text = {
                OutlinedTextField(
                    value = t, onValueChange = { t = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("Fractions work too: 1/2, 1.5") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    parseAmount(t)?.let(onAmount); typing = false
                }, enabled = parseAmount(t) != null) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
        )
    }
}

/** "1/2", "1 1/2", "1.5", ".5": an amount typed by hand. */
internal fun parseAmount(text: String): Double? {
    val t = text.trim().replace(',', '.')
    t.toDoubleOrNull()?.let { return it.takeIf { v -> v > 0 } }
    Regex("^(\\d+)\\s+(\\d+)/(\\d+)$").find(t)?.let { m ->
        val d = m.groupValues[3].toDouble(); if (d == 0.0) return null
        return m.groupValues[1].toDouble() + m.groupValues[2].toDouble() / d
    }
    Regex("^(\\d+)/(\\d+)$").find(t)?.let { m ->
        val d = m.groupValues[2].toDouble(); if (d == 0.0) return null
        return m.groupValues[1].toDouble() / d
    }
    return null
}

private fun Modifier.androidx_semantics(label: String): Modifier =
    this.then(Modifier.semantics { contentDescription = label })

/** "Say or type a change": the one box for adding, removing and changing amounts. */
@Composable
private fun ChangeBox(busy: Boolean, last: String?, onSubmit: (String) -> Unit, onSay: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = !busy,
            placeholder = { Text("Say or type a change…") },
            supportingText = { Text("\"2 slices of bread\", \"remove the cheese\", \"add a cup of milk\"") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                if (text.isNotBlank()) { onSubmit(text.trim()); text = "" }
            }),
            trailingIcon = {
                Row {
                    if (text.isNotBlank()) {
                        TextButton(onClick = { onSubmit(text.trim()); text = "" }) { Text("Go") }
                    } else {
                        IconButton(onClick = onSay, enabled = !busy) { Icon(Icons.Default.Mic, contentDescription = "Say a change") }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        last?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
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

internal fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else if (v >= 10) String.format(java.util.Locale.ROOT, "%.0f", v)
    else String.format(java.util.Locale.ROOT, "%.1f", v)
