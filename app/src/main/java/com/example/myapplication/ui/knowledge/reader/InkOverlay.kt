package com.example.myapplication.ui.knowledge.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.example.myapplication.data.reader.ink.InkPoint
import com.example.myapplication.data.reader.ink.InkStroke
import com.example.myapplication.data.reader.ink.InkTool
import java.util.UUID

/** WebView scroll offsets are in device pixels, while the document dimensions are CSS pixels. */
internal data class InkDocumentSpace(
    val documentWidthCss: Float,
    val documentHeightCss: Float,
    val layoutWidthCss: Float,
    val pixelsPerCssPixel: Float,
    val scrollXPx: Float,
    val scrollYPx: Float,
    val containers: List<InkScrollContainer> = emptyList(),
) {
    fun toDocument(x: Float, y: Float): Pair<Float, Float> =
        ((x + scrollXPx) / (documentWidthCss * pixelsPerCssPixel)).coerceIn(0f, 1f) to
            ((y + scrollYPx) / (documentHeightCss * pixelsPerCssPixel)).coerceIn(0f, 1f)

    fun toViewport(x: Float, y: Float, usesDocumentWidth: Boolean): Pair<Float, Float> {
        val width = if (usesDocumentWidth) documentWidthCss else layoutWidthCss
        return x * width * pixelsPerCssPixel - scrollXPx to
            y * documentHeightCss * pixelsPerCssPixel - scrollYPx
    }

    fun anchorAt(x: Float, y: Float): InkScrollContainer? =
        containers.lastOrNull { it.viewport(this).contains(Offset(x, y)) }

    fun anchorFor(stroke: InkStroke): InkScrollContainer? {
        val key = stroke.scrollContainer
        if (key != null) return containers.firstOrNull { it.key == key }
        // Old strokes used document coordinates captured at the unscrolled DOM position.
        val first = stroke.points.first()
        val (x, y) = toViewport(first.x, first.y, stroke.usesDocumentWidth)
        val candidate = anchorAt(x, y) ?: return null
        val bounds = candidate.viewport(this)
        return candidate.takeIf {
            stroke.points.all { point ->
                val (px, py) = toViewport(point.x, point.y, stroke.usesDocumentWidth)
                bounds.contains(Offset(px, py))
            }
        }
    }

    fun project(stroke: InkStroke, point: InkPoint, anchor: InkScrollContainer?): Offset {
        if (stroke.scrollContainer != null && anchor != null) return anchor.toViewport(point, this)
        val (x, y) = toViewport(point.x, point.y, stroke.usesDocumentWidth)
        return Offset(
            x - (anchor?.scrollXCss ?: 0f) * pixelsPerCssPixel,
            y - (anchor?.scrollYCss ?: 0f) * pixelsPerCssPixel,
        )
    }
}

/** A DOM overflow element scrolls independently of the Android WebView. */
internal data class InkScrollContainer(
    val key: String,
    val leftCss: Float,
    val topCss: Float,
    val viewportWidthCss: Float,
    val viewportHeightCss: Float,
    val contentWidthCss: Float,
    val contentHeightCss: Float,
    val scrollXCss: Float,
    val scrollYCss: Float,
) {
    fun viewport(space: InkDocumentSpace): Rect {
        val scale = space.pixelsPerCssPixel
        val left = leftCss * scale - space.scrollXPx
        val top = topCss * scale - space.scrollYPx
        return Rect(left, top, left + viewportWidthCss * scale, top + viewportHeightCss * scale)
    }

    fun toLocal(x: Float, y: Float, space: InkDocumentSpace): Pair<Float, Float> {
        val viewport = viewport(space)
        val scale = space.pixelsPerCssPixel
        return (((x - viewport.left) / scale + scrollXCss) / contentWidthCss).coerceIn(0f, 1f) to
            (((y - viewport.top) / scale + scrollYCss) / contentHeightCss).coerceIn(0f, 1f)
    }

    fun toViewport(point: InkPoint, space: InkDocumentSpace): Offset {
        val viewport = viewport(space)
        val scale = space.pixelsPerCssPixel
        return Offset(
            viewport.left + (point.x * contentWidthCss - scrollXCss) * scale,
            viewport.top + (point.y * contentHeightCss - scrollYCss) * scale,
        )
    }
}

