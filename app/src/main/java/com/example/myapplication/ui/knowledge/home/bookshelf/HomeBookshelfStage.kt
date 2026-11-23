package com.example.myapplication.ui.knowledge.home.bookshelf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlin.math.roundToInt

@Composable
internal fun HomeShelfStage(
    motion: ShelfInteraction,
    metrics: ShelfMetrics,
    rtl: Boolean,
    modifier: Modifier,
    coverLoading: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val palette = ShelfPalette(
        floor = UiRoles.surface(UiSurfaceRole.Block),
        paper = UiRoles.surface(UiSurfaceRole.Card),
        courseTint = colors.primary,
        importTint = colors.tertiary,
        text = UiRoles.foreground,
        edge = colors.outline,
        shadow = if (UiRoles.dark) colors.scrim else colors.onSurface,
    )
    val titleStyle = UiRoles.text(MaterialTheme.typography.labelMedium).copy(
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val sourceStyle = UiRoles.text(MaterialTheme.typography.labelSmall).copy(
        fontSize = 10.sp,
        lineHeight = 12.sp,
    )
    val textMeasurer = rememberTextMeasurer(cacheSize = ShelfEntryLimit * 2)
    val covers = shelfCovers(motion.entries, motion.commandIndex, coverLoading)
    Canvas(modifier = modifier) {
        val entries = motion.entries
        val poses = metrics.visiblePoses(entries.size, motion.q)
        clipRect {
            drawShelfSurface(metrics, palette)
            for (pose in poses) drawContactShadow(metrics, pose, palette.shadow, rtl)
            val matrix = Matrix()
            val values = FloatArray(9)
            val facePath = Path()
            for (pose in poses) {
                drawBook(
                    pose, entries[pose.index], metrics, rtl, palette, textMeasurer,
                    titleStyle, sourceStyle, matrix, values, facePath, covers[entries[pose.index].key],
                )
            }
        }
    }
}

private data class ShelfPalette(
    val floor: Color,
    val paper: Color,
    val courseTint: Color,
    val importTint: Color,
    val text: Color,
    val edge: Color,
    val shadow: Color,
)

private fun DrawScope.drawShelfSurface(metrics: ShelfMetrics, palette: ShelfPalette) {
    val d = metrics.density
    val floorBack = metrics.floorY - 23f * d
    val floorFront = metrics.floorY + (metrics.shadowDepth - 2f * d)
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(palette.floor.copy(alpha = 0f), palette.floor.copy(alpha = .9f)),
            startY = floorBack - 24f * d,
            endY = floorFront,
        ),
        topLeft = Offset(0f, floorBack - 24f * d),
        size = Size(size.width, floorFront - floorBack + 24f * d),
    )
    drawLine(
        brush = Brush.horizontalGradient(
            0f to palette.edge.copy(alpha = 0f),
            .18f to palette.edge.copy(alpha = .30f),
            .82f to palette.edge.copy(alpha = .30f),
            1f to palette.edge.copy(alpha = 0f),
        ),
        start = Offset(0f, floorFront),
        end = Offset(size.width, floorFront),
        strokeWidth = d,
    )
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(palette.floor, palette.floor.copy(alpha = 0f)),
            startY = floorFront,
            endY = metrics.stageHeight,
        ),
        topLeft = Offset(0f, floorFront),
        size = Size(size.width, metrics.stageHeight - floorFront),
    )
}

private fun DrawScope.drawContactShadow(metrics: ShelfMetrics, pose: ShelfPose, shadow: Color, rtl: Boolean) {
    val d = metrics.density
    val center = metrics.screenPoint(
        ShelfPoint((pose.minX + pose.maxX) * .5f, metrics.floorY - (3f + 7f * pose.weight) * d),
        rtl,
    )
    val width = (pose.maxX - pose.minX) * (1f + .12f * pose.weight)
    val height = (8f + 4f * pose.weight) * d
    drawOval(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, shadow.copy(alpha = .22f - .06f * pose.weight), Color.Transparent),
            startY = center.y - height / 2f,
            endY = center.y + height / 2f,
        ),
        topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
        size = Size(width, height),
    )
}

