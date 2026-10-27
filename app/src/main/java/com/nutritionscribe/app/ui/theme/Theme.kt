package com.nutritionscribe.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    // Dark neutrals lean very slightly green, like the light ones lean warm. They
    // used to lean blue-violet (0x17171A), which fought every green on the screen.
    val DarkBackground = Color(0xFF141615)
    val DarkSurface = Color(0xFF1D201E)
    val DarkBorder = Color(0xFF2C302D)
    val DarkTextPrimary = Color(0xFFF2F1EC)
    val DarkTextSecondary = Color(0xFFA8A79F)
    val DarkGoalMet = Color(0xFF4E9873)
    val DarkUnderGoal = Color(0xFF6D9BD1)
    val DarkDanger = Color(0xFFD9776C)

    // Container and track roles. Material fills these with its default purple
    // when they are left unset, which is how a green app grew a lavender FAB.
    val LightGoalMetSoft = Color(0xFFDDEAE1)
    val LightGoalMetDeep = Color(0xFF17422E)
    val LightUnderGoalSoft = Color(0xFFDDE7F2)
    val LightDangerSoft = Color(0xFFF6DEDC)
    val LightDangerDeep = Color(0xFF5C221D)
    val LightRingTrack = Color(0xFFE7E5DE)
    // `outline` has to be VISIBLE against surfaceVariant: Material draws an
    // unchecked Switch as a surfaceVariant track with an `outline` thumb and an
    // `outline` border. Setting all three to the same near-invisible hairline
    // made the thumb disappear completely — the switch read as a blank lozenge
    // in both themes. `outlineVariant` is the subtle one, for dividers.
    val LightOutline = Color(0xFF9AA098)

    val DarkGoalMetSoft = Color(0xFF244635)
    val DarkUnderGoalSoft = Color(0xFF28384B)
    val DarkDangerSoft = Color(0xFF5A2F2A)
    val DarkRingTrack = Color(0xFF2C302D)
    val DarkOutline = Color(0xFF727C76)

    // Surface-container tiers. Material 3 draws Cards from surfaceContainerHighest,
    // the navigation bar from surfaceContainer, dialogs and menus from
    // surfaceContainerHigh — and when these are unset it fills them from its
    // baseline scheme, which is lilac-grey (0xE6E0E9). Every card and the tab bar
    // were quietly a different colour family from everything around them, which
    // is a large part of why the app looked assembled rather than designed.
    val LightContainerLowest = Color(0xFFFFFFFF)
    val LightContainerLow = Color(0xFFF4F4EF)
    val LightContainer = Color(0xFFEFEFE9)
    val LightContainerHigh = Color(0xFFEAEAE3)
    val LightContainerHighest = Color(0xFFE4E5DD)

    val DarkContainerLowest = Color(0xFF0F1110)
    val DarkContainerLow = Color(0xFF1A1C1B)
    val DarkContainer = Color(0xFF1E211F)
    val DarkContainerHigh = Color(0xFF272A28)
    val DarkContainerHighest = Color(0xFF313532)

    // Limit states (round 9): soft on purpose, so a day over a target reads as
    // information, not an alarm. Bright red stays for errors and "looks off".
    val DarkOver = Color(0xFFB9A27A)
    val DarkPast = Color(0xFFB98A82)
    val LightOver = Color(0xFF8E7848)
    val LightPast = Color(0xFFA5675E)
}

/** The colours of [com.nutritionscribe.shared.model.Limits.State], for bars, chart days and legends. */
data class LimitColors(val inRange: Color, val over: Color, val past: Color, val low: Color, val none: Color) {
    fun of(state: com.nutritionscribe.shared.model.Limits.State): Color = when (state) {
        com.nutritionscribe.shared.model.Limits.State.IN_RANGE -> inRange
        com.nutritionscribe.shared.model.Limits.State.OVER -> over
        com.nutritionscribe.shared.model.Limits.State.PAST -> past
        com.nutritionscribe.shared.model.Limits.State.LOW -> low
        com.nutritionscribe.shared.model.Limits.State.NOT_JUDGED -> none
    }
}

