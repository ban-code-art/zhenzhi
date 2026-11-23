package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.UUID

/** The sole committed session. Persistence consumers observe [ReaderTurnCoordinator.state]. */
data class ActiveReaderSession(val target: ReaderTarget, val report: ReaderFeatureReport, val adapter: ReaderEngineAdapter)
data class ReaderCleanupFailure(val handle: ReaderSessionHandle, val code: String)
enum class ReaderTurnPhase { IDLE, TURNING, CONFIRMING, OPENING }
data class TurnConfirmation(
    val id: String,
    val sequenceVersion: String,
    val sourceToken: ReaderSessionToken,
    val sourceTarget: ReaderTarget,
    val target: ReaderTarget,
    val placement: ReaderPlacement,
)
data class ReaderTurnState(
    val active: ActiveReaderSession,
    val phase: ReaderTurnPhase = ReaderTurnPhase.IDLE,
    val confirmation: TurnConfirmation? = null,
)
sealed interface TurnDecision {
    data object Busy : TurnDecision
    data object Invalidated : TurnDecision
    data object Committed : TurnDecision
    data class Moved(val locator: ReaderLocator) : TurnDecision
    data class ConfirmationRequired(val confirmation: TurnConfirmation) : TurnDecision
    data class BookBoundary(val direction: ReaderTurnDirection) : TurnDecision
    data class Failed(val code: String, val retryable: Boolean) : TurnDecision
}

/**
 * Transactional unit switching, independent of UI and persistence. Factory must return a fresh,
 * independently owned adapter for every open. An adapter must clean up allocations when open
 * throws or returns a failure without a report: the caller has no handle in that case.
 * Returned reports transfer cleanup responsibility here. Only committed state may be persisted.
 * A close failure is observable via cleanupFailure and blocks further opens until retryCleanup succeeds; committed state never rolls back.
 */
