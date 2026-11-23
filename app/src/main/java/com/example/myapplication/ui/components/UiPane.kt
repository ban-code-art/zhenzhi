package com.example.myapplication.ui.components

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.UiRoles
import kotlin.math.roundToInt

internal enum class UiPaneRole {
    Regular, Tinted, Menu, EditMenu, Alert, Banner, Pill, Lens, LensContent;

    companion object {
        val Dock: UiPaneRole get() = Pill
    }
}

internal val LocalUiGlassQuality = staticCompositionLocalOf { UiGlassQualitySnapshot() }
/** Provides the current sibling backdrop to overlay actions without risking self-sampling cycles. */
internal val LocalUiBackdrop = compositionLocalOf<UiBackdrop?> { null }
/** Allows off-screen or deferred work to yield while navigation or scrolling is active. */
internal val LocalUiMotionBusy = compositionLocalOf { false }

internal class UiBackdrop(val layer: GraphicsLayer) {
    private var owner: Any? = null
    private var host by mutableStateOf<View?>(null)
    private var recorded by mutableStateOf(false)
    private var released by mutableStateOf(false)
    var origin by mutableStateOf<Offset?>(null)
        private set

    fun position(owner: Any, host: View, origin: Offset) {
        if (this.owner !== owner) recorded = false
        this.owner = owner
        released = false
        this.host = host
        this.origin = origin
    }

    fun markRecorded(owner: Any) {
        if (this.owner === owner && !recorded) recorded = true
    }

    fun release(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        host = null
        released = true
        recorded = false
        origin = null
    }

    fun captureFor(window: View): UiGlassCapture {
        val source = host ?: return if (released) UiGlassCapture.Unavailable else UiGlassCapture.Pending
        // Layout coordinates in a modal window cannot address a different window's display list.
        return when {
            source.rootView !== window.rootView -> UiGlassCapture.ForeignWindow
            !recorded -> UiGlassCapture.Pending
            else -> UiGlassCapture.Ready
        }
    }
}

@Composable
internal fun rememberUiBackdrop(): UiBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { UiBackdrop(layer) }
}

/** Records only the content behind the overlay, with the overlay drawn by a sibling. */
@Composable
internal fun UiGlassBackdrop(
    backdrop: UiBackdrop,
    modifier: Modifier = Modifier,
    captureEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val captureModifier = if (captureEnabled && Build.VERSION.SDK_INT >= 31 &&
        LocalUiGlassQuality.current.desired != UiGlassQuality.None
    ) {
        val host = LocalView.current
        val owner = remember(backdrop, host) { Any() }
        DisposableEffect(backdrop, owner) {
            onDispose { backdrop.release(owner) }
        }
        Modifier.onGloballyPositioned {
            backdrop.position(owner, host, it.positionInWindow())
        }.drawWithContent {
            // Draw the scene into one display list shared by the scene and its panes.
            // The normal draw pass supplies the latest scrolling content without a second capture.
            backdrop.layer.record { this@drawWithContent.drawContent() }
            backdrop.markRecorded(owner)
            drawLayer(backdrop.layer)
        }
    } else Modifier
    CompositionLocalProvider(LocalUiBackdrop provides null) {
        Box(modifier.then(captureModifier), content = content)
    }
}

/** Supplies a glass sample without putting the live content on the sampled display list. */
@Composable
internal fun UiGlassImageBackdrop(backdrop: UiBackdrop, image: ImageBitmap, modifier: Modifier) {
    val host = LocalView.current
    val owner = remember(backdrop, host, image) { Any() }
    if (Build.VERSION.SDK_INT >= 31) {
        DisposableEffect(backdrop, owner) {
            onDispose { backdrop.release(owner) }
        }
        Box(modifier
            .onGloballyPositioned { backdrop.position(owner, host, it.positionInWindow()) }
            .drawWithCache {
                val destination = IntSize(size.width.roundToInt(), size.height.roundToInt())
                onDrawBehind {
                    backdrop.layer.record { drawImage(image, dstSize = destination) }
                    backdrop.markRecorded(owner)
                }
            },
        )
    }
}

private data class PaneMaterial(
    val tint: Color,
    val fallback: Color,
    val underlay: Color = fallback.copy(alpha = .4f),
    val blur: Dp = 2.5.dp,
    val haze: Float = 0f,
    val hazeBlur: Dp = 0.dp,
    val saturation: Float = 1.5f,
    val brightness: Float = 1f,
    val contrast: Float = 0f,
    val rim: Float = .28f,
    val edge: Brush,
    val edgeWidth: Dp = 1.2.dp,
)

