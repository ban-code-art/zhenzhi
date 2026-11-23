package com.example.myapplication.ui.components.learning

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.ui.theme.UiRoles
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val StudyCardShape = RoundedCornerShape(24.dp)
private val LocalStudyCardReadability = staticCompositionLocalOf<StudyCardReadabilityState?> { null }

/** The caller keeps its existing content, measurements, semantics and action. */
@Composable
internal fun StudyCardAtmosphere(
    layers: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    motionEnabled: Boolean = interactive,
    localParticles: Boolean = true,
    content: @Composable () -> Unit,
) {
    val visibleLayers = layers.coerceIn(0, 2)
    val style = rememberStudyCardIridescentStyle()
    val renderer = rememberStudyCardIridescentRenderer()
    val density = LocalDensity.current.density
    val readability = remember(density) { StudyCardReadabilityState(6f * density) }
    val motionScale = rememberMotionDurationScaleFactor()
    val activeMotion = motionEnabled && motionScale > 0f && motionScale.isFinite()
    val phase = rememberStudyCardPhase(motionEnabled)
    val tiltState = rememberStudyCardTiltState(activeMotion)
    val pointerScope = rememberCoroutineScope()
    val pressScale = remember { Animatable(1f) }
    val pointerParallaxX = remember { Animatable(0f) }
    val pointerParallaxY = remember { Animatable(0f) }
    val activeMotionState = rememberUpdatedState(activeMotion)

    LaunchedEffect(activeMotion) {
        if (!activeMotion) {
            pressScale.snapTo(1f)
            pointerParallaxX.snapTo(0f)
            pointerParallaxY.snapTo(0f)
            tiltState.pointerX = 0f
            tiltState.pointerY = 0f
        }
    }

    val backBorder = remember(style) {
        BorderStroke(
            .9.dp,
            Brush.linearGradient(
                listOf(style.backBorderStart, style.rimPrismA.copy(alpha = .32f), style.backBorderEnd),
            ),
        )
    }

    val pointerTracker = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            var pressed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!activeMotionState.value) continue
                val downChange = event.changes.firstOrNull { it.pressed }
                if (downChange != null) {
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val nx = ((downChange.position.x / w) * 2f - 1f).coerceIn(-1f, 1f)
                    val ny = ((downChange.position.y / h) * 2f - 1f).coerceIn(-1f, 1f)
                    tiltState.pointerX = nx
                    tiltState.pointerY = ny
                    if (!pressed) {
                        pressed = true
                        pointerScope.launch {
                            pressScale.animateTo(0.988f, spring(stiffness = 500f, dampingRatio = 0.85f))
                        }
                    }
                    pointerScope.launch {
                        pointerParallaxX.snapTo(nx)
                        pointerParallaxY.snapTo(ny)
                    }
                } else if (pressed) {
                    pressed = false
                    tiltState.pointerX = 0f
                    tiltState.pointerY = 0f
                    pointerScope.launch {
                        pressScale.animateTo(1f, spring(stiffness = 380f, dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                    pointerScope.launch {
                        pointerParallaxX.animateTo(0f, spring(stiffness = 220f, dampingRatio = 0.62f))
                    }
                    pointerScope.launch {
                        pointerParallaxY.animateTo(0f, spring(stiffness = 220f, dampingRatio = 0.62f))
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxWidth().padding(end = (4 * visibleLayers).dp, bottom = (8 * visibleLayers).dp)) {
        Box(Modifier.matchParentSize().drawWithCache {
            val silhouette = studyCardPath(size, 24.dp.toPx())
            onDrawBehind {
                // Keep the breathing halo outside the card body, including the protected text background.
                clipPath(silhouette, ClipOp.Difference) { drawStudyHalo(style, phase.value) }
            }
        })
        if (visibleLayers == 2) {
            Surface(
                modifier = Modifier.matchParentSize().offset(x = 8.dp, y = 14.dp),
                shape = StudyCardShape,
                color = style.farPlate,
                border = backBorder,
                shadowElevation = 3.dp,
            ) {}
        }
        if (visibleLayers >= 1) {
            Surface(
                modifier = Modifier.matchParentSize().offset(x = 4.dp, y = 7.dp),
                shape = StudyCardShape,
                color = style.nearPlate,
                border = backBorder,
                shadowElevation = 3.dp,
            ) {}
        }
        Box(Modifier.matchParentSize().drawWithCache {
            onDrawBehind {
                if (localParticles && readability.ready) {
                    drawStudyParticles(StudyParticleDepth.Behind, style, phase.value, readability.protectedRects)
                }
            }
        })
        Surface(
            onClick = { if (interactive) onClick() },
            enabled = interactive,
            modifier = modifier.fillMaxWidth().then(pointerTracker).graphicsLayer {
                val (rotX, rotY) = studyCardTiltParallaxDegrees(pointerParallaxX.value, pointerParallaxY.value)
                rotationX = rotX
                rotationY = rotY
                scaleX = pressScale.value
                scaleY = pressScale.value
                cameraDistance = 14f * density
                shape = StudyCardShape
                shadowElevation = 11.dp.toPx()
                ambientShadowColor = style.shadow.copy(alpha = if (style.dark) .22f else .16f)
                spotShadowColor = style.shadow.copy(alpha = if (style.dark) .28f else .20f)
                clip = false
            },
            shape = StudyCardShape,
            color = Color.Transparent,
            contentColor = UiRoles.foreground,
        ) {
            CompositionLocalProvider(LocalStudyCardReadability provides readability) {
                Box(Modifier.onGloballyPositioned(readability::setSurface).drawWithCache {
                    val corners = CornerRadius(24.dp.toPx())
                    val silhouette = studyCardPath(size, corners.x)
                    val baseNacre = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to style.baseTop,
                            .32f to style.baseMid,
                            .68f to style.baseBottom,
                            1f to style.baseMid,
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width * .95f, size.height * 1.35f),
                    )
                    val baseBloomA = Brush.radialGradient(
                        colors = listOf(style.baseAccentA, Color.Transparent),
                        center = Offset(size.width * .18f, size.height * .22f),
                        radius = (size.width * .62f).coerceAtLeast(1f),
                    )
                    val baseBloomB = Brush.radialGradient(
                        colors = listOf(style.baseAccentB, Color.Transparent),
                        center = Offset(size.width * .82f, size.height * .78f),
                        radius = (size.width * .65f).coerceAtLeast(1f),
                    )
                    val baseDiagonalRibbon = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            .24f to style.iridescentB.copy(alpha = if (style.dark) .15f else .22f),
                            .50f to style.iridescentA.copy(alpha = if (style.dark) .14f else .20f),
                            .76f to style.iridescentC.copy(alpha = if (style.dark) .16f else .22f),
                            1f to Color.Transparent,
                        ),
                        start = Offset(0f, size.height * .95f),
                        end = Offset(size.width, size.height * .05f),
                    )
                    val grain = (0 until 96).map { index ->
                        Offset(
                            ((index * 37 + 11) % 101) / 101f * size.width,
                            ((index * 53 + 17) % 103) / 103f * size.height,
                        )
                    }
                    val grainRadius = .28.dp.toPx()
                    val lipHeight = 5.dp.toPx()
                    val lip = Brush.verticalGradient(
                        listOf(Color.Transparent, style.lipGlow),
                        startY = size.height - lipHeight,
                        endY = size.height,
                    )
                    val featherPx = 12.dp.toPx()
                    val bevelInset = 2.0.dp.toPx()
                    val outerInset = .68.dp.toPx()
                    onDrawWithContent {
                        val currentPhase = phase.value
                        val tiltX = if (activeMotion) tiltState.combinedX else 0f
                        val tiltY = if (activeMotion) tiltState.combinedY else 0f
                        clipPath(silhouette) {
                            drawRect(baseNacre)
                            drawRect(baseBloomA)
                            drawRect(baseBloomB)
                            drawRect(baseDiagonalRibbon)
                            grain.forEach { drawCircle(style.grainTint, grainRadius, it) }
                            clipRect(top = (size.height - lipHeight).coerceAtLeast(0f)) { drawRect(lip) }
                            if (readability.ready) {
                                withStudyReadabilityClip(readability.protectedRects) {
                                    drawRect(
                                        brush = renderer.surfaceBrush(
                                            size = size,
                                            phase = currentPhase,
                                            tiltX = tiltX,
                                            tiltY = tiltY,
                                            style = style,
                                            protectedRects = readability.protectedRects,
                                            featherPx = featherPx,
                                        ),
                                    )
                                }
                            }
                        }
                        drawContent()
                        if (localParticles && readability.ready) {
                            clipPath(silhouette) {
                                drawStudyParticles(StudyParticleDepth.Front, style, currentPhase, readability.protectedRects)
                            }
                        }
                        // Inner crystalline bevel + outer dynamic iridescent edge highlight.
                        drawRoundRect(
                            color = style.innerBevel,
                            topLeft = Offset(bevelInset, bevelInset),
                            size = Size((size.width - 2 * bevelInset).coerceAtLeast(0f), (size.height - 2 * bevelInset).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius((corners.x - bevelInset).coerceAtLeast(0f)),
                            style = Stroke(.7.dp.toPx()),
                        )
                        drawRoundRect(
                            brush = renderer.rimBrush(size, currentPhase, tiltX, tiltY, style),
                            topLeft = Offset(outerInset, outerInset),
                            size = Size((size.width - 2 * outerInset).coerceAtLeast(0f), (size.height - 2 * outerInset).coerceAtLeast(0f)),
                            cornerRadius = CornerRadius((corners.x - outerInset).coerceAtLeast(0f)),
                            style = Stroke(1.35.dp.toPx()),
                        )
                    }
                }) { content() }
            }
        }
    }
}

