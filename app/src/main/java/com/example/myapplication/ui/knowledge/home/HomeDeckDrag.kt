package com.example.myapplication.ui.knowledge.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal data class HomeCardLift(
    val key: String,
    val source: Rect,
    val pointer: Offset,
    val travel: Offset = Offset.Zero,
    val target: String? = null,
)

internal data class HomeCardLanding(
    val key: String,
    val from: Rect,
    val to: Rect,
)

@Stable
internal class HomeDeckDrag {
    private val slots = mutableStateMapOf<String, Rect>()
    var active by mutableStateOf<HomeCardLift?>(null)
        private set
    var landing by mutableStateOf<HomeCardLanding?>(null)
        private set

    fun place(key: String, slot: Rect) {
        slots[key] = slot
    }

    fun position(key: String): Rect? = slots[key]

    fun start(key: String, touch: Offset): Boolean {
        if (active != null) return false
        val slot = slots[key] ?: return false
        landing = null
        active = HomeCardLift(key, slot, slot.topLeft + touch)
        return true
    }

    fun move(delta: Offset, viewport: Rect) {
        val lift = active ?: return
        val pointer = lift.pointer + delta
        val travel = lift.travel + delta
        active = lift.copy(pointer = pointer, travel = travel, target = findTarget(lift.key, pointer, viewport))
    }

    fun refreshTarget(viewport: Rect) {
        val lift = active ?: return
        val target = findTarget(lift.key, lift.pointer, viewport)
        if (lift.target != target) active = lift.copy(target = target)
    }

    fun finish(commit: Boolean, viewport: Rect): Pair<String, String>? {
        val lift = active ?: return null
        val target = if (commit) findTarget(lift.key, lift.pointer, viewport) else null
        val destination = target?.let(slots::get) ?: slots[lift.key] ?: lift.source
        active = null
        landing = HomeCardLanding(
            lift.key,
            liftedCardBounds(lift, viewport),
            destination,
        )
        return target?.let { lift.key to it }
    }

    fun retain(keys: Set<String>) {
        slots.keys.retainAll(keys)
        if (active?.key !in keys) active = null
        if (landing?.key !in keys) landing = null
    }

    fun reset() {
        active = null
        landing = null
    }

    fun complete(transition: HomeCardLanding) {
        if (landing === transition) landing = null
    }

    private fun findTarget(key: String, pointer: Offset, viewport: Rect): String? {
        if (!viewport.contains(pointer)) return null
        val group = key.substringBefore(':')
        return slots.entries.firstOrNull { (other, bounds) ->
            other != key && other.substringBefore(':') == group && bounds.contains(pointer)
        }?.key
    }
}

internal fun liftedCardBounds(lift: HomeCardLift, viewport: Rect): Rect {
    val desired = lift.source.topLeft + lift.travel
    val x = desired.x.coerceIn(viewport.left, maxOf(viewport.left, viewport.right - lift.source.width))
    val y = desired.y.coerceIn(viewport.top, maxOf(viewport.top, viewport.bottom - lift.source.height))
    return Rect(Offset(x, y), lift.source.size)
}

@Composable
internal fun Modifier.liftHomeCard(
    enabled: Boolean,
    key: String,
    onStart: (Offset) -> Boolean,
    onDrag: (Offset) -> Unit,
    onEnd: (Boolean) -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    if (!enabled) return this
    return pointerInput(key) {
        var lifted = false
        try {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    lifted = start(position)
                    if (lifted) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDragEnd = {
                    if (lifted) end(true)
                    lifted = false
                },
                onDragCancel = {
                    if (lifted) end(false)
                    lifted = false
                },
                onDrag = { change, amount ->
                    if (lifted) {
                        change.consume()
                        drag(amount)
                    }
                },
            )
        } finally {
            if (lifted) end(false)
        }
    }
}

@Composable
internal fun HomeDeckGrip(
    title: String,
    tag: String,
    canMoveBefore: Boolean,
    canMoveAfter: Boolean,
    onMove: (Int) -> Boolean,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(48.dp).testTag(tag)
            .semantics(mergeDescendants = true) {
                contentDescription = "移动 $title"
                customActions = buildList {
                    if (canMoveBefore) add(CustomAccessibilityAction("向前移") { onMove(-1) })
                    if (canMoveAfter) add(CustomAccessibilityAction("向后移") { onMove(1) })
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> onMove(-1)
                    Key.DirectionDown -> onMove(1)
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val spacing = 4.dp.toPx()
            for (column in -1..1 step 2) {
                for (row in -1..1) {
                    drawCircle(
                        tint,
                        radius = 1.3.dp.toPx(),
                        center = center + Offset(column * spacing / 2, row * spacing),
                    )
                }
            }
        }
    }
}

