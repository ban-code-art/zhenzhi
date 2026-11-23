package com.example.myapplication.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.UiRoles
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal val UiActionId = SemanticsPropertyKey<String>("UiActionId")
private val LocalGroupPress = staticCompositionLocalOf<MutableInteractionSource?> { null }
private val ActionRaise = tween<Float>(72, easing = FastOutSlowInEasing)
private val ActionQuickTap = tween<Float>(32, easing = FastOutSlowInEasing)
private val ActionReturn = tween<Float>(140, easing = FastOutSlowInEasing)

@Composable
private fun rememberActionLift(source: MutableInteractionSource): Animatable<Float, AnimationVector1D> {
    val lift = remember(source) { Animatable(0f) }
    LaunchedEffect(source) {
        coroutineScope {
            var motion: kotlinx.coroutines.Job? = null
            var owner: PressInteraction.Press? = null
            source.interactions.collect { event ->
                when (event) {
                    is PressInteraction.Press -> {
                        if (owner != null) return@collect
                        owner = event
                        motion?.cancel()
                        motion = launch { lift.animateTo(1f, ActionRaise) }
                    }
                    is PressInteraction.Release -> {
                        if (owner !== event.press) return@collect
                        owner = null
                        motion?.cancel()
                        motion = launch {
                            // Even a tap shorter than a frame gets a small pulse.
                            if (lift.value < .18f) lift.animateTo(.28f, ActionQuickTap)
                            lift.animateTo(0f, ActionReturn)
                        }
                    }
                    is PressInteraction.Cancel -> {
                        if (owner !== event.press) return@collect
                        owner = null
                        motion?.cancel()
                        motion = launch { lift.animateTo(0f, ActionReturn) }
                    }
                }
            }
        }
    }
    return lift
}

