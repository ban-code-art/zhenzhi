package com.example.myapplication.ui.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.elevatedForSheet
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class UiSheetDragGate {
    var canDrag = false
        private set

    fun begin(contentAtTop: Boolean, imeVisible: Boolean) {
        canDrag = contentAtTop && !imeVisible
    }

    fun end() {
        canDrag = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UiSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    actionsAtTop: Boolean = false,
    dragContentAtTop: () -> Boolean = { true },
    confirmDismiss: () -> Boolean = { true },
    skipPartiallyExpanded: Boolean = true,
    maxContentFraction: Float = .60f,
    stableDrag: Boolean = false,
    dismissOnBlankTap: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val latestCanDismiss by rememberUpdatedState(confirmDismiss)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestNestedAtTop by rememberUpdatedState(dragContentAtTop)
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = skipPartiallyExpanded,
        confirmValueChange = { next -> next != SheetValue.Hidden || latestCanDismiss() },
    )
    val scroll = rememberScrollState()
    val gate = remember(state) { UiSheetDragGate() }
    var allowSheetGestures by remember(state) { mutableStateOf(true) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val dismissDistance = with(density) { 64.dp.toPx() }
    var dragOffset by remember(state) { mutableFloatStateOf(0f) }
    var settling by remember(state) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    var blankDismiss by remember(state) { mutableStateOf<Job?>(null) }
    val blankTapModifier = if (dismissOnBlankTap) {
        Modifier.pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Final)
                val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
                if (up != null &&
                    (up.position - down.position).getDistance() <= viewConfiguration.touchSlop &&
                    blankDismiss?.isActive != true &&
                    latestCanDismiss()
                ) {
                    up.consume()
                    blankDismiss = scope.launch {
                        state.hide()
                        if (!state.isVisible) latestDismiss()
                    }
                }
            }
        }
    } else Modifier
    fun dragBy(distance: Float): Float {
        settling?.cancel()
        val before = dragOffset
        dragOffset = (before + distance).coerceAtLeast(0f)
        return dragOffset - before
    }
    fun settleDrag() {
        if (dragOffset == 0f) return
        settling?.cancel()
        settling = scope.launch {
            if (dragOffset >= dismissDistance && latestCanDismiss()) {
                state.hide()
                if (!state.isVisible) {
                    latestDismiss()
                    return@launch
                }
            }
            animate(dragOffset, 0f, animationSpec = tween(180, easing = LinearOutSlowInEasing)) { value, _ ->
                dragOffset = value
            }
        }
    }
    val headerDrag = rememberDraggableState { dragBy(it) }
    val headerGesture = if (stableDrag) {
        Modifier.draggable(
            state = headerDrag,
            orientation = Orientation.Vertical,
            enabled = ime.getBottom(density) == 0,
            onDragStopped = { settleDrag() },
        )
    } else Modifier
    val bodyGesture = if (stableDrag) {
        Modifier.nestedScroll(remember(state, ime, density) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && dragOffset > 0f && available.y < 0f)
                        Offset(0f, dragBy(available.y)) else Offset.Zero

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && allowSheetGestures && available.y > 0f)
                        Offset(0f, dragBy(available.y)) else Offset.Zero

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (dragOffset == 0f) return Velocity.Zero
                    settleDrag()
                    return Velocity(0f, available.y)
                }
            }
        })
    } else Modifier
    val dark = UiRoles.dark
    val currentScheme = MaterialTheme.colorScheme
    val elevatedScheme = remember(currentScheme, dark) {
        currentScheme.elevatedForSheet(dark)
    }
    ModalBottomSheet(
        sheetState = state,
        onDismissRequest = onDismiss,
        modifier = modifier
            .pointerInput(state, ime, density) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    gate.begin(
                        contentAtTop = scroll.value == 0 && latestNestedAtTop(),
                        imeVisible = ime.getBottom(density) > 0,
                    )
                    allowSheetGestures = gate.canDrag
                    // Keep the sampled decision through fling. The next down samples the new position.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                    } while (event.changes.any { it.pressed })
                    gate.end()
                }
            }
            .then(if (stableDrag) Modifier.graphicsLayer { translationY = dragOffset } else Modifier)
            .semantics { paneTitle = title },
        sheetGesturesEnabled = !stableDrag && allowSheetGestures,
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(headerGesture)
                    .then(blankTapModifier),
                contentAlignment = Alignment.Center,
            ) {
                BottomSheetDefaults.DragHandle(
                    modifier = if (stableDrag) Modifier.testTag("ui-sheet-drag-handle") else Modifier,
                )
            }
        },
        containerColor = elevatedScheme.surfaceContainerHigh,
        scrimColor = Color.Black.copy(alpha = UiRoles.extended.modalScrimAlpha),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        MaterialTheme(colorScheme = elevatedScheme, typography = MaterialTheme.typography) {
            val height = LocalConfiguration.current.screenHeightDp.dp
            Column(Modifier.fillMaxWidth().then(blankTapModifier).imePadding()) {
                if (actionsAtTop) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        content = actions,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().then(headerGesture),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            Modifier
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .semantics { heading() },
                            style = UiRoles.text(UiRoles.pageTitle),
                            color = UiRoles.foreground,
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = height * maxContentFraction)
                        .then(bodyGesture)
                        .verticalScroll(scroll)
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    content = content,
                )
                if (!actionsAtTop) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        actions()
                    }
                }
            }
        }
    }
}
