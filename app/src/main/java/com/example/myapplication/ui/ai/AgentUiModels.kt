package com.example.myapplication.ui.ai

import com.example.myapplication.data.encyclopedia.EncyclopediaAgentLaunchContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Agent 工作区状态模型与端口。
 *
 * 本文件只属于 UI 层：不依赖 RunCoordinator/HTTP/DB。生产由 AppContainer
 * 提供 RealAgentWorkspacePort；[FakeAgentWorkspacePort] 仅供明确的测试/预览。
 * 状态字段按第四期详细设计 §5/§17 定义：
 *  - 关闭（close）≠ 停止（stop）：关闭只退 UI，运行仍由运行编排持有；
 *  - 来源卡片区分“可精确跳转”和“来源已失效但保留 quote”；
 *  - 工具步骤只展示名称/状态/摘要，不展示隐藏思维。
 */
data class AgentLaunchContext(
    val documentId: String? = null,
    val documentTitle: String? = null,
    val sectionId: String? = null,
    val sectionTitle: String? = null,
    /** 选区原文（无选区时为空，表示按当前节/当前书追问）。 */
    val quote: String? = null,
    /** ReaderLocator JSON；只用于展示与来源定位，不参与权限判断。 */
    val locatorJson: String? = null,
    /** 经学百科独立词条上下文（与书库 documentId 完全隔离）。 */
    val encyclopediaContext: EncyclopediaAgentLaunchContext? = null,
) {
    val hasReadingContext: Boolean get() = !documentId.isNullOrBlank() || encyclopediaContext != null
}

enum class AgentRunPhase { IDLE, PREPARING, GENERATING, TOOL_RUNNING, COMPLETED, CANCELLED, INTERRUPTED, ERROR }

enum class AgentMessageRole { USER, ASSISTANT, SYSTEM }

enum class AgentToolStatus { RUNNING, SUCCEEDED, FAILED }

data class AgentToolStepUi(
    val callId: String,
    val toolName: String,
    val status: AgentToolStatus,
    val summary: String,
    val action: String = "查找",
    val subject: String = "",
    val range: String? = null,
    val documentId: String? = null,
    val results: List<AgentToolHitUi> = emptyList(),
    val sectionId: String? = null,
    val rangeStart: Int? = null,
    val rangeEnd: Int? = null,
)

data class AgentToolHitUi(val documentId: String, val title: String, val quote: String, val sectionId: String? = null)

/**
 * 来源卡片（回答里的 [S1]/[S2]）。
 * [unavailableReason] 非空表示来源已删除/改版：必须显示历史 quote，但不可跳转。
 */
data class AgentSourceUi(
    val ordinal: Int,
    val documentId: String,
    val documentTitle: String,
    val sectionId: String?,
    val sectionTitle: String?,
    val quote: String,
    val locatorJson: String? = null,
    /** 持久来源引用 id（SourceNavigator 用）；缺失时只能展示 quote。 */
    val referenceId: String? = null,
    val unavailableReason: String? = null,
) {
    val label: String get() = "S$ordinal"
    val canNavigate: Boolean get() = unavailableReason == null && !locatorJson.isNullOrBlank()
}

data class AgentMessageUi(
    val id: String,
    val role: AgentMessageRole,
    val content: String,
    val streaming: Boolean = false,
    val toolSteps: List<AgentToolStepUi> = emptyList(),
    val sources: List<AgentSourceUi> = emptyList(),
    val statusLabel: String? = null,
    val presentationKey: String = id,
)

data class AgentConversationUi(
    val id: String,
    val title: String,
    val updatedAtLabel: String,
    val archived: Boolean = false,
)

data class AgentConversationRenameUi(
    val conversationId: String,
    val sequence: Long,
    val running: Boolean,
    val error: String? = null,
)

