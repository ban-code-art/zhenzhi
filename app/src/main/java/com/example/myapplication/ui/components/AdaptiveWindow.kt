package com.example.myapplication.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Width and text-scale policy shared by primary Compose surfaces. */
data class AdaptiveWindowInfo(
    val widthDp: Int,
    val fontScale: Float,
    val isCompact: Boolean,
    val isMedium: Boolean,
    val isExpanded: Boolean,
    val contentMaxWidth: Dp,
    val preferStackedControls: Boolean,
)

@Composable
fun rememberAdaptiveWindowInfo(): AdaptiveWindowInfo {
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    val widthDp = configuration.screenWidthDp
    return remember(widthDp, fontScale) {
        val compact = widthDp < 600
        val medium = widthDp in 600..839
        AdaptiveWindowInfo(
            widthDp = widthDp,
            fontScale = fontScale,
            isCompact = compact,
            isMedium = medium,
            isExpanded = widthDp >= 840,
            contentMaxWidth = when {
                widthDp >= 1200 -> 1040.dp
                widthDp >= 840 -> 920.dp
                else -> Dp.Unspecified
            },
            // Stacking preserves readable labels when accessibility text is enlarged.
            preferStackedControls = compact || fontScale >= 1.25f,
        )
    }
}
