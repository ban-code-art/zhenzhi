package com.example.myapplication.ui.knowledge.quiz

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.session.SessionIdentity
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuizSaveStateUi(
    val saving: Boolean = false,
    val error: String? = null,
    val lastSavedOrdinal: Int? = null,
    val leaving: Boolean = false,
    val submitting: Boolean = false,
)

/**
 * 随堂测 ViewModel（生成/答题/结果/历史共用）。
 *
 * 生产由 [QuizViewModelFactory] 注入真实 repository/评分端口；fake 仅用于测试。行为要求：
 *  - 生成可取消，取消后不得出现 READY 空卷；
 *  - 答题每次保存（失败必须提示，不能把本地输入当已保存）；重开按 currentOrdinal 续答；
 *  - 交卷未答完时由 UI 确认，VM 只接受确认后的提交；
 *  - 结果页区分已评/待评，score=null 不参与总分。
 */
class QuizViewModel(
    private val port: QuizUiPort = FakeQuizUiPort(),
    private val documentId: String = "",
    private val sectionId: String? = null,
    autoStartGeneration: Boolean = false,
    suppliedScope: kotlinx.coroutines.CoroutineScope? = null,
    private val monotonicClockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : ViewModel() {

    private val launchScope = suppliedScope ?: viewModelScope

    private val _generation = MutableStateFlow(QuizGenerationUi())
    val generation: StateFlow<QuizGenerationUi> = _generation.asStateFlow()

    private val _attempt = MutableStateFlow<QuizAttemptUi?>(null)
    val attempt: StateFlow<QuizAttemptUi?> = _attempt.asStateFlow()

    private val _result = MutableStateFlow<QuizResultUi?>(null)
    val result: StateFlow<QuizResultUi?> = _result.asStateFlow()

    private val _history = MutableStateFlow(QuizHistoryUi())
    val history: StateFlow<QuizHistoryUi> = _history.asStateFlow()

    private val _saveState = MutableStateFlow(QuizSaveStateUi())
    val saveState: StateFlow<QuizSaveStateUi> = _saveState.asStateFlow()

    private var generationJob: Job? = null
    private var generationRunId: String? = null
    private var paperId: String? = null
    private var submitInFlight = false
    private var submissionNeedsReconciliation = false
    private var resultLoadingAttemptId: String? = null
    private val writeMutex = Mutex()
    private var attemptGeneration = 0L
    private var editSequence = 0L
    private var resultGeneration = 0L
    private var historyGeneration = 0L
    private var generationTicket = 0L
    private var operationGeneration = 0L
    private var ownerIdentity: SessionIdentity? = null
    private var identityObserved = false
    private var active = true
    private var autoSaveJob: Job? = null
    private val dirtyItems = linkedSetOf<String>()
    private val favoriteMutex = Mutex()
    private val resultMutationMutex = Mutex()
    private var leaveInFlight = false
    private var leaveGeneration = 0L
    /** 续答计时：只统计本页面前台停留时长，翻页/定时把增量写回 attempt。 */
    private var resumeTickJob: Job? = null
    private var lastActiveTickAt: Long? = null

    private var manualFavorites: Set<Long> = emptySet()
    init {
        launchScope.launch {
            port.observeSessionIdentity().collect { identity ->
                if (identityObserved && identity != ownerIdentity) {
                    attemptGeneration++
                    resultGeneration++
                    historyGeneration++
                    generationTicket++
                    operationGeneration++
                    generationJob?.cancel()
                    resumeTickJob?.cancel()
                    autoSaveJob?.cancel()
                    dirtyItems.clear()
                    pendingActiveMs = 0L
                    lastActiveTickAt = null
                    submitInFlight = false
                    submissionNeedsReconciliation = false
                    resultLoadingAttemptId = null
                    leaveInFlight = false
                    _attempt.value = null
                    _result.value = null
                    _history.value = QuizHistoryUi()
                    _generation.value = QuizGenerationUi()
                    _saveState.value = QuizSaveStateUi()
                    manualFavorites = emptySet()
                }
                identityObserved = true
                ownerIdentity = identity
            }
        }
        launchScope.launch { port.observeManualFavorites().collect { ids ->
            manualFavorites = ids
            _result.update { result -> result?.withManualFavorites() }
        } }
        if (autoStartGeneration) startGeneration()
    }

    fun startGeneration() = startGenerationRequest({ port.startGeneration(documentId, sectionId) }, "启动生成失败")

    fun startRegeneration(sourcePaperId: String) = startGenerationRequest({ port.regenerate(sourcePaperId) }, "重新生成失败")

    fun ensureRegeneration(sourcePaperId: String) {
        if (generationJob == null) startRegeneration(sourcePaperId)
    }

    private fun startGenerationRequest(start: suspend () -> GenerationStartUi, errorPrefix: String) {
        if (generationJob?.isActive == true && !_generation.value.terminal) return
        val ticket = ++generationTicket
        generationJob?.cancel()
        generationRunId = null
        _generation.value = QuizGenerationUi(stage = QuizGenerationStageUi.REGISTERED)
        generationJob = launchScope.launch {
            try {
                val started = start()
                if (ticket != generationTicket) return@launch
                observeGenerationRun(started, ticket)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == generationTicket) _generation.value = QuizGenerationUi(
                    stage = QuizGenerationStageUi.FAILED, message = "$errorPrefix：${error.message}",
                    errorCode = "GENERATION_START_FAILED", retryable = true, terminal = true,
                )
            }
        }
    }

    private suspend fun observeGenerationRun(started: GenerationStartUi, ticket: Long) {
        generationRunId = started.generationRunId
        paperId = started.paperId
        _generation.value = QuizGenerationUi(
            generationRunId = started.generationRunId, paperId = started.paperId,
            stage = QuizGenerationStageUi.REGISTERED,
            message = if (started.existing) "继续已有的随堂测任务" else "准备生成随堂测",
            sourceLabel = started.sourceLabel,
        )
        port.observeGeneration(started.generationRunId).collect { state ->
            if (ticket != generationTicket) throw CancellationException("Generation changed")
            _generation.update { previous ->
                state.copy(
                    sourceLabel = started.sourceLabel,
                    cancelling = previous.cancelling && !state.terminal,
                    actionError = previous.actionError.takeUnless { state.terminal },
                    errorCode = state.errorCode ?: "QUIZ_GENERATION_FAILED".takeIf { state.stage == QuizGenerationStageUi.FAILED },
                    message = if (state.errorCode == "REVIEW_REQUIRED") "学习草稿已生成，可开始自测" else state.message,
                    retryable = state.errorCode != "REVIEW_REQUIRED" &&
                        state.stage in setOf(QuizGenerationStageUi.FAILED, QuizGenerationStageUi.INTERRUPTED),
                )
            }
            state.paperId?.let { paperId = it }
        }
    }

    fun cancelGeneration() {
        val runId = generationRunId ?: return
        if (!_generation.value.canCancel) return
        val ticket = generationTicket
        _generation.update { it.copy(cancelling = true, actionError = null) }
        launchScope.launch {
            try {
                port.cancelGeneration(runId)
                // The observed run is authoritative, including a result that won the cancellation race.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ticket == generationTicket) _generation.update {
                    if (it.terminal) it else it.copy(cancelling = false, actionError = "取消失败：${error.message}")
                }
            }
        }
    }

    fun loadAttempt(attemptId: String) {
        val generation = ++attemptGeneration
        resultGeneration++
        submitInFlight = false
        submissionNeedsReconciliation = false
        resultLoadingAttemptId = null
        leaveInFlight = false
        pendingActiveMs = 0L
        resumeTickJob?.cancel()
        autoSaveJob?.cancel()
        dirtyItems.clear()
        _attempt.value = null
        _result.value = null
        _saveState.value = QuizSaveStateUi()
        launchScope.launch {
            try {
                val loaded = port.loadAttempt(attemptId)
                if (generation != attemptGeneration) return@launch
                editSequence = loaded.attemptRevision
                _attempt.value = loaded
                _saveState.value = QuizSaveStateUi(lastSavedOrdinal = loaded.currentQuestion?.ordinal)
                startResumeTracking(loaded)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == attemptGeneration) _saveState.value = QuizSaveStateUi(error = "读取作答失败：${error.message}")
            }
        }
    }

    private fun monotonicMillis(): Long = monotonicClockMillis()
    private var pendingActiveMs = 0L

    private fun startResumeTracking(attempt: QuizAttemptUi) {
        resumeTickJob?.cancel()
        lastActiveTickAt = if (active) monotonicMillis() else null
        if (!active || attempt.status != QuizAttemptStatusUi.IN_PROGRESS) return
        resumeTickJob = launchScope.launch {
            while (true) {
                kotlinx.coroutines.delay(RESUME_TICK_MS)
                queueCheckpoint()
            }
        }
    }

    /** Lifecycle transitions pause active time and flush the pending answer. */
    fun setActive(value: Boolean) {
        if (active == value) return
        if (!value) {
            val now = monotonicMillis()
            pendingActiveMs += lastActiveTickAt?.let { (now - it).coerceAtLeast(0) } ?: 0
            lastActiveTickAt = null
            active = false
            resumeTickJob?.cancel()
            autoSaveJob?.cancel()
            queueCheckpoint()
        } else {
            active = true
            _attempt.value?.let(::startResumeTracking)
        }
    }

    fun selectOrdinal(ordinal: Int) {
        val current = _attempt.value ?: return
        if (leaveInFlight || submitInFlight || current.status != QuizAttemptStatusUi.IN_PROGRESS || ordinal !in current.questions.indices) return
        current.currentQuestion?.let { dirtyItems += it.itemId }
        _attempt.value = current.copy(currentOrdinal = ordinal)
        autoSaveJob?.cancel()
        queueCheckpoint()
    }

    fun previous() {
        val current = _attempt.value ?: return
        selectOrdinal((current.currentOrdinal - 1).coerceAtLeast(0))
    }

    fun next() {
        val current = _attempt.value ?: return
        selectOrdinal((current.currentOrdinal + 1).coerceAtMost(current.questions.lastIndex))
    }

    fun updateAnswer(answer: String) {
        val current = _attempt.value ?: return
        val question = current.currentQuestion ?: return
        if (leaveInFlight || submitInFlight || current.status != QuizAttemptStatusUi.IN_PROGRESS) return
        dirtyItems += question.itemId
        _attempt.value = current.copy(questions = current.questions.map {
            if (it.itemId == question.itemId) it.copy(savedAnswer = answer) else it
        })
        _saveState.update { it.copy(lastSavedOrdinal = null, error = null) }
        autoSaveJob?.cancel()
        autoSaveJob = launchScope.launch {
            kotlinx.coroutines.delay(AUTO_SAVE_DELAY_MS)
            queueCheckpoint()
        }
    }

    private fun admitted(id: String, generation: Long): Boolean =
        generation == attemptGeneration && _attempt.value?.attemptId == id

    private suspend fun persistAnswer(attempt: QuizAttemptUi, question: QuizQuestionUi, generation: Long): Long {
        if (!admitted(attempt.attemptId, generation)) throw CancellationException("Attempt changed")
        val sequence = maxOf(++editSequence, (_attempt.value?.attemptRevision ?: 0) + 1)
        val raw = if (question.savedAnswer.isBlank()) "" else QuizUiMappers.rawAnswerJson(question, question.savedAnswer)
        val revision = port.saveAnswer(attempt.attemptId, question.itemId, raw, sequence)
        if (!admitted(attempt.attemptId, generation)) throw CancellationException("Attempt changed")
        _attempt.update { it?.copy(attemptRevision = maxOf(it.attemptRevision, revision)) }
        if (_attempt.value?.questions?.firstOrNull { it.itemId == question.itemId }?.savedAnswer == question.savedAnswer) dirtyItems -= question.itemId
        return revision
    }

    /** Failed items remain dirty; answers and location use the same ordered queue. */
    private suspend fun checkpointLocked(attemptId: String, generation: Long): Long {
        if (!admitted(attemptId, generation)) throw CancellationException("Attempt changed")
        if (submissionNeedsReconciliation && reconcileSubmissionLocked(attemptId, generation)) {
            return _attempt.value?.attemptRevision ?: throw CancellationException("Attempt changed")
        }
        while (dirtyItems.isNotEmpty()) {
            val current = _attempt.value ?: throw CancellationException("Attempt changed")
            val itemId = dirtyItems.first()
            val question = current.questions.firstOrNull { it.itemId == itemId }
            if (question == null) { dirtyItems -= itemId; continue }
            persistAnswer(current, question, generation)
        }
        val current = _attempt.value ?: throw CancellationException("Attempt changed")
        val now = monotonicMillis()
        // Seal the active interval before suspending. Lifecycle changes may add new intervals
        // while storage is pending; completion only removes the captured, persisted amount.
        pendingActiveMs += lastActiveTickAt?.let { (now - it).coerceAtLeast(0) } ?: 0
        lastActiveTickAt = if (active) now else null
        val delta = pendingActiveMs
        port.saveResumeState(attemptId, current.currentOrdinal, delta)
        if (!admitted(attemptId, generation)) throw CancellationException("Attempt changed")
        pendingActiveMs = (pendingActiveMs - delta).coerceAtLeast(0)
        _attempt.update { it?.copy(activeUsedMs = it.activeUsedMs.coerceAtMost(Long.MAX_VALUE - delta) + delta) }
        _saveState.value = QuizSaveStateUi(
            lastSavedOrdinal = _attempt.value?.currentQuestion?.ordinal,
            leaving = leaveInFlight,
            saving = submitInFlight,
            submitting = submitInFlight,
        )
        return _attempt.value?.attemptRevision ?: current.attemptRevision
    }

    /** A lost receipt must be resolved before another answer/location write is admitted. */
    private suspend fun reconcileSubmissionLocked(attemptId: String, generation: Long): Boolean {
        if (!submissionNeedsReconciliation) return false
        val expected = _attempt.value ?: throw CancellationException("Attempt changed")
        val loaded = port.loadAttempt(attemptId)
        if (!admitted(attemptId, generation)) throw CancellationException("Attempt changed")
        check(loaded.attemptId == attemptId && loaded.paperId == expected.paperId && loaded.submitKey == expected.submitKey) {
            "原作答的交卷标识已变化，请重新进入"
        }
        submissionNeedsReconciliation = false
        editSequence = maxOf(editSequence, loaded.attemptRevision)
        if (loaded.status == QuizAttemptStatusUi.IN_PROGRESS) {
            // A real rollback must retain edits made locally while the read was pending.
            _attempt.update { it?.copy(attemptRevision = maxOf(it.attemptRevision, loaded.attemptRevision)) }
            return false
        }
        resumeTickJob?.cancel()
        autoSaveJob?.cancel()
        dirtyItems.clear()
        pendingActiveMs = 0L
        lastActiveTickAt = null
        _attempt.value = loaded
        _saveState.value = QuizSaveStateUi()
        loadResult(attemptId)
        return true
    }

    private fun queueCheckpoint(onSaved: (() -> Unit)? = null) {
        val current = _attempt.value ?: return
        if (current.status != QuizAttemptStatusUi.IN_PROGRESS) { onSaved?.invoke(); return }
        val generation = attemptGeneration
        launchScope.launch {
            try {
                writeMutex.withLock {
                    if (!admitted(current.attemptId, generation) || _attempt.value?.status != QuizAttemptStatusUi.IN_PROGRESS) return@withLock
                    _saveState.value = QuizSaveStateUi(saving = true, leaving = leaveInFlight, submitting = submitInFlight)
                    checkpointLocked(current.attemptId, generation)
                    onSaved?.invoke()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (admitted(current.attemptId, generation)) {
                    leaveInFlight = false
                    leaveGeneration++
                    _saveState.value = QuizSaveStateUi(
                        error = "保存失败：${error.message}（未保存输入仍保留，可重试）",
                        saving = submitInFlight,
                        submitting = submitInFlight,
                    )
                }
            }
        }
    }

    fun saveCurrentAnswer(onSaved: (() -> Unit)? = null) {
        val current = _attempt.value ?: return
        if (current.status != QuizAttemptStatusUi.IN_PROGRESS) { onSaved?.invoke(); return }
        current.currentQuestion?.let { dirtyItems += it.itemId }
        autoSaveJob?.cancel()
        queueCheckpoint(onSaved)
    }

    /** Toolbar/system back wait for the checkpoint, keeping failed input on screen. */
    fun leave(onLeft: () -> Unit) {
        val current = _attempt.value
        if (current == null || current.status != QuizAttemptStatusUi.IN_PROGRESS) { onLeft(); return }
        if (leaveInFlight || submitInFlight) return
        leaveInFlight = true
        val ticket = ++leaveGeneration
        _saveState.update { it.copy(leaving = true) }
        saveCurrentAnswer {
            if (leaveInFlight && ticket == leaveGeneration) {
                leaveInFlight = false
                _saveState.update { it.copy(leaving = false) }
                onLeft()
            }
        }
    }

    fun submit(acknowledgedUnanswered: Boolean) {
        val initial = _attempt.value ?: return
        if (leaveInFlight || submitInFlight || initial.status != QuizAttemptStatusUi.IN_PROGRESS) return
        if (initial.unansweredCount > 0 && !acknowledgedUnanswered) {
            _saveState.value = QuizSaveStateUi(error = "还有 ${initial.unansweredCount} 题未作答，请确认后再交卷")
            return
        }
        if (initial.submitKey.isBlank()) {
            _saveState.value = QuizSaveStateUi(error = "试卷缺少交卷标识，请重新进入")
            return
        }
        val generation = attemptGeneration
        initial.currentQuestion?.let { dirtyItems += it.itemId }
        autoSaveJob?.cancel()
        submitInFlight = true
        _saveState.value = QuizSaveStateUi(saving = true, submitting = true)
        launchScope.launch {
            try {
                writeMutex.withLock {
                    if (!admitted(initial.attemptId, generation)) return@withLock
                    val revision = checkpointLocked(initial.attemptId, generation)
                    val current = _attempt.value ?: return@withLock
                    if (current.status != QuizAttemptStatusUi.IN_PROGRESS) return@withLock
                    submissionNeedsReconciliation = true
                    val reply = port.submit(current.attemptId, revision, current.submitKey)
                    if (!admitted(current.attemptId, generation)) return@withLock
                    submissionNeedsReconciliation = false
                    resumeTickJob?.cancel()
                    dirtyItems.clear()
                    pendingActiveMs = 0L
                    lastActiveTickAt = null
                    _attempt.update { it?.copy(
                        status = if (reply.pendingCount > 0) QuizAttemptStatusUi.SUBMITTED_PENDING_REVIEW else QuizAttemptStatusUi.SUBMITTED_FINAL,
                        gradedScore = reply.gradedScore, maxScore = reply.maxScore, pendingReviewCount = reply.pendingCount,
                    ) }
                    _saveState.value = QuizSaveStateUi()
                    loadResult(current.attemptId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                var recovered = false
                if (admitted(initial.attemptId, generation) && submissionNeedsReconciliation) {
                    try {
                        writeMutex.withLock {
                            if (admitted(initial.attemptId, generation)) {
                                recovered = reconcileSubmissionLocked(initial.attemptId, generation)
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (recoveryError: Exception) { error.addSuppressed(recoveryError) }
                }
                if (admitted(initial.attemptId, generation) && !recovered) {
                    _saveState.value = QuizSaveStateUi(error = if (submissionNeedsReconciliation) {
                        "交卷结果暂未确认：${error.message}；重试会先核对原作答状态"
                    } else "交卷失败：${error.message}；请重试")
                }
            } finally { if (generation == attemptGeneration) submitInFlight = false }
        }
    }

    private fun QuizResultUi.withManualFavorites(): QuizResultUi =
        copy(questions = questions.map { it.copy(manuallyFavorited = it.questionId in manualFavorites) })

    private fun admittedResult(id: String, ticket: Long): Boolean =
        ticket == resultGeneration && _result.value?.attemptId == id

    fun toggleManualFavorite(question: QuizQuestionUi) {
        val id = question.questionId ?: return
        val ticket = resultGeneration
        launchScope.launch {
            try {
                favoriteMutex.withLock {
                    if (ticket != resultGeneration) return@withLock
                    val selected = id !in manualFavorites
                    port.setManualFavorite(id, selected)
                    if (ticket != resultGeneration) return@withLock
                    manualFavorites = if (selected) manualFavorites + id else manualFavorites - id
                    _result.update { it?.withManualFavorites() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == resultGeneration) _saveState.value = QuizSaveStateUi(error = "收藏操作失败：${error.message}")
            }
        }
    }

    fun loadResult(attemptId: String) {
        if (resultLoadingAttemptId == attemptId) return
        val ticket = ++resultGeneration
        resultLoadingAttemptId = attemptId
        _result.value = null
        _saveState.value = QuizSaveStateUi(saving = true)
        launchScope.launch {
            try {
                val loaded = port.loadResult(attemptId)
                check(loaded.status != QuizAttemptStatusUi.IN_PROGRESS) { "ATTEMPT_NOT_SUBMITTED: 本次作答尚未交卷，请继续作答" }
                if (ticket == resultGeneration) {
                    _result.value = loaded.withManualFavorites()
                    _saveState.value = QuizSaveStateUi()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == resultGeneration) _saveState.value = QuizSaveStateUi(error = "读取结果失败：${error.message}")
            } finally { if (ticket == resultGeneration) resultLoadingAttemptId = null }
        }
    }

    private fun mutateResult(current: QuizResultUi, operation: suspend () -> QuizResultUi, errorPrefix: String) {
        if (_saveState.value.saving) return
        val ticket = resultGeneration
        _saveState.value = QuizSaveStateUi(saving = true)
        launchScope.launch {
            try {
                resultMutationMutex.withLock {
                    if (!admittedResult(current.attemptId, ticket)) return@withLock
                    val result = operation()
                    if (!admittedResult(current.attemptId, ticket)) return@withLock
                    _result.value = result.withManualFavorites()
                    _saveState.value = QuizSaveStateUi()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (admittedResult(current.attemptId, ticket)) _saveState.value = QuizSaveStateUi(error = "$errorPrefix：${error.message}")
            }
        }
    }

    fun removeWrong(question: QuizQuestionUi) {
        val current = _result.value ?: return
        val id = current.questions.firstOrNull { it.itemId == question.itemId }?.questionId ?: return
        mutateResult(current, {
            port.removeWrongFavorite(id)
            port.loadResult(current.attemptId).copy(gradeNote = "已移除第 ${question.ordinal + 1} 题错题记录（成绩与解析不变）")
        }, "移除错题失败")
    }

    fun selfGrade(itemId: String, scoreText: String, note: String) {
        val current = _result.value ?: return
        val item = current.questions.firstOrNull { it.itemId == itemId } ?: return
        val score = scoreText.toIntOrNull()
        if (score == null || score !in 0..item.maxScore) {
            _saveState.value = QuizSaveStateUi(error = "自评分必须是 0–${item.maxScore} 的整数")
            return
        }
        mutateResult(current, {
            port.reviseGrade(current.attemptId, itemId, score, note, "self-${java.util.UUID.randomUUID()}")
                .copy(gradeNote = "已保存自评：第 ${item.ordinal + 1} 题 $score 分")
        }, "自评保存失败")
    }

    fun requestAiReview(itemId: String) {
        if (_saveState.value.saving) return
        val current = _result.value ?: return
        val question = current.questions.firstOrNull { it.itemId == itemId } ?: return
        if (!current.aiReviewEnabled) { _saveState.value = QuizSaveStateUi(error = "AI 简答复核未开启"); return }
        if (question.type != QuizItemTypeUi.ESSAY || question.savedAnswer.isBlank()) { _saveState.value = QuizSaveStateUi(error = "请先保存这道简答题的答案"); return }
        val ticket = resultGeneration
        _saveState.value = QuizSaveStateUi(saving = true)
        launchScope.launch {
            try {
                val review = port.reviewAnswer(current.attemptId, itemId)
                if (!admittedResult(current.attemptId, ticket)) return@launch
                when (review) {
                    is com.example.myapplication.data.quiz.AiRubricReviewer.ReviewOutcome.Suggested -> {
                        _result.update { result -> result?.copy(questions = result.questions.map {
                            if (it.itemId == itemId) it.copy(aiReviewSuggestion = review.suggestion) else it
                        }) }
                        _saveState.value = QuizSaveStateUi()
                    }
                    is com.example.myapplication.data.quiz.AiRubricReviewer.ReviewOutcome.Rejected -> _saveState.value = QuizSaveStateUi(error = "复核未通过：${review.detail}")
                    is com.example.myapplication.data.quiz.AiRubricReviewer.ReviewOutcome.Failed -> _saveState.value = QuizSaveStateUi(error = "复核失败：${review.detail}")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (admittedResult(current.attemptId, ticket)) _saveState.value = QuizSaveStateUi(error = "复核失败：${error.message}")
            }
        }
    }

    fun adoptAiReview(itemId: String) {
        val current = _result.value ?: return
        val suggestion = current.questions.firstOrNull { it.itemId == itemId }?.aiReviewSuggestion ?: return
        mutateResult(current, { port.adoptAiReview(current.attemptId, suggestion) }, "采纳失败（原分数保持不变）")
    }

    fun loadHistory() {
        val ticket = ++historyGeneration
        _history.value = QuizHistoryUi(loading = true)
        launchScope.launch {
            try {
                val page = port.loadHistoryPage(0, PAGE_SIZE)
                if (ticket == historyGeneration) _history.value = QuizHistoryUi(items = page.items, hasMore = page.hasMore, nextOffset = page.nextOffset)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == historyGeneration) _history.value = QuizHistoryUi(error = "加载历史失败：${error.message}")
            }
        }
    }

    fun loadMoreHistory() {
        val current = _history.value
        if (current.loading || !current.hasMore) return
        val ticket = historyGeneration
        _history.update { it.copy(loading = true) }
        launchScope.launch {
            try {
                val page = port.loadHistoryPage(current.nextOffset, PAGE_SIZE)
                if (ticket == historyGeneration) _history.value = current.copy(items = (current.items + page.items).distinctBy { it.attemptId },
                    loading = false, hasMore = page.hasMore, nextOffset = page.nextOffset, error = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == historyGeneration) _history.value = current.copy(loading = false, error = "加载更多失败：${error.message}")
            }
        }
    }

    fun retrain(paperId: String, onOpened: (String) -> Unit) =
        openAttemptRequest({ port.retrainPaper(paperId) }, onOpened, "重练失败")

    fun openPaper(paperId: String, onOpened: (String) -> Unit) =
        openAttemptRequest({ port.openPaper(paperId) }, onOpened, "打开试卷失败")

    private fun openAttemptRequest(open: suspend () -> String, onOpened: (String) -> Unit, errorPrefix: String) {
        if (_saveState.value.saving) return
        val ticket = ++operationGeneration
        _saveState.value = QuizSaveStateUi(saving = true)
        launchScope.launch {
            try {
                val id = open()
                if (ticket != operationGeneration) return@launch
                loadAttempt(id)
                onOpened(id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == operationGeneration) _saveState.value = QuizSaveStateUi(error = "$errorPrefix：${error.message}")
            }
        }
    }

    fun consumeSaveError() {
        _saveState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        resumeTickJob?.cancel()
        resumeTickJob = null
        autoSaveJob?.cancel()
    }

    companion object {
        const val AUTO_SAVE_DELAY_MS = 250L
        const val PAGE_SIZE = 20

        /** 续答计时回写间隔：进程被杀最多丢一个 tick。 */
        const val RESUME_TICK_MS = 10_000L
    }
}

class QuizViewModelFactory(
    private val context: Context,
    private val documentId: String,
    private val sectionId: String?,
    private val autoStartGeneration: Boolean,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val container = (context.applicationContext as com.example.myapplication.LearningApplication).container
        return QuizViewModel(
            port = RealQuizUiPort(
                sessionManager = container.sessionManager,
                repository = container.quizRepository,
                submissionService = container.quizSubmissionService,
                gradeAssessmentService = container.gradeAssessmentService,
                generationCoordinator = container.quizGenerationCoordinator,
                favoriteDao = container.database.favoriteDao(),
                aiReviewService = container.quizAiReviewService,
                readerCatalogDao = container.database.readerCatalogDao(),
            ),
            documentId = documentId,
            sectionId = sectionId,
            autoStartGeneration = autoStartGeneration,
        ) as T
    }
}
