package com.example.myapplication.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.appearance.AppearanceDockMotion
import com.example.myapplication.ui.theme.UiRoles
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.tanh
import kotlinx.coroutines.launch

internal val LocalMainDockBottomInset = staticCompositionLocalOf { 0.dp }

internal class DockBringIntoViewSpec(
    private val delegate: BringIntoViewSpec,
    private val bottomInsetPx: Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val effectiveSize = if (bottomInsetPx > 0f && containerSize > bottomInsetPx) {
            (containerSize - bottomInsetPx).coerceAtLeast(1f)
        } else {
            containerSize
        }
        return delegate.calculateScrollDistance(offset, size, effectiveSize)
    }

    override fun equals(other: Any?): Boolean =
        other is DockBringIntoViewSpec && other.delegate == delegate && other.bottomInsetPx == bottomInsetPx

    override fun hashCode(): Int = 31 * delegate.hashCode() + bottomInsetPx.hashCode()
}

internal data class BottomNavItem(val label: String, val icon: ImageVector)

private val DockEndPadding = 8.dp
private val DockShape = RoundedCornerShape(percent = 50)
private val RailShape = RoundedCornerShape(28.dp)
private val RailItemHeight = 72.dp

private const val JellyStretch = 0.20f
private const val JellySpeed = 3.0f
private const val JellySquash = 0.15f
private const val JellyBrake = 25.0f
private const val OverdragLimit = 0.35f
private const val PullLimitRatio = 7f / 32f
private const val LiftSnapThreshold = 0.90f
private const val LiftReleaseDistance = 0.05f
private const val LensMagnify = 0.16f

private class DockDragRelease(val position: Float, val velocity: Float)

internal fun dockRubberBand(overshoot: Float, limit: Float): Float {
    if (limit <= 0f || overshoot == 0f) return 0f
    val absValue = abs(overshoot)
    val pull = 1f - 1f / (absValue * 0.55f / limit + 1f)
    return limit * pull * sign(overshoot)
}

internal fun dockJellyDeform(velocity: Float, acceleration: Float): Float {
    val braking = maxOf(0f, -acceleration * sign(velocity))
    return (JellyStretch * tanh(abs(velocity) / JellySpeed) -
        JellySquash * tanh(braking / JellyBrake)).coerceIn(-JellySquash, JellyStretch)
}

internal fun dockCapsulePlatterAlpha(visibleLift: Float, dark: Boolean): Float {
    val lift = visibleLift.coerceIn(0f, 1f)
    return if (dark) .82f - .46f * lift else .84f - .50f * lift
}

internal fun dockOverdragPosition(
    x: Float,
    endPaddingPx: Float,
    cellWidthPx: Float,
    itemCount: Int,
    rtl: Boolean,
): Float {
    if (itemCount <= 0 || cellWidthPx <= 0f) return 0f
    val physicalIndex = (x - endPaddingPx) / cellWidthPx - .5f
    val raw = if (rtl) itemCount - 1 - physicalIndex else physicalIndex
    val last = (itemCount - 1).toFloat()
    return when {
        raw < 0f -> dockRubberBand(raw, OverdragLimit)
        raw > last -> last + dockRubberBand(raw - last, OverdragLimit)
        else -> raw
    }
}

internal fun projectDockPosition(
    x: Float,
    endPaddingPx: Float,
    cellWidthPx: Float,
    itemCount: Int,
    rtl: Boolean,
): Float {
    if (itemCount <= 0 || cellWidthPx <= 0f) return 0f
    val physicalIndex = (x - endPaddingPx) / cellWidthPx - .5f
    return (if (rtl) itemCount - 1 - physicalIndex else physicalIndex)
        .coerceIn(0f, (itemCount - 1).toFloat())
}

internal fun resolveDockDragTarget(
    lastX: Float,
    velocityX: Float,
    currentIndex: Int,
    itemCount: Int,
    endPaddingPx: Float,
    cellWidthPx: Float,
    rtl: Boolean,
): Int? {
    if (itemCount <= 0 || cellWidthPx <= 0f) return null
    val underPointer = projectDockPosition(lastX, endPaddingPx, cellWidthPx, itemCount, rtl).roundToInt()
    val projected = projectDockPosition(lastX + velocityX * .1f, endPaddingPx, cellWidthPx, itemCount, rtl).roundToInt()
    val target = projected.coerceIn(underPointer - 1, underPointer + 1)
        .coerceIn(0, itemCount - 1)
    return target.takeIf { it != currentIndex }
}

