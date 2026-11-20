package com.nutritionscribe.app.ui.watch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nutritionscribe.app.data.WatchLink
import com.nutritionscribe.app.data.db.toEntry
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.confirm.LogSession
import com.nutritionscribe.app.ui.confirm.WATCH_FIX
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.sync.WatchState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------- P1 · Settings › Watch

/** The watch-face shortcuts, as the slot picker on the watch names them (B2/B3, 11-20). */
internal data class Shortcut(val icon: ImageVector, val name: String, val does: String)

internal val SHORTCUTS = listOf(
    Shortcut(Icons.Outlined.Mic, "Need most + Say", "What you're furthest behind on. Tap: Say, or one tap on a food of yours that closes the gap."),
    Shortcut(Icons.Outlined.Restaurant, "Usual meal now", "Your usual breakfast, lunch or dinner for the time of day. Tap: logs all of it."),
    Shortcut(Icons.AutoMirrored.Outlined.Undo, "Again", "What you usually log around now. Tap: logs another, same amount."),
    Shortcut(Icons.Outlined.Add, "Close the gap", "The one food of yours that covers what's left. Tap: logs it."),
    Shortcut(Icons.Outlined.AccessTime, "Since you logged", "How long since your last entry, a nudge when you forget. Tap: Say."),
    Shortcut(Icons.Outlined.Smartphone, "Waiting on phone", "Only while a watch entry needs a look here."),
    Shortcut(Icons.Outlined.TrackChanges, "Your main goal", "Grams to go, or your goal with a bar. Tap: opens the app."),
    Shortcut(Icons.Outlined.Mic, "Say", "One tap and it's listening."),
)

class WatchSettingsViewModel : ViewModel() {
    private val settings = ServiceLocator.settingsRepository
    val home = settings.watchHome
    val one = settings.watchOne
    val goals = ServiceLocator.goalsRepository.goals.map { gs -> gs.filter { it.enabled && it.target != null }.sortedBy { it.sortOrder } }
    val toCheck = ServiceLocator.database.entryDao().observeNeedsConfirm().map { it.size }

    fun setHome(mode: String) {
        ServiceLocator.debugEvents.event("watch_home_set", "mode" to mode)
        viewModelScope.launch { settings.setWatchHome(mode) }
    }

    fun setOne(key: String) {
        ServiceLocator.debugEvents.event("watch_one_set", "metric" to key)
        viewModelScope.launch { settings.setWatchOne(key) }
    }
}

