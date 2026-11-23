package com.example.myapplication.ui.mine

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.knowledge.index.VectorCodec
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiAlertHost
import com.example.myapplication.ui.navigation.*
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

@Composable
fun KnowledgeSettingsScreen(onBack: () -> Unit) {
    val enhancement = rememberEnhancementSettingsViewModel()
    val state by enhancement.state.collectAsState()
    KnowledgeSettingsScreen(
        onBack = onBack,
        state = state,
        onSearchEndpointDraftChange = enhancement::updateSearchEndpointDraft,
        onSaveSearchEndpoint = { callback -> enhancement.saveSearchEndpoint(callback) },
        onDiscardSearchEndpointDraft = enhancement::discardSearchEndpointDraft,
        onVectorEncodingChange = enhancement::setVectorEncoding,
    )
}

/** Endpoint drafts belong to the shared ViewModel; only the leave decision is local. */
@Composable
fun KnowledgeSettingsScreen(
    onBack: () -> Unit,
    state: EnhancementSettingsUiState,
    onSearchEndpointDraftChange: (String) -> Unit,
    onSaveSearchEndpoint: (() -> Unit) -> Long?,
    onDiscardSearchEndpointDraft: () -> Unit,
    onVectorEncodingChange: (String) -> Unit,
) {
    var protectDraft by rememberSaveable { mutableStateOf(false) }
    var pendingSaveId by rememberSaveable { mutableStateOf<Long?>(null) }
    val currentBack by rememberUpdatedState(onBack)
    val continueEditing: () -> Unit = {
        protectDraft = false
        pendingSaveId = null
    }
    // Saved leave intent survives recreation; only the exact current-session success can fulfil it.
    // A callback held by a disposed page owns no navigation and cannot pop a new page.
    LaunchedEffect(pendingSaveId, state.searchEndpointSaveRequestId,
        state.searchEndpointSavedRequestId, state.searchEndpointSaving) {
        val requested = pendingSaveId ?: return@LaunchedEffect
        when {
            state.searchEndpointSaveRequestId != requested -> continueEditing()
            state.searchEndpointSaving -> Unit
            state.searchEndpointSavedRequestId == requested -> {
                continueEditing()
                currentBack()
            }
            else -> pendingSaveId = null // A completed failure keeps its draft/error and dialog.
        }
    }
    val requestBack = unifiedBackRequest(BackState(
        page = BackPage.CHILD,
        imeVisible = isBackImeVisible(),
        topLayer = when {
            protectDraft -> BackLayer.Overlay("knowledge-settings-draft-decision")
            state.searchEndpointDirty || state.searchEndpointSaving -> BackLayer.Editor("knowledge-search-endpoint", true)
            else -> null
        },
    )) { decision ->
        when (decision) {
            is BackDecision.ProtectDraft -> protectDraft = true
            is BackDecision.CloseLayer -> continueEditing()
            BackDecision.PopPage -> currentBack()
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        SubPageScaffold(title = "知识库设置", onBack = requestBack, glassBack = true, actionId = "knowledge.back") {
            Column(
                modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = GlassPageBarHeight + 8.dp, end = 16.dp, bottom = 32.dp)
                    .testTag("knowledge-settings-scroll"),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                KnowledgeSettingsSection("搜索服务") {
                    Text("SearXNG 搜索服务（可选）", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                    Text("配置地址不会开启联网检索；授权仍由设置首页的开关控制。",
                        style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Text("已保存地址：${state.searchEndpointSaved ?: "未配置"}",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                        modifier = Modifier.testTag("knowledge-saved-endpoint"))
                    OutlinedTextField(
                        value = state.searchEndpointDraft,
                        onValueChange = onSearchEndpointDraftChange,
                        enabled = !state.searchEndpointSaving,
                        isError = state.searchEndpointError != null,
                        modifier = Modifier.fillMaxWidth().testTag("knowledge-search-endpoint"),
                        label = { Text("搜索地址") },
                        supportingText = { Text("填写 HTTP(S) /search 地址。只调用 JSON 接口，不跟随重定向或抓取网页正文；留空并保存可清除。") },
                        singleLine = true,
                    )
                    state.searchEndpointError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("knowledge-search-error").semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    UiAction(
                        label = "保存搜索地址",
                        onClick = { onSaveSearchEndpoint {} },
                        enabled = state.searchEndpointDirty,
                        busy = state.searchEndpointSaving,
                        filled = true,
                        actionId = "knowledge.endpoint.save",
                        modifier = Modifier.fillMaxWidth().testTag("knowledge-save-endpoint"),
                        visualModifier = Modifier.fillMaxWidth(),
                    )
                }
                KnowledgeSettingsSection("向量编码") {
                    Text(vectorEncodingSummary(state), color = TextPrimary,
                        modifier = Modifier.testTag("knowledge-vector-status"))
                    Text(if (state.vectorEncodingRebuildPending)
                        "后台正在为可用索引重建。旧索引保留，全部完成后才切换。"
                    else "切换会在后台重建索引；全部就绪后原子切换，旧索引保留以便回退。量化召回质量仍需基准评测。",
                        style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Column(Modifier.fillMaxWidth().selectableGroup()) {
                        VectorEncodingChoice(VectorCodec.ENCODING_FLOAT32, "32 位（FLOAT32）", state.vectorEncoding, onVectorEncodingChange)
                        VectorEncodingChoice(VectorCodec.ENCODING_FLOAT16, "16 位（FLOAT16）", state.vectorEncoding, onVectorEncodingChange)
                        VectorEncodingChoice(VectorCodec.ENCODING_INT8, "INT8 对称量化", state.vectorEncoding, onVectorEncodingChange)
                    }
                }
            }
        }
    }

    if (protectDraft) {
        UiAlertHost(
            title = "未保存的修改",
            ownerKey = "knowledge-dirty-dialog",
            onDismissRequest = { _, _ -> continueEditing() },
            modifier = Modifier.testTag("knowledge-dirty-dialog"),
        ) { interaction, _, _ ->
            Text(
                "未保存的修改",
                modifier = Modifier.fillMaxWidth().semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp)
                    .heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    state.searchEndpointError ?: if (state.searchEndpointSaving) "正在保存，请等待完成或继续编辑。"
                    else "保存后返回，或明确放弃当前修改。再次返回只会继续编辑。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Vertical actions can grow at 320dp / 200% font scale.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UiAction(
                    label = "保存并返回",
                    busy = state.searchEndpointSaving,
                    onClick = { pendingSaveId = onSaveSearchEndpoint {} },
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    foreground = UiRoles.accent,
                    actionId = "knowledge.endpoint.saveAndLeave",
                    modifier = Modifier.fillMaxWidth().testTag("knowledge-save-and-leave"),
                    visualModifier = Modifier.fillMaxWidth(),
                )
                UiAction(
                    label = "放弃并返回",
                    enabled = !state.searchEndpointSaving,
                    onClick = {
                        onDiscardSearchEndpointDraft()
                        continueEditing()
                        currentBack()
                    },
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    foreground = UiRoles.extended.destructive,
                    actionId = "knowledge.endpoint.discard",
                    modifier = Modifier.fillMaxWidth().testTag("knowledge-discard-draft"),
                    visualModifier = Modifier.fillMaxWidth(),
                )
                UiAction(
                    label = "继续编辑",
                    onClick = continueEditing,
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    actionId = "knowledge.endpoint.continue",
                    modifier = Modifier.fillMaxWidth().testTag("knowledge-continue-editing"),
                    visualModifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun KnowledgeSettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = UiRoles.text(MaterialTheme.typography.titleSmall),
            fontWeight = FontWeight.Bold,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
        Surface(color = UiRoles.surface(UiSurfaceRole.Card), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** One radio-group action per row; the decorative radio indicator has no handler. */
@Composable
private fun VectorEncodingChoice(encoding: String, label: String, selected: String, onSelected: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("knowledge-vector-$encoding")
        .selectable(selected = selected == encoding, role = Role.RadioButton,
            onClick = { if (selected != encoding) onSelected(encoding) })
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected = selected == encoding, onClick = null)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
    }
}
