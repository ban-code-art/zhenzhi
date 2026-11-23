package com.example.myapplication.ui.knowledge.backup

import com.example.myapplication.data.reader.export.*
import com.example.myapplication.data.session.SessionIdentity
import java.util.Collections
import java.util.UUID

enum class DataManagementPickerKind { SAVE_EXPORT, OPEN_RESTORE }
data class DataManagementPickerTicket(
    val id: String,
    val kind: DataManagementPickerKind,
    val identity: SessionIdentity,
    val previewId: String? = null,
    val planId: String? = null,
)

sealed interface DataManagementAction {
    data class ChangeVersion(val version: DataManagementVersion) : DataManagementAction
    data class ToggleDocument(val id: String) : DataManagementAction
    data class ToggleContent(val content: DataManagementContent) : DataManagementAction
    data class ChooseConflict(val policy: DataManagementConflictPolicy) : DataManagementAction
    data object PreviewExport : DataManagementAction
    data class SaveExport(val previewId: String) : DataManagementAction
    data object PickRestore : DataManagementAction
    data class ConfirmRestore(val planId: String) : DataManagementAction
    data object CancelJob : DataManagementAction
    data object DismissReport : DataManagementAction
    data object EditSelection : DataManagementAction
}

/** Pure interaction oracle. Owns only selection/job/picker references, never background work. */
class DataManagementController(
    private val port: DataManagementPort,
    savedState: Map<String, String> = emptyMap(),
    private val newTicketId: () -> String = { UUID.randomUUID().toString() },
) {
    private val saved = LinkedHashMap(savedState.filterKeys { it.startsWith(PREFIX) })
    private var recovery: Map<String, String>? = saved.takeIf { it.isNotEmpty() }?.toMap()
    private var activeIdentity: SessionIdentity? = null
    private class Observation(val state: DataManagementState)
    private var latestObservation: Observation? = null
    private var effectToken: Any = Any()
    private var pending: DataManagementPickerTicket? = readTicket(saved)
    /** One unvalidated system callback, bound to pending's original owner/epoch and preview. */
    private var queuedPickerUri: String? = pending?.let { saved[PICKER + "uri"]?.takeIf(String::isNotBlank) }
    private data class SelectionRequest(val id: String, val identity: SessionIdentity, val selection: DataManagementSelection)
    private var awaitingSelection: SelectionRequest? = null
    private var reopeningJobId: String? = null
    private data class ReopenRequest(val id: String, val identity: SessionIdentity)
    private var deferredReopen: ReopenRequest? = null
    private val issuedTickets = mutableSetOf<String>().apply { pending?.id?.let(::add) }

    init { captureAndObserve() }

    fun beginSavePicker(previewId: String? = null): DataManagementPickerTicket? {
        val before = effectToken
        val state = current()
        if (before !== effectToken || !canCommand(state)) return null
        val preview = state.exportPreview ?: return null
        if (pending != null || awaitingSelection != null || !state.canSaveExport || (previewId != null && preview.id != previewId)) return null
        return createTicket(DataManagementPickerKind.SAVE_EXPORT, state.identity, preview.id)
    }

    fun beginRestorePicker(): DataManagementPickerTicket? {
        val before = effectToken
        val state = current()
        if (before !== effectToken || !canCommand(state)) return null
        if (pending != null || awaitingSelection != null || !state.canPickRestore) return null
        return createTicket(DataManagementPickerKind.OPEN_RESTORE, state.identity, null, state.restorePlan?.id)
    }

    /** One navigation request goes through the same draft/acknowledgement path as manual selection. */
    internal fun selectInitialDocument(documentId: String, expectedIdentity: SessionIdentity, requestId: String): Boolean {
        val before = effectToken
        var state = current()
        if (before !== effectToken || !canCommand(state) || state.identity != expectedIdentity ||
            !state.canEditSelection || pending != null || awaitingSelection != null || requestId.isBlank()
        ) return false
        // Both commands use the existing service actor. A just-imported book is checked against
        // the refreshed real catalog, rather than rejected from this route's older snapshot.
        port.refreshDocuments(expectedIdentity)
        state = current()
        if (!canCommand(state) || state.identity != expectedIdentity || !state.canEditSelection ||
            pending != null || awaitingSelection != null
        ) return false
        sendSelection(state.selection.copy(documentIds = setOf(documentId)), state, requestId)
        return true // Submitted draft, not a claim that the real service has accepted it.
    }

    /** Exact captured ticket is required. No fallback to the current owner or a replacement preview. */
    fun completePicker(ticket: DataManagementPickerTicket, uri: String?) {
        if (pending != ticket) return
        if (uri.isNullOrBlank()) {
            clearPendingPicker()
            return
        }
        // Never replace the first returned document with a different duplicate callback.
        if (queuedPickerUri == null) queuedPickerUri = uri
        storeTicket()
        captureAndObserve(force = true) // New local receipt needs processing even if the service snapshot is unchanged.
    }

    fun pendingPicker(): DataManagementPickerTicket? = pending

    fun handle(action: DataManagementAction) {
        val before = effectToken
        val state = current()
        // Recovery/receipt work performed by current is a separate operation, not a new user action.
        if (before !== effectToken || !canCommand(state)) return
        val draft = selectionRequestFor(state)?.selection ?: state.selection
        when (action) {
            is DataManagementAction.ChangeVersion -> {
                val capability = state.capabilities.forVersion(action.version)
                if (!state.canEditSelection || capability?.exportSupported != true) return
                sendSelection(draft.copy(version = action.version,
                    content = draft.content.intersect(capability.supportedContent)), state)
            }
            is DataManagementAction.ToggleDocument -> {
                if (!state.canEditSelection) return
                val selected = action.id in draft.documentIds
                if (!selected && state.documents.none { it.id == action.id && it.available }) return
                val ids = if (selected) draft.documentIds - action.id else draft.documentIds + action.id
                sendSelection(draft.copy(documentIds = ids), state)
            }
            is DataManagementAction.ToggleContent -> {
                if (!state.canEditSelection) return
                val supported = state.capabilities.forVersion(draft.version)?.supportedContent.orEmpty()
                if (action.content !in supported) return
                val content = if (action.content in draft.content) draft.content - action.content else draft.content + action.content
                sendSelection(draft.copy(content = content), state)
            }
            DataManagementAction.PreviewExport -> if (awaitingSelection == null && state.canPreviewExport) issue(state) { port.previewExport(state.identity) }
            is DataManagementAction.ChooseConflict -> {
                val plan = state.restorePlan ?: return
                if (!state.signedIn || state.loading || state.busy || state.step != DataManagementStep.RESTORE_PREVIEW ||
                    plan.identity != state.identity || state.capabilities.forVersion(plan.version)?.restoreSupported != true ||
                    action.policy !in plan.supportedPolicies) return
                issue(state) { port.chooseConflict(action.policy, state.identity) }
            }
            is DataManagementAction.ConfirmRestore -> if (state.canConfirmRestore && state.restorePlan?.id == action.planId) {
                issue(state) { port.confirmRestore(action.planId, state.identity) }
            }
            DataManagementAction.CancelJob -> if (state.canCancelJob) issue(state) { port.cancel(checkNotNull(state.job).id, state.identity) }
            DataManagementAction.DismissReport -> if (state.signedIn && !state.busy && state.report?.identity == state.identity) {
                issue(state) { port.dismissReport(state.identity) }
            }
            DataManagementAction.EditSelection -> if (state.signedIn && !state.loading && !state.busy &&
                state.step in setOf(DataManagementStep.EXPORT_PREVIEW, DataManagementStep.RESTORE_PREVIEW)) {
                sendSelection(draft, state)
            }
            // Route owns launching a system picker; these actions cannot start fake work here.
            is DataManagementAction.SaveExport, DataManagementAction.PickRestore -> Unit
        }
        captureAndObserve()
    }

    fun refreshDocuments(identity: SessionIdentity) {
        val state = current()
        if (state.identity == identity && state.signedIn && !state.loading && !state.busy) {
            port.refreshDocuments(identity)
        }
    }

    fun observeState(state: DataManagementState) {
        observeCaptured(state)
    }

    /** Returns the snapshot actually reconciled, including synchronous recovery publications. */
    private fun observeCaptured(state: DataManagementState): DataManagementState {
        val observation = Observation(state)
        latestObservation = observation
        if (handleLoading(state)) return observedState()
        val restore = recovery
        if (restore != null) {
            recovery = null
            activeIdentity = state.identity
            if (state.signedIn && readIdentity(restore) == state.identity) {
                pending = pending?.takeIf { it.identity == state.identity }
                if (pending == null) queuedPickerUri = null
                deferredReopen = restore[JOB]?.takeIf(String::isNotBlank)?.let { ReopenRequest(it, state.identity) }
                readSelection(restore)?.takeIf { it != state.selection }?.let {
                    // Preserve unavailable V2 as a disabled draft; never silently downgrade it.
                    if (!state.busy && state.step == DataManagementStep.SELECTION) sendSelection(it, state)
                }
                if (latestObservation !== observation) return observedState()
            } else {
                clearPendingPicker()
                awaitingSelection = null
                reopeningJobId = null
                deferredReopen = null
            }
        } else if (activeIdentity != state.identity) {
            clearPendingPicker()
            awaitingSelection = null
            reopeningJobId = null
            deferredReopen = null
            activeIdentity = state.identity
        }
        val request = awaitingSelection
        val resolution = state.selectionResolution
        if (request != null && resolution?.requestId == request.id && resolution.identity == request.identity &&
            state.identity == request.identity && (!resolution.accepted ||
                (state.step == DataManagementStep.SELECTION && state.selection == request.selection))) awaitingSelection = null
        if (state.error != null || state.job?.id == reopeningJobId || state.report?.jobId == reopeningJobId) reopeningJobId = null
        storeState(state)
        deferredReopen?.takeIf { it.identity == state.identity }?.let {
            deferredReopen = null // Mark issued before a synchronous publication can reenter.
            reopeningJobId = it.id
            storeState(state)
            issue(state) { port.reopen(it.id, it.identity) }
            if (latestObservation !== observation) return observedState()
        }
        consumeQueuedPicker(state)
        return observedState()
    }

    fun savedStateSnapshot(): Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(saved))

    /** Render the draft without presenting an unacknowledged selection as exportable. */
    fun displayState(): DataManagementState {
        val state = current()
        val request = selectionRequestFor(state) ?: return state
        return state.copy(selection = request.selection, selectionPending = true)
    }

    private fun current(): DataManagementState {
        captureAndObserve()
        captureAndObserve()
        return observedState()
    }

    private fun observedState(): DataManagementState = checkNotNull(latestObservation).state

    /** A getter can itself reenter observation; its earlier captured value must not replace that event. */
    private fun captureAndObserve(force: Boolean = false): DataManagementState {
        val before = latestObservation
        val captured = port.state.value
        if (latestObservation !== before) return observedState()
        return if (!force && before?.state === captured) captured else observeCaptured(captured)
    }

    private fun refreshAfterPort(before: Observation) {
        if (latestObservation === before) captureAndObserve()
    }

    private fun canCommand(state: DataManagementState): Boolean = latestObservation?.state === state &&
        state.identity == activeIdentity && state.signedIn && !state.loading

    /** All one-shot Port commands have the same scope check and reentrant return boundary. */
    private inline fun issue(state: DataManagementState, block: () -> Unit) {
        if (!canCommand(state)) return
        val before = checkNotNull(latestObservation)
        effectToken = Any()
        try { block() } finally { refreshAfterPort(before) }
    }

    private fun selectionRequestFor(state: DataManagementState): SelectionRequest? = awaitingSelection?.takeIf {
        it.identity == state.identity || (state.loading && state.identity.userId == null && state.identity.epoch == 0L)
    }

    private fun sendSelection(selection: DataManagementSelection, state: DataManagementState, requestId: String = UUID.randomUUID().toString()) {
        if (!canCommand(state) || state.busy) return
        val observation = checkNotNull(latestObservation)
        val previous = awaitingSelection
        val previousSaved = LinkedHashMap(saved)
        val request = SelectionRequest(requestId, state.identity, selection)
        awaitingSelection = request // Before enqueue: synchronous/reentrant results can resolve this request.
        storeState(state)
        try {
            effectToken = Any()
            port.changeSelection(selection, state.identity, request.id)
            refreshAfterPort(observation)
        } catch (error: Exception) {
            refreshAfterPort(observation)
            if (awaitingSelection == request && activeIdentity == request.identity) {
                awaitingSelection = previous
                saved.clear()
                saved.putAll(previousSaved)
                storeTicket()
            }
            storeState(observedState())
            throw error
        }
    }

    private fun clearPendingPicker() {
        pending = null
        queuedPickerUri = null
        storeTicket()
    }

    /** Same identity rule at every loading entry; never persist unvalidated loading fields. */
    private fun handleLoading(state: DataManagementState): Boolean {
        if (!state.loading) return false
        if (state.identity.userId == null && state.identity.epoch == 0L) return true
        val original = activeIdentity ?: recovery?.let { readIdentity(it) }
        activeIdentity = state.identity // Scope revocation is immediate; canCommand separately requires loaded state.
        if (original != null && original != state.identity) {
            clearPendingPicker()
            awaitingSelection = null
            reopeningJobId = null
            deferredReopen = null
            recovery = null
            saved.clear()
        } else {
            if (pending?.identity != state.identity) clearPendingPicker()
            if (awaitingSelection?.identity != state.identity) awaitingSelection = null
        }
        return true
    }

    private fun consumeQueuedPicker(state: DataManagementState) {
        val ticket = pending ?: return
        val uri = queuedPickerUri ?: return
        if (!canCommand(state)) return
        // Consume before sending: duplicate callbacks/reentrant state publication cannot resubmit.
        clearPendingPicker()
        if (ticket.identity != state.identity || !state.signedIn) return
        when (ticket.kind) {
            DataManagementPickerKind.SAVE_EXPORT -> {
                if (awaitingSelection != null || !state.canSaveExport || state.exportPreview?.id != ticket.previewId) return
                issue(state) { port.saveExport(checkNotNull(ticket.previewId), uri, ticket.identity) }
            }
            DataManagementPickerKind.OPEN_RESTORE -> {
                if (awaitingSelection != null || !state.canPickRestore || state.restorePlan?.id != ticket.planId) return
                issue(state) { port.prepareRestore(uri, ticket.identity) }
            }
        }
    }

    private fun createTicket(kind: DataManagementPickerKind, identity: SessionIdentity, previewId: String?, planId: String? = null): DataManagementPickerTicket {
        var id = newTicketId().takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        while (!issuedTickets.add(id)) id = UUID.randomUUID().toString()
        val ticket = DataManagementPickerTicket(id, kind, identity, previewId, planId)
        pending = ticket
        storeTicket()
        return ticket
    }

    private fun storeState(state: DataManagementState) {
        if (latestObservation?.state !== state || state.loading) return
        saved.clear()
        if (!state.signedIn) return
        saved[OWNER] = checkNotNull(state.identity.userId).toString()
        saved[EPOCH] = state.identity.epoch.toString()
        val selection = selectionRequestFor(state)?.selection ?: state.selection
        saved[VERSION] = selection.version.name
        // Length-prefixed strings work on API 24 without Android API 26 Base64 APIs.
        saved[DOCUMENTS] = selection.documentIds.joinToString("") { "${it.length}:$it" }
        saved[CONTENT] = selection.content.joinToString(",") { it.name }
        (deferredReopen?.takeIf { it.identity == state.identity }?.id ?: state.job?.takeIf { it.identity == state.identity }?.id ?:
            state.report?.takeIf { it.identity == state.identity }?.jobId ?: reopeningJobId)
            ?.let { saved[JOB] = it }
        storeTicket()
    }

    private fun storeTicket() {
        saved.keys.removeAll { it.startsWith(PICKER) }
        pending?.let { ticket ->
            saved[PICKER + "id"] = ticket.id
            saved[PICKER + "kind"] = ticket.kind.name
            saved[PICKER + "owner"] = checkNotNull(ticket.identity.userId).toString()
            saved[PICKER + "epoch"] = ticket.identity.epoch.toString()
            ticket.previewId?.let { saved[PICKER + "preview"] = it }
            ticket.planId?.let { saved[PICKER + "plan"] = it }
            queuedPickerUri?.let { saved[PICKER + "uri"] = it }
        }
    }

    private fun readIdentity(values: Map<String, String>, prefix: String = PREFIX): SessionIdentity? {
        val owner = values[prefix + "owner"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val epoch = values[prefix + "epoch"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return SessionIdentity(owner, epoch)
    }

    private fun readTicket(values: Map<String, String>): DataManagementPickerTicket? {
        return runCatching {
        val id = values[PICKER + "id"]?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
        val kind = DataManagementPickerKind.valueOf(values[PICKER + "kind"] ?: return null)
        val identity = readIdentity(values, PICKER) ?: return null
        val preview = values[PICKER + "preview"]
        val plan = values[PICKER + "plan"]
        if (kind == DataManagementPickerKind.SAVE_EXPORT && preview.isNullOrBlank()) return null
        if (plan != null && (plan.isBlank() || plan.length > 256)) return null
        DataManagementPickerTicket(id, kind, identity, preview, plan)
        }.getOrNull()
    }

    private fun readSelection(values: Map<String, String>): DataManagementSelection? {
        return runCatching {
        val version = DataManagementVersion.valueOf(values[VERSION] ?: return null)
        val encoded = values[DOCUMENTS] ?: return null
        if (encoded.length > 64 * 1024) return null
        val ids = linkedSetOf<String>()
        var cursor = 0
        while (cursor < encoded.length) {
            val delimiter = encoded.indexOf(':', cursor)
            if (delimiter < cursor) return null
            val length = encoded.substring(cursor, delimiter).toIntOrNull() ?: return null
            cursor = delimiter + 1
            if (length <= 0 || length > encoded.length - cursor) return null
            ids += encoded.substring(cursor, cursor + length)
            cursor += length
            if (ids.size > 200) return null
        }
        if (ids.size > 200 || ids.any(String::isBlank)) return null
        val rawContent = values[CONTENT] ?: return null
        val content = if (rawContent.isEmpty()) emptySet() else rawContent.split(',').map(DataManagementContent::valueOf).toSet()
        DataManagementSelection(version, ids, content)
        }.getOrNull()
    }

    companion object {
        const val PREFIX = "m21.data."
        private const val OWNER = PREFIX + "owner"
        private const val EPOCH = PREFIX + "epoch"
        private const val VERSION = PREFIX + "version"
        private const val DOCUMENTS = PREFIX + "documents"
        private const val CONTENT = PREFIX + "content"
        private const val JOB = PREFIX + "job"
        private const val PICKER = PREFIX + "picker."
    }
}
