package com.example.myapplication.ui.components.learning

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal enum class StudyParticleDepth { Behind, Front }

internal enum class StudyParticleTint { MistBlue, Lilac, Pearl }

internal data class StudyParticleSeed(
    val x: Float,
    val y: Float,
    val radiusDp: Float,
    val driftXDp: Float,
    val driftYDp: Float,
    val phaseOffset: Float,
    val depth: StudyParticleDepth,
    val tint: StudyParticleTint,
) {
    init {
        require(x.isFinite() && y.isFinite() && phaseOffset.isFinite())
        require(radiusDp.isFinite() && radiusDp > 0f)
        require(driftXDp.isFinite() && driftXDp >= 0f)
        require(driftYDp.isFinite() && driftYDp >= 0f)
    }
}

internal data class StudyParticlePosition(
    val x: Float,
    val y: Float,
    val radius: Float,
    val glowRadius: Float,
    val opacity: Float,
    val tint: StudyParticleTint,
)

internal data class StudyReadabilityRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal const val StudyCardCycleSeconds = 14f

/** Fixed normalized anchors; only their small dp drift changes during a cycle. */
internal val StudyCardParticleSeeds: List<StudyParticleSeed> = listOf(
    StudyParticleSeed(-0.010f, 0.160f, 0.85f, 4f, 4f, 0.05f, StudyParticleDepth.Behind, StudyParticleTint.MistBlue),
    StudyParticleSeed(0.160f, -0.040f, 0.80f, 5f, 3f, 0.22f, StudyParticleDepth.Behind, StudyParticleTint.Pearl),
    StudyParticleSeed(0.460f, -0.040f, 0.95f, 4f, 3f, 0.41f, StudyParticleDepth.Behind, StudyParticleTint.Lilac),
    StudyParticleSeed(0.760f, -0.035f, 0.90f, 5f, 3f, 0.67f, StudyParticleDepth.Behind, StudyParticleTint.Pearl),
    StudyParticleSeed(1.015f, 0.210f, 1.05f, 4f, 5f, 0.32f, StudyParticleDepth.Behind, StudyParticleTint.MistBlue),
    StudyParticleSeed(1.012f, 0.760f, 0.75f, 4f, 4f, 0.82f, StudyParticleDepth.Behind, StudyParticleTint.Lilac),
    StudyParticleSeed(0.880f, 1.090f, 1.10f, 5f, 3f, 0.16f, StudyParticleDepth.Behind, StudyParticleTint.Pearl),
    StudyParticleSeed(0.290f, 1.080f, 0.85f, 6f, 3f, 0.55f, StudyParticleDepth.Behind, StudyParticleTint.MistBlue),
    StudyParticleSeed(0.180f, 0.130f, 1.00f, 5f, 4f, 0.43f, StudyParticleDepth.Front, StudyParticleTint.Pearl),
    StudyParticleSeed(0.520f, 0.170f, 1.40f, 6f, 5f, 0.12f, StudyParticleDepth.Front, StudyParticleTint.MistBlue),
    StudyParticleSeed(0.710f, 0.470f, 1.80f, 7f, 6f, 0.76f, StudyParticleDepth.Front, StudyParticleTint.Pearl),
    StudyParticleSeed(0.600f, 0.820f, 1.20f, 6f, 4f, 0.34f, StudyParticleDepth.Front, StudyParticleTint.Lilac),
    StudyParticleSeed(0.450f, 0.870f, 1.00f, 4f, 4f, 0.60f, StudyParticleDepth.Front, StudyParticleTint.MistBlue),
    StudyParticleSeed(0.920f, 0.860f, 1.10f, 5f, 3f, 0.93f, StudyParticleDepth.Front, StudyParticleTint.Pearl),
)

/** Secondary fine nacre micro-motes that add pearlescent depth around card margins. */
internal val StudyCardPrismaticDustSeeds: List<StudyParticleSeed> = listOf(
    StudyParticleSeed(0.065f, 0.085f, 0.65f, 4f, 3f, 0.11f, StudyParticleDepth.Front, StudyParticleTint.Pearl),
    StudyParticleSeed(0.310f, 0.075f, 0.70f, 5f, 3f, 0.48f, StudyParticleDepth.Front, StudyParticleTint.MistBlue),
    StudyParticleSeed(0.640f, 0.080f, 0.65f, 4f, 4f, 0.73f, StudyParticleDepth.Front, StudyParticleTint.Lilac),
    StudyParticleSeed(0.865f, 0.135f, 0.90f, 5f, 4f, 0.27f, StudyParticleDepth.Front, StudyParticleTint.Pearl),
    StudyParticleSeed(0.935f, 0.380f, 0.75f, 4f, 5f, 0.64f, StudyParticleDepth.Front, StudyParticleTint.MistBlue),
    StudyParticleSeed(0.835f, 0.680f, 0.85f, 5f, 4f, 0.89f, StudyParticleDepth.Front, StudyParticleTint.Lilac),
    StudyParticleSeed(0.110f, 0.905f, 0.70f, 5f, 3f, 0.37f, StudyParticleDepth.Front, StudyParticleTint.Pearl),
    StudyParticleSeed(0.755f, 0.910f, 0.80f, 4f, 3f, 0.52f, StudyParticleDepth.Front, StudyParticleTint.Lilac),
    StudyParticleSeed(0.015f, 0.540f, 0.75f, 4f, 4f, 0.19f, StudyParticleDepth.Behind, StudyParticleTint.Lilac),
    StudyParticleSeed(0.580f, -0.025f, 0.85f, 5f, 3f, 0.84f, StudyParticleDepth.Behind, StudyParticleTint.MistBlue),
    StudyParticleSeed(1.018f, 0.510f, 0.95f, 4f, 4f, 0.44f, StudyParticleDepth.Behind, StudyParticleTint.Pearl),
    StudyParticleSeed(0.560f, 1.075f, 0.80f, 5f, 3f, 0.71f, StudyParticleDepth.Behind, StudyParticleTint.Lilac),
)

