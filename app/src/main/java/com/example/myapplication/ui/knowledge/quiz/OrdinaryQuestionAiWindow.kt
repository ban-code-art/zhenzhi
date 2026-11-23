package com.example.myapplication.ui.knowledge.quiz

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.learning.ChapterQaException
import com.example.myapplication.data.learning.OrdinaryQuestionAiPhase
import com.example.myapplication.data.learning.OrdinaryQuestionAiSnapshot
import com.example.myapplication.domain.ai.ChatMessage
import com.example.myapplication.ui.ai.AgentPresentedMarkdown
import com.example.myapplication.ui.ai.AiAnswerStatus
import com.example.myapplication.ui.ai.AiComposer
import com.example.myapplication.ui.ai.AiMessageBubble
import com.example.myapplication.ui.ai.rememberAgentAnswerPresentation
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiPane
import com.example.myapplication.ui.components.UiPaneRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val OrdinaryAiQuestionLimit = 2_000
private val OrdinaryAiContentWidth = 680.dp

private data class OrdinaryAiBubble(
    val id: String,
    val user: Boolean,
    val text: String,
    val streaming: Boolean = false,
    val notice: String? = null,
    val interrupted: Boolean = false,
)

@Composable
fun OrdinaryQuestionAiWindow(
    visible: Boolean,
    snapshot: OrdinaryQuestionAiSnapshot?,
    returnLabel: String = "返回作答",
    remainingSeconds: Int? = null,
    onDismiss: () -> Unit,
) {
    if (!visible || snapshot == null) return
    val context = LocalContext.current
    val app = context.applicationContext as? LearningApplication ?: return
    val container = app.container
    val identity by container.sessionManager.identity.collectAsState(initial = null)

    val threads = remember { mutableStateMapOf<String, List<OrdinaryAiBubble>>() }
    var draft by remember(snapshot.threadKey) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var ticket by remember { mutableLongStateOf(0L) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var closing by remember { mutableStateOf(false) }
    var showDetailCard by remember { mutableStateOf(false) }
    val jobs = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val currentSnapshot by rememberUpdatedState(snapshot)
    val bubbles = threads[snapshot.threadKey].orEmpty()

    LaunchedEffect(snapshot.bindingKey) {
        ticket++
        activeJob?.cancel()
        activeJob = null
        busy = false
        closing = false
    }

    DisposableEffect(Unit) {
        onDispose {
            closing = true
            ticket++
            activeJob?.cancel()
            activeJob = null
        }
    }

    LaunchedEffect(bubbles.size, bubbles.lastOrNull()?.text?.length) {
        if (bubbles.isNotEmpty()) {
            listState.animateScrollToItem(bubbles.lastIndex)
        }
    }

    fun stop() {
        ticket++
        activeJob?.cancel()
        activeJob = null
        busy = false
        val key = currentSnapshot.threadKey
        threads[key] = threads[key].orEmpty().map {
            if (it.streaming) it.copy(streaming = false, notice = "已停止生成", interrupted = true) else it
        }
    }

    fun close() {
        closing = true
        stop()
        onDismiss()
    }

    fun send(explicitPrompt: String? = null) {
        if (busy || closing) return
        val text = (explicitPrompt ?: draft).trim().take(OrdinaryAiQuestionLimit)
        if (text.isEmpty()) return
        val userId = identity?.userId ?: return
        val boundSnapshot = currentSnapshot
        val threadKey = boundSnapshot.threadKey
        val existing = threads[threadKey].orEmpty()
        val history = existing
            .filterNot { it.streaming }
            .map { if (it.user) ChatMessage.user(it.text) else ChatMessage.assistant(it.text) }
        val requestTicket = ++ticket
        val userBubbleId = "u-${UUID.randomUUID()}"
        val replyBubbleId = "a-${UUID.randomUUID()}"
        threads[threadKey] = existing +
            OrdinaryAiBubble(id = userBubbleId, user = true, text = text) +
            OrdinaryAiBubble(id = replyBubbleId, user = false, text = "", streaming = true)
        if (explicitPrompt == null) draft = ""
        busy = true

        activeJob = jobs.launch {
            fun updateReply(transform: (OrdinaryAiBubble) -> OrdinaryAiBubble) {
                if (requestTicket == ticket && !closing && currentSnapshot.bindingKey == boundSnapshot.bindingKey) {
                    threads[threadKey] = threads[threadKey].orEmpty().map {
                        if (it.id == replyBubbleId) transform(it) else it
                    }
                }
            }
            try {
                val reply = withContext(Dispatchers.IO) {
                    container.chapterQuestionAnswerService.askOrdinaryQuestion(
                        userId = userId,
                        snapshot = boundSnapshot,
                        question = text,
                        history = history,
                        validateContext = {
                            if (closing || currentSnapshot.bindingKey != boundSnapshot.bindingKey) {
                                throw CancellationException("Question context changed")
                            }
                        },
                        onDelta = { delta ->
                            withContext(Dispatchers.Main.immediate) {
                                updateReply { it.copy(text = it.text + delta) }
                            }
                        },
                    )
                }
                if (reply.boundKey == currentSnapshot.bindingKey) {
                    updateReply {
                        it.copy(
                            text = reply.answer,
                            streaming = false,
                            notice = if (reply.truncated) "回答已达长度限制，可继续追问" else null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ChapterQaException) {
                updateReply { it.copy(streaming = false, notice = error.message ?: "问答失败，请重试", interrupted = true) }
            } catch (error: Exception) {
                updateReply { it.copy(streaming = false, notice = error.message ?: "模型连接失败，请检查 AI 配置", interrupted = true) }
            } finally {
                if (requestTicket == ticket) {
                    busy = false
                    activeJob = null
                }
            }
        }
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("ordinary-question-ai-screen"),
            color = UiRoles.surface(UiSurfaceRole.Page),
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding(),
            ) {
                OrdinaryAiHeader(
                    snapshot = snapshot,
                    returnLabel = returnLabel,
                    onClose = ::close,
                    onShowDetail = { showDetailCard = true },
                )

                if (bubbles.isEmpty()) {
                    OrdinaryAiEmptyState(
                        snapshot = snapshot,
                        onQuickPrompt = { prompt -> send(prompt) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f)
                            .align(Alignment.CenterHorizontally)
                            .widthIn(max = OrdinaryAiContentWidth)
                            .fillMaxWidth(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(bubbles, key = { it.id }) { bubble ->
                            OrdinaryAiMessage(bubble = bubble)
                        }
                    }
                }

                Surface(color = UiRoles.surface(UiSurfaceRole.Card)) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        QuickPromptRow(
                            snapshot = snapshot,
                            enabled = !busy && !closing,
                            onQuickPrompt = { prompt -> send(prompt) },
                        )
                        AiComposer(
                            draft = draft,
                            onDraftChange = { draft = it.take(OrdinaryAiQuestionLimit) },
                            canSend = !closing && draft.isNotBlank() && identity?.userId != null,
                            onSend = { send() },
                            modifier = Modifier.widthIn(max = OrdinaryAiContentWidth)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            enabled = !busy && !closing && identity?.userId != null,
                            onStop = if (busy && !closing) ::stop else null,
                            placeholder = if (snapshot.isPreSubmitHintOnly) {
                                "针对这道题问问解题思路…"
                            } else {
                                "请 AI 讲解答案与易错点…"
                            },
                            inputModifier = Modifier.testTag("ordinary-question-ai-input"),
                            sendModifier = Modifier.testTag("ordinary-question-ai-send"),
                            stopModifier = Modifier.testTag("ordinary-question-ai-stop"),
                            actionPrefix = "ordinary.qa",
                        )
                        val footerText = when {
                            snapshot.phase == OrdinaryQuestionAiPhase.ANSWERING && remainingSeconds != null ->
                                "作答中只给提示 · 剩余 ${formatCountdown(remainingSeconds)} · 到时自动交卷"
                            snapshot.phase == OrdinaryQuestionAiPhase.BANK_ACTIVE_HIDDEN ->
                                "题目位于当前未交卷试卷中 · 暂不揭示标准答案与解析"
                            snapshot.phase == OrdinaryQuestionAiPhase.REVIEW ->
                                "已交卷模式 · 可结合你的作答、得分与解析提问"
                            else -> "已关联题干与知识点 · 可结合参考答案与解析提问"
                        }
                        Text(
                            text = footerText,
                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
                                .testTag("ordinary-question-ai-footer"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    if (showDetailCard) {
        BoundQuestionDetailDialog(snapshot = snapshot, onDismiss = { showDetailCard = false })
    }
}

@Composable
private fun OrdinaryAiHeader(
    snapshot: OrdinaryQuestionAiSnapshot,
    returnLabel: String,
    onClose: () -> Unit,
    onShowDetail: () -> Unit,
) {
    Surface(color = UiRoles.surface(UiSurfaceRole.Page)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier.widthIn(max = OrdinaryAiContentWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UiAction(
                    label = returnLabel,
                    icon = UiIcons.Back,
                    onClick = onClose,
                    modifier = Modifier.testTag("ordinary-question-ai-close"),
                    actionId = "ordinary.qa.close",
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "题目 AI",
                        modifier = Modifier.semantics { heading() },
                        style = UiRoles.text(UiRoles.pageTitle),
                    )
                    Text(
                        text = snapshot.sourceLabel,
                        modifier = Modifier.testTag("ordinary-question-ai-scope"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                UiAction(
                    label = "查看题目",
                    onClick = onShowDetail,
                    horizontalPadding = 10.dp,
                    actionId = "ordinary.qa.detail",
                )
            }
            UiPane(
                modifier = Modifier.widthIn(max = OrdinaryAiContentWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 10.dp)
                    .border(1.dp, UiRoles.accent.copy(alpha = 0.28f), RoundedCornerShape(16.dp)),
                role = UiPaneRole.Regular,
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = UiIcons.Records,
                        contentDescription = null,
                        tint = UiRoles.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val badgePrefix = snapshot.ordinalNumber?.let { "第 $it 题 · " }.orEmpty()
                        Text(
                            text = "已关联 · $badgePrefix${snapshot.typeDisplayName}",
                            modifier = Modifier.testTag("ordinary-question-ai-bound-badge"),
                            style = MaterialTheme.typography.labelLarge,
                            color = UiRoles.accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = snapshot.stem,
                            modifier = Modifier.testTag("ordinary-question-ai-bound-stem"),
                            style = MaterialTheme.typography.bodySmall,
                            color = UiRoles.foreground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun QuickPromptRow(
    snapshot: OrdinaryQuestionAiSnapshot,
    enabled: Boolean,
    onQuickPrompt: (String) -> Unit,
) {
    val prompts = if (snapshot.isPreSubmitHintOnly) {
        listOf("给我一点提示", "梳理审题要点")
    } else {
        listOf("讲讲这道题", "帮我梳理易错点")
    }
    Row(
        modifier = Modifier.widthIn(max = OrdinaryAiContentWidth)
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        prompts.forEach { prompt ->
            UiAction(
                label = prompt,
                onClick = { onQuickPrompt(prompt) },
                enabled = enabled,
                horizontalPadding = 12.dp,
                actionId = "ordinary.qa.chip.$prompt",
            )
        }
    }
}

@Composable
private fun OrdinaryAiEmptyState(
    snapshot: OrdinaryQuestionAiSnapshot,
    onQuickPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 380.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = UiIcons.Records,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                text = if (snapshot.isPreSubmitHintOnly) "需要审题思路吗？" else "想怎么复盘这道题？",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (snapshot.isPreSubmitHintOnly) {
                    "已绑定当前题干与选项/空位数。作答中会聚焦思路与经络穴位规律引导，不会提前泄露答案。"
                } else {
                    "已绑定本题完整题干、作答结果、参考答案与解析，可直接请 AI 剖析思路与记忆技巧。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            val mainChip = if (snapshot.isPreSubmitHintOnly) "给我一点提示" else "讲讲这道题"
            UiAction(
                label = mainChip,
                onClick = { onQuickPrompt(mainChip) },
                filled = true,
                actionId = "ordinary.qa.empty.prompt",
            )
        }
    }
}

@Composable
private fun OrdinaryAiMessage(bubble: OrdinaryAiBubble) {
    val messageId = "ordinary-qa-${bubble.id}"
    if (bubble.user) {
        AiMessageBubble(messageId = messageId, content = bubble.text, isUser = true)
        return
    }
    val presentation = rememberAgentAnswerPresentation(bubble.text, animate = !bubble.interrupted)
    Column(
        modifier = Modifier.fillMaxWidth().testTag("ordinary-question-ai-answer"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val waiting = (bubble.streaming || presentation.revealing) && presentation.blocks.isNullOrEmpty()
        if (waiting) {
            AiAnswerStatus(
                label = "正在结合当前题目思考…",
                running = true,
                modifier = Modifier.testTag("ordinary-question-ai-status"),
            )
        }
        if (!presentation.blocks.isNullOrEmpty()) {
            AgentPresentedMarkdown(
                messageId = messageId,
                presentation = presentation,
                onPageBackground = true,
            )
        }
        bubble.notice?.let { notice ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = UiRoles.surface(UiSurfaceRole.Block),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(
                    text = notice,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BoundQuestionDetailDialog(
    snapshot: OrdinaryQuestionAiSnapshot,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        UiPane(
            modifier = Modifier.fillMaxWidth(),
            role = UiPaneRole.Menu,
            shape = RoundedCornerShape(22.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${snapshot.typeDisplayName} · ${snapshot.maxScore} 分",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    UiAction(label = "关闭", onClick = onDismiss, horizontalPadding = 10.dp)
                }
                Text(snapshot.stem, style = MaterialTheme.typography.bodyLarge)
                if (snapshot.options.isNotEmpty()) {
                    snapshot.options.forEach { (key, value) ->
                        Text("$key. $value", style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (snapshot.questionType == "FILL") {
                    Text("共 ${snapshot.blankCount} 空", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (snapshot.canRevealSolution) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    snapshot.userAnswerText?.let {
                        Text("你的作答：$it", style = MaterialTheme.typography.bodyMedium)
                    }
                    snapshot.officialAnswerText?.let {
                        Text("参考答案：$it", style = MaterialTheme.typography.bodyMedium, color = UiRoles.accent)
                    }
                    snapshot.analysisText?.let {
                        Text("解析：$it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text("作答过程中不展示参考答案与解析。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun formatCountdown(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "%02d:%02d".format(safe / 60, safe % 60)
}
