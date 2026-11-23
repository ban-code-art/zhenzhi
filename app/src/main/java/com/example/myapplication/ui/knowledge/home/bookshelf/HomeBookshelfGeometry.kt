package com.example.myapplication.ui.knowledge.home.bookshelf

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

internal const val ShelfEntryLimit = 24

internal data class ShelfPoint(val x: Float, val y: Float) {
    operator fun plus(other: ShelfPoint) = ShelfPoint(x + other.x, y + other.y)
    operator fun minus(other: ShelfPoint) = ShelfPoint(x - other.x, y - other.y)
}

internal data class ShelfPlane(
    val origin: ShelfPoint,
    val u: ShelfPoint,
    val v: ShelfPoint,
) {
    fun map(a: Float, b: Float) = ShelfPoint(
        origin.x + a * u.x + b * v.x,
        origin.y + a * u.y + b * v.y,
    )

    fun inverse(point: ShelfPoint): ShelfPoint? {
        val det = u.x * v.y - u.y * v.x
        if (abs(det) < .0001f) return null
        val delta = point - origin
        val a = (delta.x * v.y - delta.y * v.x) / det
        val b = (u.x * delta.y - u.y * delta.x) / det
        // Bound perpendicular screen-space tolerance at each pair of edges.
        val epsilonU = .5f * hypot(v.x, v.y) / abs(det)
        val epsilonV = .5f * hypot(u.x, u.y) / abs(det)
        return if (a in -epsilonU..(1f + epsilonU) && b in -epsilonV..(1f + epsilonV)) {
            ShelfPoint(a.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        } else null
    }

    fun screen(width: Float, rtl: Boolean): ShelfPlane = if (rtl) {
        ShelfPlane(origin.mirror(width), ShelfPoint(-u.x, u.y), ShelfPoint(-v.x, v.y))
    } else this
}

private fun ShelfPoint.mirror(width: Float) = copy(x = width - x)

internal enum class ShelfFace { Spine, Top, Cover }

internal data class ShelfHit(val index: Int, val face: ShelfFace, val uv: ShelfPoint)

internal data class ShelfPose(
    val index: Int,
    val weight: Float,
    val spine: ShelfPlane,
    val cover: ShelfPlane,
    val top: ShelfPlane,
) {
    val minX: Float get() = spine.origin.x
    val maxX: Float get() = cover.map(1f, 0f).x
    val minY: Float get() = top.map(0f, 1f).y
    val maxY: Float get() = spine.map(1f, 1f).y
}

internal enum class ShelfStageSize(val height: Int) {
    Compact(184), Regular(200), Wide(204);

    companion object {
        fun forWidth(widthDp: Float) = when {
            widthDp < 320f -> Compact
            widthDp >= 500f -> Wide
            else -> Regular
        }
    }
}

internal class ShelfMetrics private constructor(
    val width: Float,
    val stageHeight: Float,
    val bodyHeight: Float,
    val artWidth: Float,
    val pitch: Float,
    val density: Float,
    val lift: Float,
    val yBase: Float,
    private val spineSlope: ShelfPoint,
    private val depth: ShelfPoint,
    private val pull: Float,
    val shadowDepth: Float,
) {
    private val projectedWidth = spineSlope.x + depth.x
    private val cullMargin = 2f * density
    private val anchor = width / 2f - projectedWidth / 2f + pull
    val floorY = yBase + bodyHeight + spineSlope.y

    fun screenPoint(point: ShelfPoint, rtl: Boolean) = if (rtl) point.mirror(width) else point

    fun pose(index: Int, q: Float): ShelfPose {
        val distance = index - q
        val r = (1f - abs(distance) / 1.5f).coerceIn(0f, 1f)
        val w = r * r * (3f - 2f * r)
        val a = ShelfPoint(anchor + distance * pitch - pull * w, yBase - lift * w)
        val b = a + spineSlope
        val height = ShelfPoint(0f, bodyHeight)
        return ShelfPose(
            index = index,
            weight = w,
            spine = ShelfPlane(a, spineSlope, height),
            cover = ShelfPlane(b, depth, height),
            top = ShelfPlane(a, spineSlope, depth),
        )
    }

    fun visiblePoses(count: Int, q: Float): List<ShelfPose> {
        if (count <= 0) return emptyList()
        val start = ceil(q + (-projectedWidth - cullMargin - anchor) / pitch).toInt().coerceAtLeast(0)
        val end = floor(q + (width + cullMargin + pull - anchor) / pitch).toInt().coerceAtMost(count - 1)
        return (start..end).mapNotNull { index ->
            pose(index, q).takeIf {
                it.maxX + cullMargin >= 0f && it.minX - cullMargin <= width &&
                    it.maxY + cullMargin >= 0f && it.minY - cullMargin <= stageHeight
            }
        }
    }

    fun hitTest(count: Int, q: Float, point: ShelfPoint, rtl: Boolean): ShelfHit? {
        if (point.x !in 0f..width || point.y !in 0f..stageHeight) return null
        val canonical = screenPoint(point, rtl)
        for (pose in visiblePoses(count, q).asReversed()) {
            pose.spine.inverse(canonical)?.let { return ShelfHit(pose.index, ShelfFace.Spine, it) }
            pose.top.inverse(canonical)?.let { return ShelfHit(pose.index, ShelfFace.Top, it) }
            pose.cover.inverse(canonical)?.let { return ShelfHit(pose.index, ShelfFace.Cover, it) }
        }
        return null
    }

    companion object {
        fun create(width: Float, height: Float, density: Float): ShelfMetrics? {
            if (!width.isFinite() || !height.isFinite() || !density.isFinite() ||
                width <= 0f || height <= 0f || density <= 0f
            ) return null
            val widthDp = width / density
            val dimensions = when (ShelfStageSize.forWidth(widthDp)) {
                ShelfStageSize.Compact -> Dimensions(128f, 12f, 92f, .56f, .16f, 8f, 20f, 6f, 6f)
                ShelfStageSize.Regular -> Dimensions(132f, 14f, 100f, .62f, .20f, 12f, 24f, 8f, 8f)
                ShelfStageSize.Wide -> Dimensions(132f, 16f, 108f, .62f, .20f, 14f, 24f, 8f, 8f)
            }
            val bodyHeight = dimensions.height * density
            val artWidth = dimensions.cover * density
            val s = ShelfPoint(dimensions.spine * density, .04f * dimensions.spine * density)
            val d = ShelfPoint(dimensions.depthX * artWidth, -dimensions.depthY * artWidth)
            val pull = dimensions.pull * density
            val lift = dimensions.lift * density
            val yBase = dimensions.top * density - d.y + lift
            val shadowDepth = dimensions.shadow * density
            val requiredHeight = yBase + bodyHeight + s.y + shadowDepth + 6f * density
            if (height + .01f < requiredHeight || width < (s.x + d.x + 4f * density)) return null
            val pitch = maxOf(
                s.x + pull + 4f * density,
                ceil((width + s.x + d.x + pull + 4f * density) / 8f),
            )
            return ShelfMetrics(width, height, bodyHeight, artWidth, pitch, density, lift, yBase, s, d, pull, shadowDepth)
        }
    }

    private data class Dimensions(
        val height: Float,
        val spine: Float,
        val cover: Float,
        val depthX: Float,
        val depthY: Float,
        val pull: Float,
        val lift: Float,
        val top: Float,
        val shadow: Float,
    )
}
