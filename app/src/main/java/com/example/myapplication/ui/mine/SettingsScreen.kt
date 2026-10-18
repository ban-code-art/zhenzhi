package com.example.myapplication.ui.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigate: (MineDestination) -> Unit,
    onLogout: () -> Unit
) {
    var showAbout by remember { mutableStateOf(false) }

    SubPageScaffold(title = "设置", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            SettingRow("个人信息") { onNavigate(MineDestination.EditProfile) }
            HorizontalDivider(color = Color(0xFFE8EEEA))
            SettingRow("修改密码") { onNavigate(MineDestination.ChangePassword) }
            HorizontalDivider(color = Color(0xFFE8EEEA))
            SettingRow("关于") { showAbout = true }
            HorizontalDivider(color = Color(0xFFE8EEEA))
            SettingRow("退出登录", isDanger = true, onClick = onLogout)
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("知道了") }
            },
            title = { Text("关于") },
            text = {
                Column {
                    Text("针灸学习 v1.0.0", color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "本应用内针灸知识仅供学习参考，不构成任何医疗建议或诊疗依据。",
                        color = TextSecondary
                    )
                }
            }
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    isDanger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (isDanger) Red else TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color(0xFFB6BFBB)
        )
    }
}
