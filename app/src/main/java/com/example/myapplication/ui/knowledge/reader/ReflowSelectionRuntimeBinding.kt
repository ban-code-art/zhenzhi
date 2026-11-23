package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderFeatureReport
import com.example.myapplication.domain.reader.ReaderSessionHandle
import com.example.myapplication.domain.reader.ReaderSessionToken
import com.example.myapplication.domain.reader.LocatorValidation
import com.example.myapplication.domain.reader.ReaderSelectionPhase
import com.example.myapplication.domain.reader.ReaderSelectionFragment
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ReflowSelectionRuntimeState(
    val selection: SelectionSessionState = SelectionSessionState(),
    val sourceAvailable: Boolean = false,
    val rejection: SelectionRejection? = null,
    val capture: ReflowSelectionCapture? = null,
) {
    val usableNativeRange: Boolean get() = sourceAvailable && selection.session?.nativeRangePresent == true
}

/** Serial owner of T01 reductions and effects. Native acknowledgements never invent a phase. */
internal class ReflowSelectionRuntimeBinding {
    private val operation = Mutex()
    private val mutableState = MutableStateFlow(ReflowSelectionRuntimeState())
    val state: StateFlow<ReflowSelectionRuntimeState> = mutableState.asStateFlow()
    private var report: ReaderFeatureReport? = null
    private var port: ReflowSelectionPort? = null
    private var gate: ReflowSelectionEventGate? = null
    private var nativeBinding: ReflowSelectionBinding? = null
    private var sourceTerminated = false
    private var focusTicket: ReflowSelectionCapture? = null
    private var focusEmptyObserved = false
    private var finishedEditingCapture: ReflowSelectionCapture? = null

    suspend fun bind(report: ReaderFeatureReport, port: ReflowSelectionPort): OperationResult<Unit> = operation.withLock {
        if (!port.isActive(report.token)) return@withLock reject("REFLOW_SELECTION_OWNER_UNAVAILABLE")
        val before = state.value
        val oldToken = before.selection.token
        if (oldToken == report.token && this.port != null && this.port !== port) return@withLock reject("REFLOW_SELECTION_PORT_MISMATCH")
        val input = if (oldToken == null || oldToken.handle() != report.token.handle() ||
            oldToken == report.token && before.selection.featureEventSequence == report.eventSequence) {
            SelectionSessionInput.BindOpened(report)
        } else SelectionSessionInput.FeatureChanged(report)
        val reduction = SelectionSessionReducer(port::validateSource).reduce(before.selection, input)
        reduction.rejection?.let { return@withLock reject(it) }
        val changed = oldToken != report.token || this.port !== port
        if (changed) {
            // The effect belongs to the old source, even when the new handle has identical text.
            executeRetiredEffect(reduction.nativeClear, before.capture, this.port)
            gate = null
            nativeBinding = null
            focusTicket = null
            focusEmptyObserved = false
            sourceTerminated = false
            finishedEditingCapture = null
        }
        this.report = report
        this.port = port
        mutableState.value = before.copy(selection = reduction.state, rejection = null,
            sourceAvailable = if (changed) false else before.sourceAvailable,
            capture = if (changed) null else before.capture)
        ok()
    }

