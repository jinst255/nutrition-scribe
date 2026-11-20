package com.nutritionscribe.wear.ui

import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.nutritionscribe.shared.sync.WatchProtocol
import com.nutritionscribe.shared.sync.WatchState
import com.nutritionscribe.wear.data.WearRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The watch app (design approved 11-19; docs/UI_GUIDELINES.md §8). */
class WearMainActivity : ComponentActivity() {
    companion object {
        /** From the tile or the Say slot: open straight into listening. */
        const val EXTRA_SAY = "say"
        /** Debug builds only: fill the screens with sample numbers, for screenshots. */
        const val EXTRA_DEMO = "demo"
    }

    private val sayNow = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = WearRepository(applicationContext)
        if (intent.getBooleanExtra(EXTRA_SAY, false)) sayNow.value++
        val demo = com.nutritionscribe.wear.BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEMO, false)
        setContent { MaterialTheme { WearApp(repository, sayNow.value, demo) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SAY, false)) sayNow.value++
    }
}

private object W {
    val green = Color(0xFF4E9873)
    val onGreen = Color(0xFF0F1110)
    val blue = Color(0xFF6D9BD1)
    val ink = Color(0xFFF2F1EC)
    val dim = Color(0xFFA8A79F)
    val surface = Color(0xFF1D201E)
    val track = Color(0xFF2C302D)
    val okChip = Color(0xFF17301F)
    val mono = FontFamily.Monospace
}

private object R {
    const val HOME = "home"
    const val INTRO = "intro"
    const val LISTEN = "listen"
    const val WORKING = "working"
    const val CHECK = "check"
    const val AGAIN = "again"
    const val TODAY = "today"
    const val NOT_HEARD = "not_heard"
    const val TRICKY = "tricky"
    const val SAVED = "saved"
    const val SENT = "sent"
}

/** What was just said, from Done to Log it. */
private class Said {
    var id by mutableStateOf("")
    var text by mutableStateOf("")
    var at by mutableStateOf(0L)
    var reply by mutableStateOf<WatchProtocol.Reply?>(null)
    var problem by mutableStateOf<String?>(null)
    fun start(text: String, at: Long) { id = java.util.UUID.randomUUID().toString(); this.text = text; this.at = at; reply = null; problem = null }
}

