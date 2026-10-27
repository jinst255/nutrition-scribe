package com.nutritionscribe.app.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutritionscribe.app.data.chat.ChatEngine
import com.nutritionscribe.app.data.chat.ChatExtra
import com.nutritionscribe.app.data.chat.ChatMessageRow
import com.nutritionscribe.app.data.chat.ChatRow
import com.nutritionscribe.app.data.repository.MicMode
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.history.formatAmount
import com.nutritionscribe.app.ui.speech.rememberVoiceController
import com.nutritionscribe.app.ui.theme.NumberStyle
import com.nutritionscribe.shared.model.Metric
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Which chat Ask opens on: the one last used, until New chat. Survives leaving Ask, not a restart. */
object AskState {
    val chatId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** A question typed on Home's bar, sent as Ask opens. */
    var pending: String? = null
}

private val SUGGESTIONS = listOf("What should I make for lunch?", "What am I short on today?", "A snack with 20 g protein")

/**
 * Ask, full screen (Chat B, the owner's pick): opened from the bar on Home, back
 * to Home with ⌄, a swipe down from the top, or Back. The chat stays for when
 * they come back, and ☰ holds the others. Picking an idea saves a plan; it never
 * logs. Home asks after the meal what they actually had.
 */
@Composable
fun AskScreen(onClose: () -> Unit) {
    val dao = ServiceLocator.database.chatDao()
    val scope = rememberCoroutineScope()
    val chatId by AskState.chatId.collectAsState()
    val chats by dao.observeChats().collectAsState(initial = emptyList())
    val messages by remember(chatId) { chatId?.let { dao.observeMessages(it) } ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    var busy by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val askConsent by ServiceLocator.settingsRepository.askConsent.collectAsState(initial = true)
    var consentFor by remember { mutableStateOf<String?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val title = chats.firstOrNull { it.id == chatId }?.title ?: "Ask"

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        if (!askConsent) { consentFor = t; return }
        draft = ""
        busy = true
        scope.launch {
            val out = ChatEngine.send(chatId, t)
            AskState.chatId.value = when (out) {
                is ChatEngine.Outcome.Answered -> out.chatId
                is ChatEngine.Outcome.Failed -> out.chatId
            }
            busy = false
        }
    }
    LaunchedEffect(Unit) {
        ServiceLocator.debugEvents.event("ask_open", "chat" to chatId)
        AskState.pending?.let { AskState.pending = null; send(it) }
    }
    androidx.activity.compose.BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    consentFor?.let { q ->
        AskConsentDialog(
            onAgree = {
                consentFor = null
                scope.launch { ServiceLocator.settingsRepository.setAskConsent(true) }
                ServiceLocator.debugEvents.always("ask_consent", "agreed" to true)
                draft = ""; busy = true
                scope.launch {
                    val out = ChatEngine.send(chatId, q)
                    AskState.chatId.value = when (out) { is ChatEngine.Outcome.Answered -> out.chatId; is ChatEngine.Outcome.Failed -> out.chatId }
                    busy = false
                }
            },
            onDismiss = { consentFor = null; draft = q },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ChatsDrawer(
                chats = chats,
                current = chatId,
                onNew = { AskState.chatId.value = null; scope.launch { drawer.close() } },
                onOpen = { AskState.chatId.value = it; scope.launch { drawer.close() } },
                onDelete = { id ->
                    scope.launch { dao.deleteMessages(id); dao.deleteChat(id); if (AskState.chatId.value == id) AskState.chatId.value = null }
                },
            )
        },
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
            // The handle and the top bar: a swipe down here goes back to Home.
            Column(
                Modifier.fillMaxWidth().pointerInput(Unit) {
                    var dragged = 0f
                    detectVerticalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = { if (dragged > 120) onClose() }) { _, dy -> dragged += dy }
                },
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline))
                }
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, contentDescription = "Chats") }
                    Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp), fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Back to Home") }
                }
            }
            val list = rememberLazyListState()
            LaunchedEffect(messages.size, busy) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size + if (busy) 1 else 0) }
            if (messages.isEmpty() && !busy) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)) {
                    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("What can I help with?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(ServiceLocator.askBlocked() ?: "Swipe down to go back to Home. This chat stays here when you come back.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                    SUGGESTIONS.forEach { q ->
                        Surface(
                            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { send(q) },
                        ) { Text(q, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            } else {
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(messages, key = { it.id }) { m -> Message(m) }
                    if (busy) item(key = "busy") {
                        Column(Modifier.fillMaxWidth(0.6f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Thinking…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Composer(draft, { draft = it }, busy, placeholder = if (messages.isEmpty()) "Ask what to eat, or about your day" else "Reply…", onSend = { send(draft) })
        }
    }
}

@Composable
private fun Composer(text: String, onText: (String) -> Unit, busy: Boolean, placeholder: String, onSend: () -> Unit) {
    val voice = rememberVoiceController { spoken, _ -> onText((text + " " + spoken).trim()) }
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                placeholder = { Text(placeholder) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                trailingIcon = {
                    IconButton(onClick = { if (voice.isActive) voice.stop("ask_tap") else voice.start(MicMode.TAP) }) {
                        Icon(if (voice.isActive) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = if (voice.isActive) "Stop" else "Say it")
                    }
                },
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(if (text.isBlank() || busy) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primary)
                    .clickable(enabled = text.isNotBlank() && !busy, onClickLabel = "Send", onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send",
                    tint = if (text.isBlank() || busy) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun Message(m: ChatMessageRow) {
    val scheme = MaterialTheme.colorScheme
    when (m.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), color = scheme.secondaryContainer, modifier = Modifier.widthIn(max = 300.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        "error" -> Text(m.text, style = MaterialTheme.typography.bodyMedium, color = scheme.error, modifier = Modifier.padding(horizontal = 4.dp))
        else -> {
            val extra = ChatEngine.extraOf(m)
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp), color = scheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant), modifier = Modifier.widthIn(max = 330.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (m.text.isNotBlank()) Text(m.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp))
                    extra?.let { Extra(it) }
                }
            }
        }
    }
}

@Composable
private fun Extra(e: ChatExtra) {
    val scheme = MaterialTheme.colorScheme
    if (e.ideas.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            e.ideas.forEachIndexed { i, idea ->
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("${i + 1}. ${idea.name}") }
                    idea.nutrients["calories"]?.let { append(" · ${Math.round(it)} kcal") }
                }, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp))
            }
        }
    }
    if (e.bars.isNotEmpty() && e.barsFor != null) {
        Text("Where the ${e.barsFor.replaceFirstChar { it.lowercase() }} puts you today:", style = MaterialTheme.typography.bodySmall, color = scheme.secondary)
        e.bars.forEach { b ->
            val m = Metric.fromKey(b.metric) ?: return@forEach
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.shortLabel, style = MaterialTheme.typography.bodySmall, color = scheme.secondary, modifier = Modifier.width(70.dp), maxLines = 1)
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(scheme.surfaceContainerHighest)) {
                    val after = (b.after / b.target).toFloat().coerceIn(0f, 1f)
                    val now = (b.now / b.target).toFloat().coerceIn(0f, 1f)
                    // What the idea adds, as a lighter part after today's.
                    Box(Modifier.fillMaxHeight().fillMaxWidth(after).clip(RoundedCornerShape(3.dp)).background(scheme.primary.copy(alpha = 0.45f)))
                    Box(Modifier.fillMaxHeight().fillMaxWidth(now).clip(RoundedCornerShape(3.dp)).background(scheme.primary))
                }
                Text(
                    "${formatAmount(b.now, m, withUnit = false)} → ${formatAmount(b.after, m, withUnit = false)}${if (m != Metric.CALORIES) " / " + formatAmount(b.target, m) else ""}",
                    style = NumberStyle.copy(fontSize = 11.5.sp), color = scheme.primary, maxLines = 1,
                )
            }
        }
    }
    e.planSaved?.let {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Schedule, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
            Text(it, style = MaterialTheme.typography.labelLarge, color = scheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** ☰: every chat, newest first, with search and New chat, grouped by day like the Claude app. */
@Composable
private fun ChatsDrawer(chats: List<ChatRow>, current: String?, onNew: () -> Unit, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(q) {
        matches = if (q.isBlank()) null else ServiceLocator.database.chatDao().chatsMatching(q.trim()).toSet()
    }
    var confirm by remember { mutableStateOf<ChatRow?>(null) }
    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete this chat?") },
            text = { Text("“${c.title}” and its messages. Plans it saved stay on Home.") },
            confirmButton = { TextButton(onClick = { onDelete(c.id); confirm = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true, placeholder = { Text("Search chats") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (q.isNotEmpty()) ({ IconButton(onClick = { q = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") } }) else null,
                shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onNew).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("New chat", style = MaterialTheme.typography.titleSmall)
            }
            val zone = ZoneId.systemDefault()
            val today = java.time.LocalDate.now(zone)
            val shown = chats.filter { matches == null || it.id in matches!! || it.title.contains(q, true) }
            val groups = shown.groupBy {
                val d = Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate()
                when {
                    d == today -> "Today"
                    d == today.minusDays(1) -> "Yesterday"
                    d.isAfter(today.minusDays(7)) -> "This week"
                    else -> d.format(DateTimeFormatter.ofPattern("MMMM"))
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                if (shown.isEmpty()) item {
                    Text(if (q.isBlank()) "No chats yet." else "No chat mentions “$q”.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(12.dp))
                }
                groups.forEach { (label, list) ->
                    item(key = "h_$label") {
                        Text(label.uppercase(), style = com.nutritionscribe.app.ui.theme.SectionLabelStyle, color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
                    }
                    items(list, key = { it.id }) { c ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (c.id == current) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { onOpen(c.id) }.padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { confirm = c }) { Icon(Icons.Filled.Close, contentDescription = "Delete ${c.title}", modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.secondary) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The first time someone uses Ask: what it sends and to whom. The provider's own
 * promise is in the per-provider consent they already gave; this says what Ask adds.
 */
@Composable
private fun AskConsentDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    val kind by ServiceLocator.settingsRepository.backend.collectAsState(initial = com.nutritionscribe.app.data.repository.AiBackendKind.OPENAI)
    val who = if (kind == com.nutritionscribe.app.data.repository.AiBackendKind.OPENAI) "OpenAI" else "Anthropic"
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
        title = { Text("What Ask sends") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Each message goes to $who with the rest of the chat and, so the answer fits your day:")
                Text("• what you've logged today, and today's totals against your goals\n• the foods you log most often\n• About you, if you filled it in (sex, age, weight, activity, aim)")
                Text("Never your name, your API key or past days' logs. It's charged to your key: with the default model, usually under a tenth of a cent a message.",
                    color = MaterialTheme.colorScheme.secondary)
                // The same provider statements as AI privacy (AiConsent), checked against their policies.
                val policy = com.nutritionscribe.app.data.repository.AiConsent.provider(kind)
                Text("${policy.training} ${policy.retention}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("OK, send it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
