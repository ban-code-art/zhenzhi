package com.example.myapplication.ui.components.learning

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Card bounds are in the drawing layer's local pixels. */
internal data class StudyCardParticleTarget(val key: String, val bounds: Rect)

internal data class StudyCardParticleLayout(
    val stack: Rect,
    val targets: List<StudyCardParticleTarget>,
    val protectedRects: List<Rect>,
    val geometryRevision: Long,
)

internal enum class StudyDeckParticleTint { Blue, Teal, Lilac, Gold, Pearl }

internal data class StudyCardParticleId(val owner: String?, val ordinal: Int)

internal data class StudyCardParticleTrack(
    val id: StudyCardParticleId,
    val start: Offset,
    val end: Offset,
    val tint: StudyDeckParticleTint,
    val radiusDp: Float,
    val opacity: Float,
    val lightweight: Boolean,
    private val controlOne: Offset,
    private val controlTwo: Offset,
    private val phaseStart: Float,
    private val phaseEnd: Float,
    private val mainPhase: Offset,
    private val miniPhase: Offset,
) {
    fun position(progress: Float): Offset {
        val t = studyCardParticleSmooth(phaseStart, phaseEnd, progress)
        val u = 1f - t
        return start * (u * u * u) +
            controlOne * (3f * u * u * t) +
            controlTwo * (3f * u * t * t) +
            end * (t * t * t)
    }

    fun drift(sine: Float, cosine: Float, blend: Float, density: Float, rtl: Boolean): Offset {
        val main = rotatedPhase(sine, cosine, mainPhase)
        val mini = rotatedPhase(sine, cosine, miniPhase)
        val offset = (main * (1.4f * (1f - blend)) + mini * (.8f * blend)) * density
        return if (rtl) Offset(-offset.x, offset.y) else offset
    }

    private fun rotatedPhase(sine: Float, cosine: Float, phase: Offset): Offset =
        Offset(sine * phase.x + cosine * phase.y, cosine * phase.x - sine * phase.y)
}

internal data class StudyCardParticlePage(
    val center: Offset,
    val width: Float,
    val height: Float,
    val angleDegrees: Float,
    val tint: StudyDeckParticleTint,
    val strength: Float,
) {
    val corner = min(width, height) * .18f
    private val cosine = cos(angleDegrees * PI / 180).toFloat()
    private val sine = sin(angleDegrees * PI / 180).toFloat()

    fun point(seed: Int, sample: Int, rtl: Boolean): Offset {
        val x: Float
        val y: Float
        when {
            sample < 72 -> {
                val phase = phaseVector(unit(seed, 2))
                val edge = 1f / max(abs(phase.x) / (width * .5f), abs(phase.y) / (height * .5f))
                val edgeX = phase.x * edge
                val edgeY = phase.y * edge
                val insideX = edgeX.coerceIn(corner - width * .5f, width * .5f - corner)
                val insideY = edgeY.coerceIn(corner - height * .5f, height * .5f - corner)
                val deltaX = edgeX - insideX
                val deltaY = edgeY - insideY
                val rounding = corner / sqrt(deltaX * deltaX + deltaY * deltaY)
                val jitter = (unit(seed, 3) - .5f) * .065f * width
                x = insideX + deltaX * rounding + phase.x * jitter
                y = insideY + deltaY * rounding + phase.y * jitter
            }
            sample < 92 -> {
                val line = (unit(seed, 2) * 3).toInt()
                x = width * (-.26f + unit(seed, 3) * (.54f - line * .09f))
                y = height * (-.19f + line * .16f + (unit(seed, 4) - .5f) * .022f)
            }
            else -> {
                val phase = phaseVector(unit(seed, 2))
                val reach = .51f + unit(seed, 3) * .15f
                x = phase.x * width * reach
                y = phase.y * height * reach
            }
        }
        val localX = if (rtl) -x else x
        return center + Offset(localX * cosine - y * sine, localX * sine + y * cosine)
    }
}

internal data class StudyCardParticleMotion(
    val tracks: List<StudyCardParticleTrack>,
    val stackPages: List<StudyCardParticlePage>,
    val targetPages: List<StudyCardParticlePage>,
)

private data class TargetPages(val key: String, val pages: List<StudyCardParticlePage>, val budget: Int)

private const val DefaultCapacity = 360
internal const val LightweightParticleCapacity = 180
private const val DefaultMiniBudget = 48
private const val LightweightMiniBudget = 24

/**
 * Keep the collapsed motif fixed while assigning visible books to the same source
 * slots for every traversal of a measured layout. The assignment is driven by keys,
 * independently of the order in which the card measurements arrived.
 */