@Composable
fun limitColors(): LimitColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    return LimitColors(
        inRange = scheme.primary,
        over = if (dark) NutritionScribeColors.DarkOver else NutritionScribeColors.LightOver,
        past = if (dark) NutritionScribeColors.DarkPast else NutritionScribeColors.LightPast,
        low = scheme.tertiary,
        none = scheme.outline,
    )
}

// Setting `primary` alone is not enough, and the gap is very visible: Material 3
// draws a FloatingActionButton from `primaryContainer` and a progress track from
// `surfaceVariant`, neither of which was set. So the app shipped a designed green
// palette with lavender rings and a lavender + button — Material's own default
// purple, showing through everywhere a container role was left unspecified.
private val LightColors = lightColorScheme(
    background = NutritionScribeColors.LightBackground,
    surface = NutritionScribeColors.LightSurface,
    surfaceVariant = NutritionScribeColors.LightRingTrack,
    onSurfaceVariant = NutritionScribeColors.LightTextSecondary,
    surfaceTint = NutritionScribeColors.LightGoalMet,
    outline = NutritionScribeColors.LightOutline,
    outlineVariant = NutritionScribeColors.LightBorder,
    onBackground = NutritionScribeColors.LightTextPrimary,
    onSurface = NutritionScribeColors.LightTextPrimary,
    secondary = NutritionScribeColors.LightTextSecondary,
    onSecondary = NutritionScribeColors.LightSurface,
    secondaryContainer = NutritionScribeColors.LightGoalMetSoft,
    onSecondaryContainer = NutritionScribeColors.LightGoalMetDeep,
    primary = NutritionScribeColors.LightGoalMet,
    onPrimary = NutritionScribeColors.LightSurface,
    primaryContainer = NutritionScribeColors.LightGoalMetSoft,
    onPrimaryContainer = NutritionScribeColors.LightGoalMetDeep,
    tertiary = NutritionScribeColors.LightUnderGoal,
    onTertiary = NutritionScribeColors.LightSurface,
    tertiaryContainer = NutritionScribeColors.LightUnderGoalSoft,
    onTertiaryContainer = NutritionScribeColors.LightTextPrimary,
    error = NutritionScribeColors.LightDanger,
    onError = NutritionScribeColors.LightSurface,
    errorContainer = NutritionScribeColors.LightDangerSoft,
    onErrorContainer = NutritionScribeColors.LightDangerDeep,
    surfaceContainerLowest = NutritionScribeColors.LightContainerLowest,
    surfaceContainerLow = NutritionScribeColors.LightContainerLow,
    surfaceContainer = NutritionScribeColors.LightContainer,
    surfaceContainerHigh = NutritionScribeColors.LightContainerHigh,
    surfaceContainerHighest = NutritionScribeColors.LightContainerHighest,
    surfaceBright = NutritionScribeColors.LightSurface,
    surfaceDim = NutritionScribeColors.LightContainerHighest,
    inverseSurface = NutritionScribeColors.DarkSurface,
    inverseOnSurface = NutritionScribeColors.DarkTextPrimary,
    inversePrimary = NutritionScribeColors.DarkGoalMet,
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    background = NutritionScribeColors.DarkBackground,
    surface = NutritionScribeColors.DarkSurface,
    surfaceVariant = NutritionScribeColors.DarkRingTrack,
    onSurfaceVariant = NutritionScribeColors.DarkTextSecondary,
    surfaceTint = NutritionScribeColors.DarkGoalMet,
    outline = NutritionScribeColors.DarkOutline,
    outlineVariant = NutritionScribeColors.DarkBorder,
    onBackground = NutritionScribeColors.DarkTextPrimary,
    onSurface = NutritionScribeColors.DarkTextPrimary,
    secondary = NutritionScribeColors.DarkTextSecondary,
    onSecondary = NutritionScribeColors.DarkBackground,
    secondaryContainer = NutritionScribeColors.DarkGoalMetSoft,
    onSecondaryContainer = NutritionScribeColors.DarkTextPrimary,
    primary = NutritionScribeColors.DarkGoalMet,
    onPrimary = NutritionScribeColors.DarkBackground,
    primaryContainer = NutritionScribeColors.DarkGoalMetSoft,
    onPrimaryContainer = NutritionScribeColors.DarkTextPrimary,
    tertiary = NutritionScribeColors.DarkUnderGoal,
    onTertiary = NutritionScribeColors.DarkBackground,
    tertiaryContainer = NutritionScribeColors.DarkUnderGoalSoft,
    onTertiaryContainer = NutritionScribeColors.DarkTextPrimary,
    error = NutritionScribeColors.DarkDanger,
    onError = NutritionScribeColors.DarkBackground,
    errorContainer = NutritionScribeColors.DarkDangerSoft,
    onErrorContainer = NutritionScribeColors.DarkTextPrimary,
    surfaceContainerLowest = NutritionScribeColors.DarkContainerLowest,
    surfaceContainerLow = NutritionScribeColors.DarkContainerLow,
    surfaceContainer = NutritionScribeColors.DarkContainer,
    surfaceContainerHigh = NutritionScribeColors.DarkContainerHigh,
    surfaceContainerHighest = NutritionScribeColors.DarkContainerHighest,
    surfaceBright = NutritionScribeColors.DarkContainerHighest,
    surfaceDim = NutritionScribeColors.DarkBackground,
    inverseSurface = NutritionScribeColors.LightSurface,
    inverseOnSurface = NutritionScribeColors.LightTextPrimary,
    inversePrimary = NutritionScribeColors.LightGoalMet,
    scrim = Color.Black,
)

