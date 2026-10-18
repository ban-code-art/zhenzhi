package com.example.myapplication.ui.auth

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun RegisterScreen(
    onRegisterSuccess: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val factory = remember { AuthViewModelFactory(context.applicationContext) }
    val viewModel: AuthViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    var confirm by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    AuthBackground {
        Spacer(Modifier.height(24.dp))
        AuthLogo()
        Spacer(Modifier.height(20.dp))
        AuthTitle(
            title = "注册账号",
            subtitle = "创建你的针灸学习账号"
        )
        Spacer(Modifier.height(12.dp))
        AuthDivider()
        Spacer(Modifier.height(24.dp))

        AuthCard {
            AuthTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                placeholder = "用户名（3–20 位字母/数字/下划线）",
                leadingIcon = Icons.Filled.Person
            )
            AuthTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                placeholder = "密码（6–20 位，含字母和数字）",
                leadingIcon = Icons.Filled.Lock,
                isPassword = true
            )
            AuthTextField(
                value = confirm,
                onValueChange = {
                    confirm = it
                    localError = null
                },
                placeholder = "确认密码",
                leadingIcon = Icons.Filled.Lock,
                isPassword = true
            )
            AuthTextField(
                value = state.nickname,
                onValueChange = viewModel::onNicknameChange,
                placeholder = "昵称（可选）",
                leadingIcon = Icons.Filled.Person
            )
            if ((localError ?: state.error) != null) {
                Text(
                    text = (localError ?: state.error)!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }
            AuthPrimaryButton(
                text = if (state.loading) "注册中..." else "注册",
                enabled = !state.loading,
                onClick = {
                    if (state.password != confirm) {
                        localError = "两次输入的密码不一致"
                    } else {
                        localError = null
                        viewModel.register(onRegisterSuccess)
                    }
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) {
            Text("已有账号？返回登录", color = AuthGreen, fontSize = 14.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}
