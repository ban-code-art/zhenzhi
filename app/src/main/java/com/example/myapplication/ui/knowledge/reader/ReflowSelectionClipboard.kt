package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderSelectionFragment
import kotlinx.coroutines.Job

internal data class ReflowSelectionCopyRequest(
    val operationId: String,
    val capture: ReflowSelectionCapture,
    val fragment: ReaderSelectionFragment,
)

internal data class ReflowSelectionCopyReceipt(
    val operationId: String,
    val capture: ReflowSelectionCapture,
)

internal fun interface ReflowSelectionClipboardWriter {
    fun writePlainText(text: String)
}

/** One pending permit and one committed fact, protected by the Surface's revoke/commit lock. */
internal class ReflowSelectionCopyCommitGate(
    private val commitLock: Any,
    private val writer: ReflowSelectionClipboardWriter,
) {
    private data class Permit(val source: Any, val request: ReflowSelectionCopyRequest, val job: Job)
    private data class Committed(val request: ReflowSelectionCopyRequest, val receipt: ReflowSelectionCopyReceipt)
    private var pending: Permit? = null
    private var committed: Committed? = null
    // Only an identifier is retained for the most recent failed/cancelled attempt, not its quote.
    private var lastOperationId: String? = null

    fun begin(source: Any, request: ReflowSelectionCopyRequest, job: Job): OperationResult<Unit> = synchronized(commitLock) {
        if (!job.isActive || !ReflowSelectionValidation.copyRequest(request))
            return@synchronized OperationResult.Rejected("INVALID_REFLOW_SELECTION_COPY_REQUEST")
        val existing = pending
        if (existing != null && existing.job.isActive)
            return@synchronized OperationResult.Rejected("REFLOW_SELECTION_COPY_IN_PROGRESS")
        pending = null
        if (lastOperationId == request.operationId || committed?.request?.operationId == request.operationId)
            return@synchronized OperationResult.Rejected("REFLOW_SELECTION_COPY_ALREADY_CONSUMED")
        pending = Permit(source, request, job)
        lastOperationId = request.operationId
        OperationResult.Ok(Unit, request.operationId)
    }

    fun commit(source: Any, request: ReflowSelectionCopyRequest,
        stillValid: () -> Boolean): OperationResult<ReflowSelectionCopyReceipt> = synchronized(commitLock) {
        val permit = pending
        // A malformed/replayed request or equivalent source cannot consume another permit.
        if (permit == null || permit.source !== source || permit.request != request)
            return@synchronized OperationResult.Rejected("REFLOW_SELECTION_COPY_PERMIT_MISMATCH")
        pending = null
        val valid = permit.job.isActive && try { stillValid() } catch (_: Exception) { false }
        if (!valid || !permit.job.isActive)
            return@synchronized OperationResult.Rejected("STALE_SELECTION")
        try {
            writer.writePlainText(request.fragment.quote)
        } catch (_: Exception) {
            return@synchronized OperationResult.Failed("REFLOW_SELECTION_CLIPBOARD_FAILED", true)
        }
        // Once IPC succeeds, cancellation or revoke cannot turn it into an uncommitted attempt.
        val receipt = ReflowSelectionCopyReceipt(request.operationId, request.capture)
        committed = Committed(request, receipt)
        OperationResult.Ok(receipt, request.operationId)
    }

    fun revoke(source: Any) = synchronized(commitLock) {
        if (pending?.source === source) pending = null
    }

    fun committedReceipt(request: ReflowSelectionCopyRequest): ReflowSelectionCopyReceipt? = synchronized(commitLock) {
        committed?.takeIf { it.request == request }?.receipt
    }
}