class ReaderTurnCoordinator(
    initial: ActiveReaderSession,
    sequence: ReadingSequence,
    private val adapterFactory: () -> ReaderEngineAdapter,
) {
    private val monitor = Any()
    private val flight = Mutex()
    private var sequence = sequence
    private var generation = 0L
    private val mutableState = MutableStateFlow(ReaderTurnState(initial))
    val state: StateFlow<ReaderTurnState> = mutableState.asStateFlow()
    private val boundary = TurnPageCoordinator()
    private var pendingCleanup: Pair<ReaderEngineAdapter, ReaderSessionHandle>? = null
    private var quarantinedAdapter: ReaderEngineAdapter? = null
    private val mutableCleanupFailure = MutableStateFlow<ReaderCleanupFailure?>(null)
    val cleanupFailure: StateFlow<ReaderCleanupFailure?> = mutableCleanupFailure.asStateFlow()
    private val usedAdapters = mutableListOf(java.lang.ref.WeakReference(initial.adapter))
    private val usedSessionIds = mutableSetOf(initial.report.token.sessionId)

    fun replaceSequence(sequence: ReadingSequence) = synchronized(monitor) {
        this.sequence = sequence
        invalidate()
    }

    /** Same-session layout/report updates only. Session ownership remains in this coordinator. */
    fun updateActive(active: ActiveReaderSession) = synchronized(monitor) {
        val previous = mutableState.value.active
        require(active.adapter === previous.adapter && active.report.token.handle() == previous.report.token.handle() && active.target == previous.target) { "updateActive only accepts the current session" }
        generation++
        mutableState.value = ReaderTurnState(active)
    }

    fun cancel(confirmation: TurnConfirmation): Boolean = synchronized(monitor) {
        if (mutableState.value.confirmation != confirmation) false else { invalidate(); true }
    }

    private fun invalidate() {
        generation++
        mutableState.value = ReaderTurnState(mutableState.value.active)
    }

    private fun permits(context: AccessContext, token: ReaderSessionToken) =
        context.userId == token.userId && context.sessionEpoch == token.sessionEpoch

    private fun validLocator(locator: ReaderLocator, target: ReaderTarget) =
        locator.documentId == target.documentId && locator.contentRevision == target.contentRevision &&
            ReaderLocatorValidator.validate(locator).isValid

    suspend fun turn(context: AccessContext, direction: ReaderTurnDirection, origin: ReaderTurnOrigin): TurnDecision {
        if (!flight.tryLock()) return TurnDecision.Busy
        var ticket: Long? = null
        try {
            val source = synchronized(monitor) {
                val current = mutableState.value
                if (current.phase != ReaderTurnPhase.IDLE) return TurnDecision.Busy
                if (!permits(context, current.active.report.token) || sequence.indexOf(current.active.target) < 0) return TurnDecision.Invalidated
                ticket = generation
                mutableState.value = current.copy(phase = ReaderTurnPhase.TURNING)
                current.active
            }
            val result = source.adapter.turn(source.report.token, direction, origin)
            val callerContext = currentCoroutineContext()
            callerContext.ensureActive()
            return synchronized(monitor) {
                callerContext.ensureActive()
                if (ticket != generation || mutableState.value.active != source) return@synchronized TurnDecision.Invalidated
                when (result) {
                    is ReaderTurnResult.Moved -> {
                        if (!validLocator(result.locator, source.target)) TurnDecision.Failed("INVALID_LOCATOR", false)
                        else {
                            mutableState.value = ReaderTurnState(source.copy(report = source.report.copy(currentLocator = result.locator)))
                            TurnDecision.Moved(result.locator)
                        }
                    }
                    is ReaderTurnResult.UnitBoundary -> {
                        if (result.direction != direction) return@synchronized TurnDecision.Failed("INVALID_DIRECTION", false)
                        when (val decision = boundary.resolve(sequence, source.target, direction)) {
                            TurnBoundary.Invalidated -> TurnDecision.Invalidated
                            is TurnBoundary.BookBoundary -> TurnDecision.BookBoundary(direction)
                            is TurnBoundary.Adjacent -> {
                                val confirmation = TurnConfirmation(UUID.randomUUID().toString(), sequence.version, source.report.token, sequence.orderedTargets[sequence.indexOf(source.target)], decision.target, decision.placement)
                                mutableState.value = ReaderTurnState(source, ReaderTurnPhase.CONFIRMING, confirmation)
                                TurnDecision.ConfirmationRequired(confirmation)
                            }
                        }
                    }
                    ReaderTurnResult.Busy -> TurnDecision.Busy
                    is ReaderTurnResult.Failed -> TurnDecision.Failed(result.code, result.retryable)
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { return TurnDecision.Failed("TURN_FAILED", true)
        } finally {
            synchronized(monitor) {
                if (ticket == generation && mutableState.value.phase == ReaderTurnPhase.TURNING) mutableState.value = ReaderTurnState(mutableState.value.active)
            }
            flight.unlock()
        }
    }

    suspend fun confirm(context: AccessContext, confirmation: TurnConfirmation): TurnDecision {
        if (!flight.tryLock()) return TurnDecision.Busy
        var ticket: Long? = null
        var staged: ReaderEngineAdapter? = null
        var opened: ReaderFeatureReport? = null
        var committed = false
        try {
            val source = synchronized(monitor) {
                val current = mutableState.value
                if (current.phase == ReaderTurnPhase.OPENING) return TurnDecision.Busy
                if (pendingCleanup != null || quarantinedAdapter != null) return TurnDecision.Failed("CLEANUP_REQUIRED", quarantinedAdapter == null)
                if (current.confirmation != confirmation || sequence.version != confirmation.sequenceVersion ||
                    current.active.report.token != confirmation.sourceToken || current.active.target != confirmation.sourceTarget ||
                    !permits(context, current.active.report.token) || sequence.indexOf(confirmation.target) < 0 ||
                    sequence.orderedTargets[sequence.indexOf(confirmation.target)] != confirmation.target) {
                    if (current.confirmation == confirmation) invalidate()
                    return TurnDecision.Invalidated
                }
                ticket = generation
                mutableState.value = current.copy(phase = ReaderTurnPhase.OPENING)
                current.active
            }
            val adapter = adapterFactory()
            val freshAdapter = synchronized(monitor) {
                usedAdapters.removeAll { it.get() == null }
                if (usedAdapters.any { it.get() === adapter }) false
                else { usedAdapters.add(java.lang.ref.WeakReference(adapter)); true }
            }
            if (!freshAdapter) return TurnDecision.Failed("ADAPTER_REUSED", false)
            staged = adapter
            when (val result = adapter.open(context, ReaderOpenRequest(confirmation.target, confirmation.placement))) {
                is OperationResult.Ok -> opened = result.value
                is OperationResult.Failed -> return TurnDecision.Failed(result.code, result.retryable)
                is OperationResult.Rejected -> return TurnDecision.Failed(result.code, false)
                is OperationResult.Conflict -> return TurnDecision.Failed("OPEN_CONFLICT", result.recoverable)
            }
            val report = checkNotNull(opened)
            val uniqueSession = synchronized(monitor) { usedSessionIds.add(report.token.sessionId) }
            val callerContext = currentCoroutineContext()
            callerContext.ensureActive()
            if (!permits(context, report.token) || report.token.documentId != confirmation.target.documentId ||
                report.token.contentRevision != confirmation.target.contentRevision || !report.renderable ||
                !uniqueSession || report.token.sessionId.isBlank() || !validLocator(report.currentLocator, confirmation.target)) {
                return TurnDecision.Failed("INVALID_OPEN_REPORT", false)
            }
            synchronized(monitor) {
                if (ticket != generation || mutableState.value.confirmation != confirmation || mutableState.value.active != source) return TurnDecision.Invalidated
                callerContext.ensureActive()
                mutableState.value = ReaderTurnState(ActiveReaderSession(confirmation.target, report, adapter))
                committed = true
            }
            closeSafely(source.adapter, source.report.token.handle())
            return TurnDecision.Committed
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { return TurnDecision.Failed("OPEN_FAILED", true)
        } finally {
            val returned = opened
            if (!committed && staged != null && returned != null) {
                // A malformed report cannot authorize closing the active session's handle.
                val activeHandle = synchronized(monitor) { mutableState.value.active.report.token.handle() }
                if (returned.token.handle() != activeHandle) closeSafely(staged, returned.token.handle())
                else synchronized(monitor) {
                    quarantinedAdapter = staged
                    mutableCleanupFailure.value = ReaderCleanupFailure(activeHandle, "INVALID_OPEN_HANDLE")
                }
            }
            synchronized(monitor) {
                if (ticket == generation && mutableState.value.phase == ReaderTurnPhase.OPENING) mutableState.value = ReaderTurnState(mutableState.value.active)
            }
            flight.unlock()
        }
    }

    /** No further opens are permitted until the resource owner confirms successful cleanup. */
    suspend fun retryCleanup(): Boolean {
        if (!flight.tryLock()) return false
        return try {
            val pending = synchronized(monitor) {
                if (quarantinedAdapter != null) return false
                pendingCleanup
            } ?: return true
            closeSafely(pending.first, pending.second)
        } finally { flight.unlock() }
    }

    private suspend fun closeSafely(adapter: ReaderEngineAdapter, handle: ReaderSessionHandle): Boolean =
        withContext(NonCancellable) {
            try {
                adapter.close(handle)
                synchronized(monitor) { pendingCleanup = null; mutableCleanupFailure.value = null }
                true
            } catch (_: Exception) {
                synchronized(monitor) {
                    pendingCleanup = adapter to handle
                    mutableCleanupFailure.value = ReaderCleanupFailure(handle, "CLOSE_FAILED")
                }
                false
            }
        }
}





