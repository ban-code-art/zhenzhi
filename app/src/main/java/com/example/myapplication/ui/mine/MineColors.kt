package com.example.myapplication.ui.mine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.example.myapplication.ui.theme.AppTheme
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

internal val PageBackground: Color @Composable @ReadOnlyComposable get() = UiRoles.surface(UiSurfaceRole.Page)
internal val CardWhite: Color @Composable @ReadOnlyComposable get() = UiRoles.surface(UiSurfaceRole.Card)
internal val TextPrimary: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.onSurface
internal val TextSecondary: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.onSurfaceVariant
internal val PrimaryGreen: Color @Composable @ReadOnlyComposable get() = UiRoles.extended.success
internal val GreenBg: Color @Composable @ReadOnlyComposable get() = UiRoles.extended.successContainer
internal val Blue: Color @Composable @ReadOnlyComposable get() = UiRoles.extended.info
internal val BlueBg: Color @Composable @ReadOnlyComposable get() = UiRoles.extended.infoContainer
internal val Orange: Color @Composable @ReadOnlyComposable get() = UiRoles.extended.warning
internal val OrangeBg: Color @Composable @ReadOnlyComposable get() = UiRoles.extended.warningContainer
internal val Gray: Color @Composable @ReadOnlyComposable get() = AppTheme.colors.onSurfaceVariant
internal val GrayBg: Color @Composable @ReadOnlyComposable get() = UiRoles.surface(UiSurfaceRole.Inset)
internal val Red: Color @Composable @ReadOnlyComposable get() = UiRoles.extended.destructive
internal val HairlineDivider: Color @Composable @ReadOnlyComposable get() = UiRoles.foreground.copy(alpha = .14f)
