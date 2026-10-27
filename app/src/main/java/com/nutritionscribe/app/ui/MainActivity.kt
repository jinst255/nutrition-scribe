package com.nutritionscribe.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nutritionscribe.app.data.db.toEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nutritionscribe.app.data.repository.ThemeMode
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.addentry.AddEntryScreen
import com.nutritionscribe.app.ui.debuglog.DebugLogScreen
import com.nutritionscribe.app.ui.exportimport.ExportImportScreen
import com.nutritionscribe.app.ui.goals.GoalsScreen
import com.nutritionscribe.app.ui.history.HistoryScreen
import com.nutritionscribe.app.ui.history.MetricChartScreen
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.app.ui.home.HomeScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.nutritionscribe.app.ui.entry.EntryDetailScreen
import com.nutritionscribe.app.ui.onboarding.OnboardingScreen
import com.nutritionscribe.app.ui.settings.SettingsScreen
import com.nutritionscribe.app.ui.theme.AppThemeMode
import com.nutritionscribe.app.ui.theme.NutritionScribeTheme
import com.nutritionscribe.app.ui.unprocessed.UnprocessedScreen

private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val DAY = "day/{date}"
    fun day(d: java.time.LocalDate) = "day/$d"
    /** date: an ISO day from History's "+" (blank is today). type=1: open with the keyboard up. */
    const val ADD_ENTRY = "add_entry?date={date}&type={type}"
    fun addEntry(date: java.time.LocalDate? = null, type: Boolean = false) =
        "add_entry?date=${date?.toString().orEmpty()}&type=${if (type) 1 else 0}"
    const val REVIEW = "review"
    const val UNPROCESSED = "unprocessed"
    const val HISTORY = "history"
    const val GOALS = "goals"
    const val STREAK = "goals/streak"
    const val ASK = "ask"
    const val AI_MODELS = "ai_models"
    const val SETTINGS = "settings"
    const val DEBUG_LOG = "debug_log"
    const val EXPORT_IMPORT = "export_import"
    const val ENTRY_DETAIL = "entry/{entryId}"
    const val FIX = "fix/{entryId}"
    const val SCAN = "scan/{index}"
    const val MY_FOODS = "my_foods"
    const val SPENDING = "spending"
    const val IDEAS = "ideas"
    const val FEEDBACK = "feedback?checkin={checkin}&kind={kind}"
    fun feedback(checkIn: Boolean = false, idea: Boolean = false) =
        "feedback?checkin=${if (checkIn) 1 else 0}&kind=${if (idea) "idea" else ""}"
    const val BETA_SENT = "beta_sent"
    /** Scan index for "start a new entry from a scan", from the Log screen. */
    const val SCAN_NEW = -2
    /** Scan index for a kind not saved yet, from the Logged card: the entry becomes it. */
    const val SCAN_KIND = -3
    /** A scan from My foods › Add a food: saved, not logged. */
    const val SCAN_SAVE = -4
    const val MY_LABELS = "my_labels"
    fun fix(id: String) = "fix/$id"
    fun scan(index: Int) = "scan/$index"
    const val METRIC_CHART = "history/metric/{metric}"
    /** A nutrient's page: what it does for you. [date] is blank for today. */
    const val NUTRIENT = "nutrient/{metric}?have={have}&target={target}&date={date}"
    fun nutrient(metric: Metric, have: Double, target: Double, date: java.time.LocalDate? = null) =
        "nutrient/${metric.key}?have=$have&target=$target&date=${date?.toString().orEmpty()}"
    fun metricChart(metric: Metric) = "history/metric/${metric.key}"
    fun entryDetail(id: String) = "entry/$id"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val theme by ServiceLocator.settingsRepository.theme.collectAsState(initial = ThemeMode.SYSTEM)
            val mode = when (theme) {
                ThemeMode.SYSTEM -> AppThemeMode.SYSTEM
                ThemeMode.LIGHT -> AppThemeMode.LIGHT
                ThemeMode.DARK -> AppThemeMode.DARK
            }
            NutritionScribeTheme(mode = mode) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    NutritionScribeApp()
                }
            }
        }
    }
}