data class AgentUiState(
    val phase: AgentRunPhase = AgentRunPhase.IDLE,
    val messages: List<AgentMessageUi> = emptyList(),
    val streamingText: String = "",
    val draft: String = "",
    val conversations: List<AgentConversationUi> = emptyList(),
    val activeConversationId: String? = null,
    val activeConversationTitle: String = "新会话",
    val conversationsHasMore: Boolean = false,
    val readingContext: AgentLaunchContext? = null,
    /** 会话内待确认/已失效的笔记提议（进程重启后仍可查；确认必须用户点击）。 */
    val noteProposals: List<AgentNoteProposalUi> = emptyList(),
    /** 正在确认/拒绝的提议 id（按钮禁用，防重复点击/重复写）。 */
    val proposalBusyId: String? = null,
    /** 打开历史会话时正在从 ai_messages 回读（占位提示，不回显“骨架”文案）。 */
    val historyLoading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** 身份/epoch变化后旧工作区需重新打开，不能提交旧上下文。 */
    val sessionChanged: Boolean = false,
    val renameUpdate: AgentConversationRenameUi? = null,
) {
    val isRunning: Boolean
        get() = phase == AgentRunPhase.PREPARING || phase == AgentRunPhase.GENERATING || phase == AgentRunPhase.TOOL_RUNNING

    val canSend: Boolean get() = !sessionChanged && !historyLoading && !isRunning && draft.isNotBlank()

    val stopLabel: String get() = if (isRunning) "停止" else "已完成"
}

/** UI → 持久运行编排端口；latestRun/observeRun 只读，绝不自动再次生成。 */
interface AgentWorkspacePort {
    fun observeIdentity(): Flow<com.example.myapplication.data.session.SessionIdentity> = kotlinx.coroutines.flow.emptyFlow()
    fun observeConversations(): Flow<List<AgentConversationUi>>
    fun observeRun(runId: String): Flow<AgentRunEvent>
    suspend fun startRun(launch: AgentLaunchContext, conversationId: String?, userMessage: String): StartRunReply
    /** 最近一次运行（包含终态），只读恢复，不自动重新生成。 */
    suspend fun latestRun(conversationId: String): StartRunReply? = null
    suspend fun stopRun(runId: String)
    suspend fun renameConversation(conversationId: String, title: String)
    suspend fun archiveConversation(conversationId: String)
    suspend fun unarchiveConversation(conversationId: String) {
        error("当前会话无法取消归档")
    }
    suspend fun deleteConversation(conversationId: String)
    suspend fun loadMoreConversations(offset: Int): List<AgentConversationUi>

    /**
     * 打开历史会话时回读最近 [limit] 条消息（P4-03：分页/历史可见）。
     *
     * 只读：内容与 sources 都来自 `ai_messages` 持久快照（user/assistant 展示正文；
     * tool 组隐藏，不从截断摘要反推 toolSteps，也不伪造 [S#] 引用）。
     * 越权/会话不存在/未登录返回空列表。
     */
    suspend fun loadHistory(conversationId: String, limit: Int = 50): List<AgentMessageUi>

    /**
     * 会话内仍待审的笔记提议（只读查询；不触发写正文）。
     * 进程重启后仍可查：确认使用当前 AccessContext，而不是旧 run 的授权。
     */
    suspend fun pendingProposals(conversationId: String?): List<AgentNoteProposalUi>

    /** 用户确认提议：内部用当前 ctx 再授权 + 同事务 CAS；UI 不会自动调用。 */
    suspend fun confirmProposal(proposalId: String): AgentProposalActionResult

    /** 用户拒绝提议：PENDING → REJECTED，不写正文。 */
    suspend fun rejectProposal(proposalId: String): AgentProposalActionResult
}

data class StartRunReply(val runId: String, val busyWithRunId: String? = null, val conversationId: String? = null, val ownsRun: Boolean = true)

/** 运行事件（UI 只消费展示所需子集）。 */
sealed interface AgentRunEvent {
    val seq: Long

    data class Snapshot(override val seq: Long, val messages: List<AgentMessageUi>, val phase: AgentRunPhase) : AgentRunEvent
    data class PhaseChanged(override val seq: Long, val phase: AgentRunPhase) : AgentRunEvent
    data class AssistantDelta(override val seq: Long, val text: String) : AgentRunEvent
    data class ToolStep(override val seq: Long, val step: AgentToolStepUi) : AgentRunEvent
    data class SourcesAttached(override val seq: Long, val sources: List<AgentSourceUi>) : AgentRunEvent
    data class Completed(override val seq: Long, val statusLabel: String) : AgentRunEvent
    data class Failed(override val seq: Long, val code: String, val message: String) : AgentRunEvent
}

/**
 * 确定性 fake：给 Compose 冒烟/设计联调用；不访问网络、不写库。
 * 真实接线前 UI 只依赖 [AgentWorkspacePort]，因此替换实现不需要改页面。
 */
class FakeAgentWorkspacePort : AgentWorkspacePort {
    private val conversations = MutableStateFlow(SAMPLE_CONVERSATIONS)
    private var eventSeq = 0L