private fun whiteRim(dark: Boolean, tail: Float, side: Float, shine: Float): Brush =
    Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = (side + shine + if (dark) .08f else .22f).coerceAtMost(1f)),
            if (dark) Color.White.copy(alpha = tail * .7f) else Color.Black.copy(alpha = .11f),
            Color.White.copy(alpha = (side + shine * .75f).coerceAtMost(.96f)),
        ),
    )

private fun paneMaterial(
    role: UiPaneRole,
    colors: ColorScheme,
    dark: Boolean,
    tintedColor: Color,
    lensIntensity: Float,
): PaneMaterial {
    // The captured backdrop carries the theme. Keep glass truly translucent (avoid stacked
    // opaque underlay) and align haze/refraction with prism_ui's LiquidGlassStyle.
    val darkTint = lerp(colors.surfaceContainer, colors.onSurface, .06f)
    val regularTint = if (dark) darkTint.copy(alpha = .24f) else Color(0x28FFFFFF)
    val regularFallback = if (dark) lerp(colors.surfaceContainerHigh, Color.White, .08f).copy(alpha = .54f) else Color(0x74FFFFFF)
    val regularEdge = whiteRim(dark, .16f, if (dark) .30f else .36f, if (dark) .26f else .58f)
    return when (role) {
        UiPaneRole.Regular -> PaneMaterial(
            regularTint, regularFallback,
            underlay = Color.Transparent,
            blur = 0.dp,
            haze = .34f,
            hazeBlur = 7.dp,
            saturation = if (dark) 2.25f else 1.98f,
            brightness = if (dark) 1.04f else 1.01f,
            contrast = if (dark) -.22f else .14f,
            rim = if (dark) .46f else .64f,
            edge = regularEdge,
            edgeWidth = .9.dp,
        )
        UiPaneRole.Tinted -> PaneMaterial(
            tint = tintedColor.copy(alpha = if (dark) .80f else .90f),
            fallback = tintedColor,
            underlay = Color.Transparent,
            blur = 0.dp,
            haze = .26f,
            hazeBlur = 5.5.dp,
            saturation = 1.48f,
            brightness = if (dark) 1.07f else 1.02f,
            contrast = if (dark) -.10f else .08f,
            rim = if (dark) .50f else .62f,
            edge = whiteRim(dark, .16f, if (dark) .32f else .38f, if (dark) .28f else .56f),
            edgeWidth = .9.dp,
        )
        UiPaneRole.Menu, UiPaneRole.Alert -> {
            val alert = role == UiPaneRole.Alert
            val fallback = if (alert) colors.surfaceContainerHigh else colors.surfaceContainerLow
            val tint = if (dark) darkTint.copy(alpha = .63f) else colors.surfaceBright.copy(alpha = .55f)
            val side = if (alert) { if (dark) .75f else .35f } else { if (dark) .27f else .25f }
            val flat = if (alert) { if (dark) .03f else .12f } else { if (dark) .02f else .09f }
            val shine = if (dark) .22f else .16f
            PaneMaterial(
                tint, fallback,
                blur = if (alert) 16.dp else 12.dp,
                saturation = if (dark) 1.75f else 2f,
                brightness = if (dark) 1.8f else 1.1f,
                contrast = if (dark) -.53f else 0f,
                rim = 0f,
                edge = whiteRim(dark, flat, side, shine),
                edgeWidth = 1.dp,
            )
        }
        UiPaneRole.EditMenu -> PaneMaterial(
            if (dark) Color(0x85454545) else Color(0x85FFFFFF), regularFallback,
            blur = 0.dp,
            haze = .71f,
            hazeBlur = 15.6.dp,
            saturation = if (dark) 1.8f else 1.97f,
            brightness = if (dark) 1.55f else .98f,
            contrast = if (dark) -.33f else 0f,
            edge = regularEdge,
        )
        UiPaneRole.Banner -> PaneMaterial(
            if (dark) lerp(colors.surfaceContainer, colors.onSurface, .20f).copy(alpha = .42f)
            else Color(0x86F9F9F9),
            colors.surfaceContainerHigh,
            blur = 0.dp,
            haze = .72f,
            hazeBlur = 15.6.dp,
            saturation = if (dark) 1.925f else 2.1f,
            edge = regularEdge,
        )
        UiPaneRole.Pill -> {
            // Match FlClash / prism_ui LiquidGlassStyle.pill: clear translucent optical center
            // so cards/text beneath remain legible, with bevel refraction and curved rim highlight.
            val base = if (dark) lerp(Color(0xFF141815), colors.surfaceContainer, .26f)
            else lerp(Color(0xFFFAFAFC), colors.surfaceContainer, .08f)
            PaneMaterial(
                base.copy(alpha = if (dark) .20f else .14f), base.copy(alpha = .78f),
                underlay = Color.Transparent,
                blur = 0.dp,
                haze = .38f,
                hazeBlur = 7.5.dp,
                saturation = if (dark) 1.98f else 2.08f,
                brightness = if (dark) 1.06f else 1.01f,
                contrast = if (dark) -.22f else .14f,
                rim = if (dark) .46f else .62f,
                edge = Brush.verticalGradient(
                    if (dark) listOf(Color.White.copy(alpha = .50f), Color.White.copy(alpha = .14f), Color.White.copy(alpha = .32f))
                    else listOf(Color.White.copy(alpha = .94f), Color.Black.copy(alpha = .12f), Color.White.copy(alpha = .86f)),
                ),
                edgeWidth = .9.dp,
            )
        }
        UiPaneRole.Lens, UiPaneRole.LensContent -> {
            val navigationLens = role == UiPaneRole.Lens
            PaneMaterial(
                if (navigationLens) Color.White.copy(alpha = (if (dark) .04f else .10f) * lensIntensity)
                else Color.Transparent,
                Color.White.copy(alpha = if (dark) .08f else .18f),
                underlay = Color.Transparent,
                blur = 0.dp,
                edge = Brush.verticalGradient(
                    if (!navigationLens) listOf(Color.Transparent, Color.Transparent)
                    else if (dark) listOf(
                        Color.White.copy(alpha = .58f * lensIntensity),
                        Color.White.copy(alpha = .16f * lensIntensity),
                        Color.White.copy(alpha = .28f * lensIntensity),
                    ) else listOf(
                        Color.White.copy(alpha = .94f * lensIntensity),
                        Color.Black.copy(alpha = .10f * lensIntensity),
                        Color.White.copy(alpha = .80f * lensIntensity),
                    ),
                ),
                edgeWidth = .9.dp,
            )
        }
    }
}