internal fun Density.mainDockHeight(windowWidth: Dp, itemCount: Int): Dp {
    val available = minOf(windowWidth - 42.dp, (itemCount * 86 + 16).dp) - DockEndPadding * 2
    val needsTwoLines = available / itemCount < 10.sp.toDp() * 4 + 4.dp
    val labelHeight = 14.5.sp.toDp() * if (needsTwoLines) 2 else 1
    return 47.5.dp + labelHeight
}

@Composable
internal fun AppBottomBar(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: UiBackdrop,
    dockHeight: Dp,
    modifier: Modifier = Modifier,
    motion: AppearanceDockMotion = AppearanceDockMotion.Slide,
) {
    if (items.isEmpty()) return
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val latestSelection by rememberUpdatedState(selectedIndex)
    val latestSelect by rememberUpdatedState(onSelect)
    val position = remember { Animatable(selectedIndex.toFloat()) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    var release by remember { mutableStateOf<DockDragRelease?>(null) }
    var appliedRelease by remember { mutableStateOf<DockDragRelease?>(null) }
    val dragging = dragPosition != null
    val colors = MaterialTheme.colorScheme
    val dark = UiRoles.dark
    val selectedFill = lerp(colors.secondaryContainer, colors.surfaceBright, if (dark) .14f else .38f)
        .copy(alpha = if (dark) .36f else .22f)
    LaunchedEffect(selectedIndex, dragging, release, motion) {
        if (dragging) { position.stop(); return@LaunchedEffect }
        val currentRelease = release?.takeIf { it !== appliedRelease }
        if (currentRelease != null) {
            position.snapTo(currentRelease.position)
            appliedRelease = currentRelease
        }
        if (motion == AppearanceDockMotion.Fade) position.snapTo(selectedIndex.toFloat())
        else position.animateTo(selectedIndex.toFloat(), spring(.9f, 650f),
            initialVelocity = currentRelease?.velocity ?: position.velocity)
    }
    BoxWithConstraints(
        modifier.widthIn(max = (items.size * 86 + 16).dp).fillMaxWidth().height(dockHeight)
            .testTag("ui02-main-dock")
            .pointerInput(items.size, rtl) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val padding = DockEndPadding.toPx()
                    val cell = (size.width - 2 * padding) / items.size
                    if (cell <= 0f) return@awaitEachGesture
                    var completed = false
                    var velocity = 0f
                    try {
                        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        tracker.addPosition(slop.uptimeMillis, slop.position)
                        var lastX = slop.position.x
                        dragPosition = dockOverdragPosition(lastX, padding, cell, items.size, rtl)
                        completed = horizontalDrag(slop.id) { change ->
                            lastX = change.position.x
                            tracker.addPosition(change.uptimeMillis, change.position)
                            dragPosition = dockOverdragPosition(lastX, padding, cell, items.size, rtl)
                            change.consume()
                        }
                        if (completed) {
                            currentEvent.changes.firstOrNull { it.id == slop.id }?.let {
                                lastX = it.position.x
                                tracker.addPosition(it.uptimeMillis, it.position)
                                dragPosition = dockOverdragPosition(lastX, padding, cell, items.size, rtl)
                            }
                            val pixelsPerSecond = tracker.calculateVelocity().x
                            velocity = (pixelsPerSecond / cell * if (rtl) -1f else 1f).coerceIn(-12f, 12f)
                            resolveDockDragTarget(lastX, pixelsPerSecond, latestSelection, items.size,
                                padding, cell, rtl)?.let(latestSelect)
                        }
                    } finally {
                        dragPosition?.let { release = DockDragRelease(it, if (completed) velocity else 0f) }
                        dragPosition = null
                    }
                }
            },
    ) {
        // One label layer and one translated capsule: pointer/animation frames never recompose
        // all icons, rebuild clipping paths or rerasterize a second magnified copy of the page.
        UiPane(UiPaneRole.Dock, DockShape, Modifier.matchParentSize().shadow(4.dp, DockShape),
            backdrop = backdrop) {}
        val pitch = (maxWidth - DockEndPadding * 2) / items.size
        val density = LocalDensity.current
        val pitchPx = with(density) { pitch.toPx() }
        val paddingPx = with(density) { DockEndPadding.toPx() }
        Box(Modifier.align(AbsoluteAlignment.CenterLeft).width(pitch).height(dockHeight - 8.dp)
            .absoluteOffset {
                val pending = release?.takeIf { it !== appliedRelease }?.position
                val logical = (dragPosition ?: pending ?: position.value).coerceIn(-OverdragLimit, items.lastIndex + OverdragLimit)
                val physical = if (rtl) items.lastIndex - logical else logical
                IntOffset((paddingPx + physical * pitchPx).roundToInt(), 0)
            }
            .testTag("ui-main-dock-lens")
            .graphicsLayer {
                scaleX = if (dragPosition != null) 1.03f else 1f
                scaleY = if (dragPosition != null) 1.04f else 1f
            }
            .clip(DockShape)) {
            UiPane(
                role = UiPaneRole.Lens,
                shape = DockShape,
                modifier = Modifier.matchParentSize(),
                backdrop = backdrop,
                lensIntensity = if (dragPosition != null) 1f else .52f,
            ) {
                Box(
                    Modifier.matchParentSize()
                        .background(selectedFill.copy(alpha = selectedFill.alpha * if (dragPosition != null) .35f else 1f)),
                )
            }
        }
        Row(Modifier.fillMaxSize().padding(horizontal = DockEndPadding).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { index, item ->
                MainTabItem(item, index, selectedIndex == index, onSelect,
                    Modifier.weight(1f).fillMaxHeight(), wide = false)
            }
        }
    }
}

