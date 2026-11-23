package com.example.myapplication.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(UiRoles.surface(UiSurfaceRole.Page)).systemBarsPadding()) {
        SubPageScaffold("消息通知", onBack, glassBack = true, actionId = "mine.notifications.back") {
            Box(
                Modifier.fillMaxSize().padding(top = GlassPageBarHeight + 8.dp, bottom = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.padding(24.dp).testTag("notifications-empty"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        Modifier.size(64.dp).background(UiRoles.iconContainer, RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(UiIcons.Notifications, null, Modifier.size(28.dp), tint = UiRoles.iconTint)
                    }
                    Text(
                        "暂无新消息", style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                        color = UiRoles.pageSupporting,
                    )
                }
            }
        }
    }
}