/** Marks the actual text or icon bounds without adding padding, input or accessibility nodes. */
@Composable
internal fun Modifier.studyCardReadability(): Modifier {
    val readability = LocalStudyCardReadability.current ?: return this
    val key = remember(readability) { Any() }
    DisposableEffect(readability, key) {
        readability.register(key)
        onDispose { readability.remove(key) }
    }
    return onGloballyPositioned { readability.setContent(key, it) }
}

private class StudyCardReadabilityState(private val clearancePx: Float) {
    private var surface: LayoutCoordinates? = null
    private val coordinates = LinkedHashMap<Any, LayoutCoordinates>()
    private val regions = mutableStateMapOf<Any, StudyReadabilityRect?>()

    val ready: Boolean get() = regions.isNotEmpty() && regions.values.all { it != null }
    val protectedRects: List<StudyReadabilityRect> get() = regions.values.filterNotNull()

    fun register(key: Any) { if (!regions.containsKey(key)) regions[key] = null }
    fun remove(key: Any) { coordinates.remove(key); regions.remove(key) }
    fun setSurface(value: LayoutCoordinates) {
        surface = value
        coordinates.forEach { (key, child) -> update(key, child) }
    }
    fun setContent(key: Any, value: LayoutCoordinates) {
        coordinates[key] = value
        update(key, value)
    }
    private fun update(key: Any, child: LayoutCoordinates) {
        val root = surface
        val bounds = if (root?.isAttached == true && child.isAttached) {
            runCatching { root.localBoundingBoxOf(child, clipBounds = false) }.getOrNull()
        } else null
        regions[key] = bounds?.let {
            StudyReadabilityRect(
                it.left - clearancePx,
                it.top - clearancePx,
                it.right + clearancePx,
                it.bottom + clearancePx,
            )
        }
    }
}

