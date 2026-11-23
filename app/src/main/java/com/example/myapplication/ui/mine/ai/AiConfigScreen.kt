package com.example.myapplication.ui.mine.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.domain.ai.CapabilityProfile
import com.example.myapplication.domain.ai.ProfilePurpose
import com.example.myapplication.domain.ai.WireProtocol
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiAlertHost
import com.example.myapplication.ui.components.UiSettingsSwitch
import com.example.myapplication.ui.mine.GlassPageBarHeight
import com.example.myapplication.ui.mine.HairlineDivider
import com.example.myapplication.ui.mine.SubPageScaffold
import com.example.myapplication.ui.mine.TextPrimary
import com.example.myapplication.ui.mine.TextSecondary
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackLayer
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

/** Existing entry point keeps trailing-lambda callers compatible. */
@Composable
fun AiConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val factory = remember(context) { AiConfigViewModel.Factory(context) }
    val configViewModel: AiConfigViewModel = viewModel(factory = factory)
    AiConfigScreen(onBack = onBack, viewModel = configViewModel)
}

private sealed interface DraftDestination {
    data object Back : DraftDestination
    data object New : DraftDestination
    data class Purpose(val value: ProfilePurpose) : DraftDestination
    data class Load(val profileId: String) : DraftDestination
    data class Delete(val profileId: String) : DraftDestination
}