internal fun buildStudyCardParticleMotion(
    layout: StudyCardParticleLayout,
    viewport: Rect,
    density: Float,
    rtl: Boolean,
    largeText: Boolean = false,
    includeTracks: Boolean = true,
    includeOptionalTracks: Boolean = true,
): StudyCardParticleMotion {
    val stackPages = particlePages(layout.stack, density, main = true, rtl = rtl, largeText = largeText)
    val targets = layout.targets.asSequence()
        .filter { it.bounds.isParticleRect() && it.bounds.overlaps(viewport) }
        .distinctBy { it.key }
        .sortedWith(compareBy({ stableSeed(it.key, 0) }, { it.key }))
        .mapNotNull { target ->
            val pages = particlePages(target.bounds, density, main = false, rtl = rtl, largeText = largeText)
            if (pages.isEmpty()) null else {
                val budget = if (pages.last().width < 13f * density) 24 else DefaultMiniBudget
                TargetPages(target.key, pages, budget)
            }
        }
        .toList()
    val targetPages = targets.flatMap { it.pages }
    if (!includeTracks || stackPages.isEmpty() && targetPages.isEmpty()) {
        return StudyCardParticleMotion(emptyList(), stackPages, targetPages)
    }

    val capacity = if (includeOptionalTracks) DefaultCapacity else LightweightParticleCapacity
    val bySlot = arrayOfNulls<StudyCardParticleTrack>(capacity)
    val startPages = stackPages.ifEmpty { targets.first().pages }
    val miniOrdinals = IntArray(targets.size)
    for (range in listOf(0 until LightweightParticleCapacity, LightweightParticleCapacity until capacity)) {
        if (range.isEmpty()) break
        val lightweight = range.first == 0
        var assigned = 0
        val maxPerCard = if (lightweight) LightweightMiniBudget else DefaultMiniBudget
        for (round in 0 until maxPerCard) {
            for ((index, target) in targets.withIndex()) {
                val ordinal = miniOrdinals[index]
                if (ordinal >= min(target.budget, maxPerCard)) continue
                var slot = range.first + (stableSeed(target.key, ordinal) ushr 1) % range.count()
                while (bySlot[slot] != null) {
                    slot = if (slot == range.last) range.first else slot + 1
                }
                bySlot[slot] = particleTrack(slot, startPages, target, ordinal, density, rtl)
                miniOrdinals[index]++
                assigned++
                if (assigned == range.count()) break
            }
            if (assigned == range.count()) break
        }
        for (slot in range) {
            if (bySlot[slot] == null) {
                bySlot[slot] = particleTrack(slot, startPages, null, slot, density, rtl)
            }
        }
    }
    return StudyCardParticleMotion(bySlot.filterNotNull(), stackPages, targetPages)
}

private fun particlePages(
    bounds: Rect,
    density: Float,
    main: Boolean,
    rtl: Boolean,
    largeText: Boolean,
): List<StudyCardParticlePage> {
    if (!bounds.isParticleRect() || !density.isFinite() || density <= 0f) return emptyList()
    val slotWidth = if (main && !largeText) {
        min(bounds.width * .30f, 112f * density)
    } else if (main) {
        min(bounds.width * .26f, 56f * density)
    } else {
        min(bounds.width * .27f, 36f * density)
    }
    if (slotWidth < (if (main) 28f else 22f) * density) return emptyList()

    val inset = (if (main) 16f else 13f) * density + slotWidth * .53f
    val direction = if (rtl) -1f else 1f
    val slotX = if (rtl) bounds.left + inset else bounds.right - inset
    val slotY = bounds.top + bounds.height * if (main) .51f else .62f
    val pageWidth = slotWidth * if (main) .60f else .49f
    val pageHeight = min(bounds.height * .60f, pageWidth * (if (main) 1.24f else 1.3f))
    val horizontalStep = slotWidth * if (main) .13f else .115f
    val verticalStep = slotWidth * .055f
    val angles = if (main) listOf(-16f, -5f, 8f) else listOf(-20f, -15f, -10f)
    val tints = listOf(StudyDeckParticleTint.Lilac, StudyDeckParticleTint.Teal, StudyDeckParticleTint.Blue)
    val strengths = listOf(.56f, .72f, 1f)
    return List(3) { layer ->
        val spread = layer - 1
        StudyCardParticlePage(
            center = Offset(slotX + spread * horizontalStep * direction, slotY + spread * verticalStep),
            width = pageWidth,
            height = pageHeight,
            angleDegrees = angles[layer] * direction,
            tint = tints[layer],
            strength = strengths[layer],
        )
    }
}