@Composable
internal fun UiPane(
    role: UiPaneRole,
    shape: Shape,
    modifier: Modifier = Modifier,
    backdrop: UiBackdrop? = null,
    secondaryBackdrop: UiBackdrop? = null,
    cornerRadiusDp: Dp? = null,
    lensIntensity: Float = 1f,
    glowCenter: Offset? = null,
    glowStrength: Float = 0f,
    glowReachDp: Dp = 28.dp,
    glowWash: Float = 0.7f,
    tintedColor: Color = Color.Unspecified,
    onQualityChanged: ((UiGlassQualitySnapshot) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val view = LocalView.current
    val desired = LocalUiGlassQuality.current.desired
    val capture = backdrop?.captureFor(view) ?: UiGlassCapture.Unavailable
    val accelerated = view.isHardwareAccelerated
    var shaderReady by remember(role, view, backdrop, desired) { mutableStateOf<Boolean?>(null) }
    val quality = resolveUiGlassQuality(desired, Build.VERSION.SDK_INT, accelerated, capture, shaderReady)
    val qualityListener by rememberUpdatedState(onQualityChanged)
    if (onQualityChanged != null) {
        LaunchedEffect(quality) { qualityListener?.invoke(quality) }
    }
    val dark = UiRoles.dark
    val colors = MaterialTheme.colorScheme
    val color = if (tintedColor == Color.Unspecified) colors.primary else tintedColor
    val material = remember(role, colors, dark, color, lensIntensity) {
        paneMaterial(role, colors, dark, color, lensIntensity)
    }
    Box(modifier.clip(shape).border(material.edgeWidth, material.edge, shape)) {
        if (backdrop != null && capture == UiGlassCapture.Ready &&
            desired != UiGlassQuality.None && accelerated && Build.VERSION.SDK_INT >= 31
        ) {
            SampledPane(
                backdrop = backdrop,
                secondaryBackdrop = secondaryBackdrop?.takeIf { it.captureFor(view) == UiGlassCapture.Ready },
                material = material,
                dark = dark,
                role = role,
                desired = desired,
                shape = shape,
                cornerRadiusDp = cornerRadiusDp,
                lensIntensity = lensIntensity,
                glowCenter = glowCenter,
                glowStrength = glowStrength,
                glowReachDp = glowReachDp,
                glowWash = glowWash,
                onShaderReady = { shaderReady = it },
                modifier = Modifier.matchParentSize(),
            )
        } else {
            StandaloneGlassPane(
                material = material,
                dark = dark,
                role = role,
                shape = shape,
                cornerRadiusDp = cornerRadiusDp,
                glowCenter = glowCenter,
                glowStrength = glowStrength,
                glowReachDp = glowReachDp,
                glowWash = glowWash,
                modifier = Modifier.matchParentSize(),
            )
        }
        content()
    }
}

@Composable
private fun StandaloneGlassPane(
    material: PaneMaterial,
    dark: Boolean,
    role: UiPaneRole,
    shape: Shape,
    cornerRadiusDp: Dp?,
    glowCenter: Offset?,
    glowStrength: Float,
    glowReachDp: Dp,
    glowWash: Float,
    modifier: Modifier = Modifier,
) {
    val shaderReadyRole = role == UiPaneRole.Regular || role == UiPaneRole.Tinted || role == UiPaneRole.Pill
    val shader = if (Build.VERSION.SDK_INT >= 33 && shaderReadyRole) {
        remember { runCatching { RuntimeShader(StandaloneGlassShaderSource) }.getOrNull() }
    } else null
    Box(
        modifier.drawWithCache {
            val paneCenter = Offset(size.width / 2f, size.height / 2f)
            val base = when (role) {
                UiPaneRole.Lens, UiPaneRole.LensContent -> material.fallback
                UiPaneRole.Tinted -> material.tint.compositeOver(material.fallback.copy(alpha = if (dark) .78f else .90f))
                else -> material.tint.compositeOver(material.fallback)
            }
            val tintedFlag = if (role == UiPaneRole.Tinted) 1f else 0f
            val cornerPx = cornerRadiusDp?.toPx() ?: if (role == UiPaneRole.Pill) -1f else {
                when (val outline = shape.createOutline(size, layoutDirection, this)) {
                    is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
                    is Outline.Rectangle -> 0f
                    is Outline.Generic -> 20.dp.toPx()
                }
            }
            val brush = if (Build.VERSION.SDK_INT >= 33 && shader != null) {
                runCatching {
                    val c = base.toArgb()
                    shader.setFloatUniform("size", maxOf(size.width, 1f), maxOf(size.height, 1f))
                    shader.setFloatUniform("density", density)
                    shader.setFloatUniform("corner", cornerPx)
                    shader.setFloatUniform("bevel", 15.dp.toPx())
                    shader.setFloatUniform("rimStrength", material.rim.coerceAtLeast(.34f))
                    shader.setFloatUniform(
                        "baseColor",
                        ((c ushr 16) and 0xFF) / 255f,
                        ((c ushr 8) and 0xFF) / 255f,
                        (c and 0xFF) / 255f,
                        ((c ushr 24) and 0xFF) / 255f,
                    )
                    shader.setFloatUniform("dark", if (dark) 1f else 0f)
                    shader.setFloatUniform("tinted", tintedFlag)
                    val g = glowCenter ?: paneCenter
                    shader.setFloatUniform("glowCenter", g.x, g.y)
                    shader.setFloatUniform("glowStrength", glowStrength)
                    shader.setFloatUniform("glowReach", maxOf(glowReachDp.toPx(), 1f))
                    shader.setFloatUniform("glowWash", glowWash)
                    ShaderBrush(shader)
                }.getOrNull()
            } else null
            val verticalHighlight = Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (dark) .18f else if (role == UiPaneRole.Tinted) .24f else .56f),
                    Color.Transparent,
                    Color.White.copy(alpha = if (dark) .08f else if (role == UiPaneRole.Tinted) .12f else .28f),
                ),
            )
            val touchGlow = if (glowStrength > 0f) Brush.radialGradient(
                listOf(Color.White.copy(alpha = glowStrength.coerceIn(0f, 1f)), Color.Transparent),
                center = glowCenter ?: paneCenter,
                radius = glowReachDp.toPx().coerceAtLeast(1f),
            ) else null
            onDrawBehind {
                if (brush != null) {
                    drawRect(brush)
                } else {
                    drawRect(base)
                    drawRect(verticalHighlight)
                    if (touchGlow != null) drawRect(touchGlow)
                }
            }
        },
    )
}