private fun DrawScope.drawBook(
    pose: ShelfPose,
    entry: HomeShelfEntry,
    metrics: ShelfMetrics,
    rtl: Boolean,
    palette: ShelfPalette,
    text: TextMeasurer,
    titleStyle: TextStyle,
    sourceStyle: TextStyle,
    matrix: Matrix,
    values: FloatArray,
    path: Path,
    texture: Bitmap?,
) {
    val course = entry.key.startsWith("course:")
    val tint = if (course) palette.courseTint else palette.importTint
    val strength = if (entry.canOpen) .16f + .15f * pose.weight else .08f
    val paper = lerp(palette.paper, tint, strength).copy(alpha = 1f)
    val cloth = lerp(palette.paper, tint, .53f + .19f * pose.weight).copy(alpha = 1f)
    val page = lerp(palette.paper, palette.text, .045f).copy(alpha = 1f)
    val edge = lerp(palette.edge, tint, pose.weight).copy(alpha = .40f + .40f * pose.weight)
    val d = metrics.density
    val cover = pose.cover.screen(metrics.width, rtl)
    val top = pose.top.screen(metrics.width, rtl)
    val spine = pose.spine.screen(metrics.width, rtl)

    drawPlane(cover, paper, path)
    drawCoverArt(cover, rtl, metrics, titleStyle, sourceStyle, palette.text, text, entry, course, matrix, values, texture)
    drawPlaneOutline(cover, edge, .65f * d, path)

    drawPlane(top, page, path)
    for (position in listOf(.24f, .47f, .70f)) {
        drawLine(
            color = palette.edge.copy(alpha = .31f),
            start = top.map(position, .10f).offset,
            end = top.map(position, .91f).offset,
            strokeWidth = .55f * d,
            cap = StrokeCap.Round,
        )
    }
    drawPlaneOutline(top, edge, .65f * d, path)

    drawPlane(spine, cloth, path)
    drawLine(
        color = lerp(tint, palette.text, .30f).copy(alpha = .5f),
        start = spine.map(.20f, .04f).offset,
        end = spine.map(.20f, .96f).offset,
        strokeWidth = .80f * d,
        cap = StrokeCap.Round,
    )
    drawPlaneOutline(spine, edge, (.75f + .30f * pose.weight) * d, path)
}

private fun DrawScope.drawCoverArt(
    cover: ShelfPlane,
    rtl: Boolean,
    metrics: ShelfMetrics,
    titleStyle: TextStyle,
    sourceStyle: TextStyle,
    color: Color,
    text: TextMeasurer,
    entry: HomeShelfEntry,
    course: Boolean,
    matrix: Matrix,
    values: FloatArray,
    texture: Bitmap?,
) {
    val w = metrics.artWidth
    val h = metrics.bodyHeight
    val d = metrics.density
    val direction = if (rtl) -1f else 1f
    val origin = if (rtl) cover.origin + cover.u else cover.origin
    values[0] = cover.u.x * direction / w
    values[1] = cover.v.x / h
    values[2] = origin.x
    values[3] = cover.u.y * direction / w
    values[4] = cover.v.y / h
    values[5] = origin.y
    values[8] = 1f
    matrix.setValues(values)

    if (texture != null) {
        val scale = minOf(w / texture.width, h / texture.height)
        val left = (w - texture.width * scale) / 2f
        val top = (h - texture.height * scale) / 2f
        drawIntoCanvas { canvas ->
            canvas.save()
            try {
                canvas.nativeCanvas.concat(matrix)
                canvas.nativeCanvas.clipRect(0f, 0f, w, h)
                canvas.nativeCanvas.drawBitmap(
                    texture, null, RectF(left, top, w - left, h - top), Paint(Paint.FILTER_BITMAP_FLAG),
                )
            } finally {
                canvas.restore()
            }
        }
        return
    }

    val inner = Constraints(maxWidth = (w - 18f * d).roundToInt().coerceAtLeast(1))
    val title = text.measure(entry.title, titleStyle, TextOverflow.Ellipsis, true, 4, constraints = inner)
    val source = text.measure(
        if (course) "内置课程" else "个人书库",
        sourceStyle,
        maxLines = 1,
        constraints = inner,
    )
    drawIntoCanvas { canvas ->
        canvas.save()
        try {
            canvas.nativeCanvas.concat(matrix)
            canvas.nativeCanvas.clipRect(0f, 0f, w, h)
            val sourceX = if (rtl) w - 9f * d - source.size.width else 9f * d
            val titleX = if (rtl) w - 9f * d - title.size.width else 9f * d
            drawText(source, color.copy(alpha = .76f), topLeft = Offset(sourceX, 12f * d))
            drawText(title, color, topLeft = Offset(titleX, 33f * d))
        } finally {
            canvas.restore()
        }
    }
}

private val ShelfPoint.offset: Offset get() = Offset(x, y)

private fun ShelfPlane.writePath(path: Path) {
    path.rewind()
    path.moveTo(origin.x, origin.y)
    val right = origin + u
    val opposite = right + v
    val bottom = origin + v
    path.lineTo(right.x, right.y)
    path.lineTo(opposite.x, opposite.y)
    path.lineTo(bottom.x, bottom.y)
    path.close()
}

private fun DrawScope.drawPlane(plane: ShelfPlane, color: Color, path: Path) {
    plane.writePath(path)
    drawPath(path, color)
}

private fun DrawScope.drawPlaneOutline(plane: ShelfPlane, color: Color, width: Float, path: Path) {
    plane.writePath(path)
    drawPath(path, color, style = Stroke(width))
}