    override fun observeConversations(): Flow<List<AgentConversationUi>> = conversations.asStateFlow()

    override fun observeRun(runId: String): Flow<AgentRunEvent> = flow {
        eventSeq = 0
        emit(AgentRunEvent.PhaseChanged(++eventSeq, AgentRunPhase.PREPARING))
        delay(120)
        emit(AgentRunEvent.PhaseChanged(++eventSeq, AgentRunPhase.TOOL_RUNNING))
        emit(
            AgentRunEvent.ToolStep(
                ++eventSeq,
                AgentToolStepUi("call-1", "search_knowledge", AgentToolStatus.SUCCEEDED, "命中 5 条候选，核验 2 条原文"),
            ),
        )
        emit(AgentRunEvent.PhaseChanged(++eventSeq, AgentRunPhase.GENERATING))
        val answer = "根据当前章节与检索到的原文，可以从三个角度理解这个问题。\n\n" +
            "第一，概念边界需要按原文定义；第二，例证要能回到具体段落；第三，注意区分相近说法。"
        answer.chunked(12).forEach { piece ->
            delay(40)
            emit(AgentRunEvent.AssistantDelta(++eventSeq, piece))
        }
        emit(
            AgentRunEvent.SourcesAttached(
                ++eventSeq,
                listOf(
                    AgentSourceUi(
                        ordinal = 1,
                        documentId = "doc-sample",
                        documentTitle = "示例文档（非医学内容）",
                        sectionId = "sec-1",
                        sectionTitle = "第一节 示例",
                        quote = "这是用于界面冒烟的示例原文片段，不构成任何事实或医学结论。",
                        locatorJson = "{\"schemaVersion\":1}",
                    ),
                    AgentSourceUi(
                        ordinal = 2,
                        documentId = "doc-removed",
                        documentTitle = "已删除的示例文档",
                        sectionId = null,
                        sectionTitle = null,
                        quote = "历史 quote 仍应可见，但来源已失效。",
                        locatorJson = null,
                        unavailableReason = "来源已删除，无法精确跳转",
                    ),
                ),
            ),
        )
        emit(AgentRunEvent.Completed(++eventSeq, "已完成"))
    }

    override suspend fun startRun(
        launch: AgentLaunchContext,
        conversationId: String?,
        userMessage: String,
    ): StartRunReply {
        val id = conversationId ?: "conv-${System.currentTimeMillis() % 1000}"
        return StartRunReply(runId = "run-$id")
    }

    override suspend fun stopRun(runId: String) = Unit

    override suspend fun renameConversation(conversationId: String, title: String) {
        conversations.value = conversations.value.map { if (it.id == conversationId) it.copy(title = title) else it }
    }

    override suspend fun archiveConversation(conversationId: String) {
        conversations.value = conversations.value.map { if (it.id == conversationId) it.copy(archived = true) else it }
    }

    override suspend fun unarchiveConversation(conversationId: String) {
        conversations.value = conversations.value.map { if (it.id == conversationId) it.copy(archived = false) else it }
    }

    override suspend fun deleteConversation(conversationId: String) {
        conversations.value = conversations.value.filterNot { it.id == conversationId }
    }

    override suspend fun loadMoreConversations(offset: Int): List<AgentConversationUi> = emptyList()

    // 骨架端口没有历史库：返回空（不伪造历史消息）。
    override suspend fun loadHistory(conversationId: String, limit: Int): List<AgentMessageUi> = emptyList()

    // 骨架模式不提供真实提议服务：不伪造可确认的提议，也不自动确认任何内容。
    override suspend fun pendingProposals(conversationId: String?): List<AgentNoteProposalUi> = emptyList()

    override suspend fun confirmProposal(proposalId: String): AgentProposalActionResult =
        AgentProposalActionResult.Unavailable("当前 UI 使用骨架端口，未接入笔记提议服务")

    override suspend fun rejectProposal(proposalId: String): AgentProposalActionResult =
        AgentProposalActionResult.Unavailable("当前 UI 使用骨架端口，未接入笔记提议服务")

    private companion object {
        val SAMPLE_CONVERSATIONS = listOf(
            AgentConversationUi("conv-1", "关于本章概念的追问", "今天 14:20"),
            AgentConversationUi("conv-2", "把要点整理成复习提纲", "昨天 20:05"),
            AgentConversationUi("conv-3", "旧会话（已归档）", "10-18", archived = true),
        )
    }
}
