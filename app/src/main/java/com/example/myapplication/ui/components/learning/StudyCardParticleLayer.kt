package com.example.myapplication.ui.components.learning

import android.app.ActivityManager
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

@Composable
internal fun StudyCardParticleLayer(
    modifier: Modifier,
    layout: StudyCardParticleLayout,
    progress: () -> Float,
    active: Boolean,
    reducedMotion: Boolean,
) {
    val style = rememberStudyCardIridescentStyle()
    val localDensity = LocalDensity.current
    val largeText = localDensity.fontScale >= 1.3f
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val context = LocalContext.current
    val lowRam = remember(context) {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    }
    val clock = remember(lowRam) { StudyCardParticleClock(lowRam) }
    val compact = clock.compact
    val currentProgress = rememberUpdatedState(progress)
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    val viewport = remember(dimensions) {
        Rect(0f, 0f, dimensions.width.toFloat(), dimensions.height.toFloat())
    }
    // HOME stays mounted through navigation. Keep the measured drawing ready so a
    // returning page can reveal the last particle frame before its clock resumes.
    val visible = viewport.isParticleRect() &&
        (layout.stack.overlaps(viewport) || layout.targets.any { it.bounds.overlaps(viewport) })
    val motion = remember(
        layout.stack, layout.targets, layout.geometryRevision, viewport, localDensity, rtl, visible, reducedMotion, compact,
    ) {
        if (visible) {
            buildStudyCardParticleMotion(
                layout, viewport, localDensity.density, rtl, largeText,
                includeTracks = !reducedMotion,
                includeOptionalTracks = !compact,
            )
        } else {
            null
        }
    }
    val mainVisible = motion?.stackPages?.isNotEmpty() == true && layout.stack.overlaps(viewport)
    val miniVisible = motion?.targetPages?.isNotEmpty() == true
    val hasVisibleTracks by remember(mainVisible, miniVisible) {
        derivedStateOf {
            val q = currentProgress.value().visibleProgress()
            q < 1f && mainVisible || q > 0f && miniVisible
        }
    }
    val showTracks = !reducedMotion && motion?.tracks?.isNotEmpty() == true && hasVisibleTracks
    if (active && showTracks) {
        LaunchedEffect(clock) {
            clock.run { currentProgress.value().visibleProgress() }
        }
    }

    val canvas = modifier.onSizeChanged { dimensions = it }
    if (motion == null) {
        Spacer(canvas)
        return
    }

    val palette = remember(style) { style.toParticlePalette() }
    Spacer(canvas.drawWithCache {
        val clearance = 6f * density
        val protectedRects = layout.protectedRects.mapNotNull {
            if (it.isParticleRect()) it.inflate(clearance).takeIf { rect -> rect.overlaps(viewport) } else null
        }
        val protection = Path().apply {
            protectedRects.forEach { addRect(it) }
        }
        val stackPages = motion.stackPages.map { it.decoration(palette, washStrength = 1f) }
        val miniPages = motion.targetPages.map { it.decoration(palette, washStrength = .5f) }
        val outline = Stroke(density * if (reducedMotion) .95f else .78f)
        onDrawBehind {
            val q = currentProgress.value().visibleProgress()
            val phase = if (showTracks) clock.phase else 0f
            val angle = phase * 2 * PI
            val cosine = cos(angle).toFloat()
            val sine = sin(angle).toFloat()
            val washBreath = if (showTracks) 1f + .10f * (cosine * cosine - sine * sine) else 1f
            clipRect {
                clipPath(protection, ClipOp.Difference) {
                    drawParticlePages(stackPages, 1f - studyCardParticleSmooth(.02f, .40f, q), washBreath, outline, palette, cosine, sine)
                    drawParticlePages(miniPages, studyCardParticleSmooth(.62f, .97f, q), washBreath, outline, palette, cosine, sine)
                    if (showTracks) {
                        drawParticleTracks(
                            motion, q, phase, sine, cosine, clock.extraWeight, palette, viewport, protectedRects, rtl,
                        )
                    }
                }
            }
        }
    })
}

private class StudyCardParticleClock(lowRam: Boolean) {
    private var elapsedNanos = 0.0
    private var targetLightweight = lowRam
    private var downgradeElapsedNanos = 0L
    var compact by mutableStateOf(lowRam)
        private set
    var phase by mutableFloatStateOf(0f)
        private set
    var extraWeight by mutableFloatStateOf(if (lowRam) 0f else 1f)
        private set

    suspend fun run(progress: () -> Float) {
        val coroutine = currentCoroutineContext()
        val durationScale = coroutine[MotionDurationScale]
        var previous = 0L
        var lastPublish = 0L
        var frames = 0
        var slowFrames = 0
        while (coroutine.isActive) {
            val scale = durationScale?.scaleFactor ?: 1f
            if (!scale.isFinite() || scale <= 0f) return
            val now = withInfiniteAnimationFrameNanos { it }
            if (previous == 0L) previous = now
            val delta = now - previous
            previous = now
            elapsedNanos = (elapsedNanos + delta.toDouble() / scale) % (StudyCardCycleSeconds * 1e9)
            if (!targetLightweight && delta > 0L) {
                frames++
                if (delta > 29_000_000L) slowFrames++
                if (frames == 15) {
                    targetLightweight = slowFrames >= 6
                    frames = 0
                    slowFrames = 0
                }
            }
            if (targetLightweight && extraWeight > 0f) {
                downgradeElapsedNanos += delta
                extraWeight = 1f - studyCardParticleSmooth(0f, 120_000_000f, downgradeElapsedNanos.toFloat())
            }
            val q = progress()
            if (!compact && extraWeight <= 0f && (q == 0f || q == 1f)) compact = true
            if (q > 0f && q < 1f || now - lastPublish >= 32_000_000L) {
                phase = (elapsedNanos / (StudyCardCycleSeconds * 1e9)).toFloat()
                lastPublish = now
            }
        }
    }
}

