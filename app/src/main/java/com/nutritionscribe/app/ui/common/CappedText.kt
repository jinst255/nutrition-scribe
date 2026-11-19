package com.nutritionscribe.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * A size that grows with the phone's text setting only up to [maxScale]: for the
 * labels of fixed-height controls (the tab bar, the dock), which at 200% were cut
 * in half ("Setting", "Hold to"). Body text still grows all the way.
 */
@Composable
fun cappedSp(base: Float, maxScale: Float = 1.3f): TextUnit {
    val scale = LocalDensity.current.fontScale
    return if (scale <= maxScale) base.sp else (base * maxScale / scale).sp
}
