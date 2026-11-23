package com.example.myapplication.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.components.LocalUiBackdrop
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiBackdrop
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

internal val GlassPageBarHeight = 72.dp

@Composable
internal fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    glassBack: Boolean = false,
    actionId: String = "settings.back",
    trailing: (@Composable RowScope.(UiBackdrop?) -> Unit)? = null,
    bottomOverlay: (@Composable androidx.compose.foundation.layout.BoxScope.(UiBackdrop?) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val background = UiRoles.surface(UiSurfaceRole.Page)
    val foreground = UiRoles.pageForeground
    val titleStyle = UiRoles.text(UiRoles.pageTitle)
    if (glassBack) {
        val backdrop = rememberUiBackdrop()
        Box(Modifier.fillMaxSize().background(background).statusBarsPadding()) {
            UiGlassBackdrop(backdrop, Modifier.fillMaxSize()) { content() }
            CompositionLocalProvider(LocalUiBackdrop provides backdrop) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(GlassPageBarHeight)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    background,
                                    background.copy(alpha = .94f),
                                    background.copy(alpha = .72f),
                                    Color.Transparent,
                                ),
                            ),
                        )
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UiAction(
                        label = "返回",
                        icon = UiIcons.Back,
                        onClick = onBack,
                        modifier = Modifier.testTag("settings-glass-back"),
                        backdrop = backdrop,
                        actionId = actionId,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = titleStyle,
                        color = foreground,
                    )
                    trailing?.invoke(this, backdrop)
                }
                bottomOverlay?.invoke(this, backdrop)
            }
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GlassPageBarHeight)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = UiIcons.Back,
                    contentDescription = "返回",
                    modifier = Modifier.size(24.dp),
                    tint = foreground,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = titleStyle,
                color = foreground,
            )
            trailing?.invoke(this, null)
        }
        content()
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = TextSecondary)
    }
}
