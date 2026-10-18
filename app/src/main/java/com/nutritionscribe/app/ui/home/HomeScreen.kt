package com.nutritionscribe.app.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.data.db.toEntry
import com.nutritionscribe.app.data.repository.MicMode
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.addentry.LogFlow
import com.nutritionscribe.app.ui.confirm.LogDay
import com.nutritionscribe.app.ui.history.formatAmount
import com.nutritionscribe.app.ui.speech.ListeningPanel
import com.nutritionscribe.app.ui.speech.MicButton
import com.nutritionscribe.app.ui.speech.VoiceController
import com.nutritionscribe.app.ui.speech.rememberVoiceController
import com.nutritionscribe.app.ui.theme.NumberStyle
import com.nutritionscribe.app.ui.theme.SectionLabelStyle
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.GoalDirection
import com.nutritionscribe.shared.model.LogAgain
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import com.nutritionscribe.shared.model.MetricGroup
import com.nutritionscribe.shared.model.NutrientDay
import com.nutritionscribe.shared.model.NutrientTotal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The entry that just logged without a card, for the "Logged — Fix / Undo" message on Home. */
object LastLogged {
    data class Note(val entry: Entry, val how: String, val undoable: Boolean)
    val entry = kotlinx.coroutines.flow.MutableStateFlow<Note?>(null)
    fun show(e: Entry, how: String, undoable: Boolean = false) { entry.value = Note(e, how, undoable) }
}

class HomeViewModel : ViewModel() {
    private val repository = ServiceLocator.currentEntryRepository()
    private val dao = ServiceLocator.database.entryDao()

    private val all: Flow<List<Entry>> = dao.observeAll().map { rows -> rows.map { it.toEntry() } }

    val todayEntries: Flow<List<Entry>> = all.map { list ->
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        list.filter { it.timestampEpochMillis >= start }.sortedBy { it.timestampEpochMillis }
    }

    val goals: Flow<List<MetricGoal>> = ServiceLocator.goalsRepository.goals
    val unprocessedCount = repository.observeUnprocessed().map { it.size }
    val needsGlance = dao.observeNeedsConfirm().map { rows -> rows.map { it.entry.id } }
    val micMode = ServiceLocator.settingsRepository.micMode
    val homeSort = ServiceLocator.settingsRepository.homeSort
    val ideas = com.nutritionscribe.app.ui.ideas.IdeasSource.flow()

    fun setSort(sort: com.nutritionscribe.app.data.repository.HomeSort) {
        ServiceLocator.debugEvents.event("home_sort", "sort" to sort.name)
        viewModelScope.launch { ServiceLocator.settingsRepository.setHomeSort(sort) }
    }
    val profile = ServiceLocator.settingsRepository.profile
    val donations = ServiceLocator.settingsRepository.plasmaDonations
    val plasmaHidden = ServiceLocator.settingsRepository.plasmaHiddenOn

    fun hidePlasma(day: LocalDate) {
        ServiceLocator.debugEvents.event("plasma_hidden", "day" to day.toString())
        viewModelScope.launch { ServiceLocator.settingsRepository.setPlasmaHiddenOn(day.toString()) }
    }
    /** The provider to ask about before anything is sent to it, or null. */
    val consentAsk: Flow<com.nutritionscribe.app.data.repository.AiBackendKind?> = kotlinx.coroutines.flow.combine(
        ServiceLocator.settingsRepository.aiConsent,
        ServiceLocator.settingsRepository.lookupSource,
        ServiceLocator.settingsRepository.backend,
        ServiceLocator.settingsRepository.keysChanged,
    ) { consent, source, backend, _ ->
        val s = ServiceLocator.settingsRepository
        com.nutritionscribe.app.data.repository.AiConsent.pending(
            consent, source == com.nutritionscribe.app.data.repository.LookupSource.AI, backend,
        ) { !s.getApiKey(it).isNullOrBlank() }.firstOrNull()
    }

    fun answerConsent(kind: com.nutritionscribe.app.data.repository.AiBackendKind, agreed: Boolean) {
        ServiceLocator.debugEvents.always("ai_consent", "provider" to kind.name, "agreed" to agreed, "where" to "home")
        viewModelScope.launch {
            val s = ServiceLocator.settingsRepository
            s.setAiConsent(kind, agreed)
            // Saying no to the lookup provider is saying no to Highest quality mode;
            // Settings should show it off, not on-but-silent.
            if (!agreed && kind == s.backend.first()) s.setLookupSource(com.nutritionscribe.app.data.repository.LookupSource.OFFLINE)
        }
    }

    /** A week after install (or after the last send): time to send the debug log back. */
    val logDue: Flow<Boolean> = kotlinx.coroutines.flow.combine(
        ServiceLocator.settingsRepository.installedAt,
        ServiceLocator.settingsRepository.logSentAt,
        ServiceLocator.settingsRepository.logSnoozeUntil,
    ) { installed, sent, snooze ->
        com.nutritionscribe.app.ui.debuglog.LogShare.due(installed, sent, snooze, System.currentTimeMillis())
    }

