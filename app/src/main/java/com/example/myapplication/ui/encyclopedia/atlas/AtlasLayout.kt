package com.example.myapplication.ui.encyclopedia.atlas

import com.example.myapplication.data.encyclopedia.AtlasProjectionPoint
import com.example.myapplication.data.encyclopedia.AtlasProjectionRating
import com.example.myapplication.data.encyclopedia.AtlasViewId
import com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val ALL_MERIDIANS_CODE = "all"

internal data class AtlasBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun overlaps(other: AtlasBox): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

internal data class AtlasRouteSegment(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
)

internal data class PlacedAtlasMarker(
    val pointCode: String,
    val pointName: String,
    val meridianCode: String,
    val frameX: Float,
    val frameY: Float,
    val labelFrameX: Float,
    val labelFrameY: Float,
    val leaderEndFrameX: Float,
    val leaderEndFrameY: Float,
    val selected: Boolean,
    val isProjected: Boolean,
    val rating: AtlasProjectionRating,
    val labelBoxDp: AtlasBox?,
    val dotBoxDp: AtlasBox?,
)

internal data class AtlasSceneLayout(
    val isOverview: Boolean,
    val isLungFrontSpecial: Boolean,
    val totalViewPointCount: Int,
    val candidatePoints: List<AtlasProjectionPoint>,
    val visibleMarkers: List<PlacedAtlasMarker>,
    val routeSegments: List<AtlasRouteSegment>,
    val lungMainPath: String?,
    val lungDirectionPath: String?,
    val lungBranchPath: String?,
)

internal object AtlasLayoutEngine {
    private const val OVERVIEW_HIT_RADIUS_DP = 6f
    private const val OVERVIEW_FONT_DP = 9f
    private const val OVERVIEW_LABEL_HEIGHT_DP = 12f

    fun layout(
        snapshot: EncyclopediaSnapshot,
        meridianCode: String,
        viewId: AtlasViewId,
        camera: AtlasCameraState,
        viewportWidthDp: Float,
        viewportHeightDp: Float,
        selectedPointCode: String? = null,
        overlayOccupiedDp: List<AtlasBox> = emptyList(),
    ): AtlasSceneLayout {
        val isOverview = meridianCode == ALL_MERIDIANS_CODE
        val candidates = if (isOverview) {
            snapshot.projectionsByView[viewId].orEmpty()
        } else {
            snapshot.atlas.projectionsByViewAndMeridian[viewId]?.get(meridianCode).orEmpty()
        }

        if (viewportWidthDp <= 0f || viewportHeightDp <= 0f) {
            return AtlasSceneLayout(
                isOverview = isOverview,
                isLungFrontSpecial = !isOverview && meridianCode == "LU" && viewId == AtlasViewId.FRONT,
                totalViewPointCount = candidates.size,
                candidatePoints = candidates,
                visibleMarkers = emptyList(),
                routeSegments = emptyList(),
                lungMainPath = null,
                lungDirectionPath = null,
                lungBranchPath = null,
            )
        }

        if (isOverview) {
            val placed = layoutOverview(
                snapshot = snapshot,
                candidates = candidates,
                camera = camera,
                viewportWidthDp = viewportWidthDp,
                viewportHeightDp = viewportHeightDp,
                selectedPointCode = selectedPointCode,
                overlayOccupiedDp = overlayOccupiedDp,
            )
            return AtlasSceneLayout(
                isOverview = true,
                isLungFrontSpecial = false,
                totalViewPointCount = candidates.size,
                candidatePoints = candidates,
                visibleMarkers = placed,
                routeSegments = emptyList(),
                lungMainPath = null,
                lungDirectionPath = null,
                lungBranchPath = null,
            )
        }

        val lungFront = meridianCode == "LU" && viewId == AtlasViewId.FRONT
        if (lungFront) {
            val spec = snapshot.atlas.lungFront
            val featured = spec.featuredPointCodes.toSet()
            val wrist = spec.wristPointCodes.toSet()
            val anchorByCode = spec.labels.associateBy { it.pointCode }
            val visible = candidates.filter { it.pointCode in featured || it.pointCode == selectedPointCode }
                .mapNotNull { row ->
                    val point = snapshot.pointsByCode[row.pointCode] ?: return@mapNotNull null
                    val anchor = anchorByCode[row.pointCode]
                    val lx = anchor?.labelX?.toFloat() ?: (row.xFloat + 12f)
                    val ly = anchor?.labelY?.toFloat() ?: row.yFloat
                    PlacedAtlasMarker(
                        pointCode = row.pointCode,
                        pointName = point.name,
                        meridianCode = row.meridianCode,
                        frameX = row.xFloat,
                        frameY = row.yFloat,
                        labelFrameX = lx,
                        labelFrameY = ly,
                        leaderEndFrameX = lx - 4f,
                        leaderEndFrameY = ly - 3f,
                        selected = row.pointCode == selectedPointCode,
                        isProjected = row.pointCode in wrist,
                        rating = row.rating,
                        labelBoxDp = null,
                        dotBoxDp = null,
                    )
                }
            return AtlasSceneLayout(
                isOverview = false,
                isLungFrontSpecial = true,
                totalViewPointCount = candidates.size,
                candidatePoints = candidates,
                visibleMarkers = visible,
                routeSegments = emptyList(),
                lungMainPath = spec.mainPath,
                lungDirectionPath = spec.directionPath,
                lungBranchPath = spec.branchPath,
            )
        }

        val anchors = labelAnchors(candidates)
        val route = projectedRouteSegments(candidates)
        val visible = candidates.filter { it.pointCode in anchors || it.pointCode == selectedPointCode }
            .mapNotNull { row ->
                val point = snapshot.pointsByCode[row.pointCode] ?: return@mapNotNull null
                val useLeftColumn = viewId == AtlasViewId.BACK && row.xFloat < 140f
                val inAnchors = row.pointCode in anchors
                val labelX = if (inAnchors == useLeftColumn) 30f else 242f
                val labelY = anchors[row.pointCode] ?: row.yFloat.coerceIn(47f, 452f)
                PlacedAtlasMarker(
                    pointCode = row.pointCode,
                    pointName = point.name,
                    meridianCode = row.meridianCode,
                    frameX = row.xFloat,
                    frameY = row.yFloat,
                    labelFrameX = labelX,
                    labelFrameY = labelY,
                    leaderEndFrameX = labelX - 4f,
                    leaderEndFrameY = labelY - 3f,
                    selected = row.pointCode == selectedPointCode,
                    isProjected = false,
                    rating = row.rating,
                    labelBoxDp = null,
                    dotBoxDp = null,
                )
            }
        return AtlasSceneLayout(
            isOverview = false,
            isLungFrontSpecial = false,
            totalViewPointCount = candidates.size,
            candidatePoints = candidates,
            visibleMarkers = visible,
            routeSegments = route,
            lungMainPath = null,
            lungDirectionPath = null,
            lungBranchPath = null,
        )
    }

