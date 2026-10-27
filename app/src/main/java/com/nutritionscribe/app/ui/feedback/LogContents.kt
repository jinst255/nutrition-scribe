package com.nutritionscribe.app.ui.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.home.SectionLabel
import com.nutritionscribe.app.ui.theme.NumberStyle
import kotlinx.coroutines.launch

/**
 * The parts of the debug log a tester may leave out. Everything else (what was
 * logged, how the app behaved, notes and their screenshots, version and phone)
 * always goes: without it the log can't explain anything.
 */
enum class LogPart(val key: String, val events: Set<String>) {
    ABOUT("about", setOf("profile_saved")),
    ACTIVITY("activity", setOf("profile_saved")),
    PLASMA("plasma", setOf("plasma_donation", "plasma_hidden")),
    PHOTOS("photos", emptySet());

    companion object {
        /** Event types to keep out of events.jsonl for these left-out parts. */
        fun droppedEvents(leaveOut: Set<String>): Set<String> =
            entries.filter { it.key in leaveOut }.flatMap { it.events }.toSet()

        /** An events.jsonl line of one of [types]. Lines are written as compact JSON, so the type reads `"type":"x"`. */
        fun isDropped(line: String, types: Set<String>): Boolean = types.any { line.contains("\"type\":\"$it\"") }
    }
}

/**
 * What's in your log, before it goes: the parts that always go, and the
 * personal ones, ticked unless the tester unticks them. Each shows its real
 * value, so nobody has to guess what "About you" means.
 */
@Composable
fun LogContentsScreen(where: String) {
    val settings = ServiceLocator.settingsRepository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val leaveOut by settings.logLeaveOut.collectAsState(initial = emptySet())
    val profile by settings.profile.collectAsState(initial = null)
    val plasma by settings.plasmaDonations.collectAsState(initial = emptySet())
    val notes by settings.betaNotes.collectAsState(initial = emptyList())
    val photos = remember { ServiceLocator.debugEvents.labelPhotoCount() }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    fun toggle(p: LogPart, on: Boolean) {
        ServiceLocator.debugEvents.event("log_part", "part" to p.key, "on" to on)
        scope.launch { settings.setLogLeaveOut(if (on) leaveOut - p.key else leaveOut + p.key) }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Everything below goes to me when you send. Untick anything you'd rather keep. It changes nothing about the test.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        SectionLabel("Always in it")
        TesterCard {
            Column {
                LockRow("What you logged, and when", "Foods, amounts, edits", first = true)
                LockRow("How the app behaved", "Screens, errors, how long things took")
                LockRow("Your notes, with their screenshots", if (notes.size == 1) "1 note" else "${notes.size} notes")
                LockRow("App version and phone", "Nutrition Scribe $version · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            }
        }
        SectionLabel("Yours to choose")
        TesterCard {
            Column {
                val p = profile
                OptRow(LogPart.ABOUT, leaveOut, "About you",
                    p?.let { pr ->
                        listOfNotNull("Age ${pr.ageYears}", "${Math.round(pr.heightCm)} cm", "${Math.round(pr.weightKg)} kg",
                            pr.stage.takeIf { it != com.nutritionscribe.shared.model.Profile.LifeStage.NONE }?.label).joinToString(" · ")
                    } ?: "Not filled in",
                    "So I can check your targets came out right.", first = true, onToggle = ::toggle)
                OptRow(LogPart.ACTIVITY, leaveOut, "Activity and aim",
                    p?.let { "${it.activity.label} · ${it.aim.label}" } ?: "Not filled in", "Same reason.", onToggle = ::toggle)
                OptRow(LogPart.PLASMA, leaveOut, "Plasma donations",
                    if (plasma.isEmpty()) "None" else if (plasma.size == 1) "1 day" else "${plasma.size} days", "Only matters if you donate.", onToggle = ::toggle)
                OptRow(LogPart.PHOTOS, leaveOut, "Label photos you scanned",
                    if (photos == 1) "1 photo" else "$photos photos", "Shows me what the scanner misread.", onToggle = ::toggle)
            }
        }
        Text("Your API key is never included.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Button(
            onClick = { scope.launch { com.nutritionscribe.app.ui.debuglog.LogShare.share(context, where) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 6.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Send my log", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun LockRow(title: String, sub: String, first: Boolean = false) {
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Lock, contentDescription = "Always included", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun OptRow(part: LogPart, leaveOut: Set<String>, title: String, value: String, why: String, first: Boolean = false,
                   onToggle: (LogPart, Boolean) -> Unit) {
    val on = part.key !in leaveOut
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Row(
            Modifier.fillMaxWidth().toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle(part, it) }).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(top = 2.dp, end = 4.dp))
            Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(value, style = NumberStyle.copy(fontSize = 12.5.sp))
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}
