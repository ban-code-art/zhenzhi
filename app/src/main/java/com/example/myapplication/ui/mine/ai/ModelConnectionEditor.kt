package com.example.myapplication.ui.mine.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.myapplication.domain.ai.WireProtocol
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.mine.HairlineDivider
import com.example.myapplication.ui.mine.TextPrimary
import com.example.myapplication.ui.mine.TextSecondary
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

/** Fields edit only the draft. Candidate discovery does not imply a successful capability test. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ModelConnectionEditor(state: AiConfigUiState, viewModel: AiConfigViewModel) {
    val enabled = !state.saving
    val modelFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    AiSectionCard {
        Text(
            text = "连接",
            style = UiRoles.text(UiRoles.pageTitle),
            color = TextPrimary,
        )
        Text(
            text = "协议",
            style = UiRoles.text(MaterialTheme.typography.labelMedium),
            color = TextSecondary,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WireProtocol.entries.forEach { protocol ->
                AiOptionPill(
                    label = protocol.shortLabel(),
                    selected = state.draft.protocol == protocol,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { viewModel.editDraft { it.copy(protocol = protocol) } },
                )
            }
        }

        EndpointConnectionFields(state, viewModel)

        OutlinedTextField(
            value = state.draft.apiKey,
            onValueChange = viewModel::editApiKey,
            label = { Text("API Key（本地服务可留空）") },
            textStyle = UiRoles.text(MaterialTheme.typography.bodyLarge),
            supportingText = {
                Text(
                    text = when {
                        state.draft.clearApiKey -> "保存后将删除密钥；本地服务可以不使用密钥。"
                        state.draft.apiKey.isNotEmpty() -> "保存后将使用新输入的密钥。"
                        state.draft.secretRef != null -> "已有密钥，留空保持；已有密钥不会回填到输入框。"
                        else -> "未设置密钥；本地无密钥服务也可以保存。"
                    },
                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = aiFieldColors(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("ai-api-key"),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UiAction(
                label = "删除密钥",
                onClick = viewModel::clearApiKey,
                enabled = enabled && !state.draft.clearApiKey &&
                    (state.draft.secretRef != null || state.draft.apiKey.isNotEmpty()),
                foreground = UiRoles.extended.destructive,
                actionId = "ai.key.clear",
                modifier = Modifier.testTag("ai-clear-key"),
            )
            if (state.draft.clearApiKey || state.draft.apiKey.isNotEmpty()) {
                UiAction(
                    label = if (state.draft.secretRef != null) "保持已有密钥" else "撤销密钥修改",
                    onClick = viewModel::keepApiKey,
                    enabled = enabled,
                    actionId = "ai.key.keep",
                    modifier = Modifier.testTag("ai-keep-key"),
                )
            }
        }

        AiSwitchBlock(
            title = "启用该配置",
            checked = state.draft.enabled,
            enabled = enabled,
            onCheckedChange = { value -> viewModel.editDraft { it.copy(enabled = value) } },
            modifier = Modifier.testTag("ai-enabled"),
        )
    }

    AiSectionCard {
        Text(
            text = "模型",
            style = UiRoles.text(UiRoles.pageTitle),
            color = TextPrimary,
        )
        OutlinedTextField(
            value = state.draft.model,
            onValueChange = { value -> viewModel.editDraft { it.copy(model = value) } },
            label = { Text("Model") },
            textStyle = UiRoles.text(MaterialTheme.typography.bodyLarge),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = aiFieldColors(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().focusRequester(modelFocus).testTag("ai-model"),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UiAction(
                label = if (state.modelsLoading) "取消获取" else "获取模型",
                onClick = { if (state.modelsLoading) viewModel.cancelModels() else viewModel.fetchModels() },
                enabled = enabled && (state.modelsLoading || state.draft.endpoint.isNotBlank()),
                actionId = "ai.models.fetch",
                modifier = Modifier.testTag("ai-fetch-models"),
            )
            UiAction(
                label = "手动输入模型",
                onClick = {
                    modelFocus.requestFocus()
                    keyboard?.show()
                },
                enabled = enabled,
                actionId = "ai.models.manual",
                modifier = Modifier.testTag("ai-manual-model"),
            )
        }
        if (state.modelsLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = UiRoles.accent,
                )
                Text(
                    text = "正在获取候选模型，仍可手动输入。",
                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                    color = TextSecondary,
                )
            }
        }
        if (state.models.isEmpty()) {
            Text(
                text = "可直接手动输入模型；获取失败或没有候选时，已填写的模型仍会保留。",
                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                color = TextSecondary,
            )
        } else {
            Text(
                text = "候选模型（共 ${state.models.size}，未经验证）",
                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                color = TextSecondary,
            )
            OutlinedTextField(
                value = state.modelQuery,
                onValueChange = viewModel::editModelQuery,
                label = { Text("搜索候选模型") },
                textStyle = UiRoles.text(MaterialTheme.typography.bodyLarge),
                enabled = enabled,
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = aiFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            val filtered = ModelListFilter.filter(state.models, state.modelQuery)
            if (filtered.isEmpty()) {
                Text(
                    text = "没有匹配的候选，可手动输入模型。",
                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                    color = TextSecondary,
                )
            } else {
                Surface(
                    color = UiRoles.surface(UiSurfaceRole.Block),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth().selectableGroup()) {
                        val visibleModels = ModelListFilter.visible(filtered, state.modelVisibleCount)
                        visibleModels.forEachIndexed { index, model ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    thickness = 0.5.dp,
                                    color = HairlineDivider,
                                )
                            }
                            val selected = model == state.draft.model
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .sizeIn(minHeight = 48.dp)
                                    .selectable(
                                        selected = selected,
                                        enabled = enabled,
                                        role = Role.RadioButton,
                                        onClick = { viewModel.selectModel(model) },
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = model,
                                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) UiRoles.accent else TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(
                                        imageVector = UiIcons.Check,
                                        contentDescription = null,
                                        tint = UiRoles.accent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = "已选",
                                        style = UiRoles.text(MaterialTheme.typography.labelSmall),
                                        color = UiRoles.accent,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val remaining = ModelListFilter.remaining(filtered, state.modelVisibleCount)
            if (remaining > 0) {
                UiAction(
                    label = "显示更多（剩余 $remaining）",
                    onClick = viewModel::showMoreModels,
                    enabled = enabled,
                    actionId = "ai.models.more",
                )
            }
            Text(
                text = "候选列表不代表连接或能力验证通过；请使用下方的草稿测试。",
                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                color = TextSecondary,
            )
        }
    }
}
