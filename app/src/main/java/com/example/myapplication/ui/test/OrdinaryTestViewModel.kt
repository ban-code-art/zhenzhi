package com.example.myapplication.ui.test

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.test.OrdinaryHomeSummary
import com.example.myapplication.data.test.OrdinaryTestSession
import com.example.myapplication.data.test.OrdinaryTestService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class OrdinaryTestUiState(
    val session: OrdinaryTestSession? = null,
    val homeSummary: OrdinaryHomeSummary? = null,
    val manualFavorites: Set<Long> = emptySet(),
    val wrongFavorites: Set<Long> = emptySet(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class OrdinaryTestViewModel(
    private val service: OrdinaryTestService,
    private val expectedIdentity: SessionIdentity,
    private val identityChanges: Flow<SessionIdentity>,
) : ViewModel() {
    private val userId = requireNotNull(expectedIdentity.userId)
    private val _state = MutableStateFlow(OrdinaryTestUiState())
    val state: StateFlow<OrdinaryTestUiState> = _state.asStateFlow()
    private val mutex = Mutex()
    private val dirtyAnswers = linkedMapOf<Int, Long>()
    private var loadingGeneration = 0L
    private var saveSequence = 0L
    private var identityActive = true
    private var foreground = false
    private var timer: Job? = null
    private var lastTick = 0L
    private var tickRemainder = 0L

    init {
        viewModelScope.launch {
            identityChanges.collect { identity ->
                if (identity != expectedIdentity) invalidateIdentity()
            }
        }
        viewModelScope.launch {
            service.observeManualFavorites(userId).collect { ids ->
                if (identityActive && service.currentIdentity() == expectedIdentity) {
                    _state.update { it.copy(manualFavorites = ids) }
                }
            }
        }
        viewModelScope.launch {
            service.observeWrongFavorites(userId).collect { ids ->
                if (identityActive && service.currentIdentity() == expectedIdentity) {
                    _state.update { it.copy(wrongFavorites = ids) }
                }
            }
        }
        refreshHomeSummary()
    }

    private fun invalidateIdentity() {
        if (!identityActive) return
        identityActive = false
        ++loadingGeneration
        timer?.cancel()
        dirtyAnswers.clear()
        _state.value = OrdinaryTestUiState(error = "登录会话已变化，请重新打开测试")
    }

    private suspend fun ensureIdentity() {
        if (!identityActive || service.currentIdentity() != expectedIdentity) {
            invalidateIdentity()
            throw CancellationException("Session changed")
        }
    }

    fun refreshHomeSummary() {
        if (!identityActive) return
        viewModelScope.launch {
            try {
                ensureIdentity()
                val summary = service.loadHomeSummary(userId, expectedIdentity)
                ensureIdentity()
                if (identityActive) _state.update { it.copy(homeSummary = summary) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep previous summary quietly; errors are surfaced explicitly on open().
            }
        }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun open(testId: Long? = null, initialOrdinal: Int? = null) {
        if (!identityActive) return
        val previous = _state.value.session
        timer?.cancel()
        tickRemainder = 0
        val ticket = ++loadingGeneration
        dirtyAnswers.clear()
        _state.update { it.copy(session = null, loading = true, saving = false, error = null) }
        viewModelScope.launch {
            try {
                mutex.withLock {
                    ensureIdentity()
                    val session = if (testId == null) {
                        service.startOrResume(userId, expectedIdentity)
                    } else {
                        service.load(userId, testId, expectedIdentity)
                    }
                    ensureIdentity()
                    if (ticket == loadingGeneration) {
                        _state.update { old ->
                            val targetOrdinal = initialOrdinal?.takeIf { it in session.questions.indices }
                                ?: previous?.takeIf { it.record.id == session.record.id }?.currentOrdinal
                            old.copy(
                                session = if (targetOrdinal != null) session.copy(currentOrdinal = targetOrdinal) else session,
                                loading = false,
                            )
                        }
                    }
                }
                if (ticket == loadingGeneration && foreground) startTimer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ticket == loadingGeneration) {
                    _state.update { it.copy(loading = false, error = error.message ?: "读取测试失败") }
                }
            }
        }
    }

    private fun admitted(testId: Long, generation: Long): Boolean =
        identityActive && generation == loadingGeneration && _state.value.session?.record?.id == testId

    private fun saveAdmitted(testId: Long, generation: Long): Boolean =
        admitted(testId, generation) && !_state.value.loading && _state.value.session?.record?.status == "IN_PROGRESS"

    fun updateAnswer(answer: String) {
        val current = _state.value.session ?: return
        if (_state.value.loading || current.record.status != "IN_PROGRESS") return
        val ordinal = current.currentOrdinal
        val edited = current.copy(
            answers = current.answers.mapIndexed { index, row ->
                if (index == ordinal) row.copy(userAnswer = answer) else row
            },
        )
        _state.update { it.copy(session = edited) }
        dirtyAnswers[ordinal] = ++saveSequence
        persist(edited)
    }

    fun selectOrdinal(ordinal: Int) {
        val current = _state.value.session ?: return
        if (ordinal !in current.questions.indices || _state.value.loading) return
        if (current.record.status == "IN_PROGRESS") {
            accountTime()
            persist(_state.value.session ?: current)
        }
        val next = (_state.value.session ?: current).copy(currentOrdinal = ordinal)
        _state.update { it.copy(session = next) }
        if (next.record.status == "IN_PROGRESS") persist(next)
    }

    private fun persist(session: OrdinaryTestSession, onComplete: ((Boolean) -> Unit)? = null) {
        if (!identityActive || session.record.status != "IN_PROGRESS" || _state.value.loading) {
            onComplete?.invoke(false)
            return
        }
        val generation = loadingGeneration
        dirtyAnswers.putIfAbsent(session.currentOrdinal, ++saveSequence)
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            var savedForCaller = false
            try {
                mutex.withLock {
                    if (!saveAdmitted(session.record.id, generation)) return@withLock
                    ensureIdentity()
                    var failure: Exception? = null
                    val captured = _state.value.session ?: return@withLock
                    val pending = dirtyAnswers.toMap()
                    for ((ordinal, version) in pending) {
                        if (!saveAdmitted(session.record.id, generation)) return@withLock
                        ensureIdentity()
                        try {
                            service.save(
                                userId,
                                captured.record.id,
                                ordinal,
                                captured.answers[ordinal].userAnswer.orEmpty(),
                                captured.record.remainingSeconds ?: captured.record.durationLimitSeconds,
                                expectedIdentity,
                            )
                            ensureIdentity()
                            if (dirtyAnswers[ordinal] == version) dirtyAnswers.remove(ordinal)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            ensureIdentity()
                            failure = error
                        }
                    }
                    if (captured.currentOrdinal != pending.keys.lastOrNull() &&
                        captured.currentOrdinal !in dirtyAnswers &&
                        saveAdmitted(captured.record.id, generation)
                    ) {
                        ensureIdentity()
                        service.save(
                            userId,
                            captured.record.id,
                            captured.currentOrdinal,
                            captured.answers[captured.currentOrdinal].userAnswer.orEmpty(),
                            captured.record.remainingSeconds ?: captured.record.durationLimitSeconds,
                            expectedIdentity,
                        )
                        ensureIdentity()
                    }
                    if (saveAdmitted(session.record.id, generation)) {
                        _state.update {
                            it.copy(
                                saving = false,
                                error = failure?.let { err -> "保存失败：${err.message}；请重试保存后交卷" },
                            )
                        }
                        savedForCaller = failure == null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (saveAdmitted(session.record.id, generation)) {
                    _state.update {
                        it.copy(saving = false, error = "保存失败：${error.message}；请重试保存后交卷")
                    }
                }
            } finally {
                onComplete?.invoke(savedForCaller && saveAdmitted(session.record.id, generation))
            }
        }
    }

    fun save() {
        accountTime()
        _state.value.session?.let { persist(it) }
    }

    fun saveAndLeave(onComplete: (Boolean) -> Unit) {
        accountTime()
        val current = _state.value.session
        if (current == null) {
            onComplete(false)
            return
        }
        if (current.record.status == "IN_PROGRESS" && current.record.remainingSeconds == 0) {
            submit(autoTimeout = true)
            onComplete(false)
            return
        }
        persist(current) { ok ->
            if (ok) refreshHomeSummary()
            onComplete(ok)
        }
    }

    fun submit(autoTimeout: Boolean = false) {
        val current = _state.value.session ?: return
        if (_state.value.loading || current.record.status != "IN_PROGRESS") return
        accountTime()
        timer?.cancel()
        val captured = _state.value.session ?: current
        val generation = loadingGeneration
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val result = mutex.withLock {
                    captured.answers.forEachIndexed { ordinal, row ->
                        if (!admitted(captured.record.id, generation)) throw CancellationException("Test changed")
                        ensureIdentity()
                        service.save(
                            userId,
                            captured.record.id,
                            ordinal,
                            row.userAnswer.orEmpty(),
                            captured.record.remainingSeconds ?: captured.record.durationLimitSeconds,
                            expectedIdentity,
                        )
                        ensureIdentity()
                    }
                    if (!admitted(captured.record.id, generation)) throw CancellationException("Test changed")
                    ensureIdentity()
                    service.submit(userId, captured.record.id, expectedIdentity).copy(currentOrdinal = captured.currentOrdinal)
                }
                ensureIdentity()
                if (admitted(captured.record.id, generation)) {
                    dirtyAnswers.clear()
                    _state.update {
                        it.copy(
                            session = result,
                            loading = false,
                            saving = false,
                            notice = if (autoTimeout) "考试时间到，已自动交卷" else "已交卷，可查看解析与错题整理",
                        )
                    }
                    refreshHomeSummary()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (admitted(captured.record.id, generation)) {
                    _state.update {
                        it.copy(
                            loading = false,
                            saving = false,
                            error = "交卷失败：${error.message}；答案保留，请重试",
                        )
                    }
                    if (foreground) startTimer()
                }
            }
        }
    }

    fun toggleFavorite(questionId: Long) {
        if (!identityActive) return
        val favorite = questionId !in _state.value.manualFavorites
        viewModelScope.launch {
            try {
                ensureIdentity()
                service.setManualFavorite(userId, questionId, favorite, expectedIdentity)
                ensureIdentity()
                _state.update {
                    it.copy(notice = if (favorite) "已收藏到「我的 · 收藏题目」" else "已取消收藏")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(error = "收藏操作失败：${error.message}") }
            }
        }
    }

    fun removeWrongFavorite(questionId: Long) {
        if (!identityActive) return
        viewModelScope.launch {
            try {
                ensureIdentity()
                service.removeWrongFavorite(userId, questionId, expectedIdentity)
                ensureIdentity()
                _state.update { it.copy(notice = "已移出错题本（原成绩与手动收藏不受影响）") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(error = "移除错题失败：${error.message}") }
            }
        }
    }

    fun setForeground(active: Boolean) {
        if (foreground == active) return
        if (foreground) {
            accountTime()
            _state.value.session?.let { persist(it) }
        }
        foreground = active
        if (active) {
            lastTick = SystemClock.elapsedRealtime()
            startTimer()
        } else {
            timer?.cancel()
            timer = null
        }
    }

    private fun accountTime() {
        val session = _state.value.session ?: return
        if (!foreground || session.record.status != "IN_PROGRESS") return
        val now = SystemClock.elapsedRealtime()
        tickRemainder += (now - lastTick).coerceAtLeast(0)
        lastTick = now
        val seconds = (tickRemainder / 1000L).toInt()
        tickRemainder %= 1000L
        if (seconds > 0) _state.update { state ->
            val current = state.session ?: return@update state
            state.copy(
                session = current.copy(
                    record = current.record.copy(
                        remainingSeconds = ((current.record.remainingSeconds ?: current.record.durationLimitSeconds) - seconds)
                            .coerceAtLeast(0),
                    ),
                ),
            )
        }
    }

    private fun startTimer() {
        timer?.cancel()
        if (!foreground || _state.value.session?.record?.status != "IN_PROGRESS") return
        lastTick = SystemClock.elapsedRealtime()
        timer = viewModelScope.launch {
            var ticks = 0
            while (true) {
                delay(1000)
                accountTime()
                if (_state.value.session?.record?.remainingSeconds == 0) {
                    submit(autoTimeout = true)
                    return@launch
                }
                if (++ticks % 10 == 0) _state.value.session?.let { persist(it) }
            }
        }
    }

    class Factory(private val context: Context, private val identity: SessionIdentity) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(OrdinaryTestViewModel::class.java))
            val container = (context.applicationContext as LearningApplication).container
            return OrdinaryTestViewModel(container.ordinaryTestService, identity, container.sessionManager.identity) as T
        }
    }
}
