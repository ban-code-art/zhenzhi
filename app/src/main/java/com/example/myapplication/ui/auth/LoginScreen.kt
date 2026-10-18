package com.example.myapplication.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onGoRegister: () -> Unit
) {
    val context = LocalContext.current
    val factory = remember { AuthViewModelFactory(context.applicationContext) }
    val viewModel: AuthViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    var showForgot by remember { mutableStateOf(false) }

    AuthBackground {
        Spacer(Modifier.height(24.dp))
        AuthLogo()
        Spacer(Modifier.height(20.dp))
        AuthTitle(
            title = "针灸学习",
            subtitle = "欢迎来到中医针灸学习平台"
        )
        Spacer(Modifier.height(12.dp))
        AuthDivider()
        Spacer(Modifier.height(24.dp))

        AuthCard {
            AuthTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                placeholder = "请输入用户名",
                leadingIcon = Icons.Filled.Person
            )
            AuthTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                placeholder = "请输入密码",
                leadingIcon = Icons.Filled.Lock,
                isPassword = true
            )
            if (state.error != null) {
                Text(
                    text = state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }
            AuthPrimaryButton(
                text = if (state.loading) "登录中..." else "登录",
                enabled = !state.loading,
                onClick = { viewModel.login(onLoginSuccess) }
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onGoRegister) {
                Text("注册账号", color = AuthGreen, fontSize = 14.sp)
            }
            Box(
                Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(Color(0xFFD5E5DD))
            )
            TextButton(onClick = { showForgot = true }) {
                Text("忘记密码", color = AuthHint, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showForgot) {
        AlertDialog(
            onDismissRequest = { showForgot = false },
            confirmButton = {
                TextButton(onClick = { showForgot = false }) { Text("知道了") }
            },
            title = { Text("忘记密码") },
            text = {
                Text("本应用为本地离线账号，无法在线找回密码。请使用已注册账号登录，或重新注册。")
            }
        )
    }
}