private fun Float.visibleProgress(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f

private data class ParticlePalette(
    val colors: List<Color>,
    val washes: List<Color>,
    val rimHighlight: Color,
    val rimPrismA: Color,
    val rimPrismB: Color,
    val glintStrength: Float,
)

private fun StudyCardIridescentStyle.toParticlePalette(): ParticlePalette = ParticlePalette(
    colors = listOf(particlePrimary, particleSecondary, particleTransition, particleWarmGold, particlePearl),
    washes = listOf(washA, washB, washC),
    rimHighlight = rimHighlight,
    rimPrismA = rimPrismA,
    rimPrismB = rimPrismB,
    glintStrength = glintStrength,
)

private data class ParticlePageDecoration(val page: StudyCardParticlePage, val tint: Color, val brush: Brush)

private fun StudyCardParticlePage.decoration(palette: ParticlePalette, washStrength: Float): ParticlePageDecoration {
    val color = palette.washes[tint.ordinal]
    val washAlpha = when (tint) {
        StudyDeckParticleTint.Blue -> .14f
        StudyDeckParticleTint.Teal -> .11f
        else -> .09f
    } * washStrength
    val brush = Brush.radialGradient(
        colors = listOf(color.copy(alpha = washAlpha), color.copy(alpha = 0f)),
        center = center,
        radius = height * .88f,
    )
    return ParticlePageDecoration(this, palette.colors[tint.ordinal], brush)
}

private fun DrawScope.drawParticlePages(
    pages: List<ParticlePageDecoration>,
    alpha: Float,
    breath: Float,
    outline: Stroke,
    palette: ParticlePalette,
    cosine: Float,
    sine: Float,
) {
    if (alpha <= 0f) return
    for ((page, tint, brush) in pages) {
        drawCircle(brush, radius = page.height * .88f, center = page.center, alpha = alpha * breath)
        val topLeft = page.center - Offset(page.width * .5f, page.height * .5f)
        val strokeBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to tint,
                .5f to palette.rimHighlight,
                1f to if (page.tint == StudyDeckParticleTint.Lilac) palette.rimPrismA else palette.rimPrismB,
            ),
            start = topLeft + Offset(page.width * (.2f + .15f * cosine), 0f),
            end = topLeft + Offset(page.width * (.8f - .15f * sine), page.height),
        )
        rotate(page.angleDegrees, page.center) {
            drawRoundRect(
                brush = strokeBrush,
                topLeft = topLeft,
                size = Size(page.width, page.height),
                cornerRadius = CornerRadius(page.corner),
                style = outline,
                alpha = alpha * .42f * page.strength,
            )
        }
    }
}

private fun DrawScope.drawParticleTracks(
    motion: StudyCardParticleMotion,
    q: Float,
    phase: Float,
    sine: Float,
    cosine: Float,
    extraWeight: Float,
    palette: ParticlePalette,
    viewport: Rect,
    protectedRects: List<Rect>,
    rtl: Boolean,
) {
    val blend = studyCardParticleSmooth(0f, 1f, q)
    val surplus = 1f - studyCardParticleSmooth(.06f, .55f, q)
    val brightness = 1f + sine * .05f
    val tracks = motion.tracks
    val limit = if (extraWeight <= 0f) minOf(tracks.size, LightweightParticleCapacity) else tracks.size
    val glintStroke = .5.dp.toPx()
    for (index in 0 until limit) {
        val track = tracks[index]
        val ownerWeight = if (track.id.owner == null) surplus else 1f
        val quality = if (track.lightweight) 1f else extraWeight
        if (quality * ownerWeight <= 0f) continue
        val center = track.position(q) + track.drift(sine, cosine, blend, density, rtl)
        val radius = track.radiusDp * density * (1f - .14f * blend)
        val bright = track.tint == StudyDeckParticleTint.Gold || track.tint == StudyDeckParticleTint.Pearl
        val glowRadius = radius * if (bright) 2.5f else 1.45f
        val visibility = studyCardParticleVisibility(center, glowRadius, viewport, protectedRects, density)
        if (visibility <= 0f) continue
        val sparkle = if (bright) studyCardSparkleFactor(phase, (track.id.ordinal % 19) / 19f) else 1f
        val alpha = (track.opacity * brightness * quality * ownerWeight * visibility * (0.78f + 0.22f * sparkle)).coerceIn(0f, 1f)
        val tint = palette.colors[track.tint.ordinal]
        if (!track.lightweight) {
            val haloAlpha = if (bright) alpha * .22f else alpha * .10f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(tint.copy(alpha = haloAlpha), Color.Transparent),
                    center = center,
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = center,
            )
        }
        drawCircle(tint, radius, center, alpha = alpha)
        if (bright && !track.lightweight && extraWeight > .2f) {
            val reach = radius * 1.75f * (0.75f + 0.25f * sparkle)
            val glintColor = palette.rimHighlight.copy(alpha = alpha * .44f * palette.glintStrength * extraWeight)
            drawLine(glintColor, center - Offset(reach, 0f), center + Offset(reach, 0f), glintStroke)
            drawLine(glintColor, center - Offset(0f, reach), center + Offset(0f, reach), glintStroke)
        }
    }
}