@RequiresApi(31)
private data class SampleOffsets(val primary: Offset, val secondary: Offset?)

@Composable
private fun SampledPane(
    backdrop: UiBackdrop,
    secondaryBackdrop: UiBackdrop?,
    material: PaneMaterial,
    dark: Boolean,
    role: UiPaneRole,
    desired: UiGlassQuality,
    shape: Shape,
    cornerRadiusDp: Dp?,
    lensIntensity: Float,
    glowCenter: Offset?,
    glowStrength: Float,
    glowReachDp: Dp,
    glowWash: Float,
    onShaderReady: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val sampledLayer = rememberGraphicsLayer()
    val hazeLayers = if (material.haze > 0f && desired == UiGlassQuality.Ultra && Build.VERSION.SDK_INT >= 33) {
        rememberGraphicsLayer() to rememberGraphicsLayer()
    } else null
    var paneOrigin by remember { mutableStateOf<Offset?>(null) }
    val sampleOffsets by remember(backdrop, secondaryBackdrop) {
        derivedStateOf(structuralEqualityPolicy()) {
            val pane = paneOrigin ?: return@derivedStateOf null
            val origin = backdrop.origin ?: return@derivedStateOf null
            SampleOffsets(origin - pane, secondaryBackdrop?.origin?.minus(pane))
        }
    }
    val lens = role == UiPaneRole.Lens || role == UiPaneRole.LensContent
    val compiledShader = if (Build.VERSION.SDK_INT >= 33) {
        remember(lens) { runCatching { RuntimeShader(if (lens) LensShaderSource else PillShaderSource) }.getOrNull() }
    } else null
    Box(
        modifier.onGloballyPositioned { paneOrigin = it.positionInWindow() }
            .drawWithCache {
                val paneCenter = Offset(size.width / 2f, size.height / 2f)
                // Scrolling changes the captured content/origin, not the effect parameters.
                // Keep Android shader/blur chains until geometry or the visual settings change.
                val hazeBlurPx = material.hazeBlur.toPx()
                val blurPx = if (material.haze > 0f && desired == UiGlassQuality.Standard) hazeBlurPx * 0.52f else material.blur.toPx()
                val shaderEffect = if (Build.VERSION.SDK_INT >= 33 && compiledShader != null) {
                    runCatching {
                        if (lens) configureLensEffect(compiledShader, size.width, size.height, density,
                            lensIntensity, dark, role == UiPaneRole.LensContent, desired == UiGlassQuality.Ultra)
                        else {
                            val pill = role == UiPaneRole.Pill
                            val cornerPx = cornerRadiusDp?.toPx() ?: if (pill) -1f else {
                                when (val outline = shape.createOutline(size, layoutDirection, this)) {
                                    is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
                                    is Outline.Rectangle -> 0f
                                    is Outline.Generic -> 20.dp.toPx()
                                }
                            }
                            configurePillEffect(compiledShader, size.width, size.height, density, cornerPx,
                                blurPx, (if (pill) 16.dp else 15.dp).toPx(), (if (pill) 42.dp else 28.dp).toPx(),
                                material.saturation, material.brightness, material.contrast, material.rim,
                                glowCenter ?: paneCenter, glowStrength, glowReachDp.toPx(), glowWash)
                        }
                    }.getOrNull()
                } else null
                val fallbackBlurPx = when { hazeBlurPx > 0f -> hazeBlurPx * 0.55f; blurPx > 0f -> blurPx; else -> 2.5.dp.toPx() }
                sampledLayer.renderEffect = shaderEffect ?: BlurEffect(fallbackBlurPx, fallbackBlurPx, TileMode.Clamp)
                hazeLayers?.second?.apply {
                    alpha = material.haze
                    renderEffect = BlurEffect(hazeBlurPx, hazeBlurPx, TileMode.Clamp)
                }
                val glow = if (shaderEffect == null && glowStrength > 0f) Brush.radialGradient(
                    listOf(Color.White.copy(alpha = glowStrength.coerceIn(0f, 1f)), Color.Transparent),
                    center = glowCenter ?: paneCenter, radius = glowReachDp.toPx().coerceAtLeast(1f)) else null
                var recordedOffsets: SampleOffsets? = null
                onDrawWithContent {
                    val offsets = sampleOffsets
                    if (offsets == null) {
                        drawRect(material.tint.compositeOver(material.fallback))
                    } else {
                        // The display list references the source layer, so source content can
                        // update independently. Ignore sub-pixel translation jitter during animations.
                        val unchanged = recordedOffsets?.let { prev ->
                            kotlin.math.abs(prev.primary.x - offsets.primary.x) < 0.75f &&
                                kotlin.math.abs(prev.primary.y - offsets.primary.y) < 0.75f &&
                                prev.secondary == offsets.secondary
                        } == true
                        if (!unchanged) {
                            onShaderReady(shaderEffect != null)
                            fun DrawScope.drawBackdrop() {
                                drawRect(material.underlay)
                                val secondary = offsets.secondary
                                if (secondaryBackdrop != null && secondary != null) {
                                    translate(secondary.x, secondary.y) { drawLayer(secondaryBackdrop.layer) }
                                }
                                translate(offsets.primary.x, offsets.primary.y) { drawLayer(backdrop.layer) }
                            }
                            if (hazeLayers != null && shaderEffect != null) {
                                val (sharp, hazy) = hazeLayers
                                sharp.record { drawBackdrop() }
                                hazy.record { drawLayer(sharp) }
                                sampledLayer.record { drawLayer(sharp); drawLayer(hazy) }
                            } else sampledLayer.record { drawBackdrop() }
                            recordedOffsets = offsets
                        }
                        drawLayer(sampledLayer)
                        drawRect(material.tint)
                        if (glow != null) drawRect(glow)
                    }
                }
            },
    )
}


