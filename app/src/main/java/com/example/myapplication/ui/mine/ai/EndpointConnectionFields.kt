package com.example.myapplication.ui.mine.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.ai.config.EndpointConfigCodec
import com.example.myapplication.data.ai.config.EndpointOperation
import com.example.myapplication.data.ai.config.EndpointResolver
import com.example.myapplication.domain.ai.ProfilePurpose
import com.example.myapplication.domain.ai.WireProtocol
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiInlineExpand
import com.example.myapplication.ui.components.UiSettingsSwitch
import com.example.myapplication.ui.mine.HairlineDivider
import com.example.myapplication.ui.mine.TextPrimary
import com.example.myapplication.ui.mine.TextSecondary
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

/** Mode and URL edits belong to the VM; this UI renders the real resolver's projection. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun EndpointConnectionFields(state: AiConfigUiState, viewModel: AiConfigViewModel) {
    val draft = state.draft
    val projection = EndpointDraftEditor.project(draft, state.purpose)
    val enabled = !state.saving
    // Opening an exact profile reveals its URLs. A user's collapse preference changes no draft bytes.
    var userExpanded by rememberSaveable(state.purpose, draft.id) { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: (projection.mode == EndpointConfigCodec.Mode.EXACT_ENDPOINT)
    val exact = projection.mode == EndpointConfigCodec.Mode.EXACT_ENDPOINT

    OutlinedTextField(
        value = draft.endpoint,
        onValueChange = { value -> viewModel.editDraft { it.copy(endpoint = value) } },
        // Preserve the label used by existing accessibility and configuration callers.
        label = { Text("Endpoint（绝对 URL）") },
        textStyle = UiRoles.text(MaterialTheme.typography.bodyLarge),
        supportingText = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = when (projection.mode) {
                        EndpointConfigCodec.Mode.EXACT_ENDPOINT ->
                            "填写${state.purpose.primaryOperation().label()}的完整请求 URL；不会追加请求路径。"
                        EndpointConfigCodec.Mode.BASE ->
                            "填写服务基址，例如 https://api.example.com/v1；请求路径由协议解析，基址不含查询参数。"
                        EndpointConfigCodec.Mode.LEGACY_BASE ->
                            "填写服务基址，例如 https://api.example.com/v1；当前使用兼容基址解析，完整请求 URL 请在高级项显式转换。"
                        null -> "地址配置元数据无效；原配置已保留，可新建配置重新填写。"
                    },
                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                )
                projection.error?.let {
                    Text(
                        text = it,
                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        isError = projection.error != null,
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = aiFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("ai-endpoint"),
    )

    UiInlineExpand(
        ownerKey = state.purpose to draft.id,
        expanded = expanded,
        onExpandedChange = { userExpanded = it },
        trigger = { triggerMod, progress, _, onToggle ->
            Surface(
                color = UiRoles.surface(UiSurfaceRole.Block),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = triggerMod
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClick = onToggle)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("ai-endpoint-advanced"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (expanded) "收起地址高级设置" else "展开地址高级设置",
                        style = UiRoles.text(UiRoles.actionText),
                        color = UiRoles.accent,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = UiIcons.ChevronDown,
                        contentDescription = null,
                        tint = UiRoles.accent,
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 180f * progress },
                    )
                }
            }
        },
    ) {
        Surface(
            color = UiRoles.surface(UiSurfaceRole.Block),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "地址模式",
                    style = UiRoles.text(MaterialTheme.typography.labelMedium),
                    color = TextSecondary,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EndpointConfigCodec.Mode.entries.forEach { mode ->
                        AiOptionPill(
                            label = mode.label(),
                            selected = projection.mode == mode,
                            enabled = enabled && projection.mode != null,
                            role = Role.RadioButton,
                            onClick = { viewModel.editEndpointMode(mode) },
                            unselectedContainer = UiRoles.surface(UiSurfaceRole.Card),
                            modifier = Modifier.testTag("ai-endpoint-mode-${mode.name}"),
                        )
                    }
                }
                if (projection.mode == EndpointConfigCodec.Mode.LEGACY_BASE) {
                    Text(
                        text = "历史配置保留原基址含义。即使地址已含完整请求路径，也会继续追加协议路径；选择“完整地址”才显式转换，输入的地址保持原样。",
                        color = TextSecondary,
                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                    )
                }
                if (projection.operationUrls.isNotEmpty()) {
                    Text(
                        text = "切换为历史基址前，请先清空独立操作地址。",
                        color = TextSecondary,
                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                    )
                }
                OperationUrlField(
                    value = projection.operationUrls[EndpointOperation.MODELS].orEmpty(),
                    onValueChange = { viewModel.editOperationUrl(EndpointOperation.MODELS, it) },
                    label = "独立模型列表 URL",
                    supportingText = when (projection.mode) {
                        EndpointConfigCodec.Mode.EXACT_ENDPOINT -> "留空表示未配置模型列表地址；仍可手动输入模型并保存。"
                        EndpointConfigCodec.Mode.LEGACY_BASE -> "填写独立地址将启用基址模式；留空保留兼容解析。URL 必须与 Endpoint 同源。"
                        EndpointConfigCodec.Mode.BASE -> "留空按基址模式解析；是否支持见请求预览。独立 URL 必须与 Endpoint 同源。"
                        null -> "地址配置元数据无效，当前无法应用独立操作地址。"
                    },
                    tag = "ai-endpoint-models-url",
                    enabled = enabled && projection.mode != null,
                )
                if (state.purpose == ProfilePurpose.CHAT || projection.operationUrls.containsKey(EndpointOperation.CHAT_STREAM)) {
                    OperationUrlField(
                        value = projection.operationUrls[EndpointOperation.CHAT_STREAM].orEmpty(),
                        onValueChange = { viewModel.editOperationUrl(EndpointOperation.CHAT_STREAM, it) },
                        label = "独立流式聊天 URL",
                        supportingText = when {
                            state.purpose != ProfilePurpose.CHAT ->
                                "已有流式操作地址，可编辑或清空；当前用途的可用操作见请求预览。"
                            exact && draft.protocol == WireProtocol.GEMINI_NATIVE ->
                                "Gemini 需要独立的流式请求 URL；留空时此配置仅非流式。"
                            exact -> "填写独立流式 URL，或明确选择流式使用同一地址；留空时此配置仅非流式。"
                            projection.mode == EndpointConfigCodec.Mode.LEGACY_BASE ->
                                "填写独立地址将启用基址模式；留空保留兼容解析。URL 必须与 Endpoint 同源。"
                            projection.mode == null -> "地址配置元数据无效，当前无法应用独立操作地址。"
                            else -> "留空使用基址解析的流式地址；独立 URL 必须与 Endpoint 同源。"
                        },
                        tag = "ai-endpoint-stream-url",
                        enabled = enabled && projection.mode != null,
                    )
                    if (state.purpose == ProfilePurpose.CHAT && exact && draft.protocol != WireProtocol.GEMINI_NATIVE) {
                        Surface(
                            color = UiRoles.surface(UiSurfaceRole.Card),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            UiSettingsSwitch(
                                title = "流式使用同一地址",
                                checked = draft.endpoint.isNotBlank() &&
                                    projection.operationUrls[EndpointOperation.CHAT_STREAM] == draft.endpoint,
                                onCheckedChange = viewModel::useSharedStreamEndpoint,
                                enabled = enabled && draft.endpoint.isNotBlank(),
                                modifier = Modifier
                                    .testTag("ai-endpoint-shared-stream")
                                    .semantics { contentDescription = "流式使用同一地址" },
                            )
                        }
                    }
                    if (state.purpose == ProfilePurpose.CHAT && exact &&
                        projection.previews[EndpointOperation.CHAT_STREAM] is EndpointResolver.Resolution.Unsupported
                    ) {
                        Text(
                            text = "此配置仅非流式；配置流式操作地址后才能使用流式聊天。",
                            color = TextSecondary,
                            style = UiRoles.text(MaterialTheme.typography.bodySmall),
                        )
                    }
                }
                projection.operationUrls.filterKeys {
                    it != EndpointOperation.MODELS && it != EndpointOperation.CHAT_STREAM
                }.forEach { (operation, url) ->
                    key(operation) {
                        val tag = "ai-endpoint-operation-${operation.name}"
                        OperationUrlField(
                            value = url,
                            onValueChange = { viewModel.editOperationUrl(operation, it) },
                            label = "${operation.label()}独立 URL",
                            supportingText = "已配置的独立操作地址；清除只移除此项，不改变 Endpoint 或其它操作。",
                            tag = tag,
                            enabled = enabled && projection.mode != null,
                        )
                        UiAction(
                            label = "清除此地址",
                            onClick = { viewModel.editOperationUrl(operation, "") },
                            enabled = enabled,
                            foreground = UiRoles.extended.destructive,
                            actionId = "ai.endpoint.clearOperation",
                            modifier = Modifier.testTag("$tag-clear"),
                        )
                    }
                }
                Surface(
                    color = UiRoles.surface(UiSurfaceRole.Card),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "实际请求预览",
                            style = UiRoles.text(UiRoles.actionText),
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                        )
                        val previewEntries = projection.previews.entries.toList()
                        previewEntries.forEachIndexed { index, (operation, resolution) ->
                            if (index > 0) {
                                HorizontalDivider(thickness = 0.5.dp, color = HairlineDivider)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = operation.label(),
                                    color = TextSecondary,
                                    style = UiRoles.text(MaterialTheme.typography.labelMedium),
                                )
                                // Only validated final URLs are displayed; an invalid input never becomes a URL preview.
                                Text(
                                    text = when (resolution) {
                                        is EndpointResolver.Resolution.Ok -> resolution.endpoint.url
                                        is EndpointResolver.Resolution.Unsupported -> "UNSUPPORTED_OPERATION：此操作不支持或尚未配置。"
                                        is EndpointResolver.Resolution.Invalid -> "${resolution.code}：${resolution.reason}"
                                    },
                                    color = if (resolution is EndpointResolver.Resolution.Invalid) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        TextPrimary
                                    },
                                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                    modifier = Modifier.fillMaxWidth().testTag("ai-endpoint-preview-${operation.name}"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationUrlField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supportingText: String,
    tag: String,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        textStyle = UiRoles.text(MaterialTheme.typography.bodyLarge),
        supportingText = {
            Text(
                text = supportingText,
                style = UiRoles.text(MaterialTheme.typography.bodySmall),
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = aiFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
    )
}

private fun EndpointConfigCodec.Mode.label(): String = when (this) {
    EndpointConfigCodec.Mode.LEGACY_BASE -> "历史基址"
    EndpointConfigCodec.Mode.BASE -> "基址"
    EndpointConfigCodec.Mode.EXACT_ENDPOINT -> "完整地址"
}

private fun EndpointOperation.label(): String = when (this) {
    EndpointOperation.CHAT_SINGLE -> "非流式聊天"
    EndpointOperation.CHAT_STREAM -> "流式聊天"
    EndpointOperation.MODELS -> "模型列表"
    EndpointOperation.EMBEDDING -> "向量生成"
    EndpointOperation.RERANK -> "重排"
    EndpointOperation.SEARCH -> "搜索"
}

private fun ProfilePurpose.primaryOperation(): EndpointOperation = when (this) {
    ProfilePurpose.CHAT -> EndpointOperation.CHAT_SINGLE
    ProfilePurpose.EMBEDDING -> EndpointOperation.EMBEDDING
    ProfilePurpose.RERANK -> EndpointOperation.RERANK
    ProfilePurpose.SEARCH -> EndpointOperation.SEARCH
}
