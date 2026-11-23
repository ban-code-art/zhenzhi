package com.example.myapplication.ui.knowledge.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/** Observe the pager after its handlers. No overlay and no consumption of native gestures. */
@Composable
internal fun Modifier.readerViewportInput(
    blocked: Boolean,
    systemEdgePx: Float,
    onTap: (ReaderTapAction) -> Unit,
): Modifier {
    val latestBlocked by rememberUpdatedState(blocked)
    val latestTap by rememberUpdatedState(onTap)
    return pointerInput(systemEdgePx) {
        val arbiter = ReaderInputArbiter(viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            var consumed = down.isConsumed
            var rejected = latestBlocked
            var fingers = down.type == PointerType.Touch
            var pointerCount = 1
            var movement = 0f
            var duration = 0L
            var released = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                pointerCount = maxOf(pointerCount, event.changes.size)
                consumed = consumed || event.changes.any { it.isConsumed }
                rejected = rejected || latestBlocked
                fingers = fingers && event.changes.all { it.type == PointerType.Touch }
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change != null) {
                    movement = maxOf(movement, (change.position - down.position).getDistance())
                    duration = change.uptimeMillis - down.uptimeMillis
                    released = !change.pressed
                }
            } while (event.changes.any { it.pressed })
            val action = arbiter.resolve(ReaderTapSample(
                x = down.position.x, y = down.position.y,
                viewportWidth = size.width.toFloat(), viewportHeight = size.height.toFloat(),
                durationMillis = duration, maxMovementPx = movement,
                maxPointerCount = pointerCount, finger = fingers, consumed = consumed,
                inkActive = rejected, cancelled = !released,
                startedInSystemEdge = down.position.x < systemEdgePx || down.position.x >= size.width - systemEdgePx,
            ))
            if (action != null && !latestBlocked) latestTap(action)
        }
    }
}
