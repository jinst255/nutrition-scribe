package com.nutritionscribe.app.ui.feedback

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutritionscribe.app.data.repository.BetaNote
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.home.SectionLabel
import com.nutritionscribe.app.ui.theme.NumberStyle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a tester came to say: something wrong, an idea, or the week's three questions. */
enum class FeedbackKind { PROBLEM, IDEA, CHECK_IN }

/**
 * Feedback for someone testing the app, in as few taps as possible. Friends don't
 * write essays; they'll say "it was kind of broken". A tap on what bothered them
 * says more than an empty text box ever got.
 *
 * Everything goes into the debug log (as "feedback" and "beta_checkin" events), so
 * it travels with the log that shows what actually happened, and is listed under
 * What I've sent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(kind: FeedbackKind = FeedbackKind.PROBLEM, onDone: () -> Unit = {}) {
    val events = ServiceLocator.debugEvents
    val settings = ServiceLocator.settingsRepository
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val tags = remember { mutableStateListOf<String>() }
    var note by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf<String?>(null) }
    var why by remember { mutableStateOf("") }
    var pay by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (kind) {
            FeedbackKind.PROBLEM -> {
                Text("Tap anything that bothered you. A word or two is plenty; it goes with your next log.",
                    style = MaterialTheme.typography.bodyLarge)
                SectionLabel("What bothered you?")
                Chips(listOf(
                    "Wrong numbers", "Logging took too long", "Couldn't find a food", "Voice misheard me",
                    "Scanning didn't work", "Confusing screen", "Froze or crashed", "Too many questions",
                    "Nothing, it was fine", "I liked something",
                ), selected = tags, onTap = { t -> if (t in tags) tags -= t else tags += t })
                OutlinedTextField(
                    value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text("Anything else? (optional)") },
                    placeholder = { Text("e.g. the sandwich came out at 2,500 calories") },
                )
                Text("What you logged today goes with it, so we can see what happened.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            FeedbackKind.IDEA -> {
                Text("What would make it better? Big or small, it all helps.", style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                    label = { Text("Your idea") },
                    placeholder = { Text("e.g. let me log a recipe once and reuse it") },
                )
            }
            FeedbackKind.CHECK_IN -> {
                Text("Three questions, under a minute. Short answers are perfect.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
                Question(1, "What bothered you most?")
                Chips(listOf("Wrong amounts", "Too many taps", "Scanning", "Slow", "Nothing"), selected = tags,
                    onTap = { t -> tags.clear(); tags += t })
                Question(2, "Would you keep using it?")
                Chips(listOf("Yes", "Maybe", "No"), selected = listOfNotNull(keep), onTap = { keep = it; if (it != "Yes") pay = null })
                OutlinedTextField(
                    value = why, onValueChange = { why = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Why? (optional)") },
                    placeholder = { Text(if (keep == "No") "e.g. too much effort to log" else "e.g. it's faster than typing everything") },
                )
                // The app is meant to be free; whether anyone would pay is only worth
                // asking someone who would keep it.
                if (keep == "Yes") {
                    Question(3, "Would you pay anything for it?")
                    Chips(listOf("No, only if free", "\$1–3 once", "\$5–10 once", "\$1–2 a month", "More than that"),
                        selected = listOfNotNull(pay), onTap = { pay = it })
                }
            }
        }

        val hasAnything = tags.isNotEmpty() || note.isNotBlank() || keep != null || why.isNotBlank()
        Button(
            enabled = hasAnything,
            onClick = {
                val now = System.currentTimeMillis()
                // Taken now: the boxes are cleared below, before the save has run.
                val tagsNow = tags.toList()
                val noteNow = note.trim()
                when (kind) {
                    FeedbackKind.CHECK_IN -> {
                        events.always("beta_checkin", "bothered" to tagsNow.firstOrNull(), "keep" to keep, "why" to why.trim(), "pay" to pay)
                        val checkin = BetaNote(now, "checkin", listOfNotNull(tagsNow.firstOrNull(), keep?.let { "Keep using: $it" }), why.trim())
                        scope.launch {
                            settings.addBetaNote(checkin)
                            com.nutritionscribe.app.ui.debuglog.LogShare.share(context, "check_in")
                        }
                    }
                    else -> {
                        val k = if (kind == FeedbackKind.IDEA) "idea" else if (tagsNow == listOf("I liked something")) "liked" else "problem"
                        events.always("feedback", "kind" to k, "tags" to tagsNow, "note" to noteNow, "where" to "beta")
                        scope.launch { settings.addBetaNote(BetaNote(now, k, tagsNow, noteNow)) }
                    }
                }
                tags.clear(); note = ""
                saved = if (kind == FeedbackKind.CHECK_IN) "Thanks! Choose where to send your log." else "Thanks, it's saved. It goes with your next log."
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (kind == FeedbackKind.CHECK_IN) "Send answers and my log" else "Save") }
        saved?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
        Text(
            if (kind == FeedbackKind.CHECK_IN) "Nothing leaves the phone until you tap Send. Your API key is never included."
            else "Saved notes go with your log on day 7, or tap Send my log now in Settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun Question(n: Int, text: String) {
    Text("$n. $text", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: List<String>, selected: List<String>, onTap: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o -> FilterChip(selected = o in selected, onClick = { onTap(o) }, label = { Text(o) }) }
    }
}

/** Everything they've told us, and whether it has gone out yet (with a sent log). */
@Composable
fun SentScreen() {
    val settings = ServiceLocator.settingsRepository
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val notes by settings.betaNotes.collectAsState(initial = emptyList())
    val sentAt by settings.logSentAt.collectAsState(initial = null)
    val fmt = remember { SimpleDateFormat("EEE h:mm a", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Everything you've told us, and whether it has gone out yet.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary)
        if (notes.isEmpty()) {
            Text("Nothing yet. Notes you save from Tell us and I have an idea show up here.", modifier = Modifier.padding(vertical = 16.dp))
        }
        notes.forEach { n ->
            val gone = sentAt?.let { it >= n.at } == true
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(fmt.format(Date(n.at)), style = NumberStyle.copy(fontSize = 12.5.sp), color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f))
                    Text(if (gone) "Sent ${SimpleDateFormat("EEE", Locale.getDefault()).format(Date(sentAt!!))}" else "Goes with your next log",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = if (gone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                }
                Text(
                    listOfNotNull(
                        n.who.takeIf { it.isNotBlank() }?.let { "From $it" },
                        when (n.kind) { "idea" -> "Idea"; "checkin" -> "Week check-in"; "liked" -> "I liked something"; else -> n.tags.joinToString(", ").ifBlank { "Something's wrong" } },
                        n.text.takeIf { it.isNotBlank() }?.let { "\"$it\"" },
                    ).joinToString(" · "),
                    fontSize = 15.sp, lineHeight = 21.sp,
                )
            }
        }
        OutlinedButton(onClick = { scope.launch { com.nutritionscribe.app.ui.debuglog.LogShare.share(context, "sent_screen") } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp)) { Text("Send my log now") }
        Text("Your log has what you ate and how the app behaved. It never includes your API key. Nothing leaves the phone until you tap Send.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

/**
 * Beta testing, at the top of Settings while the app is in testing: the test week,
 * then the four things a tester comes to Settings for.
 */
@Composable
fun BetaSection(onTellUs: () -> Unit, onIdea: () -> Unit, onSent: () -> Unit, onCheckIn: () -> Unit) {
    val settings = ServiceLocator.settingsRepository
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by settings.installedAt.collectAsState(initial = null)
    val sentAt by settings.logSentAt.collectAsState(initial = null)
    val notes by settings.betaNotes.collectAsState(initial = emptyList())
    val from = maxOf(installed ?: System.currentTimeMillis(), sentAt ?: 0L)
    val day = ((System.currentTimeMillis() - from) / 86_400_000L + 1).toInt().coerceIn(1, 7)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Your test week", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("day $day of 7", style = NumberStyle, color = MaterialTheme.colorScheme.secondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(7) { i ->
                        Box(Modifier.weight(1f).height(6.dp).background(
                            if (i < day) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(3.dp)))
                    }
                }
                if (day >= 7) {
                    Button(onClick = onCheckIn, modifier = Modifier.fillMaxWidth()) { Text("Three quick questions, then send") }
                } else {
                    Text("On day 7: three quick questions, then you send your log.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
            val name by settings.testerName.collectAsState(initial = "")
            var renaming by remember { mutableStateOf(false) }
            BetaRow(Icons.Outlined.Person, if (name.isBlank()) "Who's testing? Add your name" else "Testing as $name", trail = "Change") { renaming = true }
            if (renaming) {
                var typed by remember { mutableStateOf(name) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { renaming = false },
                    title = { Text("Your first name") },
                    text = {
                        androidx.compose.material3.OutlinedTextField(value = typed, onValueChange = { typed = it.take(40) }, singleLine = true,
                            supportingText = { Text("It goes on the notes and logs you send.") })
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(enabled = typed.isNotBlank(), onClick = {
                            renaming = false
                            scope.launch { settings.setTesterName(typed.trim()) }
                        }) { Text("Save") }
                    },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { renaming = false }) { Text("Cancel") } },
                )
            }
            BetaRow(Icons.Outlined.ChatBubbleOutline, "Something's wrong? Tell us", onClick = onTellUs)
            BetaRow(Icons.Outlined.Lightbulb, "I have an idea", onClick = onIdea)
            BetaRow(Icons.Outlined.ListAlt, "What I've sent", trail = if (notes.isEmpty()) null else if (notes.size == 1) "1 note" else "${notes.size} notes", onClick = onSent)
            BetaRow(Icons.AutoMirrored.Outlined.Send, "Send my log now") {
                scope.launch { com.nutritionscribe.app.ui.debuglog.LogShare.share(context, "settings_beta") }
            }
        }
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.padding(top = 8.dp)) {
        Text(
            buildTip(), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

private fun buildTip() = androidx.compose.ui.text.buildAnnotatedString {
    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)); append("Tip: "); pop()
    append("press and hold anything that looks wrong for a second, then tap what it was.")
}

@Composable
private fun BetaRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, trail: String? = null, onClick: () -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f))
            trail?.let { Text(it, style = NumberStyle, color = MaterialTheme.colorScheme.secondary) }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** A test build ("1.4-test"): the Beta testing section shows. */
fun isTestBuild(context: android.content.Context): Boolean = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty().contains("test")
}.getOrDefault(false)
