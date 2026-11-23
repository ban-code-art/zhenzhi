package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.ReaderTurnDirection
import kotlin.math.abs

internal data class ReaderSwipeSample(val tap: ReaderTapSample, val deltaX: Float, val deltaY: Float)

/** A release can produce either a short tap or one horizontal swipe, never both. */
internal class ReaderSwipeArbiter(touchSlopPx: Float, longPressTimeoutMillis: Long) {
    private val taps = ReaderInputArbiter(touchSlopPx, longPressTimeoutMillis)
    private val minimumDistance = maxOf(1f, touchSlopPx * 3f)
    fun resolve(sample: ReaderSwipeSample): ReaderTurnDirection? {
        val (tap, dx, dy) = sample
        if (!dx.isFinite() || !dy.isFinite() || !tap.maxMovementPx.isFinite() || tap.maxMovementPx < 0f ||
            abs(dx) < minimumDistance || abs(dx) <= abs(dy) * 1.5f ||
            tap.maxMovementPx > kotlin.math.hypot(dx, dy) * 1.5f ||
            tap.x + dx < 0f || tap.x + dx >= tap.viewportWidth ||
            tap.y + dy < 0f || tap.y + dy >= tap.viewportHeight) return null
        // Reuse every ownership, duration and viewport guard; movement is checked above.
        if (taps.resolve(tap.copy(maxMovementPx = 0f)) == null) return null
        return if (dx < 0f) ReaderTurnDirection.NEXT else ReaderTurnDirection.PREVIOUS
    }
}
