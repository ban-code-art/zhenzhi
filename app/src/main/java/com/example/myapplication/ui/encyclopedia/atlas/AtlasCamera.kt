package com.example.myapplication.ui.encyclopedia.atlas

import kotlin.math.min
import kotlin.math.roundToInt

internal data class AtlasFrame(val width: Float, val height: Float)

internal data class AtlasRect(val left: Float, val top: Float, val width: Float, val height: Float)

internal object AtlasFrames {
    val Body = AtlasFrame(340f, 480f)
    val Region = AtlasFrame(360f, 200f)
    val BodyArtwork = AtlasRect(19f, 23f, 290f, 435f)
    val RegionArtwork = AtlasRect(12f, 16f, 336f, 168f)
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 3f
    const val BUTTON_ZOOM_STEP = 0.35f
}

internal data class AtlasCameraState(
    val zoom: Float = AtlasFrames.MIN_ZOOM,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    val zoomPercent: Int get() = (zoom * 100f).roundToInt()
}

internal data class AtlasViewportTransform(
    val viewLeft: Float,
    val viewTop: Float,
    val viewWidth: Float,
    val viewHeight: Float,
    val scale: Float,
    val originX: Float,
    val originY: Float,
) {
    fun frameToScreen(fx: Float, fy: Float): Pair<Float, Float> =
        (originX + (fx - viewLeft) * scale) to (originY + (fy - viewTop) * scale)

    fun screenToFrame(sx: Float, sy: Float): Pair<Float, Float> =
        if (scale <= 0f) 0f to 0f
        else (viewLeft + (sx - originX) / scale) to (viewTop + (sy - originY) / scale)
}

internal object AtlasCameraMath {
    fun clamp(frame: AtlasFrame, zoom: Float, panX: Float, panY: Float): AtlasCameraState {
        val safeZoom = zoom.coerceIn(AtlasFrames.MIN_ZOOM, AtlasFrames.MAX_ZOOM)
        val visibleWidth = frame.width / safeZoom
        val visibleHeight = frame.height / safeZoom
        val limitX = ((frame.width - visibleWidth) / 2f).coerceAtLeast(0f)
        val limitY = ((frame.height - visibleHeight) / 2f).coerceAtLeast(0f)
        return AtlasCameraState(
            zoom = safeZoom,
            panX = panX.coerceIn(-limitX, limitX),
            panY = panY.coerceIn(-limitY, limitY),
        )
    }

    fun reset(): AtlasCameraState = AtlasCameraState()

    fun zoomAtAnchor(
        frame: AtlasFrame,
        current: AtlasCameraState,
        nextZoom: Float,
        anchorFrameX: Float = frame.width / 2f,
        anchorFrameY: Float = frame.height / 2f,
    ): AtlasCameraState {
        val targetZoom = nextZoom.coerceIn(AtlasFrames.MIN_ZOOM, AtlasFrames.MAX_ZOOM)
        val base = clamp(frame, current.zoom, current.panX, current.panY)
        val weight = 1f - base.zoom / targetZoom
        val nextPanX = base.panX + (anchorFrameX - frame.width / 2f - base.panX) * weight
        val nextPanY = base.panY + (anchorFrameY - frame.height / 2f - base.panY) * weight
        return clamp(frame, targetZoom, nextPanX, nextPanY)
    }

    fun zoomStep(
        frame: AtlasFrame,
        current: AtlasCameraState,
        zoomIn: Boolean,
        step: Float = AtlasFrames.BUTTON_ZOOM_STEP,
    ): AtlasCameraState = zoomAtAnchor(
        frame = frame,
        current = current,
        nextZoom = current.zoom + if (zoomIn) step else -step,
    )

    fun panByScreenDelta(
        frame: AtlasFrame,
        current: AtlasCameraState,
        screenDeltaX: Float,
        screenDeltaY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): AtlasCameraState {
        if (viewportWidth <= 0f || viewportHeight <= 0f) return clamp(frame, current.zoom, current.panX, current.panY)
        val t = transform(frame, current, viewportWidth, viewportHeight)
        if (t.scale <= 0f) return current
        return clamp(
            frame = frame,
            zoom = current.zoom,
            panX = current.panX - screenDeltaX / t.scale,
            panY = current.panY - screenDeltaY / t.scale,
        )
    }

    fun zoomByPinch(
        frame: AtlasFrame,
        current: AtlasCameraState,
        centroidScreenX: Float,
        centroidScreenY: Float,
        panScreenDx: Float,
        panScreenDy: Float,
        zoomFactor: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): AtlasCameraState {
        if (viewportWidth <= 0f || viewportHeight <= 0f || zoomFactor <= 0f) {
            return clamp(frame, current.zoom, current.panX, current.panY)
        }
        val before = transform(frame, current, viewportWidth, viewportHeight)
        val (anchorX, anchorY) = before.screenToFrame(centroidScreenX, centroidScreenY)
        val panShifted = clamp(
            frame = frame,
            zoom = current.zoom,
            panX = current.panX - panScreenDx / before.scale,
            panY = current.panY - panScreenDy / before.scale,
        )
        return zoomAtAnchor(
            frame = frame,
            current = panShifted,
            nextZoom = panShifted.zoom * zoomFactor,
            anchorFrameX = anchorX,
            anchorFrameY = anchorY,
        )
    }

    fun transform(
        frame: AtlasFrame,
        camera: AtlasCameraState,
        viewportWidth: Float,
        viewportHeight: Float,
    ): AtlasViewportTransform {
        val clamped = clamp(frame, camera.zoom, camera.panX, camera.panY)
        val viewWidth = frame.width / clamped.zoom
        val viewHeight = frame.height / clamped.zoom
        val viewLeft = (frame.width - viewWidth) / 2f + clamped.panX
        val viewTop = (frame.height - viewHeight) / 2f + clamped.panY
        val scale = if (viewportWidth <= 0f || viewportHeight <= 0f) 0f
        else min(viewportWidth / viewWidth, viewportHeight / viewHeight)
        val originX = (viewportWidth - viewWidth * scale) / 2f
        val originY = (viewportHeight - viewHeight * scale) / 2f
        return AtlasViewportTransform(
            viewLeft = viewLeft,
            viewTop = viewTop,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            scale = scale,
            originX = originX,
            originY = originY,
        )
    }
}
