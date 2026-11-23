package com.example.myapplication.ui.encyclopedia.atlas

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.encyclopedia.Acupoint
import com.example.myapplication.data.encyclopedia.EncyclopediaPackConstants
import com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot
import com.example.myapplication.data.encyclopedia.RegionView
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 百科插图按需解码内存缓存，限制峰值占用并保证缩放拖动手势流畅。
 */
internal object EncyclopediaBitmapCache {
    private const val MAX_CACHE_KB = 28 * 1024
    private val cache = object : LruCache<String, ImageBitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            ((value.width.toLong() * value.height.toLong() * 4L) / 1024L).toInt().coerceAtLeast(1)
    }

    suspend fun load(assets: AssetManager, relativePath: String, maxDimensionPx: Int = 1400): ImageBitmap? {
        val cacheKey = "$relativePath@$maxDimensionPx"
        synchronized(cache) {
            cache.get(cacheKey)?.let { return it }
        }
        return withContext(Dispatchers.IO) {
            val decoded = runCatching {
                val fullAssetPath = "${EncyclopediaPackConstants.ASSET_ROOT}/${relativePath.trimStart('/')}"
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                assets.open(fullAssetPath).use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                var sampleSize = 1
                val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
                if (maxSide > maxDimensionPx && maxDimensionPx > 0) {
                    while (maxSide / (sampleSize * 2) >= maxDimensionPx) {
                        sampleSize *= 2
                    }
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                assets.open(fullAssetPath).use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOpts)?.asImageBitmap()
                }
            }.getOrNull()
            if (decoded != null) {
                synchronized(cache) {
                    cache.put(cacheKey, decoded)
                }
            }
            decoded
        }
    }
}

@Composable
internal fun rememberEncyclopediaImage(relativePath: String, maxDimensionPx: Int = 1400): ImageBitmap? {
    val context = LocalContext.current
    val assets = context.assets
    val state by produceState<ImageBitmap?>(initialValue = null, relativePath, maxDimensionPx, assets) {
        value = EncyclopediaBitmapCache.load(assets, relativePath, maxDimensionPx)
    }
    return state
}

