package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.DocumentSnapshot
import com.example.myapplication.domain.reader.LocatorValidation
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import com.example.myapplication.domain.reader.ReaderSelectionFragment
import com.example.myapplication.domain.reader.ReaderSessionToken
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class BookmarkHostSelectionPhase { NONE, DRAGGING, STABLE, EDITING, UNAVAILABLE }

internal data class BookmarkHostSelection(
    val phase: BookmarkHostSelectionPhase,
    val bindingId: String? = null,
    val eventSequence: Long = -1L,
    val selectionId: String? = null,
    val fragments: List<ReaderSelectionFragment> = emptyList(),
    val nativeRangePresent: Boolean = false,
    val sourceAvailable: Boolean = false,
)

/** occurrenceId must identify a real native viewport occurrence, including A -> B -> A moves. */
internal data class BookmarkHostViewport(val occurrenceId: String, val locator: ReaderLocator, val visibleRanges: List<ReaderLocator> = emptyList())

internal data class BookmarkHostSnapshot(
    val token: ReaderSessionToken,
    val originalSnapshot: DocumentSnapshot,
    val phase: ReaderTurnPhase,
    val ready: Boolean,
    val closed: Boolean,
    val cleanupFailure: String?,
    val readOnly: Boolean,
    val selection: BookmarkHostSelection,
    val viewport: BookmarkHostViewport?,
)

internal data class BookmarkHostPosition(
    val identity: SessionIdentity,
    val token: ReaderSessionToken,
    val positionId: String,
    val originalSnapshot: DocumentSnapshot,
    val writable: Boolean,
    val selection: BookmarkHostSelection?,
    val viewport: BookmarkHostViewport?,
)

internal enum class BookmarkHostInputPhase { EMPTY, REFRESHING, READY, INVALIDATED, FAILED, CLOSED }

internal data class BookmarkHostInputState(
    val generation: Long = 0L,
    val phase: BookmarkHostInputPhase = BookmarkHostInputPhase.EMPTY,
    val position: BookmarkHostPosition? = null,
    val failureCode: String? = null,
    val retryable: Boolean = false,
)

/**
 * Every callback is mandatory. This binding holds no outer monitor, including while StateFlow
 * notifies collectors; callbacks, freezing and validation never run in a binding critical section.
 *
 * identityNow must read the real current identity on each call (SessionManager.identity.first()).
 * hostNow must read the committed runtime, current selection and native owner, including the
 * original prepared DocumentSnapshot; an asynchronous UI/turnState mirror is insufficient.
 * proveLive must query the full current native occurrence and capture token/binding/sequence/ID,
 * Range and source, or positively prove NONE and the supplied viewport occurrence. StateFlow
 * equality, port.isActive and validateSource alone cannot provide this proof. Missing native
 * read-only suppliers must return unavailable/false, never manufacture IDs or default to true.
 * openCurrentDocument must perform real DocumentAccess.open access checks for the requested
 * document and revision. validateCanonical must check the current canonical source for all
 * supplied fragments or the viewport locator, without replacing the original source snapshot.
 */
internal class BookmarkHostInputServices(
    val identityNow: suspend () -> SessionIdentity,
    val hostNow: suspend () -> BookmarkHostSnapshot?,
    val proveLive: suspend (BookmarkHostPosition) -> Boolean,
    val openCurrentDocument: suspend (AccessContext, String, String) -> OperationResult<DocumentSnapshot>,
    val validateCanonical: (ReaderSessionToken, List<ReaderSelectionFragment>?, ReaderLocator?) -> LocatorValidation,
)

internal interface BookmarkHostInputPort : AutoCloseable {
    val state: StateFlow<BookmarkHostInputState>
    suspend fun refresh(): OperationResult<BookmarkHostPosition>
    suspend fun ownsPosition(expected: BookmarkHostPosition): Boolean
    fun invalidate(reason: String)
    override fun close()
}

