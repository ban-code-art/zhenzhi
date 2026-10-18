package com.example.myapplication.ui.mine

import androidx.compose.runtime.Composable

@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    SubPageScaffold(title = "消息通知", onBack = onBack) {
        EmptyHint("暂无新消息")
    }
}
