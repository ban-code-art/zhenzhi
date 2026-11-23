package com.example.myapplication.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapplication.ui.theme.UiRoles
import kotlin.math.PI
import kotlin.math.exp

internal data class UiAlertExit(val confirmed: Boolean, val scale: Float, val pull: Offset)

internal class UiAlertDecision(private val owner: Any) {
    var exit by mutableStateOf<UiAlertExit?>(null)
        private set
    private var consumed = false

    fun request(confirmed: Boolean, scale: Float, pull: Offset = Offset.Zero): Boolean {
        if (exit != null) return false
        exit = UiAlertExit(confirmed, scale, pull)
        return true
    }

    fun consume(currentOwner: Any): Boolean? {
        val pending = exit ?: return null
        if (consumed) return null
        consumed = true
        return pending.confirmed.takeIf { owner == currentOwner }
    }
}

private const val AlertWindowMillis = 404
private const val AlertSpringMillis = 275.0
private val AlertSpringEasing = Easing { fraction ->
    if (fraction >= 1f) 1f else {
        val time = 2.0 * PI * AlertWindowMillis / AlertSpringMillis * fraction
        (1.0 - (1.0 + time) * exp(-time)).toFloat()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UiConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    ownerKey: Any = title,
    canConfirm: Boolean = true,
    busy: Boolean = false,
    destructive: Boolean = true,
    actionId: String = "alert",
) {
    val decision = remember(ownerKey) { UiAlertDecision(ownerKey) }
    val exit = decision.exit
    val latestOwner by rememberUpdatedState(ownerKey)
    val latestConfirm by rememberUpdatedState(onConfirm)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val confirmEnabled by rememberUpdatedState(canConfirm && !busy)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    UiAlertHost(
        title = title,
        ownerKey = ownerKey,
        exitRequested = exit != null,
        frozenScale = exit?.scale,
        frozenPull = exit?.pull,
        onDismissRequest = { scale, pull -> decision.request(false, scale, pull) },
        onVisibilitySettled = {
            when (decision.consume(latestOwner)) {
                true -> if (confirmEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    latestConfirm()
                } else {
                    latestDismiss()
                }
                false -> latestDismiss()
                null -> Unit
            }
        },
        modifier = modifier,
    ) { interaction, currentScale, currentPull ->
        val colors = MaterialTheme.colorScheme
        val cancelFocus = remember(ownerKey) { FocusRequester() }
        LaunchedEffect(ownerKey) { cancelFocus.requestFocus() }
        Text(
            title,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UiAction(
                label = dismissLabel,
                onClick = { decision.request(false, currentScale(), currentPull()) },
                modifier = Modifier.focusRequester(cancelFocus),
                actionId = "$actionId.cancel",
                enabled = exit == null,
                inheritedPane = true,
                interactionSource = interaction,
                animatePress = false,
            )
            UiAction(
                label = confirmLabel,
                onClick = { if (confirmEnabled) decision.request(true, currentScale(), currentPull()) },
                actionId = "$actionId.confirm",
                enabled = exit == null && canConfirm,
                busy = busy,
                foreground = if (destructive) UiRoles.extended.destructive else UiRoles.accent,
                inheritedPane = true,
                interactionSource = interaction,
                animatePress = false,
            )
        }
    }
}

/** Shared single-pane Alert container (corner 34dp, 404/275ms spring entrance, dim scrim). */
@Composable
internal fun UiAlertHost(
    title: String,
    ownerKey: Any,
    onDismissRequest: (scale: Float, pull: Offset) -> Unit,
    modifier: Modifier = Modifier,
    exitRequested: Boolean = false,
    frozenScale: Float? = null,
    frozenPull: Offset? = null,
    onVisibilitySettled: () -> Unit = {},
    content: @Composable ColumnScope.(
        interaction: MutableInteractionSource,
        currentScale: () -> Float,
        currentPull: () -> Offset,
    ) -> Unit,
) {
    val visibility = remember(ownerKey) { Animatable(0f) }
    val interaction = remember(ownerKey) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = .593f, stiffness = 311f),
        label = "confirmation-press",
    )
    val density = LocalDensity.current
    val maxGrowth = with(density) { 4.dp.toPx() }
    val maxPull = with(density) { 3.dp.toPx() }
    var pointerPull by remember(ownerKey) { mutableStateOf(Offset.Zero) }
    val pull by animateOffsetAsState(
        targetValue = if (pressed) pointerPull else Offset.Zero,
        animationSpec = spring(dampingRatio = .593f, stiffness = 311f),
        label = "confirmation-pull",
    )
    var cardExtentPx by remember(ownerKey) { mutableStateOf(0) }
    val latestSettled by rememberUpdatedState(onVisibilitySettled)

    fun currentScale(): Float {
        val routeScale = 1.2f - .2f * visibility.value
        val pressGrowth = if (cardExtentPx > 0) maxGrowth * press / cardExtentPx else 0f
        return routeScale * (1f + pressGrowth)
    }

    LaunchedEffect(ownerKey, exitRequested) {
        visibility.animateTo(
            targetValue = if (!exitRequested) 1f else 0f,
            animationSpec = tween(AlertWindowMillis, easing = AlertSpringEasing),
        )
        latestSettled()
    }

    Dialog(
        onDismissRequest = { onDismissRequest(currentScale(), pull) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val scrim = UiRoles.extended.modalScrimAlpha
        DisposableEffect(window) {
            window?.setWindowAnimations(0)
            onDispose {}
        }
        LaunchedEffect(window, scrim) {
            snapshotFlow { visibility.value }.collect { window?.setDimAmount(scrim * it) }
        }
        UiPane(
            role = UiPaneRole.Alert,
            shape = RoundedCornerShape(34.dp),
            modifier = modifier
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .widthIn(max = 348.dp)
                .fillMaxWidth()
                .onSizeChanged { cardExtentPx = maxOf(it.width, it.height) }
                .pointerInput(ownerKey, maxPull) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        try {
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull {
                                    it.id == down.id && it.pressed
                                } ?: break
                                val movement = change.position - down.position
                                val distance = movement.getDistance()
                                pointerPull = if (distance > maxPull) movement * (maxPull / distance) else movement
                            }
                        } finally {
                            pointerPull = Offset.Zero
                        }
                    }
                }
                .graphicsLayer {
                    val scale = frozenScale ?: currentScale()
                    scaleX = scale
                    scaleY = scale
                    translationX = (frozenPull ?: pull).x
                    translationY = (frozenPull ?: pull).y
                    alpha = visibility.value
                }
                .semantics { paneTitle = title },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                content(interaction, ::currentScale, { pull })
            }
        }
    }
}