    fun snoozeLog() {
        ServiceLocator.debugEvents.event("log_reminder_later")
        viewModelScope.launch { ServiceLocator.settingsRepository.snoozeLogReminder(System.currentTimeMillis() + 2L * 86_400_000L) }
    }

    fun setDonated(day: LocalDate, donated: Boolean) {
        ServiceLocator.debugEvents.event("plasma_donation", "day" to day.toString(), "donated" to donated)
        viewModelScope.launch { ServiceLocator.settingsRepository.setPlasmaDonation(day, donated) }
    }

    /** Everything that could be logged again, ranked on screen. */
    val choices: Flow<List<LogAgain.Choice>> = all.map { LogAgain.choices(it, System.currentTimeMillis(), ZoneId.systemDefault()) }

    /** The words being looked up after the mic, for the panel above the dock. */
    var lookingUp by mutableStateOf<String?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    fun submit(text: String, how: String, tag: String?, onReview: () -> Unit) {
        LogDay.today()
        lookingUp = text
        problem = null
        viewModelScope.launch {
            when (val out = LogFlow.submit(text, how, tag)) {
                is LogFlow.Outcome.Logged -> Unit
                LogFlow.Outcome.Review -> onReview()
                is LogFlow.Outcome.Failed -> problem = out.message
            }
            lookingUp = null
        }
    }

    fun dismissProblem() { problem = null }

    fun logAgain(entry: Entry, where: String) {
        LogDay.today()
        viewModelScope.launch { LogFlow.logAgain(entry, where) }
    }

    fun delete(entry: Entry) {
        viewModelScope.launch { ServiceLocator.logPipeline.delete(entry) }
    }