@Composable
internal fun UiAction(
    label: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: UiBackdrop? = LocalUiBackdrop.current,
    filled: Boolean = false,
    busy: Boolean = false,
    inheritedPane: Boolean = false,
    actionId: String = label,
    visualModifier: Modifier = Modifier,
    foregroundModifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    animatePress: Boolean = true,
    foreground: Color? = null,
    retainFilledWhenBusy: Boolean = false,
    keepEnabledStyle: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    trailingTint: Color? = null,
    fillLabelWidth: Boolean = false,
    horizontalPadding: Dp = 14.dp,
) {
    val source = remember { MutableInteractionSource() }
    val groupPress = LocalGroupPress.current
    val available = enabled && !busy
    val visuallyEnabled = enabled || keepEnabledStyle
    val filledFace = filled && (visuallyEnabled && !busy || busy && retainFilledWhenBusy)
    val pressTarget = groupPress ?: interactionSource
    val animateOwnPress = animatePress && groupPress == null
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val lift = rememberActionLift(source)
    LaunchedEffect(pressTarget, source) {
        if (pressTarget == null) return@LaunchedEffect
        var forwarded: PressInteraction.Press? = null
        try {
            source.interactions.collect { event ->
                when (event) {
                    is PressInteraction.Press -> {
                        forwarded = event
                        pressTarget.emit(event)
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        forwarded = null
                        pressTarget.emit(event)
                    }
                }
            }
        } finally {
            forwarded?.let { pressTarget.tryEmit(PressInteraction.Cancel(it)) }
        }
    }
    var paintSize by remember { mutableStateOf(IntSize.Zero) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var pointerActive by remember { mutableStateOf(false) }
    val delta = if (pointerActive) (pointer ?: Offset.Zero) - (start ?: Offset.Zero) else Offset.Zero
    val limit = minOf(paintSize.width, paintSize.height) * 7f / 32f
    val pull by animateOffsetAsState(
        targetValue = if (available && animateOwnPress) Offset(
            delta.x.coerceIn(-limit, limit),
            delta.y.coerceIn(-limit, limit),
        ) else Offset.Zero,
        animationSpec = if (pointerActive) tween(48) else spring(stiffness = 700f),
        label = "action pull",
    )
    val growthPx = with(LocalDensity.current) { 16.dp.toPx() }
    val showText = icon == null
    val targetShape = CircleShape
    val contentColor = foreground ?: if (filledFace && !inheritedPane && groupPress == null) {
        MaterialTheme.colorScheme.onPrimary
    } else UiRoles.foreground
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                this[UiActionId] = actionId
                if (!showText || busy) contentDescription = label
                if (busy) stateDescription = "处理中"
            }
            .pointerInput(available, animateOwnPress) {
                if (!available || !animateOwnPress) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    start = down.position
                    pointer = down.position
                    pointerActive = true
                    try {
                        do {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                            if (change != null) pointer = change.position
                        } while (change?.pressed == true && change.position.x in 0f..size.width.toFloat() &&
                            change.position.y in 0f..size.height.toFloat())
                    } finally {
                        pointerActive = false
                        start = null
                    }
                }
            }
            .clickable(interactionSource = source, indication = null, enabled = available, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val visual = visualModifier
            .then(if (showText) Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp) else Modifier.size(44.dp))
            .onSizeChanged { paintSize = it }
            .graphicsLayer {
                val width = size.width
                val height = size.height
                val longest = maxOf(width, height).coerceAtLeast(1f)
                val raised = if (available && animateOwnPress) {
                    maxOf(lift.value, if (hovered) .10f else 0f)
                } else 0f
                val growth = minOf(.25f, growthPx / longest) * raised
                scaleX = 1f + growth + kotlin.math.abs(pull.x) * .5f / width.coerceAtLeast(1f)
                scaleY = 1f + growth + kotlin.math.abs(pull.y) * .5f / height.coerceAtLeast(1f)
                translationX = pull.x
                translationY = pull.y
                alpha = if (visuallyEnabled || (busy && retainFilledWhenBusy)) 1f else .45f
            }
            .then(if (focused) Modifier.border(2.dp, UiRoles.accent, targetShape) else Modifier)
        val content: @Composable () -> Unit = {
            Box(
                foregroundModifier.then(if (showText) Modifier.padding(horizontal = horizontalPadding) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(18.dp), color = contentColor, strokeWidth = 2.dp)
                    icon != null -> Icon(icon, contentDescription = null, tint = contentColor)
                    leadingIcon != null || trailingIcon != null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (leadingIcon != null) {
                            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(16.dp), tint = contentColor)
                        }
                        Text(
                            label,
                            modifier = if (fillLabelWidth) Modifier.weight(1f) else Modifier,
                            style = UiRoles.actionText,
                            color = contentColor,
                        )
                        if (trailingIcon != null) {
                            Icon(
                                trailingIcon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = trailingTint ?: contentColor,
                            )
                        }
                    }
                    else -> Text(label, style = UiRoles.actionText, color = contentColor)
                }
            }
        }
        if (inheritedPane || groupPress != null) {
            Box(
                visual.then(if (hovered && available) Modifier.background(UiRoles.accent.copy(alpha = .08f), targetShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) { content() }
        } else {
            UiPane(
                role = if (filledFace) UiPaneRole.Tinted else UiPaneRole.Regular,
                shape = targetShape,
                modifier = if (available && animateOwnPress) visual.actionGlow(lift) else visual,
                backdrop = backdrop,
            ) {
                Box(Modifier.align(Alignment.Center)) { content() }
            }
        }
    }
}

@Composable
internal fun UiActionGroup(
    modifier: Modifier = Modifier,
    backdrop: UiBackdrop? = LocalUiBackdrop.current,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val lift = rememberActionLift(source)
    val growthPx = with(LocalDensity.current) { 16.dp.toPx() }
    UiPane(
        role = UiPaneRole.Regular,
        shape = CircleShape,
        modifier = modifier.graphicsLayer {
            val growth = minOf(.25f, growthPx / maxOf(size.width, size.height).coerceAtLeast(1f)) * lift.value
            scaleX = 1f + growth
            scaleY = 1f + growth
        }.actionGlow(lift),
        backdrop = backdrop,
    ) {
        CompositionLocalProvider(LocalGroupPress provides source) {
            Row(verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

private fun Modifier.actionGlow(lift: Animatable<Float, AnimationVector1D>): Modifier = drawWithContent {
    drawContent()
    // Paint the moving highlight without reallocating the pane's cached glass effect.
    val strength = lift.value.coerceIn(0f, 1f) * .16f
    if (strength > 0f && size.minDimension > 0f) {
        drawRoundRect(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = strength), Color.Transparent),
                center = center,
                radius = size.maxDimension * .65f,
            ),
            cornerRadius = CornerRadius(size.minDimension / 2f),
        )
    }
}
