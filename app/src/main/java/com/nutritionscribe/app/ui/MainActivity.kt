package com.nutritionscribe.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.Icons
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
import com.nutritionscribe.app.ui.history.HistoryScreen
import com.nutritionscribe.app.ui.home.HomeScreen
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
    const val SETTINGS = "settings"
    const val DEBUG_LOG = "debug_log"
    const val EXPORT_IMPORT = "export_import"
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

private data class BottomTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun MainNavScaffold() {
    val navController = rememberNavController()
    val tabs = listOf(
        BottomTab(Routes.HOME, "Home", Icons.Filled.Home),
        BottomTab(Routes.HISTORY, "History", Icons.Filled.History),
        BottomTab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
    )

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            NavigationBar {
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
                        label = { androidx.compose.material3.Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
            enterTransition = { androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(120)) },
            exitTransition = { androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(120)) },
            popEnterTransition = { androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(120)) },
            popExitTransition = { androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(120)) },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onAddEntry = { navController.navigate(Routes.ADD_ENTRY) },
                    onOpenUnprocessed = { navController.navigate(Routes.UNPROCESSED) },
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
            composable(Routes.HISTORY) { HistoryScreen() }
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
