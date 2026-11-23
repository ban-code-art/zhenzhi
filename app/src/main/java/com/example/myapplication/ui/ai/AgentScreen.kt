package com.example.myapplication.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.data.agent.ContextAssembler
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiActionId
import com.example.myapplication.ui.components.UiConfirmDialog
import com.example.myapplication.ui.components.UiInlineExpand
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackLayer
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.navigation.UiPageStack
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 全屏 AI 工作区（W4-11）。
 *
 * 设计 §5/§17：
 *  - 全屏子页，关闭（返回阅读现场）与停止（中断生成本轮）是两个动作；
 *  - 生成中关闭不中断运行（由运行编排持有），但页面要提示“可后台继续”；
 *  - 流式片段、工具步骤（名称/状态/摘要）、来源卡片可见，不展示隐藏思维；
 *  - 来源已失效：显示历史 quote，点击给出可读提示而不是跳到别处。
 */
@Composable
fun AgentScreen(
    launch: AgentLaunchContext,
    onClose: () -> Unit,
    onOpenSource: (AgentSourceUi) -> Unit,
    initialReturnPosition: String = "{}",
    onSourceCheckpoint: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val factory = remember(launch) { AgentViewModelFactory(context = context.applicationContext, launch = launch) }
    val viewModel: AgentViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    var showConversations by rememberSaveable { mutableStateOf(false) }
    val screenState = rememberSaveableStateHolder()
    val position = remember(initialReturnPosition) {
        runCatching { Json.parseToJsonElement(initialReturnPosition).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }
    LaunchedEffect(state.conversations, initialReturnPosition) {
        val id = position["conversationId"]?.jsonPrimitive?.contentOrNull
        viewModel.restoreReturnConversation(id, position["draft"]?.jsonPrimitive?.contentOrNull)
    }

    DisposableEffect(viewModel) {
        viewModel.resumeUi()
        onDispose { viewModel.closeUi() }
    }

    UiPageStack(if (showConversations) listOf("conversations") else emptyList()) { visiblePath ->
        if (visiblePath.isNotEmpty()) {
            screenState.SaveableStateProvider("conversations") {
                ConversationScreen(
                    state = state,
                    onBack = { showConversations = false },
                    onNewConversation = {
                        viewModel.newConversation()
                        showConversations = false
                    },
                    onOpenConversation = {
                        viewModel.openConversation(it)
                        showConversations = false
                    },
                    onRenameConversation = viewModel::renameConversation,
                    onArchiveConversation = viewModel::archiveConversation,
                    onDeleteConversation = viewModel::deleteConversation,
                    onLoadMore = viewModel::loadMoreConversations,
                )
            }
        } else screenState.SaveableStateProvider("workspace") {
            AgentScreenContent(
                state = state,
                onDraftChange = viewModel::updateDraft,
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onClose = {
                    viewModel.closeUi()
                    onClose()
                },
                onOpenConversations = { showConversations = true },
                onOpenSource = onOpenSource,
                initialReturnPosition = initialReturnPosition,
                onSourceCheckpoint = onSourceCheckpoint,
                onUnavailableSource = viewModel::reportSourceUnavailable,
                onConsumeNotice = viewModel::consumeNotice,
                onConfirmProposal = viewModel::confirmProposal,
                onRejectProposal = viewModel::rejectProposal,
                onDismissProposal = viewModel::dismissProposal,
            )
        }
    }
}


@Composable
fun AgentScreenContent(
    state: AgentUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenConversations: () -> Unit,
    onOpenSource: (AgentSourceUi) -> Unit,
    onUnavailableSource: (AgentSourceUi) -> Unit,
    onConsumeNotice: () -> Unit,
    onConfirmProposal: (String) -> Unit = {},
    onRejectProposal: (String) -> Unit = {},
    onDismissProposal: (String) -> Unit = {},
    initialReturnPosition: String = "{}",
    onSourceCheckpoint: (String) -> Unit = {},
) {
    val visibleMessages = remember(state.messages) { AgentExploration.turns(state.messages) }
    val tailScope = rememberCoroutineScope()
    var protectDraft by rememberSaveable { mutableStateOf(false) }
    val requestBack = unifiedBackRequest(
        BackState(
            page = BackPage.CHILD,
            imeVisible = isBackImeVisible(),
            topLayer = when {
                protectDraft -> BackLayer.Overlay("agent-unsent-draft")
                state.notice != null -> BackLayer.Overlay("agent-notice")
                state.draft.isNotBlank() -> BackLayer.Editor("agent-composer", true)
                else -> null
            },
        ),
    ) { decision ->
        when (decision) {
            is BackDecision.ProtectDraft -> protectDraft = true
            is BackDecision.CloseLayer -> if (protectDraft) protectDraft = false else onConsumeNotice()
            BackDecision.PopPage -> onClose()
            else -> Unit
        }
    }
    val position = remember(initialReturnPosition) {
        runCatching { Json.parseToJsonElement(initialReturnPosition).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }

    Scaffold(
        containerColor = UiRoles.surface(UiSurfaceRole.Page),
        topBar = {
            AgentHeader(
                title = state.activeConversationTitle,
                terminalStatus = state.unreportedTerminalStatus(),
                onBack = requestBack,
                onOpenConversations = onOpenConversations,
            )
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().background(UiRoles.surface(UiSurfaceRole.Page))
                    .navigationBarsPadding().imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    AiComposer(
                        draft = state.draft,
                        onDraftChange = onDraftChange,
                        canSend = state.canSend,
                        onSend = onSend,
                        enabled = !state.sessionChanged,
                        onStop = onStop.takeIf { state.isRunning },
                        actionPrefix = "ai.workspace",
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.historyLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.historyLoading) {
                Text(
                    text = "正在加载会话历史…",
                    style = MaterialTheme.typography.labelSmall,
                    color = UiRoles.pageSupporting,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            state.readingContext?.encyclopediaContext?.let { enc ->
                Text(
                    text = "当前词条：${enc.kind.label} · ${enc.title}（${enc.selectedSectionTitle}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = UiRoles.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("ai-encyclopedia-context-banner"),
                )
            }
            key(state.activeConversationId) {
                val restoresCurrentConversation = position["conversationId"]?.jsonPrimitive?.contentOrNull == state.activeConversationId
                val restoredIndex = if (restoresCurrentConversation) position["listIndex"]?.jsonPrimitive?.intOrNull else null
                val restoredOffset = if (restoredIndex != null) position["listOffset"]?.jsonPrimitive?.intOrNull else null
                val messageList = rememberLazyListState(
                    initialFirstVisibleItemIndex = restoredIndex?.coerceAtLeast(0) ?: 0,
                    initialFirstVisibleItemScrollOffset = restoredOffset?.coerceAtLeast(0) ?: 0,
                )
                var followingTail by rememberSaveable { mutableStateOf(restoredIndex == null) }
                var followingUpdate by remember { mutableStateOf(false) }
                LaunchedEffect(messageList) {
                    snapshotFlow { messageList.isScrollInProgress to messageList.canScrollForward }
                        .collect { (scrolling, canAdvance) ->
                            if (!followingUpdate && messageList.layoutInfo.totalItemsCount > 0 &&
                                (scrolling || !canAdvance)) {
                                followingTail = !canAdvance
                            }
                        }
                }
                val lastMessage = visibleMessages.lastOrNull()
                var lastMessageHeight by remember(lastMessage?.presentationKey) { mutableStateOf(0) }
                val activeAnswer = lastMessage?.takeIf { message ->
                    state.isRunning && message.role == AgentMessageRole.ASSISTANT
                }
                val activeLabel = when (state.phase) {
                    AgentRunPhase.PREPARING -> "正在查找…"
                    AgentRunPhase.TOOL_RUNNING -> lastMessage?.toolSteps?.lastOrNull { it.status == AgentToolStatus.RUNNING }?.let { "正在${it.action} ${it.subject}…" } ?: "正在查找…"
                    AgentRunPhase.GENERATING -> "正在查找…"
                    else -> null
                }
                val showPendingAnswer = activeLabel != null && activeAnswer == null && !state.historyLoading
                val count = state.noteProposals.size + visibleMessages.size + if (showPendingAnswer) 1 else 0
                LaunchedEffect(count, lastMessage?.id, lastMessageHeight, state.phase) {
                    // A row can change height during lazy subcomposition. Scroll after that measure pass.
                    withFrameNanos { }
                    if (count == 0 || !followingTail || messageList.isScrollInProgress) return@LaunchedEffect
                    followingUpdate = true
                    try {
                        messageList.scrollToItem(count - 1)
                        val layout = messageList.layoutInfo
                        val last = layout.visibleItemsInfo.lastOrNull()
                        if (last?.index == count - 1) {
                            val overflow = (last.offset + last.size - layout.viewportEndOffset).coerceAtLeast(0)
                            if (overflow > 0) messageList.scrollBy(overflow.toFloat())
                        }
                    } finally {
                        followingUpdate = false
                    }
                }
                if (count == 0 && !state.historyLoading) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier.size(64.dp).background(
                                MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(22.dp),
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(UiIcons.Books, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                        }
                        Text(
                            "输入问题开始对话",
                            modifier = Modifier.padding(top = 14.dp),
                            color = UiRoles.pageForeground,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = messageList,
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(state.noteProposals, key = { "proposal-${it.id}" }) { proposal ->
                            NoteProposalCard(
                                proposal = proposal,
                                busy = state.proposalBusyId == proposal.id,
                                onConfirm = { onConfirmProposal(proposal.id) },
                                onReject = { onRejectProposal(proposal.id) },
                                onDismiss = { onDismissProposal(proposal.id) },
                            )
                        }
                        items(visibleMessages, key = { it.presentationKey }) { message ->
                            AgentMessageRow(
                                message = message,
                                activeLabel = activeLabel.takeIf { message.id == activeAnswer?.id },
                                modifier = if (message.presentationKey == lastMessage?.presentationKey)
                                    Modifier.onSizeChanged { lastMessageHeight = it.height } else Modifier,
                                onOpenSource = { source ->
                                    onSourceCheckpoint(buildJsonObject {
                                        put("conversationId", state.activeConversationId)
                                        put("messageId", message.id)
                                        put("draft", state.draft)
                                        put("listIndex", messageList.firstVisibleItemIndex)
                                        put("listOffset", messageList.firstVisibleItemScrollOffset)
                                    }.toString())
                                    onOpenSource(source)
                                },
                                onUnavailableSource = onUnavailableSource,
                            )
                        }
                        if (showPendingAnswer) {
                            item(key = "pending-answer") {
                                AiAnswerStatus(checkNotNull(activeLabel), running = true)
                            }
                        }
                    }
                    if (!followingTail && messageList.canScrollForward) {
                        UiAction(label = "回到最新", onClick = {
                            followingTail = true
                            tailScope.launch {
                                messageList.animateScrollToItem((count - 1).coerceAtLeast(0))
                                messageList.scrollBy(100_000f)
                            }
                        }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), actionId = "ai.latest")
                    }
                    }
                }
            }
        }
    }

    if (protectDraft) {
        UiConfirmDialog(
            title = "未发送的内容",
            message = if (state.isRunning) "返回会丢弃当前输入。正在生成的回答会在后台继续。"
                else "返回会丢弃当前输入。",
            confirmLabel = "放弃输入并返回",
            dismissLabel = "继续编辑",
            onConfirm = {
                protectDraft = false
                onDraftChange("")
                onClose()
            },
            onDismiss = { protectDraft = false },
            actionId = "ai.workspace.draft",
        )
    } else {
        state.notice?.let { notice ->
            UiConfirmDialog(
                title = "提示",
                message = notice,
                confirmLabel = "知道了",
                dismissLabel = "关闭",
                onConfirm = onConsumeNotice,
                onDismiss = onConsumeNotice,
                destructive = false,
                ownerKey = notice,
                actionId = "ai.workspace.notice",
            )
        }
    }
}

private fun AgentUiState.unreportedTerminalStatus(): String? {
    val footer = messages.lastOrNull()?.takeIf { it.role == AgentMessageRole.ASSISTANT }?.statusLabel
    return when (phase) {
        AgentRunPhase.CANCELLED -> "已停止".takeUnless { footer == it }
        AgentRunPhase.INTERRUPTED -> "已中断（进程恢复）".takeUnless { footer?.startsWith("已中断") == true }
        AgentRunPhase.ERROR -> "出错".takeIf { error == null && footer != "失败" }
        else -> null
    }
}

@Composable
private fun AgentHeader(
    title: String,
    terminalStatus: String?,
    onBack: () -> Unit,
    onOpenConversations: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(UiRoles.surface(UiSurfaceRole.Chrome)).statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UiAction(
                label = "关闭工作区（返回阅读）",
                icon = UiIcons.Back,
                onClick = onBack,
                actionId = "ai.workspace.back",
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    modifier = Modifier.semantics { heading() },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = UiRoles.text(UiRoles.pageTitle),
                    color = UiRoles.foreground,
                )
                terminalStatus?.let {
                    Text(
                        it,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            UiAction(
                label = "会话列表",
                icon = UiIcons.Records,
                onClick = onOpenConversations,
                actionId = "ai.workspace.conversations",
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AgentMessageRow(
    message: AgentMessageUi,
    activeLabel: String?,
    modifier: Modifier,
    onOpenSource: (AgentSourceUi) -> Unit,
    onUnavailableSource: (AgentSourceUi) -> Unit,
) {
    val live = activeLabel != null || message.streaming
    var observedLive by rememberSaveable { mutableStateOf(live || message.presentationKey.startsWith("m-assistant-")) }
    if (live && !observedLive) SideEffect { observedLive = true }
    val interrupted = !live && message.statusLabel?.let { it == "已停止" || it.startsWith("已中断") || it == "失败" } == true
    val presentation = if (message.role == AgentMessageRole.ASSISTANT) {
        rememberAgentAnswerPresentation(message.content, animate = (live || observedLive) && !interrupted)
    } else null
    val revealing = presentation?.revealing == true
    var previewOrdinal by rememberSaveable(message.id) { mutableStateOf<Int?>(null) }
    // Duplicate ordinals are ambiguous and must never select an unrelated source.
    val sourcesByLabel = remember(message.sources) {
        message.sources.groupBy { it.label }.filterValues { it.size == 1 }.mapValues { it.value.single() }
    }
    previewOrdinal?.let { ordinal ->
        message.sources.singleOrNull { it.ordinal == ordinal }?.let { source ->
            AiSourcePreviewDialog(source, onDismiss = { previewOrdinal = null }, onOpenSource = {
                previewOrdinal = null
                if (it.canNavigate) onOpenSource(it) else onUnavailableSource(it)
            })
        }
    }
    AiMessageBubble(
        messageId = message.id,
        content = message.content,
        isUser = message.role == AgentMessageRole.USER,
        modifier = modifier,
        presentation = presentation,
        citationLabels = sourcesByLabel.keys,
        citationSources = sourcesByLabel,
        onCitationClick = { label -> previewOrdinal = sourcesByLabel[label]?.ordinal },
        beforeContent = {
            val waiting = (activeLabel != null || revealing) && presentation?.blocks.isNullOrEmpty()
            if (waiting || message.toolSteps.isNotEmpty()) {
                AiExplorationCard(message, waiting, activeLabel) { previewOrdinal = it.ordinal }
            }
        },
    ) {
        if (message.sources.isNotEmpty() && activeLabel == null && !revealing) {
            AiSourcesFooter(message) { previewOrdinal = it.ordinal }
        }
        val footer = message.statusLabel?.takeIf { activeLabel == null && it != "已完成" }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.labelSmall,
                color = UiRoles.pageSupporting,
            )
        }
    }
}
