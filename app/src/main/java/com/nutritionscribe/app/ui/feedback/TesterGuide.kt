package com.nutritionscribe.app.ui.feedback

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.home.SectionLabel
import com.nutritionscribe.app.ui.theme.NumberStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A bordered card, as the Beta testing section draws them. */
@Composable
internal fun TesterCard(color: Color? = null, border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = color ?: MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, border ?: MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/** An icon in a soft circle, a bold line and a line of detail. */
@Composable
internal fun PointRow(icon: ImageVector, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** The three things that can leave the phone, said the same in setup and in the guide. */
@Composable
internal fun LeavesYourPhone() {
    TesterCard {
        PointRow(Icons.Outlined.AutoAwesome, "What you eat",
            "With an AI key, what you say or type goes to OpenAI to work out the food. Without a key it stays here.")
        PointRow(Icons.AutoMirrored.Outlined.ListAlt, "Your test log",
            "What you log and how the app behaved, plus label photos you scan. It stays on this phone until you send it on day 7, " +
                "and you see what's in it first.")
        PointRow(Icons.Outlined.Person, "Your name", "Only on the notes and logs you send.")
    }
}

/**
 * "You're all set": each part of setup and whether it worked, so when the owner
 * asks what broke, a tester can say setup wasn't it. Read live, so the guide shows
 * the same list as it stands now.
 */
@Composable
fun SetupSummary() {
    val settings = ServiceLocator.settingsRepository
    val context = LocalContext.current
    val testing = remember { isTestBuild(context) }
    val name by settings.testerName.collectAsState(initial = "")
    val quality by settings.highestQuality.collectAsState(initial = false)
    val debug by settings.debugMode.collectAsState(initial = true)
    val pack by ServiceLocator.labelPack.status.collectAsState()
    val hasKey = remember(quality) { settings.hasAnyApiKey() }
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val mic = granted(Manifest.permission.RECORD_AUDIO)
    val cam = granted(Manifest.permission.CAMERA)
    val packOk = pack is com.nutritionscribe.app.data.resolve.LabelPack.Status.Installed
    TesterCard {
        Column {
            if (testing) CheckRow(name.isNotBlank(), "Testing as ${name.ifBlank { "…" }}", "On every note and log you send", first = true)
            CheckRow(hasKey && quality, "AI key",
                when { hasKey && quality -> "Saved · Highest quality mode is on"; hasKey -> "Saved · Highest quality mode is off"; else -> "None · foods come from the built-in list" },
                first = !testing)
            CheckRow(true, "Foods and package labels",
                if (packOk) "13,400 foods · 230,000 package labels, on this phone" else "13,400 foods · package labels still setting up")
            if (testing) CheckRow(debug, "Test log", if (debug) "Recording · you send it on day 7" else "Off · switch it on in Settings › Advanced")
            CheckRow(mic && cam, "Microphone and camera",
                when { mic && cam -> "Both allowed"; mic -> "Microphone allowed · camera asked when you scan"
                    cam -> "Camera allowed · microphone asked when you talk"; else -> "Asked the first time you use them" })
        }
    }
}

@Composable
private fun CheckRow(ok: Boolean, title: String, status: String, first: Boolean = false) {
    Column {
        if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(32.dp).background(
                if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(if (ok) Icons.Outlined.Check else Icons.Outlined.Remove, contentDescription = if (ok) "Done" else "Not yet",
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

/** "Nutrition Scribe 1.6-test · set up Oct 27, 8:14 PM". */
@Composable
internal fun SetupFooter(setupAt: Long?) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val at = setupAt?.let { " · set up " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(it)) }.orEmpty()
    Text("Nutrition Scribe $version$at", style = NumberStyle.copy(fontSize = 12.5.sp), color = MaterialTheme.colorScheme.secondary)
}

/**
 * Everything a tester was told at setup, to read again: their week, what to do,
 * how to report, what they'll be asked on day 7, and the one mistake that loses
 * data (uninstalling before an update).
 */
@Composable
fun TesterGuideScreen(onLogContents: () -> Unit) {
    val settings = ServiceLocator.settingsRepository
    val installed by settings.installedAt.collectAsState(initial = null)
    val sentAt by settings.logSentAt.collectAsState(initial = null)
    val setupAt by settings.setupDoneAt.collectAsState(initial = null)
    val from = maxOf(installed ?: System.currentTimeMillis(), sentAt ?: 0L)
    val day = ((System.currentTimeMillis() - from) / 86_400_000L + 1).toInt().coerceIn(1, 7)
    val ends = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(from + 6L * 86_400_000L))
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TesterCard {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Day $day of 7", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Ends $ends", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(7) { i ->
                    Box(Modifier.weight(1f).height(8.dp).background(
                        if (i < day) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(4.dp)))
                }
            }
        }
        SectionLabel("What to do")
        TesterCard {
            Bullet("Log everything you eat, and drinks like soda, juice or coffee. Plain water doesn't need logging.")
            Bullet("Say it the way you'd say it. Rough is fine: \"a handful of almonds\" works.")
            Bullet("Use it like a real app. If something annoys you, that's exactly what I want to hear.")
        }
        SectionLabel("How to tell me")
        TesterCard {
            PointRow(Icons.Outlined.TouchApp, "Hold anything for a second",
                "Then tap a reason. The note and a screenshot stay on your phone. Nothing reaches me until you send your log.")
            PointRow(Icons.Outlined.ChatBubbleOutline, "Or use Beta testing in Settings", "Something's wrong, or an idea.")
        }
        SectionLabel("Your setup")
        SetupSummary()
        SetupFooter(setupAt)
        SectionLabel("On day 7 you'll be asked")
        TesterCard {
            Numbered(1, "What bothered you most?")
            Numbered(2, "Would you keep using it, and why?")
            Numbered(3, "The app stays free. Is it good enough that you'd pay a little, so you don't need your own AI key?")
            Text("Notice these as you go. Short answers are perfect.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
        }
        SectionLabel("When I send a new version")
        TesterCard(color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f), border = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Download, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Tap the file, then Update", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Never uninstall first. That erases your log and your foods.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        SectionLabel("What leaves your phone")
        LeavesYourPhone()
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onLogContents).heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ListAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text("What's in your log", fontSize = 16.sp)
                    Text("See it, and untick what you'd rather keep", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            }
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
            Text("The numbers are estimates, not medical advice. Ask a doctor about anything health-related.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 8.dp).size(5.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Numbered(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$n", style = NumberStyle, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
