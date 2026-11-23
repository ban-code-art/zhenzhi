package com.example.myapplication.domain.review

/** Measures one card using monotonic foreground time. Inactivity closes a segment after one minute. */
internal class ReviewFocusTimer(private var accumulatedMs: Long = 0L) {
    private var segmentStartMs: Long? = null

    fun setVisible(visible: Boolean, nowMs: Long) {
        if (visible == (segmentStartMs != null)) return
        if (visible) {
            segmentStartMs = nowMs
        } else {
            accumulatedMs = activeMillis(nowMs)
            segmentStartMs = null
        }
    }

    fun interaction(nowMs: Long) {
        if (segmentStartMs == null) return
        accumulatedMs = activeMillis(nowMs)
        segmentStartMs = nowMs
    }

    fun activeMillis(nowMs: Long): Long {
        val started = segmentStartMs ?: return accumulatedMs
        return accumulatedMs + (nowMs - started).coerceIn(0L, IDLE_LIMIT_MS)
    }

    private companion object {
        const val IDLE_LIMIT_MS = 60_000L
    }
}