/** Every action that replaces the draft uses the same explicit discard/continue decision. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun AiConfigScreen(onBack: () -> Unit, viewModel: AiConfigViewModel) {
    val state by viewModel.state.collectAsState()
    var pending by remember { mutableStateOf<DraftDestination?>(null) }
    var saveBeforeLeave by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        viewModel.cancelProbe()
        viewModel.cancelModels()
        onBack()
    }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.cancelProbe()
            viewModel.cancelModels()
        }
    }
    LaunchedEffect(saveBeforeLeave, state.saving, state.isDirty, state.error, state.savedMessage) {
        if (saveBeforeLeave && !state.saving) {
            if (state.error != null) saveBeforeLeave = false
            else if (!state.isDirty && state.savedMessage == "已保存") {
                saveBeforeLeave = false
                pending = null
                leave()
            }
        }
    }
    val execute: (DraftDestination) -> Unit = { destination ->
        when (destination) {
            DraftDestination.Back -> leave()
            DraftDestination.New -> viewModel.clearDraft()
            is DraftDestination.Purpose -> viewModel.selectPurpose(destination.value)
            is DraftDestination.Load -> state.profiles.firstOrNull { it.id == destination.profileId }
                ?.let(viewModel::loadProfile)
            is DraftDestination.Delete -> viewModel.delete(destination.profileId)
        }
    }
    val request: (DraftDestination) -> Unit = { destination ->
        if (!state.saving) {
            if (state.isDirty) pending = destination else execute(destination)
        }
    }
    val requestBack = unifiedBackRequest(
        BackState(
            page = BackPage.CHILD,
            imeVisible = isBackImeVisible(),
            topLayer = when {
                pending != null -> BackLayer.Overlay("ai-draft-decision")
                state.isDirty || state.saving -> BackLayer.Editor("ai-config", true)
                else -> null
            },
        ),
    ) { decision ->
        when (decision) {
            is BackDecision.ProtectDraft -> pending = DraftDestination.Back
            is BackDecision.CloseLayer -> {
                pending = null
                saveBeforeLeave = false
            }
            BackDecision.PopPage -> leave()
            else -> Unit
        }
    }

    pending?.let { destination ->
        UiAlertHost(
            title = "未保存的修改",
            ownerKey = "ai-dirty-dialog",
            onDismissRequest = { _, _ ->
                pending = null
                saveBeforeLeave = false
            },
            modifier = Modifier.testTag("ai-dirty-dialog"),
        ) { interaction, _, _ ->
            Text(
                "未保存的修改",
                modifier = Modifier.fillMaxWidth().semantics { heading() },
                style = UiRoles.text(MaterialTheme.typography.titleLarge),
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp)
                    .heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = state.error ?: if (state.saving) {
                        "正在保存，请等待完成或继续编辑。"
                    } else if (destination == DraftDestination.Back) {
                        "保存后返回，或明确放弃当前修改。再次返回只会继续编辑。"
                    } else {
                        "当前草稿还未保存。放弃修改后继续操作，或返回保留草稿；需要保存时请先继续编辑。"
                    },
                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (destination == DraftDestination.Back) {
                    UiAction(
                        label = if (state.saving) "正在保存…" else "保存并返回",
                        enabled = !state.saving && state.draft.endpoint.isNotBlank() && state.draft.model.isNotBlank(),
                        busy = state.saving,
                        onClick = {
                            saveBeforeLeave = true
                            viewModel.save()
                        },
                        inheritedPane = true,
                        interactionSource = interaction,
                        animatePress = false,
                        foreground = UiRoles.accent,
                        actionId = "ai.draft.saveAndLeave",
                        modifier = Modifier.fillMaxWidth().testTag("ai-save-and-leave"),
                        visualModifier = Modifier.fillMaxWidth(),
                    )
                }
                UiAction(
                    label = if (destination == DraftDestination.Back) "放弃并返回" else "放弃修改并继续",
                    enabled = !state.saving,
                    onClick = {
                        pending = null
                        saveBeforeLeave = false
                        execute(destination)
                    },
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    foreground = UiRoles.extended.destructive,
                    actionId = "ai.draft.discard",
                    modifier = Modifier.fillMaxWidth().testTag("ai-discard-draft"),
                    visualModifier = Modifier.fillMaxWidth(),
                )
                UiAction(
                    label = "继续编辑",
                    onClick = {
                        pending = null
                        saveBeforeLeave = false
                    },
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    actionId = "ai.draft.continue",
                    modifier = Modifier.fillMaxWidth(),
                    visualModifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // Mine subpages bypass the home Scaffold; this screen owns its system-bar insets.
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        SubPageScaffold(title = "AI 配置", onBack = requestBack, glassBack = true, actionId = "ai.back") {
            Column(
                modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = GlassPageBarHeight + 8.dp, end = 16.dp, bottom = 32.dp)
                    .testTag("ai-config-scroll"),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                PurposeTabs(state.purpose, enabled = !state.saving) { purpose ->
                    if (purpose != state.purpose) request(DraftDestination.Purpose(purpose))
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AiSectionHeader(
                        title = "${state.purpose.label()}用途的配置",
                        subtitle = "保存配置后，可单独设为此用途的默认配置。",
                    )
                    val connections = ProfileConnectionProjection.forPurpose(state.profiles, state.purpose)
                    if (connections.isEmpty()) {
                        AiSectionCard {
                            Text(
                                text = "尚未配置${state.purpose.label()}服务",
                                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                color = TextSecondary,
                            )
                        }
                    } else {
                        connections.forEach { connection ->
                            val profile = state.profiles.first { it.id == connection.profileId }
                            ProfileRow(
                                connection = connection,
                                enabled = profile.enabled,
                                isActive = connection.profileId == state.activeProfileId,
                                actionsEnabled = !state.saving,
                                activating = state.activating,
                                onLoad = { request(DraftDestination.Load(connection.profileId)) },
                                onActivate = { viewModel.activate(connection.profileId) },
                                onDelete = {
                                    if (state.draft.id == connection.profileId) {
                                        request(DraftDestination.Delete(connection.profileId))
                                    } else if (!state.saving) {
                                        viewModel.delete(connection.profileId)
                                    }
                                },
                            )
                        }
                    }
                    UiAction(
                        label = "新建配置",
                        onClick = { request(DraftDestination.New) },
                        enabled = !state.saving,
                        actionId = "ai.newProfile",
                        modifier = Modifier.fillMaxWidth().testTag("ai-new-profile"),
                        visualModifier = Modifier.fillMaxWidth(),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AiSectionHeader(
                        title = if (state.draft.id == null) "新配置草稿" else "编辑配置草稿",
                        subtitle = if (state.isDirty) "当前草稿有未保存的更改。" else null,
                    )
                    ModelConnectionEditor(state, viewModel)
                    AiSectionCard {
                        Text(
                            text = "验证与保存",
                            style = UiRoles.text(UiRoles.pageTitle),
                            color = TextPrimary,
                        )
                        Text(
                            text = "测试只验证当前草稿，不会自动保存或更改用途默认。",
                            style = UiRoles.text(MaterialTheme.typography.bodySmall),
                            color = TextSecondary,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            UiAction(
                                label = if (state.probing) "取消测试" else "测试草稿",
                                onClick = { if (state.probing) viewModel.cancelProbe() else viewModel.probeDraft() },
                                enabled = !state.saving && (state.probing ||
                                    (state.draft.endpoint.isNotBlank() && state.draft.model.isNotBlank())),
                                actionId = "ai.probeDraft",
                                modifier = Modifier.testTag("ai-probe-draft"),
                            )
                        }
                        if (state.probing) {
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
                                    text = "测试中：${state.probePhase.orEmpty()}",
                                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                    color = TextSecondary,
                                )
                            }
                        }
                        state.probeResult?.let { result ->
                            CapabilityResults(result, state.probeNotes, state.probeFirstTokenMs, state.probeTotalMs)
                        } ?: state.probeNotes.forEach { note ->
                            Text(
                                text = note,
                                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                color = TextSecondary,
                            )
                        }
                        state.error?.let { error ->
                            Text(
                                text = error,
                                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        state.savedMessage?.let { saved ->
                            Text(
                                text = saved,
                                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                color = UiRoles.extended.success,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        UiAction(
                            label = if (state.saving) "保存中…" else "保存配置",
                            onClick = viewModel::save,
                            enabled = !state.saving && state.draft.endpoint.isNotBlank() && state.draft.model.isNotBlank(),
                            busy = state.saving,
                            filled = true,
                            actionId = "ai.saveDraft",
                            modifier = Modifier.fillMaxWidth().testTag("ai-save-draft"),
                            visualModifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "保存后继续保留此草稿；用途默认需要在上方单独设置。",
                            style = UiRoles.text(MaterialTheme.typography.bodySmall),
                            color = TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AiSectionHeader(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = UiRoles.text(MaterialTheme.typography.titleSmall),
            fontWeight = FontWeight.Bold,
            color = TextSecondary,
            modifier = Modifier.semantics { heading() },
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                color = TextSecondary,
            )
        }
    }
}

@Composable
internal fun AiSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = UiRoles.surface(UiSurfaceRole.Card),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun AiOptionPill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    role: Role,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unselectedContainer: Color = UiRoles.surface(UiSurfaceRole.Block),
) {
    val cs = MaterialTheme.colorScheme
    val background = if (selected) cs.primary else unselectedContainer
    val contentColor = if (selected) cs.onPrimary else cs.onSurfaceVariant
    val border = if (selected) null else BorderStroke(0.8.dp, cs.outlineVariant.copy(alpha = 0.6f))
    val pillShape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(pillShape)
            .background(background, pillShape)
            .then(if (border != null) Modifier.border(border, pillShape) else Modifier)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = role,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.48f)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = UiRoles.text(UiRoles.actionText),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor,
        )
    }
}

@Composable
internal fun AiSwitchBlock(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = UiRoles.surface(UiSurfaceRole.Block),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        UiSettingsSwitch(
            title = title,
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = modifier,
        )
    }
}

@Composable
internal fun aiFieldColors(): TextFieldColors {
    val cs = MaterialTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = UiRoles.surface(UiSurfaceRole.Card),
        unfocusedContainerColor = UiRoles.surface(UiSurfaceRole.Block),
        disabledContainerColor = UiRoles.surface(UiSurfaceRole.Block).copy(alpha = 0.65f),
        focusedBorderColor = cs.primary,
        unfocusedBorderColor = cs.outlineVariant,
        disabledBorderColor = cs.outlineVariant.copy(alpha = 0.5f),
        focusedTextColor = cs.onSurface,
        unfocusedTextColor = cs.onSurface,
        focusedLabelColor = cs.primary,
        unfocusedLabelColor = cs.onSurfaceVariant,
        cursorColor = cs.primary,
        errorBorderColor = cs.error,
        errorLabelColor = cs.error,
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PurposeTabs(current: ProfilePurpose, enabled: Boolean, onSelect: (ProfilePurpose) -> Unit) {
    Surface(
        color = UiRoles.surface(UiSurfaceRole.Card),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(6.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProfilePurpose.entries.forEach { purpose ->
                AiOptionPill(
                    label = purpose.label(),
                    selected = current == purpose,
                    enabled = enabled,
                    role = Role.Tab,
                    onClick = { onSelect(purpose) },
                    unselectedContainer = Color.Transparent,
                    modifier = Modifier.testTag("ai-purpose-${purpose.name}"),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ProfileRow(
    connection: ProfileConnectionProjection,
    enabled: Boolean,
    isActive: Boolean,
    actionsEnabled: Boolean,
    activating: Boolean,
    onLoad: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    AiSectionCard(modifier = Modifier.testTag("ai-profile-${connection.profileId}")) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = connection.model,
                    style = UiRoles.text(UiRoles.pageTitle),
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (isActive) {
                    AiStatusBadge(
                        label = "当前${connection.purpose.label()}默认",
                        containerColor = UiRoles.iconContainer,
                        contentColor = UiRoles.iconTint,
                    )
                }
                if (!enabled) {
                    AiStatusBadge(
                        label = "已禁用",
                        containerColor = UiRoles.destructiveIconContainer,
                        contentColor = UiRoles.extended.destructive,
                    )
                }
            }
            Text(
                text = "${connection.protocol.shortLabel()} · ${if (connection.hasSecret) "已有密钥" else "未设置密钥"}",
                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                color = TextSecondary,
            )
            Text(
                text = connection.endpoint,
                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                color = TextSecondary,
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = HairlineDivider)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UiAction(
                label = "编辑",
                onClick = onLoad,
                enabled = actionsEnabled,
                actionId = "ai.profile.edit",
                modifier = Modifier.testTag("ai-load-${connection.profileId}"),
            )
            if (!isActive) {
                UiAction(
                    label = "设为${connection.purpose.label()}默认",
                    onClick = onActivate,
                    enabled = actionsEnabled && enabled && !activating,
                    busy = activating,
                    filled = true,
                    actionId = "ai.profile.activate",
                    modifier = Modifier.testTag("ai-activate-${connection.profileId}"),
                )
            }
            UiAction(
                label = "删除",
                onClick = onDelete,
                enabled = actionsEnabled,
                foreground = UiRoles.extended.destructive,
                actionId = "ai.profile.delete",
                modifier = Modifier.testTag("ai-delete-${connection.profileId}"),
            )
        }
    }
}

@Composable
private fun AiStatusBadge(
    label: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        color = containerColor,
        shape = CircleShape,
    ) {
        Text(
            text = label,
            style = UiRoles.text(MaterialTheme.typography.labelMedium),
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 草稿测试结果：每一项能力独立显示，未测项保持未测，不把“列表可用”当“端点已联通”。 */
@Composable
private fun CapabilityResults(
    capabilities: CapabilityProfile,
    notes: List<String>,
    firstTokenMs: Long?,
    totalMs: Long?,
) {
    Surface(
        color = UiRoles.surface(UiSurfaceRole.Block),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "草稿测试结果",
                style = UiRoles.text(UiRoles.pageTitle),
                color = TextPrimary,
            )
            CapabilityLine("生成", capabilities.generation)
            CapabilityLine("流式", capabilities.streaming)
            CapabilityLine("工具调用", capabilities.toolCalling)
            CapabilityLine("结构化输出", capabilities.structuredOutput)
            CapabilityLine("嵌入", capabilities.embedding)
            CapabilityLine("重排", capabilities.rerank)
            CapabilityLine("搜索", capabilities.search)
            capabilities.embeddingDim?.let {
                Text("嵌入维度：$it", style = UiRoles.text(MaterialTheme.typography.bodyMedium), color = TextPrimary)
            }
            if (firstTokenMs != null || totalMs != null) {
                val timing = buildList {
                    firstTokenMs?.let { add("首字 $it ms") }
                    totalMs?.let { add("总耗时 $it ms") }
                }.joinToString(" · ")
                Text(timing, style = UiRoles.text(MaterialTheme.typography.bodySmall), color = TextSecondary)
            }
            notes.forEach {
                Text(it, style = UiRoles.text(MaterialTheme.typography.bodySmall), color = TextSecondary)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "以上只是本机对当前草稿发出的真实请求结果；未测项保持未测，不代表服务商其他能力可用。",
                color = TextSecondary,
                style = UiRoles.text(MaterialTheme.typography.labelSmall),
            )
        }
    }
}