@Composable
private fun NutritionScribeApp() {
    val settings = ServiceLocator.settingsRepository
    val onboarded by settings.isOnboarded.collectAsState(initial = null)
    // Test versions ask whose copy this is, once at first open and once after each update.
    val testing = androidx.compose.runtime.remember { com.nutritionscribe.app.ui.feedback.isTestBuild(ServiceLocator.appContext) }
    val version = androidx.compose.runtime.remember { com.nutritionscribe.app.ui.onboarding.appVersionCode(ServiceLocator.appContext) }
    val name by settings.testerName.collectAsState(initial = null)
    val confirmed by settings.nameConfirmedVersion.collectAsState(initial = null)
    val askName = testing && name != null && confirmed != null && confirmed!! < version
    when {
        onboarded == null || (testing && (name == null || confirmed == null)) -> Unit
        askName -> com.nutritionscribe.app.ui.onboarding.BetaNameScreen(current = name.orEmpty(), onDone = {})
        onboarded == false -> OnboardingScreen(onFinished = {})
        else -> MainNavScaffold()
    }
}

/**
 * What the bar at the top of each screen says. Every screen used to open straight
 * into its content with no title, which is most of why the app read as a set of
 * pages stuck together rather than one thing: nothing told you where you were, and
 * the only way back from a sub-screen was the system gesture.
 */