    internal fun projectedRouteSegments(rows: List<AtlasProjectionPoint>): List<AtlasRouteSegment> {
        if (rows.size < 2) return emptyList()
        val segments = ArrayList<AtlasRouteSegment>(rows.size)
        for (i in 1 until rows.size) {
            val prev = rows[i - 1]
            val curr = rows[i]
            val prevOrder = pointOrder(prev.pointCode) ?: continue
            val currOrder = pointOrder(curr.pointCode) ?: continue
            if (currOrder != prevOrder + 1 || curr.pointCode == "BL41" || curr.pointCode == "BL55") continue
            if (hypot((curr.xFloat - prev.xFloat).toDouble(), (curr.yFloat - prev.yFloat).toDouble()) > 58.0) continue
            segments.add(AtlasRouteSegment(prev.xFloat, prev.yFloat, curr.xFloat, curr.yFloat))
        }
        return segments
    }

    internal fun labelAnchors(rows: List<AtlasProjectionPoint>): Map<String, Float> {
        if (rows.isEmpty()) return emptyMap()
        val sorted = rows.sortedBy { it.y }
        val limit = min(6, sorted.size)
        val chosen = LinkedHashMap<String, Float>(limit)
        var previous = 33f
        val denom = max(1, limit - 1)
        for (i in 0 until limit) {
            val idx = ((i.toFloat() * (sorted.size - 1)) / denom).roundToInt().coerceIn(0, sorted.lastIndex)
            val row = sorted[idx]
            val yCap = 448f - (limit - i - 1) * 19f
            previous = max(previous + 19f, min(row.yFloat, yCap))
            chosen[row.pointCode] = previous
        }
        return chosen
    }

