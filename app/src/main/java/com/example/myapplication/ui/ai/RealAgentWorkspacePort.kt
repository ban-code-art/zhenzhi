package com.example.myapplication.ui.ai

import com.example.myapplication.data.agent.ConversationRepository
import com.example.myapplication.data.agent.RunCoordinator
import com.example.myapplication.data.agent.evidence.AgentSourcesCodec
import com.example.myapplication.data.agent.evidence.SourceNavigator
import com.example.myapplication.data.agent.notes.NoteProposalPort
import com.example.myapplication.data.agent.notes.NoteProposalRecord
import com.example.myapplication.data.agent.notes.NoteProposalStaleReason
import com.example.myapplication.data.agent.notes.NoteProposalStatus
import com.example.myapplication.data.agent.notes.NoteProposalTargetKind
import com.example.myapplication.data.agent.notes.ProposalApplyOutcome
import com.example.myapplication.data.agent.notes.ProposalRejectOutcome
import com.example.myapplication.data.local.entity.AiMessageEntity
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.ReadingScope
import com.example.myapplication.domain.reader.ScopeRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 真实 [AgentWorkspacePort]：会话持久 + RunCoordinator 运行编排（W4-11 接线）。
 *
 * 设计要点（与详细设计 §5/§17/§21 对齐）：
 * - 关闭 UI 不 stop：本类不实现“关闭即停”，UI 的关闭只退订阅（VM 层保证）；
 * - 流式文本来自 ai_messages 的 STREAMING 行轮询（运行时按 checkpoint 更新同一行）；
 * - 工具步骤来自 assistant.toolCalls 与当前 run 的持久工具事件，按 callId 更新；
 * - 来源来自 `ai_messages.sources_json`（W4-02 已在 run 终态写入）：解码出真实
 *   referenceId/quote/locatorJson 后再显示卡片，不再从回答正文正则猜 [S#]，
 *   也不允许出现“来源明细尚未持久化”这类占位来源；
 * - 传入 [SourceNavigator] 时按持久 referenceId 做一次可用性核验：源删除/改版/撤权
 *   转成 SourceUnavailable 卡片（§17），仍保留历史 quote，只是不可跳转；
 * - 笔记提议（§6/§20）：待审清单来自 `ai_note_proposals`（会话内 PENDING，重启可查）；
 *   确认/拒绝由用户显式触发，确认时用**当前** AccessContext 重新授权 + 同事务 CAS，
 *   不复用旧 run 的授权，也不自动确认/自动重放。
 */
class RealAgentWorkspacePort(
    private val sessionManager: SessionManager,
    private val conversations: ConversationRepository,
    private val coordinator: RunCoordinator,
    /** 笔记提议端口（生产 = NoteProposalService）：唯一写入口，确认时才写正文。 */
    private val noteProposals: NoteProposalPort,
    /** 持久来源导航端口（可选）：为 null 时只按 sources_json 里的 locator 判断可跳转。 */
    private val sourceNavigator: SourceNavigator? = null,
) : AgentWorkspacePort {

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun observeIdentity(): Flow<SessionIdentity> = sessionManager.identity.distinctUntilChanged()

    private suspend fun context(): AccessContext {
        val identity = sessionManager.identity.first()
        val userId = identity.userId ?: throw IllegalStateException("未登录，无法使用 AI 助手")
        return AccessContext(userId = userId, sessionEpoch = identity.epoch, requestId = "ui-${UUID.randomUUID()}")
    }

    private suspend fun checkIdentity(ctx: AccessContext) {
        val identity = sessionManager.identity.first()
        check(identity.userId == ctx.userId && identity.epoch == ctx.sessionEpoch) { "登录状态已变化，请重新打开工作区" }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeConversations(): Flow<List<AgentConversationUi>> =
        sessionManager.identity.flatMapLatest { identity ->
            val userId = identity.userId
            if (userId == null) {
                flowOf(emptyList())
            } else {
                conversations.observeConversations(userId, ConversationRepository.WORKFLOW_CHAT)
                    .map { rows ->
                        if (sessionManager.identity.first() != identity) emptyList() else
                            rows.map { row -> row.toConversationUi(timeFormat.format(Date(row.updatedAt))) }
                    }
            }
        }

    override suspend fun startRun(
        launch: AgentLaunchContext,
        conversationId: String?,
        userMessage: String,
    ): StartRunReply {
        val ctx = context()
        if (userMessage.isBlank()) throw IllegalStateException("消息不能为空")
        val encCtx = launch.encyclopediaContext
        val documentId = launch.documentId?.takeIf { it.isNotBlank() }
        if (encCtx == null && documentId == null) {
            throw IllegalStateException("请从阅读页或经学百科打开 AI 工作区（需要绑定当前上下文）")
        }
        val conversation = if (conversationId.isNullOrBlank()) {
            conversations.createConversation(
                userId = ctx.userId,
                title = userMessage.trim().take(24),
                workflow = ConversationRepository.WORKFLOW_CHAT,
                documentId = documentId,
                scopeJson = encCtx?.let {
                    buildJsonObject { put("encyclopediaContext", it.toJson()) }.toString()
                } ?: "{}",
            )
        } else {
            conversations.findConversation(ctx.userId, conversationId)
                ?: throw IllegalStateException("会话不存在或无权访问")
        }
        if (encCtx != null) {
            check(conversation.documentId == null && conversation.scopeJson.contains("\"encyclopediaContext\"")) {
                "当前会话绑定其他文档，请新建会话"
            }
        } else {
            check(conversation.documentId == documentId) { "当前会话绑定其他文档，请新建会话" }
        }
        checkIdentity(ctx)
        val scope = if (encCtx != null) {
            ScopeRequest(documentIds = emptyList(), readingScope = ReadingScope.CURRENT_SECTION)
        } else {
            ScopeRequest(
                documentIds = listOf(checkNotNull(documentId)),
                readingScope = if (launch.sectionId.isNullOrBlank()) ReadingScope.CURRENT_DOCUMENT else ReadingScope.CURRENT_SECTION,
            )
        }
        val reply = when (
            val outcome = coordinator.start(
                ctx = ctx,
                conversationId = conversation.id,
                userMessage = userMessage.trim(),
                scopeRequest = scope,
                readingContext = launch.toReadingContext(),
                requestKey = "ui-agent-${UUID.randomUUID()}",
            )
        ) {
            is RunCoordinator.StartOutcome.Started -> StartRunReply(runId = outcome.runId, conversationId = conversation.id, ownsRun = true)
            is RunCoordinator.StartOutcome.Existing -> StartRunReply(runId = outcome.runId, conversationId = conversation.id, ownsRun = false)
            // 同会话已有活跃 run：不新起，也不 stop；提示用户先等待/停止
            is RunCoordinator.StartOutcome.Busy ->
                StartRunReply(runId = outcome.activeRunId, busyWithRunId = outcome.activeRunId, conversationId = conversation.id, ownsRun = false)

            is RunCoordinator.StartOutcome.Rejected -> throw IllegalStateException("启动失败：${outcome.code}")
        }
        try {
            checkIdentity(ctx)
            return reply
        } catch (error: Exception) {
            // The app scope already owns the new run; a cancelled UI must not lose
            // its only start receipt during this last identity suspension.
            if (reply.ownsRun && reply.busyWithRunId == null) {
                try {
                    withContext(NonCancellable) { coordinator.stop(ctx, reply.runId) }
                } catch (cleanup: Exception) {
                    if (cleanup !== error) error.addSuppressed(cleanup)
                }
            }
            throw error
        }
    }

    override suspend fun latestRun(conversationId: String): StartRunReply? {
        val ctx = context()
        val run = if (conversationId.isBlank()) null else coordinator.latestRun(ctx.userId, conversationId)
        checkIdentity(ctx)
        return run?.let { StartRunReply(it.id, conversationId = it.conversationId, ownsRun = false) }
    }

    override suspend fun stopRun(runId: String) {
        val ctx = context()
        coordinator.stop(ctx, runId)
        checkIdentity(ctx)
    }

    override suspend fun renameConversation(conversationId: String, title: String) {
        val ctx = context()
        if (!conversations.rename(ctx.userId, conversationId, title)) {
            throw IllegalStateException("重命名失败（会话可能已删除）")
        }
        checkIdentity(ctx)
    }

    override suspend fun archiveConversation(conversationId: String) {
        val ctx = context()
        if (!conversations.archive(ctx.userId, conversationId)) {
            throw IllegalStateException("归档失败（会话可能已删除）")
        }
        checkIdentity(ctx)
    }

    override suspend fun unarchiveConversation(conversationId: String) {
        val ctx = context()
        if (!conversations.unarchive(ctx.userId, conversationId)) {
            throw IllegalStateException("取消归档失败（会话可能已删除）")
        }
        checkIdentity(ctx)
    }

    override suspend fun deleteConversation(conversationId: String) {
        val ctx = context()
        conversations.delete(ctx.userId, conversationId)
        checkIdentity(ctx)
    }

    override suspend fun loadMoreConversations(offset: Int): List<AgentConversationUi> {
        val ctx = context()
        val rows = conversations.listConversations(
            userId = ctx.userId,
            includeArchived = true,
            limit = PAGE_SIZE,
            offset = offset,
            workflow = ConversationRepository.WORKFLOW_CHAT,
        ).map { row -> row.toConversationUi(timeFormat.format(Date(row.updatedAt))) }
        checkIdentity(ctx)
        return rows
    }

    // ------------------------------------------------------ 历史回读（P4-03）

    override suspend fun loadHistory(conversationId: String, limit: Int): List<AgentMessageUi> {
        if (conversationId.isBlank()) return emptyList()
        val identity = sessionManager.identity.first()
        val userId = identity.userId ?: return emptyList()
        val ctx = AccessContext(userId = userId, sessionEpoch = identity.epoch, requestId = "ui-history")
        // 越权/会话不存在一律返回空列表（不泄露其它用户的会话存在性）。
        val conversation = conversations.findConversation(userId, conversationId)
        if (conversation == null) {
            checkIdentity(ctx)
            return emptyList()
        }
        val entities = conversations.recentMessages(
            conversation.id,
            limit.coerceIn(1, AgentHistoryMapper.MAX_HISTORY_MESSAGES),
        )
        // sources 先按消息 id 一次性解码+复核（只读，不写库），不依赖正文正则。
        val sourcesByMessageId = entities.mapNotNull { entity ->
            if (entity.role != AgentHistoryMapper.ROLE_ASSISTANT) return@mapNotNull null
            val sources = historySources(entity.sourcesJson, ctx)
            if (sources.isEmpty()) null else entity.id to sources
        }.toMap()
        val messages = withContext(kotlinx.coroutines.Dispatchers.Default) {
            AgentHistoryMapper.toMessageUi(entities, sourcesByMessageId)
        }
        checkIdentity(ctx)
        return messages
    }

    /**
     * 历史 `sources_json` 快照 → 来源卡片；坏 JSON/空快照降级为空列表，不打断回读。
     * 有 SourceNavigator 时按 referenceId 复核可用性（删除/改版/撤权 → 保留 quote 不可跳转）。
     */
    private suspend fun historySources(sourcesJson: String?, ctx: AccessContext): List<AgentSourceUi> {
        val records = AgentSourcesCodec.decode(sourcesJson)
        if (records.isEmpty()) return emptyList()
        val navigator = sourceNavigator ?: return records.map { it.toSourceUi() }
        return records.map { record ->
            val outcome = try { navigator.open(ctx, record.referenceId) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            if (outcome == null) {
                record.toSourceUi().copy(unavailableReason = "来源核验失败，暂时无法跳转")
            } else {
                record.toSourceUi(outcome)
            }
        }
    }

    // -------------------------------------------------------- 笔记提议（§6/§20）

    override suspend fun pendingProposals(conversationId: String?): List<AgentNoteProposalUi> {
        if (conversationId.isNullOrBlank()) return emptyList()
        val ctx = context()
        // 只读查询：不触发任何写正文；进程重启后 PENDING 仍能显示（使用当前 ctx 授权）。
        val proposals = noteProposals.pendingProposalsForConversation(ctx, conversationId).map { it.toUi() }
        checkIdentity(ctx)
        return proposals
    }

    override suspend fun confirmProposal(proposalId: String): AgentProposalActionResult {
        // 关键：用**当前** AccessContext 重新授权（不缓存生成提议时的 sessionEpoch/run 权限）。
        val ctx = context()
        val result = when (val outcome = noteProposals.applyProposal(ctx, proposalId)) {
            is ProposalApplyOutcome.Applied -> AgentProposalActionResult.Confirmed(outcome.revision)
            is ProposalApplyOutcome.Stale -> AgentProposalActionResult.Stale(
                reason = outcome.reason.toReadable(),
                currentRevision = outcome.currentRevision,
                draftPreview = outcome.draftBody?.take(80),
            )

            is ProposalApplyOutcome.Rejected -> AgentProposalActionResult.Rejected(
                code = outcome.code,
                message = "提议确认被拒绝：${outcome.code}",
            )

            is ProposalApplyOutcome.Failed -> AgentProposalActionResult.Rejected(
                code = outcome.code,
                message = "提议确认失败：${outcome.code}（可重试）",
            )
        }
        checkIdentity(ctx)
        return result
    }

    override suspend fun rejectProposal(proposalId: String): AgentProposalActionResult {
        val ctx = context()
        val result = when (val outcome = noteProposals.rejectProposal(ctx, proposalId)) {
            is ProposalRejectOutcome.Rejected -> AgentProposalActionResult.Rejected("REJECTED", "已拒绝提议")
            is ProposalRejectOutcome.AlreadyResolved -> AgentProposalActionResult.AlreadyResolved(outcome.status)
            ProposalRejectOutcome.NotFound -> AgentProposalActionResult.NotFound
        }
        checkIdentity(ctx)
        return result
    }

    /** Rebuild from durable seq/message associations; closing the flow never stops the run. */
    override fun observeRun(runId: String): Flow<AgentRunEvent> = flow {
        val ctx = context()
        var cursor = -1L
        val sourcesCache = HashMap<String, Pair<String?, List<AgentSourceUi>>>()
        while (true) {
            checkIdentity(ctx)
            val run = coordinator.findRun(ctx.userId, runId)
                ?: throw IllegalStateException("运行不存在或无权访问")
            val events = coordinator.eventsSince(ctx.userId, runId)
            val terminalEvent = when (run.status) {
                RunCoordinator.STATUS_COMPLETED -> RunCoordinator.EVENT_COMPLETED
                RunCoordinator.STATUS_CANCELLED -> RunCoordinator.EVENT_CANCELLED
                RunCoordinator.STATUS_INTERRUPTED -> RunCoordinator.EVENT_INTERRUPTED
                RunCoordinator.STATUS_ERROR -> RunCoordinator.EVENT_ERROR
                else -> null
            }
            // Status and terminal event may be read between their writes. Never publish a
            // terminal snapshot on an old cursor, or the next subscription would lose it.
            if (terminalEvent != null && events.none { it.type == terminalEvent }) {
                delay(POLL_MS)
                continue
            }
            val terminalPhase = events.lastOrNull { it.type in TERMINAL_EVENTS }?.type?.let { type ->
                when (type) {
                    RunCoordinator.EVENT_COMPLETED -> AgentRunPhase.COMPLETED
                    RunCoordinator.EVENT_CANCELLED -> AgentRunPhase.CANCELLED
                    RunCoordinator.EVENT_INTERRUPTED -> AgentRunPhase.INTERRUPTED
                    else -> AgentRunPhase.ERROR
                }
            }
            val seq = events.maxOfOrNull { it.seq } ?: 0L
            if (seq > cursor) {
                val ids = AgentRunProjection.messageIds(runId, events)
                val rows = runMessages(ctx, run.conversationId, ids)
                val sources = rows.filter { it.role == "assistant" }.associate { row ->
                    val cached = sourcesCache[row.id]
                    val items = if (cached != null && cached.first == row.sourcesJson) cached.second else
                        historySources(row.sourcesJson, ctx).also { sourcesCache[row.id] = row.sourcesJson to it }
                    row.id to items
                }
                val snapshot = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    AgentRunProjection.project(runId, terminalPhase ?: run.status.toPhase(), events, rows, sources)
                }
                checkIdentity(ctx)
                emit(AgentRunEvent.Snapshot(snapshot.seq, snapshot.messages, snapshot.phase))
                cursor = snapshot.seq
            }
            if (terminalPhase != null) return@flow
            delay(POLL_MS)
        }
    }

    /** Keep only this run's rows, even when an old or deleted association requires a full scan. */
    private suspend fun runMessages(
        ctx: AccessContext,
        conversationId: String,
        messageIds: Set<String>,
    ): List<AiMessageEntity> {
        val remaining = messageIds.toMutableSet()
        val rows = ArrayList<AiMessageEntity>()
        var offset = 0
        while (remaining.isNotEmpty()) {
            checkIdentity(ctx)
            val page = conversations.pageMessages(conversationId, limit = RUN_MESSAGE_PAGE_SIZE, offset = offset)
            checkIdentity(ctx)
            for (message in page) {
                if (remaining.remove(message.id)) rows += message
            }
            if (page.size < RUN_MESSAGE_PAGE_SIZE) break
            offset = Math.addExact(offset, page.size)
        }
        return rows
    }

    private fun String.toPhase(): AgentRunPhase = when (this) {
        RunCoordinator.STATUS_PREPARING -> AgentRunPhase.PREPARING
        RunCoordinator.STATUS_GENERATING, "RUNNING" -> AgentRunPhase.GENERATING
        RunCoordinator.STATUS_TOOL_RUNNING -> AgentRunPhase.TOOL_RUNNING
        RunCoordinator.STATUS_COMPLETED -> AgentRunPhase.COMPLETED
        RunCoordinator.STATUS_CANCELLED -> AgentRunPhase.CANCELLED
        RunCoordinator.STATUS_INTERRUPTED -> AgentRunPhase.INTERRUPTED
        RunCoordinator.STATUS_ERROR -> AgentRunPhase.ERROR
        else -> AgentRunPhase.PREPARING
    }

    /** 持久提议 → UI 卡片模型：diff/目标/版本/指纹全部来自持久化字段，不由 UI 重算。 */
    private fun NoteProposalRecord.toUi(): AgentNoteProposalUi = AgentNoteProposalUi(
        id = id,
        targetKindLabel = when (targetKind) {
            NoteProposalTargetKind.NOTEBOOK -> "笔记本"
            NoteProposalTargetKind.ANNOTATION_NOTE -> "标注附注"
        },
        targetId = targetId,
        targetRevision = baseRevision,
        reason = reason,
        edits = edits.map { edit -> AgentPatchEditUi(oldText = edit.oldText, newText = edit.newText) },
        previewHash = previewHash,
        status = status,
        staleReason = if (status == NoteProposalStatus.STALE) "目标版本已变化或本地草稿未保存，请重新生成提议" else null,
    )

    private fun NoteProposalStaleReason.toReadable(): String = when (this) {
        NoteProposalStaleReason.REVISION_CHANGED -> "目标正文已被修改（revision 已变），提议未写入"
        NoteProposalStaleReason.DIRTY_DRAFT -> "你还有未保存的本地草稿，提议未覆盖"
        NoteProposalStaleReason.RECEIPT_MISMATCH -> "读取回执与目标不一致，提议未写入"
        NoteProposalStaleReason.PAYLOAD_MISMATCH -> "提议载荷校验失败，提议未写入"
    }

    private companion object {
        const val POLL_MS = 300L
        const val PAGE_SIZE = 30
        const val RUN_MESSAGE_PAGE_SIZE = 64
        val TERMINAL_EVENTS = setOf(RunCoordinator.EVENT_COMPLETED, RunCoordinator.EVENT_CANCELLED,
            RunCoordinator.EVENT_INTERRUPTED, RunCoordinator.EVENT_ERROR)
    }
}
