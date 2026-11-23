package com.example.myapplication.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * A display-only disclosure. The caller keeps expanded state, business state and the real access
 * gate. The trigger must apply the supplied Modifier to its single focusable action.
 */
@Composable
internal fun UiInlineExpand(
    ownerKey: Any,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentAvailable: Boolean = true,
    trigger: @Composable (modifier: Modifier, progress: Float, enabled: Boolean, onToggle: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    key(ownerKey) {
        val opened = expanded && contentAvailable
        val progress by animateFloatAsState(
            targetValue = if (opened) 1f else 0f,
            animationSpec = spring(
                dampingRatio = if (opened) .85f else 1f,
                stiffness = inlineSpringStiffness(if (opened) .42 else .34),
                visibilityThreshold = .01f,
            ),
            label = "inline.expand",
        )
        val triggerFocus = remember { FocusRequester() }
        var restoreTriggerFocus by remember { mutableStateOf(false) }
        LaunchedEffect(opened) {
            if (!opened && restoreTriggerFocus) {
                triggerFocus.requestFocus()
                restoreTriggerFocus = false
            }
        }
        Column(modifier) {
            trigger(
                Modifier.focusRequester(triggerFocus).semantics {
                    stateDescription = if (opened) "已展开" else "已收起"
                },
                progress.coerceIn(0f, 1f),
                contentAvailable,
            ) {
                if (contentAvailable) onExpandedChange(!opened)
            }

            if (contentAvailable) {
                // Keep editor/list composition alive across collapse, but remove all input routes.
                val inactive = if (opened) Modifier else Modifier
                    .clearAndSetSemantics { hideFromAccessibility() }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    }
                Layout(
                    content = {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Box(Modifier.graphicsLayer { alpha = progress.coerceIn(0f, 1f) }) {
                                content()
                            }
                        }
                    },
                    modifier = Modifier.clipToBounds()
                        .then(inactive)
                        .focusProperties { onEnter = { if (!opened) cancelFocusChange() } }
                        .onFocusChanged { if (it.hasFocus) restoreTriggerFocus = true else if (opened) restoreTriggerFocus = false }
                        .focusGroup(),
                ) { measurables, constraints ->
                    val child = measurables.single().measure(constraints.copy(minHeight = 0))
                    val visibleHeight = (child.height * progress.coerceIn(0f, 1f)).roundToInt()
                    layout(child.width, constraints.constrainHeight(visibleHeight)) {
                        child.placeRelative(0, 0)
                    }
                }
            }
        }
    }
}

private fun inlineSpringStiffness(seconds: Double): Float =
    (2 * PI / seconds).let { (it * it).toFloat() }