@Composable
fun WatchSettingsScreen(onOpenQueue: () -> Unit, viewModel: WatchSettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val status by WatchLink.status.collectAsState()
    LaunchedEffect(Unit) { WatchLink.refresh(context.applicationContext) }
    val home by viewModel.home.collectAsState(initial = null)
    val one by viewModel.one.collectAsState(initial = null)
    val goals by viewModel.goals.collectAsState(initial = emptyList())
    val toCheck by viewModel.toCheck.collectAsState(initial = 0)
    val mode = home ?: WatchState.Home.GOALS

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WatchHero(status, toCheck, onOpenQueue)

        Label("Watch home shows")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeTile("Your goals", goals.take(2).joinToString(", ") { it.metric.shortLabel }.ifBlank { "Your top two" },
                mode == WatchState.Home.GOALS, Modifier.weight(1f)) { viewModel.setHome(WatchState.Home.GOALS) }
            HomeTile("Need most", "Furthest behind", mode == WatchState.Home.NEED, Modifier.weight(1f)) { viewModel.setHome(WatchState.Home.NEED) }
            HomeTile("One number", "You pick it", mode == WatchState.Home.ONE, Modifier.weight(1f)) { viewModel.setHome(WatchState.Home.ONE) }
        }
        if (mode == WatchState.Home.ONE && goals.isNotEmpty()) {
            val picked = one ?: goals.first().metric.key
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                goals.take(4).forEach { g ->
                    FilterChip(selected = g.metric.key == picked, onClick = { viewModel.setOne(g.metric.key) },
                        label = { Text(g.metric.shortLabel, maxLines = 1) })
                }
            }
        }

        Label("Watch-face shortcuts")
        Text(
            "Put any of these in a slot on your watch face: on the watch, press and hold the face, tap Customise, " +
                "tap a slot, then Nutrition Scribe.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
        )
        Card {
            SHORTCUTS.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconDot(s.icon)
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(s.does, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }

        Card(Modifier.padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconDot(Icons.Outlined.QrCode2)
                Column(Modifier.weight(1f)) {
                    Text("Say a barcode", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("Say “barcode” and then its numbers, on the watch or here.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun WatchHero(status: WatchLink.Status, toCheck: Int, onOpenQueue: () -> Unit) {
    val green = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(green, green.copy(alpha = 0.82f))))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Watch, null, tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(status.name ?: if (status.paired) "Your watch" else "No watch yet", color = Color.White,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    status.connected -> "Connected"
                    status.paired -> "Not in reach right now"
                    else -> "Install Nutrition Scribe on a Wear OS watch paired with this phone"
                },
                color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodySmall,
            )
        }
        if (toCheck > 0) {
            Surface(onClick = onOpenQueue, shape = RoundedCornerShape(14.dp), color = Color.White,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text("$toCheck to check", color = green, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun HomeTile(title: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, shape = RoundedCornerShape(16.dp),
        color = if (on) scheme.primaryContainer else scheme.surface,
        border = BorderStroke(if (on) 2.dp else 1.dp, if (on) scheme.primary else scheme.outlineVariant),
        modifier = modifier.heightIn(min = 96.dp).semantics { selected = on; contentDescription = "$title: $sub" },
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniWatch(title)
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = scheme.secondary, maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** A tiny watch face showing roughly what the home looks like. Sample shapes, no numbers of theirs. */
@Composable
private fun MiniWatch(kind: String) {
    val ink = Color(0xFFF2F1EC)
    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF151716)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            when (kind) {
                "Your goals" -> { MiniBar(0.58f, Color(0xFF6D9BD1)); MiniBar(0.65f, Color(0xFF4E9873)) }
                "Need most" -> { Text("Fiber", color = ink, style = MaterialTheme.typography.labelSmall); MiniBar(0.3f, Color(0xFF6D9BD1)) }
                else -> Text("107", color = ink, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.size(width = 16.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF4E9873)))
        }
    }
}

@Composable
private fun MiniBar(f: Float, c: Color) {
    Box(Modifier.size(width = 24.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF2C302D))) {
        Box(Modifier.fillMaxSize().padding(end = (24 * (1 - f)).dp).clip(RoundedCornerShape(2.dp)).background(c))
    }
}

// ---------------------------------------------------------------- P3 · From your watch

class WatchQueueViewModel : ViewModel() {
    private val pipeline = ServiceLocator.logPipeline
    val entries = ServiceLocator.database.entryDao().observeNeedsConfirm().map { rows -> rows.map { it.toEntry() } }
    var working by mutableStateOf<String?>(null)
        private set

    /** Nobody checked it on the watch; it looks right: saved and remembered, at the time it was said. */
    fun keep(e: Entry) {
        ServiceLocator.debugEvents.event("watch_queue_keep", "entry_id" to e.id)
        viewModelScope.launch {
            pipeline.confirm(e.rawText, pipeline.toConfirmed(e), replacing = e, how = "watch_check")
            com.nutritionscribe.app.ui.common.AppMessages.say("Saved ${com.nutritionscribe.app.ui.common.logAgainName(e)}")
        }
    }

    /**
     * Work it out with AI (P4): the words said on the watch, worked out here, then the
     * check card. It replaces the watch's entry and keeps its time; nothing is logged
     * until Log.
     */
    fun workOut(e: Entry, onReady: () -> Unit) {
        if (working != null) return
        working = e.id
        ServiceLocator.debugEvents.event("watch_queue_ai", "entry_id" to e.id)
        viewModelScope.launch {
            val tag = java.util.UUID.randomUUID().toString()
            val r = runCatching { pipeline.resolve(e.rawText, tag = tag, how = WATCH_FIX) }.getOrNull()
            working = null
            if (r == null) { com.nutritionscribe.app.ui.common.AppMessages.say("Couldn't work that out. Try Type it."); return@launch }
            LogSession.start(r, tag, WATCH_FIX)
            LogSession.editing = e
            onReady()
        }
    }
}

/** A watch entry the watch couldn't split into foods (it has no numbers yet). */
internal fun isTricky(e: Entry) = e.needsDetail || e.nutrients.isEmpty() || e.items.isEmpty()

@Composable
fun WatchQueueScreen(
    onReview: () -> Unit,
    onFix: (String) -> Unit,
    viewModel: WatchQueueViewModel = viewModel(),
) {
    val entries by viewModel.entries.collectAsState(initial = null)
    val aiOn by ServiceLocator.askShownFlow.collectAsState(initial = ServiceLocator.askShown())
    val list = entries
    if (list != null && list.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            IconDot(Icons.Outlined.Check)
            Text("All checked", style = MaterialTheme.typography.titleMedium)
            Text("Anything the watch can't work out, or said while your phone was away, waits here.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(list.orEmpty(), key = { it.id }) { e ->
            QueueCard(e, aiOn, working = viewModel.working == e.id,
                onAi = { viewModel.workOut(e, onReview) }, onType = { onFix(e.id) }, onKeep = { viewModel.keep(e) })
        }
        item {
            Text("Each one keeps the time you said it on the watch.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun QueueCard(e: Entry, aiOn: Boolean, working: Boolean, onAi: () -> Unit, onType: () -> Unit, onKeep: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tricky = isTricky(e)
    val time = Instant.ofEpochMilli(e.timestampEpochMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a · EEE", Locale.US))
    Card {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (tricky) "TOO TRICKY FOR THE WATCH" else "SAID WHILE YOUR PHONE WAS AWAY",
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    color = if (tricky) scheme.tertiary else scheme.primary,
                    modifier = Modifier.weight(1f).semantics { heading() })
                Text(time, style = MaterialTheme.typography.labelSmall, color = scheme.secondary)
            }
            Text("“${e.rawText}”", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic,
                modifier = Modifier.border(width = 0.dp, color = Color.Transparent).padding(start = 2.dp))
            if (tricky) {
                Text(
                    if (aiOn) "The watch couldn't split this into foods. AI can: it reads your words, lists each food with an amount, " +
                        "and you check the list before anything is logged."
                    else "The watch couldn't split this into foods. Type it out as foods and amounts, or add an AI key in Settings so AI can do it.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.secondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (aiOn) {
                        Button(onClick = onAi, enabled = !working, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp))
                            Text(if (working) "Working it out…" else "Work it out with AI", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(onClick = onType, modifier = Modifier.heightIn(min = 52.dp)) {
                            Icon(Icons.Outlined.Keyboard, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Type it")
                        }
                    } else {
                        Button(onClick = onType, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                            Icon(Icons.Outlined.Keyboard, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Type it")
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 4.dp)) {
                    e.items.forEach { i ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(listOfNotNull(i.name.substringAfter(" — "), i.portion).joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            i.nutrients["calories"]?.let { Text("${Math.round(it)} kcal", style = com.nutritionscribe.app.ui.theme.NumberStyle,
                                color = scheme.secondary) }
                        }
                    }
                    if (e.items.size > 1) {
                        HorizontalDivider(color = scheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Total", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("${Math.round(e.calories ?: 0.0)} kcal", style = com.nutritionscribe.app.ui.theme.NumberStyle, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text("Already counted in today on a best guess. Check it and it's remembered.", style = MaterialTheme.typography.bodySmall,
                    color = scheme.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onKeep, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp))
                        Text("Looks right", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = onType, modifier = Modifier.heightIn(min = 52.dp)) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Change")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- P2 · Home's Watch button

/** By the streak on Home, when a watch is paired or something waits (P2). The count is what waits. */
@Composable
fun WatchButton(toCheck: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, shape = RoundedCornerShape(20.dp), color = scheme.secondaryContainer,
        modifier = Modifier.heightIn(min = 40.dp).semantics(mergeDescendants = true) {
            contentDescription = if (toCheck > 0) "Watch: $toCheck to check" else "Watch"
        },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.Watch, null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
            Text("Watch", style = MaterialTheme.typography.labelLarge, color = scheme.onSecondaryContainer)
            if (toCheck > 0) {
                Box(Modifier.clip(RoundedCornerShape(9.dp)).background(scheme.primary).padding(horizontal = 7.dp, vertical = 1.dp)) {
                    Text("$toCheck", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Home's card when watch entries wait: "2 from your watch to check" (P2). */
@Composable
fun WatchWaitingCard(entries: List<Entry>, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tricky = entries.count(::isTricky)
    val away = entries.size - tricky
    Surface(onClick = onOpen, shape = RoundedCornerShape(16.dp), color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconDot(Icons.Outlined.Watch)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${entries.size} from your watch to check", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        tricky.takeIf { it > 0 }?.let { if (it == 1) "One was too tricky for the watch" else "$it were too tricky for the watch" },
                        away.takeIf { it > 0 }?.let { if (it == 1) "one waited while your phone was away" else "$it waited while your phone was away" },
                    ).joinToString(", ").replaceFirstChar { it.uppercase() } + ".",
                    style = MaterialTheme.typography.bodySmall, color = scheme.secondary,
                )
            }
            Surface(onClick = onOpen, shape = RoundedCornerShape(20.dp), color = scheme.primary, modifier = Modifier.heightIn(min = 40.dp)) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text("Check them", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp)) { content() }
    }
}

@Composable
private fun IconDot(icon: ImageVector) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}