/**
 * Public Sans for text: plain, open, made for reading numbers-heavy public
 * information, which is what a nutrition log is. One variable font file covers
 * every weight. (SIL OFL; licence in assets/licenses.)
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun sans(weight: FontWeight) = androidx.compose.ui.text.font.Font(
    com.nutritionscribe.app.R.font.public_sans, weight,
    variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
        androidx.compose.ui.text.font.FontVariation.weight(weight.weight),
    ),
)

val AppSans = androidx.compose.ui.text.font.FontFamily(
    sans(FontWeight.Normal), sans(FontWeight.Medium), sans(FontWeight.SemiBold), sans(FontWeight.Bold),
)

/** IBM Plex Mono for amounts, so columns of numbers line up digit for digit. */
val AppMono = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(com.nutritionscribe.app.R.font.plex_mono_regular, FontWeight.Normal),
    androidx.compose.ui.text.font.Font(com.nutritionscribe.app.R.font.plex_mono_medium, FontWeight.Medium),
)

// Material's default type scale, with the title and headline styles given more
// weight. At the default Normal weight a screen's heading and its body text were
// the same colour and nearly the same heft, so nothing led the eye.
private val Base = Typography().let { t ->
    // Material's letter spacing was tuned for Roboto; Public Sans is already open,
    // and with it on top body text read as spaced out.
    fun androidx.compose.ui.text.TextStyle.sans() = copy(fontFamily = AppSans, letterSpacing = letterSpacing * 0.3f)
    t.copy(
        displayLarge = t.displayLarge.sans(), displayMedium = t.displayMedium.sans(), displaySmall = t.displaySmall.sans(),
        headlineLarge = t.headlineLarge.sans(), headlineMedium = t.headlineMedium.sans(), headlineSmall = t.headlineSmall.sans(),
        titleLarge = t.titleLarge.sans(), titleMedium = t.titleMedium.sans(), titleSmall = t.titleSmall.sans(),
        bodyLarge = t.bodyLarge.sans(), bodyMedium = t.bodyMedium.sans(), bodySmall = t.bodySmall.sans(),
        labelLarge = t.labelLarge.sans(), labelMedium = t.labelMedium.sans(), labelSmall = t.labelSmall.sans(),
    )
}
private val AppTypography = Base.copy(
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Base.labelSmall.copy(letterSpacing = Base.labelSmall.letterSpacing * 1.4),
)

/** An amount on screen: mono, so "1,364" and "114" line up in a column. */
val NumberStyle = androidx.compose.ui.text.TextStyle(fontFamily = AppMono, fontSize = 13.sp)

/** The small caps-style section label ("YOUR GOALS", "MINERALS"). */
val SectionLabelStyle = androidx.compose.ui.text.TextStyle(
    fontFamily = AppSans, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 1.1.sp,
)

// Slightly softer than Material's defaults (12dp cards), matching the rings.
private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
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
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
