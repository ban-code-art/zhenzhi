package com.example.myapplication.ui.knowledge.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Page-local view transform. PDF pixels, text and persisted typography are never rewritten. */
@Stable
class PdfZoomState {
    companion object {
        const val MIN_SCALE = .75f
        const val MAX_SCALE = 4f
        private val STEPS = listOf(.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
    }
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var fittedContent = Size.Zero
    var viewport = IntSize.Zero
        set(value) {
            field = IntSize(value.width.coerceAtLeast(0), value.height.coerceAtLeast(0))
            fittedContent = Size(field.width.toFloat(), field.height.toFloat())
            moveTo(offset.x.toDouble(), offset.y.toDouble())
        }

    /** The image/stack reports the size actually fitted inside the untransformed viewport. */
    fun updateGeometry(viewport: IntSize, contentSize: Size) {
        if (viewport.width < 0 || viewport.height < 0 || !contentSize.width.isFinite() ||
            !contentSize.height.isFinite() || contentSize.width < 0f || contentSize.height < 0f) return
        this.viewport = viewport
        fittedContent = Size(contentSize.width.coerceAtMost(viewport.width.toFloat()),
            contentSize.height.coerceAtMost(viewport.height.toFloat()))
        moveTo(offset.x.toDouble(), offset.y.toDouble())
    }

    fun setZoomScale(value: Float) {
        if (!value.isFinite() || value <= 0f) return
        scale = value.coerceIn(MIN_SCALE, MAX_SCALE)
        moveTo(offset.x.toDouble(), offset.y.toDouble())
    }

    fun zoomIn() { STEPS.firstOrNull { it > scale }?.let(::setZoomScale) }
    fun zoomOut() { STEPS.lastOrNull { it < scale }?.let(::setZoomScale) }

    fun transform(factor: Float, pan: Offset) {
        if (!factor.isFinite() || factor <= 0 || !pan.x.isFinite() || !pan.y.isFinite()) return
        scale = (scale.toDouble() * factor).coerceIn(MIN_SCALE.toDouble(), MAX_SCALE.toDouble()).toFloat()
        moveTo(offset.x.toDouble() + pan.x, offset.y.toDouble() + pan.y)
    }

    private fun moveTo(x: Double, y: Double) {
        val limitX = maxOf(0.0, (fittedContent.width.toDouble() * scale - viewport.width) / 2.0)
        val limitY = maxOf(0.0, (fittedContent.height.toDouble() * scale - viewport.height) / 2.0)
        offset = Offset(if (limitX == 0.0) 0f else x.coerceIn(-limitX, limitX).toFloat(),
            if (limitY == 0.0) 0f else y.coerceIn(-limitY, limitY).toFloat())
    }
    fun reset() { scale = 1f; offset = Offset.Zero }
}

@Composable
internal fun PdfZoomContent(
    bitmap: Bitmap,
    description: String,
    state: PdfZoomState,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 0.dp,
    allowSingleFingerPan: Boolean = true,
    overlay: @Composable () -> Unit = {},
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val paddingPx = with(LocalDensity.current) { contentPadding.toPx() }
    LaunchedEffect(state, bitmap.width, bitmap.height, viewport, paddingPx) {
        val width = (viewport.width - paddingPx * 2).coerceAtLeast(0f)
        val height = (viewport.height - paddingPx * 2).coerceAtLeast(0f)
        val fit = minOf(width / bitmap.width, height / bitmap.height)
        state.updateGeometry(viewport, Size(bitmap.width * fit, bitmap.height * fit))
    }
    Box(
        modifier.clipToBounds().onSizeChanged { viewport = it }
            .pointerInput(state, allowSingleFingerPan) {
                awaitEachGesture {
                    awaitFirstDown(false)
                    var transforming = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) break
                        val fingers = event.changes.count { it.pressed }
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        if (fingers > 1 || (allowSingleFingerPan && state.scale > 1f && pan.getDistance() > 0f)) transforming = true
                        if (transforming) {
                            state.transform(zoom, pan)
                            event.changes.filter { it.position != it.previousPosition }.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        // The page and every page-local decoration share one transform. Pointer positions
        // delivered to the overlay are already inverse-transformed by Compose.
        Box(Modifier.fillMaxSize().graphicsLayer {
            scaleX = state.scale; scaleY = state.scale
            translationX = state.offset.x; translationY = state.offset.y
        }) {
            Image(bitmap.asImageBitmap(), description,
                Modifier.fillMaxSize().padding(contentPadding), contentScale = ContentScale.Fit)
            overlay()
        }
    }
}