@Composable
private fun rememberMotionDurationScaleFactor(): Float {
    val scale = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        val durationScale = currentCoroutineContext()[MotionDurationScale]
        snapshotFlow { durationScale?.scaleFactor ?: 1f }.collectLatest {
            scale.floatValue = it
        }
    }
    return scale.floatValue
}

@Composable
private fun rememberStudyCardPhase(enabled: Boolean): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, enabled) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val durationScale = currentCoroutineContext()[MotionDurationScale]
            snapshotFlow { durationScale?.scaleFactor ?: 1f }.collectLatest { scale ->
                // Freeze at the current frame when system animations are disabled.
                if (!scale.isFinite() || scale <= 0f) return@collectLatest
                val cycleNanos = StudyCardCycleSeconds.toDouble() * 1_000_000_000.0
                var elapsedNanos = phase.floatValue.toDouble() * cycleNanos
                var previousFrame: Long? = null
                var lastPublication: Long? = null
                while (currentCoroutineContext().isActive) {
                    withInfiniteAnimationFrameNanos { now ->
                        val delta = previousFrame?.let { (now - it).coerceIn(0L, 100_000_000L) } ?: 0L
                        previousFrame = now
                        elapsedNanos = (elapsedNanos + delta / scale.coerceAtLeast(.01f)) % cycleNanos
                        if (lastPublication == null || now - lastPublication!! >= 33_333_333L) {
                            phase.floatValue = (elapsedNanos / cycleNanos).toFloat()
                            lastPublication = now
                        }
                    }
                }
            }
        }
    }
    return phase
}

