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
import com.nutritionscribe.app.ui.home.HomeScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.nutritionscribe.app.ui.entry.EntryDetailScreen
import com.nutritionscribe.app.ui.onboarding.OnboardingScreen
import com.nutritionscribe.app.ui.review.ReviewScreen
import com.nutritionscribe.app.ui.settings.SettingsScreen
import com.nutritionscribe.app.ui.theme.AppThemeMode
import com.nutritionscribe.app.ui.theme.NutritionScribeTheme
import com.nutritionscribe.app.ui.unprocessed.UnprocessedScreen

private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ADD_ENTRY = "add_entry"
    const val REVIEW = "review"
    const val UNPROCESSED = "unprocessed"
    const val HISTORY = "history"
    const val GOALS = "goals"
    const val SETTINGS = "settings"
    const val DEBUG_LOG = "debug_log"
    const val EXPORT_IMPORT = "export_import"
    const val ENTRY_DETAIL = "entry/{entryId}"
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
private fun titleFor(route: String?): String = when (route) {
    // The list below already says "Today"; the bar says which day that is.
    Routes.HOME -> java.time.LocalDate.now().format(
        java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d"),
    )
    Routes.ADD_ENTRY -> "Log food"
    Routes.REVIEW -> "Check the numbers"
    Routes.UNPROCESSED -> "Needs attention"
    Routes.HISTORY -> "History"
    Routes.GOALS -> "Goals"
    Routes.SETTINGS -> "Settings"
    Routes.DEBUG_LOG -> "Error log"
    Routes.EXPORT_IMPORT -> "Back up & restore"
    Routes.ENTRY_DETAIL -> "Entry"
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

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // The four tabs are places; everything else is somewhere you went FROM one of
    // them, so it gets a back arrow and the tab bar steps aside.
    val onTab = tabs.any { it.route == currentDestination?.route }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleFor(currentDestination?.route)) },
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
                    onAddEntry = { navController.navigate(Routes.ADD_ENTRY) },
                    onOpenUnprocessed = { navController.navigate(Routes.UNPROCESSED) },
                    onOpenEntry = { id -> navController.navigate(Routes.entryDetail(id)) },
                )
            }
            composable(Routes.ADD_ENTRY) {
                AddEntryScreen(
                    onReadyForReview = { navController.navigate(Routes.REVIEW) },
                    // A manual entry is already saved by the time this fires — there is
                    // nothing to review, so it goes straight back Home.
                    onManualSaved = { navController.popBackStack(Routes.HOME, inclusive = false) },
                )
            }
            composable(Routes.REVIEW) {
                ReviewScreen(onConfirmed = { navController.popBackStack(Routes.HOME, inclusive = false) })
            }
            composable(Routes.UNPROCESSED) { UnprocessedScreen() }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onOpenEntry = { id -> navController.navigate(Routes.entryDetail(id)) },
                )
            }
            composable(
                Routes.ENTRY_DETAIL,
                arguments = listOf(navArgument("entryId") { type = NavType.StringType }),
            ) { backStackEntry ->
                EntryDetailScreen(
                    entryId = backStackEntry.arguments?.getString("entryId").orEmpty(),
                )
            }
            composable(Routes.GOALS) { GoalsScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenDebugLog = { navController.navigate(Routes.DEBUG_LOG) },
                    onOpenExportImport = { navController.navigate(Routes.EXPORT_IMPORT) },
                )
            }
            composable(Routes.DEBUG_LOG) { DebugLogScreen() }
            composable(Routes.EXPORT_IMPORT) { ExportImportScreen() }
        }
    }
}
