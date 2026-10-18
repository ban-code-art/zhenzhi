package com.example.myapplication.ui.mine

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun EditProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val factory = remember { AccountViewModelFactory(context.applicationContext) }
    val viewModel: AccountViewModel = viewModel(key = "edit_profile", factory = factory)
    val state by viewModel.state.collectAsState()
    var showAvatarPicker by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.selectCustomAvatar(uri)
        }
    }

    SubPageScaffold(title = "个人信息", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    UserAvatar(
                        avatarId = state.avatarId,
                        name = state.username,
                        size = 88.dp,
                        avatarPath = state.avatarPath
                    )
                    Row {
                        TextButton(onClick = { showAvatarPicker = true }) {
                            Text("切换头像")
                        }
                        TextButton(
                            onClick = {
                                pickImage.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        ) {
                            Text("上传图片")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "账号：${state.username}",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = state.nickname,
                onValueChange = viewModel::onNicknameChange,
                label = { Text("昵称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (state.error != null) {
                Text(
                    text = state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Button(
                onClick = { viewModel.saveNickname(onBack) },
                enabled = !state.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .height(48.dp)
            ) {
                Text(if (state.loading) "保存中..." else "保存")
            }
        }
    }

    if (showAvatarPicker) {
        AvatarPickerDialog(
            selectedId = state.avatarId,
            onSelect = viewModel::selectAvatar,
            onDismiss = { showAvatarPicker = false }
        )
    }
}
