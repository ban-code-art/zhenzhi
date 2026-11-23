package com.example.myapplication.ui.mine

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackLayer
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) { AccountViewModelFactory(context.applicationContext) }
    val viewModel: AccountViewModel = viewModel(key = "edit_profile", factory = factory)
    val state by viewModel.state.collectAsState()
    var showAvatarPicker by rememberSaveable { mutableStateOf(false) }
    // Save only delivery routing here. The full (userId, epoch) ticket remains in the retained VM;
    // after process recreation a restored token cannot create fresh authority for an old result.
    var activePickToken by rememberSaveable { mutableStateOf<String?>(null) }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val deliveredToken = activePickToken
        activePickToken = null
        viewModel.consumeAvatarPick(deliveredToken, uri)
    }
    val returnFromPage: () -> Unit = {
        viewModel.cancelAvatarPick(activePickToken)
        activePickToken = null
        showAvatarPicker = false
        onBack()
    }
    val completion = rememberAccountPageCompletion(returnFromPage)
    val requestBack = unifiedBackRequest(
        BackState(
            page = BackPage.CHILD,
            imeVisible = isBackImeVisible(),
            topLayer = if (showAvatarPicker) BackLayer.Overlay("profile-avatar-picker") else null,
        ),
    ) { decision ->
        when (decision) {
            BackDecision.PopPage -> {
                completion.revoke()
                returnFromPage()
            }
            is BackDecision.CloseLayer -> showAvatarPicker = false
            else -> Unit
        }
    }
    LaunchedEffect(state.sessionIdentity) { showAvatarPicker = false }

    val available = state.sessionIdentity?.userId != null && state.username.isNotBlank() &&
        !state.loading && !state.avatarPickerPending && activePickToken == null
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        SubPageScaffold(title = "个人信息", onBack = requestBack, glassBack = true, actionId = "profile.back") {
            Column(
                modifier = Modifier.fillMaxSize()
                    .background(UiRoles.surface(UiSurfaceRole.Page))
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = GlassPageBarHeight + 8.dp, bottom = 24.dp)
                    .testTag("profile-page"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProfileCard(title = "头像") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        UserAvatar(
                            avatarId = state.avatarId,
                            name = state.nickname,
                            username = state.username,
                            size = 88.dp,
                            avatarPath = state.avatarPath,
                            modifier = Modifier.testTag("profile-avatar"),
                        )
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        UiAction(
                            label = "切换头像",
                            onClick = { showAvatarPicker = true },
                            enabled = available,
                            actionId = "profile.choose-avatar",
                            animatePress = false,
                            modifier = Modifier.testTag("profile-choose-avatar"),
                        )
                        UiAction(
                            label = "上传图片",
                            onClick = {
                                val ticket = viewModel.beginAvatarPick()
                                if (ticket != null) {
                                    activePickToken = ticket.token
                                    try {
                                        pickImage.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    } catch (_: Exception) {
                                        activePickToken = null
                                        viewModel.cancelAvatarPick(ticket.token, launchFailed = true)
                                    }
                                }
                            },
                            enabled = available,
                            actionId = "profile.upload-avatar",
                            animatePress = false,
                            modifier = Modifier.testTag("profile-upload-avatar"),
                        )
                    }
                    state.avatarError?.let { ProfileFeedback(it, "profile-avatar-feedback") }
                }
                ProfileCard(title = "账号") {
                    Text(
                        text = state.username.ifBlank { if (state.loading) "正在读取…" else "未登录" },
                        style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("profile-username"),
                    )
                }
                ProfileCard(title = "昵称") {
                    val inset = UiRoles.surface(UiSurfaceRole.Inset)
                    OutlinedTextField(
                        value = state.nickname,
                        onValueChange = viewModel::onNicknameChange,
                        label = { Text("昵称") },
                        singleLine = true,
                        enabled = available,
                        isError = state.error != null,
                        shape = RoundedCornerShape(16.dp),
                        textStyle = UiRoles.text(MaterialTheme.typography.bodyLarge),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = inset,
                            unfocusedContainerColor = inset,
                            disabledContainerColor = inset,
                        ),
                        leadingIcon = {
                            Icon(UiIcons.Person, contentDescription = null, tint = UiRoles.iconTint, modifier = Modifier.size(20.dp))
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (available) viewModel.saveNickname(completion.capture()) }),
                        modifier = Modifier.fillMaxWidth().testTag("profile-nickname"),
                    )
                    state.error?.let { ProfileFeedback(it, "profile-save-feedback") }
                    Text(
                        text = "昵称最多 20 个字符，留空时使用账号名",
                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                UiAction(
                    label = if (state.loading) "保存中…" else "保存",
                    onClick = { viewModel.saveNickname(completion.capture()) },
                    enabled = available,
                    busy = state.loading,
                    filled = true,
                    modifier = Modifier.fillMaxWidth().testTag("profile-save"),
                    visualModifier = Modifier.fillMaxWidth(),
                    actionId = "profile.save",
                )
            }
        }
    }

    if (showAvatarPicker) {
        AvatarPickerDialog(
            selectedId = state.avatarId,
            hasCustomAvatar = !state.avatarPath.isNullOrBlank(),
            name = state.nickname,
            username = state.username,
            onSelect = viewModel::selectAvatar,
            onDismiss = { showAvatarPicker = false },
            enabled = available,
        )
    }
}

@Composable
private fun ProfileCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = title,
                style = UiRoles.text(UiRoles.pageTitle),
                color = UiRoles.foreground,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun ProfileFeedback(message: String, tag: String) {
    Text(
        text = message,
        style = UiRoles.text(MaterialTheme.typography.bodyMedium),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
    )
}
