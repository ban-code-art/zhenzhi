package com.example.myapplication.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.auth.AuthErrorText
import com.example.myapplication.ui.auth.AuthTextField
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

@Composable
fun ChangePasswordScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) { AccountViewModelFactory(context.applicationContext) }
    val viewModel: AccountViewModel = viewModel(key = "change_password", factory = factory)
    val state by viewModel.state.collectAsState()

    var oldPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var localError by rememberSaveable { mutableStateOf<String?>(null) }
    val newPasswordFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val completion = rememberAccountPageCompletion(onBack)
    val requestBack = unifiedBackRequest(BackState(page = BackPage.CHILD, imeVisible = isBackImeVisible())) { decision ->
        if (decision == BackDecision.PopPage) {
            completion.revoke()
            onBack()
        }
    }
    val submit = {
        if (!state.loading) {
            if (newPassword != confirm) {
                localError = "两次输入的新密码不一致"
            } else {
                localError = null
                viewModel.changePassword(oldPassword, newPassword, confirm, completion.capture())
            }
        }
    }
    val minButtonHeight = maxOf(52.dp, with(LocalDensity.current) { UiRoles.actionText.lineHeight.toDp() } + 24.dp)

    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        SubPageScaffold(
            title = "修改密码",
            onBack = requestBack,
            glassBack = true,
            actionId = "account.password.back",
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = GlassPageBarHeight + 8.dp, end = 16.dp, bottom = 32.dp)
                    .testTag("account-password-scroll"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Card(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AuthTextField(
                            value = oldPassword,
                            onValueChange = {
                                oldPassword = it
                                localError = null
                            },
                            placeholder = "请输入原密码",
                            leadingIcon = UiIcons.Lock,
                            isPassword = true,
                            imeAction = ImeAction.Next,
                            testTag = "account-password-old",
                            enabled = !state.loading,
                            onNext = { newPasswordFocus.requestFocus() },
                        )
                        AuthTextField(
                            value = newPassword,
                            onValueChange = {
                                newPassword = it
                                localError = null
                            },
                            placeholder = "新密码（6–20 位，含字母和数字）",
                            leadingIcon = UiIcons.Lock,
                            isPassword = true,
                            imeAction = ImeAction.Next,
                            modifier = Modifier.focusRequester(newPasswordFocus),
                            testTag = "account-password-new",
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
                            placeholder = "确认新密码",
                            leadingIcon = UiIcons.Lock,
                            isPassword = true,
                            imeAction = ImeAction.Done,
                            modifier = Modifier.focusRequester(confirmFocus),
                            testTag = "account-password-confirm",
                            enabled = !state.loading,
                            isError = localError != null,
                            onDone = submit,
                        )
                        (localError ?: state.error)?.let { AuthErrorText(it) }
                        UiAction(
                            label = if (state.loading) "提交中..." else "确认修改",
                            onClick = submit,
                            modifier = Modifier.fillMaxWidth().heightIn(min = minButtonHeight).testTag("account-password-submit"),
                            enabled = !state.loading,
                            filled = true,
                            busy = state.loading,
                            retainFilledWhenBusy = true,
                            visualModifier = Modifier.fillMaxWidth().heightIn(min = minButtonHeight),
                            actionId = "account.password.submit",
                        )
                    }
                }
            }
        }
    }
}