private fun titleFor(route: String?, args: Bundle?): String = when (route) {
    // The list below already says "Today"; the bar says which day that is.
    Routes.HOME -> java.time.LocalDate.now().format(
        java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d"),
    )
    Routes.ADD_ENTRY -> args?.getString("date")?.takeIf { it.isNotBlank() }
        ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        ?.takeIf { it != com.nutritionscribe.shared.model.LogDays.today() }
        ?.let { "Add to " + it.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d")) }
        ?: "Log food"
    Routes.REVIEW -> "Check it once"
    Routes.DAY -> args?.getString("date")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        ?.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d")) ?: "Day"
    Routes.FIX -> "Edit entry"
    Routes.SCAN -> "Scan the package"
    Routes.MY_FOODS -> "My foods"
    Routes.MY_LABELS -> "My labels"
    Routes.UNPROCESSED -> "Needs attention"
    Routes.HISTORY -> "History"
    Routes.GOALS -> "Goals"
    Routes.STREAK -> "Streak"
    Routes.AI_MODELS -> "AI models"
    Routes.SETTINGS -> "Settings"
    Routes.DEBUG_LOG -> "Debug log"
    Routes.SPENDING -> "AI spending"
    Routes.IDEAS -> "Ideas"
    Routes.FEEDBACK -> when {
        args?.getInt("checkin") == 1 -> "Your week with the app"
        args?.getString("kind") == "idea" -> "I have an idea"
        else -> "Tell us"
    }
    Routes.BETA_SENT -> "What I've sent"
    Routes.EXPORT_IMPORT -> "Back up & restore"
    Routes.ENTRY_DETAIL -> "Entry"
    // The chart switches nutrient in place, so the bar names the screen, not the nutrient.
    Routes.METRIC_CHART -> "Over time"
    Routes.NUTRIENT -> args?.getString("metric")?.let { Metric.fromKey(it) }
        ?.let { com.nutritionscribe.shared.model.NutrientGuide.of(it).metric.label } ?: "Nutrient"
    else -> "Nutrition Scribe"
}

private data class BottomTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/**
 * The bar at the top, 48 dp instead of Material's 64: the title and a back arrow
 * need no more. On Home the title is just the date, set small.
 */
@Composable
private fun CompactTopBar(title: String, small: Boolean, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(if (small) 40.dp else 48.dp)
                .padding(start = if (onBack != null) 4.dp else 16.dp, end = 16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
            Text(
                title,
                style = if (small) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = if (small) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
    }
}

/**
 * The four tabs, 60 dp tall instead of Material's 80: the same pill behind the
 * chosen icon and the same labels, with less air above and below them.
 */
@Composable
private fun CompactTabBar(tabs: List<BottomTab>, selected: (BottomTab) -> Boolean, onSelect: (BottomTab) -> Unit) {
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp)
                .then(androidx.compose.ui.Modifier.selectableGroup()),
        ) {
            tabs.forEach { tab ->
                val on = selected(tab)
                androidx.compose.foundation.layout.Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected = on, onClick = { onSelect(tab) }, role = androidx.compose.ui.semantics.Role.Tab),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(width = 56.dp, height = 28.dp).background(
                            if (on) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                            androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        ),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Icon(tab.icon, contentDescription = null, modifier = Modifier.size(22.dp),
                            tint = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainNavScaffold() {
    val navController = rememberNavController()
    val tabs = listOf(
        BottomTab(Routes.HOME, "Home", Icons.Filled.Home),
        BottomTab(Routes.HISTORY, "History", Icons.Filled.History),
        BottomTab(Routes.GOALS, "Goals", Icons.Filled.Flag),
        BottomTab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
    )

    androidx.compose.runtime.DisposableEffect(navController) {
        val l = androidx.navigation.NavController.OnDestinationChangedListener { _, dest, args ->
            val (prev, prevMs) = ServiceLocator.debugEvents.screenChanged(dest.route?.substringBefore('?'))
            ServiceLocator.debugEvents.event("screen", "route" to dest.route, "prev" to prev, "prev_ms" to prevMs,
                "args" to args?.keySet()?.filterNot { it.startsWith("android-support-nav") }
                    ?.associateWith { k -> @Suppress("DEPRECATION") args.get(k)?.toString() }?.takeIf { it.isNotEmpty() })
        }
        navController.addOnDestinationChangedListener(l)
        onDispose { navController.removeOnDestinationChangedListener(l) }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // The four tabs are places; everything else is somewhere you went FROM one of
    // them, so it gets a back arrow and the tab bar steps aside.
    val onTab = tabs.any { it.route == currentDestination?.route }

    com.nutritionscribe.app.ui.feedback.HoldToFeedback(screen = { titleFor(currentDestination?.route, backStackEntry?.arguments) + " (${currentDestination?.route})" }) {
    Scaffold(
        topBar = {
            val dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
            // Home opens on its own streak strip, which carries the date.
            if (currentDestination?.route !in setOf(Routes.HOME, Routes.ASK, Routes.ENTRY_DETAIL)) CompactTopBar(
                title = titleFor(currentDestination?.route, backStackEntry?.arguments),
                // Home's title is only the date: small, so the day's numbers start higher.
                small = currentDestination?.route == Routes.HOME,
                onBack = if (!onTab && currentDestination != null) ({
                    // Through the dispatcher rather than popBackStack(), so a
                    // screen's own BackHandler (leaving an edit) gets first say.
                    dispatcher?.onBackPressedDispatcher?.onBackPressed() ?: navController.popBackStack()
                }) else null,
                actions = {
                    if (currentDestination?.route == Routes.GOALS) {
                        IconButton(onClick = { com.nutritionscribe.app.ui.goals.GoalsInfo.open.value = true }) {
                            Icon(androidx.compose.material.icons.Icons.Outlined.Info, contentDescription = "How goals work",
                                tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (onTab) CompactTabBar(
                tabs = tabs,
                selected = { tab -> currentDestination?.hierarchy?.any { it.route == tab.route } == true },
                onSelect = { tab ->
                    if (tab.route == Routes.HOME) com.nutritionscribe.app.ui.home.HomeTab.taps.value++
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { padding ->
        NavHost(
            navController,
            startDestination = Routes.HOME,
            // consumeWindowInsets: screens with their own Scaffold (Home) would
            // otherwise pad for the status bar a second time under the top bar.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = { androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(120)) },
            exitTransition = { androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(120)) },
            popEnterTransition = { androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(120)) },
            popExitTransition = { androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(120)) },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenUnprocessed = { navController.navigate(Routes.UNPROCESSED) },
                    onOpenEntry = { id -> navController.navigate(Routes.entryDetail(id)) },
                    onFixEntry = { id -> navController.navigate(Routes.fix(id)) },
                    onTypeIt = { navController.navigate(Routes.addEntry(type = true)) },
                    onScan = {
                        com.nutritionscribe.app.ui.confirm.LogDay.today()
                        navController.navigate(Routes.scan(Routes.SCAN_NEW))
                    },
                    onReview = { navController.navigate(Routes.REVIEW) },
                    onOpenGoals = {
                        navController.navigate(Routes.GOALS) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                    onOpenIdeas = { navController.navigate(Routes.IDEAS) },
                    onOpenNutrient = { m, have, target -> navController.navigate(Routes.nutrient(m, have, target)) },
                    onCheckIn = { navController.navigate(Routes.feedback(checkIn = true)) },
                    onScanKind = { navController.navigate(Routes.scan(Routes.SCAN_KIND)) },
                    onStreakRules = { navController.navigate(Routes.STREAK) },
                    onAsk = { navController.navigate(Routes.ASK) },
                )
            }
            composable(Routes.IDEAS) { com.nutritionscribe.app.ui.ideas.IdeasScreen() }
            composable(Routes.FEEDBACK, arguments = listOf(
                navArgument("checkin") { type = NavType.IntType; defaultValue = 0 },
                navArgument("kind") { type = NavType.StringType; defaultValue = "" },
            )) { b ->
                val kind = when {
                    b.arguments?.getInt("checkin") == 1 -> com.nutritionscribe.app.ui.feedback.FeedbackKind.CHECK_IN
                    b.arguments?.getString("kind") == "idea" -> com.nutritionscribe.app.ui.feedback.FeedbackKind.IDEA
                    else -> com.nutritionscribe.app.ui.feedback.FeedbackKind.PROBLEM
                }
                com.nutritionscribe.app.ui.feedback.FeedbackScreen(kind = kind)
            }
            composable(Routes.BETA_SENT) { com.nutritionscribe.app.ui.feedback.SentScreen() }
            composable(
                Routes.ADD_ENTRY,
                arguments = listOf(
                    navArgument("date") { type = NavType.StringType; defaultValue = "" },
                    navArgument("type") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { backStackEntry ->
                val date = backStackEntry.arguments?.getString("date")?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                AddEntryScreen(
                    onReadyForReview = { navController.navigate(Routes.REVIEW) },
                    // Already saved by the time these fire: back to wherever this was
                    // opened from — Home, or the day in History.
                    onManualSaved = { navController.popBackStack() },
                    onLogged = { navController.popBackStack() },
                    onScan = { navController.navigate(Routes.scan(Routes.SCAN_NEW)) },
                    date = date,
                    startTyping = backStackEntry.arguments?.getInt("type") == 1,
                )
            }
            composable(Routes.REVIEW) {
                com.nutritionscribe.app.ui.confirm.ConfirmScreen(
                    onLogged = {
                        // Past the Log food screen if it was used (History's day), else Home.
                        if (!navController.popBackStack(Routes.ADD_ENTRY, inclusive = true)) {
                            navController.popBackStack(Routes.HOME, inclusive = false)
                        }
                    },
                    onScan = { i -> navController.navigate(Routes.scan(i)) },
                )
            }
            composable(
                Routes.FIX,
                arguments = listOf(navArgument("entryId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("entryId").orEmpty()
                var ready by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(id) {
                    val row = ServiceLocator.database.entryDao().find(id)
                    com.nutritionscribe.app.ui.confirm.LogSession.startFix(row?.toEntry())
                    ready = true
                }
                if (ready && com.nutritionscribe.app.ui.confirm.LogSession.editing != null) {
                    com.nutritionscribe.app.ui.confirm.ConfirmScreen(
                        onLogged = { navController.popBackStack() },
                        onScan = { i -> navController.navigate(Routes.scan(i)) },
                    )
                } else if (ready) {
                    Text("That entry is gone.", modifier = Modifier.padding(24.dp))
                }
            }
            composable(
                Routes.SCAN,
                arguments = listOf(navArgument("index") { type = NavType.IntType }),
            ) { backStackEntry ->
                val index = backStackEntry.arguments?.getInt("index") ?: 0
                val hint = androidx.compose.runtime.remember { if (index < 0) null else com.nutritionscribe.app.ui.confirm.LogSession.scanHint.also { com.nutritionscribe.app.ui.confirm.LogSession.scanHint = null } }
                val kind = androidx.compose.runtime.remember { if (index == Routes.SCAN_KIND) com.nutritionscribe.app.ui.home.KindScan.pending else null }
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                com.nutritionscribe.app.ui.scan.ScanScreen(
                    nameHint = hint,
                    kindOf = kind?.second,
                    onMyLabels = { navController.navigate(Routes.MY_LABELS) },
                    onFound = { candidate ->
                        ServiceLocator.debugEvents.event("scan_used", "index" to index)
                        if (index == Routes.SCAN_KIND && kind != null) {
                            com.nutritionscribe.app.ui.home.KindScan.pending = null
                            scope.launch {
                                val updated = ServiceLocator.logPipeline.swapKind(kind.first, candidate, "scan_kind")
                                com.nutritionscribe.app.ui.home.LastLogged.show(updated, "new kind")
                                navController.popBackStack()
                            }
                        } else if (index == Routes.SCAN_SAVE) {
                            scope.launch {
                                val d = com.nutritionscribe.app.ui.confirm.ItemDraft.fromCandidate(candidate)
                                ServiceLocator.logPipeline.remember(d.name, listOf(d.toConfirmed().copy(unchangedMemory = false)), "scan")
                                android.widget.Toast.makeText(ServiceLocator.appContext, "Saved ${d.name} to My foods", android.widget.Toast.LENGTH_SHORT).show()
                                navController.popBackStack()
                            }
                        } else if (index == Routes.SCAN_NEW) {
                            com.nutritionscribe.app.ui.confirm.LogSession.startFromScan(candidate)
                            navController.navigate(Routes.REVIEW) { popUpTo(Routes.SCAN) { inclusive = true } }
                        } else {
                            com.nutritionscribe.app.ui.confirm.LogSession.scanned = index to candidate
                            navController.popBackStack()
                        }
                    },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable(Routes.MY_FOODS) {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                val context = androidx.compose.ui.platform.LocalContext.current
                com.nutritionscribe.app.ui.myfoods.MyFoodsScreen(
                    onLog = { phrase ->
                        com.nutritionscribe.app.ui.confirm.LogDay.today()
                        scope.launch {
                            when (val out = com.nutritionscribe.app.ui.addentry.LogFlow.submit(phrase, "my_foods")) {
                                is com.nutritionscribe.app.ui.addentry.LogFlow.Outcome.Logged ->
                                    android.widget.Toast.makeText(context, "Logged ${out.entry.name}", android.widget.Toast.LENGTH_SHORT).show()
                                com.nutritionscribe.app.ui.addentry.LogFlow.Outcome.Review -> navController.navigate(Routes.REVIEW)
                                is com.nutritionscribe.app.ui.addentry.LogFlow.Outcome.Failed ->
                                    android.widget.Toast.makeText(context, out.message, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onScanToSave = { navController.navigate(Routes.scan(Routes.SCAN_SAVE)) },
                )
            }
            composable(Routes.MY_LABELS) { com.nutritionscribe.app.ui.myfoods.MyLabelsScreen() }
            composable(Routes.UNPROCESSED) { UnprocessedScreen() }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onOpenEntry = { id -> navController.navigate(Routes.entryDetail(id)) },
                    onAddToDay = { day -> navController.navigate(Routes.addEntry(day)) },
                    onOpenMetric = { metric -> navController.navigate(Routes.metricChart(metric)) },
                    onOpenDay = { day -> navController.navigate(Routes.day(day)) },
                )
            }
            composable(Routes.DAY, arguments = listOf(navArgument("date") { type = NavType.StringType })) { entry ->
                val day = entry.arguments?.getString("date")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: com.nutritionscribe.shared.model.LogDays.today()
                com.nutritionscribe.app.ui.home.DayScreen(
                    date = day,
                    onOpenEntry = { id -> navController.navigate(Routes.entryDetail(id)) },
                    onOpenGoals = { navController.navigate(Routes.GOALS) },
                    onOpenNutrient = { m, have, target -> navController.navigate(Routes.nutrient(m, have, target, day)) },
                )
            }
            composable(
                Routes.NUTRIENT,
                arguments = listOf(
                    navArgument("metric") { type = NavType.StringType },
                    navArgument("have") { type = NavType.FloatType; defaultValue = 0f },
                    navArgument("target") { type = NavType.FloatType; defaultValue = 0f },
                    navArgument("date") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val a = entry.arguments
                val metric = a?.getString("metric")?.let { Metric.fromKey(it) } ?: Metric.PROTEIN
                val day = a?.getString("date")?.takeIf { it.isNotBlank() }?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                    ?.takeIf { it != com.nutritionscribe.shared.model.LogDays.today() }
                com.nutritionscribe.app.ui.home.NutrientGuideScreen(
                    metric = metric,
                    have = (a?.getFloat("have") ?: 0f).toDouble(),
                    target = (a?.getFloat("target") ?: 0f).toDouble(),
                    dayLabel = day?.let { "On " + it.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d")) },
                )
            }
            composable(
                Routes.METRIC_CHART,
                arguments = listOf(navArgument("metric") { type = NavType.StringType }),
            ) { entry ->
                val metric = entry.arguments?.getString("metric")?.let { Metric.fromKey(it) } ?: Metric.PROTEIN
                MetricChartScreen(
                    startMetric = metric,
                    onOpenEntry = { id -> navController.navigate(Routes.entryDetail(id)) },
                    onAddToDay = { day -> navController.navigate(Routes.addEntry(day)) },
                )
            }
            composable(
                Routes.ENTRY_DETAIL,
                arguments = listOf(navArgument("entryId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("entryId").orEmpty()
                EntryDetailScreen(
                    entryId = id,
                    onBack = { navController.popBackStack() },
                    onFix = { navController.navigate(Routes.fix(id)) },
                    onScan = { i -> navController.navigate(Routes.scan(i)) },
                )
            }
            composable(Routes.GOALS) { GoalsScreen(onStreak = { navController.navigate(Routes.STREAK) }) }
            composable(Routes.STREAK) { com.nutritionscribe.app.ui.goals.StreakSettingsScreen() }
            composable(
                Routes.ASK,
                enterTransition = { androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { it / 3 } + androidx.compose.animation.fadeIn() },
                popExitTransition = { androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(200)) { it / 3 } + androidx.compose.animation.fadeOut() },
            ) { com.nutritionscribe.app.ui.ask.AskScreen(onClose = { navController.popBackStack() }) }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenDebugLog = { navController.navigate(Routes.DEBUG_LOG) },
                    onOpenExportImport = { navController.navigate(Routes.EXPORT_IMPORT) },
                    onOpenMyFoods = { navController.navigate(Routes.MY_FOODS) },
                    onOpenSpending = { navController.navigate(Routes.SPENDING) },
                    onOpenAiModels = { navController.navigate(Routes.AI_MODELS) },
                    onOpenFeedback = { navController.navigate(Routes.feedback()) },
                    onOpenMyLabels = { navController.navigate(Routes.MY_LABELS) },
                    onOpenIdea = { navController.navigate(Routes.feedback(idea = true)) },
                    onOpenSent = { navController.navigate(Routes.BETA_SENT) },
                    onOpenCheckIn = { navController.navigate(Routes.feedback(checkIn = true)) },
                )
            }
            composable(Routes.SPENDING) {
                com.nutritionscribe.app.ui.cost.SpendingScreen(
                    onOpenEntry = { id -> navController.navigate(Routes.entryDetail(id)) },
                )
            }
            composable(Routes.DEBUG_LOG) { DebugLogScreen() }
            composable(Routes.AI_MODELS) { com.nutritionscribe.app.ui.settings.AiModelsScreen() }
            composable(Routes.EXPORT_IMPORT) { ExportImportScreen() }
        }
    }
    }
}