@Composable
internal fun AppNavigationRail(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val indicatorTop by animateDpAsState(
        targetValue = RailItemHeight * selectedIndex,
        animationSpec = spring(0.85f, 247f),
        label = "rail-position",
    )
    val labelWidth = with(LocalDensity.current) { 12.sp.toDp() * 4 }
    val railWidth = maxOf(88.dp, labelWidth + 20.dp)
    val indicator = MaterialTheme.colorScheme.secondaryContainer

    UiPane(
        UiPaneRole.Dock,
        RailShape,
        modifier.width(railWidth).testTag("ui02-main-rail"),
        cornerRadiusDp = 28.dp,
    ) {
        Box(Modifier.fillMaxWidth().padding(6.dp)) {
            Box(
                Modifier.absoluteOffset(y = indicatorTop + 4.dp).fillMaxWidth()
                    .height(RailItemHeight - 8.dp).background(indicator, DockShape),
            )
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                items.forEachIndexed { index, item ->
                    val selected = selectedIndex == index
                    MainTabItem(
                        item = item,
                        index = index,
                        selected = selected,
                        onSelect = onSelect,
                        modifier = Modifier.fillMaxWidth().height(RailItemHeight),
                        wide = true,
                        tintColor = if (selected) UiRoles.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        interactive = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun MainTabItem(
    item: BottomNavItem,
    index: Int,
    selected: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    wide: Boolean,
    lift: Float = 0f,
    tintColor: Color = if (selected) UiRoles.accent else MaterialTheme.colorScheme.onSurfaceVariant,
    interactive: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focusShape = if (wide) DockShape else DockShape
    val itemModifier = modifier.clip(focusShape).then(
        if (!interactive) Modifier else Modifier.testTag("main-tab-$index")
            .then(
                if (!focused) Modifier else Modifier
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), focusShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, focusShape),
            )
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = { if (!selected) onSelect(index) },
            ),
    )
    Box(itemModifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp).graphicsLayer {
                scaleX = 1f + LensMagnify * lift
                scaleY = scaleX
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(item.icon, contentDescription = null, tint = tintColor, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                item.label,
                color = tintColor,
                fontSize = if (wide) 12.sp else 10.sp,
                lineHeight = if (wide) 16.sp else 14.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
