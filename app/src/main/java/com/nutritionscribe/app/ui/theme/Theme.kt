package com.nutritionscribe.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette confirmed in Section 13.
object NutritionScribeColors {
    val LightBackground = Color(0xFFFAFAF7)
    val LightSurface = Color(0xFFFFFFFF)
    val LightBorder = Color(0xFFE7E5DE)
    val LightTextPrimary = Color(0xFF1C1C1A)
    val LightTextSecondary = Color(0xFF6B6B66)
    val LightGoalMet = Color(0xFF2F6F4F)
    val LightUnderGoal = Color(0xFF3E6FA8)
    val LightDanger = Color(0xFFB5433A)

    val DarkBackground = Color(0xFF17171A)
    val DarkSurface = Color(0xFF232326)
    val DarkBorder = Color(0xFF2C2C30)
    val DarkTextPrimary = Color(0xFFF2F1EC)
    val DarkTextSecondary = Color(0xFFA8A79F)
    val DarkGoalMet = Color(0xFF4E9873)
    val DarkUnderGoal = Color(0xFF6D9BD1)
    val DarkDanger = Color(0xFFD9776C)
}

private val LightColors = lightColorScheme(
    background = NutritionScribeColors.LightBackground,
    surface = NutritionScribeColors.LightSurface,
    outline = NutritionScribeColors.LightBorder,
    onBackground = NutritionScribeColors.LightTextPrimary,
    onSurface = NutritionScribeColors.LightTextPrimary,
    secondary = NutritionScribeColors.LightTextSecondary,
    primary = NutritionScribeColors.LightGoalMet,
    tertiary = NutritionScribeColors.LightUnderGoal,
    error = NutritionScribeColors.LightDanger,
)

private val DarkColors = darkColorScheme(
    background = NutritionScribeColors.DarkBackground,
    surface = NutritionScribeColors.DarkSurface,
    outline = NutritionScribeColors.DarkBorder,
    onBackground = NutritionScribeColors.DarkTextPrimary,
    onSurface = NutritionScribeColors.DarkTextPrimary,
    secondary = NutritionScribeColors.DarkTextSecondary,
    primary = NutritionScribeColors.DarkGoalMet,
    tertiary = NutritionScribeColors.DarkUnderGoal,
    error = NutritionScribeColors.DarkDanger,
)

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun NutritionScribeTheme(mode: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val useDark = when (mode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        content = content,
    )
}
