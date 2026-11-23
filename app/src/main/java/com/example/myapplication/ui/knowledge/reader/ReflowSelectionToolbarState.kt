package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.ReaderSessionToken
import com.example.myapplication.ui.navigation.BackSelection

internal enum class ReflowSelectionToolbarMode { HIDDEN, DRAGGING, STABLE, EDITING, UNAVAILABLE }

/** Composition-local action feedback; this never stores or reduces a selection session. */
internal enum class ReflowSelectionUiActionPhase { IDLE, CONFIRMING, WAITING_STABLE, COPYING, CLEARING }

internal data class ReflowSelectionToolbarUiAction(
    val capture: ReflowSelectionCapture,
    val phase: ReflowSelectionUiActionPhase,
    val feedback: String? = null,
)

/** Every field must be supplied from the current committed runtime UI snapshot. */
internal data class ReflowSelectionToolbarOwner(
    val activeToken: ReaderSessionToken?,
    val loading: Boolean,
    val busy: Boolean,
    val closed: Boolean,
    val turnPhase: ReaderTurnPhase?,
)

internal data class ReflowSelectionToolbarState(
    val mode: ReflowSelectionToolbarMode = ReflowSelectionToolbarMode.HIDDEN,
    val capture: ReflowSelectionCapture? = null,
    val confirmEnabled: Boolean = false,
    val copyEnabled: Boolean = false,
    val clearEnabled: Boolean = false,
    val dismissEnabled: Boolean = false,
    val blocksReaderInput: Boolean = false,
    val backSelection: BackSelection? = null,
    val statusText: String? = null,
) {
    val visible: Boolean get() = mode != ReflowSelectionToolbarMode.HIDDEN
}

/** Projects accepted T01 state; acknowledgements and UI feedback never change its phase. */
internal fun projectReflowSelectionToolbar(
    selection: ReflowSelectionRuntimeState,
    owner: ReflowSelectionToolbarOwner,
    action: ReflowSelectionToolbarUiAction? = null,
    dismissedCapture: ReflowSelectionCapture? = null,
): ReflowSelectionToolbarState {
    val capture = ownedReflowSelectionCapture(selection, owner) ?: return ReflowSelectionToolbarState()
    val session = selection.selection.session ?: return ReflowSelectionToolbarState()
    val editing = session.phase == SelectionSessionPhase.EDITING
    val usable = selection.usableNativeRange && !editing
    if (!usable && !editing && dismissedCapture == capture) return ReflowSelectionToolbarState()
    val mode = when {
        editing -> ReflowSelectionToolbarMode.EDITING
        !usable -> ReflowSelectionToolbarMode.UNAVAILABLE
        session.phase == SelectionSessionPhase.DRAGGING -> ReflowSelectionToolbarMode.DRAGGING
        else -> ReflowSelectionToolbarMode.STABLE
    }
    val currentAction = action?.takeIf { isCurrentReflowSelectionUiAction(it, selection, owner) }
    val completedWait = currentAction?.let {
        it.phase == ReflowSelectionUiActionPhase.WAITING_STABLE && session.phase == SelectionSessionPhase.STABLE &&
            capture.eventSequence > it.capture.eventSequence
    } == true
    val pending = currentAction != null && currentAction.phase != ReflowSelectionUiActionPhase.IDLE && !completedWait
    val status = when {
        editing -> "正在编辑附注"
        !usable -> "选区已保留，请重新选择"
        completedWait -> null
        currentAction?.phase == ReflowSelectionUiActionPhase.CONFIRMING -> "正在确认选区…"
        currentAction?.phase == ReflowSelectionUiActionPhase.WAITING_STABLE -> "等待选区稳定…"
        currentAction?.phase == ReflowSelectionUiActionPhase.COPYING -> "正在复制…"
        currentAction?.phase == ReflowSelectionUiActionPhase.CLEARING -> "正在清除…"
        currentAction?.capture == capture -> currentAction?.feedback
        else -> null
    }
    return ReflowSelectionToolbarState(
        mode = mode,
        capture = capture,
        confirmEnabled = mode == ReflowSelectionToolbarMode.DRAGGING && !pending,
        copyEnabled = mode == ReflowSelectionToolbarMode.STABLE && !pending,
        clearEnabled = usable && !pending,
        dismissEnabled = !usable && !editing,
        blocksReaderInput = usable || editing,
        backSelection = if (usable) BackSelection.TEXT else null,
        statusText = status,
    )
}

internal fun ReflowReaderRuntimeUiState.selectionToolbarOwner() = ReflowSelectionToolbarOwner(
    activeToken = turnState?.active?.report?.token,
    loading = loading,
    busy = busy,
    closed = closed,
    turnPhase = turnState?.phase,
)

/** Uses the full token, native binding, sequence and selection ID, including retained snapshots. */
internal fun ownedReflowSelectionCapture(
    selection: ReflowSelectionRuntimeState,
    owner: ReflowSelectionToolbarOwner,
): ReflowSelectionCapture? {
    if (owner.loading || owner.busy || owner.closed || owner.turnPhase != ReaderTurnPhase.IDLE) return null
    val capture = selection.capture ?: return null
    val session = selection.selection.session ?: return null
    return capture.takeIf {
        ReflowSelectionValidation.capture(it) && owner.activeToken == it.binding.token &&
            selection.selection.token == it.binding.token && session.token == it.binding.token &&
            session.selectionId == it.selectionId
    }
}

/** A pending confirmation may follow its own newer STABLE event, never a newer DRAGGING event. */
internal fun isCurrentReflowSelectionUiAction(
    action: ReflowSelectionToolbarUiAction,
    selection: ReflowSelectionRuntimeState,
    owner: ReflowSelectionToolbarOwner,
): Boolean {
    val capture = ownedReflowSelectionCapture(selection, owner) ?: return false
    if (!selection.usableNativeRange || selection.selection.session?.phase == SelectionSessionPhase.EDITING) return false
    if (capture == action.capture) return true
    return capture.binding == action.capture.binding && capture.selectionId == action.capture.selectionId &&
        capture.eventSequence > action.capture.eventSequence && selection.selection.session?.phase == SelectionSessionPhase.STABLE &&
        (action.phase == ReflowSelectionUiActionPhase.CONFIRMING || action.phase == ReflowSelectionUiActionPhase.WAITING_STABLE)
}