@RequiresApi(33)
private fun configurePillEffect(
    shader: RuntimeShader,
    width: Float,
    height: Float,
    density: Float,
    cornerPx: Float,
    blurPx: Float,
    bevelPx: Float,
    refractDepthPx: Float,
    saturation: Float,
    brightness: Float,
    contrast: Float,
    rimStrength: Float,
    glowCenter: Offset,
    glowStrength: Float,
    glowReachPx: Float,
    glowWash: Float,
): androidx.compose.ui.graphics.RenderEffect {
    shader.setFloatUniform("size", maxOf(width, 1f), maxOf(height, 1f))
    shader.setFloatUniform("density", density)
    shader.setFloatUniform("corner", cornerPx)
    shader.setFloatUniform("bevel", bevelPx)
    shader.setFloatUniform("refractDepth", refractDepthPx)
    shader.setFloatUniform("saturation", saturation)
    shader.setFloatUniform("brightness", brightness)
    shader.setFloatUniform("contrast", contrast)
    shader.setFloatUniform("rimStrength", rimStrength)
    shader.setFloatUniform("glowCenter", glowCenter.x, glowCenter.y)
    shader.setFloatUniform("glowStrength", glowStrength)
    shader.setFloatUniform("glowReach", maxOf(glowReachPx, 1f))
    shader.setFloatUniform("glowWash", glowWash)
    val outer = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
    val combined = if (blurPx > 0.5f) {
        val innerBlur = android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
        android.graphics.RenderEffect.createChainEffect(outer, innerBlur)
    } else {
        outer
    }
    return combined.asComposeRenderEffect()
}