@Composable
private fun WearApp(repo: WearRepository, sayNow: Int, demo: Boolean) {
    val nav = rememberSwipeDismissableNavController()
    val scope = rememberCoroutineScope()
    val said = remember { Said() }
    val introSeen by repo.sayIntroSeen.collectAsState(initial = true)

    LaunchedEffect(Unit) {
        if (demo) repo.cacheState(WatchProtocol.encode(Demo.state))
        repo.flushPending()
    }
    fun home() { nav.popBackStack(R.HOME, inclusive = false) }
    fun say() {
        scope.launch {
            val seen = repo.sayIntroSeen.first()
            nav.navigate(if (seen) R.LISTEN else R.INTRO) { launchSingleTop = true }
        }
    }
    LaunchedEffect(sayNow) { if (sayNow > 0) say() }

    /** Words from the mic or the keyboard: off to the phone, or kept for later. */
    fun send(text: String, at: Long) {
        if (text.isBlank()) { repo.debug("not_heard"); nav.navigate(R.NOT_HEARD) { popUpTo(R.HOME) }; return }
        said.start(text, at)
        if (demo) { said.reply = Demo.reply(said.id, text); nav.navigate(R.CHECK) { popUpTo(R.HOME) }; return }
        scope.launch {
            val ok = repo.request(WatchProtocol.Request(said.id, WatchProtocol.Kind.RESOLVE, text, at))
            if (ok) nav.navigate(R.WORKING) { popUpTo(R.HOME) }
            else { repo.keepForLater(text, at); nav.navigate(R.SAVED) { popUpTo(R.HOME) } }
        }
    }

    Scaffold(timeText = { TimeText() }, modifier = Modifier.background(Color.Black)) {
        SwipeDismissableNavHost(navController = nav, startDestination = R.HOME) {
            composable(R.HOME) { HomeScreen(repo, onSay = ::say, onAgain = { nav.navigate(R.AGAIN) }, onToday = { nav.navigate(R.TODAY) }) }
            composable(R.INTRO) {
                SayIntro(onOk = { scope.launch { repo.setSayIntroSeen(); repo.debug("say_intro_seen") }; nav.navigate(R.LISTEN) { popUpTo(R.HOME) } })
            }
            composable(R.LISTEN) { ListenScreen(repo, onDone = ::send, onCancel = ::home) }
            composable(R.WORKING) {
                WorkingScreen(repo, said,
                    onHeard = { nav.navigate(R.CHECK) { popUpTo(R.HOME) } },
                    onTricky = { nav.navigate(R.TRICKY) { popUpTo(R.HOME) } },
                    onFailed = { nav.navigate(R.NOT_HEARD) { popUpTo(R.HOME) } },
                    onSlow = { nav.navigate(R.SENT) { popUpTo(R.HOME) } },
                    onCancel = ::home)
            }
            composable(R.CHECK) {
                CheckScreen(said, onLog = {
                    val kcal = said.reply?.kcal ?: 0
                    WearRepository.lastLogged.value = WearRepository.Logged(said.id, kcal, System.currentTimeMillis())
                    scope.launch {
                        val r = WatchProtocol.Request(said.id, WatchProtocol.Kind.LOG, said.text, said.at)
                        if (!repo.request(r)) repo.keepForLater(said.text, said.at)
                    }
                    // Straight back home (owner, 11-19), where the Logged chip offers Undo.
                    home()
                })
            }
            composable(R.AGAIN) {
                AgainScreen(repo, onPick = { ids, kcal ->
                    val id = java.util.UUID.randomUUID().toString()
                    WearRepository.lastLogged.value = WearRepository.Logged(id, kcal, System.currentTimeMillis())
                    scope.launch { repo.request(WatchProtocol.Request(id, WatchProtocol.Kind.LOG_AGAIN, at = System.currentTimeMillis(), ids = ids)) }
                    home()
                }, onSay = ::say)
            }
            composable(R.TODAY) { TodayScreen(repo) }
            composable(R.NOT_HEARD) { NotHeardScreen(said.problem, onSay = ::say, onTyped = { send(it, System.currentTimeMillis()) }) }
            composable(R.TRICKY) {
                Notice(Icons.Filled.Smartphone, W.blue, "Sent to your phone", "Too tricky for the watch. Check it on the phone, where AI can help.", onOk = ::home)
            }
            composable(R.SAVED) {
                val waiting by repo.pendingCount.collectAsState(initial = 1)
                Notice(Icons.Filled.Smartphone, W.blue, "Saved on watch", "Goes to your phone when it's back in range.", note = "$waiting waiting", onOk = ::home)
            }
            composable(R.SENT) {
                Notice(Icons.Filled.Check, W.green, "Sent to your phone", "It's taking a while, so the phone will log it when it's done.", onOk = ::home)
            }
        }
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun Pill(text: String, onClick: () -> Unit, icon: ImageVector? = null, primary: Boolean = true, width: Dp = 130.dp, label: String? = null) {
    Chip(
        onClick = onClick,
        label = { Text(text, fontSize = if (primary) 15.sp else 13.sp, fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(if (primary) 20.dp else 18.dp), tint = if (primary) W.onGreen else W.green) } },
        colors = ChipDefaults.chipColors(backgroundColor = if (primary) W.green else W.surface, contentColor = if (primary) W.onGreen else W.ink),
        modifier = Modifier.width(width).height(if (primary) 44.dp else 38.dp).then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
    )
}

@Composable
private fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier.fillMaxWidth(0.8f), height: Dp = 5.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(height / 2)).background(W.track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0.02f, 1f)).clip(RoundedCornerShape(height / 2)).background(color))
    }
}