private fun encyclopediaArtworkColorFilter(dark: Boolean): ColorFilter? {
    if (!dark) return null
    val satMatrix = ColorMatrix().apply { setToSaturation(0.75f) }
    val scaleMatrix = ColorMatrix(
        floatArrayOf(
            0.84f, 0f, 0f, 0f, 0f,
            0f, 0.84f, 0f, 0f, 0f,
            0f, 0f, 0.84f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    satMatrix.timesAssign(scaleMatrix)
    return ColorFilter.colorMatrix(satMatrix)
}

/**
 * 解析离线资料包中经络路线与局部图引导线使用的 SVG Path（支持 M/m/L/l/H/h/V/v/Q/q/T/t/C/c/Z/z）。
 */
internal fun parseEncyclopediaSvgPath(
    pathData: String,
    mapper: (Float, Float) -> Pair<Float, Float> = { x, y -> x to y },
): Path {
    val result = Path()
    if (pathData.isBlank()) return result
    val tokens = SVG_TOKEN_REGEX.findAll(pathData).map { it.value }.toList()
    var index = 0
    var currentCmd = ' '
    var curX = 0f
    var curY = 0f
    var startX = 0f
    var startY = 0f
    var lastQuadCtrlX = 0f
    var lastQuadCtrlY = 0f
    var hasLastQuad = false

    fun nextFloat(): Float? = tokens.getOrNull(index)?.toFloatOrNull()?.also { index++ }
    fun peekIsNumber(): Boolean = tokens.getOrNull(index)?.toFloatOrNull() != null

    while (index < tokens.size) {
        val tok = tokens[index]
        if (tok.length == 1 && tok[0].isLetter()) {
            currentCmd = tok[0]
            index++
            if (currentCmd == 'Z' || currentCmd == 'z') {
                result.close()
                curX = startX
                curY = startY
                hasLastQuad = false
                continue
            }
        } else if (currentCmd == ' ') {
            index++
            continue
        }

        when (currentCmd) {
            'M', 'm' -> {
                val nx = nextFloat() ?: break
                val ny = nextFloat() ?: break
                if (currentCmd == 'm') {
                    curX += nx
                    curY += ny
                } else {
                    curX = nx
                    curY = ny
                }
                startX = curX
                startY = curY
                val (mx, my) = mapper(curX, curY)
                result.moveTo(mx, my)
                hasLastQuad = false
                currentCmd = if (currentCmd == 'm') 'l' else 'L'
            }
            'L', 'l' -> {
                do {
                    val nx = nextFloat() ?: break
                    val ny = nextFloat() ?: break
                    if (currentCmd == 'l') {
                        curX += nx
                        curY += ny
                    } else {
                        curX = nx
                        curY = ny
                    }
                    val (lx, ly) = mapper(curX, curY)
                    result.lineTo(lx, ly)
                    hasLastQuad = false
                } while (peekIsNumber())
            }
            'H', 'h' -> {
                do {
                    val nx = nextFloat() ?: break
                    curX = if (currentCmd == 'h') curX + nx else nx
                    val (lx, ly) = mapper(curX, curY)
                    result.lineTo(lx, ly)
                    hasLastQuad = false
                } while (peekIsNumber())
            }
            'V', 'v' -> {
                do {
                    val ny = nextFloat() ?: break
                    curY = if (currentCmd == 'v') curY + ny else ny
                    val (lx, ly) = mapper(curX, curY)
                    result.lineTo(lx, ly)
                    hasLastQuad = false
                } while (peekIsNumber())
            }
            'Q', 'q' -> {
                do {
                    val cx = nextFloat() ?: break
                    val cy = nextFloat() ?: break
                    val nx = nextFloat() ?: break
                    val ny = nextFloat() ?: break
                    val absCx = if (currentCmd == 'q') curX + cx else cx
                    val absCy = if (currentCmd == 'q') curY + cy else cy
                    val absX = if (currentCmd == 'q') curX + nx else nx
                    val absY = if (currentCmd == 'q') curY + ny else ny
                    val (mcx, mcy) = mapper(absCx, absCy)
                    val (mx, my) = mapper(absX, absY)
                    result.quadraticTo(mcx, mcy, mx, my)
                    lastQuadCtrlX = absCx
                    lastQuadCtrlY = absCy
                    curX = absX
                    curY = absY
                    hasLastQuad = true
                } while (peekIsNumber())
            }
            'T', 't' -> {
                do {
                    val nx = nextFloat() ?: break
                    val ny = nextFloat() ?: break
                    val absCx = if (hasLastQuad) 2f * curX - lastQuadCtrlX else curX
                    val absCy = if (hasLastQuad) 2f * curY - lastQuadCtrlY else curY
                    val absX = if (currentCmd == 't') curX + nx else nx
                    val absY = if (currentCmd == 't') curY + ny else ny
                    val (mcx, mcy) = mapper(absCx, absCy)
                    val (mx, my) = mapper(absX, absY)
                    result.quadraticTo(mcx, mcy, mx, my)
                    lastQuadCtrlX = absCx
                    lastQuadCtrlY = absCy
                    curX = absX
                    curY = absY
                    hasLastQuad = true
                } while (peekIsNumber())
            }
            'C', 'c' -> {
                do {
                    val c1x = nextFloat() ?: break
                    val c1y = nextFloat() ?: break
                    val c2x = nextFloat() ?: break
                    val c2y = nextFloat() ?: break
                    val nx = nextFloat() ?: break
                    val ny = nextFloat() ?: break
                    val rel = currentCmd == 'c'
                    val a1x = if (rel) curX + c1x else c1x
                    val a1y = if (rel) curY + c1y else c1y
                    val a2x = if (rel) curX + c2x else c2x
                    val a2y = if (rel) curY + c2y else c2y
                    val ax = if (rel) curX + nx else nx
                    val ay = if (rel) curY + ny else ny
                    val (m1x, m1y) = mapper(a1x, a1y)
                    val (m2x, m2y) = mapper(a2x, a2y)
                    val (mx, my) = mapper(ax, ay)
                    result.cubicTo(m1x, m1y, m2x, m2y, mx, my)
                    curX = ax
                    curY = ay
                    hasLastQuad = false
                } while (peekIsNumber())
            }
            else -> {
                index++
                hasLastQuad = false
            }
        }
    }
    return result
}

private val SVG_TOKEN_REGEX = Regex("[A-Za-z]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?")

/**
 * 全身经络与单经正面/背面/侧面图谱交互画布：统一变换彩铅底图、循行线条、引线与标注，支持 1-3 倍手势缩放与平移。
 */
@Composable
internal fun FullBodyAtlasCanvas(
    snapshot: EncyclopediaSnapshot,
    scene: AtlasSceneLayout,
    artworkPath: String,
    camera: AtlasCameraState,
    onCameraChange: (AtlasCameraState) -> Unit,
    onTapPoint: (String, List<PlacedAtlasMarker>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = rememberEncyclopediaImage(artworkPath)
    val dark = UiRoles.dark
    val accent = UiRoles.accent
    val fgColor = UiRoles.foreground
    val subtleColor = UiRoles.color(UiColorRole.OnSurfaceVariant)
    val labelOutlineColor = if (dark) Color(0xD916191D) else Color(0xEBFFFFFF)
    val colorFilter = remember(dark) { encyclopediaArtworkColorFilter(dark) }
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val latestCamera by rememberUpdatedState(camera)
    val latestScene by rememberUpdatedState(scene)
    val latestOnCameraChange by rememberUpdatedState(onCameraChange)
    val latestOnTapPoint by rememberUpdatedState(onTapPoint)

    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val transform = AtlasCameraMath.transform(
            frame = AtlasFrames.Body,
            camera = camera,
            viewportWidth = widthPx,
            viewportHeight = heightPx,
        )

        val labelPaint = remember(fgColor, density.density) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { 10.5.dp.toPx() }
                isFakeBoldText = true
            }
        }
        val haloPaint = remember(labelOutlineColor, density.density) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { 10.5.dp.toPx() }
                isFakeBoldText = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = with(density) { 2.6.dp.toPx() }
                strokeJoin = android.graphics.Paint.Join.ROUND
                color = labelOutlineColor.toArgb()
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("atlas-canvas")
                .pointerInput(widthPx, heightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var currentCam = latestCamera
                        var moved = false
                        var totalPan = Offset.Zero
                        val downPos = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (pressed.size >= 2 || kotlin.math.abs(zoomChange - 1f) > 0.005f) {
                                moved = true
                                currentCam = AtlasCameraMath.zoomByPinch(
                                    frame = AtlasFrames.Body,
                                    current = currentCam,
                                    centroidScreenX = centroid.x,
                                    centroidScreenY = centroid.y,
                                    panScreenDx = panChange.x,
                                    panScreenDy = panChange.y,
                                    zoomFactor = zoomChange,
                                    viewportWidth = widthPx,
                                    viewportHeight = heightPx,
                                )
                                latestOnCameraChange(currentCam)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (currentCam.zoom > 1.01f) {
                                totalPan += panChange
                                if (!moved && totalPan.getDistance() > touchSlop) moved = true
                                if (moved && (panChange.x != 0f || panChange.y != 0f)) {
                                    currentCam = AtlasCameraMath.panByScreenDelta(
                                        frame = AtlasFrames.Body,
                                        current = currentCam,
                                        screenDeltaX = panChange.x,
                                        screenDeltaY = panChange.y,
                                        viewportWidth = widthPx,
                                        viewportHeight = heightPx,
                                    )
                                    latestOnCameraChange(currentCam)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } else {
                                totalPan += panChange
                                if (totalPan.getDistance() > touchSlop) moved = true
                            }
                        }
                        if (!moved) {
                            val currentTransform = AtlasCameraMath.transform(
                                frame = AtlasFrames.Body,
                                camera = latestCamera,
                                viewportWidth = widthPx,
                                viewportHeight = heightPx,
                            )
                            val hit = AtlasLayoutEngine.hitTest(
                                markers = latestScene.visibleMarkers,
                                transform = currentTransform,
                                tapScreenX = downPos.x,
                                tapScreenY = downPos.y,
                                density = density.density,
                                isOverview = latestScene.isOverview,
                            )
                            if (hit != null) {
                                latestOnTapPoint(hit.primary.pointCode, hit.nearbyCandidates)
                            }
                        }
                    }
                },
        ) {
            if (image != null) {
                val (left, top) = transform.frameToScreen(AtlasFrames.BodyArtwork.left, AtlasFrames.BodyArtwork.top)
                val (right, bottom) = transform.frameToScreen(
                    AtlasFrames.BodyArtwork.left + AtlasFrames.BodyArtwork.width,
                    AtlasFrames.BodyArtwork.top + AtlasFrames.BodyArtwork.height,
                )
                drawImage(
                    image = image,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize((right - left).roundToInt().coerceAtLeast(1), (bottom - top).roundToInt().coerceAtLeast(1)),
                    colorFilter = colorFilter,
                )
            }

            if (scene.isLungFrontSpecial) {
                val mapper: (Float, Float) -> Pair<Float, Float> = { fx, fy -> transform.frameToScreen(fx, fy) }
                scene.lungMainPath?.let { pathStr ->
                    drawPath(
                        path = parseEncyclopediaSvgPath(pathStr, mapper),
                        color = accent.copy(alpha = 0.88f),
                        style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                scene.lungBranchPath?.let { pathStr ->
                    drawPath(
                        path = parseEncyclopediaSvgPath(pathStr, mapper),
                        color = accent.copy(alpha = 0.72f),
                        style = Stroke(
                            width = 2.0.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f),
                        ),
                    )
                }
                scene.lungDirectionPath?.let { pathStr ->
                    drawPath(
                        path = parseEncyclopediaSvgPath(pathStr, mapper),
                        color = accent,
                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            } else if (scene.routeSegments.isNotEmpty()) {
                scene.routeSegments.forEach { seg ->
                    val (sx, sy) = transform.frameToScreen(seg.startX, seg.startY)
                    val (ex, ey) = transform.frameToScreen(seg.endX, seg.endY)
                    drawLine(
                        color = accent.copy(alpha = 0.65f),
                        start = Offset(sx, sy),
                        end = Offset(ex, ey),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }

            scene.visibleMarkers.forEach { marker ->
                val (sx, sy) = transform.frameToScreen(marker.frameX, marker.frameY)
                val (lx, ly) = transform.frameToScreen(marker.labelFrameX, marker.labelFrameY)
                val (lex, ley) = transform.frameToScreen(marker.leaderEndFrameX, marker.leaderEndFrameY)

                val leaderDist = hypot((lex - sx).toDouble(), (ley - sy).toDouble())
                if (leaderDist > 3.0) {
                    drawLine(
                        color = if (marker.selected) accent else subtleColor.copy(alpha = 0.65f),
                        start = Offset(sx, sy),
                        end = Offset(lex, ley),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                val ringRadius = if (marker.selected) 8.5.dp.toPx() else if (scene.isOverview) 4.8.dp.toPx() else 6.5.dp.toPx()
                val dotRadius = if (marker.selected) 4.dp.toPx() else if (scene.isOverview) 2.5.dp.toPx() else 3.4.dp.toPx()
                drawCircle(
                    color = if (marker.selected) accent.copy(alpha = 0.28f)
                    else if (marker.isProjected) subtleColor.copy(alpha = 0.16f)
                    else accent.copy(alpha = 0.14f),
                    radius = ringRadius,
                    center = Offset(sx, sy),
                )
                drawCircle(
                    color = if (marker.selected) accent
                    else if (marker.isProjected) subtleColor
                    else if (dark) Color.White else accent,
                    radius = dotRadius,
                    center = Offset(sx, sy),
                )
                drawCircle(
                    color = if (dark) Color(0xFF141815) else Color.White,
                    radius = dotRadius * 0.42f,
                    center = Offset(sx, sy),
                )

                labelPaint.color = (if (marker.selected) accent else fgColor).toArgb()
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(marker.pointName, lx, ly, haloPaint)
                    canvas.nativeCanvas.drawText(marker.pointName, lx, ly, labelPaint)
                }
            }
        }

        // 无障碍与测试点击命中锚点：为当前可见标点暴露名称、代码与选中语义，避免全屏不可达
        scene.visibleMarkers.forEach { marker ->
            val (sx, sy) = transform.frameToScreen(marker.frameX, marker.frameY)
            if (sx in 0f..widthPx && sy in 0f..heightPx) {
                val hitSizePx = with(density) { 28.dp.toPx() }
                val point = snapshot.pointsByCode[marker.pointCode]
                val desc = "${marker.pointName} ${marker.pointCode}" +
                    if (point != null) "，${snapshot.meridiansByCode[point.meridianCode]?.shortName.orEmpty()}" else ""
                Box(
                    modifier = Modifier
                        .offset { IntOffset((sx - hitSizePx / 2f).roundToInt(), (sy - hitSizePx / 2f).roundToInt()) }
                        .size(28.dp)
                        .semantics {
                            contentDescription = desc
                            role = Role.Button
                            semanticsSelected = marker.selected
                        }
                        .clickable { onTapPoint(marker.pointCode, listOf(marker)) }
                        .testTag("atlas-point-${marker.pointCode}"),
                )
            }
        }
    }
}

/**
 * 穴位详情内嵌局部预览画布：只标当前穴位单点，共用局部底图与标点坐标（360x200 比例）。
 */
@Composable
internal fun AcupointSinglePreviewCanvas(
    point: Acupoint,
    region: RegionView,
    modifier: Modifier = Modifier,
) {
    val image = rememberEncyclopediaImage(region.artwork, maxDimensionPx = 1100)
    val dark = UiRoles.dark
    val accent = UiRoles.accent
    val fgColor = UiRoles.foreground
    val surfaceBg = UiRoles.surface(UiSurfaceRole.Block)
    val outline = UiRoles.color(UiColorRole.OutlineVariant)
    val labelHaloColor = if (dark) Color(0xE016191D) else Color(0xEBFFFFFF)
    val colorFilter = remember(dark) { encyclopediaArtworkColorFilter(dark) }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(surfaceBg)
            .border(1.dp, outline, RoundedCornerShape(16.dp))
            .semantics { contentDescription = "${point.name} ${point.code} 的体表局部定位图" }
            .testTag("acupoint-single-preview-${point.code}"),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val transform = AtlasCameraMath.transform(
            frame = AtlasFrames.Region,
            camera = AtlasCameraState(),
            viewportWidth = widthPx,
            viewportHeight = heightPx,
        )
        val namePaint = remember(fgColor, density.density) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { 12.dp.toPx() }
                isFakeBoldText = true
                color = fgColor.toArgb()
            }
        }
        val codePaint = remember(accent, density.density) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { 10.dp.toPx() }
                isFakeBoldText = true
                color = accent.toArgb()
            }
        }
        val haloPaint = remember(labelHaloColor, density.density) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { 12.dp.toPx() }
                isFakeBoldText = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = with(density) { 3.dp.toPx() }
                strokeJoin = android.graphics.Paint.Join.ROUND
                color = labelHaloColor.toArgb()
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            if (image != null) {
                val (left, top) = transform.frameToScreen(AtlasFrames.RegionArtwork.left, AtlasFrames.RegionArtwork.top)
                val (right, bottom) = transform.frameToScreen(
                    AtlasFrames.RegionArtwork.left + AtlasFrames.RegionArtwork.width,
                    AtlasFrames.RegionArtwork.top + AtlasFrames.RegionArtwork.height,
                )
                drawImage(
                    image = image,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize((right - left).roundToInt().coerceAtLeast(1), (bottom - top).roundToInt().coerceAtLeast(1)),
                    colorFilter = colorFilter,
                )
            }

            val (sx, sy) = transform.frameToScreen(region.marker.xFloat, region.marker.yFloat)
            val (lx, ly) = transform.frameToScreen(region.marker.labelXFloat, region.marker.labelYFloat)
            drawLine(
                color = accent,
                start = Offset(sx, sy),
                end = Offset(lx - 4.dp.toPx(), ly - 3.dp.toPx()),
                strokeWidth = 1.4.dp.toPx(),
            )
            drawCircle(color = accent.copy(alpha = 0.28f), radius = 10.dp.toPx(), center = Offset(sx, sy))
            drawCircle(color = accent, radius = 4.6.dp.toPx(), center = Offset(sx, sy))
            drawCircle(color = Color.White, radius = 1.9.dp.toPx(), center = Offset(sx, sy))

            val safeLabelX = lx.coerceIn(10.dp.toPx(), (widthPx - 64.dp.toPx()).coerceAtLeast(12.dp.toPx()))
            val safeLabelY = ly.coerceIn(16.dp.toPx(), (heightPx - 18.dp.toPx()).coerceAtLeast(20.dp.toPx()))
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(point.name, safeLabelX, safeLabelY, haloPaint)
                canvas.nativeCanvas.drawText(point.name, safeLabelX, safeLabelY, namePaint)
                canvas.nativeCanvas.drawText(point.code, safeLabelX, safeLabelY + 12.dp.toPx(), haloPaint.apply { textSize = codePaint.textSize })
                canvas.nativeCanvas.drawText(point.code, safeLabelX, safeLabelY + 12.dp.toPx(), codePaint)
            }
        }
    }
}

