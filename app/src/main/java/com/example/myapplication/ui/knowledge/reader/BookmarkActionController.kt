package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.notes.BookmarkToggleResult
import com.example.myapplication.data.reader.notes.ReaderBookmarkView
import com.example.myapplication.data.reader.notes.ReaderMutationHasher
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import com.example.myapplication.domain.reader.ReaderSelectionFragment
import com.example.myapplication.domain.reader.ReaderSessionToken
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * positionId identifies this particular selection/viewport occurrence, including position ABA.
 * The host must change it for a newly established selection or position, even at the same anchor.
 */
data class BookmarkPositionInput(
    val identity: SessionIdentity,
    val token: ReaderSessionToken,
    val positionId: String,
    val writable: Boolean,
    val selection: List<ReaderSelectionFragment>? = null,
    val viewport: ReaderLocator? = null,
    val visibleRanges: List<ReaderLocator> = emptyList(),
)

/** UI adapter for the position resolver; matching, reading order and preview belong to that resolver. */
sealed interface BookmarkActionResolution {
    data class Target(val locator: ReaderLocator, val anchorHash: String, val preview: String)
    data class Resolved(val target: Target, val bookmark: ReaderBookmarkView?) : BookmarkActionResolution
    data class Ambiguous(val target: Target, val bookmarkIds: List<String>) : BookmarkActionResolution
    data class Unavailable(val reason: String) : BookmarkActionResolution
}

/**
 * observeBookmarks must create a fresh, current-user-isolated repository Flow on every invocation.
 * ownsPosition must read the host's real current identity, complete token and position occurrence;
 * merely comparing the argument with a cached controller State is not an ownership proof.
 * Every callback, including Flow creation/collection, runs outside the controller's monitor.
 */
class BookmarkActionServices(
    val identityNow: suspend () -> SessionIdentity,
    val ownsPosition: suspend (BookmarkPositionInput) -> Boolean,
    val observeBookmarks: (Long, String) -> Flow<List<ReaderBookmarkView>>,
    val resolvePosition: (String, String, List<ReaderSelectionFragment>?, ReaderLocator?, List<ReaderBookmarkView>) -> BookmarkActionResolution,
    val ensureBookmark: suspend (AccessContext, String, ReaderLocator, String) -> OperationResult<ReaderBookmarkView>,
    val toggleBookmark: suspend (AccessContext, String, ReaderLocator, String) -> OperationResult<BookmarkToggleResult>,
)

enum class BookmarkActionIntent { ENSURE_ADDED, TOGGLE }
enum class BookmarkObservationPhase { EMPTY, LOADING, READY, FAILED, INVALIDATED, CLOSED }
enum class BookmarkWritePhase { IDLE, SAVING, SYNCHRONIZING, FAILED, CANCELLED }
enum class BookmarkActionFailureKind {
    INPUT, IDENTITY, OWNERSHIP, OBSERVATION, RESOLUTION,
    CONFLICT, REJECTED, FAILED, CANCELLED, RECEIPT,
}

/** Technical detail is retained for the host; the buttons translate kind into user-facing Chinese. */
data class BookmarkActionFailure(
    val kind: BookmarkActionFailureKind,
    val code: String,
    val retryable: Boolean,
    val outcomeUnknown: Boolean = false,
    val currentRevision: Int? = null,
)

data class BookmarkActionState(
    val generation: Long = 0L,
    val input: BookmarkPositionInput? = null,
    val observation: BookmarkObservationPhase = BookmarkObservationPhase.EMPTY,
    val resolution: BookmarkActionResolution? = null,
    val write: BookmarkWritePhase = BookmarkWritePhase.IDLE,
    val pendingIntent: BookmarkActionIntent? = null,
    val actionFailure: BookmarkActionFailure? = null,
    val observationFailure: BookmarkActionFailure? = null,
    val candidates: List<ReaderBookmarkView> = emptyList(),
) {
    val isBookmarked: Boolean
        get() = observation == BookmarkObservationPhase.READY &&
            (resolution as? BookmarkActionResolution.Resolved)?.bookmark != null

    val ambiguousBookmarkIds: List<String>
        get() = (resolution as? BookmarkActionResolution.Ambiguous)?.bookmarkIds.orEmpty()

    val showsProgress: Boolean
        get() = write == BookmarkWritePhase.SAVING || observation == BookmarkObservationPhase.LOADING ||
            (write == BookmarkWritePhase.SYNCHRONIZING &&
                observation != BookmarkObservationPhase.FAILED &&
                observation != BookmarkObservationPhase.INVALIDATED &&
                observation != BookmarkObservationPhase.CLOSED)

    val canRetryObservation: Boolean
        get() = input != null && observation == BookmarkObservationPhase.FAILED &&
            write != BookmarkWritePhase.SAVING && observationFailure?.retryable == true

    fun canInvoke(intent: BookmarkActionIntent): Boolean =
        input?.writable == true && observation == BookmarkObservationPhase.READY &&
            resolution is BookmarkActionResolution.Resolved && !showsProgress &&
            (pendingIntent == null || (pendingIntent == intent && actionFailure?.retryable == true))

    fun canRetryWrite(intent: BookmarkActionIntent): Boolean =
        pendingIntent == intent && actionFailure?.retryable == true && canInvoke(intent)
}

/**
 * Local tickets prevent duplicate/reversed toggles; they are not a shared DataStore/Room commit lock.
 * Identity and host ownership are checked immediately before and after repository IO, but those
 * separate systems can still change between checks. Production repositories must keep their own
 * access and idempotency checks. A receipt never supplies the current bookmark/green state.
 *
 * Unconfirmed tickets are indexed by the complete, deeply frozen input, never by anchor alone.
 * Navigation retires the visible generation while retaining those tickets until confirmed or close.
 * All work belongs to a child Job; closing this controller cannot cancel the supplied parent scope.
 */
