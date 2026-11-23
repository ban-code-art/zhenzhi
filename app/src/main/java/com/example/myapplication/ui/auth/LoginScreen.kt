package com.example.myapplication.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiAlertDecision
import com.example.myapplication.ui.components.UiAlertHost
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onGoRegister: () -> Unit,
) {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) { AuthViewModelFactory(context.applicationContext) }
    val viewModel: AuthViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    LoginScreenContent(
        state = state,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onLogin = { viewModel.login(onLoginSuccess) },
        onGoRegister = onGoRegister,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LoginScreenContent(
    state: AuthViewModel.UiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onGoRegister: () -> Unit,
) {
    val passwordFocus = remember { FocusRequester() }
    var showForgot by remember { mutableStateOf(false) }
    val submit = { if (!state.loading) onLogin() }

    AuthBackground(Modifier.testTag("auth-login-scroll")) {
        AuthLogo()
        Spacer(Modifier.height(20.dp))
        AuthTitle(
            title = "针灸学习",
            subtitle = "欢迎来到中医针灸学习平台",
        )
        Spacer(Modifier.height(24.dp))

        AuthCard {
            AuthTextField(
                value = state.username,
                onValueChange = onUsernameChange,
                placeholder = "请输入用户名",
                leadingIcon = UiIcons.Person,
                imeAction = ImeAction.Next,
                testTag = "auth-username",
                enabled = !state.loading,
                isError = state.error != null,
                onNext = { passwordFocus.requestFocus() },
            )
            AuthTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                placeholder = "请输入密码",
                leadingIcon = UiIcons.Lock,
                isPassword = true,
                imeAction = ImeAction.Done,
                modifier = Modifier.focusRequester(passwordFocus),
                testTag = "auth-password",
                enabled = !state.loading,
                isError = state.error != null,
                onDone = submit,
            )
            state.error?.let { AuthErrorText(it) }
            AuthPrimaryButton(
                text = if (state.loading) "登录中..." else "登录",
                enabled = !state.loading,
                onClick = submit,
                loading = state.loading,
                modifier = Modifier.testTag("auth-login-submit"),
                actionId = "auth.login.submit",
            )
        }

        Spacer(Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UiAction(
                label = "注册账号",
                onClick = onGoRegister,
                enabled = !state.loading,
                modifier = Modifier.testTag("auth-go-register"),
                actionId = "auth.login.register",
            )
            UiAction(
                label = "忘记密码",
                onClick = { showForgot = true },
                enabled = !state.loading,
                modifier = Modifier.testTag("auth-forgot-password"),
                actionId = "auth.login.forgot",
            )
        }
    }

    if (showForgot) ForgotPasswordDialog(onDismiss = { showForgot = false })
}

private const val ForgotPasswordOwner = "auth.forgot-password"

@Composable
private fun ForgotPasswordDialog(onDismiss: () -> Unit) {
    val decision = remember { UiAlertDecision(ForgotPasswordOwner) }
    val exit = decision.exit
    val latestDismiss by rememberUpdatedState(onDismiss)
    UiAlertHost(
        title = "忘记密码",
        ownerKey = ForgotPasswordOwner,
        onDismissRequest = { scale, pull -> decision.request(false, scale, pull) },
        modifier = Modifier.testTag("auth-forgot-dialog"),
        exitRequested = exit != null,
        frozenScale = exit?.scale,
        frozenPull = exit?.pull,
        onVisibilitySettled = {
            if (decision.consume(ForgotPasswordOwner) != null) latestDismiss()
        },
    ) { interaction, currentScale, currentPull ->
        Text(
            text = "忘记密码",
            modifier = Modifier.fillMaxWidth().semantics { heading() },
            style = UiRoles.text(UiRoles.pageTitle),
            color = UiRoles.foreground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "本应用为本地离线账号，无法在线找回密码。请使用已注册账号登录，或重新注册。",
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp),
            style = UiRoles.text(MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        UiAction(
            label = "知道了",
            onClick = { decision.request(false, currentScale(), currentPull()) },
            modifier = Modifier.align(Alignment.CenterHorizontally).testTag("auth-forgot-close"),
            enabled = exit == null,
            inheritedPane = true,
            interactionSource = interaction,
            animatePress = false,
            foreground = UiRoles.accent,
            actionId = "auth.forgot.close",
        )
    }
}
