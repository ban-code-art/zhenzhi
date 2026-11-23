package com.example.myapplication.ui.knowledge.reader

import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class ReaderChromeConditions(
    val immersiveEnabled: Boolean,
    val readerVisible: Boolean,
    val selectionActive: Boolean = false,
    val imeVisible: Boolean = false,
    val editorOpen: Boolean = false,
    val directoryOpen: Boolean = false,
    val accessibilityActive: Boolean = false,
    val overlayOpen: Boolean = false,
)

/**
 * Owns reader toolbar visibility and an idle deadline, without owning the timer coroutine.
 *
 * Callers supply the current interaction conditions and lifecycle state on the main thread.
 * A paused timer keeps its remaining budget; explicit interaction starts a full budget.
 */
@Stable
@MainThread
class ReaderChromeController(
    autoHideDelayMillis: Long = 3_000L,
    initialVisible: Boolean = true,
) : AutoCloseable {
    init {
        checkMainThread()
        require(autoHideDelayMillis > 0L) { "autoHideDelayMillis must be positive" }
    }

    val autoHideDelayMillis: Long = autoHideDelayMillis
        get() {
            checkMainThread()
            return field
        }

    private var visible by mutableStateOf(initialVisible)
    private var currentRequest by mutableStateOf<ReaderChromeAutoHideRequest?>(null)
    private var conditions = ReaderChromeConditions(
        immersiveEnabled = false,
        readerVisible = false,
    )
    private var lifecycleResumed = false
    private var remainingBudgetMillis = autoHideDelayMillis
    private var closed = false

    val isVisible: Boolean
        get() {
            checkMainThread()
            return visible
        }

    /** True only while there is a running request, excluding a paused idle budget. */
    val isAutoHidePending: Boolean
        get() {
            checkMainThread()
            return currentRequest != null
        }

    internal val autoHideRequest: ReaderChromeAutoHideRequest?
        get() {
            checkMainThread()
            return currentRequest
        }

    fun updateConditions(conditions: ReaderChromeConditions) {
        checkMainThread()
        if (closed || this.conditions == conditions) return

        val wasImmersive = this.conditions.immersiveEnabled
        this.conditions = conditions
        when {
            wasImmersive && !conditions.immersiveEnabled -> {
                resetIdleBudget()
                visible = true
            }

            !wasImmersive && conditions.immersiveEnabled -> {
                resetIdleBudget()
                // Entering while unavailable keeps the toolbar accessible until it can idle.
                visible = !canHide()
            }
        }
        synchronizeRequest()
    }

    /** Returns whether visibility changed; even an already visible toolbar gets a fresh budget. */
    fun show(): Boolean {
        checkMainThread()
        if (closed) return false

        val changed = !visible
        resetIdleBudget()
        visible = true
        synchronizeRequest()
        return changed
    }

    /** Refuses to hide while the reader is unavailable or an interaction needs the toolbar. */
    fun hide(): Boolean {
        checkMainThread()
        if (closed || !visible || !canHide()) return false

        resetIdleBudget()
        visible = false
        return true
    }

    fun toggle(): Boolean {
        checkMainThread()
        if (closed) return false
        return if (visible) hide() else show()
    }

    /** Restarts the idle budget without revealing an already hidden toolbar. */
    fun onInteraction() {
        checkMainThread()
        if (closed) return

        resetIdleBudget()
        synchronizeRequest()
    }

    internal fun setLifecycleResumed(resumed: Boolean) {
        checkMainThread()
        if (closed || lifecycleResumed == resumed) return

        lifecycleResumed = resumed
        synchronizeRequest()
    }

    /** A stale or paused request has no remaining delay and can never be consumed. */
    internal fun remainingDelayMillis(request: ReaderChromeAutoHideRequest): Long {
        checkMainThread()
        if (closed || currentRequest !== request || !canAutoHide()) return 0L
        return remainingAt(request, SystemClock.uptimeMillis())
    }

    internal fun consumeAutoHide(request: ReaderChromeAutoHideRequest): Boolean {
        checkMainThread()
        if (closed || currentRequest !== request || !canAutoHide()) return false
        if (SystemClock.uptimeMillis() < request.deadlineUptimeMillis) return false

        resetIdleBudget()
        visible = false
        return true
    }

    override fun close() {
        checkMainThread()
        if (closed) return

        closed = true
        currentRequest = null
        remainingBudgetMillis = 0L
    }

    private fun canHide(): Boolean = lifecycleResumed && conditions.readerVisible &&
        !conditions.selectionActive && !conditions.imeVisible && !conditions.editorOpen &&
        !conditions.directoryOpen && !conditions.accessibilityActive && !conditions.overlayOpen

    private fun canAutoHide(): Boolean = !closed && visible &&
        conditions.immersiveEnabled && canHide()

    private fun resetIdleBudget() {
        currentRequest = null
        remainingBudgetMillis = autoHideDelayMillis
    }

    private fun synchronizeRequest() {
        val request = currentRequest
        if (!canAutoHide()) {
            if (request != null) {
                remainingBudgetMillis = remainingAt(request, SystemClock.uptimeMillis())
                currentRequest = null
            }
            return
        }

        if (request == null) {
            val now = SystemClock.uptimeMillis()
            // Saturate rather than wrapping a valid positive delay into an expired deadline.
            val deadline = if (remainingBudgetMillis > Long.MAX_VALUE - now) {
                Long.MAX_VALUE
            } else {
                now + remainingBudgetMillis
            }
            currentRequest = ReaderChromeAutoHideRequest(deadline)
        }
    }

    private fun remainingAt(request: ReaderChromeAutoHideRequest, now: Long): Long =
        if (now >= request.deadlineUptimeMillis) 0L else request.deadlineUptimeMillis - now

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ReaderChromeController must be used on the main thread"
        }
    }
}

/** Object identity is the cancellation token; requests deliberately do not have value equality. */
internal class ReaderChromeAutoHideRequest(val deadlineUptimeMillis: Long)