    private fun layoutOverview(
        snapshot: EncyclopediaSnapshot,
        candidates: List<AtlasProjectionPoint>,
        camera: AtlasCameraState,
        viewportWidthDp: Float,
        viewportHeightDp: Float,
        selectedPointCode: String?,
        overlayOccupiedDp: List<AtlasBox>,
    ): List<PlacedAtlasMarker> {
        val transform = AtlasCameraMath.transform(
            frame = AtlasFrames.Body,
            camera = camera,
            viewportWidth = viewportWidthDp,
            viewportHeight = viewportHeightDp,
        )
        val orderedByRating = candidates.sortedBy { it.rating.ordinal }
        val ordered = if (selectedPointCode != null) {
            val selected = orderedByRating.firstOrNull { it.pointCode == selectedPointCode }
            if (selected != null) listOf(selected) + orderedByRating.filter { it.pointCode != selectedPointCode }
            else orderedByRating
        } else orderedByRating

        val occupied = ArrayList<AtlasBox>(overlayOccupiedDp.size + ordered.size * 2)
        occupied.addAll(overlayOccupiedDp)
        val bounds = AtlasBox(0f, 0f, viewportWidthDp, viewportHeightDp)

        fun isAvailable(box: AtlasBox): Boolean =
            box.left >= bounds.left &&
                box.right <= bounds.right &&
                box.top >= bounds.top &&
                box.bottom <= bounds.bottom &&
                occupied.none { it.overlaps(box) }

        val placed = ArrayList<PlacedAtlasMarker>(ordered.size)
        for (row in ordered) {
            val point = snapshot.pointsByCode[row.pointCode] ?: continue
            val (sx, sy) = transform.frameToScreen(row.xFloat, row.yFloat)
            val dotBox = AtlasBox(
                left = sx - OVERVIEW_HIT_RADIUS_DP,
                top = sy - OVERVIEW_HIT_RADIUS_DP,
                right = sx + OVERVIEW_HIT_RADIUS_DP,
                bottom = sy + OVERVIEW_HIT_RADIUS_DP,
            )
            if (!isAvailable(dotBox)) continue

            val labelWidthDp = point.name.length * OVERVIEW_FONT_DP + 4f
            val candidateOffsets = arrayOf(
                (sx + 9f) to (sy - 6f),
                (sx - 9f - labelWidthDp) to (sy - 6f),
                (sx + 7f) to (sy - 17f),
                (sx - 7f - labelWidthDp) to (sy - 17f),
                (sx + 7f) to (sy + 5f),
                (sx - 7f - labelWidthDp) to (sy + 5f),
            )
            var chosenBox: AtlasBox? = null
            for ((left, top) in candidateOffsets) {
                val box = AtlasBox(left, top, left + labelWidthDp, top + OVERVIEW_LABEL_HEIGHT_DP)
                if (isAvailable(box)) {
                    chosenBox = box
                    break
                }
            }
            if (chosenBox == null) continue

            occupied.add(dotBox)
            occupied.add(chosenBox)
            val (labelFx, labelFy) = transform.screenToFrame(chosenBox.left + 2f, chosenBox.top + 9f)
            val (leaderEndFx, leaderEndFy) = transform.screenToFrame(
                sx.coerceIn(chosenBox.left, chosenBox.right),
                chosenBox.top + 6f,
            )
            placed.add(
                PlacedAtlasMarker(
                    pointCode = row.pointCode,
                    pointName = point.name,
                    meridianCode = row.meridianCode,
                    frameX = row.xFloat,
                    frameY = row.yFloat,
                    labelFrameX = labelFx,
                    labelFrameY = labelFy,
                    leaderEndFrameX = leaderEndFx,
                    leaderEndFrameY = leaderEndFy,
                    selected = row.pointCode == selectedPointCode,
                    isProjected = false,
                    rating = row.rating,
                    labelBoxDp = chosenBox,
                    dotBoxDp = dotBox,
                ),
            )
        }
        return placed
    }

    private fun pointOrder(code: String): Int? {
        val digits = code.dropWhile { !it.isDigit() }
        return digits.toIntOrNull()
    }

    internal data class HitResult(
        val primary: PlacedAtlasMarker,
        val nearbyCandidates: List<PlacedAtlasMarker>,
    )

    fun hitTest(
        markers: List<PlacedAtlasMarker>,
        transform: AtlasViewportTransform,
        tapScreenX: Float,
        tapScreenY: Float,
        density: Float,
        isOverview: Boolean,
    ): HitResult? {
        if (markers.isEmpty() || transform.scale <= 0f || density <= 0f) return null
        val maxFrameDist = (if (isOverview) 14f else 24f) * density / transform.scale
        val overlapFrameDist = 12f * density / transform.scale
        var best: PlacedAtlasMarker? = null
        var bestDist = maxFrameDist
        val hits = ArrayList<Pair<PlacedAtlasMarker, Float>>()
        val (tapFx, tapFy) = transform.screenToFrame(tapScreenX, tapScreenY)
        for (marker in markers) {
            val d = hypot((tapFx - marker.frameX).toDouble(), (tapFy - marker.frameY).toDouble()).toFloat()
            if (d <= maxFrameDist) {
                hits.add(marker to d)
                if (d < bestDist || best == null) {
                    best = marker
                    bestDist = d
                }
            }
        }
        val primary = best ?: return null
        val nearby = hits
            .filter { (candidate, _) ->
                hypot((candidate.frameX - primary.frameX).toDouble(), (candidate.frameY - primary.frameY).toDouble()) <= overlapFrameDist
            }
            .sortedBy { it.second }
            .map { it.first }
        return HitResult(primary = primary, nearbyCandidates = nearby)
    }
}
