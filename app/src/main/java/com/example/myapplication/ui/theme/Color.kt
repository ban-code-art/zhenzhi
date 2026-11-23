package com.example.myapplication.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.myapplication.data.appearance.AppearancePalette
import com.example.myapplication.data.appearance.DefaultAppearanceSeed

internal val LocalAppDarkTheme = staticCompositionLocalOf { false }

/** Theme palette derived from the active HCT seed/scheme; preserves PaperColors for reader paper isolation. */
object AppTheme {
    val BrandGreen: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val Accent: Color @Composable @ReadOnlyComposable get() = UiRoles.extended.warning
    val AccentContainer: Color @Composable @ReadOnlyComposable get() = UiRoles.extended.warningContainer
    val Info: Color @Composable @ReadOnlyComposable get() = UiRoles.extended.info
    val InfoContainer: Color @Composable @ReadOnlyComposable get() = UiRoles.extended.infoContainer

    val LightColors: ColorScheme by lazy {
        generateUiTheme(DefaultAppearanceSeed, AppearancePalette.Vivid, dark = false, pureBlack = false).colorScheme
    }
    val PaperColors: ColorScheme by lazy {
        LightColors.copy(
            background = Color(0xFFF7F0E3), surface = Color(0xFFF7F0E3),
            surfaceVariant = Color(0xFFF1E7D6), surfaceBright = Color(0xFFFFF8EB),
            surfaceDim = Color(0xFFEDE2CD), surfaceContainerLowest = Color(0xFFFFF9F0),
            surfaceContainerLow = Color(0xFFF8F1E5), surfaceContainer = Color(0xFFF5ECDD),
            surfaceContainerHigh = Color(0xFFF1E7D6), surfaceContainerHighest = Color(0xFFEDE2CD),
        )
    }
    val DarkColors: ColorScheme by lazy {
        generateUiTheme(DefaultAppearanceSeed, AppearancePalette.Vivid, dark = true, pureBlack = false).colorScheme
    }

    val colors: ColorScheme
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme
}

// Source-compatible legacy names routed through the default seed scheme.
val Purple80 = AppTheme.DarkColors.primary
val PurpleGrey80 = AppTheme.DarkColors.secondary
val Pink80 = AppTheme.DarkColors.tertiary
val Purple40 = AppTheme.LightColors.primary
val PurpleGrey40 = AppTheme.LightColors.secondary
val Pink40 = AppTheme.LightColors.tertiary