@Composable
private fun CapabilityLine(label: String, status: CapabilityProfile.CapabilityStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "$label：${status.displayLabel()}",
            style = UiRoles.text(MaterialTheme.typography.bodyMedium),
            color = status.displayColor(),
        )
    }
}

private fun CapabilityProfile.CapabilityStatus.displayLabel(): String = when (this) {
    CapabilityProfile.CapabilityStatus.PASS -> "通过"
    CapabilityProfile.CapabilityStatus.FAIL -> "失败"
    CapabilityProfile.CapabilityStatus.UNSUPPORTED -> "不支持"
    CapabilityProfile.CapabilityStatus.UNTESTED -> "未测"
}

/** 沿用语义色：通过=success、失败=error/destructive、不支持=warning、未测=辅助色。 */
@Composable
private fun CapabilityProfile.CapabilityStatus.displayColor(): Color = when (this) {
    CapabilityProfile.CapabilityStatus.PASS -> UiRoles.extended.success
    CapabilityProfile.CapabilityStatus.FAIL -> MaterialTheme.colorScheme.error
    CapabilityProfile.CapabilityStatus.UNSUPPORTED -> UiRoles.extended.warning
    CapabilityProfile.CapabilityStatus.UNTESTED -> TextSecondary
}

internal fun ProfilePurpose.label(): String = when (this) {
    ProfilePurpose.CHAT -> "对话"
    ProfilePurpose.EMBEDDING -> "向量"
    ProfilePurpose.RERANK -> "重排"
    ProfilePurpose.SEARCH -> "搜索"
}

internal fun WireProtocol.shortLabel(): String = when (this) {
    WireProtocol.OPENAI_CHAT -> "OpenAI Chat"
    WireProtocol.OPENAI_RESPONSES -> "Responses"
    WireProtocol.ANTHROPIC_MESSAGES -> "Anthropic"
    WireProtocol.GEMINI_NATIVE -> "Gemini"
}
