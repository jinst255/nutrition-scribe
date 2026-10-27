package com.nutritionscribe.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutritionscribe.app.data.resolve.LogPipeline
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.theme.NumberStyle
import com.nutritionscribe.shared.model.Entry
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.resolve.FoodCandidate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** A kind being added from the Logged card: the entry to change, and the kind it is now. */
object KindScan {
    var pending: Pair<Entry, FoodCandidate>? = null
}

/** How long the card stays up untouched. */
private const val SHOWN_MS = 8000L

/** After it is swiped away, how long "Undo the log" stays. */
private const val UNDO_MS = 4000L

/**
 * "Logged · your usual": what just went in without a card, with Undo and Fix on
 * every log, and the food's kind when it comes in several. It used to be a dark
 * snackbar that sat over Home for ten seconds and could not be moved, so the row
 * behind it could not be tapped. Now it is swiped sideways or down (or ×) and is
 * gone at once; a small "Undo the log" stays a few seconds more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoggedHost(
    onUndo: (Entry) -> Unit,
    onFix: (Entry) -> Unit,
    /** "+ A kind I haven't had" → scan its label: the entry, and the kind it is now. */
    onScanKind: (Entry, FoodCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val events = ServiceLocator.debugEvents
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<LastLogged.Note?>(null) }
    var cleared by remember { mutableStateOf<Entry?>(null) }
    // Collected rather than keyed: clearing the value would otherwise restart the
    // effect and cancel the card before anyone saw it.
    LaunchedEffect(Unit) {
        LastLogged.entry.collect { n ->
            if (n == null) return@collect
            LastLogged.entry.value = null
            cleared = null
            note = n
        }
    }
    LaunchedEffect(cleared) { if (cleared != null) { delay(UNDO_MS); cleared = null } }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val n = note
        AnimatedVisibility(
            visible = n != null,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = fadeOut(tween(120)),
        ) {
            if (n != null) {
                LoggedCard(
                    note = n,
                    onGone = { swiped, entry ->
                        note = null
                        if (swiped) {
                            events.event("logged_card_cleared", "entry_id" to entry.id)
                            cleared = entry
                        }
                    },
                    onUndo = { e ->
                        events.event(if (n.how == "again") "log_again_undo" else "logged_undo", "entry_id" to e.id)
                        note = null
                        onUndo(e)
                    },
                    onFix = { e -> events.event("logged_fix", "entry_id" to e.id); note = null; onFix(e) },
                    onScanKind = { e, base -> note = null; onScanKind(e, base) },
                )
            }
        }
        cleared?.let { e ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 2.dp,
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Row(
                    Modifier.heightIn(min = 40.dp).clickable {
                        events.event("logged_undo", "entry_id" to e.id, "from" to "cleared")
                        cleared = null
                        onUndo(e)
                    }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Cleared", style = MaterialTheme.typography.bodyMedium)
                    Text("Undo the log", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LoggedCard(
    note: LastLogged.Note,
    onGone: (swiped: Boolean, Entry) -> Unit,
    onUndo: (Entry) -> Unit,
    onFix: (Entry) -> Unit,
    onScanKind: (Entry, FoodCandidate) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var entry by remember(note) { mutableStateOf(note.entry) }
    var kinds by remember(note) { mutableStateOf<LogPipeline.KindChoice?>(null) }
    LaunchedEffect(note) { kinds = ServiceLocator.logPipeline.kindsFor(note.entry) }
    var picking by remember(note) { mutableStateOf(false) }

    // Time left, paused while a finger is on it or the kinds are open.
    var held by remember { mutableStateOf(false) }
    var left by remember(note) { mutableFloatStateOf(1f) }
    LaunchedEffect(note, held, picking) {
        while (left > 0f) {
            delay(50)
            if (!held && !picking) left -= 50f / SHOWN_MS
        }
        onGone(false, entry)
    }

    val dx = remember(note) { Animatable(0f) }
    val dy = remember(note) { Animatable(0f) }
    val away = with(density) { 110.dp.toPx() }
    val down = with(density) { 56.dp.toPx() }

    fun swap(c: FoodCandidate) {
        scope.launch {
            entry = ServiceLocator.logPipeline.swapKind(entry, c, "logged_card")
            kinds = ServiceLocator.logPipeline.kindsFor(entry)
            left = 1f
        }
    }

    val k = kinds
    val chips = k != null && (k.ask == com.nutritionscribe.shared.resolve.PersonalFood.ASK_ALWAYS ||
        (k.ask == com.nutritionscribe.shared.resolve.PersonalFood.ASK_WHEN_DIFFERENT && k.differ))
    val button = k != null && !chips && k.ask != com.nutritionscribe.shared.resolve.PersonalFood.ASK_NEVER

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .offset { IntOffset(dx.value.roundToInt(), dy.value.roundToInt()) }
            .graphicsLayer {
                rotationZ = dx.value / away * 2.5f
                alpha = (1f - maxOf(abs(dx.value) / (away * 2.2f), dy.value / (down * 2.5f))).coerceIn(0.2f, 1f)
            }
            .pointerInput(note) {
                detectDragGestures(
                    onDragStart = { held = true },
                    onDragEnd = {
                        held = false
                        scope.launch {
                            if (abs(dx.value) > away || dy.value > down) {
                                if (dy.value > down && abs(dx.value) <= away) dy.animateTo(dy.value + down * 3, tween(140))
                                else dx.animateTo(if (dx.value > 0) away * 4 else -away * 4, tween(160))
                                onGone(true, entry)
                            } else {
                                launch { dx.animateTo(0f) }
                                dy.animateTo(0f)
                            }
                        }
                    },
                    onDragCancel = { held = false; scope.launch { dx.animateTo(0f); dy.animateTo(0f) } },
                ) { change, drag ->
                    change.consume()
                    scope.launch {
                        dx.snapTo(dx.value + drag.x)
                        dy.snapTo((dy.value + drag.y).coerceAtLeast(0f))
                    }
                }
            }
            .semantics { contentDescription = "Logged ${entry.name}. Swipe to clear." },
    ) {
        Column {
            Column(
                Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (note.how == "again") "Logged again · from Log again" else "Logged · ${note.how}",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            letterSpacing = 0.3.sp,
                        )
                        Text(entry.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(meta(entry), style = NumberStyle.copy(fontSize = 12.5.sp), color = MaterialTheme.colorScheme.secondary)
                    }
                    IconButton(onClick = { onGone(true, entry) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                    }
                }
                // One row whatever it holds: with kinds that differ, the kinds sit beside
                // Undo and Fix (scrolling sideways), so the card never grows.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallAction("Undo", Icons.AutoMirrored.Filled.Undo) { onUndo(entry) }
                    SmallAction("Fix", Icons.Outlined.Edit) { onFix(entry) }
                    if (button && k != null) {
                        SmallAction(k.now, Icons.Filled.ArrowDropDown, tonal = true, trailing = true) {
                            ServiceLocator.debugEvents.event("kind_open", "where" to "logged_card", "kinds" to k.mine.map { it.label })
                            picking = true
                        }
                    }
                    if (chips && k != null) {
                        Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        k.mine.forEach { o ->
                            KindChip(o.label, selected = o.label == k.now) { if (o.label != k.now) swap(o.candidate) }
                        }
                        KindChip("+ Other", selected = false, dashed = true) { picking = true }
                    }
                }
            }
            // How long it stays up.
            Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Box(Modifier.fillMaxWidth(left.coerceIn(0f, 1f)).height(3.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
    }

    if (picking && k != null) {
        KindSheet(
            choice = k,
            onPick = { c -> picking = false; swap(c) },
            onScan = { picking = false; k.mine.firstOrNull()?.candidate?.let { onScanKind(entry, it) } },
            onDismiss = { picking = false },
        )
    }
}

private fun meta(e: Entry): String = listOfNotNull(
    e.calories?.let { "${Math.round(it)} kcal" },
    e.nutrients[Metric.PROTEIN.key]?.let { "${com.nutritionscribe.app.ui.history.formatAmount(it, Metric.PROTEIN)} protein" },
).joinToString(" · ")

@Composable
private fun SmallAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tonal: Boolean = false,
    trailing: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (tonal) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        border = if (tonal) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.heightIn(min = 36.dp).padding(start = if (trailing) 14.dp else 12.dp, end = if (trailing) 8.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val tint = if (tonal) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
            if (!trailing) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(label, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            if (trailing) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun KindChip(label: String, selected: Boolean, dashed: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, if (dashed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(15.dp))
            Text(label, fontSize = 13.5.sp, maxLines = 1,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else if (dashed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * "Which kind was it?": just the kinds they buy, with their numbers, and under
 * "A kind I haven't had" the label pack's others and a scan of the label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KindSheet(
    choice: LogPipeline.KindChoice,
    onPick: (FoodCandidate) -> Unit,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    var more by remember { mutableStateOf(choice.mine.size < 2) }
    com.nutritionscribe.app.ui.feedback.ReportableSheet("Logged card", onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Which kind was it?", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (choice.mine.size >= 2) "Your ${choice.mine.size} kinds of ${short(choice.food)}, from what you've logged and scanned."
                else "${short(choice.food)} comes in other kinds.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(4.dp))
            choice.mine.forEach { o -> KindRow(o, selected = o.label == choice.now) { onPick(o.candidate) } }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (!more) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { more = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("+ A kind I haven't had", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                }
            } else {
                if (choice.more.isNotEmpty()) {
                    SectionLabel("Other kinds on the label list", Modifier.padding(top = 8.dp))
                    choice.more.forEach { o -> KindRow(o, selected = false) { onPick(o.candidate) } }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onScan),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Not listed? Scan its label", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text("About 10 seconds, once. After that it's one of your kinds.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            val other = (choice.mine.drop(1) + choice.more).firstOrNull()?.label?.lowercase()
            if (other != null) Text(
                "Next time you can also just say it: \"a $other ${(choice.brand ?: short(choice.food)).lowercase()}\".",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun KindRow(o: LogPipeline.KindOption, selected: Boolean, onClick: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(22.dp)) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Logged", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Text(o.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(
            listOfNotNull(
                o.nutrients[Metric.CALORIES.key]?.let { "${Math.round(it)} kcal" },
                o.nutrients[Metric.PROTEIN.key]?.let { "${Math.round(it)} g" },
            ).joinToString(" · "),
            style = NumberStyle.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/** "Chobani — 20g Protein Greek Yogurt, Vanilla" → the product without its kind. */
private fun short(name: String): String {
    val noKind = name.substringBeforeLast(", ")
    return if (noKind.length > 42) noKind.take(40) + "…" else noKind
}
