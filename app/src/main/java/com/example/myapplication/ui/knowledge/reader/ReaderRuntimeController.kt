package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Persistence errors never roll back a rendered and committed reading position. */
data class ReaderProgressSaveFailure(val token: ReaderSessionToken, val locator: ReaderLocator, val code: String, val retryable: Boolean,
    val outcomeUnknown: Boolean = false)

/** Owns an already successfully opened session. Call start once; collectors stay caller-owned. */
class ReaderRuntimeController(
    private val context: AccessContext,
    initial: ActiveReaderSession,
    sequence: ReadingSequence,
    adapterFactory: () -> ReaderEngineAdapter,
    private val progressWriter: suspend (AccessContext, ReaderLocator) -> OperationResult<Unit>,
    private val progressClose: suspend () -> Unit = {},
    allowedSectionIds: Set<String> = sequence.orderedTargets.flatMap { listOf(it.firstSectionId, it.lastSectionId) }.toSet(),
) {
    private val allowedSections = allowedSectionIds.toSet()
    init {
        require(initial.report.renderable && initial.report.token.userId == context.userId &&
            initial.report.token.sessionEpoch == context.sessionEpoch && initial.report.token.sessionId.isNotBlank())
        require(initial.report.token.documentId == initial.target.documentId && initial.report.token.contentRevision == initial.target.contentRevision)
        require(initial.report.currentLocator.sectionId in allowedSections)
        require(initial.report.currentLocator.documentId == initial.target.documentId && initial.report.currentLocator.contentRevision == initial.target.contentRevision &&
            ReaderLocatorValidator.validate(initial.report.currentLocator).isValid && sequence.orderedTargets.contains(initial.target))
    }
    private val coordinator = ReaderTurnCoordinator(initial, sequence, adapterFactory)
    val state = coordinator.state
    private val lock = Mutex()
    private val mutableSaveFailure = MutableStateFlow<ReaderProgressSaveFailure?>(null)
    val saveFailure = mutableSaveFailure.asStateFlow()
    private val mutableCleanupFailure = MutableStateFlow<ReaderCleanupFailure?>(null)
    val cleanupFailure = mutableCleanupFailure.asStateFlow()
    private val mutableClosed = MutableStateFlow(false)
    val isClosed = mutableClosed.asStateFlow()
    @Volatile private var started = false
    private var activeClosed = false
    private var sinkClosed = false
    private var closeFailure: ReaderCleanupFailure? = null
    private var attempted: Pair<ReaderSessionHandle, ReaderLocator>? = null

    /** Failure here affects saving only. The successfully opened session remains usable. */
    suspend fun start(): OperationResult<Unit> = lock.withLock {
        if (mutableClosed.value) return@withLock OperationResult.Rejected("RUNTIME_CLOSED")
        if (started) return@withLock OperationResult.Ok(Unit, context.requestId)
        started = true
        saveCommitted()
    }

    suspend fun turn(direction: ReaderTurnDirection, origin: ReaderTurnOrigin): TurnDecision = decision {
        if (!usable()) return@decision TurnDecision.Invalidated
        val decision = coordinator.turn(context, direction, origin)
        syncCleanup()
        if (decision is TurnDecision.Moved || decision == TurnDecision.Committed) saveCommitted()
        decision
    }
    suspend fun confirm(confirmation: TurnConfirmation): TurnDecision = decision {
        if (!usable()) return@decision TurnDecision.Invalidated
        val decision = coordinator.confirm(context, confirmation)
        syncCleanup()
        if (decision == TurnDecision.Committed || decision is TurnDecision.Moved) saveCommitted()
        decision
    }
    suspend fun cancel(confirmation: TurnConfirmation): Boolean = usable() && coordinator.cancel(confirmation)
    /** Only a validated, successful same-session relayout can replace the committed report. */
    suspend fun applyPreferences(preferences: ReaderLayoutPreferences): OperationResult<ReaderFeatureReport> {
        if (!lock.tryLock()) return OperationResult.Rejected("RUNTIME_BUSY")
        try {
            if (!usable()) return OperationResult.Rejected("RUNTIME_CLOSED")
            val before = state.value
            if (before.phase != ReaderTurnPhase.IDLE) return OperationResult.Rejected("RUNTIME_BUSY")
            val active = before.active
            val result = try { active.adapter.applyPreferences(active.report.token, preferences) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return OperationResult.Failed("LAYOUT_FAILED", true) }
            currentCoroutineContext().ensureActive()
            if (result is OperationResult.Ok) {
                val report = result.value
                if (!report.renderable || report.token.handle() != active.report.token.handle() ||
                    report.token.layoutGeneration <= active.report.token.layoutGeneration || report.eventSequence < 0 ||
                    !validLocator(report.currentLocator, active.target)) return OperationResult.Rejected("INVALID_LAYOUT_REPORT")
                coordinator.updateActive(active.copy(report = report))
                saveCommitted()
            }
            return result
        } finally { lock.unlock() }
    }
    suspend fun navigate(locator: ReaderLocator): ReaderNavigationResult = lock.withLock {
        if (!usable()) return@withLock ReaderNavigationResult.Unavailable("RUNTIME_CLOSED")
        val before = state.value
        if (before.phase != ReaderTurnPhase.IDLE) return@withLock ReaderNavigationResult.Unavailable("RUNTIME_BUSY")
        val active = before.active
        val result = try { active.adapter.navigate(active.report.token, locator) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { return@withLock ReaderNavigationResult.Unavailable("NAVIGATION_FAILED") }
        currentCoroutineContext().ensureActive()
        if (result is ReaderNavigationResult.Exact) {
            if (!validLocator(result.locator, active.target)) return@withLock ReaderNavigationResult.Unavailable("INVALID_NAVIGATION_LOCATOR")
            coordinator.updateActive(active.copy(report = active.report.copy(currentLocator = result.locator)))
            saveCommitted()
        }
        result
    }

    /** No internal scope: the owner launches/cancels this collector with its own lifecycle. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun collectFeatureEvents() {
        combine(state, isClosed) { current, closed -> if (closed) null else current.active }
            .distinctUntilChangedBy { it?.report?.token }
            .flatMapLatest { active ->
                if (active == null) flowOf<ReaderFeatureReport?>(null)
                else active.adapter.featureEvents(active.report.token).map { it as ReaderFeatureReport? }
            }
            .takeWhile { it != null }
            .filterNotNull()
            .collect { acceptFeatureReport(it) }
    }
    suspend fun acceptFeatureReport(report: ReaderFeatureReport): Boolean = lock.withLock {
        if (!usable()) return@withLock false
        val current = state.value
        val active = current.active
        val previous = active.report
        if (current.phase != ReaderTurnPhase.IDLE || !report.renderable || report.token.handle() != previous.token.handle() ||
            report.token.layoutGeneration < previous.token.layoutGeneration ||
            (report.token.layoutGeneration == previous.token.layoutGeneration && report.eventSequence <= previous.eventSequence) ||
            report.eventSequence < 0 || !validLocator(report.currentLocator, active.target)) return@withLock false
        coordinator.updateActive(active.copy(report = report))
        saveCommitted()
        true
    }
    suspend fun retrySave(): OperationResult<Unit> = lock.withLock {
        if (!usable()) return@withLock OperationResult.Rejected("RUNTIME_CLOSED")
        mutableSaveFailure.value?.takeIf { !it.retryable }?.let { return@withLock OperationResult.Rejected(it.code) }
        saveCommitted(force = true)
    }
    suspend fun retryCleanup(): Boolean = lock.withLock {
        if (mutableClosed.value) closeLocked() else coordinator.retryCleanup().also { syncCleanup() }
    }
    /** Failures remain observable and retryable. Further operations and collectors stop immediately. */
    suspend fun close(): Boolean = withContext(NonCancellable) { lock.withLock { closeLocked() } }
    private suspend fun closeLocked(): Boolean {
        mutableClosed.value = true
        val previousClean = coordinator.retryCleanup()
        val active = state.value.active
        if (!activeClosed) {
            try { active.adapter.close(active.report.token.handle()); activeClosed = true }
            catch (_: Exception) { closeFailure = ReaderCleanupFailure(active.report.token.handle(), "ACTIVE_CLOSE_FAILED") }
        }
        if (!sinkClosed) {
            try { progressClose(); sinkClosed = true }
            catch (_: Exception) { closeFailure = ReaderCleanupFailure(active.report.token.handle(), "PROGRESS_CLOSE_FAILED") }
        }
        if (activeClosed && sinkClosed) closeFailure = null
        syncCleanup()
        return previousClean && activeClosed && sinkClosed
    }
    private suspend fun decision(action: suspend () -> TurnDecision): TurnDecision {
        if (!lock.tryLock()) return TurnDecision.Busy
        try { return action() } finally { lock.unlock() }
    }
    private fun usable() = started && !mutableClosed.value
    private fun syncCleanup() { mutableCleanupFailure.value = closeFailure ?: coordinator.cleanupFailure.value }
    private fun validLocator(locator: ReaderLocator, target: ReaderTarget) =
        locator.sectionId in allowedSections && locator.documentId == target.documentId && locator.contentRevision == target.contentRevision &&
            (target.firstSectionId != target.lastSectionId || locator.sectionId == target.firstSectionId) && ReaderLocatorValidator.validate(locator).isValid

    private suspend fun saveCommitted(force: Boolean = false): OperationResult<Unit> {
        val report = state.value.active.report
        if (!validLocator(report.currentLocator, state.value.active.target)) {
            mutableSaveFailure.value = ReaderProgressSaveFailure(report.token, report.currentLocator, "INVALID_COMMITTED_LOCATOR", false)
            return OperationResult.Rejected("INVALID_COMMITTED_LOCATOR")
        }
        val key = report.token.handle() to report.currentLocator
        val previouslyUnknown = mutableSaveFailure.value?.let {
            it.token.handle() == report.token.handle() && it.locator == report.currentLocator && it.outcomeUnknown
        } == true
        if (!force && attempted == key) return OperationResult.Ok(Unit, context.requestId)
        attempted = key
        val result = try { progressWriter(context, report.currentLocator) }
        catch (error: CancellationException) {
            mutableSaveFailure.value = ReaderProgressSaveFailure(report.token, report.currentLocator, "PROGRESS_SAVE_CANCELLED", true, outcomeUnknown = true)
            throw error
        } catch (_: Exception) { OperationResult.Failed("PROGRESS_SAVE_FAILED", true) }
        mutableSaveFailure.value = when (result) {
            is OperationResult.Ok -> null
            is OperationResult.Rejected -> ReaderProgressSaveFailure(report.token, report.currentLocator, result.code, false, previouslyUnknown)
            is OperationResult.Failed -> ReaderProgressSaveFailure(report.token, report.currentLocator, result.code, result.retryable, outcomeUnknown = true)
            is OperationResult.Conflict -> ReaderProgressSaveFailure(report.token, report.currentLocator, "PROGRESS_SAVE_CONFLICT", result.recoverable, previouslyUnknown)
        }
        return result
    }
}