class BookmarkActionController(
    parentScope: CoroutineScope,
    private val services: BookmarkActionServices,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    private val monitor = Any()
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val mutableState = MutableStateFlow(BookmarkActionState())
    val state = mutableState.asStateFlow()

    private data class Ticket(
        val input: BookmarkPositionInput,
        val target: BookmarkActionResolution.Target,
        val context: AccessContext,
        val intent: BookmarkActionIntent,
    )

    private sealed interface Receipt {
        data class Ensured(val bookmark: ReaderBookmarkView) : Receipt
        data class Toggled(val result: BookmarkToggleResult) : Receipt
    }

    private class Pending(val ticket: Ticket) {
        var receipt: OperationResult.Ok<Receipt>? = null
        var failure: BookmarkActionFailure? = null
        var active: Attempt? = null
        var latest: Attempt? = null
    }

    private class Attempt(val pending: Pending, val generation: Long) {
        val completion = CompletableDeferred<Unit>()
        var job: Deferred<OperationResult<Receipt>>? = null
        var invoked = false
    }

    private class Observation(val generation: Long, val input: BookmarkPositionInput) {
        var job: Job? = null
    }

    private data class ObservationChange(val oldJob: Job?, val next: Observation)
    private data class Candidate(
        val generation: Long,
        val input: BookmarkPositionInput,
        val resolution: BookmarkActionResolution.Resolved,
        val pending: Pending?,
    )

    private enum class Proof { VALID, IDENTITY_CHANGED, POSITION_CHANGED }

    private val pendingByInput = mutableMapOf<BookmarkPositionInput, Pending>()
    private var generation = 0L
    private var currentInput: BookmarkPositionInput? = null
    private var currentObservation: Observation? = null
    private var closed = false
    private var selectedBookmarkId: String? = null

    init {
        ownerJob.invokeOnCompletion { invalidateClosed() }
    }

    /** Immediately invalidates old input/Flow ownership, before any suspendable proof is requested. */
    fun updatePosition(input: BookmarkPositionInput?): OperationResult<Unit> {
        val frozen = try {
            input?.let(::freezeInput)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return invalidateInvalidInput()
        }
        val valid = frozen == null || validInput(frozen)
        val oldJobs: List<Job>
        val observation: Observation?
        synchronized(monitor) {
            if (closed || !ownerJob.isActive) return rejected("BOOKMARK_CONTROLLER_CLOSED")
            if (valid && frozen == currentInput &&
                mutableState.value.observation != BookmarkObservationPhase.INVALIDATED
            ) return accepted()
            oldJobs = listOfNotNull(currentObservation?.job, currentInput?.let { pendingByInput[it]?.active?.job })
            generation += 1L
            currentInput = frozen
            selectedBookmarkId = null
            currentObservation = null
            val pending = frozen?.let(pendingByInput::get)
            mutableState.value = BookmarkActionState(
                generation = generation,
                input = frozen,
                observation = when {
                    !valid -> BookmarkObservationPhase.INVALIDATED
                    frozen == null -> BookmarkObservationPhase.EMPTY
                    else -> BookmarkObservationPhase.LOADING
                },
                write = phaseFor(pending),
                pendingIntent = pending?.ticket?.intent,
                actionFailure = pending?.failure,
                observationFailure = if (valid) null else inputFailure(),
            )
            observation = if (valid && frozen != null) Observation(generation, frozen).also {
                currentObservation = it
            } else null
        }
        oldJobs.forEach { it.cancel() }
        observation?.let(::launchObservation)
        return if (valid) accepted() else rejected("BOOKMARK_INVALID_INPUT")
    }

    suspend fun ensureBookmark(): OperationResult<ReaderBookmarkView> {
        return when (val result = perform(BookmarkActionIntent.ENSURE_ADDED)) {
            is OperationResult.Ok -> {
                val receipt = result.value as? Receipt.Ensured
                    ?: return OperationResult.Failed("BOOKMARK_RECEIPT_INTENT_MISMATCH", true)
                OperationResult.Ok(receipt.bookmark, result.operationId)
            }
            is OperationResult.Conflict -> result
            is OperationResult.Rejected -> result
            is OperationResult.Failed -> result
        }
    }

    suspend fun toggleBookmark(): OperationResult<BookmarkToggleResult> {
        return when (val result = perform(BookmarkActionIntent.TOGGLE)) {
            is OperationResult.Ok -> {
                val receipt = result.value as? Receipt.Toggled
                    ?: return OperationResult.Failed("BOOKMARK_RECEIPT_INTENT_MISMATCH", true)
                OperationResult.Ok(receipt.result, result.operationId)
            }
            is OperationResult.Conflict -> result
            is OperationResult.Rejected -> result
            is OperationResult.Failed -> result
        }
    }

    /** Replays the same intent/context/payload; a known commit retries observation only. */
    suspend fun retryWrite(): OperationResult<Unit> {
        currentCoroutineContext().ensureActive()
        val pending = synchronized(monitor) {
            if (closed) return rejected("BOOKMARK_CONTROLLER_CLOSED")
            currentInput?.let(pendingByInput::get) ?: return rejected("BOOKMARK_NO_PENDING_REQUEST")
        }
        if (synchronized(monitor) { pending.receipt != null }) return refreshCommitted(pending)
        return when (val result = perform(pending.ticket.intent, expectedPending = pending)) {
            is OperationResult.Ok -> OperationResult.Ok(Unit, result.operationId)
            is OperationResult.Conflict -> result
            is OperationResult.Rejected -> result
            is OperationResult.Failed -> result
        }
    }

    /** Creates a fresh cold subscription, also for ensure's existing-row/no-DB-change success. */
    fun retryObservation(): OperationResult<Unit> {
        val change = synchronized(monitor) {
            if (closed || !ownerJob.isActive) return rejected("BOOKMARK_CONTROLLER_CLOSED")
            val input = currentInput ?: return rejected("BOOKMARK_NO_POSITION")
            if (mutableState.value.write == BookmarkWritePhase.SAVING) return rejected("BOOKMARK_ACTION_BUSY")
            if (mutableState.value.observation == BookmarkObservationPhase.LOADING) return rejected("BOOKMARK_OBSERVATION_BUSY")
            if (mutableState.value.observation == BookmarkObservationPhase.INVALIDATED) return rejected("BOOKMARK_POSITION_INVALIDATED")
            replaceObservationLocked(input)
        }
        applyObservationChange(change)
        return accepted()
    }

    /** A choice is scoped to this occurrence and cannot replace an unresolved mutation ticket. */
    fun selectBookmark(bookmarkId: String): OperationResult<Unit> = synchronized(monitor) {
        val state = mutableState.value
        val input = currentInput ?: return@synchronized rejected("BOOKMARK_NO_POSITION")
        if (state.observation != BookmarkObservationPhase.READY || state.showsProgress || pendingByInput[input] != null)
            return@synchronized rejected("BOOKMARK_ACTION_BUSY")
        val ambiguous = state.resolution as? BookmarkActionResolution.Ambiguous
            ?: return@synchronized rejected("BOOKMARK_NOT_AMBIGUOUS")
        if (bookmarkId !in ambiguous.bookmarkIds) return@synchronized rejected("BOOKMARK_CHOICE_CHANGED")
        val row = state.candidates.singleOrNull { it.id == bookmarkId }
            ?: return@synchronized rejected("BOOKMARK_CHOICE_CHANGED")
        // Duplicate hashes cannot be safely addressed by the repository's anchor-only toggle.
        if (state.candidates.count { it.anchorHash == row.anchorHash } != 1)
            return@synchronized rejected("BOOKMARK_DUPLICATE_ANCHOR")
        val locator = row.locator ?: return@synchronized rejected("BOOKMARK_CHOICE_CHANGED")
        selectedBookmarkId = row.id
        mutableState.value = state.copy(resolution = BookmarkActionResolution.Resolved(
            BookmarkActionResolution.Target(locator, row.anchorHash, bookmarkPreview(row.preview)), row))
        accepted()
    }

    private fun bookmarkPreview(value: String): String {
        var end = minOf(160, value.length)
        if (end > 0 && end < value.length && Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end--
        return value.substring(0, end)
    }

    override fun close() {
        invalidateClosed()
        ownerJob.cancel()
    }

    private suspend fun perform(intent: BookmarkActionIntent, expectedPending: Pending? = null): OperationResult<Receipt> {
        currentCoroutineContext().ensureActive()
        // A previously verified receipt may only schedule a refresh. It cannot create a new toggle.
        val committed = synchronized(monitor) {
            val pending = currentInput?.let(pendingByInput::get)
            if (expectedPending != null && pending !== expectedPending) return rejected("BOOKMARK_POSITION_CHANGED")
            pending?.takeIf { it.ticket.intent == intent && it.receipt != null }
        }
        if (committed != null) {
            val refreshed = refreshCommitted(committed)
            if (refreshed !is OperationResult.Ok) return failureResult(refreshed)
            return synchronized(monitor) { checkNotNull(committed.receipt) }
        }
        val candidate = synchronized(monitor) {
            if (closed || !ownerJob.isActive) return rejected("BOOKMARK_CONTROLLER_CLOSED")
            val before = mutableState.value
            val input = currentInput ?: return rejected("BOOKMARK_NO_POSITION")
            val pending = pendingByInput[input]
            if (expectedPending != null && pending !== expectedPending) return rejected("BOOKMARK_POSITION_CHANGED")
            if (pending?.active != null || before.showsProgress) return rejected("BOOKMARK_ACTION_BUSY")
            if (pending != null && pending.ticket.intent != intent) return rejected("BOOKMARK_PENDING_OTHER_INTENT")
            if (!before.canInvoke(intent)) return rejected("BOOKMARK_POSITION_NOT_WRITABLE")
            val resolved = before.resolution as? BookmarkActionResolution.Resolved
                ?: return rejected("BOOKMARK_POSITION_UNRESOLVED")
            Candidate(generation, input, resolved, pending)
        }
        // ID allocation and payload freezing are deliberately outside the monitor.
        val newTicket = if (candidate.pending == null) {
            try {
                val requestId = newRequestId()
                if (!validIdentifier(requestId, 128)) return failIdAllocation(candidate)
                Ticket(candidate.input, freezeTarget(candidate.resolution.target),
                    AccessContext(checkNotNull(candidate.input.identity.userId), candidate.input.identity.epoch, requestId), intent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failIdAllocation(candidate)
            }
        } else null
        val attempt = synchronized(monitor) {
            val before = mutableState.value
            if (!current(candidate.generation, candidate.input) || before.resolution != candidate.resolution ||
                !before.canInvoke(intent)
            ) return rejected("BOOKMARK_POSITION_CHANGED")
            val existing = pendingByInput[candidate.input]
            if (existing !== candidate.pending || existing?.active != null || existing?.receipt != null)
                return rejected("BOOKMARK_ACTION_BUSY")
            val selected = existing ?: Pending(checkNotNull(newTicket)).also { pendingByInput[candidate.input] = it }
            Attempt(selected, generation).also {
                selected.active = it
                selected.latest = it
                selected.failure = null
                mutableState.value = before.copy(write = BookmarkWritePhase.SAVING, pendingIntent = intent, actionFailure = null)
            }
        }
        val deferred = scope.async(start = CoroutineStart.LAZY) { runAttempt(attempt) }
        synchronized(monitor) { attempt.job = deferred }
        deferred.invokeOnCompletion { cause ->
            if (cause is CancellationException) markCancelled(attempt)
            finishAttempt(attempt)
        }
        return try {
            currentCoroutineContext().ensureActive()
            deferred.start()
            deferred.await()
        } catch (cancelled: CancellationException) {
            deferred.cancel(cancelled)
            markCancelled(attempt)
            throw cancelled
        }
    }

    private suspend fun runAttempt(attempt: Attempt): OperationResult<Receipt> {
        val ticket = attempt.pending.ticket
        try {
            currentCoroutineContext().ensureActive()
            if (!attemptCurrent(attempt)) {
                val result = rejected("BOOKMARK_POSITION_CHANGED")
                markFailure(attempt, failureFor(result))
                return result
            }
            val beforeProof = prove(ticket.input)
            currentCoroutineContext().ensureActive()
            if (!attemptCurrent(attempt)) {
                val result = rejected("BOOKMARK_POSITION_CHANGED")
                markFailure(attempt, failureFor(result))
                return result
            }
            if (beforeProof != Proof.VALID) {
                val failure = proofFailure(beforeProof)
                markFailure(attempt, failure)
                invalidateProof(attempt.generation, ticket.input, failure)
                return rejected(failure.code)
            }
            val admitted = synchronized(monitor) {
                if (!attemptCurrentLocked(attempt) || !ticket.input.writable ||
                    mutableState.value.observation != BookmarkObservationPhase.READY ||
                    mutableState.value.resolution !is BookmarkActionResolution.Resolved
                ) false else {
                    attempt.invoked = true
                    true
                }
            }
            if (!admitted) {
                val result = rejected("BOOKMARK_POSITION_CHANGED")
                markFailure(attempt, failureFor(result))
                return result
            }
            currentCoroutineContext().ensureActive()
            val raw = execute(ticket)
            // Record a valid commit before any further suspendable proof/caller cancellation.
            val result = try {
                validateReceipt(ticket, raw)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OperationResult.Failed("BOOKMARK_RECEIPT_MISMATCH", true)
            }
            if (result is OperationResult.Ok) synchronized(monitor) {
                attempt.pending.receipt = result
                attempt.pending.failure = null
            }
            currentCoroutineContext().ensureActive()
            val afterProof = prove(ticket.input)
            currentCoroutineContext().ensureActive()
            if (afterProof != Proof.VALID) {
                if (result !is OperationResult.Ok) markFailure(attempt, failureFor(result))
                invalidateProof(attempt.generation, ticket.input, proofFailure(afterProof))
                return result
            }
            if (result is OperationResult.Ok) {
                val change = synchronized(monitor) {
                    if (attemptCurrentLocked(attempt)) replaceObservationLocked(ticket.input) else null
                }
                change?.let(::applyObservationChange)
            } else {
                markFailure(attempt, failureFor(result))
            }
            return result
        } catch (cancelled: CancellationException) {
            markCancelled(attempt)
            throw cancelled
        } catch (_: Exception) {
            val committed = synchronized(monitor) { attempt.pending.receipt != null }
            if (committed) {
                markCommittedRefreshFailure(attempt)
            } else {
                markFailure(attempt, BookmarkActionFailure(BookmarkActionFailureKind.FAILED,
                    "BOOKMARK_ACTION_FAILED", true, outcomeUnknown = synchronized(monitor) { attempt.invoked }))
            }
            return OperationResult.Failed(if (committed) "BOOKMARK_REFRESH_FAILED" else "BOOKMARK_ACTION_FAILED", true)
        }
    }

    private suspend fun execute(ticket: Ticket): OperationResult<Receipt> = when (ticket.intent) {
        BookmarkActionIntent.ENSURE_ADDED -> when (val result = services.ensureBookmark(
            ticket.context, ticket.input.token.documentId, ticket.target.locator, ticket.target.preview,
        )) {
            is OperationResult.Ok -> OperationResult.Ok(Receipt.Ensured(result.value), result.operationId)
            is OperationResult.Conflict -> result
            is OperationResult.Rejected -> result
            is OperationResult.Failed -> result
        }
        BookmarkActionIntent.TOGGLE -> when (val result = services.toggleBookmark(
            ticket.context, ticket.input.token.documentId, ticket.target.locator, ticket.target.preview,
        )) {
            is OperationResult.Ok -> OperationResult.Ok(Receipt.Toggled(result.value), result.operationId)
            is OperationResult.Conflict -> result
            is OperationResult.Rejected -> result
            is OperationResult.Failed -> result
        }
    }

    private fun validateReceipt(ticket: Ticket, result: OperationResult<Receipt>): OperationResult<Receipt> {
        if (result !is OperationResult.Ok) return result
        if (result.operationId != ticket.context.requestId) return OperationResult.Failed("BOOKMARK_RECEIPT_MISMATCH", true)
        val frozen: Receipt = when (val receipt = result.value) {
            is Receipt.Ensured -> {
                if (ticket.intent != BookmarkActionIntent.ENSURE_ADDED) return OperationResult.Failed("BOOKMARK_RECEIPT_MISMATCH", true)
                val bookmark = freezeBookmark(receipt.bookmark)
                if (!validReceiptBookmark(bookmark, ticket)) return OperationResult.Failed("BOOKMARK_RECEIPT_MISMATCH", true)
                Receipt.Ensured(bookmark)
            }
            is Receipt.Toggled -> {
                if (ticket.intent != BookmarkActionIntent.TOGGLE) return OperationResult.Failed("BOOKMARK_RECEIPT_MISMATCH", true)
                val toggle = when (val value = receipt.result) {
                    is BookmarkToggleResult.Added -> {
                        val bookmark = freezeBookmark(value.bookmark)
                        if (!validReceiptBookmark(bookmark, ticket)) return OperationResult.Failed("BOOKMARK_RECEIPT_MISMATCH", true)
                        BookmarkToggleResult.Added(bookmark)
                    }
                    is BookmarkToggleResult.Removed -> {
                        if (value.anchorHash != ticket.target.anchorHash) return OperationResult.Failed("BOOKMARK_RECEIPT_MISMATCH", true)
                        value.copy()
                    }
                }
                Receipt.Toggled(toggle)
            }
        }
        return OperationResult.Ok(frozen, result.operationId)
    }

    private fun validReceiptBookmark(bookmark: ReaderBookmarkView, ticket: Ticket): Boolean {
        val locator = bookmark.locator ?: return false
        // Existing anchors may retain a previous content revision. This validates the receipt only;
        // the current revision's visible match still comes exclusively from resolvePosition/Flow.
        return validIdentifier(bookmark.id) && bookmark.documentId == ticket.input.token.documentId &&
            bookmark.anchorHash == ticket.target.anchorHash &&
            ReaderLocatorValidator.validateForDocument(locator, ticket.input.token.documentId).isValid &&
            ReaderMutationHasher.bookmarkHash(locator) == ticket.target.anchorHash &&
            ReaderLocatorValidator.isWellFormedUtf16(bookmark.preview)
    }

    private fun refreshCommitted(pending: Pending): OperationResult<Unit> {
        val change = synchronized(monitor) {
            if (closed || !ownerJob.isActive) return rejected("BOOKMARK_CONTROLLER_CLOSED")
            if (currentInput != pending.ticket.input || pendingByInput[pending.ticket.input] !== pending)
                return rejected("BOOKMARK_POSITION_CHANGED")
            if (pending.active != null || mutableState.value.observation == BookmarkObservationPhase.LOADING)
                return rejected("BOOKMARK_ACTION_BUSY")
            if (mutableState.value.observation == BookmarkObservationPhase.INVALIDATED)
                return rejected("BOOKMARK_POSITION_INVALIDATED")
            replaceObservationLocked(pending.ticket.input)
        }
        applyObservationChange(change)
        return accepted()
    }

    private fun replaceObservationLocked(input: BookmarkPositionInput): ObservationChange {
        val old = currentObservation?.job
        val next = Observation(generation, input)
        currentObservation = next
        val pending = pendingByInput[input]
        mutableState.value = mutableState.value.copy(
            observation = BookmarkObservationPhase.LOADING,
            resolution = null,
            observationFailure = null,
            write = phaseFor(pending),
            pendingIntent = pending?.ticket?.intent,
            actionFailure = pending?.failure,
        )
        return ObservationChange(old, next)
    }

    private fun applyObservationChange(change: ObservationChange) {
        change.oldJob?.cancel()
        launchObservation(change.next)
    }

    private fun launchObservation(observation: Observation) {
        val job = scope.launch(start = CoroutineStart.LAZY) { observe(observation) }
        val admitted = synchronized(monitor) {
            if (!observationCurrentLocked(observation)) false else {
                observation.job = job
                true
            }
        }
        if (admitted) job.start() else job.cancel()
    }

    private suspend fun observe(observation: Observation) {
        var failureKind = BookmarkActionFailureKind.OBSERVATION
        try {
            // Returning to an identical frozen position must let its old attempt actually finish.
            // Awaiting happens outside the monitor and never infers completion from elapsed time.
            val waitFor = synchronized(monitor) { pendingByInput[observation.input]?.active?.completion }
            waitFor?.await()
            currentCoroutineContext().ensureActive()
            if (!observationCurrent(observation)) return
            val initialProof = prove(observation.input)
            if (!observationCurrent(observation)) return
            if (initialProof != Proof.VALID) {
                invalidateProof(observation.generation, observation.input, proofFailure(initialProof))
                return
            }
            // Only a subscription created after a verified commit can release its barrier.
            val confirmed = synchronized(monitor) {
                if (!observationCurrentLocked(observation)) return
                pendingByInput[observation.input]?.takeIf { it.receipt != null }.also { pending ->
                    if (pending != null) mutableState.value = mutableState.value.copy(
                        write = phaseFor(pending), actionFailure = null,
                    )
                }
            }
            val flow = services.observeBookmarks(checkNotNull(observation.input.identity.userId), observation.input.token.documentId)
            var emitted = false
            flow.collect { rows ->
                currentCoroutineContext().ensureActive()
                if (!observationCurrent(observation)) return@collect
                val beforeProof = prove(observation.input)
                if (!observationCurrent(observation)) return@collect
                if (beforeProof != Proof.VALID) {
                    invalidateProof(observation.generation, observation.input, proofFailure(beforeProof))
                    return@collect
                }
                val frozenRows = immutableList(rows.map(::freezeBookmark))
                if (frozenRows.any { it.documentId != observation.input.token.documentId })
                    throw IllegalArgumentException("BOOKMARK_SNAPSHOT_DOCUMENT_MISMATCH")
                failureKind = BookmarkActionFailureKind.RESOLUTION
                val resolution = freezeResolution(services.resolvePosition(
                    observation.input.token.documentId, observation.input.token.contentRevision,
                    observation.input.selection, observation.input.viewport, frozenRows,
                ), observation.input, frozenRows)
                failureKind = BookmarkActionFailureKind.OBSERVATION
                val afterProof = prove(observation.input)
                currentCoroutineContext().ensureActive()
                if (afterProof != Proof.VALID) {
                    invalidateProof(observation.generation, observation.input, proofFailure(afterProof))
                    return@collect
                }
                synchronized(monitor) {
                    if (!observationCurrentLocked(observation)) return@synchronized
                    emitted = true
                    var pending = pendingByInput[observation.input]
                    if (pending === confirmed && pending != null && pending.receipt != null && pending.active == null) {
                        pendingByInput.remove(observation.input)
                        pending = null
                    }
                    val candidates = if (resolution is BookmarkActionResolution.Ambiguous)
                        frozenRows.filter { it.id in resolution.bookmarkIds } else emptyList()
                    val chosen = candidates.singleOrNull { it.id == selectedBookmarkId }
                    val displayed = if (chosen != null && candidates.count { it.anchorHash == chosen.anchorHash } == 1) {
                        BookmarkActionResolution.Resolved(BookmarkActionResolution.Target(checkNotNull(chosen.locator),
                            chosen.anchorHash, bookmarkPreview(chosen.preview)), chosen)
                    } else { selectedBookmarkId = null; resolution }
                    // No requirement that a refreshed snapshot still contains the target bookmark.
                    mutableState.value = mutableState.value.copy(
                        observation = BookmarkObservationPhase.READY,
                        resolution = displayed,
                        candidates = immutableList(candidates),
                        observationFailure = null,
                        write = phaseFor(pending),
                        pendingIntent = pending?.ticket?.intent,
                        actionFailure = pending?.failure,
                    )
                }
            }
            if (!emitted && observationCurrent(observation)) {
                markObservationFailure(observation, BookmarkActionFailure(BookmarkActionFailureKind.OBSERVATION,
                    "BOOKMARK_OBSERVATION_EMPTY", true))
            }
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive) markObservationFailure(observation,
                BookmarkActionFailure(BookmarkActionFailureKind.OBSERVATION, "BOOKMARK_OBSERVATION_CANCELLED", true))
            throw cancelled
        } catch (_: Exception) {
            markObservationFailure(observation, BookmarkActionFailure(failureKind,
                if (failureKind == BookmarkActionFailureKind.RESOLUTION) "BOOKMARK_RESOLUTION_FAILED" else "BOOKMARK_OBSERVATION_FAILED", true))
        }
    }

    private fun freezeResolution(
        result: BookmarkActionResolution,
        input: BookmarkPositionInput,
        rows: List<ReaderBookmarkView>,
    ): BookmarkActionResolution = when (result) {
        is BookmarkActionResolution.Unavailable -> result.copy()
        is BookmarkActionResolution.Resolved -> {
            val target = freezeTarget(result.target)
            require(validTarget(target, input))
            val bookmark = result.bookmark?.let(::freezeBookmark)
            if (bookmark != null) {
                val locator = checkNotNull(bookmark.locator)
                // Validate provenance and the resolver's output, without selecting or sorting rows.
                require(rows.contains(bookmark) && validIdentifier(bookmark.id) &&
                    bookmark.documentId == input.token.documentId && bookmark.anchorHash == target.anchorHash &&
                    ReaderLocatorValidator.validateForDocument(locator, input.token.documentId, input.token.contentRevision).isValid &&
                    ReaderMutationHasher.bookmarkHash(locator) == target.anchorHash)
            }
            BookmarkActionResolution.Resolved(target, bookmark)
        }
        is BookmarkActionResolution.Ambiguous -> {
            val target = freezeTarget(result.target)
            require(validTarget(target, input))
            val ids = immutableList(result.bookmarkIds)
            require(ids.size >= 2 && ids.distinct().size == ids.size && ids.all { id ->
                val row = rows.singleOrNull { it.id == id }
                val locator = row?.locator
                validIdentifier(id) && row != null && locator != null && row.documentId == input.token.documentId &&
                    ReaderLocatorValidator.validateForDocument(locator, input.token.documentId, input.token.contentRevision).isValid &&
                    ReaderMutationHasher.bookmarkHash(locator) == row.anchorHash && ReaderLocatorValidator.isWellFormedUtf16(row.preview)
            })
            BookmarkActionResolution.Ambiguous(target, ids)
        }
    }

    private suspend fun prove(input: BookmarkPositionInput): Proof {
        currentCoroutineContext().ensureActive()
        if (services.identityNow() != input.identity) return Proof.IDENTITY_CHANGED
        currentCoroutineContext().ensureActive()
        val owned = services.ownsPosition(input)
        currentCoroutineContext().ensureActive()
        if (services.identityNow() != input.identity) return Proof.IDENTITY_CHANGED
        currentCoroutineContext().ensureActive()
        return if (owned) Proof.VALID else Proof.POSITION_CHANGED
    }

    private fun invalidateProof(expectedGeneration: Long, input: BookmarkPositionInput, failure: BookmarkActionFailure) {
        val old = synchronized(monitor) {
            if (!current(expectedGeneration, input)) return
            generation += 1L
            val job = currentObservation?.job
            currentObservation = null
            val pending = pendingByInput[input]
            mutableState.value = mutableState.value.copy(
                generation = generation,
                observation = BookmarkObservationPhase.INVALIDATED,
                resolution = null,
                observationFailure = failure,
                write = phaseFor(pending),
                pendingIntent = pending?.ticket?.intent,
                actionFailure = pending?.failure,
            )
            job
        }
        old?.cancel()
    }

    private fun markObservationFailure(observation: Observation, failure: BookmarkActionFailure) = synchronized(monitor) {
        if (observationCurrentLocked(observation)) mutableState.value = mutableState.value.copy(
            observation = BookmarkObservationPhase.FAILED, observationFailure = failure,
        )
    }

    private fun markFailure(attempt: Attempt, failure: BookmarkActionFailure) = synchronized(monitor) {
        if (attempt.pending.receipt != null || attempt.pending.latest !== attempt) return@synchronized
        attempt.pending.failure = failure
        if (current(attempt.generation, attempt.pending.ticket.input) &&
            pendingByInput[attempt.pending.ticket.input] === attempt.pending
        ) projectPendingLocked(attempt.pending)
    }

    private fun markCancelled(attempt: Attempt) {
        val committed = synchronized(monitor) { attempt.pending.receipt != null }
        if (committed) {
            markCommittedRefreshFailure(attempt)
        } else {
            markFailure(attempt, BookmarkActionFailure(BookmarkActionFailureKind.CANCELLED,
                "BOOKMARK_ACTION_CANCELLED", true, outcomeUnknown = synchronized(monitor) { attempt.invoked }))
        }
    }

    private fun markCommittedRefreshFailure(attempt: Attempt) {
        val old = synchronized(monitor) {
            if (!attemptCurrentLocked(attempt) || mutableState.value.observation == BookmarkObservationPhase.LOADING) return
            val job = currentObservation?.job
            currentObservation = null
            mutableState.value = mutableState.value.copy(
                write = phaseFor(attempt.pending), actionFailure = null,
                observation = BookmarkObservationPhase.FAILED,
                observationFailure = BookmarkActionFailure(BookmarkActionFailureKind.OBSERVATION, "BOOKMARK_REFRESH_FAILED", true),
            )
            job
        }
        old?.cancel()
    }

    private fun finishAttempt(attempt: Attempt) {
        synchronized(monitor) {
            if (attempt.pending.active === attempt) {
                attempt.pending.active = null
                // A lazy coroutine cancelled before entry never reaches runAttempt's catch clause.
                if (attempt.pending.receipt == null && attempt.pending.failure == null) {
                    val failure = BookmarkActionFailure(BookmarkActionFailureKind.CANCELLED,
                        "BOOKMARK_ACTION_CANCELLED", true, outcomeUnknown = attempt.invoked)
                    attempt.pending.failure = failure
                }
                // Publish the terminal phase only after the Job's real completion cleared active.
                // A new generation at the identical frozen input may be waiting for this attempt;
                // project that currently owned Pending, without touching any other position's UI.
                projectPendingLocked(attempt.pending)
            }
        }
        attempt.completion.complete(Unit)
    }

    private fun failIdAllocation(candidate: Candidate): OperationResult.Failed {
        synchronized(monitor) {
            if (current(candidate.generation, candidate.input) && pendingByInput[candidate.input] == null &&
                mutableState.value.write != BookmarkWritePhase.SAVING
            ) mutableState.value = mutableState.value.copy(
                write = BookmarkWritePhase.FAILED,
                actionFailure = BookmarkActionFailure(BookmarkActionFailureKind.FAILED, "BOOKMARK_REQUEST_ID_FAILED", true),
            )
        }
        return OperationResult.Failed("BOOKMARK_REQUEST_ID_FAILED", true)
    }

    private fun invalidateInvalidInput(): OperationResult.Rejected {
        val oldJobs = synchronized(monitor) {
            if (closed) return rejected("BOOKMARK_CONTROLLER_CLOSED")
            val jobs = listOfNotNull(currentObservation?.job, currentInput?.let { pendingByInput[it]?.active?.job })
            generation += 1L
            currentInput = null
            currentObservation = null
            mutableState.value = BookmarkActionState(generation = generation,
                observation = BookmarkObservationPhase.INVALIDATED, observationFailure = inputFailure())
            jobs
        }
        oldJobs.forEach { it.cancel() }
        return rejected("BOOKMARK_INVALID_INPUT")
    }

    private fun invalidateClosed() = synchronized(monitor) {
        if (closed) return@synchronized
        closed = true
        generation += 1L
        currentInput = null
        currentObservation = null
        pendingByInput.clear()
        mutableState.value = BookmarkActionState(generation = generation, observation = BookmarkObservationPhase.CLOSED)
    }

    private fun current(expectedGeneration: Long, input: BookmarkPositionInput): Boolean =
        !closed && ownerJob.isActive && generation == expectedGeneration && currentInput == input

    private fun attemptCurrentLocked(attempt: Attempt): Boolean =
        current(attempt.generation, attempt.pending.ticket.input) &&
            pendingByInput[attempt.pending.ticket.input] === attempt.pending && attempt.pending.active === attempt

    private fun attemptCurrent(attempt: Attempt): Boolean = synchronized(monitor) { attemptCurrentLocked(attempt) }
    private fun observationCurrentLocked(observation: Observation): Boolean =
        current(observation.generation, observation.input) && currentObservation === observation

    private fun observationCurrent(observation: Observation): Boolean = synchronized(monitor) { observationCurrentLocked(observation) }

    private fun projectPendingLocked(pending: Pending) {
        val input = pending.ticket.input
        if (closed || !ownerJob.isActive || currentInput != input ||
            mutableState.value.generation != generation || pendingByInput[input] !== pending
        ) return
        mutableState.value = mutableState.value.copy(
            write = phaseFor(pending),
            pendingIntent = pending.ticket.intent,
            actionFailure = if (pending.receipt == null) pending.failure else null,
        )
    }

    private fun phaseFor(pending: Pending?): BookmarkWritePhase = when {
        pending == null -> BookmarkWritePhase.IDLE
        // cancel() requests cancellation; only finishAttempt proves cleanup has actually ended.
        // Receipt/failure facts remain recorded while every action and retry stays busy.
        pending.active != null -> BookmarkWritePhase.SAVING
        pending.receipt != null -> BookmarkWritePhase.SYNCHRONIZING
        pending.failure?.kind == BookmarkActionFailureKind.CANCELLED -> BookmarkWritePhase.CANCELLED
        pending.failure != null -> BookmarkWritePhase.FAILED
        else -> BookmarkWritePhase.FAILED
    }

    private fun failureFor(result: OperationResult<*>): BookmarkActionFailure = when (result) {
        is OperationResult.Conflict -> BookmarkActionFailure(BookmarkActionFailureKind.CONFLICT,
            "BOOKMARK_REVISION_CONFLICT", result.recoverable, currentRevision = result.currentRevision)
        is OperationResult.Rejected -> BookmarkActionFailure(BookmarkActionFailureKind.REJECTED, result.code, true)
        is OperationResult.Failed -> BookmarkActionFailure(
            if (result.code == "BOOKMARK_RECEIPT_MISMATCH") BookmarkActionFailureKind.RECEIPT else BookmarkActionFailureKind.FAILED,
            result.code, result.retryable, outcomeUnknown = true,
        )
        is OperationResult.Ok -> error("Expected an unsuccessful bookmark result")
    }

    private fun proofFailure(proof: Proof): BookmarkActionFailure = when (proof) {
        // INVALIDATED disables writes now; a later proven return to the identical input can replay.
        Proof.IDENTITY_CHANGED -> BookmarkActionFailure(BookmarkActionFailureKind.IDENTITY, "BOOKMARK_SESSION_CHANGED", true)
        Proof.POSITION_CHANGED -> BookmarkActionFailure(BookmarkActionFailureKind.OWNERSHIP, "BOOKMARK_POSITION_CHANGED", true)
        Proof.VALID -> error("Expected invalid ownership proof")
    }

    private fun inputFailure() = BookmarkActionFailure(BookmarkActionFailureKind.INPUT, "BOOKMARK_INVALID_INPUT", false)
    private fun rejected(code: String) = OperationResult.Rejected(code)
    private fun accepted() = OperationResult.Ok(Unit, "bookmark-controller")
    private fun failureResult(result: OperationResult<*>): OperationResult<Nothing> = when (result) {
        is OperationResult.Conflict -> result
        is OperationResult.Rejected -> result
        is OperationResult.Failed -> result
        is OperationResult.Ok -> error("Expected an unsuccessful bookmark result")
    }

    private fun validInput(input: BookmarkPositionInput): Boolean {
        val token = input.token
        if (input.identity.userId == null || input.identity.userId <= 0L || input.identity.epoch < 0L ||
            token.userId != input.identity.userId || token.sessionEpoch != input.identity.epoch || token.layoutGeneration < 0L ||
            !validIdentifier(token.documentId) || !validIdentifier(token.contentRevision) ||
            !validIdentifier(token.sessionId) || !validIdentifier(input.positionId)
        ) return false
        if (input.viewport?.let { !ReaderLocatorValidator.validateForDocument(it, token.documentId, token.contentRevision).isValid } == true)
            return false
        if (input.visibleRanges.size > 128 || input.visibleRanges.any {
            !ReaderLocatorValidator.validateForDocument(it, token.documentId, token.contentRevision).isValid }) return false
        return input.selection?.all {
            ReaderLocatorValidator.validateForDocument(it.locator, token.documentId, token.contentRevision).isValid &&
                ReaderLocatorValidator.isWellFormedUtf16(it.quote)
        } != false
    }

    private fun validTarget(target: BookmarkActionResolution.Target, input: BookmarkPositionInput): Boolean =
        ReaderLocatorValidator.validateForDocument(target.locator, input.token.documentId, input.token.contentRevision).isValid &&
            target.anchorHash == ReaderMutationHasher.bookmarkHash(target.locator) &&
            target.preview.length <= 160 && ReaderLocatorValidator.isWellFormedUtf16(target.preview)

    private fun validIdentifier(value: String, maxLength: Int = 512): Boolean =
        value.isNotBlank() && value.length <= maxLength && value.none(Char::isISOControl) &&
            ReaderLocatorValidator.isWellFormedUtf16(value)

    private fun freezeInput(input: BookmarkPositionInput): BookmarkPositionInput = input.copy(
        identity = input.identity.copy(), token = input.token.copy(),
        selection = input.selection?.let { fragments -> immutableList(fragments.map {
            ReaderSelectionFragment(freezeLocator(it.locator), it.quote)
        }) },
        viewport = input.viewport?.let(::freezeLocator),
        visibleRanges = immutableList(input.visibleRanges.map(::freezeLocator)),
    )

    private fun freezeTarget(target: BookmarkActionResolution.Target) = target.copy(locator = freezeLocator(target.locator))
    private fun freezeBookmark(bookmark: ReaderBookmarkView) = bookmark.copy(locator = bookmark.locator?.let(::freezeLocator))
    private fun freezeLocator(locator: ReaderLocator): ReaderLocator = when (locator) {
        is ReaderLocator.Reflow -> locator.copy()
        is ReaderLocator.Fixed -> locator.copy(pageBox = locator.pageBox.copy(), rects = immutableList(locator.rects.map { it.copy() }))
        is ReaderLocator.Note -> locator.copy()
    }

    private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