    suspend fun accept(report: ReaderFeatureReport, port: ReflowSelectionPort, envelope: ReflowSelectionEnvelope): OperationResult<Unit> = operation.withLock {
        if (this.port !== port || this.report?.token != report.token || state.value.selection.token != report.token ||
            !port.isActive(report.token) || sourceTerminated) return@withLock reject("REFLOW_SELECTION_OWNER_UNAVAILABLE")
        if (envelope.binding.token != report.token || !ReflowSelectionValidation.envelope(envelope) ||
            nativeBinding != null && nativeBinding != envelope.binding) return@withLock reject("INVALID_SELECTION_ENVELOPE")
        val before = state.value
        val reduction = envelope.event?.let {
            SelectionSessionReducer(port::validateSource).reduce(before.selection, SelectionSessionInput.Selection(it, envelope.emptyCause))
        }
        // Canonical failures cannot consume a sequence or retire a good selection ID.
        reduction?.rejection?.let { return@withLock reject(it) }
        val eventGate = gate ?: ReflowSelectionEventGate(envelope.binding)
        if (!eventGate.accept(envelope)) return@withLock reject("STALE_SELECTION_EVENT")
        gate = eventGate
        nativeBinding = envelope.binding
        val keepsFinished = envelope.event == null ||
            envelope.event.phase == ReaderSelectionPhase.CLEARED && envelope.emptyCause == SelectionEmptyCause.TEMPORARY_FOCUS_LOSS &&
            before.capture == finishedEditingCapture && envelope.event.token == before.capture?.binding?.token &&
            envelope.event.selectionId == before.capture?.selectionId &&
            reduction?.state?.session?.fragments == before.selection.session?.fragments
        if (!keepsFinished) finishedEditingCapture = null
        val event = envelope.event
        when {
            event == null -> { focusTicket = null; focusEmptyObserved = false }
            event.phase != ReaderSelectionPhase.CLEARED -> { focusTicket = null; focusEmptyObserved = false }
            envelope.emptyCause == SelectionEmptyCause.TEMPORARY_FOCUS_LOSS -> {
                focusEmptyObserved = focusTicket == before.capture && focusTicket?.selectionId == event.selectionId
            }
            else -> { focusTicket = null; focusEmptyObserved = false }
        }
        val capture = when {
            event == null -> before.capture
            event.phase != ReaderSelectionPhase.CLEARED -> ReflowSelectionCapture(envelope.binding, envelope.eventSequence, event.selectionId)
            envelope.emptyCause == SelectionEmptyCause.TEMPORARY_FOCUS_LOSS -> before.capture
            else -> null
        }
        mutableState.value = before.copy(selection = reduction?.state ?: before.selection, rejection = null,
            sourceAvailable = event != null && event.phase != ReaderSelectionPhase.CLEARED, capture = capture)
        ok()
    }

    suspend fun sourceEnded(token: ReaderSessionToken, port: ReflowSelectionPort) = operation.withLock {
        if (this.port !== port || report?.token != token || state.value.selection.token != token) return@withLock
        sourceTerminated = true
        finishedEditingCapture = null
        focusTicket = null
        focusEmptyObserved = false
        if (port.isActive(token)) {
            mutableState.value = state.value.copy(sourceAvailable = false)
        } else {
            val reduction = SelectionSessionReducer(port::validateSource).reduce(state.value.selection, SelectionSessionInput.Close(token.handle()))
            mutableState.value = ReflowSelectionRuntimeState(selection = reduction.state)
            this.port = null
            report = null
            gate = null
            nativeBinding = null
        }
    }

    suspend fun confirmStable(capture: ReflowSelectionCapture): OperationResult<Unit> = operation.withLock {
        val owner = nativeOwner(capture) ?: return@withLock reject("STALE_SELECTION")
        nativeResult { owner.confirmStable(capture) }
    }

    suspend fun copy(capture: ReflowSelectionCapture): OperationResult<ReflowSelectionCopyReceipt> = operation.withLock {
        val owner = nativeOwner(capture) ?: return@withLock OperationResult.Rejected("STALE_SELECTION")
        val snapshot = state.value.selection.session
        if (snapshot == null || snapshot.phase != SelectionSessionPhase.STABLE || snapshot.fragments.size != 1)
            return@withLock OperationResult.Rejected("REFLOW_SELECTION_STABLE_REQUIRED")
        val request = ReflowSelectionCopyRequest(UUID.randomUUID().toString(), capture, snapshot.fragments.single())
        if (!ReflowSelectionValidation.copyRequest(request) || !owner.validateSource(capture.binding.token, listOf(request.fragment)).isValid)
            return@withLock OperationResult.Rejected("INVALID_REFLOW_SELECTION_COPY_REQUEST")
        val result = try { owner.copy(request) }
        catch (cancelled: CancellationException) {
            val committed = committedCopy(owner, request) ?: throw cancelled
            OperationResult.Ok(committed, request.operationId)
        } catch (_: Exception) { OperationResult.Failed("REFLOW_SELECTION_NATIVE_FAILED", false) }
        committedCopy(owner, request)?.let { return@withLock OperationResult.Ok(it, request.operationId) }
        val verified = if (result is OperationResult.Ok) OperationResult.Rejected("REFLOW_SELECTION_COPY_NOT_COMMITTED") else result
        // A pure Clipboard failure does not invalidate a still-STABLE native Range.
        if (!(verified is OperationResult.Failed && verified.code == "REFLOW_SELECTION_CLIPBOARD_FAILED") &&
            matches(capture) && port === owner) {
            mutableState.value = state.value.copy(sourceAvailable = false)
            focusTicket = null
            focusEmptyObserved = false
        }
        verified
    }