@Composable
private fun GoalRow(g: WatchState.Goal, modifier: Modifier = Modifier.fillMaxWidth(0.86f)) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            Text(g.name, fontSize = 11.sp, color = W.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("%,d / %s%,d%s".format(Math.round(g.value), if (g.limit) "≤" else "", Math.round(g.target), if (g.unit == "kcal") "" else " ${g.unit}"),
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = W.ink, maxLines = 1, softWrap = false)
        }
        Bar(g.fraction, if (g.limit) W.green else W.blue, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Centered(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // Centred when it fits; scrolls when it doesn't (the 40 mm watch, or large text).
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val h = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())
                .heightIn(min = h).padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            content = content,
        )
    }
}

private fun buzz(context: android.content.Context, ms: Long) {
    runCatching { context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
}

// ---------------------------------------------------------------- screens

/** 1 · Home: your goals, or what you need most; Say; Log again; after logging, Logged · Undo. */
@Composable
private fun HomeScreen(repo: WearRepository, onSay: () -> Unit, onAgain: () -> Unit, onToday: () -> Unit) {
    val state by repo.state.collectAsState(initial = WatchState())
    val showNeed by repo.homeShowsNeed.collectAsState(initial = false)
    val waiting by repo.pendingCount.collectAsState(initial = 0)
    val logged by WearRepository.lastLogged.collectAsState()
    val scope = rememberCoroutineScope()
    var chip by remember { mutableStateOf(false) }
    LaunchedEffect(logged) {
        val l = logged ?: return@LaunchedEffect
        val left = 5_000 - (System.currentTimeMillis() - l.at)
        if (left <= 0) return@LaunchedEffect
        chip = true; delay(left); chip = false
    }
    LaunchedEffect(Unit) { repo.debug("screen", "name" to "home", "mode" to if (showNeed) "need" else "goals") }
    Centered {
        if (chip && logged != null) {
            Row(
                Modifier.clip(RoundedCornerShape(20.dp)).background(W.okChip).padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                    .semantics { contentDescription = "Logged" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Check, null, tint = W.green, modifier = Modifier.size(16.dp))
                Text("Logged", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(W.track).clickable {
                        val l = logged ?: return@clickable
                        chip = false; WearRepository.lastLogged.value = null
                        scope.launch {
                            // The phone's reply carries the new entries' ids; wait a moment for it.
                            val ids = withTimeoutOrNull(4_000) { WearRepository.lastLogged.first { it == null }; l.ids }
                                ?: l.ids
                            repo.request(WatchProtocol.Request(java.util.UUID.randomUUID().toString(), WatchProtocol.Kind.UNDO, at = System.currentTimeMillis(), ids = ids))
                            repo.debug("undo", "ids" to ids.size.toString())
                        }
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text("Undo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        val need = state.need
        Column(
            Modifier.clickable(onClickLabel = "Open today", onClick = onToday).padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showNeed && need != null) {
                Text("MOST BEHIND ›", fontSize = 11.sp, color = W.dim, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(need.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("%,d / %,d %s".format(Math.round(need.value), Math.round(need.target), need.unit), fontSize = 12.sp, fontFamily = W.mono)
                }
                Bar((need.value / need.target).toFloat(), W.blue)
                need.tip?.let { Text(it, fontSize = 11.sp, color = W.dim, textAlign = TextAlign.Center, maxLines = 2) }
            } else if (state.goals.isNotEmpty()) {
                Text("YOUR GOALS ›", fontSize = 11.sp, color = W.dim, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() })
                state.goals.take(2).forEach { GoalRow(it) }
            } else {
                Text("Open Nutrition Scribe on your phone once to see your goals here.", fontSize = 11.sp, color = W.dim, textAlign = TextAlign.Center)
            }
        }
        Pill("Say", onSay, Icons.Filled.Mic, label = "Say what you ate", width = 120.dp)
        Pill("Log again", onAgain, Icons.Filled.Refresh, primary = false, width = 128.dp)
        if (waiting > 0) Text("$waiting waiting for your phone", fontSize = 11.sp, color = W.blue)
    }
}

/** The first Say: how it works, once (owner, 11-19). */
@Composable
private fun SayIntro(onOk: () -> Unit) {
    Centered {
        Icon(Icons.Filled.Mic, null, tint = W.green, modifier = Modifier.size(26.dp))
        Text("How Say works", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Say what you ate and how much, then tap Done.", fontSize = 12.sp, textAlign = TextAlign.Center)
        Text("For a package, say “barcode” and its numbers.", fontSize = 11.sp, color = W.dim, textAlign = TextAlign.Center)
        Pill("Got it", onOk, Icons.Filled.Check)
    }
}

/** 2 · Listening: it keeps listening through pauses until Done. Swipe right cancels. */
@Composable
private fun ListenScreen(repo: WearRepository, onDone: (String, Long) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var heard by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val startedAt = remember { System.currentTimeMillis() }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val spoken = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (spoken.isNullOrBlank()) onCancel() else onDone(spoken, startedAt)
    }
    val recognizer = remember {
        com.nutritionscribe.shared.speech.ContinuousRecognizer(context, object : com.nutritionscribe.shared.speech.ContinuousRecognizer.Listener {
            override fun onText(committed: String, partial: String) { heard = listOf(committed, partial).filter { it.isNotBlank() }.joinToString(" ") }
            override fun onFinished(text: String, stats: com.nutritionscribe.shared.speech.ContinuousRecognizer.Stats) {
                repo.debug("say_done", "ms" to (System.currentTimeMillis() - startedAt).toString(), "chars" to text.length.toString())
                onDone(text, startedAt)
            }
            override fun onFailed(code: Int, message: String) { problem = message; repo.debug("say_failed", "code" to code.toString()) }
        })
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) recognizer.start() else problem = "The microphone is off for this app"
    }
    LaunchedEffect(Unit) {
        repo.debug("say_start")
        buzz(context, 30)
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        when {
            !granted -> permission.launch(android.Manifest.permission.RECORD_AUDIO)
            !recognizer.isAvailable -> system.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
            else -> recognizer.start()
        }
    }
    DisposableEffect(Unit) { onDispose { recognizer.cancel() } }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.4f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "p")
    Centered {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.4f, 0.9f, 0.6f, 1f, 0.5f).forEach { h ->
                Box(Modifier.width(3.dp).height((14 * h * pulse).dp.coerceAtLeast(4.dp)).clip(RoundedCornerShape(2.dp)).background(W.green))
            }
            Text("LISTENING", fontSize = 11.sp, color = W.green, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        }
        Text(heard.ifBlank { "Say what you ate" }, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            color = if (heard.isBlank()) W.dim else W.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Text(problem ?: "Pauses don't stop it", fontSize = 11.sp, color = if (problem != null) W.blue else W.dim, textAlign = TextAlign.Center)
        Pill("Done", { buzz(context, 30); recognizer.stop() }, Icons.Filled.Check)
    }
}

/** 3 · Working it out, while the phone resolves it. */
@Composable
private fun WorkingScreen(
    repo: WearRepository, said: Said,
    onHeard: () -> Unit, onTricky: () -> Unit, onFailed: () -> Unit, onSlow: () -> Unit, onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(said.id) {
        val t0 = System.currentTimeMillis()
        val reply = withTimeoutOrNull(20_000) { WearRepository.replies.first { it.id == said.id } }
        repo.debug("reply", "kind" to (reply?.kind ?: "timeout"), "ms" to (System.currentTimeMillis() - t0).toString(), "lines" to (reply?.lines?.size ?: 0).toString())
        when (reply?.kind) {
            WatchProtocol.Kind.HEARD -> { said.reply = reply; onHeard() }
            WatchProtocol.Kind.TRICKY -> onTricky()
            WatchProtocol.Kind.FAILED -> { said.problem = reply.message; onFailed() }
            else -> {
                // The phone took it but hasn't answered: let it log there, at the time it was said.
                scope.launch { repo.request(WatchProtocol.Request(said.id, WatchProtocol.Kind.LOG, said.text, said.at)) }
                onSlow()
            }
        }
    }
    val x by rememberInfiniteTransition(label = "work").animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "x")
    Centered {
        Text("“${said.text}”", fontSize = 12.sp, color = W.dim, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Working it out…", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.width(110.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(W.track)) {
            Box(Modifier.padding(start = (66 * x).dp).width(44.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(W.green))
        }
        Pill("Cancel", onCancel, primary = false, width = 110.dp)
    }
}

/** 4 · What it heard: the foods, then Log it. Found by barcode says so. */
@Composable
private fun CheckScreen(said: Said, onLog: () -> Unit) {
    val reply = said.reply ?: return
    val list = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
        ScalingLazyColumn(Modifier.fillMaxSize().background(Color.Black), state = list,
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text("“${reply.heard ?: said.text}”", fontSize = 11.sp, color = W.dim, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (reply.barcode) item { Text("FOUND BY BARCODE", fontSize = 11.sp, color = W.green, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp) }
            items(reply.lines) { l ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(W.surface).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(l.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(l.sub, "${l.kcal} kcal").filter { it.isNotBlank() }.joinToString(" · "), fontSize = 11.sp, color = W.dim, maxLines = 1)
                }
            }
            item { Pill("Log it · ${reply.kcal} kcal", onLog, Icons.Filled.Check, width = 150.dp) }
            item { Text(if (reply.barcode) "From its real label" else "Swipe right if it's wrong", fontSize = 11.sp, color = W.dim) }
        }
    }
}

/** 6 · Log again: usual meals first, then single foods; Something else opens Say. */
@Composable
private fun AgainScreen(repo: WearRepository, onPick: (List<String>, Int) -> Unit, onSay: () -> Unit) {
    val state by repo.state.collectAsState(initial = WatchState())
    val list = rememberScalingLazyListState()
    LaunchedEffect(Unit) { repo.debug("screen", "name" to "again", "meals" to state.meals.size.toString(), "foods" to state.again.size.toString()) }
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
        ScalingLazyColumn(Modifier.fillMaxSize().background(Color.Black), state = list,
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text("Log again", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }) }
            items(state.meals) { m ->
                AgainRow(m.label, m.names.joinToString(", ") + " · ${m.kcal} kcal", highlight = true) {
                    repo.debug("again_tap", "kind" to "meal", "foods" to m.ids.size.toString()); onPick(m.ids, m.kcal)
                }
            }
            items(state.again) { a ->
                AgainRow(a.name, a.sub) {
                    repo.debug("again_tap", "kind" to "food")
                    onPick(listOf(a.id), Regex("""(\d+) kcal""").find(a.sub)?.groupValues?.get(1)?.toIntOrNull() ?: 0)
                }
            }
            if (state.again.isEmpty() && state.meals.isEmpty()) item {
                Text("Foods you log show up here.", fontSize = 12.sp, color = W.dim, textAlign = TextAlign.Center)
            }
            item { Pill("Something else", onSay, Icons.Filled.Mic, primary = false, width = 150.dp) }
        }
    }
}