/**
 * Freezes input and verifies read-only ownership; it never mutates or navigates the reader.
 *
 * READY is an issued expected value, not a cached live proof. Call ownsPosition with the exact
 * original object from state.position or refresh's Ok before submission; equal copies and
 * reconstructed positions are not adapter-owned. The submit adapter must compare its complete
 * action input first, then map it back to the current original object for this ownership check.
 * MutableStateFlow is the sole state authority. All transitions use CAS on immutable states;
 * synchronous collector reentry can retire an update before CAS returns. READY publication
 * therefore checks the current generation, phase and issued reference again after notification.
 * No scope, job or timer is created, and close never cancels the caller's scope.
 */
internal class BookmarkHostInputBinding(
    private val services: BookmarkHostInputServices,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) : BookmarkHostInputPort {
    private val mutableState = MutableStateFlow(BookmarkHostInputState())
    override val state: StateFlow<BookmarkHostInputState> = mutableState.asStateFlow()

    override suspend fun refresh(): OperationResult<BookmarkHostPosition> {
        val generation = beginRefresh() ?: return OperationResult.Rejected(CLOSED)
        return try {
            val candidate = readOwner(generation, null)
            validateCanonical(candidate)
            checkCurrent(generation, null)
            verifyOwner(generation, null, candidate)
            proveLive(generation, null, candidate)
            val requestId = openAndVerify(generation, null, candidate)
            // Access IO can change both identity and native ownership. Recheck the actual source,
            // then native proof, then real identity/host once more after that suspending proof.
            validateCanonical(candidate)
            checkCurrent(generation, null)
            proveLive(generation, null, candidate)
            publishReady(generation, candidate)
            OperationResult.Ok(candidate, requestId)
        } catch (cancelled: CancellationException) {
            failRefresh(generation, OperationResult.Rejected(CANCELLED), BookmarkHostInputPhase.INVALIDATED)
            throw cancelled
        } catch (failure: InputFailure) {
            failRefresh(generation, failure.result)
            failure.result
        } catch (_: Exception) {
            val failure = OperationResult.Failed(EXCEPTION, retryable = true)
            failRefresh(generation, failure)
            failure
        }
    }

    override suspend fun ownsPosition(expected: BookmarkHostPosition): Boolean {
        // Reference ownership prevents foreign writable/source values from becoming expectations.
        val generation = mutableState.value.takeIf {
            it.phase == BookmarkHostInputPhase.READY && it.position === expected
        }?.generation ?: return false
        return try {
            val candidate = freezePosition(expected)
            validatePosition(candidate)
            checkCurrent(generation, expected)
            verifyOwner(generation, expected, candidate)
            validateCanonical(candidate)
            checkCurrent(generation, expected)
            verifyOwner(generation, expected, candidate)
            proveLive(generation, expected, candidate)
            openAndVerify(generation, expected, candidate)
            validateCanonical(candidate)
            checkCurrent(generation, expected)
            proveLive(generation, expected, candidate)
            isCurrent(generation, expected)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: InputFailure) {
            retireOwned(generation, expected, failure.result.failureCode())
            false
        } catch (_: Exception) {
            retireOwned(generation, expected, EXCEPTION)
            false
        }
    }

    override fun invalidate(reason: String) {
        val code = reason.takeIf { validId(it) } ?: INVALIDATED
        while (true) {
            val previous = mutableState.value
            if (previous.phase == BookmarkHostInputPhase.CLOSED) return
            val next = BookmarkHostInputState(
                generation = nextGeneration(previous.generation),
                phase = if (previous.generation == Long.MAX_VALUE) BookmarkHostInputPhase.CLOSED
                else BookmarkHostInputPhase.INVALIDATED,
                failureCode = code,
            )
            if (mutableState.compareAndSet(previous, next)) return
        }
    }

    override fun close() {
        while (true) {
            val previous = mutableState.value
            if (previous.phase == BookmarkHostInputPhase.CLOSED) return
            val next = BookmarkHostInputState(
                generation = nextGeneration(previous.generation),
                phase = BookmarkHostInputPhase.CLOSED,
                failureCode = CLOSED,
            )
            if (mutableState.compareAndSet(previous, next)) return
        }
    }

    private fun beginRefresh(): Long? {
        while (true) {
            val previous = mutableState.value
            if (previous.phase == BookmarkHostInputPhase.CLOSED) return null
            if (previous.generation == Long.MAX_VALUE) {
                val closed = BookmarkHostInputState(
                    generation = previous.generation,
                    phase = BookmarkHostInputPhase.CLOSED,
                    failureCode = GENERATION_EXHAUSTED,
                )
                if (mutableState.compareAndSet(previous, closed)) return null
            } else {
                val generation = nextGeneration(previous.generation)
                // Retire the visible prior expectation before the first service call or suspension.
                val refreshing = BookmarkHostInputState(generation, BookmarkHostInputPhase.REFRESHING)
                if (mutableState.compareAndSet(previous, refreshing)) return generation
            }
        }
    }

    private fun isCurrent(generation: Long, issued: BookmarkHostPosition?): Boolean =
        matches(mutableState.value, generation, issued)

    private fun matches(
        current: BookmarkHostInputState,
        generation: Long,
        issued: BookmarkHostPosition?,
    ): Boolean {
        return current.generation == generation && if (issued == null) {
            current.phase == BookmarkHostInputPhase.REFRESHING
        } else {
            current.phase == BookmarkHostInputPhase.READY && current.position === issued
        }
    }

    private fun checkCurrent(generation: Long, issued: BookmarkHostPosition?) {
        if (!isCurrent(generation, issued)) reject(SUPERSEDED)
    }

    private fun publishReady(generation: Long, candidate: BookmarkHostPosition) {
        val ready = BookmarkHostInputState(
            generation = generation,
            phase = BookmarkHostInputPhase.READY,
            position = candidate,
        )
        while (true) {
            val previous = mutableState.value
            if (!matches(previous, generation, null)) reject(SUPERSEDED)
            if (mutableState.compareAndSet(previous, ready)) {
                // An inline collector can close/invalidate/start a new refresh before CAS returns.
                // Only the still-current issued object may escape as a successful refresh.
                checkCurrent(generation, candidate)
                return
            }
        }
    }

    /** Bracket a host read with fresh identities, checking the local ticket after each suspension. */
    private suspend fun readOwner(generation: Long, issued: BookmarkHostPosition?): BookmarkHostPosition {
        checkCurrent(generation, issued)
        val before = services.identityNow().copy()
        checkCurrent(generation, issued)
        validateIdentity(before)
        val suppliedHost = services.hostNow()
        checkCurrent(generation, issued)
        val host = suppliedHost?.let(::freezeHost)
        if (host == null) reject(HOST_UNAVAILABLE)
        val after = services.identityNow().copy()
        checkCurrent(generation, issued)
        validateIdentity(after)
        if (before != after) reject(OWNER_CHANGED)
        return project(after, host)
    }

    private suspend fun verifyOwner(
        generation: Long,
        issued: BookmarkHostPosition?,
        candidate: BookmarkHostPosition,
    ) {
        if (readOwner(generation, issued) != candidate) reject(OWNER_CHANGED)
        checkCurrent(generation, issued)
    }

    private fun validateCanonical(candidate: BookmarkHostPosition) {
        val isolated = freezePosition(candidate)
        if (!services.validateCanonical(
                isolated.token, isolated.selection?.fragments, isolated.viewport?.locator,
            ).isValid
        ) reject(CANONICAL_INVALID)
    }

    private suspend fun proveLive(
        generation: Long,
        issued: BookmarkHostPosition?,
        candidate: BookmarkHostPosition,
    ) {
        checkCurrent(generation, issued)
        val live = services.proveLive(freezePosition(candidate))
        checkCurrent(generation, issued)
        if (!live) reject(LIVE_UNAVAILABLE)
        // A successful suspending native query cannot bypass its final real owner check.
        verifyOwner(generation, issued, candidate)
    }

    private suspend fun openAndVerify(
        generation: Long,
        issued: BookmarkHostPosition?,
        candidate: BookmarkHostPosition,
    ): String {
        checkCurrent(generation, issued)
        val requestId = newRequestId()
        if (!validId(requestId, MAX_REQUEST_ID_UNITS)) reject(REQUEST_ID_INVALID)
        checkCurrent(generation, issued)
        val context = AccessContext(candidate.token.userId, candidate.identity.epoch, requestId)
        val result = services.openCurrentDocument(
            context, candidate.token.documentId, candidate.token.contentRevision,
        )
        checkCurrent(generation, issued)
        val currentSource = when (result) {
            is OperationResult.Ok -> freezeSnapshot(result.value)
            is OperationResult.Rejected -> throw InputFailure(result)
            is OperationResult.Conflict -> throw InputFailure(result)
            is OperationResult.Failed -> throw InputFailure(result)
        }
        validateSnapshot(currentSource, candidate.identity, candidate.token)
        val original = candidate.originalSnapshot
        if (currentSource.documentId != original.documentId ||
            currentSource.contentRevision != original.contentRevision ||
            currentSource.ownerUserId != original.ownerUserId ||
            currentSource.accessEpoch != original.accessEpoch ||
            currentSource.extractorVersion != original.extractorVersion
        ) reject(SOURCE_CHANGED)
        // Reopening only validates access/source. It never replaces the prepared snapshot.
        verifyOwner(generation, issued, candidate)
        return requestId
    }

    private fun failRefresh(
        generation: Long,
        result: OperationResult<Nothing>,
        phase: BookmarkHostInputPhase = BookmarkHostInputPhase.FAILED,
    ) {
        val code = result.failureCode()
        val retryable = when (result) {
            is OperationResult.Conflict -> result.recoverable
            is OperationResult.Failed -> result.retryable
            else -> false
        }
        val failed = BookmarkHostInputState(generation, phase, failureCode = code, retryable = retryable)
        while (true) {
            val previous = mutableState.value
            if (!matches(previous, generation, null)) return
            if (mutableState.compareAndSet(previous, failed)) return
        }
    }

    private fun retireOwned(generation: Long, issued: BookmarkHostPosition, code: String) {
        val retired = BookmarkHostInputState(
            generation = nextGeneration(generation),
            phase = if (generation == Long.MAX_VALUE) BookmarkHostInputPhase.CLOSED
            else BookmarkHostInputPhase.INVALIDATED,
            failureCode = code,
        )
        while (true) {
            val previous = mutableState.value
            if (!matches(previous, generation, issued)) return
            if (mutableState.compareAndSet(previous, retired)) return
        }
    }

    private fun project(identity: SessionIdentity, host: BookmarkHostSnapshot): BookmarkHostPosition {
        validateIdentity(identity)
        validateToken(host.token, identity)
        validateSnapshot(host.originalSnapshot, identity, host.token)
        if (!host.ready || host.closed || host.cleanupFailure != null || host.phase != ReaderTurnPhase.IDLE) {
            reject(HOST_UNAVAILABLE)
        }
        val selection: BookmarkHostSelection?
        val viewport: BookmarkHostViewport?
        val positionId: String
        when (host.selection.phase) {
            BookmarkHostSelectionPhase.NONE -> {
                val none = host.selection
                if (none.bindingId != null || none.selectionId != null || none.eventSequence != -1L ||
                    none.fragments.isNotEmpty() || none.nativeRangePresent
                ) reject(SELECTION_INVALID)
                selection = null
                viewport = host.viewport ?: reject(VIEWPORT_UNAVAILABLE)
                if (!validId(viewport.occurrenceId)) reject(VIEWPORT_UNAVAILABLE)
                validateLocator(viewport.locator, host.token)
                if (viewport.visibleRanges.size > 128) reject(VIEWPORT_UNAVAILABLE)
                viewport.visibleRanges.forEach { validateLocator(it, host.token) }
                positionId = encodePosition("viewport", listOf(viewport.occurrenceId))
            }
            BookmarkHostSelectionPhase.STABLE -> {
                selection = host.selection
                validateSelection(selection, host.token)
                viewport = null
                positionId = encodePosition(
                    "selection",
                    listOf(selection.bindingId!!, selection.eventSequence.toString(), selection.selectionId!!),
                )
            }
            BookmarkHostSelectionPhase.DRAGGING,
            BookmarkHostSelectionPhase.EDITING,
            BookmarkHostSelectionPhase.UNAVAILABLE -> reject(SELECTION_UNAVAILABLE)
        }
        return BookmarkHostPosition(
            identity = identity.copy(),
            token = host.token.copy(),
            positionId = positionId,
            originalSnapshot = host.originalSnapshot,
            writable = !host.readOnly,
            selection = selection,
            viewport = viewport,
        )
    }

    private fun validatePosition(position: BookmarkHostPosition) {
        if (!validId(position.positionId) || (position.selection == null) == (position.viewport == null)) {
            reject(POSITION_INVALID)
        }
        val projected = project(
            position.identity,
            BookmarkHostSnapshot(
                token = position.token,
                originalSnapshot = position.originalSnapshot,
                phase = ReaderTurnPhase.IDLE,
                ready = true,
                closed = false,
                cleanupFailure = null,
                readOnly = !position.writable,
                selection = position.selection ?: BookmarkHostSelection(BookmarkHostSelectionPhase.NONE),
                viewport = position.viewport,
            ),
        )
        if (projected != position) reject(POSITION_INVALID)
    }

    private fun validateIdentity(identity: SessionIdentity) {
        if (identity.userId == null || identity.userId <= 0L || identity.epoch < 0L) reject(IDENTITY_INVALID)
    }

    private fun validateToken(token: ReaderSessionToken, identity: SessionIdentity) {
        if (token.userId <= 0L || token.sessionEpoch < 0L || token.layoutGeneration < 0L ||
            token.userId != identity.userId || token.sessionEpoch != identity.epoch ||
            !validId(token.documentId) || !validId(token.contentRevision) || !validId(token.sessionId)
        ) reject(TOKEN_INVALID)
    }

    private fun validateSnapshot(snapshot: DocumentSnapshot, identity: SessionIdentity, token: ReaderSessionToken) {
        if (!validId(snapshot.snapshotId) || !validId(snapshot.documentId) ||
            !validId(snapshot.contentRevision) || !validId(snapshot.extractorVersion) ||
            snapshot.accessEpoch < 0L || snapshot.metadataRevision < 0L ||
            snapshot.documentId != token.documentId || snapshot.contentRevision != token.contentRevision ||
            (snapshot.ownerUserId != null && snapshot.ownerUserId != identity.userId) ||
            snapshot.sections.size > 64 || snapshot.sectionsNextCursor?.let { !validId(it) } == true
        ) reject(SOURCE_INVALID)
        snapshot.sections.forEach { section ->
            if (!validId(section.id) || !validId(section.documentId) || !validId(section.contentRevision) ||
                !validId(section.resourceHref) || !ReaderLocatorValidator.isWellFormedUtf16(section.title) ||
                section.documentId != snapshot.documentId || section.contentRevision != snapshot.contentRevision ||
                section.orderIndex < 0 || section.charCount < 0 ||
                section.pageStart?.let { it < 0 } == true || section.pageEnd?.let { it < 0 } == true ||
                (section.pageStart != null && section.pageEnd != null && section.pageEnd < section.pageStart)
            ) reject(SOURCE_INVALID)
        }
    }

    private fun validateSelection(selection: BookmarkHostSelection, token: ReaderSessionToken) {
        if (selection.phase != BookmarkHostSelectionPhase.STABLE ||
            selection.bindingId?.let { validId(it) } != true || selection.selectionId?.let { validId(it) } != true ||
            selection.eventSequence < 0L || !selection.nativeRangePresent || !selection.sourceAvailable ||
            selection.fragments.isEmpty()
        ) reject(SELECTION_INVALID)
        var quoteUnits = 0
        selection.fragments.forEach { fragment ->
            validateLocator(fragment.locator, token)
            if (fragment.quote.isBlank() || !ReaderLocatorValidator.isWellFormedUtf16(fragment.quote) ||
                fragment.quote.length > MAX_QUOTE_UNITS - quoteUnits
            ) reject(SELECTION_INVALID)
            quoteUnits += fragment.quote.length
            when (val locator = fragment.locator) {
                is ReaderLocator.Reflow -> {
                    val range = locator.canonicalEnd.toLong() - locator.canonicalStart.toLong()
                    if (range <= 0L || range != fragment.quote.length.toLong() || locator.exactQuote != fragment.quote) {
                        reject(SELECTION_INVALID)
                    }
                }
                is ReaderLocator.Fixed -> {
                    if (locator.rects.isEmpty() || (locator.textQuote != null && locator.textQuote != fragment.quote)) {
                        reject(SELECTION_INVALID)
                    }
                }
                is ReaderLocator.Note -> {
                    val start = locator.canonicalStart
                    val end = locator.canonicalEnd
                    if (start != null && end != null &&
                        (end.toLong() - start.toLong() != fragment.quote.length.toLong())
                    ) reject(SELECTION_INVALID)
                }
            }
        }
    }

    private fun validateLocator(locator: ReaderLocator, token: ReaderSessionToken) {
        if (!ReaderLocatorValidator.validateForDocument(locator, token.documentId, token.contentRevision).isValid ||
            !validId(locator.documentId) || !validId(locator.sectionId) || !validId(locator.contentRevision)
        ) reject(LOCATOR_INVALID)
        when (locator) {
            is ReaderLocator.Reflow -> if (!validId(locator.nativeLocator) || !validId(locator.blockId) ||
                !ReaderLocatorValidator.isWellFormedUtf16(locator.exactQuote) ||
                locator.prefix?.let { !ReaderLocatorValidator.isWellFormedUtf16(it) } == true ||
                locator.suffix?.let { !ReaderLocatorValidator.isWellFormedUtf16(it) } == true
            ) reject(LOCATOR_INVALID)
            is ReaderLocator.Fixed -> if (locator.textQuote?.let {
                    !ReaderLocatorValidator.isWellFormedUtf16(it)
                } == true
            ) reject(LOCATOR_INVALID)
            is ReaderLocator.Note -> if (!validId(locator.notebookId) ||
                locator.blockId?.let { !validId(it) } == true
            ) reject(LOCATOR_INVALID)
        }
    }

    /** UTF-16 unit lengths make embedded separators unambiguous; no ID is truncated or hashed. */
    private fun encodePosition(kind: String, components: List<String>): String {
        val encoded = buildString {
            append(kind).append(':')
            components.forEach { append(it.length).append(':').append(it) }
        }
        if (!validId(encoded)) reject(POSITION_INVALID)
        return encoded
    }

    private fun validId(value: String, maxUnits: Int = MAX_ID_UNITS): Boolean =
        value.isNotBlank() && value.length <= maxUnits && !value.any(Char::isISOControl) &&
            ReaderLocatorValidator.isWellFormedUtf16(value)

    private fun freezeHost(host: BookmarkHostSnapshot): BookmarkHostSnapshot = host.copy(
        token = host.token.copy(),
        originalSnapshot = freezeSnapshot(host.originalSnapshot),
        selection = freezeSelection(host.selection),
        viewport = host.viewport?.let(::freezeViewport),
    )

    private fun freezePosition(position: BookmarkHostPosition): BookmarkHostPosition = position.copy(
        identity = position.identity.copy(),
        token = position.token.copy(),
        originalSnapshot = freezeSnapshot(position.originalSnapshot),
        selection = position.selection?.let(::freezeSelection),
        viewport = position.viewport?.let(::freezeViewport),
    )

    private fun freezeSnapshot(snapshot: DocumentSnapshot): DocumentSnapshot = snapshot.copy(
        sections = immutableList(snapshot.sections.map { it.copy() }),
    )

    private fun freezeSelection(selection: BookmarkHostSelection): BookmarkHostSelection = selection.copy(
        fragments = immutableList(selection.fragments.map { it.copy(locator = freezeLocator(it.locator)) }),
    )

    private fun freezeViewport(viewport: BookmarkHostViewport): BookmarkHostViewport =
        viewport.copy(locator = freezeLocator(viewport.locator), visibleRanges = immutableList(viewport.visibleRanges.map(::freezeLocator)))

    private fun freezeLocator(locator: ReaderLocator): ReaderLocator = when (locator) {
        is ReaderLocator.Reflow -> locator.copy()
        is ReaderLocator.Fixed -> locator.copy(
            pageBox = locator.pageBox.copy(),
            rects = immutableList(locator.rects.map { it.copy() }),
        )
        is ReaderLocator.Note -> locator.copy()
    }

    private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    private fun nextGeneration(generation: Long): Long = if (generation == Long.MAX_VALUE) generation else generation + 1L
    private fun reject(code: String): Nothing = throw InputFailure(OperationResult.Rejected(code))

    private fun OperationResult<Nothing>.failureCode(): String = when (this) {
        is OperationResult.Rejected -> code
        is OperationResult.Conflict -> ACCESS_CONFLICT
        is OperationResult.Failed -> code
        is OperationResult.Ok -> EXCEPTION
    }

    private class InputFailure(val result: OperationResult<Nothing>) : Exception()

    private companion object {
        const val MAX_ID_UNITS = 512
        const val MAX_REQUEST_ID_UNITS = 128
        const val MAX_QUOTE_UNITS = 8192
        const val CLOSED = "BOOKMARK_HOST_INPUT_CLOSED"
        const val CANCELLED = "BOOKMARK_HOST_INPUT_CANCELLED"
        const val INVALIDATED = "BOOKMARK_HOST_INPUT_INVALIDATED"
        const val SUPERSEDED = "BOOKMARK_HOST_INPUT_SUPERSEDED"
        const val GENERATION_EXHAUSTED = "BOOKMARK_HOST_INPUT_GENERATION_EXHAUSTED"
        const val EXCEPTION = "BOOKMARK_HOST_INPUT_EXCEPTION"
        const val HOST_UNAVAILABLE = "BOOKMARK_HOST_UNAVAILABLE"
        const val OWNER_CHANGED = "BOOKMARK_HOST_OWNER_CHANGED"
        const val IDENTITY_INVALID = "BOOKMARK_HOST_IDENTITY_INVALID"
        const val TOKEN_INVALID = "BOOKMARK_HOST_TOKEN_INVALID"
        const val SOURCE_INVALID = "BOOKMARK_HOST_SOURCE_INVALID"
        const val SOURCE_CHANGED = "BOOKMARK_HOST_SOURCE_CHANGED"
        const val SELECTION_INVALID = "BOOKMARK_HOST_SELECTION_INVALID"
        const val SELECTION_UNAVAILABLE = "BOOKMARK_HOST_SELECTION_UNAVAILABLE"
        const val VIEWPORT_UNAVAILABLE = "BOOKMARK_HOST_VIEWPORT_UNAVAILABLE"
        const val LOCATOR_INVALID = "BOOKMARK_HOST_LOCATOR_INVALID"
        const val CANONICAL_INVALID = "BOOKMARK_HOST_CANONICAL_INVALID"
        const val LIVE_UNAVAILABLE = "BOOKMARK_HOST_LIVE_PROOF_UNAVAILABLE"
        const val POSITION_INVALID = "BOOKMARK_HOST_POSITION_INVALID"
        const val REQUEST_ID_INVALID = "BOOKMARK_HOST_REQUEST_ID_INVALID"
        const val ACCESS_CONFLICT = "BOOKMARK_HOST_DOCUMENT_ACCESS_CONFLICT"
    }
}
