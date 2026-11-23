package com.example.myapplication.ui.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.session.SessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val CONVERSATION_PAGE_SIZE = 30

/** UI subscriptions are disposable; the coordinator owns each persisted run. */
class AgentViewModel(
    private val port: AgentWorkspacePort = FakeAgentWorkspacePort(),
    private val launch: AgentLaunchContext = AgentLaunchContext(),
    taskScope: CoroutineScope? = null,
) : ViewModel() {
    private val uiScope = taskScope ?: viewModelScope
    private val _state = MutableStateFlow(AgentUiState(readingContext = launch.takeIf { it.hasReadingContext }))
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var runId: String? = null
    private var eventJob: Job? = null
    private class Preparation(val generation: Long, val submittedDraft: String) {
        var job: Job? = null
        var stopRequested = false
    }
    private var preparation: Preparation? = null
    private var generation = 0L
    private var proposalRequest = 0L
    private var uiAttached = true
    private var returnConversationRestored = false
    private var returnDraftRestored = false
    private var optimisticIds = emptySet<String>()
    private var projectedRunIds = emptySet<String>()
    private val drafts = mutableMapOf<String, String>()
    private var conversationRows = emptyList<AgentConversationUi>()
    private var conversationLimit = CONVERSATION_PAGE_SIZE
    private var renameSequence = 0L
    private val archiving = mutableSetOf<String>()

    init {
        uiScope.launch {
            var baseline: SessionIdentity? = null
            attempt {
                port.observeIdentity().collect { identity ->
                    val previous = baseline
                    baseline = identity
                    if (previous != null && previous != identity) invalidateIdentity()
                }
            }.onFailure { invalidateIdentity() }
        }
        uiScope.launch {
            attempt {
                port.observeConversations().collect { conversations ->
                    if (!_state.value.sessionChanged) {
                        conversationRows = conversations
                        updateConversationPage()
                    }
                }
            }.onFailure { error -> _state.update { it.copy(error = error.message ?: "会话加载失败") } }
        }
        refreshProposals()
    }

    private fun invalidateIdentity() {
        ++generation
        ++proposalRequest
        eventJob?.cancel()
        eventJob = null
        runId = null
        preparation = null
        optimisticIds = emptySet()
        projectedRunIds = emptySet()
        drafts.clear()
        conversationRows = emptyList()
        conversationLimit = CONVERSATION_PAGE_SIZE
        archiving.clear()
        returnConversationRestored = false
        returnDraftRestored = false
        uiAttached = false
        _state.value = AgentUiState(sessionChanged = true, error = "登录状态已变化，请重新打开工作区")
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    fun restoreReturnConversation(conversationId: String?, draft: String? = null) {
        if (_state.value.sessionChanged || returnConversationRestored) return
        if (conversationId.isNullOrBlank()) {
            restoreDraft(draft)
            return
        }
        val current = _state.value
        if (current.activeConversationId != null) {
            returnConversationRestored = true
            restoreDraft(draft)
            return
        }
        val conversation = conversationRows.firstOrNull { it.id == conversationId } ?: return
        returnConversationRestored = true
        openConversation(conversation)
        restoreDraft(draft)
    }

    private fun restoreDraft(draft: String?) {
        if (returnDraftRestored || draft == null) return
        returnDraftRestored = true
        if (_state.value.draft.isEmpty()) updateDraft(draft)
    }

    /** Called when this retained ViewModel is visible again after a source excursion. */
    fun resumeUi() {
        if (_state.value.sessionChanged) return
        uiAttached = true
        // Keep this admission's generation: its reply will attach the run when it arrives.
        if (preparation?.generation == generation) return
        val current = _state.value
        if (eventJob?.isActive == true || current.activeConversationId == null) return
        openConversation(AgentConversationUi(current.activeConversationId, current.activeConversationTitle, ""))
    }

    private fun refreshProposals() {
        if (_state.value.sessionChanged) return
        val conversationId = _state.value.activeConversationId
        val expectedGeneration = generation
        val request = ++proposalRequest
        uiScope.launch {
            val result = attempt { port.pendingProposals(conversationId) }
            if (expectedGeneration != generation || request != proposalRequest ||
                _state.value.activeConversationId != conversationId) return@launch
            _state.update { state ->
                state.copy(noteProposals = result.getOrElse { emptyList() },
                    error = result.exceptionOrNull()?.message ?: state.error)
            }
        }
    }

    fun confirmProposal(proposalId: String) = actOnProposal(proposalId, confirm = true)
    fun rejectProposal(proposalId: String) = actOnProposal(proposalId, confirm = false)

    private fun actOnProposal(proposalId: String, confirm: Boolean) {
        val current = _state.value
        if (current.sessionChanged) return
        val proposal = current.noteProposals.firstOrNull { it.id == proposalId } ?: return
        if (!proposal.isPending || current.proposalBusyId != null) return
        val expectedGeneration = generation
        val conversationId = current.activeConversationId
        ++proposalRequest // an older refresh cannot undo the action result
        _state.update { it.copy(proposalBusyId = proposalId, error = null, notice = null) }
        uiScope.launch {
            val result = attempt {
                if (confirm) port.confirmProposal(proposalId) else port.rejectProposal(proposalId)
            }.getOrElse { AgentProposalActionResult.Rejected("UI_ERROR", it.message ?: "提议处理失败") }
            if (expectedGeneration != generation || _state.value.activeConversationId != conversationId) return@launch
            ++proposalRequest // a refresh started during the action must not undo its result
            applyProposalResult(proposalId, result)
        }
    }

    fun dismissProposal(proposalId: String) {
        _state.update { it.copy(noteProposals = it.noteProposals.filterNot { proposal -> proposal.id == proposalId }) }
    }

    private fun applyProposalResult(proposalId: String, result: AgentProposalActionResult) {
        when (result) {
            is AgentProposalActionResult.Confirmed -> _state.update { state ->
                state.copy(proposalBusyId = null, noteProposals = state.noteProposals.filterNot { it.id == proposalId },
                    notice = "已应用笔记修改（revision ${result.revision}）")
            }
            is AgentProposalActionResult.Rejected -> _state.update { state ->
                if (result.code == "REJECTED") state.copy(proposalBusyId = null,
                    noteProposals = state.noteProposals.filterNot { it.id == proposalId }, notice = "已拒绝该笔记提议，正文未修改")
                else state.copy(proposalBusyId = null, error = result.message)
            }
            is AgentProposalActionResult.Stale -> {
                _state.update { state -> state.copy(proposalBusyId = null,
                    noteProposals = state.noteProposals.map { proposal ->
                        if (proposal.id == proposalId) proposal.copy(status = "STALE", staleReason = result.reason,
                            conflictingDraftPreview = result.draftPreview) else proposal
                    }, error = "提议已失效：${result.reason}") }
                refreshProposals()
            }
            is AgentProposalActionResult.AlreadyResolved -> _state.update { state ->
                state.copy(proposalBusyId = null, noteProposals = state.noteProposals.filterNot { it.id == proposalId },
                    notice = "该提议已处理（状态 ${result.status}），未重复写入")
            }
            AgentProposalActionResult.NotFound -> _state.update { state ->
                state.copy(proposalBusyId = null, noteProposals = state.noteProposals.filterNot { it.id == proposalId },
                    error = "提议不存在或无权访问")
            }
            is AgentProposalActionResult.Unavailable -> _state.update { it.copy(proposalBusyId = null, error = result.message) }
        }
    }

    fun updateDraft(text: String) = _state.update { if (it.sessionChanged) it else it.copy(draft = text) }

    fun send() {
        val current = _state.value
        if (!current.canSend || current.historyLoading) return
        val expectedGeneration = generation
        val userText = current.draft.trim()
        val userId = "m-user-${UUID.randomUUID()}"
        val assistantId = "m-assistant-${UUID.randomUUID()}"
        optimisticIds = setOf(userId, assistantId)
        projectedRunIds = emptySet()
        val operation = Preparation(expectedGeneration, current.draft)
        preparation = operation
        _state.update { it.copy(draft = "", error = null, notice = null, streamingText = "",
            phase = AgentRunPhase.PREPARING,
            messages = it.messages + AgentMessageUi(userId, AgentMessageRole.USER, userText) +
                AgentMessageUi(assistantId, AgentMessageRole.ASSISTANT, "", streaming = true)) }
        eventJob?.cancel()
        eventJob = null
        // Preparation is cancellable by an explicit Stop, never by merely closing the UI.
        val job = uiScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = attempt { port.startRun(launch, current.activeConversationId, userText) }
                if (operation.stopRequested) {
                    // A non-cooperative start may return after cancellation. Never attach its UI.
                    result.getOrNull()?.takeIf { it.ownsRun && it.busyWithRunId == null }?.let { reply ->
                        withContext(NonCancellable) {
                            attempt { port.stopRun(reply.runId) }.onFailure { error ->
                                if (expectedGeneration == generation) _state.update { state ->
                                    state.copy(phase = AgentRunPhase.ERROR,
                                        error = "停止失败：${error.message ?: "未知错误"}")
                                }
                            }
                        }
                    }
                    return@launch
                }
                coroutineContext.ensureActive()
                if (expectedGeneration != generation) return@launch
                val reply = result.getOrElse { error ->
                    _state.update { state -> state.copy(
                        draft = state.draft.ifEmpty { current.draft }, phase = AgentRunPhase.ERROR,
                        messages = state.messages.filterNot { it.id in optimisticIds },
                        error = "启动失败：${error.message ?: "未知错误"}") }
                    optimisticIds = emptySet()
                    return@launch
                }
                val conversationId = reply.conversationId ?: current.activeConversationId
                _state.update { state -> state.copy(
                    activeConversationId = conversationId,
                    activeConversationTitle = if (current.activeConversationId == null) userText.take(24) else state.activeConversationTitle,
                    draft = if (reply.busyWithRunId != null) state.draft.ifEmpty { current.draft } else state.draft,
                    messages = if (reply.busyWithRunId != null) state.messages.filterNot { it.id in optimisticIds } else state.messages,
                    notice = if (reply.busyWithRunId != null) "当前会话已有运行中的回答，已恢复订阅" else null) }
                if (reply.busyWithRunId != null) optimisticIds = emptySet()
                runId = reply.runId
                refreshProposals()
                if (uiAttached) subscribe(reply.runId, assistantId, expectedGeneration)
            } finally {
                if (preparation === operation) preparation = null
            }
        }
        operation.job = job
        if (operation.stopRequested) job.cancel() else job.start()
    }

    private fun subscribe(id: String, assistantId: String, expectedGeneration: Long) {
        eventJob?.cancel()
        eventJob = uiScope.launch {
            var lastSeq = 0L
            attempt {
                port.observeRun(id).collect { event ->
                    if (expectedGeneration != generation || runId != id || event.seq <= lastSeq) return@collect
                    lastSeq = event.seq
                    apply(event, assistantId)
                }
            }.onFailure { error ->
                if (expectedGeneration == generation && runId == id) {
                    _state.update { state -> state.copy(phase = AgentRunPhase.ERROR,
                        messages = state.messages.map { if (it.streaming) it.copy(streaming = false, statusLabel = "订阅中断") else it },
                        error = "运行订阅失败：${error.message ?: "未知错误"}") }
                    runId = null
                }
            }
        }
    }

    fun stop() {
        val id = runId
        if (id == null) {
            val operation = preparation?.takeIf { it.generation == generation } ?: return
            if (operation.stopRequested) return
            operation.stopRequested = true
            _state.update { state -> state.copy(phase = AgentRunPhase.CANCELLED,
                draft = state.draft.ifEmpty { operation.submittedDraft },
                messages = state.messages.map { if (it.streaming) it.copy(streaming = false, statusLabel = "已停止") else it }) }
            operation.job?.cancel()
            return
        }
        val expectedGeneration = generation
        uiScope.launch {
            val result = attempt { port.stopRun(id) }
            if (expectedGeneration != generation || runId != id) return@launch
            result.onSuccess {
                _state.update { state ->
                    if (!state.isRunning) state else state.copy(phase = AgentRunPhase.CANCELLED,
                        messages = state.messages.map { if (it.streaming) it.copy(streaming = false, statusLabel = "已停止") else it })
                }
            }.onFailure { error -> _state.update { it.copy(error = "停止失败：${error.message ?: "未知错误"}") } }
        }
    }

    fun closeUi() {
        uiAttached = false
        eventJob?.cancel()
        eventJob = null
    }

    private fun switchContext() {
        drafts[_state.value.activeConversationId.orEmpty()] = _state.value.draft
        ++generation
        ++proposalRequest
        eventJob?.cancel()
        eventJob = null
        runId = null
        preparation = null
        optimisticIds = emptySet()
        projectedRunIds = emptySet()
        uiAttached = true
    }

    fun newConversation() {
        if (_state.value.sessionChanged) return
        returnConversationRestored = true
        switchContext()
        _state.update { it.copy(activeConversationId = null, activeConversationTitle = "新会话", draft = "",
            messages = emptyList(), streamingText = "", phase = AgentRunPhase.IDLE, historyLoading = false,
            noteProposals = emptyList(), proposalBusyId = null, error = null, notice = null) }
        refreshProposals()
    }

    fun openConversation(conversation: AgentConversationUi) {
        if (_state.value.sessionChanged) return
        returnConversationRestored = true
        switchContext()
        val expectedGeneration = generation
        _state.update { it.copy(activeConversationId = conversation.id, activeConversationTitle = conversation.title,
            draft = drafts[conversation.id].orEmpty(), messages = emptyList(), streamingText = "",
            phase = AgentRunPhase.IDLE, historyLoading = true, noteProposals = emptyList(), proposalBusyId = null,
            error = null, notice = null) }
        uiScope.launch {
            val history = attempt { port.loadHistory(conversation.id) }
            if (expectedGeneration != generation) return@launch
            _state.update { it.copy(messages = history.getOrElse { emptyList() },
                error = history.exceptionOrNull()?.let { error -> "历史消息加载失败：${error.message ?: "未知错误"}" }) }
            refreshProposals()
            val latest = attempt { port.latestRun(conversation.id) }
            if (expectedGeneration != generation) return@launch
            _state.update { state -> state.copy(historyLoading = false,
                phase = if (latest.getOrNull() != null) AgentRunPhase.PREPARING else AgentRunPhase.IDLE,
                error = latest.exceptionOrNull()?.let { "运行恢复失败：${it.message ?: "未知错误"}" } ?: state.error) }
            latest.getOrNull()?.let { reply ->
                runId = reply.runId
                if (uiAttached) subscribe(reply.runId,
                    _state.value.messages.lastOrNull { it.role == AgentMessageRole.ASSISTANT }?.id ?: "m-resumed-${reply.runId}",
                    expectedGeneration)
            }
        }
    }

    fun renameConversation(conversation: AgentConversationUi, title: String) {
        val normalized = title.trim()
        val current = _state.value
        if (current.sessionChanged || current.renameUpdate?.running == true ||
            normalized.isBlank() || normalized == conversation.title) return
        val request = AgentConversationRenameUi(conversation.id, ++renameSequence, running = true)
        _state.update { it.copy(renameUpdate = request, error = null) }
        uiScope.launch {
            val result = attempt { port.renameConversation(conversation.id, normalized) }
            _state.update { state ->
                if (state.sessionChanged || state.renameUpdate?.sequence != request.sequence) return@update state
                val newTitle = if (result.isSuccess && state.activeConversationId == conversation.id) normalized
                    else state.activeConversationTitle
                state.copy(
                    activeConversationTitle = newTitle,
                    renameUpdate = request.copy(
                        running = false,
                        error = result.exceptionOrNull()?.let { it.message ?: "重命名失败" },
                    ),
                )
            }
        }
    }

    fun archiveConversation(conversation: AgentConversationUi) {
        if (_state.value.sessionChanged || !archiving.add(conversation.id)) return
        val expectedGeneration = generation
        uiScope.launch {
            val failureMessage = if (conversation.archived) "取消归档失败" else "归档失败"
            try {
                attempt {
                    if (conversation.archived) port.unarchiveConversation(conversation.id)
                    else port.archiveConversation(conversation.id)
                }.onFailure { error ->
                    if (expectedGeneration == generation) _state.update { it.copy(error = error.message ?: failureMessage) }
                }
            } finally {
                archiving.remove(conversation.id)
            }
        }
    }

    fun deleteConversation(conversation: AgentConversationUi) {
        if (_state.value.sessionChanged) return
        val expectedGeneration = generation
        uiScope.launch {
            attempt { port.deleteConversation(conversation.id) }.onSuccess {
                drafts.remove(conversation.id)
                if (expectedGeneration == generation && _state.value.activeConversationId == conversation.id) newConversation()
            }.onFailure { error -> if (expectedGeneration == generation) _state.update { it.copy(error = error.message ?: "删除失败") } }
        }
    }

    private fun updateConversationPage() {
        _state.update { state ->
            state.copy(
                conversations = conversationRows.take(conversationLimit),
                conversationsHasMore = conversationRows.size > conversationLimit,
            )
        }
    }

    fun loadMoreConversations() {
        if (_state.value.sessionChanged || !_state.value.conversationsHasMore) return
        conversationLimit += CONVERSATION_PAGE_SIZE
        updateConversationPage()
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }
    fun reportSourceUnavailable(source: AgentSourceUi) {
        _state.update { it.copy(notice = "${source.label} ${source.unavailableReason ?: "来源不可用"}") }
    }

    private fun updateAssistant(assistantId: String, transform: (AgentMessageUi) -> AgentMessageUi) {
        _state.update { state ->
            val target = state.messages.firstOrNull { it.id == assistantId }
                ?: state.messages.lastOrNull { it.role == AgentMessageRole.ASSISTANT }
            if (target == null) state.copy(messages = state.messages + transform(AgentMessageUi(assistantId, AgentMessageRole.ASSISTANT, "", streaming = true)))
            else state.copy(messages = state.messages.map { if (it.id == target.id) transform(it) else it })
        }
    }

    private fun apply(event: AgentRunEvent, assistantId: String) {
        when (event) {
            is AgentRunEvent.Snapshot -> {
                _state.update { state ->
                    val prior = state.messages.filter { it.id in optimisticIds || it.id in projectedRunIds }
                    val userKey = prior.firstOrNull { it.role == AgentMessageRole.USER }?.presentationKey
                    val assistant = prior.firstOrNull { it.role == AgentMessageRole.ASSISTANT }
                    val firstAssistant = event.messages.firstOrNull { it.role == AgentMessageRole.ASSISTANT }?.id
                    val keepPending = event.phase !in TERMINAL_PHASES && firstAssistant == null
                    val messages = if (event.messages.isEmpty()) state.messages else {
                        val old = state.messages.filterNot { it.id in optimisticIds || it.id in projectedRunIds }
                        val incomingIds = event.messages.map { it.id }.toSet()
                        old.filterNot { it.id in incomingIds } + event.messages.map { row ->
                            val key = when {
                                row.role == AgentMessageRole.USER -> userKey
                                row.id == firstAssistant -> assistant?.presentationKey
                                else -> null
                            }
                            if (key == null) row else row.copy(presentationKey = key)
                        } + listOfNotNull(assistant?.takeIf { keepPending })
                    }
                    state.copy(phase = event.phase, streamingText = event.messages.filter { it.streaming }.joinToString("") { it.content },
                        messages = if (event.phase in TERMINAL_PHASES) messages.map { it.copy(streaming = false) } else messages)
                }
                if (event.messages.isNotEmpty()) {
                    optimisticIds = emptySet()
                    projectedRunIds = event.messages.map { it.id }.toSet() +
                        _state.value.messages.filter { it.id == assistantId }.map { it.id }
                }
                if (event.phase in TERMINAL_PHASES) { runId = null; refreshProposals() }
            }
            is AgentRunEvent.PhaseChanged -> _state.update { it.copy(phase = event.phase) }
            is AgentRunEvent.AssistantDelta -> {
                _state.update { it.copy(streamingText = it.streamingText + event.text) }
                updateAssistant(assistantId) { it.copy(content = it.content + event.text) }
            }
            is AgentRunEvent.ToolStep -> updateAssistant(assistantId) { message ->
                val existing = message.toolSteps.indexOfFirst { it.callId == event.step.callId }
                message.copy(toolSteps = if (existing < 0) message.toolSteps + event.step
                    else message.toolSteps.map { if (it.callId == event.step.callId) event.step else it })
            }
            is AgentRunEvent.SourcesAttached -> updateAssistant(assistantId) { it.copy(sources = event.sources) }
            is AgentRunEvent.Completed -> {
                _state.update { state -> state.copy(phase = when (state.phase) {
                    AgentRunPhase.CANCELLED -> AgentRunPhase.CANCELLED
                    AgentRunPhase.INTERRUPTED -> AgentRunPhase.INTERRUPTED
                    else -> AgentRunPhase.COMPLETED
                }, messages = state.messages.map { if (it.streaming) it.copy(streaming = false, statusLabel = event.statusLabel) else it }) }
                runId = null
                refreshProposals()
            }
            is AgentRunEvent.Failed -> {
                _state.update { state -> state.copy(
                    phase = if (event.code == "INTERRUPTED") AgentRunPhase.INTERRUPTED else AgentRunPhase.ERROR,
                    error = "${event.code}: ${event.message}",
                    messages = state.messages.map { if (it.streaming) it.copy(streaming = false,
                        statusLabel = if (event.code == "INTERRUPTED") "已中断" else "失败") else it }) }
                runId = null
                refreshProposals()
            }
        }
    }

    private companion object {
        val TERMINAL_PHASES = setOf(AgentRunPhase.COMPLETED, AgentRunPhase.CANCELLED, AgentRunPhase.INTERRUPTED, AgentRunPhase.ERROR)
    }
}

class AgentViewModelFactory(
    private val context: Context,
    private val launch: AgentLaunchContext,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val container = (context.applicationContext as com.example.myapplication.LearningApplication).container
        return AgentViewModel(
            port = RealAgentWorkspacePort(
                sessionManager = container.sessionManager,
                conversations = container.conversationRepository,
                coordinator = container.runCoordinator,
                // 笔记提议：唯一写入口是 NoteProposalService；确认时必须用户点击且用当前 ctx 再授权。
                noteProposals = container.noteProposalService,
                // 来源卡片按持久 referenceId 复核可用性（删除/改版/撤权 → SourceUnavailable 卡片）
                sourceNavigator = container.sourceNavigator,
            ),
            launch = launch,
        ) as T
    }
}
