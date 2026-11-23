package com.example.myapplication.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

internal data class AnchoredMenuChoice(
    val value: String,
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val testTag: String? = null,
    val destructive: Boolean = false,
    val keepsOpen: Boolean = false,
    val opensSubmenu: Boolean = false,
)

internal fun resolveMenuCommit(
    anchorPresent: Boolean,
    lifecycleAtLeastStarted: Boolean,
    choice: String?,
    choices: List<AnchoredMenuChoice>,
): String? {
    return resolvePopupChoice(choice, choices, anchorPresent, lifecycleAtLeastStarted)?.value
}

private sealed interface PopupTarget {
    val child: Boolean

    data object OpenChild : PopupTarget { override val child = false }
    data object BackToParent : PopupTarget { override val child = true }
    data class Choice(val value: String, override val child: Boolean) : PopupTarget
}

private data class PopupDragSample(
    val state: PopupDragState,
    val target: PopupTarget?,
)

private data class MenuExit(
    val choice: String?,
    val toAnchor: Boolean,
    val startProgress: Float,
)

/** A single two-level overlay. [anchor] is expressed in the containing Box's coordinates. */
@Composable
internal fun AnchoredActionMenu(
    anchor: Rect?,
    openFrom: Rect,
    choices: List<AnchoredMenuChoice>,
    submenuLabel: String? = null,
    childChoices: List<AnchoredMenuChoice> = emptyList(),
    selected: String? = null,
    paneTitleText: String = submenuLabel?.let { "${it}菜单" } ?: "操作菜单",
    onChoose: (String) -> Unit,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: UiBackdrop? = null,
    tagPrefix: String = "anchored-action-menu",
    sourceAnchor: PopupAnchor? = null,
    drag: PopupDrag? = null,
    checkedValues: Set<String> = emptySet(),
    ownerMatches: () -> Boolean = { true },
) {
    val rootMotion = remember { Animatable(0f) }
    val childMotion = remember { Animatable(0f) }
    val childVelocity = remember { FloatArray(1) }
    val targets = remember { mutableStateMapOf<PopupTarget, Rect>() }
    var showChild by remember { mutableStateOf(false) }
    var exit by remember { mutableStateOf<MenuExit?>(null) }
    var lastAnchor by remember { mutableStateOf(openFrom) }
    var capturePhase by remember(sourceAnchor) {
        mutableStateOf(if (sourceAnchor == null) PopupCapturePhase.NoSource else PopupCapturePhase.Pending)
    }
    var sourceImage by remember(sourceAnchor) { mutableStateOf<ImageBitmap?>(null) }
    var stopped by remember { mutableStateOf(false) }
    val latestAnchor by rememberUpdatedState(anchor)
    val latestChoices by rememberUpdatedState(choices)
    val latestChildChoices by rememberUpdatedState(childChoices)
    val latestChoose by rememberUpdatedState(onChoose)
    val latestClosed by rememberUpdatedState(onClosed)
    val latestOwnerMatches by rememberUpdatedState(ownerMatches)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    SideEffect { if (anchor != null) lastAnchor = anchor }

    fun currentChoice(value: String?): AnchoredMenuChoice? = resolvePopupChoice(
        value,
        if (latestChildChoices.isEmpty()) latestChoices else latestChoices + latestChildChoices,
        ownerMatches = !stopped && latestOwnerMatches(),
        lifecycleStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
    )

    fun close() {
        if (exit == null) {
            val returnsToSource = capturePhase == PopupCapturePhase.Ready && latestAnchor != null
            exit = MenuExit(null, returnsToSource, rootMotion.value)
        }
    }

    fun choose(value: String) {
        if (exit != null) return
        val choice = currentChoice(value) ?: return
        when (popupSelectionDelivery(capturePhase, choice.keepsOpen)) {
            PopupDelivery.AfterSourceReturn -> {
                if (latestAnchor != null) exit = MenuExit(choice.value, true, rootMotion.value)
                else close()
            }
            PopupDelivery.CloseAndDispatch -> {
                close()
                latestChoose(choice.value)
            }
            PopupDelivery.KeepOpenAndDispatch -> latestChoose(choice.value)
        }
    }

    LaunchedEffect(sourceAnchor, exit != null) {
        if (sourceAnchor == null || exit != null) return@LaunchedEffect
        withFrameNanos { }
        val image = sourceAnchor.captureForeground()
        if (exit == null && latestAnchor != null && !stopped) {
            sourceImage = image
            capturePhase = if (image == null) PopupCapturePhase.Failed else PopupCapturePhase.Ready
            sourceAnchor.hidden = image != null
        }
    }

    DisposableEffect(lifecycle, sourceAnchor) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                stopped = true
                sourceAnchor?.hidden = false
                sourceImage = null
                latestClosed()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            sourceAnchor?.hidden = false
        }
    }
    LaunchedEffect(anchor == null) {
        if (sourceAnchor != null && anchor == null) {
            sourceAnchor?.hidden = false
            sourceImage = null
            capturePhase = PopupCapturePhase.Failed
            exit = MenuExit(choice = null, toAnchor = false, rootMotion.value)
        }
    }
    LaunchedEffect(exit) {
        val request = exit
        if (request == null) {
            rootMotion.animateTo(1f, tween(450, easing = popupSpring(450, 400f, .754f)))
        } else {
            val easing = if (request.toAnchor) popupReturnEasing() else FastOutSlowInEasing
            rootMotion.animateTo(0f, tween(if (request.toAnchor) 500 else 200, easing = easing))
            sourceAnchor?.hidden = false
            sourceImage = null
            if (request.toAnchor && latestAnchor != null) {
                currentChoice(request.choice)?.let { latestChoose(it.value) }
            }
            if (!stopped) latestClosed()
        }
    }
    LaunchedEffect(showChild, exit) {
        if (exit != null) return@LaunchedEffect
        val target = if (showChild) 1f else 0f
        val remaining = target - childMotion.value
        if (remaining == 0f) return@LaunchedEffect
        val duration = if (showChild) 420 else 340
        // The reference bounce maps to damping ratio (1 - bounce).
        val easing = popupSpring(
            duration,
            stiffness = (7f / (duration / 1_000f)).let { it * it },
            damping = if (showChild) .85f else 1f,
            velocity = childVelocity[0] / remaining,
        )
        childMotion.animateTo(target, tween(duration, easing = easing)) {
            childVelocity[0] = velocity
        }
        childVelocity[0] = 0f
    }
    var dragTarget by remember { mutableStateOf<PopupTarget?>(null) }
    var autoOpenedFrom by remember(drag) { mutableStateOf<Offset?>(null) }
    LaunchedEffect(drag) {
        if (drag == null) return@LaunchedEffect
        var releaseHandled = false
        snapshotFlow {
            val state = drag.state
            val target = if (state.armed && !state.cancelled && exit == null) {
                targets.entries.firstOrNull { (row, bounds) ->
                    row.child == showChild && bounds.contains(state.position)
                }?.key
            } else null
            PopupDragSample(state, target)
        }.collectLatest { (state, target) ->
            dragTarget = target.takeUnless { state.released || state.cancelled }
            if (state.released && !releaseHandled) {
                releaseHandled = true
                val parked = autoOpenedFrom?.let { (state.position - it).getDistance() < 1f } == true
                if (!parked) when (target) {
                    is PopupTarget.Choice -> choose(target.value)
                    PopupTarget.OpenChild -> showChild = true
                    PopupTarget.BackToParent -> showChild = false
                    null -> Unit
                }
            }
        }
    }
    LaunchedEffect(drag, dragTarget) {
        if (dragTarget == PopupTarget.OpenChild && drag != null) {
            delay(400)
            if (!drag.state.released && !drag.state.cancelled && dragTarget == PopupTarget.OpenChild) {
                autoOpenedFrom = drag.state.position
                showChild = true
            }
        }
    }
    BackHandler {
        if (exit == null) {
            if (showChild) showChild = false else close()
        }
    }

    BoxWithConstraints(
        modifier.fillMaxSize().semantics { paneTitle = paneTitleText },
    ) {
        val density = LocalDensity.current
        val layout = LocalLayoutDirection.current
        val margin = with(density) { 10.dp.toPx() }
        val gap = with(density) { 6.dp.toPx() }
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val usableWidth = (widthPx - 2 * margin).coerceAtLeast(1f)
        val usableHeight = (heightPx - 2 * margin).coerceAtLeast(1f)
        val measure = rememberTextMeasurer()
        val style = UiRoles.actionText
        val singleSubmenuRoot = submenuLabel != null && childChoices.isEmpty()
        val effectiveChildChoices = if (childChoices.isNotEmpty()) childChoices else choices
        val labels = choices.map { it.label }
        val childLabels = effectiveChildChoices.map { it.label }
        fun menuWidth(values: List<String>, minPx: Float): Float {
            val minWidth = minPx.coerceAtMost(usableWidth)
            val maxWidth = maxOf(minPx, with(density) { 280.dp.toPx() }).coerceAtMost(usableWidth)
            val content = values.maxOfOrNull { measure.measure(it, style, maxLines = 1).size.width }
                ?: 0
            return (content + with(density) { 92.dp.toPx() }).coerceIn(minWidth, maxWidth)
        }
        val rootWidth = menuWidth(if (singleSubmenuRoot) listOf(submenuLabel!!) else labels, with(density) { 160.dp.toPx() })
        val childWidth = menuWidth(childLabels + listOfNotNull(submenuLabel), rootWidth * 1.12f)
        val heightLimit = with(density) { usableHeight.toDp() }
        var rootMeasured by remember { mutableIntStateOf(0) }
        var childMeasured by remember { mutableIntStateOf(0) }
        val initialRootHeight = if (singleSubmenuRoot) 60.dp else (44.dp * choices.size + 16.dp).coerceAtLeast(60.dp)
        val rootHeight = (if (rootMeasured == 0) with(density) { initialRootHeight.toPx() } else rootMeasured.toFloat())
            .coerceAtMost(usableHeight)
        val childHeight = (if (childMeasured == 0) with(density) { (44.dp * (effectiveChildChoices.size + 1) + 16.dp).coerceAtLeast(120.dp).toPx() } else childMeasured.toFloat())
            .coerceAtMost(usableHeight)
        val source = anchor ?: lastAnchor
        val rootLeft = if (layout == LayoutDirection.Ltr) source.right - rootWidth else source.left
        val limitedRootLeft = rootLeft.coerceIn(margin, (widthPx - rootWidth - margin).coerceAtLeast(margin))
        val below = source.bottom + gap
        val rootTop = (if (below + rootHeight <= heightPx - margin) below else source.top - rootHeight - gap)
            .coerceIn(margin, (heightPx - rootHeight - margin).coerceAtLeast(margin))
        val rootTarget = Rect(limitedRootLeft, rootTop, limitedRootLeft + rootWidth, rootTop + rootHeight)
        val childLeft = (if (layout == LayoutDirection.Ltr) rootTarget.right - childWidth else rootTarget.left)
            .coerceIn(margin, (widthPx - childWidth - margin).coerceAtLeast(margin))
        val childTop = rootTop.coerceIn(margin, (heightPx - childHeight - margin).coerceAtLeast(margin))
        val childTarget = Rect(childLeft, childTop, childLeft + childWidth, childTop + childHeight)

        val opening = rootMotion.value.coerceIn(0f, 1f)
        val childFraction = childMotion.value.coerceIn(0f, 1.05f)
        val rootShown = exit == null && opening > .16f && !showChild && childFraction < .02f
        val childShown = exit == null && opening > .16f && showChild && childFraction > .35f
        val rootFocus = remember { FocusRequester() }
        val childFocus = remember { FocusRequester() }
        LaunchedEffect(rootShown, childShown, choices.firstOrNull { it.enabled }?.value) {
            if (childShown) childFocus.requestFocus()
            if (rootShown && (submenuLabel != null || choices.any { it.enabled })) rootFocus.requestFocus()
        }

        // Consume hits behind both cards and keep the underlying list at the same scroll position.
        Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = null) { close() }
            .clearAndSetSemantics { hideFromAccessibility() }.testTag("$tagPrefix-dismiss"))

        val noSourceExit = exit?.takeUnless { it.toAnchor }
        val backedRoot = rootTarget.scaleAboutCenter(1f - .05f * childFraction)
        val rootRect = menuRect(source, backedRoot, opening, noSourceExit)
        val rootAlpha = noSourceExit?.let { exitFraction(opening, it.startProgress) } ?: 1f
        ActionMenuCard(
            finalWidth = rootWidth,
            finalHeight = rootHeight,
            drawnBounds = rootRect,
            opening = opening,
            morphToAnchor = noSourceExit == null,
            opacity = rootAlpha * (1f - .20f * childFraction),
            shown = rootShown,
            backdrop = backdrop,
            maxHeight = heightLimit,
            tag = "$tagPrefix-root-panel",
            onHeight = { rootMeasured = it },
            onBackdropClick = if (showChild && exit == null) ({ showChild = false }) else null,
        ) {
            if (!singleSubmenuRoot) {
                val firstAction = choices.indexOfFirst { it.enabled }
                choices.forEachIndexed { index, choice ->
                    if (choice.opensSubmenu && submenuLabel != null) {
                        MenuRow(
                            title = choice.label,
                            target = PopupTarget.OpenChild,
                            hitTargets = targets,
                            dragged = dragTarget == PopupTarget.OpenChild,
                            modifier = Modifier.testTag(choice.testTag ?: "$tagPrefix-submenu")
                                .then(if (index == firstAction) Modifier.focusRequester(rootFocus) else Modifier),
                            enabled = rootShown && choice.enabled,
                            onClick = { showChild = true },
                            leading = {
                                choice.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
                            },
                            trailing = { Icon(UiIcons.Chevron, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    } else {
                        val rowTarget = PopupTarget.Choice(choice.value, child = false)
                        MenuRow(
                            title = choice.label,
                            target = rowTarget,
                            hitTargets = targets,
                            dragged = dragTarget == rowTarget,
                            modifier = Modifier.testTag(choice.testTag ?: "$tagPrefix-${choice.value}")
                                .then(if (index == firstAction) Modifier.focusRequester(rootFocus) else Modifier),
                            enabled = rootShown && choice.enabled,
                            destructive = choice.destructive,
                            onClick = { choose(choice.value) },
                            selected = if (choice.keepsOpen) choice.value in checkedValues else null,
                            keepsOpen = choice.keepsOpen,
                            leading = {
                                choice.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
                            },
                            trailing = { MenuCheck(choice.keepsOpen && choice.value in checkedValues) },
                        )
                    }
                }
            } else {
                MenuRow(
                    title = submenuLabel!!,
                    target = PopupTarget.OpenChild,
                    hitTargets = targets,
                    dragged = dragTarget == PopupTarget.OpenChild,
                    modifier = Modifier.testTag("$tagPrefix-submenu").focusRequester(rootFocus),
                    enabled = rootShown,
                    onClick = { showChild = true },
                    trailing = { Icon(UiIcons.Chevron, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
            }
        }
        var foregroundBounds = rootRect
        if (submenuLabel != null && (showChild || childFraction > 0f)) {
            val childRect = menuRect(
                source,
                interpolateRect(rootTarget, childTarget, childFraction),
                opening,
                noSourceExit,
            )
            foregroundBounds = childRect
            ActionMenuCard(
                finalWidth = childWidth,
                finalHeight = childHeight,
                drawnBounds = childRect,
                opening = opening,
                morphToAnchor = noSourceExit == null,
                opacity = ((childFraction * 2f).coerceAtMost(1f)) * rootAlpha,
                shown = childShown,
                backdrop = backdrop,
                maxHeight = heightLimit,
                tag = "$tagPrefix-child-panel",
                onHeight = { childMeasured = it },
            ) {
                MenuRow(
                    title = submenuLabel,
                    target = PopupTarget.BackToParent,
                    hitTargets = targets,
                    dragged = dragTarget == PopupTarget.BackToParent,
                    modifier = Modifier.testTag("$tagPrefix-parent").focusRequester(childFocus)
                        .semantics { paneTitle = "返回上一级" },
                    enabled = childShown,
                    onClick = { showChild = false },
                    leading = { Icon(UiIcons.Back, contentDescription = "返回上一级", modifier = Modifier.size(20.dp)) },
                )
                for (choice in effectiveChildChoices) {
                    val isChecked = when {
                        choice.keepsOpen -> choice.value in checkedValues
                        selected != null -> choice.value == selected
                        else -> false
                    }
                    val rowTarget = PopupTarget.Choice(choice.value, child = true)
                    MenuRow(
                        title = choice.label,
                        target = rowTarget,
                        hitTargets = targets,
                        dragged = dragTarget == rowTarget,
                        modifier = Modifier.testTag(choice.testTag ?: "$tagPrefix-${choice.value}"),
                        enabled = childShown && choice.enabled,
                        destructive = choice.destructive,
                        onClick = { choose(choice.value) },
                        selected = if (choice.keepsOpen || selected != null) isChecked else null,
                        keepsOpen = choice.keepsOpen,
                        leading = {
                            choice.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
                        },
                        trailing = { MenuCheck(isChecked) },
                    )
                }
            }
        }
        val captured = sourceImage
        if (captured != null && opening < .70f) {
            val left = foregroundBounds.center.x - captured.width / 2f
            val top = foregroundBounds.center.y - captured.height / 2f
            Image(
                bitmap = captured,
                contentDescription = null,
                modifier = Modifier
                    .absoluteOffset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(density) { captured.width.toDp() }, with(density) { captured.height.toDp() })
                    .alpha((1f - opening / .70f).coerceIn(0f, 1f))
                    .testTag("$tagPrefix-source-image"),
            )
        }
    }
}

@Composable
private fun MenuCheck(checked: Boolean) {
    if (checked) Icon(UiIcons.Check, null, Modifier.size(16.dp), tint = UiRoles.accent)
}

/** Wide row anchors can grow during return; width alone cannot hide their compressed menu content. */
internal fun popupMenuContentVisibility(
    drawnWidth: Float,
    finalWidth: Float,
    opening: Float = 1f,
): Float {
    if (!drawnWidth.isFinite() || drawnWidth <= 0f ||
        !finalWidth.isFinite() || finalWidth <= 0f || !opening.isFinite()
    ) return 0f
    val widthVisibility = ((drawnWidth / finalWidth - .37f) / .43f).coerceIn(0f, 1f)
    val motionVisibility = ((opening.coerceIn(0f, 1f) - .16f) / .54f).coerceIn(0f, 1f)
    return minOf(widthVisibility, motionVisibility)
}

/** Keeps unscaled corner radius matching a circular or capsule anchor as the menu droplet closes. */
internal fun popupMenuCornerRadiusDp(
    drawnWidthDp: Float,
    drawnHeightDp: Float,
    opening: Float,
    morphToAnchor: Boolean,
    openCornerDp: Float = 24f,
): Float {
    val minHalf = (minOf(drawnWidthDp, drawnHeightDp) * 0.5f).coerceAtLeast(1f)
    val openRadius = openCornerDp.coerceAtMost(minHalf)
    if (!morphToAnchor) return openRadius
    val cardBlend = ((opening - .16f) / .64f).coerceIn(0f, 1f)
    return minHalf + (openRadius - minHalf) * cardBlend
}

/** Critically damped return curve so anchored exit glides smoothly onto the source icon without stalling at zero. */
internal fun popupReturnEasing(durationMillis: Int = 500): Easing =
    popupSpring(durationMillis, stiffness = 165f, damping = 1f)

@Composable
private fun ActionMenuCard(
    finalWidth: Float,
    finalHeight: Float,
    drawnBounds: Rect,
    opening: Float,
    morphToAnchor: Boolean,
    opacity: Float,
    shown: Boolean,
    backdrop: UiBackdrop?,
    maxHeight: Dp,
    tag: String,
    onHeight: (Int) -> Unit,
    onBackdropClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val drawnWidthDp = with(density) { drawnBounds.width.coerceAtLeast(1f).toDp() }
    val drawnHeightDp = with(density) { drawnBounds.height.coerceAtLeast(1f).toDp() }
    val finalWidthPx = finalWidth.roundToInt().coerceAtLeast(1)
    val safeFinalWidth = finalWidth.coerceAtLeast(1f)
    val safeFinalHeight = finalHeight.coerceAtLeast(1f)
    val contentVisibility = popupMenuContentVisibility(
        drawnBounds.width,
        safeFinalWidth,
        opening = if (morphToAnchor) opening else 1f,
    )
    val cornerDp = popupMenuCornerRadiusDp(
        drawnWidthDp = drawnWidthDp.value,
        drawnHeightDp = drawnHeightDp.value,
        opening = opening,
        morphToAnchor = morphToAnchor,
    ).dp
    UiPane(
        role = UiPaneRole.Menu,
        shape = RoundedCornerShape(cornerDp),
        cornerRadiusDp = cornerDp,
        backdrop = backdrop,
        modifier = Modifier
            .absoluteOffset { IntOffset(drawnBounds.left.roundToInt(), drawnBounds.top.roundToInt()) }
            .size(drawnWidthDp, drawnHeightDp)
            .graphicsLayer { alpha = opacity }
            .then(
                if (onBackdropClick != null) {
                    Modifier.clickable(interactionSource = null, indication = null, onClick = onBackdropClick)
                } else Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
            )
            .testTag(tag)
            .then(if (shown) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }),
    ) {
        // Lay out menu rows at the target menu width while clipping and morphing the outer glass pane to drawnBounds.
        Box(
            Modifier.layout { measurable, constraints ->
                val maxH = maxHeight.roundToPx().coerceAtLeast(1)
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = finalWidthPx,
                        maxWidth = finalWidthPx,
                        minHeight = 0,
                        maxHeight = maxH,
                    ),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(0, 0)
                }
            },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .onSizeChanged { onHeight(it.height) }
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = drawnBounds.width / safeFinalWidth
                        scaleY = drawnBounds.height / safeFinalHeight
                    }
                    .verticalScroll(rememberScrollState())
                    .alpha(contentVisibility)
                    .padding(8.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun MenuRow(
    title: String,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    target: PopupTarget,
    hitTargets: MutableMap<PopupTarget, Rect>,
    dragged: Boolean,
    selected: Boolean? = null,
    keepsOpen: Boolean = false,
    destructive: Boolean = false,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val action = when {
        keepsOpen -> Modifier.toggleable(
            value = selected == true, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() },
        )
        selected != null -> Modifier.selectable(
            selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick,
        )
        else -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    DisposableEffect(target, enabled) {
        onDispose { hitTargets.remove(target) }
    }
    val foreground = if (destructive) MaterialTheme.colorScheme.error else UiRoles.foreground
    CompositionLocalProvider(LocalContentColor provides foreground.copy(alpha = if (enabled) 1f else .40f)) {
        Row(
            modifier
                .onGloballyPositioned { if (enabled) hitTargets[target] = it.boundsInRoot() }
                .then(if (dragged) Modifier.background(UiRoles.accent.copy(alpha = .13f), RoundedCornerShape(12.dp)) else Modifier)
                .then(action)
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Text(
                title,
                Modifier.weight(1f).padding(horizontal = 8.dp),
                style = UiRoles.actionText,
            )
            trailing()
        }
    }
}

/** Damped spring on the popup route's fixed motion window, carrying the previous layer velocity. */
private fun popupSpring(
    durationMillis: Int,
    stiffness: Float,
    damping: Float,
    velocity: Float = 0f,
): Easing {
    val duration = durationMillis / 1_000f
    val naturalRate = sqrt(stiffness)
    val decay = damping * naturalRate
    val oscillation = naturalRate * sqrt((1f - damping * damping).coerceAtLeast(0f))
    // Limit unrepresentable velocities near a settled endpoint to keep popup geometry bounded.
    val startVelocity = velocity.coerceIn(-20f, 20f)
    fun displacement(time: Float): Float = if (oscillation > 0f) {
        exp(-decay * time) *
            (-cos(oscillation * time) + (startVelocity - decay) / oscillation * sin(oscillation * time))
    } else {
        exp(-decay * time) * (-1f + (startVelocity - decay) * time)
    }
    val tail = displacement(duration)
    return Easing { progress ->
        1f + displacement(progress * duration) - tail * progress * progress * progress
    }
}

private fun menuRect(source: Rect, target: Rect, progress: Float, noSourceExit: MenuExit?): Rect =
    if (noSourceExit == null) {
        interpolateRect(source, target, progress)
    } else {
        val atStart = interpolateRect(source, target, noSourceExit.startProgress)
        atStart.scaleAboutCenter(.8f + .2f * exitFraction(progress, noSourceExit.startProgress))
    }

private fun exitFraction(progress: Float, start: Float): Float =
    (progress / start.coerceAtLeast(.0001f)).coerceIn(0f, 1f)

private fun Rect.scaleAboutCenter(factor: Float): Rect {
    val dx = width * (1f - factor) * .5f
    val dy = height * (1f - factor) * .5f
    return Rect(left + dx, top + dy, right - dx, bottom - dy)
}

private fun interpolateRect(a: Rect, b: Rect, progress: Float): Rect = Rect(
    a.left + (b.left - a.left) * progress,
    a.top + (b.top - a.top) * progress,
    a.right + (b.right - a.right) * progress,
    a.bottom + (b.bottom - a.bottom) * progress,
)