private fun studyCardPath(size: Size, radius: Float): Path = Path().apply {
    addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius)))
}

private fun DrawScope.withStudyReadabilityClip(regions: List<StudyReadabilityRect>, draw: DrawScope.() -> Unit) {
    val excluded = Path().apply {
        regions.forEach { addRect(Rect(it.left, it.top, it.right, it.bottom)) }
    }
    clipPath(excluded, ClipOp.Difference, block = draw)
}

private fun DrawScope.drawStudyHalo(style: StudyCardIridescentStyle, phase: Float) {
    val breath = .088f + .022f * sin(phase.toDouble() * 4.0 * PI).toFloat()
    val padding = 10.dp.toPx()
    val shiftX = sin(phase.toDouble() * 2.0 * PI).toFloat() * size.width * .08f
    val center = Offset(size.width * .5f + shiftX, size.height * .6f)
    drawRoundRect(
        brush = Brush.radialGradient(
            listOf(
                style.haloPrimary.copy(alpha = breath),
                style.haloSecondary.copy(alpha = breath * .58f),
                Color.Transparent,
            ),
            center = center,
            radius = (size.width * .64f).coerceAtLeast(1f),
        ),
        topLeft = Offset(-padding, -padding),
        size = Size(size.width + padding * 2, size.height + padding * 2),
        cornerRadius = CornerRadius(34.dp.toPx()),
    )
}

private fun DrawScope.drawStudyParticles(
    depth: StudyParticleDepth,
    style: StudyCardIridescentStyle,
    phase: Float,
    regions: List<StudyReadabilityRect>,
) {
    val rtl = layoutDirection == LayoutDirection.Rtl
    for (seed in StudyCardParticleSeeds) {
        if (seed.depth != depth) continue
        drawParticleSeed(seed, depth, style, phase, regions, rtl, isDust = false)
    }
    for (seed in StudyCardPrismaticDustSeeds) {
        if (seed.depth != depth) continue
        drawParticleSeed(seed, depth, style, phase, regions, rtl, isDust = true)
    }
}

private fun DrawScope.drawParticleSeed(
    seed: StudyParticleSeed,
    depth: StudyParticleDepth,
    style: StudyCardIridescentStyle,
    phase: Float,
    regions: List<StudyReadabilityRect>,
    rtl: Boolean,
    isDust: Boolean,
) {
    val particle = studyCardParticleAt(seed, size.width, size.height, density, phase, rtl)
    if (!isStudyCardParticleClear(particle.x, particle.y, particle.glowRadius, regions)) return
    val center = Offset(particle.x, particle.y)
    val twinkle = studyCardSparkleFactor(phase, seed.phaseOffset)
    val tint = when (particle.tint) {
        StudyParticleTint.MistBlue -> style.particlePrimary
        StudyParticleTint.Lilac -> style.particleTransition
        StudyParticleTint.Pearl -> if (isDust) style.particleWarmGold else style.particlePearl
    }
    val haze = when (particle.tint) {
        StudyParticleTint.Pearl -> style.particleSecondary
        else -> tint
    }
    val alphaScale = if (isDust) 0.72f * twinkle else twinkle
    val effectiveOpacity = (particle.opacity * alphaScale).coerceIn(0f, 1f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                haze.copy(alpha = effectiveOpacity * .28f),
                tint.copy(alpha = effectiveOpacity * .09f),
                Color.Transparent,
            ),
            center = center,
            radius = particle.glowRadius,
        ),
        radius = particle.glowRadius,
        center = center,
    )
    drawCircle(tint.copy(alpha = effectiveOpacity * .86f), particle.radius, center)
    if (depth == StudyParticleDepth.Front && particle.tint == StudyParticleTint.Pearl) {
        val reach = particle.radius * (if (isDust) 1.5f else 1.85f) * (0.75f + 0.25f * twinkle)
        val glint = style.particlePearl.copy(alpha = effectiveOpacity * .42f * style.glintStrength)
        val stroke = .55.dp.toPx()
        drawLine(glint, center - Offset(reach, 0f), center + Offset(reach, 0f), stroke)
        drawLine(glint, center - Offset(0f, reach), center + Offset(0f, reach), stroke)
    }
}