@Composable
internal fun InkOverlay(
    strokes: List<InkStroke>,
    tool: InkTool,
    color: String,
    width: Float,
    onStroke: (InkStroke) -> Unit,
    onEraseMatching: ((InkStroke) -> Boolean) -> Unit,
    editable: Boolean = true,
    /** Null for fixed pages. A provider returning null indicates that the WebView is still loading. */
    document: (() -> InkDocumentSpace?)? = null,
    modifier: Modifier = Modifier,
    /** Legacy fixed-page coordinates projected into the untransformed page body. */
    fixedPageSpace: (() -> PdfInkCanvasSpace?)? = null,
    /** Event-time ownership check, also used when a page starts moving before recomposition. */
    canEdit: () -> Boolean = { true },
    /** Saved reflow strokes already composited by WebView; still use them for eraser hit testing. */
    paintSavedStrokes: Boolean = true,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var pending by remember(editable, tool, color, width) { mutableStateOf<List<InkPoint>>(emptyList()) }
    var pendingAnchor by remember(editable, tool, color, width) { mutableStateOf<String?>(null) }
    val latestDocument by rememberUpdatedState(document)
    val latestFixedPageSpace by rememberUpdatedState(fixedPageSpace)
    val latestCanEdit by rememberUpdatedState(canEdit)
    val latestEditable by rememberUpdatedState(editable)
    val latestOnStroke by rememberUpdatedState(onStroke)
    val latestOnEraseMatching by rememberUpdatedState(onEraseMatching)

    fun handlePoint(position: Offset) {
        if (!latestEditable || !latestCanEdit()) { pending = emptyList(); return }
        val size = canvasSize
        if (size.width <= 0 || size.height <= 0) return
        val provider = latestDocument
        val space = if (provider != null) provider() ?: return else null
        val fixedProvider = latestFixedPageSpace
        val fixed = if (fixedProvider != null) fixedProvider() ?: return else null
        val anchor = pendingAnchor?.let { key -> space?.containers?.firstOrNull { it.key == key } }
        if (pendingAnchor != null && anchor == null) return
        val (x, y) = if (anchor != null && space != null) anchor.toLocal(position.x, position.y, space)
        else space?.toDocument(position.x, position.y) ?:
            fixed?.toNormalized(position.x, position.y) ?:
            ((position.x / size.width).coerceIn(0f, 1f) to (position.y / size.height).coerceIn(0f, 1f))
        val tolerance = 0.035f
        if (tool == InkTool.ERASER) {
            latestOnEraseMatching { stroke ->
                if (space == null && fixed == null) stroke.hitTest(x, y, tolerance)
                else {
                    val strokeAnchor = space?.anchorFor(stroke)
                    val hasAnchor = stroke.scrollContainer == null || strokeAnchor != null
                    val insideClip = space == null || strokeAnchor == null || strokeAnchor.viewport(space).contains(position)
                    val reference = fixed?.referenceWidth ?: minOf(size.width, size.height).toFloat()
                    hasAnchor && insideClip && stroke.points.any { point ->
                        val projected = space?.project(stroke, point, strokeAnchor) ?: run {
                            val (px, py) = checkNotNull(fixed).toViewport(point.x, point.y)
                            Offset(px, py)
                        }
                        (projected - position).getDistance() <= tolerance * reference
                    }
                }
            }
        } else if (pending.size < InkStroke.MAX_POINTS) {
            pending = pending + InkPoint(x, y, 1f, System.currentTimeMillis())
        }
    }

    val input = if (editable) Modifier.pointerInput(tool, color, width, editable) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dragging = false
            pending = emptyList()
            pendingAnchor = latestDocument?.invoke()?.anchorAt(down.position.x, down.position.y)?.key
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || event.changes.count { it.pressed } > 1 ||
                    event.changes.any { it.isConsumed } || !latestEditable || !latestCanEdit()) {
                    // A two-finger gesture belongs to PDF zoom. Leave this event unconsumed
                    // so the common page transform can handle it; no partial stroke survives.
                    pending = emptyList()
                    break
                }
                if (!change.pressed) {
                    if (dragging && pending.isNotEmpty() && latestEditable && latestCanEdit()) latestOnStroke(InkStroke(
                        strokeId = UUID.randomUUID().toString(),
                        tool = tool,
                        color = color,
                        width = width,
                        points = pending,
                        createdAt = System.currentTimeMillis(),
                        usesDocumentWidth = latestDocument != null,
                        scrollContainer = pendingAnchor,
                    ))
                    pending = emptyList()
                    break
                }
                if (!dragging && (change.position - down.position).getDistance() >= viewConfiguration.touchSlop) {
                    dragging = true
                    handlePoint(down.position)
                }
                if (dragging) {
                    handlePoint(change.position)
                    change.consume()
                }
            }
        }
    } else Modifier

    Canvas(modifier.fillMaxSize().clipToBounds().onSizeChanged { canvasSize = it }.then(input)) {
        val space = if (document != null) document() ?: return@Canvas else null
        val fixed = if (fixedPageSpace != null) fixedPageSpace() ?: return@Canvas else null
        if (paintSavedStrokes) strokes.forEach { drawStroke(it, canvasSize, space, fixed) }
        if (pending.isNotEmpty()) {
            drawStroke(InkStroke("pending", tool, color, width, pending, 0, space != null, pendingAnchor), canvasSize, space, fixed)
        }
    }
}

private fun DrawScope.drawStroke(stroke: InkStroke, size: IntSize, document: InkDocumentSpace?, fixed: PdfInkCanvasSpace?) {
    if (size.width <= 0 || size.height <= 0) return
    val anchor = document?.anchorFor(stroke)
    if (stroke.scrollContainer != null && anchor == null) return
    val path = Path()
    stroke.points.forEachIndexed { index, point ->
        val projected = document?.project(stroke, point, anchor) ?: run {
            val (x, y) = fixed?.toViewport(point.x, point.y)
                ?: (point.x * size.width to point.y * size.height)
            Offset(x, y)
        }
        if (index == 0) path.moveTo(projected.x, projected.y) else path.lineTo(projected.x, projected.y)
    }
    val paintColor = runCatching { Color(android.graphics.Color.parseColor(stroke.color)) }.getOrDefault(Color.Black)
    val referenceWidth = document?.takeIf { stroke.usesDocumentWidth }?.let {
        it.layoutWidthCss * it.pixelsPerCssPixel
    } ?: fixed?.referenceWidth ?: minOf(size.width, size.height).toFloat()
    val strokeWidth = (stroke.width * referenceWidth).coerceAtLeast(1.5f)
    val style = Stroke(
        width = if (stroke.tool == InkTool.MARKER) strokeWidth * 2.2f else strokeWidth,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
    if (document != null && anchor != null) {
        val clip = anchor.viewport(document)
        clipRect(clip.left, clip.top, clip.right, clip.bottom) { drawPath(path, paintColor, style = style) }
    } else {
        drawPath(path, paintColor, style = style)
    }
}