@RequiresApi(33)
private fun configureLensEffect(
    shader: RuntimeShader,
    width: Float,
    height: Float,
    density: Float,
    intensity: Float,
    dark: Boolean,
    contentLens: Boolean,
    disperse: Boolean,
): androidx.compose.ui.graphics.RenderEffect {
    shader.setFloatUniform("size", maxOf(width, 1f), maxOf(height, 1f))
    shader.setFloatUniform("density", density)
    shader.setFloatUniform("intensity", intensity.coerceIn(0f, 1f))
    shader.setFloatUniform("dark", if (dark) 1f else 0f)
    shader.setFloatUniform("contentLens", if (contentLens) 1f else 0f)
    shader.setFloatUniform("disperse", if (disperse) 1f else 0f)
    return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect()
}

private const val PillShaderSource = """
    uniform shader backdrop;
    uniform float2 size;
    uniform float density;
    uniform float corner;
    uniform float bevel;
    uniform float refractDepth;
    uniform float saturation;
    uniform float brightness;
    uniform float contrast;
    uniform float rimStrength;
    uniform float2 glowCenter;
    uniform float glowStrength;
    uniform float glowReach;
    uniform float glowWash;

    const float3 kLuma = float3(0.2126, 0.7152, 0.0722);

    half4 main(float2 p) {
        float2 halfSize = max(size * 0.5, float2(1.0));
        float halfMinor = min(halfSize.x, halfSize.y);
        float r = corner >= 0.0 ? min(corner, halfMinor) : halfMinor;
        float2 centered = p - halfSize;
        float2 q = abs(centered) - halfSize + r;
        float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        float inset = max(-dist, 0.0);
        float2 rawN = (q.x > 0.0 && q.y > 0.0)
            ? q
            : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
        float2 sgn = float2(centered.x < 0.0 ? -1.0 : 1.0, centered.y < 0.0 ? -1.0 : 1.0);
        float2 normal = (dot(rawN, rawN) > 1e-6 ? normalize(rawN) : float2(0.0, 1.0)) * sgn;

        float effBevel = max(min(bevel, halfMinor * 0.45), 1.0);
        float rise = 1.0 - clamp(inset / effBevel, 0.0, 1.0);
        float depth = min(refractDepth, halfMinor * 0.45) * (1.0 - sqrt(max(0.0, 1.0 - rise * rise)));
        float2 samplePos = clamp(p - normal * depth, float2(1.0), max(size - float2(1.0), float2(1.0)));

        half4 accum = backdrop.eval(samplePos);
        float a = accum.a;
        float3 rgb = accum.rgb / max(a, 1e-3);
        rgb = mix(float3(dot(rgb, kLuma)), rgb, saturation) * brightness;
        rgb = (rgb - 0.5) * (1.0 + contrast) + 0.5;

        float band = clamp(1.0 - inset / max(3.6 * density, 1.0), 0.0, 1.0);
        float rise2 = normal.y * normal.y;
        float facing = mix(0.22, 1.0, rise2);
        float shine = facing * band * rimStrength;
        rgb += (1.0 - rgb) * clamp(shine, 0.0, 1.0);

        float hairline = clamp((0.8 * density - inset) / max(density, 1.0) + 0.5, 0.0, 1.0);
        rgb *= 1.0 - hairline * 0.075;

        if (glowStrength > 0.0001) {
            float2 dGlow = p - glowCenter;
            float radial = exp(-dot(dGlow, dGlow) / (glowReach * glowReach));
            rgb += glowStrength * mix(radial, 1.0, glowWash);
        }

        return half4(clamp(rgb * a, 0.0, 1.0), a);
    }
"""

