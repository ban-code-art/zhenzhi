package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.notes.*
import com.example.myapplication.domain.reader.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException

/** Immutable ticket retained until its exact repository result is known. */
data class AnnotationActionOperation(val action: String, val context: AccessContext, val original: ReaderAnnotationView,
    val noteText: String?, val style: AnnotationStyle, val guard: AnnotationWriteGuard?)
data class AnnotationActionState(val original: ReaderAnnotationView? = null, val draftNote: String? = null,
    val draftStyle: AnnotationStyle = AnnotationStyle(), val pending: AnnotationActionOperation? = null,
    val busy: Boolean = false, val deleted: Boolean = false, val failure: OperationResult<*>? = null,
    val isOpen: Boolean = false, val receiptUncertain: Boolean = false, val showEditor: Boolean = true) {
    val dirty: Boolean get() = original?.let { draftNote != it.noteText || draftStyle != it.style } ?: false
    val outcomeUnknown: Boolean get() = receiptUncertain ||
        (pending != null && failure !is OperationResult.Conflict && failure !is OperationResult.Rejected)
}
class AnnotationActionController(
    private val update: suspend (AccessContext, String, Long, String?, AnnotationStyle?, AnnotationWriteGuard?) -> OperationResult<ReaderAnnotationView>,
    private val remove: suspend (AccessContext, String, Long, AnnotationWriteGuard?) -> OperationResult<ReaderAnnotationView>,
    private val restore: suspend (AccessContext, String, Long, AnnotationWriteGuard?) -> OperationResult<ReaderAnnotationView>,
    private val newRequestId: () -> String,
) {
    private val mutable = MutableStateFlow(AnnotationActionState())
    val state: StateFlow<AnnotationActionState> = mutable
    private val lock = Any()
    private var generation = 0L
    private var context: AccessContext? = null
    private var guard: AnnotationWriteGuard? = null

    fun open(context: AccessContext, view: ReaderAnnotationView, guard: AnnotationWriteGuard? = null, showEditor: Boolean = true) {
        require(view.kind != AnnotationKind.INK && view.revision > 0 && view.id.isNotBlank())
        val frozen = AnnotationTargetValidation.freezeView(view)
        synchronized(lock) {
            if (mutable.value.busy || mutable.value.outcomeUnknown) return
            generation++
            this.context = context.copy()
            this.guard = guard
            mutable.value = AnnotationActionState(frozen, frozen.noteText, frozen.style, isOpen = true, showEditor = showEditor)
        }
    }

    fun editNote(note: String?) {
        require(AnnotationMutationCodec.validNote(note)) { "INVALID_NOTE" }
        synchronized(lock) { mutable.value = mutable.value.copy(draftNote = note) }
    }

    fun chooseColor(color: AnnotationColor) = synchronized(lock) {
        mutable.value = mutable.value.copy(draftStyle = mutable.value.draftStyle.copy(color = color))
    }

    suspend fun save() = execute("UPDATE")
    suspend fun delete() = execute("DELETE")
    suspend fun undo() = execute("RESTORE")
    suspend fun retry() { mutable.value.pending?.let { execute(it.action) } }

    /** Explicit conflict resolution: adopt the current persisted revision, never overwrite local edits. */
    fun rebase(latest: ReaderAnnotationView): Boolean = synchronized(lock) {
        val current = mutable.value
        val original = current.original ?: return false
        val conflict = current.failure as? OperationResult.Conflict ?: return false
        if (current.busy || current.outcomeUnknown || !current.isOpen || latest.id != original.id || latest.documentId != original.documentId ||
            latest.kind == AnnotationKind.INK || latest.revision < conflict.currentRevision.toLong() || latest.revision <= original.revision) return false
        mutable.value = current.copy(original = AnnotationTargetValidation.freezeView(latest), pending = null, failure = null)
        true
    }

    fun discard(): Boolean = synchronized(lock) {
        if (mutable.value.busy || mutable.value.outcomeUnknown) return false
        generation++
        context = null
        guard = null
        mutable.value = AnnotationActionState()
        true
    }

    fun close() = synchronized(lock) {
        generation++
        context = null
        guard = null
        mutable.value = mutable.value.copy(busy = false, isOpen = false)
    }

    private suspend fun execute(action: String) {
        val initial = synchronized(lock) {
            val s = mutable.value
            val c = context ?: return
            if (s.busy || s.original == null || (s.pending != null && s.pending.action != action)) return
            if (s.pending == null && ((action == "RESTORE") != s.deleted)) return
            Triple(generation, s, c)
        }
        val old = initial.second
        val operation = old.pending ?: AnnotationActionOperation(
            action, initial.third.copy(requestId = newRequestId()), old.original!!,
            old.draftNote, old.draftStyle, guard,
        )
        synchronized(lock) {
            if (generation != initial.first || mutable.value !== old) return
            mutable.value = old.copy(pending = operation, busy = true, failure = null)
        }
        try {
            val result = when (action) {
                "UPDATE" -> update(operation.context, operation.original.id, operation.original.revision,
                    operation.noteText, operation.style, operation.guard)
                "DELETE" -> remove(operation.context, operation.original.id, operation.original.revision, operation.guard)
                "RESTORE" -> restore(operation.context, operation.original.id, operation.original.revision, operation.guard)
                else -> OperationResult.Rejected("INVALID_ACTION")
            }
            synchronized(lock) {
                val current = mutable.value
                if (generation != initial.first || current.pending !== operation) return
                if (result is OperationResult.Ok && result.operationId == operation.context.requestId &&
                    result.value.id == operation.original.id && result.value.documentId == operation.original.documentId &&
                    result.value.revision == operation.original.revision + 1 &&
                    result.value.locator == operation.original.locator && result.value.quote == operation.original.quote &&
                    result.value.targets == operation.original.targets && result.value.createdAt == operation.original.createdAt
                    && result.value.noteText == (if (action == "UPDATE") operation.noteText else operation.original.noteText)
                    && result.value.style == (if (action == "UPDATE") operation.style else operation.original.style)
                ) {
                    val committed = AnnotationTargetValidation.freezeView(result.value)
                    mutable.value = current.copy(original = committed,
                        draftNote = if (current.draftNote == operation.noteText) committed.noteText else current.draftNote,
                        draftStyle = if (current.draftStyle == operation.style) committed.style else current.draftStyle,
                        pending = null, busy = false, deleted = action == "DELETE", failure = null, receiptUncertain = false)
                } else {
                    val failure = if (result is OperationResult.Ok) OperationResult.Failed("INVALID_RECEIPT", true) else result
                    val uncertain = current.receiptUncertain || failure is OperationResult.Failed
                    // A refused replay cannot establish whether an earlier call committed.
                    // Keep the confirmation/retry UI reachable instead of exposing CAS rebase.
                    val displayed = if (uncertain && (failure is OperationResult.Conflict || failure is OperationResult.Rejected))
                        OperationResult.Failed("ANNOTATION_RESULT_UNCONFIRMED", true) else failure
                    mutable.value = current.copy(busy = false, failure = displayed, receiptUncertain = uncertain, showEditor = true)
                }
            }
        } catch (cancelled: CancellationException) {
            fail(initial.first, operation, OperationResult.Failed("CANCELLED", true))
            throw cancelled
        } catch (_: Exception) {
            fail(initial.first, operation, OperationResult.Failed("ANNOTATION_ACTION_FAILED", true))
        }
    }

    private fun fail(expectedGeneration: Long, operation: AnnotationActionOperation, result: OperationResult<*>) {
        synchronized(lock) {
            if (generation == expectedGeneration && mutable.value.pending === operation) {
                mutable.value = mutable.value.copy(busy = false, failure = result, receiptUncertain = true, showEditor = true)
            }
        }
    }
}
