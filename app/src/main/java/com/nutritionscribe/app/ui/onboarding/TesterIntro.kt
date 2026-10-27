package com.nutritionscribe.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nutritionscribe.app.data.repository.AiBackendKind
import com.nutritionscribe.app.data.repository.LookupSource
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.network.openai.KeyCheck
import com.nutritionscribe.app.ui.feedback.HoldToFeedback
import com.nutritionscribe.app.ui.feedback.LeavesYourPhone
import com.nutritionscribe.app.ui.feedback.PointRow
import com.nutritionscribe.app.ui.feedback.SetupFooter
import com.nutritionscribe.app.ui.feedback.SetupSummary
import com.nutritionscribe.app.ui.feedback.TesterCard
import kotlinx.coroutines.launch

/**
 * A setup page laid out like the name step: icon, big title, a line, then the
 * page's own content, with the buttons kept at the bottom.
 */
@Composable
internal fun SetupPage(
    icon: ImageVector,
    title: String,
    text: String?,
    page: Int? = null,
    pages: Int = 4,
    buttons: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 24.dp).padding(top = 40.dp, bottom = 20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp)
            text?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary) }
            content()
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = buttons)
        if (page != null) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                repeat(pages) { i ->
                    Box(Modifier.height(6.dp).width(if (i == page) 18.dp else 6.dp).background(
                        if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}

/**
 * Test versions only, before setup: the week in three lines, a practice hold so
 * the report gesture is learned by doing it, and what leaves the phone. Testers
 * get the app in a text message with no explanation; this is the explanation.
 */
@Composable
fun TesterIntro(onDone: () -> Unit) {
    val name by ServiceLocator.settingsRepository.testerName.collectAsState(initial = "")
    val events = ServiceLocator.debugEvents
    var page by remember { mutableIntStateOf(0) }
    fun go(to: Int) { events.event("tester_intro", "page" to to); page = to }
    when (page) {
        0 -> SetupPage(Icons.Outlined.CalendarMonth, if (name.isBlank()) "Thanks for testing" else "Thanks for testing, ${name.trim()}",
            "Here's your week. Logging takes about a minute a meal.", page = 0,
            buttons = { Button(onClick = { go(1) }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Next") } }) {
            TesterCard {
                PointRow(Icons.Outlined.CalendarMonth, "Log everything you eat for 7 days",
                    "Snacks too, and drinks like soda or coffee (not plain water). Rough is fine: \"a handful of almonds\" works.")
                PointRow(Icons.Outlined.TouchApp, "Hold anything that looks wrong",
                    "Press a number, a food or a screen for a second and say what bugged you. That helps the most.")
                PointRow(Icons.AutoMirrored.Outlined.Send, "On day 7, send your log",
                    "Three quick questions, then your log comes to me. You see what's in it first.")
            }
            Text("It's a test version, so expect rough edges. The numbers are estimates, not medical advice.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        1 -> SetupPage(Icons.Outlined.TouchApp, "Try it: hold the card",
            "Press and hold the card below for a second. This is how you report anything in the app.", page = 1,
            buttons = { TextButton(onClick = { events.event("hold_practice_skipped"); go(2) }, modifier = Modifier.fillMaxWidth()) { Text("Skip") } }) {
            HoldToFeedback(screen = { "Practice" }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), practice = { go(2) }) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text("Banana", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("105 kcal", style = com.nutritionscribe.app.ui.theme.NumberStyle)
                        }
                        Text("1 medium · 8:10 AM · Protein 1 g · Fibre 3 g", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            Text("Practice only. Nothing is saved or sent.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        2 -> SetupPage(Icons.Outlined.Check, "That's all it takes",
            "Hold works on every screen: a number, a food, a button, a whole page. Notes stay on your phone and go with your log on day 7.",
            page = 2, buttons = { Button(onClick = { go(3) }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Next") } }) {
            TesterCard {
                PointRow(Icons.AutoMirrored.Outlined.Send, "No hold? Settings › Beta testing", "Something's wrong? Tell us, or I have an idea.")
            }
        }
        else -> SetupPage(Icons.Outlined.Lock, "What leaves your phone", null, page = 3,
            buttons = { Button(onClick = { events.event("tester_intro_done"); onDone() }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Got it") } }) {
            LeavesYourPhone()
            Text("You can switch the test log off in Settings › Advanced.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private sealed interface KeyState {
    data object Idle : KeyState
    data object Checking : KeyState
    data class Done(val result: KeyCheck.Result) : KeyState
}

/**
 * Ready to go: where the key goes in. Testers are sent a key in their message,
 * so it says so, offers Paste (nobody types 160 characters), and checks the key
 * with a free call before going on.
 */
@Composable
internal fun ReadyToGo(keptKey: Boolean, onStart: (withKey: Boolean) -> Unit) {
    val settings = ServiceLocator.settingsRepository
    val events = ServiceLocator.debugEvents
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var key by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<KeyState>(KeyState.Idle) }
    var help by remember { mutableStateOf(false) }
    val clean = key.filterNot { it.isWhitespace() }

    fun check() {
        state = KeyState.Checking
        scope.launch {
            val r = KeyCheck.check(clean)
            events.event("key_check", "result" to r.toString())
            state = KeyState.Done(r)
        }
    }
    fun useKey() {
        settings.setApiKey(AiBackendKind.OPENAI, clean)
        scope.launch { settings.setManualMode(false); settings.setLookupSource(LookupSource.AI) }
        onStart(true)
    }
    fun withoutKey() {
        events.event("key_skipped")
        scope.launch { settings.setManualMode(false); settings.setLookupSource(LookupSource.OFFLINE) }
        onStart(false)
    }

    val result = (state as? KeyState.Done)?.result
    SetupPage(Icons.Outlined.Key, "Ready to go",
        "Say or type what you ate. The first time you log a food you check it once; after that it logs without asking.",
        buttons = {
            if (keptKey) {
                Button(onClick = {
                    scope.launch { settings.setManualMode(false); settings.setLookupSource(LookupSource.AI) }
                    onStart(true)
                }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Next") }
            } else when (result) {
                KeyCheck.Result.Works -> Button(onClick = ::useKey, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Next") }
                KeyCheck.Result.Refused -> {
                    FilledTonalButton(onClick = ::check, enabled = clean.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Try again") }
                    TextButton(onClick = ::withoutKey, modifier = Modifier.fillMaxWidth()) { Text("Start without a key") }
                }
                KeyCheck.Result.NoCredit, is KeyCheck.Result.Unreachable -> {
                    FilledTonalButton(onClick = ::useKey, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Save it anyway") }
                    TextButton(onClick = ::withoutKey, modifier = Modifier.fillMaxWidth()) { Text("Start without a key") }
                }
                null -> {
                    Button(onClick = ::check, enabled = clean.isNotEmpty() && state != KeyState.Checking,
                        modifier = Modifier.fillMaxWidth().height(54.dp)) {
                        if (state == KeyState.Checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text("Use this key")
                    }
                    TextButton(onClick = ::withoutKey, modifier = Modifier.fillMaxWidth()) { Text("I don't have a key") }
                }
            }
        }) {
        if (keptKey) {
            TesterCard {
                Text("Your API key is still saved", style = MaterialTheme.typography.titleSmall)
                Text("Highest quality mode is on: AI checks foods you haven't logged before and turns your speech into text. " +
                    "You can switch it off or remove the key in Settings.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; state = KeyState.Idle },
                label = { Text("Your AI key") },
                placeholder = { Text("sk-…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                // A key typed by hand got a space from autocorrect; a key is never words.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                trailingIcon = {
                    when (result) {
                        KeyCheck.Result.Works -> Icon(Icons.Outlined.Check, contentDescription = "Key works", tint = MaterialTheme.colorScheme.primary)
                        else -> if (key.isEmpty()) TextButton(onClick = {
                            val pasted = clipboard.getText()?.text.orEmpty().trim()
                            events.event("key_paste", "looks_like_key" to pasted.startsWith("sk-"))
                            if (pasted.isNotEmpty()) { key = pasted; state = KeyState.Idle }
                        }) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Paste", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            val (line, tone) = when (result) {
                null -> "Got a key from me? It's in my message: press and hold it there, tap Copy, then Paste here." to 0
                KeyCheck.Result.Works -> "Key works. Highest quality mode will be on." to 1
                KeyCheck.Result.Refused -> "That key didn't work. It may have a typo or been turned off. Ask for a new one, or start without it and add it later in Settings." to 2
                KeyCheck.Result.NoCredit -> "That key is real but has no credit left. Ask whoever gave it to you to top it up, or start without it for now." to 2
                is KeyCheck.Result.Unreachable -> "Couldn't reach OpenAI to check it. Are you online? You can save it now; it's tried again when you log." to 2
            }
            Text(line, style = MaterialTheme.typography.bodyMedium, color = when (tone) {
                1 -> MaterialTheme.colorScheme.primary; 2 -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.secondary })
        }

        // The label pack ships inside the APK; it only needs a word while it unpacks.
        val pack = ServiceLocator.labelPack
        val packStatus by pack.status.collectAsState()
        val packPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri -> if (uri != null) scope.launch { pack.installFrom(uri) } }
        when (packStatus) {
            is com.nutritionscribe.app.data.resolve.LabelPack.Status.Installed,
            is com.nutritionscribe.app.data.resolve.LabelPack.Status.Working ->
                Text("230,000 package labels and 13,400 foods are built in, and work offline.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            else -> Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Recommended: the label pack", style = MaterialTheme.typography.titleSmall)
                    Text("Real Nutrition Facts for about 230,000 packaged products, so branded food and barcode scans are exact. " +
                        "About 70 MB. You can add it later in Settings.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (com.nutritionscribe.app.data.resolve.LabelPack.DOWNLOAD_URL != null) {
                            Button(onClick = { scope.launch { pack.download() } }) { Text("Download") }
                        }
                        OutlinedButton(onClick = { packPicker.launch(arrayOf("*/*")) }) { Text("Install from file") }
                    }
                }
            }
        }

        if (!keptKey) {
            TextButton(onClick = { help = !help }) { Text(if (help) "Hide" else "How do I get my own API key?") }
            if (help) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "1. Go to platform.openai.com and make an account.\n" +
                            "2. Add a payment method. Lookups cost a fraction of a cent each; a few dollars lasts a very long time.\n" +
                            "3. Open the API keys page and create a new secret key.\n" +
                            "4. Copy it and paste it above. It starts with \"sk-\".\n\n" +
                            "You can add this later in Settings; nothing is lost by skipping now.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * The last step of setup: each part with a tick, so when something breaks later
 * a tester can say setup wasn't it. The Tester guide shows the same list.
 */
@Composable
internal fun AllSet(onStart: () -> Unit) {
    val name by ServiceLocator.settingsRepository.testerName.collectAsState(initial = "")
    val testing = remember { com.nutritionscribe.app.ui.feedback.isTestBuild(ServiceLocator.appContext) }
    val now = remember { System.currentTimeMillis() }
    SetupPage(Icons.Outlined.Check, if (name.isBlank()) "You're all set" else "You're all set, ${name.trim()}",
        "Setup worked. If something breaks later, you'll know it wasn't this part.",
        buttons = { Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Start logging") } }) {
        SetupSummary()
        SetupFooter(now)
        if (testing) Text("You can see this again in Settings › Beta testing › Tester guide.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary)
    }
}