private const val LensShaderSource = """
    uniform shader backdrop;
    uniform float2 size;
    uniform float density;
    uniform float intensity;
    uniform float dark;
    uniform float contentLens;
    uniform float disperse;

    half4 main(float2 p) {
        float2 halfSize = max(size * 0.5, float2(1.0));
        float halfMinor = min(halfSize.x, halfSize.y);
        float r = halfMinor;
        float2 centered = p - halfSize;
        float2 q = abs(centered) - halfSize + r;
        float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        float inset = max(-dist, 0.0);
        float2 rawN = (q.x > 0.0 && q.y > 0.0)
            ? q
            : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
        float2 sgn = float2(centered.x < 0.0 ? -1.0 : 1.0, centered.y < 0.0 ? -1.0 : 1.0);
        float2 normal = (dot(rawN, rawN) > 1e-6 ? normalize(rawN) : float2(0.0, 1.0)) * sgn;

        float bevel = max(min(mix(6.0, 11.2, contentLens) * density, halfMinor * 0.45), 1.0);
        float shiftAmount = (mix(3.8, -14.0, contentLens) * density * intensity)
            * smoothstep(0.0, bevel, inset)
            * (1.0 - smoothstep(bevel, halfMinor, inset));
        float2 maxCoord = max(size - float2(1.5), float2(1.5));
        float2 pos = clamp(p + normal * shiftAmount, float2(1.5), maxCoord);

        float partReach = 9.0 * density;
        float partHold = 6.0 * density;
        float dispScale = mix(1.15, 0.64, contentLens) * disperse * density * intensity
            * (0.6 + 0.4 * abs(normal.y) * max(1.0 - inset / max(partHold, 1.0), 0.0))
            * (1.0 - smoothstep(partHold, partReach, inset));
        float2 part = float2(normal.y, normal.x) * dispScale;

        half4 green = backdrop.eval(pos);
        half4 red = backdrop.eval(clamp(pos + part, float2(1.5), maxCoord));
        half4 blue = backdrop.eval(clamp(pos - part, float2(1.5), maxCoord));
        float a = max(green.a, 1e-3);
        half3 gRgb = green.rgb / a;
        float rChan = red.a > 0.05 ? (red.r / red.a) : gRgb.r;
        float bChan = blue.a > 0.05 ? (blue.b / blue.a) : gRgb.b;
        float3 rgb = float3(rChan, gRgb.g, bChan);

        rgb *= 1.0 - (dark > 0.5 ? 0.04 : 0.06) * intensity * abs(normal.x) * exp(-inset / max(3.0 * density, 1.0));
        rgb *= (dark > 0.5) ? (1.0 + 0.22 * intensity) : (1.0 + 0.06 * intensity);

        float facing = smoothstep(0.50, 0.94, abs(normal.y));
        float band = 1.0 - smoothstep(0.0, max(2.6 * density, 1.0), inset);
        float shine = facing * band * intensity * (1.0 - contentLens) * (dark > 0.5 ? 0.28 : 0.46);
        rgb += (1.0 - rgb) * clamp(shine, 0.0, 1.0);

        return half4(clamp(rgb * green.a, 0.0, 1.0), green.a);
    }
"""