    fun setGoal(metric: Metric, on: Boolean) {
        ServiceLocator.debugEvents.event("goal_toggle", "metric" to metric.key, "on" to on, "where" to "home")
        viewModelScope.launch { ServiceLocator.goalsRepository.setEnabled(metric, on) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenUnprocessed: () -> Unit,
    onOpenEntry: (String) -> Unit = {},
    onFixEntry: (String) -> Unit = {},
    onTypeIt: () -> Unit = {},
    onScan: () -> Unit = {},
    onReview: () -> Unit = {},
    onOpenGoals: () -> Unit = {},
    onOpenIdeas: () -> Unit = {},
    /** "What does fiber do for me?": the nutrient, today's amount and its target. */
    onOpenNutrient: (Metric, Double, Double) -> Unit = { _, _, _ -> },
    /** The week-later ask: three questions, then the log. */
    onCheckIn: () -> Unit = {},
    /** The Logged card's "scan its label" for a kind not saved yet. */
    onScanKind: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val entries by viewModel.todayEntries.collectAsState(initial = emptyList())
    val goals by viewModel.goals.collectAsState(initial = emptyList())
    val glance by viewModel.needsGlance.collectAsState(initial = emptyList())
    val unprocessedCount by viewModel.unprocessedCount.collectAsState(initial = 0)
    val choices by viewModel.choices.collectAsState(initial = emptyList())
    val micMode by viewModel.micMode.collectAsState(initial = MicMode.HOLD)
    val homeSort by viewModel.homeSort.collectAsState(initial = com.nutritionscribe.app.data.repository.HomeSort.GOALS)
    val profile by viewModel.profile.collectAsState(initial = null)
    val donations by viewModel.donations.collectAsState(initial = emptySet())
    val plasmaHidden by viewModel.plasmaHidden.collectAsState(initial = null)
    val consentAsk by viewModel.consentAsk.collectAsState(initial = null)
    val logDue by viewModel.logDue.collectAsState(initial = false)
    val ideas by viewModel.ideas.collectAsState(initial = null)
    val context = androidx.compose.ui.platform.LocalContext.current
    val shareScope = rememberCoroutineScope()
    consentAsk?.let { kind -> com.nutritionscribe.app.ui.common.AiConsentDialog(kind) { viewModel.answerConsent(kind, it) } }
    val today = LocalDate.now()
    // What each bar fills toward: the user's own needs once they've said who they
    // are, the label figures until then. A plasma donation puts back the protein
    // it took, today and tomorrow.
    val plasmaExtra = profile?.takeIf { it.plasmaDonor }?.let {
        com.nutritionscribe.shared.model.PlasmaDonation.extraProtein(today, donations, it.weightKg)
    } ?: 0.0
    val references = remember(profile) { com.nutritionscribe.shared.model.Needs.forProfile(profile) }
    val refs = remember(references, plasmaExtra) {
        if (plasmaExtra > 0) references + (Metric.PROTEIN to references.getValue(Metric.PROTEIN) + plasmaExtra) else references
    }

    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) { if (hint != null) { delay(4500); hint = null } }
    val voice = rememberVoiceController { spoken, tag -> viewModel.submit(spoken, "voice", tag, onReview) }
    // Timed from the press, so the log shows talking, waiting and checking apart.
    LaunchedEffect(voice.isActive) { if (voice.isActive) ServiceLocator.debugEvents.startFlow("voice", onlyIfIdle = true) }

    var detail by remember { mutableStateOf<Metric?>(null) }
    val totals = remember(entries) { NutrientDay.totals(entries) }
    val splits = remember(entries, totals) {
        Splits(
            com.nutritionscribe.shared.resolve.Sugars.split(entries),
            com.nutritionscribe.shared.resolve.Fats.split(totals.mapKeys { it.key.key }.mapValues { it.value.amount }),
        )
    }
    val goalOf = goals.associateBy { it.metric }
    val lead = goals.firstOrNull { it.enabled }?.metric ?: Metric.CALORIES

    Scaffold(
        bottomBar = {
            LogDock(
                voice = voice,
                mode = micMode,
                hint = hint,
                busy = viewModel.lookingUp != null,
                onHint = { hint = it },
                onTypeIt = { ServiceLocator.debugEvents.startFlow("type"); ServiceLocator.debugEvents.event("dock_type"); onTypeIt() },
                onScan = { ServiceLocator.debugEvents.startFlow("scan"); ServiceLocator.debugEvents.event("dock_scan"); onScan() },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = androidx.compose.foundation.lazy.rememberLazyListState()
            val homeTaps by HomeTab.taps.collectAsState()
            // Only taps not yet acted on: coming Back to Home keeps its place.
            var seenTaps by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(HomeTab.taps.value) }
            LaunchedEffect(homeTaps) { if (homeTaps > seenTaps) { seenTaps = homeTaps; HomeTab.reset.value++; list.animateScrollToItem(0) } }
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (unprocessedCount > 0) item {
                    Notice(
                        if (unprocessedCount == 1) "1 entry needs attention" else "$unprocessedCount entries need attention",
                        MaterialTheme.colorScheme.errorContainer, onOpenUnprocessed,
                    )
                }
                if (glance.isNotEmpty()) item {
                    Notice(
                        if (glance.size == 1) "1 entry from your watch needs a quick look"
                        else "${glance.size} entries from your watch need a quick look",
                        MaterialTheme.colorScheme.secondaryContainer,
                    ) { onFixEntry(glance.first()) }
                }
                if (profile == null) item {
                    Notice(
                        "Add your height, weight and activity in Goals, so these bars show what you need.",
                        MaterialTheme.colorScheme.secondaryContainer,
                    ) { ServiceLocator.debugEvents.event("profile_nudge_tap"); onOpenGoals() }
                }
                if (logDue) item {
                    LogReminder(
                        onSend = { ServiceLocator.debugEvents.event("check_in_open", "where" to "home_reminder"); onCheckIn() },
                        onLater = viewModel::snoozeLog,
                    )
                }
                if (profile?.plasmaDonor == true && plasmaHidden != today.toString()) item {
                    PlasmaButton(today, donations, profile!!.weightKg, onHide = { viewModel.hidePlasma(today) }) { viewModel.setDonated(today, it) }
                }
                item {
                    NutrientPager(
                        totals, goalOf, refs, plasmaExtra,
                        hasFood = entries.any { it.nutrients.isNotEmpty() }, onOpen = { detail = it },
                        splits = splits, sort = homeSort, onSort = viewModel::setSort,
                    )
                }
                item {
                    IdeasButton(ideas) {
                        ServiceLocator.debugEvents.event("ideas_tap", "headline" to ideas?.headline)
                        onOpenIdeas()
                    }
                }
                item {
                    LogAgainSection(
                        choices = choices,
                        onLog = { viewModel.logAgain(it, "home") },
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Today", Modifier.weight(1f))
                        Text(
                            if (entries.size == 1) "1 entry" else "${entries.size} entries",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                if (entries.isEmpty()) item {
                    Text(
                        "Nothing logged yet today. ${if (micMode == MicMode.HOLD) "Hold" else "Tap"} Say below and tell it what you ate, or scan or type it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    val groups = groupByTime(entries)
                    items(groups, key = { it.first().id }) { group ->
                        TimeGroup(
                            group = group,
                            lead = lead,
                            onOpen = onOpenEntry,
                            onLogAgain = { viewModel.logAgain(it, "home_row") },
                            onFix = onFixEntry,
                            onDelete = { viewModel.delete(it) },
                        )
                    }
                }
            }

            LoggedHost(
                onUndo = { viewModel.delete(it) },
                onFix = { onFixEntry(it.id) },
                onScanKind = { e, base -> KindScan.pending = e to base; onScanKind() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            ListeningOverlay(
                voice = voice,
                mode = micMode,
                lookingUp = viewModel.lookingUp,
                problem = viewModel.problem,
                onDismissProblem = viewModel::dismissProblem,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    detail?.let { m ->
        NutrientSheet(
            metric = m,
            total = totals[m] ?: NutrientTotal(m, 0.0, 0.0),
            goal = goalOf[m],
            reference = refs.getValue(m),
            personal = profile != null,
            extra = if (m == Metric.PROTEIN) plasmaExtra else 0.0,
            entries = entries,
            onToggleGoal = { on -> viewModel.setGoal(m, on) },
            onOpenGoals = { detail = null; onOpenGoals() },
            onLearnMore = { have, target -> detail = null; onOpenNutrient(m, have, target) },
            onDismiss = { detail = null },
        )
    }
}

/**
 * The week-later ask, for someone testing the app: one tap sends the log to
 * whoever gave it to them. "Later" asks again in two days.
 */
@Composable
private fun LogReminder(onSend: () -> Unit, onLater: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("You've been testing for a week", style = MaterialTheme.typography.titleSmall)
            Text(
                "Three quick questions, then send your log to whoever gave you the app. It has what you logged and how the app behaved, so they can fix what went wrong.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = onSend) { Text("Answer and send") }
                TextButton(onClick = onLater) { Text("Later") }
            }
        }
    }
}

/**
 * "Ideas · fiber averaged 13 of 38 g": one button, naming the most useful thing
 * this week, that opens the rest. Quiet until there is something to say.
 */
@Composable
private fun IdeasButton(result: com.nutritionscribe.shared.model.Ideas.Result?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val line = when {
        result == null -> null
        !result.ready -> "after ${result.needed} full days (${result.fullDays} so far)"
        else -> result.headline?.replaceFirstChar { it.lowercase() } ?: "meals and a store list"
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (result?.ready == true && result.headline != null) scheme.secondaryContainer else scheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)
            .semantics { contentDescription = "Ideas from your week" + (line?.let { ": $it" } ?: "") },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ideas", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            line?.let {
                Text(" · $it", style = MaterialTheme.typography.labelLarge, color = scheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Notice(text: String, color: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = SectionLabelStyle, color = MaterialTheme.colorScheme.secondary, modifier = modifier)
}

/**
 * Taps on Home in the bottom bar. Each one puts Home back as it opens: the macros
 * page, at the top. Before, it came back on whatever page was last left open.
 */
object HomeTab {
    val taps = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Taps Home has acted on, for the pager inside it. */
    val reset = kotlinx.coroutines.flow.MutableStateFlow(0)
}

// ---------------------------------------------------------------------------
// The two pages: Macros, then Minerals & vitamins. Goals lead each page.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NutrientPager(
    totals: Map<Metric, NutrientTotal>,
    goals: Map<Metric, MetricGoal>,
    refs: Map<Metric, Double>,
    plasmaExtra: Double,
    hasFood: Boolean,
    onOpen: (Metric) -> Unit,
    splits: Splits,
    sort: com.nutritionscribe.app.data.repository.HomeSort,
    onSort: (com.nutritionscribe.app.data.repository.HomeSort) -> Unit,
) {
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    // Home tapped in the bottom bar: back to the page it opens on.
    val resets by HomeTab.reset.collectAsState()
    var seenResets by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(HomeTab.reset.value) }
    LaunchedEffect(resets) { if (resets > seenResets) { seenResets = resets; pager.animateScrollToPage(0) } }
    LaunchedEffect(pager.currentPage) {
        ServiceLocator.debugEvents.event("home_page", "page" to if (pager.currentPage == 0) "macros" else "micros")
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
            listOf("Macros", "Minerals & vitamins").forEachIndexed { i, label ->
                val on = pager.currentPage == i
                Column(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { scope.launch { pager.animateScrollToPage(i) } }
                        .padding(top = 6.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.secondary,
                    )
                    Box(
                        Modifier.padding(top = 8.dp).height(2.dp).width(if (on) 36.dp else 0.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            SortSwitch(sort, onSort)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            verticalAlignment = Alignment.Top,
        ) { page ->
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val row = RowContext(refs, plasmaExtra, hasFood, onOpen, splits, sort)
                if (page == 0) MacroPage(totals, goals, row) else MicroPage(totals, goals, row)
            }
        }
    }
}

@Composable
private fun MacroPage(totals: Map<Metric, NutrientTotal>, goals: Map<Metric, MetricGoal>, row: RowContext) {
    val mineMetrics = goals.values.filter { it.enabled && !it.metric.isMicro }.sortedBy { it.sortOrder }.map { it.metric }
    val mine = HomeRow.of(mineMetrics)
    val rest = HomeRow.of(Metric.MACROS).filter { it !in mine }
    // One value column for the page, so every bar starts and ends in the same place.
    // Wide enough for the split rows' "12 / ≤50 g".
    val valueWidth = 120.dp
    ShowSections(mine, listOf((if (mine.isEmpty()) "Today" else "Other macros") to rest), totals, goals, row, valueWidth)
}

@Composable
private fun MicroPage(totals: Map<Metric, NutrientTotal>, goals: Map<Metric, MetricGoal>, row: RowContext) {
    val mine = goals.values.filter { it.enabled && it.metric.isMicro }.sortedBy { it.sortOrder }.map { it.metric }
    val valueWidth = if (mine.isEmpty() && row.sort == com.nutritionscribe.app.data.repository.HomeSort.GOALS) 52.dp else 120.dp
    val rest = Metric.MICROS.filter { it !in mine }
    val sections = listOf(
        "Minerals" to rest.filter { it.group == MetricGroup.MINERAL && it.direction == GoalDirection.REACH },
        "Vitamins" to rest.filter { it.group == MetricGroup.VITAMIN && it.direction == GoalDirection.REACH },
        "Stay under" to rest.filter { it.direction == GoalDirection.LIMIT },
    ).map { (label, list) -> label to list.map { HomeRow.One(it) as HomeRow } }
    ShowSections(mine.map { HomeRow.One(it) }, sections, totals, goals, row, valueWidth)
}

/** What every row on a page shares: what bars fill toward, and where a tap goes. */
private class RowContext(
    val refs: Map<Metric, Double>,
    val plasmaExtra: Double,
    val hasFood: Boolean,
    val onOpen: (Metric) -> Unit,
    val splits: Splits,
    val sort: com.nutritionscribe.app.data.repository.HomeSort,
)

/** One row of either page: a plain nutrient, or the split sugar or fat pair. */
@Composable
private fun ShowRow(r: HomeRow, totals: Map<Metric, NutrientTotal>, goals: Map<Metric, MetricGoal>, row: RowContext, valueWidth: androidx.compose.ui.unit.Dp) {
    when (r) {
        is HomeRow.One -> NutrientRow(r.metric, totals[r.metric], goals[r.metric], row, valueWidth)
        HomeRow.SugarPair -> SugarRows(row.splits.sugar, goals, row.refs, valueWidth, row.onOpen)
        HomeRow.FatPair -> FatRows(row.splits.fat, goals, row.refs, valueWidth, row.onOpen)
    }
}

/** Goals then the rest, or one list ordered by how full each bar is. */
@Composable
private fun ShowSections(
    mine: List<HomeRow>,
    rest: List<Pair<String, List<HomeRow>>>,
    totals: Map<Metric, NutrientTotal>,
    goals: Map<Metric, MetricGoal>,
    row: RowContext,
    valueWidth: androidx.compose.ui.unit.Dp,
) {
    if (row.sort != com.nutritionscribe.app.data.repository.HomeSort.GOALS) {
        val all = sortRows(mine + rest.flatMap { it.second }, row.sort) { fullness(it, totals, goals, row.refs, row.splits) }
        SectionLabel(if (row.sort == com.nutritionscribe.app.data.repository.HomeSort.LOWEST) "Most needed first" else "Fullest first")
        all.forEach { ShowRow(it, totals, goals, row, valueWidth) }
        return
    }
    if (mine.isNotEmpty()) {
        SectionLabel("Your goals")
        mine.forEach { ShowRow(it, totals, goals, row, valueWidth) }
    }
    rest.filter { it.second.isNotEmpty() }.forEachIndexed { i, (label, list) ->
        SectionLabel(label, Modifier.padding(top = if (i == 0 && mine.isEmpty()) 0.dp else 8.dp))
        list.forEach { ShowRow(it, totals, goals, row, valueWidth) }
    }
}

/**
 * "Donated plasma today": shown only to someone who said they donate. Tapping it
 * adds back the protein a donation takes (see PlasmaDonation), and it refuses a
 * day the FDA rule would, so a stray tap can't count twice.
 */
@Composable
private fun PlasmaButton(today: LocalDate, donations: Set<LocalDate>, weightKg: Double, onHide: () -> Unit, onSet: (Boolean) -> Unit) {
    val check = com.nutritionscribe.shared.model.PlasmaDonation.check(today, donations)
    val grams = Math.round(com.nutritionscribe.shared.model.PlasmaDonation.proteinLost(weightKg))
    val fmt = DateTimeFormatter.ofPattern("EEEE")
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (check) {
            com.nutritionscribe.shared.model.PlasmaDonation.Check.Allowed -> {
                OutlinedButton(onClick = { onSet(true) }) { Text("I donated plasma today") }
                Text("Adds ${grams / 2} g of protein today and tomorrow", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
            }
            com.nutritionscribe.shared.model.PlasmaDonation.Check.AlreadyToday -> {
                Text("Donated plasma today · +${grams / 2} g protein", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onSet(false) }) { Text("Undo") }
            }
            is com.nutritionscribe.shared.model.PlasmaDonation.Check.TooSoon -> {
                val when_ = if (check.earliest == today.plusDays(1)) "tomorrow" else check.earliest.format(fmt)
                val bump = com.nutritionscribe.shared.model.PlasmaDonation.extraProtein(today, donations, weightKg)
                // One short line: it took a sixth of the screen as two sentences.
                Text(
                    (if (bump > 0) "+${Math.round(bump)} g protein from yesterday's donation · " else "") +
                        "next one $when_ or later",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Closed for today; the protein it adds still counts.
        androidx.compose.material3.IconButton(onClick = onHide, modifier = Modifier.size(32.dp)) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Hide for today",
                tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * One nutrient: name, a thin bar, and the number.
 *
 * A goal row is the user's own target: bold name, "amount / target", at the top
 * of its page. Every other row fills toward what this person needs (see Needs),
 * or a general figure until they've said who they are. All of them are green: red only ever means a line the
 * user set was crossed — a "stay under" goal, or a "red above" number they typed.
 * The app has no business calling 130 g of sugar too much for someone who never
 * said so. (A "red below" line is judged in History, when the day is over; at 9
 * in the morning every floor would be red.) A nutrient too little of today
 * documents is striped with "?", never drawn as a low bar.
 */
@Composable
private fun NutrientRow(
    metric: Metric,
    total: NutrientTotal?,
    goal: MetricGoal?,
    row: RowContext,
    valueWidth: androidx.compose.ui.unit.Dp,
) {
    val hasFood = row.hasFood
    val onOpen = row.onOpen
    val isGoal = goal?.enabled == true
    val amount = total?.amount ?: 0.0
    val extra = if (metric == Metric.PROTEIN) row.plasmaExtra else 0.0
    val target = (if (isGoal) goal?.target?.plus(extra) else null) ?: row.refs.getValue(metric)
    val direction = (if (isGoal) goal?.effectiveDirection else null) ?: metric.direction
    val over = (isGoal && direction == GoalDirection.LIMIT && goal?.target != null && amount > goal.target!!) ||
        (goal?.ceilingValue?.let { amount > it } == true)
    // Nothing eaten yet is an empty bar, not an unknown one: stripes mean food was
    // logged that does not say.
    val unknown = hasFood && metric.isMicro && (total == null || !total.trustworthy)
    val fraction = if (target > 0) (amount / target).toFloat() else 0f
    val scheme = MaterialTheme.colorScheme
    val fill = if (over) scheme.error else scheme.primary
    val value = when {
        unknown -> "?"
        isGoal -> "${formatAmount(amount, metric, withUnit = false)} / ${if (direction == GoalDirection.LIMIT) "≤" else ""}${formatAmount(target, metric, withUnit = metric != Metric.CALORIES)}"
        metric.isMicro -> "${Math.round(fraction * 100)}%"
        else -> formatAmount(amount, metric)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onOpen(metric) }
            .padding(vertical = 4.dp)
            .semantics { contentDescription = "${metric.label}: ${if (unknown) "not enough data today" else value}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            metric.shortLabel,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isGoal) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.width(88.dp),
        )
        HomeBar(fraction, fill, unknown, Modifier.weight(1f))
        Text(
            value,
            style = NumberStyle,
            color = if (over) scheme.error else scheme.onBackground,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(valueWidth),
        )
    }
}

@Composable
internal fun HomeBar(fraction: Float, color: Color, unknown: Boolean, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val stripe = MaterialTheme.colorScheme.outline
    Box(modifier.height(7.dp).clip(RoundedCornerShape(4.dp)).background(track)) {
        if (unknown) {
            Canvas(Modifier.fillMaxSize()) {
                val step = 5.dp.toPx()
                var x = -size.height
                while (x < size.width) {
                    drawLine(stripe.copy(alpha = 0.55f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.6.dp.toPx())
                    x += step
                }
            }
        } else if (fraction > 0f) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0.02f, 1f)).clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}

/** Tapping a nutrient: what today's number is made of, and what is missing from it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NutrientSheet(
    metric: Metric,
    total: NutrientTotal,
    goal: MetricGoal?,
    reference: Double,
    personal: Boolean,
    extra: Double,
    entries: List<Entry>,
    onToggleGoal: (Boolean) -> Unit,
    onOpenGoals: () -> Unit,
    /** Opens the nutrient's page with what the sheet shows: the amount and the target. */
    onLearnMore: ((have: Double, target: Double) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(metric) { ServiceLocator.debugEvents.event("nutrient_open", "metric" to metric.key, "amount" to total.amount, "coverage" to total.coverage) }
    val isGoal = goal?.enabled == true
    val target = (if (isGoal) goal?.target?.plus(extra) else null) ?: reference
    val sources = remember(entries, metric) { NutrientDay.sources(entries, metric).take(6) }
    val missing = remember(entries, metric) { NutrientDay.unknownFor(entries, metric) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(metric.label, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatAmount(total.amount, metric, withUnit = false), style = NumberStyle.copy(fontSize = 26.sp))
                Text(metric.unit, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 3.dp))
                Text(
                    if (isGoal) "of your ${if ((goal?.effectiveDirection ?: metric.direction) == GoalDirection.LIMIT) "limit" else "goal"}, ${formatAmount(target, metric)}"
                    // A limit is not something anyone needs: 178% of the added sugar "you need" read as praise.
                    else if (metric.direction == GoalDirection.LIMIT) "of the ${formatAmount(target, metric)} limit"
                    else if (personal) "of the ${formatAmount(target, metric)} you need"
                    else if (metric == Metric.SUGAR) "total sugar today"
                    else if (metric.isMicro) "of the ${formatAmount(target, metric)} Daily Value"
                    else "today",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (onLearnMore != null) {
                // The sugar limit is a limit on added sugar, so the page measures that.
                val have = if (metric == Metric.SUGAR) com.nutritionscribe.shared.resolve.Sugars.split(entries).let { it.added + it.unknown } else total.amount
                LearnMoreRow(metric) { onLearnMore(have, target) }
            }
            if (metric == Metric.SUGAR) {
                val sp = remember(entries) { com.nutritionscribe.shared.resolve.Sugars.split(entries) }
                Text(
                    "Added ${formatAmount(sp.added, metric)} · natural ${formatAmount(sp.natural, metric)}" +
                        (if (sp.unknown > 0.5) " · not split ${formatAmount(sp.unknown, metric)}" else "") + ". " +
                        "The limit is for added sugar only: what a label lists as \"added sugars\", plus honey, syrup and soda. " +
                        "Fruit and plain milk count as natural." +
                        (if (sp.unknown > 0.5) " Sugar the app can't split is counted as added." else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (metric == Metric.FAT || metric == Metric.SATURATED_FAT) {
                val fp = remember(total, entries) {
                    com.nutritionscribe.shared.resolve.Fats.split(com.nutritionscribe.shared.resolve.sumNutrients(entries.map { it.nutrients }))
                }
                Text(
                    "Saturated + trans ${formatAmount(fp.saturatedAndTrans, Metric.FAT)} · unsaturated ${formatAmount(fp.unsaturated, Metric.FAT)}. " +
                        "Saturated and trans fat are the kinds to keep low; unsaturated fat (nuts, avocado, olive oil, fish) is the kind to have instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (extra > 0) {
                Text(
                    "Includes ${Math.round(extra)} g to replace the protein a plasma donation takes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (!personal && !isGoal) {
                Text(
                    "A general figure. Add your height, weight and activity in Goals to see what you need.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (entries.isNotEmpty() && total.coverage < 0.999) {
                Text(
                    "Known for ${Math.round(total.coverage * 100)}% of today's calories." +
                        if (missing.isNotEmpty()) " Not listed for: ${missing.take(4).joinToString(", ")}${if (missing.size > 4) "…" else ""}." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (sources.isNotEmpty()) {
                SectionLabel("Where it came from")
                sources.forEach { (name, v) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatAmount(v, metric), style = NumberStyle)
                    }
                }
            } else if (entries.isNotEmpty()) {
                Text("Nothing today lists ${metric.label.lowercase()}.", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onToggleGoal(!isGoal); onDismiss() }) {
                    Text(if (isGoal) "Stop tracking as a goal" else "Make this a goal")
                }
                TextButton(onClick = onOpenGoals) { Text("Change the target") }
            }
        }
    }
}

/** "What does fiber do for me? ›", the sheet's way to the nutrient's own page. */
@Composable
private fun LearnMoreRow(metric: Metric, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(
                com.nutritionscribe.shared.model.NutrientGuide.question(metric),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 15.sp),
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

// ---------------------------------------------------------------------------
// Log again: a few one-tap chips, and a small panel for the rest.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogAgainSection(choices: List<LogAgain.Choice>, onLog: (Entry) -> Unit) {
    if (choices.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val chips = remember(choices) { LogAgain.smart(choices, now, limit = 6) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Log again", Modifier.weight(1f))
            TextButton(
                onClick = {
                    expanded = !expanded
                    ServiceLocator.debugEvents.event("log_again_more", "open" to expanded)
                },
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(if (expanded) "Less" else "More", style = MaterialTheme.typography.labelLarge)
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            chips.forEach { c ->
                Surface(
                    shape = RoundedCornerShape(17.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.height(36.dp).clip(RoundedCornerShape(17.dp)).clickable { onLog(c.entry) }
                        .semantics { contentDescription = "Log ${c.entry.name} again" },
                ) {
                    Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text(shortName(c.entry), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
            }
        }
        if (expanded) MoreFoods(choices, onLog)
    }
}

/** "Banana" for "Banana, raw"; "Yogurt + Banana" for a two-food entry, not just its first word. */
private fun shortName(entry: Entry): String {
    val base = if (entry.items.size > 1) entry.items.joinToString(" + ") { it.name.substringBefore(",").trim() }
    else entry.name.substringBefore(",")
    return if (base.length > 28) base.take(26).trimEnd() + "…" else base
}

@Composable
private fun MoreFoods(choices: List<LogAgain.Choice>, onLog: (Entry) -> Unit) {
    var often by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var shown by remember { mutableIntStateOf(6) }
    val list = remember(choices, often, query) {
        val ordered = if (often) LogAgain.often(choices) else LogAgain.recent(choices)
        val q = query.trim().lowercase()
        if (q.isEmpty()) ordered else ordered.filter { q in it.entry.name.lowercase() || q in it.entry.rawText.lowercase() }
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleChip("Recent", !often) { often = false; shown = 6 }
                ToggleChip("You eat often", often) { often = true; shown = 6 }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; shown = 6 },
                placeholder = { Text("Search everything you've logged") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
            if (list.isEmpty()) {
                Text("Nothing you've logged matches that.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(vertical = 8.dp))
            }
            list.take(shown).forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(c.entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                if (often) "${c.count}×" else whenLabel(c.lastAt),
                                c.entry.items.singleOrNull()?.portion,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                        )
                    }
                    c.entry.calories?.let { Text("${Math.round(it)}", style = NumberStyle, color = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { onLog(c.entry) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = "Log ${c.entry.name} now", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (list.size > shown) {
                TextButton(onClick = { shown += 10 }) { Text("Show more") }
            }
        }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(15.dp),
        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.height(32.dp).clip(RoundedCornerShape(15.dp)).clickable(onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/** "Yesterday 8:05 PM", "Wed 12:30 PM", "Sep 2". */
private fun whenLabel(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(millis).atZone(zone)
    val today = LocalDate.now(zone)
    val time = t.format(DateTimeFormatter.ofPattern("h:mm a"))
    return when (val d = t.toLocalDate()) {
        today -> "Today $time"
        today.minusDays(1) -> "Yesterday $time"
        else -> if (d.isAfter(today.minusDays(7))) "${t.format(DateTimeFormatter.ofPattern("EEE"))} $time"
        else t.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

// ---------------------------------------------------------------------------
// Today's list, grouped by the time things were eaten.
// ---------------------------------------------------------------------------

/** Entries logged at the same minute read as one meal. */
private fun groupByTime(entries: List<Entry>): List<List<Entry>> {
    val out = mutableListOf<MutableList<Entry>>()
    for (e in entries) {
        val last = out.lastOrNull()?.last()
        if (last != null && !e.timeUnspecified && !last.timeUnspecified &&
            e.timestampEpochMillis / 60_000 == last.timestampEpochMillis / 60_000) out.last().add(e)
        else out.add(mutableListOf(e))
    }
    return out
}

@Composable
private fun TimeGroup(
    group: List<Entry>,
    lead: Metric,
    onOpen: (String) -> Unit,
    onLogAgain: (Entry) -> Unit,
    onFix: (String) -> Unit,
    onDelete: (Entry) -> Unit,
) {
    val first = group.first()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (first.timeUnspecified) "—" else Instant.ofEpochMilli(first.timestampEpochMillis).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("h:mm")),
            style = NumberStyle.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(38.dp).padding(top = 11.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            group.forEach { e ->
                // A tap is the quick edit; the full breakdown is in the long-press menu.
                EntryRow(e, lead, { onFix(e.id) }, { onLogAgain(e) }, { onOpen(e.id) }, { onDelete(e) })
            }
        }
    }
}

/**
 * One of today's entries. Tap to open it; press and hold for the quick things —
 * above all "Log again", for the second bar that is exactly like the first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: Entry,
    lead: Metric,
    onClick: () -> Unit,
    onLogAgain: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text(entry.name) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    val haptics = LocalHapticFeedback.current
    val sub = when {
        entry.needsConfirm -> "needs a look"
        entry.items.size > 1 -> "${entry.items.size} foods"
        entry.items.singleOrNull()?.origin == "PERSONAL" -> "your usual"
        else -> entry.items.singleOrNull()?.portion
    }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                    onLongClickLabel = "More: log again, details, delete",
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                sub?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (entry.needsConfirm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                }
            }
            Text(
                entry.amountOf(lead)?.let { formatAmount(it, lead, withUnit = lead != Metric.CALORIES) } ?: "–",
                style = NumberStyle.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Log again now") },
                leadingIcon = { Icon(Icons.Filled.Replay, contentDescription = null) },
                onClick = { menu = false; onLogAgain() },
            )
            DropdownMenuItem(
                text = { Text("Details · report a problem") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = { menu = false; onDetails() },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; confirmDelete = true },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The dock: hold (or tap) to say it, or type it, or scan it.
// ---------------------------------------------------------------------------

@Composable
private fun LogDock(
    voice: VoiceController,
    mode: MicMode,
    hint: String?,
    busy: Boolean,
    onHint: (String) -> Unit,
    onTypeIt: () -> Unit,
    onScan: () -> Unit,
) {
    val listening = voice.isActive
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }
            // Three ways to log, a third of the width each, so none is hidden
            // behind another. Scan and Type wait while a lookup is still running:
            // leaving then dropped what had just been said.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MicButton(
                    voice, mode, onHint = onHint,
                    modifier = Modifier.weight(1f).height(56.dp),
                    caption = when {
                        listening && mode == MicMode.HOLD -> "Let go"
                        listening -> "Tap to stop"
                        mode == MicMode.HOLD -> "Hold to say"
                        else -> "Tap to say"
                    },
                )
                DockButton("Scan", Icons.Filled.QrCodeScanner, enabled = !busy && !listening, onClick = onScan, modifier = Modifier.weight(1f))
                DockButton("Type", Icons.Filled.Keyboard, enabled = !busy && !listening, onClick = onTypeIt, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DockButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val content = if (enabled) scheme.onSecondaryContainer else scheme.onSurface.copy(alpha = 0.38f)
    Box(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) scheme.secondaryContainer else scheme.surfaceContainerHighest)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        }
    }
}

/** What is being heard, just above the dock, while the rest of Home stays in view. */
@Composable
private fun ListeningOverlay(
    voice: VoiceController,
    mode: MicMode,
    lookingUp: String?,
    problem: String?,
    onDismissProblem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showVoice = voice.state !is VoiceController.State.Idle
    if (!showVoice && lookingUp == null && problem == null) return
    Surface(
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            when {
                showVoice -> ListeningPanel(voice, mode)
                lookingUp != null -> Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("“${lookingUp.trim()}”", style = MaterialTheme.typography.titleMedium)
                    Text("Looking it up — your own foods first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                problem != null -> Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismissProblem) { Text("OK") }
                }
            }
        }
    }
}