/**
 * 完整局部图互动画布：显示引导线、骨性解剖标志、同图整组穴位标点并支持 1-3 倍缩放与组内选穴。
 */
@Composable
internal fun LocalRegionAtlasCanvas(
    snapshot: EncyclopediaSnapshot,
    region: RegionView,
    selectedPointCode: String,
    selectedActive: Boolean,
    camera: AtlasCameraState,
    onCameraChange: (AtlasCameraState) -> Unit,
    onSelectPoint: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = rememberEncyclopediaImage(region.artwork)
    val dark = UiRoles.dark
    val accent = UiRoles.accent
    val fgColor = UiRoles.foreground
    val subtleColor = UiRoles.color(UiColorRole.OnSurfaceVariant)
    val labelHaloColor = if (dark) Color(0xE016191D) else Color(0xF0FFFFFF)
    val colorFilter = remember(dark) { encyclopediaArtworkColorFilter(dark) }
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val latestCamera by rememberUpdatedState(camera)
    val latestOnCameraChange by rememberUpdatedState(onCameraChange)
    val latestOnSelectPoint by rememberUpdatedState(onSelectPoint)

    val groupCodes = remember(region) { region.points.ifEmpty { listOf(region.pointCode) } }
    val denseLabelsOnlySelected = region.compactLabels || groupCodes.size >= 8

    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val transform = AtlasCameraMath.transform(
            frame = AtlasFrames.Region,
            camera = camera,
            viewportWidth = widthPx,
            viewportHeight = heightPx,
        )
        val latestTransform by rememberUpdatedState(transform)

        val landmarkPaint = remember(subtleColor, density.density) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { 10.dp.toPx() }
                color = subtleColor.toArgb()
            }
        }
        val pointNamePaint = remember(fgColor, density.density) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { 11.dp.toPx() }
                isFakeBoldText = true
            }
        }
        val codePaint = remember(accent, density.density) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { 9.5.dp.toPx() }
                isFakeBoldText = true
            }
        }
        val haloPaint = remember(labelHaloColor, density.density) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { 11.dp.toPx() }
                isFakeBoldText = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = with(density) { 2.8.dp.toPx() }
                strokeJoin = android.graphics.Paint.Join.ROUND
                color = labelHaloColor.toArgb()
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("local-region-canvas")
                .pointerInput(widthPx, heightPx, groupCodes) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var currentCam = latestCamera
                        var moved = false
                        var totalPan = Offset.Zero
                        val downPos = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (pressed.size >= 2 || kotlin.math.abs(zoomChange - 1f) > 0.005f) {
                                moved = true
                                currentCam = AtlasCameraMath.zoomByPinch(
                                    frame = AtlasFrames.Region,
                                    current = currentCam,
                                    centroidScreenX = centroid.x,
                                    centroidScreenY = centroid.y,
                                    panScreenDx = panChange.x,
                                    panScreenDy = panChange.y,
                                    zoomFactor = zoomChange,
                                    viewportWidth = widthPx,
                                    viewportHeight = heightPx,
                                )
                                latestOnCameraChange(currentCam)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (currentCam.zoom > 1.01f) {
                                totalPan += panChange
                                if (!moved && totalPan.getDistance() > touchSlop) moved = true
                                if (moved && (panChange.x != 0f || panChange.y != 0f)) {
                                    currentCam = AtlasCameraMath.panByScreenDelta(
                                        frame = AtlasFrames.Region,
                                        current = currentCam,
                                        screenDeltaX = panChange.x,
                                        screenDeltaY = panChange.y,
                                        viewportWidth = widthPx,
                                        viewportHeight = heightPx,
                                    )
                                    latestOnCameraChange(currentCam)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } else {
                                totalPan += panChange
                                if (totalPan.getDistance() > touchSlop) moved = true
                            }
                        }
                        if (!moved) {
                            val (tapFx, tapFy) = latestTransform.screenToFrame(downPos.x, downPos.y)
                            val maxDist = 22f * density.density / latestTransform.scale.coerceAtLeast(0.1f)
                            var bestCode: String? = null
                            var bestDist = maxDist
                            groupCodes.forEach { code ->
                                val r = snapshot.regionByPointCode[code] ?: return@forEach
                                val d = hypot((tapFx - r.marker.xFloat).toDouble(), (tapFy - r.marker.yFloat).toDouble()).toFloat()
                                if (d <= bestDist) {
                                    bestDist = d
                                    bestCode = code
                                }
                            }
                            bestCode?.let(latestOnSelectPoint)
                        }
                    }
                },
        ) {
            val mapper: (Float, Float) -> Pair<Float, Float> = { fx, fy -> transform.frameToScreen(fx, fy) }
            if (image != null) {
                val (left, top) = mapper(AtlasFrames.RegionArtwork.left, AtlasFrames.RegionArtwork.top)
                val (right, bottom) = mapper(
                    AtlasFrames.RegionArtwork.left + AtlasFrames.RegionArtwork.width,
                    AtlasFrames.RegionArtwork.top + AtlasFrames.RegionArtwork.height,
                )
                drawImage(
                    image = image,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize((right - left).roundToInt().coerceAtLeast(1), (bottom - top).roundToInt().coerceAtLeast(1)),
                    colorFilter = colorFilter,
                )
            }

            region.guide?.takeIf(String::isNotBlank)?.let { guidePath ->
                drawPath(
                    path = parseEncyclopediaSvgPath(guidePath, mapper),
                    color = accent.copy(alpha = 0.6f),
                    style = Stroke(
                        width = 1.6.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()), 0f),
                    ),
                )
            }

            region.labels.forEach { landmark ->
                if (landmark.leaderPath.isNotBlank()) {
                    drawPath(
                        path = parseEncyclopediaSvgPath(landmark.leaderPath, mapper),
                        color = subtleColor.copy(alpha = 0.5f),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
                val (lx, ly) = mapper(landmark.x.toFloat(), landmark.y.toFloat())
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(landmark.text, lx, ly, landmarkPaint)
                }
            }

            groupCodes.forEach { code ->
                val point = snapshot.pointsByCode[code] ?: return@forEach
                val pointRegion = snapshot.regionByPointCode[code] ?: return@forEach
                val isSelected = selectedActive && code == selectedPointCode
                val (sx, sy) = mapper(pointRegion.marker.xFloat, pointRegion.marker.yFloat)
                val showLabel = !denseLabelsOnlySelected || isSelected

                if (showLabel) {
                    val (lx, ly) = mapper(pointRegion.marker.labelXFloat, pointRegion.marker.labelYFloat)
                    drawLine(
                        color = if (isSelected) accent else subtleColor.copy(alpha = 0.7f),
                        start = Offset(sx, sy),
                        end = Offset(lx - 3.dp.toPx(), ly - 2.dp.toPx()),
                        strokeWidth = 1.1.dp.toPx(),
                    )
                    pointNamePaint.color = (if (isSelected) accent else fgColor).toArgb()
                    codePaint.color = (if (isSelected) accent else subtleColor).toArgb()
                    drawIntoCanvas { canvas ->
                        haloPaint.textSize = pointNamePaint.textSize
                        canvas.nativeCanvas.drawText(point.name, lx, ly, haloPaint)
                        canvas.nativeCanvas.drawText(point.name, lx, ly, pointNamePaint)
                        haloPaint.textSize = codePaint.textSize
                        canvas.nativeCanvas.drawText(point.code, lx, ly + 11.dp.toPx(), haloPaint)
                        canvas.nativeCanvas.drawText(point.code, lx, ly + 11.dp.toPx(), codePaint)
                    }
                }

                drawCircle(
                    color = if (isSelected) accent.copy(alpha = 0.30f) else accent.copy(alpha = 0.14f),
                    radius = if (isSelected) 9.dp.toPx() else 6.dp.toPx(),
                    center = Offset(sx, sy),
                )
                drawCircle(
                    color = if (isSelected) accent else if (dark) Color.White else accent.copy(alpha = 0.85f),
                    radius = if (isSelected) 4.4.dp.toPx() else 3.2.dp.toPx(),
                    center = Offset(sx, sy),
                )
                drawCircle(
                    color = if (dark) Color(0xFF141815) else Color.White,
                    radius = if (isSelected) 1.8.dp.toPx() else 1.2.dp.toPx(),
                    center = Offset(sx, sy),
                )
            }
        }

        groupCodes.forEach { code ->
            val point = snapshot.pointsByCode[code] ?: return@forEach
            val pointRegion = snapshot.regionByPointCode[code] ?: return@forEach
            val (sx, sy) = transform.frameToScreen(pointRegion.marker.xFloat, pointRegion.marker.yFloat)
            if (sx in 0f..widthPx && sy in 0f..heightPx) {
                val hitPx = with(density) { 28.dp.toPx() }
                val isSelected = selectedActive && code == selectedPointCode
                Box(
                    modifier = Modifier
                        .offset { IntOffset((sx - hitPx / 2f).roundToInt(), (sy - hitPx / 2f).roundToInt()) }
                        .size(28.dp)
                        .semantics {
                            contentDescription = "${point.name} ${point.code}"
                            role = Role.Button
                            semanticsSelected = isSelected
                        }
                        .clickable { onSelectPoint(code) }
                        .testTag("region-point-$code"),
                )
            }
        }
    }
}