@Composable
private fun AgainRow(title: String, sub: String, highlight: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (highlight) W.okChip else W.surface)
            .clickable(onClickLabel = "Log $title again", onClick = onClick).padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 11.sp, color = W.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.Add, contentDescription = null, tint = W.green, modifier = Modifier.size(20.dp))
    }
}

/** 7 · Today: every goal, the free tip, and what the home shows. */
@Composable
private fun TodayScreen(repo: WearRepository) {
    val state by repo.state.collectAsState(initial = WatchState())
    val showNeed by repo.homeShowsNeed.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val list = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
        ScalingLazyColumn(Modifier.fillMaxSize().background(Color.Black), state = list,
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Today", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }) }
            items(state.goals) { GoalRow(it, Modifier.fillMaxWidth(0.84f)) }
            state.need?.let { n ->
                item {
                    Text(
                        listOfNotNull("%,d %s %s to go.".format(Math.round(n.left), n.unit, n.name.lowercase()), n.tip).joinToString(" "),
                        fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(W.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            item {
                Pill(if (showNeed) "Home: most behind" else "Home: your goals", {
                    scope.launch { repo.setHomeShowsNeed(!showNeed); repo.debug("home_mode", "need" to (!showNeed).toString()) }
                }, primary = false, width = 160.dp)
            }
        }
    }
}

/** 9 · Didn't catch that: say it again, or type it. */
@Composable
private fun NotHeardScreen(problem: String?, onSay: () -> Unit, onTyped: (String) -> Unit) {
    val typed = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val results = r.data?.let { android.app.RemoteInput.getResultsFromIntent(it) }
        results?.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() }?.let(onTyped)
    }
    Centered {
        Text(if (problem != null) "Couldn't work that out" else "Didn't catch that", fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(problem ?: "Too quiet or too short. Say it again, or type it.", fontSize = 12.sp, color = W.dim, textAlign = TextAlign.Center)
        Pill("Say again", onSay, Icons.Filled.Mic)
        Pill("Type", {
            val intent = androidx.wear.input.RemoteInputIntentHelper.createActionRemoteInputIntent()
            androidx.wear.input.RemoteInputIntentHelper.putRemoteInputsExtra(intent,
                listOf(android.app.RemoteInput.Builder("text").setLabel("What did you eat?").build()))
            typed.launch(intent)
        }, Icons.Filled.Keyboard, primary = false, width = 110.dp)
    }
}

/** 10, 11 and the slow case: one message and OK. */
@Composable
private fun Notice(icon: ImageVector, tint: Color, title: String, body: String, note: String? = null, onOk: () -> Unit) {
    Centered {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(body, fontSize = 12.sp, color = W.dim, textAlign = TextAlign.Center)
        note?.let { Text(it, fontSize = 11.sp, fontFamily = W.mono, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(W.surface).padding(horizontal = 10.dp, vertical = 3.dp)) }
        Pill("OK", onOk, width = 100.dp)
    }
}

/** Sample numbers for debug screenshots only (EXTRA_DEMO). Nobody's real log. */
private object Demo {
    val state = WatchState(
        goals = listOf(WatchState.Goal("Protein", 92.0, 160.0, "g", false), WatchState.Goal("Calories", 1420.0, 2200.0, "kcal", true),
            WatchState.Goal("Fiber", 14.0, 30.0, "g", false)),
        need = WatchState.Need("Protein", 92.0, 160.0, "g", "Protein shake covers 30 g."),
        again = listOf(WatchState.Again("d1", "Greek yogurt", "1 cup · 150 kcal"), WatchState.Again("d2", "Protein shake", "1 bottle · 160 kcal"),
            WatchState.Again("d3", "Chicken rice bowl", "1 bowl · 520 kcal")),
        meals = listOf(WatchState.Meal(listOf("d4", "d5", "d6"), "Your usual lunch", listOf("Veggie bag", "Greek yogurt", "Bean burrito"), 460)),
        updatedAt = 1,
    )

    fun reply(id: String, text: String) = WatchProtocol.Reply(id, WatchProtocol.Kind.HEARD, heard = text,
        lines = listOf(WatchProtocol.Line("Eggs", "2 large", 143), WatchProtocol.Line("Toast", "1 slice", 80)))
}
