package com.example.myapplication.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

object ReaderTokens {
    val PageBackground: Color @Composable @ReadOnlyComposable get() = UiRoles.surface(UiSurfaceRole.Page)
    val Surface: Color @Composable @ReadOnlyComposable get() = UiRoles.surface(UiSurfaceRole.Card)
    val SurfaceMuted: Color @Composable @ReadOnlyComposable get() = UiRoles.surface(UiSurfaceRole.Block)
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.onSurface
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.onSurfaceVariant
    val Primary: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.primary
    val PrimaryDark: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.primary
    val Accent: Color @Composable @ReadOnlyComposable get() = AppTheme.Accent
    val Divider: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.outlineVariant
    val Danger: Color @Composable @ReadOnlyComposable get() = UiRoles.extended.destructive
    val NightBackground: Color @Composable @ReadOnlyComposable get() = UiRoles.surface(UiSurfaceRole.Page)
    val NightSurface: Color @Composable @ReadOnlyComposable get() = UiRoles.surface(UiSurfaceRole.Card)
    val NightText: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.onSurface
}
