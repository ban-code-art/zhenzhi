package com.example.myapplication.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException

internal enum class PopupCapturePhase { Pending, Ready, Failed, NoSource }

internal enum class PopupDelivery { AfterSourceReturn, CloseAndDispatch, KeepOpenAndDispatch }

internal fun popupSelectionDelivery(phase: PopupCapturePhase, keepsOpen: Boolean): PopupDelivery = when {
    keepsOpen -> PopupDelivery.KeepOpenAndDispatch
    phase == PopupCapturePhase.Ready -> PopupDelivery.AfterSourceReturn
    else -> PopupDelivery.CloseAndDispatch
}

internal fun resolvePopupChoice(
    value: String?,
    choices: List<AnchoredMenuChoice>,
    ownerMatches: Boolean,
    lifecycleStarted: Boolean,
): AnchoredMenuChoice? {
    if (!ownerMatches || !lifecycleStarted) return null
    return choices.firstOrNull { it.enabled && it.value == value }
}

@Stable
internal class PopupAnchor {
    val focus = FocusRequester()

    var bounds by mutableStateOf<Rect?>(null)
        private set
    var hidden by mutableStateOf(false)
    private var foreground: GraphicsLayer? = null
    private var input: LayoutCoordinates? = null

    val visualModifier: Modifier
        get() = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }
            .graphicsLayer { alpha = if (hidden) 0f else 1f }

    @Composable
    fun foregroundModifier(): Modifier {
        val layer = rememberGraphicsLayer()
        DisposableEffect(this, layer) {
            foreground = layer
            onDispose {
                if (foreground === layer) {
                    foreground = null
                    input = null
                    bounds = null
                    hidden = false
                }
            }
        }
        return Modifier.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
    }

    suspend fun captureForeground(): ImageBitmap? {
        val layer = foreground ?: return null
        if (layer.size == IntSize.Zero) return null
        return try {
            layer.toImageBitmap().takeIf { foreground === layer && bounds != null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    @Composable
    fun inputModifier(enabled: Boolean = true, onOpen: (PopupDrag) -> Boolean): Modifier {
        val latestOpen by rememberUpdatedState(onOpen)
        return Modifier.onGloballyPositioned { input = it }
            .pointerInput(this, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val coordinates = input?.takeIf { it.isAttached } ?: return@awaitEachGesture
                    val origin = coordinates.localToRoot(down.position)
                    if (down.isConsumed || bounds == null) return@awaitEachGesture
                    val drag = PopupDrag(origin)
                    if (!latestOpen(drag)) return@awaitEachGesture
                    down.consume()
                    val touchSlop = viewConfiguration.touchSlop
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed || !coordinates.isAttached) break
                            drag.update(coordinates.localToRoot(change.position), touchSlop, released = !change.pressed)
                            event.changes.forEach { it.consume() }
                        } while (change.pressed)
                    } finally {
                        drag.cancelIfActive()
                    }
                }
            }
    }
}

internal data class PopupDragState(
    val position: Offset,
    val armed: Boolean = false,
    val released: Boolean = false,
    val cancelled: Boolean = false,
)

@Stable
internal class PopupDrag(private val origin: Offset) {
    var state by mutableStateOf(PopupDragState(origin))
        private set

    fun update(position: Offset, touchSlop: Float, released: Boolean) {
        state = PopupDragState(
            position,
            armed = state.armed || (position - origin).getDistance() >= touchSlop,
            released = released,
        )
    }

    fun cancelIfActive() {
        if (!state.released) state = state.copy(cancelled = true)
    }
}

