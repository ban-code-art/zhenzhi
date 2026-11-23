package com.example.myapplication.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.mine.EnhancementTogglePhase
import com.example.myapplication.ui.mine.EnhancementToggleStatus
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private val SwitchEaseOutBack = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
private val SwitchTrackWidth = 52.dp
private val SwitchTrackHeight = 32.dp
private val SwitchThumbSize = 24.dp

/** Single-owner name-only switch row for Settings (CF07 / SET06-07). */
@Composable
internal fun UiSettingsSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    status: EnhancementToggleStatus = EnhancementToggleStatus(),
    icon: ImageVector? = null,
    iconTint: Color = UiRoles.iconTint,
    iconContainerColor: Color = UiRoles.iconContainer,
    enabled: Boolean = true,
) {
    require(title.isNotBlank()) { "switch title must not be blank" }
    val effectiveEnabled = enabled && status.canChange
    val latestChecked by rememberUpdatedState(checked)
    val latestCanChange by rememberUpdatedState(effectiveEnabled)
    val latestOnChange by rememberUpdatedState(onCheckedChange)
    val interactionSource = remember { MutableInteractionSource() }
    val rowPressed by interactionSource.collectIsPressedAsState()
    var dragPreview by remember { mutableStateOf<Float?>(null) }
    var thumbPressed by remember { mutableStateOf(false) }

    val targetProgress = dragPreview ?: if (checked) 1f else 0f
    val progress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = if (dragPreview != null) tween(0) else tween(300, easing = SwitchEaseOutBack),
        label = "switch-progress",
    )
    val lift by animateFloatAsState(
        targetValue = if ((rowPressed || thumbPressed || dragPreview != null) && effectiveEnabled) 1f else 0f,
        animationSpec = tween(durationMillis = if (rowPressed || thumbPressed) 150 else 200),
        label = "switch-lift",
    )

    val stretch = sin(PI * progress.coerceIn(0f, 1f)).toFloat() * (if (dragPreview != null) 1f else lift * 0.4f)
    val thumbWidth by animateDpAsState(
        targetValue = (SwitchThumbSize + (10.dp * stretch) + (4.dp * lift)).coerceIn(SwitchThumbSize, 40.dp),
        animationSpec = tween(150),
        label = "switch-thumb-width",
    )
    val thumbHeight by animateDpAsState(
        targetValue = (SwitchThumbSize - (2.dp * stretch)).coerceAtLeast(22.dp),
        animationSpec = tween(150),
        label = "switch-thumb-height",
    )

    val colors = MaterialTheme.colorScheme
    val trackColor by animateColorAsState(
        targetValue = when {
            checked && effectiveEnabled -> colors.primary
            checked -> colors.primary.copy(alpha = 0.55f)
            else -> UiRoles.surface(UiSurfaceRole.Inset)
        },
        animationSpec = tween(200),
        label = "switch-track",
    )
    val thumbColor = when {
        checked -> colors.onPrimary
        else -> colors.surfaceBright
    }
    val effectiveGlass = LocalUiGlassQuality.current.effective != UiGlassQuality.None
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current

    val stateLabel = status.message ?: if (checked) "开启" else "关闭"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {
                stateDescription = stateLabel
            }
            .toggleable(
                value = checked,
                enabled = effectiveEnabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onValueChange = { next ->
                    if (latestCanChange) latestOnChange(next)
                },
            )
            .alpha(if (effectiveEnabled) 1f else 0.54f)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(iconContainerColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = iconTint,
                )
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
        ) {
            Text(
                text = title,
                style = UiRoles.text(UiRoles.rowText),
                color = UiRoles.foreground,
            )
        }
        Spacer(Modifier.width(16.dp))
        if (status.phase == EnhancementTogglePhase.Loading || status.phase == EnhancementTogglePhase.Saving) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp).size(16.dp),
                strokeWidth = 2.dp,
            )
        } else if (status.phase == EnhancementTogglePhase.Failed ||
            status.phase == EnhancementTogglePhase.ReadFailed ||
            status.phase == EnhancementTogglePhase.Unconfirmed
        ) {
            Icon(
                imageVector = UiIcons.Info,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.padding(end = 8.dp).size(16.dp),
            )
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .size(width = SwitchTrackWidth, height = SwitchTrackHeight)
                .clip(CircleShape)
                .background(trackColor)
                .border(
                    width = if (checked) 0.dp else 1.dp,
                    color = if (checked) Color.Transparent else colors.outline.copy(alpha = 0.45f),
                    shape = CircleShape,
                )
                .pointerInput(isRtl) {
                    val travelPx = with(density) { (SwitchTrackWidth - SwitchThumbSize - 8.dp).toPx() }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!latestCanChange) return@awaitEachGesture
                        thumbPressed = true
                        var fraction = if (latestChecked) 1f else 0f
                        var dragging = false
                        try {
                            val dragStart = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                dragging = true
                                change.consume()
                                val signed = if (isRtl) -over else over
                                fraction = ((if (latestChecked) 1f else 0f) + signed / travelPx).coerceIn(0f, 1f)
                                dragPreview = fraction
                            }
                            if (dragStart != null && dragging) {
                                val finished = horizontalDrag(dragStart.id) { change ->
                                    val delta = change.position.x - change.previousPosition.x
                                    val signed = if (isRtl) -delta else delta
                                    fraction = (fraction + signed / travelPx).coerceIn(0f, 1f)
                                    dragPreview = fraction
                                    change.consume()
                                }
                                if (finished && latestCanChange) {
                                    val committed = fraction >= 0.5f
                                    if (committed != latestChecked) {
                                        latestOnChange(committed)
                                    }
                                }
                            }
                        } finally {
                            dragPreview = null
                            thumbPressed = false
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val thumbInset = 4.dp
            val travelDp = (SwitchTrackWidth - thumbInset * 2 - thumbWidth).coerceAtLeast(0.dp)
            val thumbOffsetPx = with(density) { (thumbInset + travelDp * progress.coerceIn(0f, 1f)).roundToPx() }
            val lensTint = if (effectiveGlass && lift > 0f) {
                thumbColor.copy(alpha = (1f - 0.14f * lift).coerceIn(0.82f, 1f))
            } else thumbColor
            Box(
                modifier = Modifier
                    .offset { IntOffset(thumbOffsetPx, 0) }
                    .size(width = thumbWidth, height = thumbHeight)
                    .shadow(if (lift > 0.05f) 4.dp else 1.5.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(lensTint)
                    .then(
                        if (effectiveGlass && lift > 0f) {
                            Modifier.border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.65f * lift),
                                shape = CircleShape,
                            )
                        } else Modifier,
                    ),
            )
        }
    }
}
