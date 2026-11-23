package com.example.myapplication.ui.knowledge.reader

/** Intent only: the caller routes page actions through its current reader session. */
internal enum class ReaderTapAction { PREVIOUS, TOGGLE_CHROME, NEXT }

/**
 * A complete gesture observed without consuming native input. Coordinates and movement use
 * the same units as [ReaderInputArbiter.touchSlopPx], relative to the body viewport.
 * The observer retains maximum pointer count/displacement and any blocked state across
 * the entire gesture, including pointers lifted before UP and out-and-back movement.
 */
internal data class ReaderTapSample(
    val x: Float,
    val y: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val durationMillis: Long,
    val maxMovementPx: Float,
    val maxPointerCount: Int = 1,
    val finger: Boolean = true,
    val consumed: Boolean = false,
    val interactiveTarget: Boolean = false,
    val selectionActive: Boolean = false,
    val inkActive: Boolean = false,
    val cancelled: Boolean = false,
    val startedInSystemEdge: Boolean = false,
)

/** Pure 28/44/28 short-tap arbitration; no gesture interception or synthetic boundaries. */
internal class ReaderInputArbiter(
    val touchSlopPx: Float,
    val longPressTimeoutMillis: Long,
) {
    init {
        require(touchSlopPx.isFinite() && touchSlopPx >= 0f)
        require(longPressTimeoutMillis > 0)
    }

    fun resolve(sample: ReaderTapSample): ReaderTapAction? = with(sample) {
        if (consumed || interactiveTarget || selectionActive || inkActive || cancelled ||
            startedInSystemEdge || !finger || maxPointerCount != 1) return null
        if (durationMillis < 0 || durationMillis >= longPressTimeoutMillis ||
            !maxMovementPx.isFinite() || maxMovementPx < 0f || maxMovementPx > touchSlopPx) return null
        if (!viewportWidth.isFinite() || !viewportHeight.isFinite() ||
            viewportWidth <= 0f || viewportHeight <= 0f || !x.isFinite() || !y.isFinite() ||
            x < 0f || x >= viewportWidth || y < 0f || y >= viewportHeight) return null
        when {
            x < viewportWidth * 0.28f -> ReaderTapAction.PREVIOUS
            x < viewportWidth * 0.72f -> ReaderTapAction.TOGGLE_CHROME
            else -> ReaderTapAction.NEXT
        }
    }
}