private const val StandaloneGlassShaderSource = """
    uniform float2 size;
    uniform float density;
    uniform float corner;
    uniform float bevel;
    uniform float rimStrength;
    uniform float4 baseColor;
    uniform float dark;
    uniform float tinted;
    uniform float2 glowCenter;
    uniform float glowStrength;
    uniform float glowReach;
    uniform float glowWash;

    half4 main(float2 p) {
        float2 halfSize = max(size * 0.5, float2(1.0));
        float halfMinor = min(halfSize.x, halfSize.y);
        float r = corner >= 0.0 ? min(corner, halfMinor) : halfMinor;
        float2 centered = p - halfSize;
        float2 q = abs(centered) - halfSize + r;
        float dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        float inset = max(-dist, 0.0);
        float2 rawN = (q.x > 0.0 && q.y > 0.0)
            ? q
            : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
        float2 sgn = float2(centered.x < 0.0 ? -1.0 : 1.0, centered.y < 0.0 ? -1.0 : 1.0);
        float2 normal = (dot(rawN, rawN) > 1e-6 ? normalize(rawN) : float2(0.0, 1.0)) * sgn;

        float effBevel = max(min(bevel, halfMinor * 0.55), 1.0);
        float edgeT = clamp(inset / effBevel, 0.0, 1.0);
        float rise = 1.0 - edgeT;
        float bevelCurve = 1.0 - sqrt(max(0.0, 1.0 - rise * rise));

        float3 rgb = baseColor.rgb;
        float topLumen = pow(clamp(1.0 - p.y / max(size.y * 0.50, 1.0), 0.0, 1.0), 1.5);
        float bottomLumen = pow(clamp((p.y - size.y * 0.60) / max(size.y * 0.40, 1.0), 0.0, 1.0), 1.4);

        if (tinted >= 0.5) {
            float rimLight = (1.0 - bevelCurve) * 0.14;
            rgb += (1.0 - rgb) * clamp(rimLight + topLumen * 0.10 + bottomLumen * 0.05, 0.0, 0.35);
        } else {
            float shade = topLumen * (dark > 0.5 ? 0.16 : 0.34) + bottomLumen * (dark > 0.5 ? 0.08 : 0.18);
            rgb += (1.0 - rgb) * clamp(shade, 0.0, 0.65);
            rgb = mix(rgb, rgb * (dark > 0.5 ? 0.90 : 0.95), (1.0 - bevelCurve) * 0.35);
        }

        float band = clamp(1.0 - inset / max(3.6 * density, 1.0), 0.0, 1.0);
        float rise2 = normal.y * normal.y;
        float facing = mix(0.24, 1.0, rise2);
        float shine = facing * band * rimStrength * (tinted >= 0.5 ? 0.55 : 0.62);
        rgb += (1.0 - rgb) * clamp(shine, 0.0, 1.0);

        float hairline = clamp((0.9 * density - inset) / max(density, 1.0) + 0.5, 0.0, 1.0);
        rgb *= 1.0 - hairline * (dark > 0.5 ? 0.05 : 0.11);

        if (glowStrength > 0.0001) {
            float2 dGlow = p - glowCenter;
            float radial = exp(-dot(dGlow, dGlow) / (glowReach * glowReach));
            rgb += (1.0 - rgb) * clamp(glowStrength * mix(radial, 1.0, glowWash), 0.0, 1.0);
        }

        float outAlpha = clamp(baseColor.a + band * (tinted >= 0.5 ? 0.12 : 0.24) + hairline * (tinted >= 0.5 ? 0.08 : 0.22), 0.0, 1.0);
        return half4(clamp(rgb * outAlpha, 0.0, outAlpha), outAlpha);
    }
"""
