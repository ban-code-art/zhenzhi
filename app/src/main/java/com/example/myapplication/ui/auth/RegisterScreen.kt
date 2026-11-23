package com.example.myapplication.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.mine.GlassPageBarHeight
import com.example.myapplication.ui.mine.SubPageScaffold
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles

@Composable
fun RegisterScreen(
    onRegisterSuccess: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) { AuthViewModelFactory(context.applicationContext) }
    val viewModel: AuthViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    RegisterScreenContent(
        state = state,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onNicknameChange = viewModel::onNicknameChange,
        onRegister = { viewModel.register(onRegisterSuccess) },
        onBack = onBack,
    )
}

@Composable
internal fun RegisterScreenContent(
    state: AuthViewModel.UiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onNicknameChange: (String) -> Unit,
    onRegister: () -> Unit,
    onBack: () -> Unit,
) {
    var confirm by rememberSaveable { mutableStateOf("") }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    val passwordFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val nicknameFocus = remember { FocusRequester() }
    val requestBack = unifiedBackRequest(BackState(page = BackPage.CHILD, imeVisible = isBackImeVisible())) { decision ->
        if (decision == BackDecision.PopPage) onBack()
    }
    val error = localError ?: state.error
    val submit = {
        if (!state.loading) {
            if (state.password != confirm) {
                localError = "两次输入的密码不一致"
            } else {
                localError = null
                onRegister()
            }
        }
    }

    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        SubPageScaffold(
            title = "注册账号",
            onBack = requestBack,
            glassBack = true,
            actionId = "auth.register.back",
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = GlassPageBarHeight + 8.dp, end = 16.dp, bottom = 32.dp)
                    .testTag("auth-register-scroll"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "创建你的针灸学习账号",
                        modifier = Modifier.fillMaxWidth(),
                        style = UiRoles.text(MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    AuthCard {
                        AuthTextField(
                            value = state.username,
                            onValueChange = {
                                localError = null
                                onUsernameChange(it)
                            },
                            placeholder = "用户名（3–20 位字母/数字/下划线）",
                            leadingIcon = UiIcons.Person,
                            imeAction = ImeAction.Next,
                            testTag = "auth-username",
                            enabled = !state.loading,
                            onNext = { passwordFocus.requestFocus() },
                        )
                        AuthTextField(
                            value = state.password,
                            onValueChange = {
                                localError = null
                                onPasswordChange(it)
                            },
                            placeholder = "密码（6–20 位，含字母和数字）",
                            leadingIcon = UiIcons.Lock,
                            isPassword = true,
                            imeAction = ImeAction.Next,
                            modifier = Modifier.focusRequester(passwordFocus),
                            testTag = "auth-password",
                            enabled = !state.loading,
                            isError = localError != null,
                            onNext = { confirmFocus.requestFocus() },
                        )
                        AuthTextField(
                            value = confirm,
                            onValueChange = {
                                confirm = it
                                localError = null
                            },
                            placeholder = "确认密码",
                            leadingIcon = UiIcons.Lock,
                            isPassword = true,
                            imeAction = ImeAction.Next,
                            modifier = Modifier.focusRequester(confirmFocus),
                            testTag = "auth-register-confirm",
                            enabled = !state.loading,
                            isError = localError != null,
                            onNext = { nicknameFocus.requestFocus() },
                        )
                        AuthTextField(
                            value = state.nickname,
                            onValueChange = {
                                localError = null
                                onNicknameChange(it)
                            },
                            placeholder = "昵称（可选）",
                            leadingIcon = UiIcons.Person,
                            imeAction = ImeAction.Done,
                            modifier = Modifier.focusRequester(nicknameFocus),
                            testTag = "auth-register-nickname",
                            enabled = !state.loading,
                            onDone = submit,
                        )
                        error?.let { AuthErrorText(it) }
                        AuthPrimaryButton(
                            text = if (state.loading) "注册中..." else "注册",
                            enabled = !state.loading,
                            onClick = submit,
                            loading = state.loading,
                            modifier = Modifier.testTag("auth-register-submit"),
                            actionId = "auth.register.submit",
                        )
                    }
                    UiAction(
                        label = "已有账号？返回登录",
                        onClick = requestBack,
                        modifier = Modifier.testTag("auth-register-back-login"),
                        actionId = "auth.register.login",
                    )
                }
            }
        }
    }
}