    private fun committedCopy(owner: ReflowSelectionPort, request: ReflowSelectionCopyRequest): ReflowSelectionCopyReceipt? =
        try { owner.committedCopyReceipt(request)?.takeIf { ReflowSelectionValidation.copyReceipt(request, it) } }
        catch (_: Exception) { null }

    suspend fun temporaryFocusHandoff(capture: ReflowSelectionCapture): OperationResult<Unit> = operation.withLock {
        val owner = nativeOwner(capture) ?: return@withLock reject("STALE_SELECTION")
        val result = nativeResult { owner.temporaryFocusHandoff(capture) }
        if (result is OperationResult.Ok) { focusTicket = capture; focusEmptyObserved = false }
        result
    }

    suspend fun clear(capture: ReflowSelectionCapture): OperationResult<Unit> = operation.withLock {
        val owner = nativeOwner(capture) ?: return@withLock reject("STALE_SELECTION")
        val reduction = SelectionSessionReducer(owner::validateSource).reduce(state.value.selection,
            SelectionSessionInput.Clear(capture.binding.token, capture.selectionId))
        reduction.rejection?.let { return@withLock reject(it) }
        val request = reduction.nativeClear ?: return@withLock reject("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
        val result = nativeResult { owner.compareAndClear(request, capture) }
        if (result is OperationResult.Ok) {
            mutableState.value = state.value.copy(selection = reduction.state, capture = null, sourceAvailable = false, rejection = null)
            focusTicket = null
            focusEmptyObserved = false
        }
        result
    }

    suspend fun beginEditing(capture: ReflowSelectionCapture, draftId: String): OperationResult<Unit> = operation.withLock {
        val before = state.value
        val owner = port
        if (!matches(capture) || owner == null || sourceTerminated || !owner.isActive(capture.binding.token))
            return@withLock reject("STALE_SELECTION")
        val reduction = SelectionSessionReducer(owner::validateSource).reduce(before.selection,
            SelectionSessionInput.BeginEditing(capture.binding.token, capture.selectionId, draftId))
        reduction.rejection?.let { return@withLock reject(it) }
        val ticketMatches = focusTicket == capture
        if (!before.usableNativeRange && !(ticketMatches && focusEmptyObserved)) return@withLock reject("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
        if (!ticketMatches) {
            val result = nativeResult { owner.temporaryFocusHandoff(capture) }
            if (result !is OperationResult.Ok) return@withLock result
            focusTicket = capture
            focusEmptyObserved = false
        }
        mutableState.value = before.copy(selection = reduction.state, sourceAvailable = false, rejection = null)
        finishedEditingCapture = null
        ok()
    }

    /** Caller has attached the current persisted row. This never restores a native Range. */
    suspend fun beginSavedEditing(capture: ReflowSelectionCapture, draftId: String,
        fragments: List<ReaderSelectionFragment>): OperationResult<Unit> = operation.withLock {
        val before = state.value
        val owner = port
        val selected = before.selection.session ?: return@withLock reject("STALE_SAVED_SELECTION")
        if (finishedEditingCapture != capture || !matches(capture) || owner == null || sourceTerminated ||
            report?.token != capture.binding.token || !owner.isActive(capture.binding.token) ||
            selected.nativeRangePresent || selected.fragments != fragments)
            return@withLock reject("STALE_SAVED_SELECTION")
        val reduction = SelectionSessionReducer(owner::validateSource).reduce(before.selection,
            SelectionSessionInput.BeginEditing(capture.binding.token, capture.selectionId, draftId))
        reduction.rejection?.let { return@withLock reject(it) }
        mutableState.value = before.copy(selection = reduction.state, sourceAvailable = false, rejection = null)
        focusTicket = null
        focusEmptyObserved = false
        finishedEditingCapture = null
        ok()
    }

    suspend fun finishEditing(capture: ReflowSelectionCapture, draftId: String): OperationResult<Unit> = operation.withLock {
        if (!matches(capture)) return@withLock reject("STALE_SELECTION")
        val before = state.value
        val snapshot = before.selection.session ?: return@withLock reject("NO_SELECTION")
        // A panel may close after blur or source termination. This proof is restricted to the
        // exact immutable snapshot previously accepted through this owner; it cannot add text.
        val reducer = SelectionSessionReducer { token, fragments ->
            LocatorValidation(if (token == snapshot.token && fragments == snapshot.fragments) emptyList() else listOf("UNVERIFIED_EDITING_SNAPSHOT"))
        }
        val reduction = reducer.reduce(before.selection, SelectionSessionInput.FinishEditing(capture.binding.token, capture.selectionId, draftId))
        reduction.rejection?.let { return@withLock reject(it) }
        mutableState.value = before.copy(selection = reduction.state, sourceAvailable = false, rejection = null)
        focusTicket = null
        focusEmptyObserved = false
        finishedEditingCapture = capture
        ok()
    }

    suspend fun close(handle: ReaderSessionHandle) = operation.withLock {
        val token = state.value.selection.token ?: return@withLock
        if (token.handle() != handle) return@withLock
        val owner = port ?: return@withLock
        val before = state.value
        val reduction = SelectionSessionReducer(owner::validateSource).reduce(before.selection, SelectionSessionInput.Close(handle))
        executeRetiredEffect(reduction.nativeClear, before.capture, owner)
        mutableState.value = ReflowSelectionRuntimeState(selection = reduction.state)
        port = null
        report = null
        gate = null
        nativeBinding = null
        focusTicket = null
        focusEmptyObserved = false
        sourceTerminated = true
        finishedEditingCapture = null
    }

    private fun matches(capture: ReflowSelectionCapture): Boolean = ReflowSelectionValidation.capture(capture) &&
        state.value.capture == capture && state.value.selection.token == capture.binding.token &&
        state.value.selection.session?.let { it.token == capture.binding.token && it.selectionId == capture.selectionId } == true

    private fun nativeOwner(capture: ReflowSelectionCapture): ReflowSelectionPort? =
        port?.takeIf { matches(capture) && state.value.usableNativeRange && !sourceTerminated && it.isActive(capture.binding.token) }

    private suspend fun nativeResult(action: suspend () -> OperationResult<Unit>): OperationResult<Unit> {
        val result = try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { OperationResult.Failed("REFLOW_SELECTION_NATIVE_FAILED", false) }
        if (result !is OperationResult.Ok) {
            mutableState.value = state.value.copy(sourceAvailable = false)
            focusTicket = null
            focusEmptyObserved = false
        }
        return result
    }

    private suspend fun executeRetiredEffect(request: SelectionNativeClearRequest?, capture: ReflowSelectionCapture?, owner: ReflowSelectionPort?) {
        if (request == null || capture == null || owner == null || request.token != capture.binding.token ||
            request.selectionId != capture.selectionId || !owner.isActive(request.token)) return
        try { owner.compareAndClear(request, capture) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The replacement/close still consumes the old business snapshot. */ }
    }
    private fun ok(): OperationResult<Unit> = OperationResult.Ok(Unit, "reflow-selection")
    private fun reject(value: SelectionRejection): OperationResult<Unit> {
        mutableState.value = state.value.copy(rejection = value)
        return OperationResult.Rejected(value.code)
    }
    private fun reject(code: String): OperationResult<Unit> = reject(SelectionRejection(code, code))
}