private fun particleTrack(
    slot: Int,
    stack: List<StudyCardParticlePage>,
    target: TargetPages?,
    ordinal: Int,
    density: Float,
    rtl: Boolean,
): StudyCardParticleTrack {
    val sourceSeed = stableSeed("", slot)
    val ownerSeed = target?.let { stableSeed(it.key, ordinal) }
    val paletteIndex = unit(sourceSeed, 0)
    val tint = when {
        paletteIndex < .38f -> StudyDeckParticleTint.Blue
        paletteIndex < .68f -> StudyDeckParticleTint.Teal
        paletteIndex < .91f -> StudyDeckParticleTint.Lilac
        paletteIndex < .96f -> StudyDeckParticleTint.Gold
        else -> StudyDeckParticleTint.Pearl
    }
    val layer = when (tint) {
        StudyDeckParticleTint.Lilac -> 0
        StudyDeckParticleTint.Teal -> 1
        else -> 2
    }
    val lightweight = slot < LightweightParticleCapacity
    val sampleRoll = unit(sourceSeed, 1)
    val sample = when {
        sampleRoll < (if (lightweight) .84f else .60f) -> 0
        lightweight || sampleRoll < .84f -> 80
        else -> 99
    }
    val sourcePage = stack[layer]
    val start = sourcePage.point(sourceSeed, sample, rtl)
    val end = if (target != null && ownerSeed != null) {
        val miniSample = when {
            ordinal < 21 || ordinal in 24..37 -> 0
            ordinal in 45..47 -> 99
            else -> 83
        }
        target.pages[layer].point(ownerSeed, miniSample, rtl)
    } else {
        val direction = if (rtl) -1f else 1f
        start + Offset(direction * density * (6f + unit(sourceSeed, 5) * 14f), (unit(sourceSeed, 6) - .5f) * 24f * density)
    }
    val seed = ownerSeed ?: sourceSeed
    val distance = (end - start).getDistance()
    val far = (distance / (420f * density)).coerceIn(0f, 1f)
    val direction = if (rtl) -1f else 1f
    val scatter = (18f + 14f * unit(seed, 7)) * density
    val splay = (unit(sourceSeed, 8) * 2f - 1f) * scatter * direction
    val highlight = tint == StudyDeckParticleTint.Gold || tint == StudyDeckParticleTint.Pearl
    return StudyCardParticleTrack(
        id = StudyCardParticleId(target?.key, ordinal),
        start = start,
        end = end,
        tint = tint,
        radiusDp = if (highlight) 1f + unit(sourceSeed, 9) * .65f else .7f + unit(sourceSeed, 9) * .43f,
        opacity = (.53f + unit(sourceSeed, 10) * .31f) * sourcePage.strength,
        lightweight = lightweight,
        controlOne = start + Offset(direction * scatter + splay, (end.y - start.y) * .18f - scatter),
        controlTwo = Offset(end.x + (start.x - end.x) * .17f - splay * .46f, end.y - (end.y - start.y) * .30f),
        phaseStart = .04f + unit(seed, 11) * .11f,
        phaseEnd = .88f + .07f * far + .03f * unit(seed, 12),
        mainPhase = phaseVector(unit(sourceSeed, 13)),
        miniPhase = phaseVector(unit(seed, 13)),
    )
}

internal fun studyCardParticleSmooth(start: Float, end: Float, progress: Float): Float {
    val fraction = ((progress - start) / (end - start)).coerceIn(0f, 1f)
    return fraction * fraction * (3f - 2f * fraction)
}

internal fun studyCardParticleVisibility(
    point: Offset,
    radius: Float,
    viewport: Rect,
    protectedRects: List<Rect>,
    density: Float,
): Float {
    val edge = min(
        min(point.x - viewport.left, viewport.right - point.x),
        min(point.y - viewport.top, viewport.bottom - point.y),
    )
    var alpha = studyCardParticleSmooth(0f, 12f * density, edge - radius)
    if (alpha <= 0f) return 0f
    val margin = 8f * density
    val testRadius = radius + margin
    val testRadiusSquared = testRadius * testRadius
    for (index in protectedRects.indices) {
        val rect = protectedRects[index]
        val dx = max(0f, max(rect.left - point.x, point.x - rect.right))
        val dy = max(0f, max(rect.top - point.y, point.y - rect.bottom))
        val distanceSquared = dx * dx + dy * dy
        if (distanceSquared < testRadiusSquared) {
            alpha = min(alpha, studyCardParticleSmooth(0f, margin, sqrt(distanceSquared) - radius))
            if (alpha <= 0f) return 0f
        }
    }
    return alpha
}

internal fun Rect.isParticleRect(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && width > 0f && height > 0f

private fun phaseVector(phase: Float): Offset {
    val angle = phase * 2 * PI
    return Offset(cos(angle).toFloat(), sin(angle).toFloat())
}

private fun stableSeed(key: String, ordinal: Int): Int {
    var hash = 0x811c9dc5.toInt()
    for (char in key) hash = (hash xor char.code) * 16777619
    return mix(hash xor ordinal)
}

private fun unit(seed: Int, channel: Int): Float =
    (mix(seed + channel * 0x9e3779b9.toInt()) ushr 8) / 16777216f

private fun mix(input: Int): Int {
    var value = (input xor (input ushr 16)) * 0x7feb352d
    value = (value xor (value ushr 15)) * 0x846ca68b.toInt()
    return value xor (value ushr 16)
}
