package com.example.myapplication.ui.knowledge.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import com.example.myapplication.domain.reader.ReaderTurnDirection

/** For isolated bitmap surfaces: observes Final pass and never consumes native ownership. */
@Composable
internal fun Modifier.readerRuntimeViewportInput(
    blocked: Boolean,
    systemEdgePx: Float,
    onTap: (ReaderTapAction) -> Unit,
    onSwipe: (ReaderTurnDirection) -> Unit,
    edgeTapEnabled: Boolean = true,
    onGestureActive: (Boolean) -> Unit = {},
): Modifier {
    val latestBlocked by rememberUpdatedState(blocked)
    val latestTap by rememberUpdatedState(onTap)
    val latestSwipe by rememberUpdatedState(onSwipe)
    val latestEdgeTap by rememberUpdatedState(edgeTapEnabled)
    val latestGesture by rememberUpdatedState(onGestureActive)
    return pointerInput(systemEdgePx) {
        val taps = ReaderInputArbiter(viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis)
        val swipes = ReaderSwipeArbiter(viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis)
        awaitEachGesture {
            val down = awaitFirstDown(false, PointerEventPass.Final)
            latestGesture(true)
            try {
            var consumed = down.isConsumed
            var rejected = latestBlocked
            var finger = down.type == PointerType.Touch
            var pointerCount = currentEvent.changes.size
            var movement = 0f
            var end = down.position
            var duration = 0L
            var released = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                pointerCount = maxOf(pointerCount, event.changes.size)
                consumed = consumed || event.changes.any { it.isConsumed }
                rejected = rejected || latestBlocked
                finger = finger && event.changes.all { it.type == PointerType.Touch }
                event.changes.firstOrNull { it.id == down.id }?.let {
                    end = it.position
                    movement = maxOf(movement, (end - down.position).getDistance())
                    duration = it.uptimeMillis - down.uptimeMillis
                    released = !it.pressed
                }
            } while (event.changes.any { it.pressed })
            val sample = ReaderTapSample(down.position.x, down.position.y,
                size.width.toFloat(), size.height.toFloat(), duration, movement,
                maxPointerCount = pointerCount, finger = finger, consumed = consumed,
                inkActive = rejected, cancelled = !released,
                startedInSystemEdge = down.position.x < systemEdgePx || down.position.x >= size.width - systemEdgePx)
            if (!latestBlocked) {
                val tap = taps.resolve(sample)
                if (tap != null) {
                    if (tap == ReaderTapAction.TOGGLE_CHROME || latestEdgeTap) latestTap(tap)
                }
                else swipes.resolve(ReaderSwipeSample(sample, end.x - down.position.x, end.y - down.position.y))?.let(latestSwipe)
            }
            } finally { latestGesture(false) }
        }
    }
}
