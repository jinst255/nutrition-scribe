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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    /** date: an ISO day from History's "+" (blank is today). type=1: open with the keyboard up. */
    const val ADD_ENTRY = "add_entry?date={date}&type={type}"
    fun addEntry(date: java.time.LocalDate? = null, type: Boolean = false) =
        "add_entry?date=${date?.toString().orEmpty()}&type=${if (type) 1 else 0}"
    const val REVIEW = "review"
    const val UNPROCESSED = "unprocessed"
    const val HISTORY = "history"
    const val GOALS = "goals"
    const val SETTINGS = "settings"
    const val DEBUG_LOG = "debug_log"
    const val EXPORT_IMPORT = "export_import"
    const val ENTRY_DETAIL = "entry/{entryId}"
    const val FIX = "fix/{entryId}"
    const val SCAN = "scan/{index}"
    const val MY_FOODS = "my_foods"
    const val SPENDING = "spending"
    /** Scan index for "start a new entry from a scan", from the Log screen. */
    const val SCAN_NEW = -2
    fun fix(id: String) = "fix/$id"
    fun scan(index: Int) = "scan/$index"
    const val METRIC_CHART = "history/metric/{metric}"
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
    val onboarded by ServiceLocator.settingsRepository.isOnboarded.collectAsState(initial = null)
    when (onboarded) {
        null -> Unit
        false -> OnboardingScreen(onFinished = {})
        true -> MainNavScaffold()
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
        ?.takeIf { it != java.time.LocalDate.now() }
        ?.let { "Add to " + it.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d")) }
        ?: "Log food"
    Routes.REVIEW -> "Check it once"
    Routes.FIX -> "Fix entry"
    Routes.SCAN -> "Scan the package"
    Routes.MY_FOODS -> "My foods"
    Routes.UNPROCESSED -> "Needs attention"
    Routes.HISTORY -> "History"
    Routes.GOALS -> "Goals"
    Routes.SETTINGS -> "Settings"
    Routes.DEBUG_LOG -> "Debug log"
    Routes.SPENDING -> "AI spending"
    Routes.EXPORT_IMPORT -> "Back up & restore"
    Routes.ENTRY_DETAIL -> "Entry"
    // The chart switches nutrient in place, so the bar names the screen, not the nutrient.
    Routes.METRIC_CHART -> "Over time"
    else -> "Nutrition Scribe"
}

private data class BottomTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(currentDestination?.route, backStackEntry?.arguments)) },
                navigationIcon = {
                    if (!onTab && currentDestination != null) {
                        // Through the dispatcher rather than popBackStack(), so a
                        // screen's own BackHandler (leaving an edit) gets first say.
                        val dispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
                        IconButton(onClick = {
                            dispatcher?.onBackPressedDispatcher?.onBackPressed() ?: navController.popBackStack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            if (onTab) NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
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
                )
            }
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
                com.nutritionscribe.app.ui.scan.ScanScreen(
                    onFound = { candidate ->
                        ServiceLocator.debugEvents.event("scan_used", "index" to index)
                        if (index == Routes.SCAN_NEW) {
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
            composable(Routes.MY_FOODS) { com.nutritionscribe.app.ui.myfoods.MyFoodsScreen() }
            composable(Routes.UNPROCESSED) { UnprocessedScreen() }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onOpenEntry = { id -> navController.navigate(Routes.entryDetail(id)) },
                    onAddToDay = { day -> navController.navigate(Routes.addEntry(day)) },
                    onOpenMetric = { metric -> navController.navigate(Routes.metricChart(metric)) },
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
                    onFix = { navController.navigate(Routes.fix(id)) },
                )
            }
            composable(Routes.GOALS) { GoalsScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenDebugLog = { navController.navigate(Routes.DEBUG_LOG) },
                    onOpenExportImport = { navController.navigate(Routes.EXPORT_IMPORT) },
                    onOpenMyFoods = { navController.navigate(Routes.MY_FOODS) },
                    onOpenSpending = { navController.navigate(Routes.SPENDING) },
                )
            }
            composable(Routes.SPENDING) {
                com.nutritionscribe.app.ui.cost.SpendingScreen(
                    onOpenEntry = { id -> navController.navigate(Routes.entryDetail(id)) },
                )
            }
            composable(Routes.DEBUG_LOG) { DebugLogScreen() }
            composable(Routes.EXPORT_IMPORT) { ExportImportScreen() }
        }
    }
}
