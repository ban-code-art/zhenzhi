package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.notes.*
import com.example.myapplication.domain.reader.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AnnotationCreationOperation(
    val context: AccessContext,
    val locator: ReaderLocator,
    val quote: String,
    val kind: AnnotationKind,
    val style: AnnotationStyle,
    val noteText: String?,
    val targets: List<AnnotationTargetInput>,
    val guard: AnnotationWriteGuard?,
)
data class AnnotationCreationState(
    val draft: AnnotationCreationOperation? = null,
    val pending: AnnotationCreationOperation? = null,
    val busy: Boolean = false,
    val failure: OperationResult<*>? = null,
    val committed: ReaderAnnotationView? = null,
    val receiptUncertain: Boolean = false,
    val showEditor: Boolean = true,
) {
    val outcomeUnknown: Boolean get() = receiptUncertain ||
        (pending != null && failure !is OperationResult.Rejected && failure !is OperationResult.Conflict)
    val dirty: Boolean get() = draft != null && committed == null
}

/** Owns one immutable create request until its exact receipt is known. */
class AnnotationCreationController(
    private val create: suspend (AnnotationCreationOperation) -> OperationResult<ReaderAnnotationView>,
    private val newRequestId: () -> String,
) {
    private val mutable = MutableStateFlow(AnnotationCreationState())
    val state: StateFlow<AnnotationCreationState> = mutable
    private val lock = Any()
    private var generation = 0L

    fun open(context: AccessContext, locator: ReaderLocator, quote: String,
        kind: AnnotationKind = AnnotationKind.HIGHLIGHT, style: AnnotationStyle = AnnotationStyle(),
        noteText: String? = null, targets: List<AnnotationTargetInput> = emptyList(),
        guard: AnnotationWriteGuard? = null,
        showEditor: Boolean = true): Boolean = synchronized(lock) {
        if (mutable.value.draft != null || kind == AnnotationKind.INK || !AnnotationMutationCodec.validNote(noteText) ||
            !ReaderLocatorValidator.validate(locator).isValid || quote.isBlank()) return false
        generation++
        mutable.value = AnnotationCreationState(draft = AnnotationCreationOperation(context.copy(), AnnotationTargetValidation.freezeLocator(locator), quote, kind,
            style, noteText, AnnotationTargetValidation.freezeTargets(targets.ifEmpty { listOf(AnnotationTargetInput(locator, "UNRESOLVED")) }), guard),
            showEditor = showEditor)
        true
    }

    fun editNote(note: String?) = synchronized(lock) {
        require(AnnotationMutationCodec.validNote(note)) { "INVALID_NOTE" }
        val current = mutable.value
        if (current.pending != null || current.committed != null) return@synchronized
        mutable.value = current.copy(draft = current.draft?.copy(noteText = note), failure = null)
    }
    fun chooseColor(color: AnnotationColor) = synchronized(lock) {
        val current = mutable.value
        if (current.pending != null || current.committed != null) return@synchronized
        mutable.value = current.copy(draft = current.draft?.let { it.copy(style = it.style.copy(color = color)) }, failure = null)
    }
    fun discard(): Boolean = synchronized(lock) {
        if (mutable.value.busy || mutable.value.outcomeUnknown) return false
        generation++
        mutable.value = AnnotationCreationState()
        true
    }
    /** Owner disposal is unconditional; an old account's ticket must never enter a new owner. */
    fun close() = synchronized(lock) {
        generation++
        mutable.value = AnnotationCreationState()
    }
    suspend fun retry() = save()
    suspend fun save() {
        val ticket = synchronized(lock) {
            val current = mutable.value
            val draft = current.draft ?: return
            if (current.busy || current.committed != null) return
            val operation = current.pending ?: draft.copy(context = draft.context.copy(requestId = newRequestId()))
            mutable.value = current.copy(pending = operation, busy = true, failure = null)
            generation to operation
        }
        try {
            val result = create(ticket.second)
            synchronized(lock) {
                val current = mutable.value
                if (generation != ticket.first || current.pending !== ticket.second) return
                val op = ticket.second
                if (result is OperationResult.Ok && result.operationId == op.context.requestId &&
                    result.value.documentId == op.locator.documentId && samePersistedLocator(op.locator, result.value.locator) &&
                    result.value.quote == op.quote && result.value.noteText == op.noteText && result.value.style == op.style &&
                    result.value.targets.size == op.targets.size && op.targets.zip(result.value.targets).all { (expected, actual) ->
                        expected.precision == actual.precision && samePersistedLocator(expected.locator, actual.locator)
                    } &&
                    result.value.kind == op.kind && result.value.revision == 1L && result.value.id.isNotBlank()) {
                    mutable.value = current.copy(pending = null, busy = false, failure = null,
                        committed = AnnotationTargetValidation.freezeView(result.value), receiptUncertain = false)
                } else {
                    // A malformed success is an unknown write result, not a safe rejection.
                    val failure = if (result is OperationResult.Ok) OperationResult.Failed("INVALID_RECEIPT", true) else result
                    val uncertain = current.receiptUncertain || failure is OperationResult.Failed
                    mutable.value = current.copy(busy = false, failure = failure, showEditor = true,
                        receiptUncertain = uncertain,
                        pending = if (!uncertain && (failure is OperationResult.Rejected || failure is OperationResult.Conflict)) null else op)
                }
            }
        } catch (cancelled: CancellationException) {
            fail(ticket, "CANCELLED")
            throw cancelled
        } catch (_: Exception) {
            fail(ticket, "ANNOTATION_CREATE_FAILED")
        }
    }
    /** Locator v1 omits empty Reflow context strings when writing to Room. */
    private fun samePersistedLocator(expected: ReaderLocator, actual: ReaderLocator?): Boolean = actual != null && runCatching {
        ReaderLocatorCodec.encode(ReaderLocatorCodec.decode(ReaderLocatorCodec.encode(expected))) ==
            ReaderLocatorCodec.encode(ReaderLocatorCodec.decode(ReaderLocatorCodec.encode(actual)))
    }.getOrDefault(false)

    private fun fail(ticket: Pair<Long, AnnotationCreationOperation>, code: String) = synchronized(lock) {
        if (generation == ticket.first && mutable.value.pending === ticket.second)
            mutable.value = mutable.value.copy(busy = false, failure = OperationResult.Failed(code, true),
                receiptUncertain = true, showEditor = true)
    }
}
