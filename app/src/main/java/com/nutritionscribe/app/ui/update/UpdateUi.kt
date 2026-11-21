package com.nutritionscribe.app.ui.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutritionscribe.app.data.update.AppUpdater.Status
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.update.AppUpdates

/** The release a status is about, if any. */
private val Status.release: AppUpdates.Release?
    get() = when (this) {
        is Status.Available -> release
        is Status.Downloading -> release
        is Status.NeedsPermission -> release
        is Status.Installing -> release
        is Status.Failed -> release
        else -> null
    }

/** Whether Home shows its update card, so a hidden card leaves no gap. */
fun updateCardShows(status: Status, skipped: String?): Boolean {
    val r = status.release ?: return false
    return !(status is Status.Available && r.version == skipped)
}

private fun mb(bytes: Long) = "%.0f".format(bytes / 1_048_576.0)

/**
 * Shows Android's "Update this app?" box once the installer asks for it, and carries on
 * after "Install unknown apps" is turned on. Put once in the app's scaffold.
 */
@Composable
fun UpdatePrompts() {
    val updater = ServiceLocator.appUpdater
    val confirm by updater.confirm.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(confirm) {
        confirm?.let { runCatching { context.startActivity(it) }; updater.confirmShown() }
    }
}

/**
 * Home's card while a newer version is out (hidden once "Later" is tapped on it, until a
 * newer one). Same card shape as the watch card above it; hiding it closes its gap.
 */
@Composable
fun UpdateCard() {
    val updater = ServiceLocator.appUpdater
    val status by updater.status.collectAsState()
    val skipped by updater.skipped.collectAsState()
    if (!updateCardShows(status, skipped)) return
    val release = status.release ?: return
    var notes by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = scheme.surface, border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(scheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.SystemUpdate, null, tint = scheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(headline(status, release), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(detail(status, release), style = MaterialTheme.typography.bodySmall,
                        color = if (status is Status.Failed) scheme.error else scheme.secondary)
                }
            }
            (status as? Status.Downloading)?.let { d ->
                LinearProgressIndicator(progress = { (d.bytes.toFloat() / release.apkBytes.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Downloaded ${mb(d.bytes)} of ${mb(release.apkBytes)} MB" })
            }
            if (status is Status.Installing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            UpdateButtons(status, release, onNotes = { notes = true }, onLater = { updater.later(release) })
        }
    }
    if (notes) WhatsNewSheet(release, onDismiss = { notes = false })
}

private fun headline(s: Status, r: AppUpdates.Release) = when (s) {
    is Status.Downloading -> "Downloading ${r.version}"
    is Status.Installing -> "Installing ${r.version}"
    is Status.NeedsPermission -> "One switch to update"
    is Status.Failed -> "${r.version} didn't install"
    else -> "Nutrition Scribe ${r.version} is out"
}

private fun detail(s: Status, r: AppUpdates.Release) = when (s) {
    is Status.Downloading -> "${mb(s.bytes)} of ${mb(r.apkBytes)} MB from GitHub."
    is Status.Installing -> "Android closes the app to update it; open it again after. Your log stays."
    is Status.NeedsPermission -> "Android asks once: turn on \"Allow from this source\" for Nutrition Scribe, then come back."
    is Status.Failed -> s.message
    else -> "${mb(r.apkBytes)} MB from GitHub. Your log, goals and foods stay as they are."
}

@Composable
private fun UpdateButtons(status: Status, release: AppUpdates.Release, onNotes: () -> Unit, onLater: (() -> Unit)?) {
    val updater = ServiceLocator.appUpdater
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (release.notes.isNotBlank()) TextButton(onClick = onNotes, modifier = Modifier.heightIn(min = 48.dp)) { Text("What's new") }
        when (status) {
            is Status.Available -> {
                if (onLater != null) TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) { Text("Later") }
                Button(onClick = { updater.update(release) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Update") }
            }
            is Status.NeedsPermission -> Button(onClick = {
                ServiceLocator.debugEvents.event("update_permission_open")
                runCatching { context.startActivity(updater.permissionIntent()) }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open settings") }
            is Status.Failed -> Button(onClick = { updater.update(release) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
            else -> Unit
        }
    }
}

/** The release's notes, as plain lines, with Update at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(release: AppUpdates.Release, onDismiss: () -> Unit) {
    val updater = ServiceLocator.appUpdater
    val status by updater.status.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("What's new in ${release.version}", style = MaterialTheme.typography.titleLarge)
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppUpdates.noteLines(release.notes, max = 40).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            if (status is Status.Available) Button(onClick = { onDismiss(); updater.update(release) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(bottom = 4.dp)) { Text("Update to ${release.version}") }
            else TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Close") }
        }
    }
}

/**
 * Settings › Updates: what's installed, when it last looked, Check now, and the switch
 * for looking by itself. A newer version gets the same buttons as Home's card.
 */
@Composable
fun UpdateSettings(
    row: @Composable (icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: (() -> Unit)?, trailing: (@Composable () -> Unit)?) -> Unit,
    divider: @Composable () -> Unit,
) {
    val updater = ServiceLocator.appUpdater
    val status by updater.status.collectAsState()
    val auto by updater.auto.collectAsState()
    var notes by remember { mutableStateOf(false) }
    val release = status.release
    val sub = when (val s = status) {
        is Status.Checking -> "Looking on GitHub…"
        is Status.UpToDate -> "You're on ${updater.currentVersion}, the newest. Checked just now."
        is Status.Failed -> if (s.release == null) s.message else "${s.release.version}: ${s.message}"
        is Status.Downloading, is Status.Installing, is Status.NeedsPermission, is Status.Available -> "${release!!.version} is out. You're on ${updater.currentVersion}."
        Status.Idle -> "You're on ${updater.currentVersion}." + (updater.lastCheckAt?.let { " Last checked ${ago(it)}." } ?: "")
    }
    row(Icons.Outlined.SystemUpdate, if (release != null) "Update to ${release.version}" else "Check for updates", sub,
        { if (release != null) notes = true else updater.check(byHand = true) }, null)
    if (release != null && status !is Status.Available) {
        Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp)) {
            (status as? Status.Downloading)?.let { d ->
                LinearProgressIndicator(progress = { (d.bytes.toFloat() / release.apkBytes.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            UpdateButtons(status, release, onNotes = { notes = true }, onLater = null)
        }
    }
    divider()
    row(Icons.Outlined.Autorenew, "Check by itself", "Looks on GitHub when you open the app, twice a day at most. Nothing installs until you tap Update.",
        { updater.setAuto(!auto) }) { androidx.compose.material3.Switch(checked = auto, onCheckedChange = { updater.setAuto(it) }) }
    if (notes && release != null) WhatsNewSheet(release, onDismiss = { notes = false })
}

private fun ago(at: Long): String {
    val m = ((System.currentTimeMillis() - at).coerceAtLeast(0)) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / (24 * 60)} days ago"
    }
}
