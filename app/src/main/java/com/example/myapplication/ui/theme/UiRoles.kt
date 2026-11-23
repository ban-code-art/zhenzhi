package com.example.myapplication.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val LocalUiExtendedColors = staticCompositionLocalOf {
    UiExtendedColors(
        success = Color(0xFF2E7D32),
        successContainer = Color(0xFFC8E6C9),
        onSuccessContainer = Color(0xFF1B5E20),
        warning = Color(0xFF9C5700),
        warningContainer = Color(0xFFFFE0B2),
        onWarningContainer = Color(0xFF5D3400),
        destructive = Color(0xFFFF383C),
        info = Color(0xFF365FA9),
        infoContainer = Color(0xFFE8F0FE),
        onInfoContainer = Color(0xFF1C355E),
        iconContainer = Color(0xFFDFE5F9),
        iconTint = Color(0xFF3B5485),
        destructiveIconContainer = Color(0xFFF6E3E2),
        modalScrimAlpha = .20f,
        pureBlack = false,
    )
}

/** Canonical design roles backed by the active Compose palette, including fixed colors. */
@OptIn(ExperimentalMaterial3Api::class)
internal enum class UiColorRole(val colorIn: (ColorScheme) -> Color) {
    Primary({ it.primary }),
    OnPrimary({ it.onPrimary }),
    PrimaryContainer({ it.primaryContainer }),
    OnPrimaryContainer({ it.onPrimaryContainer }),
    PrimaryFixed({ it.primaryFixed }),
    PrimaryFixedDim({ it.primaryFixedDim }),
    OnPrimaryFixed({ it.onPrimaryFixed }),
    OnPrimaryFixedVariant({ it.onPrimaryFixedVariant }),
    Secondary({ it.secondary }),
    OnSecondary({ it.onSecondary }),
    SecondaryContainer({ it.secondaryContainer }),
    OnSecondaryContainer({ it.onSecondaryContainer }),
    SecondaryFixed({ it.secondaryFixed }),
    SecondaryFixedDim({ it.secondaryFixedDim }),
    OnSecondaryFixed({ it.onSecondaryFixed }),
    OnSecondaryFixedVariant({ it.onSecondaryFixedVariant }),
    Tertiary({ it.tertiary }),
    OnTertiary({ it.onTertiary }),
    TertiaryContainer({ it.tertiaryContainer }),
    OnTertiaryContainer({ it.onTertiaryContainer }),
    TertiaryFixed({ it.tertiaryFixed }),
    TertiaryFixedDim({ it.tertiaryFixedDim }),
    OnTertiaryFixed({ it.onTertiaryFixed }),
    OnTertiaryFixedVariant({ it.onTertiaryFixedVariant }),
    Error({ it.error }),
    OnError({ it.onError }),
    ErrorContainer({ it.errorContainer }),
    OnErrorContainer({ it.onErrorContainer }),
    Surface({ it.surface }),
    OnSurface({ it.onSurface }),
    SurfaceDim({ it.surfaceDim }),
    SurfaceBright({ it.surfaceBright }),
    SurfaceContainerLowest({ it.surfaceContainerLowest }),
    SurfaceContainerLow({ it.surfaceContainerLow }),
    SurfaceContainer({ it.surfaceContainer }),
    SurfaceContainerHigh({ it.surfaceContainerHigh }),
    SurfaceContainerHighest({ it.surfaceContainerHighest }),
    OnSurfaceVariant({ it.onSurfaceVariant }),
    Outline({ it.outline }),
    OutlineVariant({ it.outlineVariant }),
    Shadow({ Color.Black }),
    Scrim({ it.scrim }),
    InverseSurface({ it.inverseSurface }),
    OnInverseSurface({ it.inverseOnSurface }),
    InversePrimary({ it.inversePrimary }),
    SurfaceTint({ it.primary }),
}

internal enum class UiSurfaceRole { Page, Card, Block, Inset, Chrome }

internal object UiRoles {
    val dark: Boolean @Composable @ReadOnlyComposable get() = LocalAppDarkTheme.current
    val foreground: Color @Composable @ReadOnlyComposable get() = color(UiColorRole.OnSurface)
    val pageForeground: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onBackground
    val pageSupporting: Color
        @Composable @ReadOnlyComposable get() = with(MaterialTheme.colorScheme) {
            if (contrastRatio(onSurfaceVariant, background) >= 4.5f) onSurfaceVariant else onBackground
        }
    val accent: Color @Composable @ReadOnlyComposable get() = color(UiColorRole.Primary)
    val extended: UiExtendedColors @Composable @ReadOnlyComposable get() = LocalUiExtendedColors.current
    val iconContainer: Color @Composable @ReadOnlyComposable get() = extended.iconContainer
    val iconTint: Color @Composable @ReadOnlyComposable get() = extended.iconTint
    val destructiveIconContainer: Color @Composable @ReadOnlyComposable get() = extended.destructiveIconContainer

    @Composable
    @ReadOnlyComposable
    fun color(role: UiColorRole): Color = role.colorIn(MaterialTheme.colorScheme)

    @Composable
    @ReadOnlyComposable
    fun surface(role: UiSurfaceRole): Color = when (role) {
        UiSurfaceRole.Page -> MaterialTheme.colorScheme.background
        UiSurfaceRole.Card -> color(if (dark) UiColorRole.SurfaceContainer else UiColorRole.SurfaceBright)
        UiSurfaceRole.Block -> color(if (dark) UiColorRole.SurfaceContainerHigh else UiColorRole.SurfaceContainer)
        UiSurfaceRole.Inset -> color(UiColorRole.SurfaceContainerHighest)
        UiSurfaceRole.Chrome -> color(if (dark) UiColorRole.SurfaceContainerLow else UiColorRole.SurfaceContainerHighest)
    }

    @Composable
    @ReadOnlyComposable
    fun text(base: TextStyle): TextStyle {
        val family = MaterialTheme.typography.bodyLarge.fontFamily ?: LocalTextStyle.current.fontFamily
        return if (family != null && base.fontFamily == null) base.copy(fontFamily = family) else base
    }

    val actionText = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val rowText = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val pageTitle = rowText.copy(fontWeight = FontWeight.SemiBold)
}
