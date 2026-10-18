package com.example.myapplication.ui.mine

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.auth.AuthBackground
import com.example.myapplication.ui.auth.AuthCard
import com.example.myapplication.ui.auth.AuthDarkGreen
import com.example.myapplication.ui.auth.AuthPrimaryButton
import com.example.myapplication.ui.auth.AuthTextField

@Composable
fun ChangePasswordScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val factory = remember { AccountViewModelFactory(context.applicationContext) }
    val viewModel: AccountViewModel = viewModel(key = "change_password", factory = factory)
    val state by viewModel.state.collectAsState()

    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    AuthBackground {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AuthDarkGreen
                )
            }
            Text(
                text = "修改密码",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = AuthDarkGreen
            )
        }
        Spacer(Modifier.height(20.dp))

        AuthCard {
            AuthTextField(
                value = oldPassword,
                onValueChange = {
                    oldPassword = it
                    localError = null
                },
                placeholder = "请输入原密码",
                leadingIcon = Icons.Filled.Lock,
                isPassword = true
            )
            AuthTextField(
                value = newPassword,
                onValueChange = {
                    newPassword = it
                    localError = null
                },
                placeholder = "新密码（6–20 位，含字母和数字）",
                leadingIcon = Icons.Filled.Lock,
                isPassword = true
            )
            AuthTextField(
                value = confirm,
                onValueChange = {
                    confirm = it
                    localError = null
                },
                placeholder = "确认新密码",
                leadingIcon = Icons.Filled.Lock,
                isPassword = true
            )
            if ((localError ?: state.error) != null) {
                Text(
                    text = (localError ?: state.error)!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }
            AuthPrimaryButton(
                text = if (state.loading) "提交中..." else "确认修改",
                enabled = !state.loading,
                onClick = {
                    if (newPassword != confirm) {
                        localError = "两次输入的新密码不一致"
                    } else {
                        localError = null
                        viewModel.changePassword(oldPassword, newPassword, confirm, onBack)
                    }
                }
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