/** Smooth [0.42f, 1.0f] twinkle intensity for crystalline pearl glints. */
internal fun studyCardSparkleFactor(phase: Float, phaseOffset: Float): Float {
    if (!phase.isFinite() || !phaseOffset.isFinite()) return 0.7f
    val normalizedPhase = phase.toDouble() - floor(phase.toDouble())
    val wave = 0.5 + 0.5 * sin((normalizedPhase * 2.0 + phaseOffset.toDouble()) * 2.0 * PI)
    return (0.42 + 0.58 * wave * wave).toFloat()
}

/** Converts normalized pointer offset [-1, 1] into subtle 3D parallax (rotationX, rotationY) degrees. */
internal fun studyCardTiltParallaxDegrees(
    pointerX: Float,
    pointerY: Float,
    maxDegrees: Float = 2.2f,
): Pair<Float, Float> {
    if (!pointerX.isFinite() || !pointerY.isFinite() || !maxDegrees.isFinite() || maxDegrees <= 0f) {
        return 0f to 0f
    }
    val rotX = (-pointerY.coerceIn(-1f, 1f) * maxDegrees)
    val rotY = (pointerX.coerceIn(-1f, 1f) * maxDegrees)
    return rotX to rotY
}

/**
 * [width] and [height] are pixels, [density] converts dp to pixels, and [phase]
 * is normalized cycles, not seconds. Mirroring includes drift so RTL retains
 * the same clearance from the leading text and trailing icon.
 */
internal fun studyCardParticleAt(
    seed: StudyParticleSeed,
    width: Float,
    height: Float,
    density: Float,
    phase: Float,
    rtl: Boolean = false,
): StudyParticlePosition {
    require(width.isFinite() && width >= 0f && height.isFinite() && height >= 0f)
    require(density.isFinite() && density > 0f && phase.isFinite())

    val normalizedPhase = phase.toDouble() - floor(phase.toDouble())
    val angle = (normalizedPhase + seed.phaseOffset.toDouble()) * 2.0 * PI
    val leadingX = seed.x.toDouble() * width + sin(angle) * seed.driftXDp * density
    val y = seed.y.toDouble() * height + cos(angle) * seed.driftYDp * density
    val radius = seed.radiusDp * density
    val opacity = when (seed.depth) {
        StudyParticleDepth.Front -> 0.665 + 0.115 * sin(angle + 0.35)
        StudyParticleDepth.Behind -> 0.325 + 0.105 * sin(angle + 0.35)
    }
    return StudyParticlePosition(
        x = (if (rtl) width - leadingX else leadingX).toFloat(),
        y = y.toFloat(),
        radius = radius,
        glowRadius = radius * 3.8f,
        opacity = opacity.toFloat(),
        tint = seed.tint,
    )
}

/** The complete glow circle must clear every protected rectangle, including tangency. */
internal fun isStudyCardParticleClear(
    x: Float,
    y: Float,
    glowRadius: Float,
    protectedRects: List<StudyReadabilityRect>,
): Boolean {
    if (!x.isFinite() || !y.isFinite() || !glowRadius.isFinite() || glowRadius < 0f) return false
    val squaredRadius = glowRadius.toDouble() * glowRadius
    return protectedRects.all { rect ->
        if (!rect.left.isFinite() || !rect.top.isFinite() ||
            !rect.right.isFinite() || !rect.bottom.isFinite() ||
            rect.left > rect.right || rect.top > rect.bottom
        ) {
            return@all false
        }
        val nearestX = x.coerceIn(rect.left, rect.right).toDouble()
        val nearestY = y.coerceIn(rect.top, rect.bottom).toDouble()
        val distanceX = x.toDouble() - nearestX
        val distanceY = y.toDouble() - nearestY
        distanceX * distanceX + distanceY * distanceY > squaredRadius
    }
}
