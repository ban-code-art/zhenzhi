package com.example.myapplication.ui.knowledge.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt

/** Measurements belong to the stable, untransformed children, never their animated bounds. */
internal class HomeDeckMeasurements {
    var compactHeight: Int = 0
    var expandedHeight: Int = 0
}

/** A reversible scroll path, frozen until the latest toggle has reached its endpoint. */
internal class HomeDeckScrollPath(
    private val compactHeight: Int,
    private val expandedHeight: Int,
    private val padding: Int,
    private val viewportHeight: Int,
    compactOffset: Int,
    expandedOffset: Int,
) {
    private val compactRatio = ratio(compactOffset, range(0f))
    private val expandedRatio = ratio(expandedOffset, range(1f))

    val compactOffset: Int get() = offset(0f)
    val expandedOffset: Int get() = offset(1f)

    fun range(progress: Float): Int =
        (homeDeckHeight(compactHeight, expandedHeight, progress) + padding - viewportHeight).coerceAtLeast(0)

    fun offset(progress: Float): Int {
        val phase = progress.coerceIn(0f, 1f)
        val fraction = compactRatio + (expandedRatio - compactRatio) * phase
        return (range(phase) * fraction).roundToInt().coerceIn(0, range(phase))
    }

    private fun ratio(offset: Int, range: Int): Float =
        if (range == 0) 0f else (offset.toFloat() / range).coerceIn(0f, 1f)
}

internal data class HomeDeckCardPose(val bounds: Rect, val alpha: Float, val contentAlpha: Float)

internal fun homeDeckHeight(compact: Int, expanded: Int, progress: Float): Int =
    (compact + (expanded - compact) * progress.coerceIn(0f, 1f)).roundToInt().coerceAtLeast(0)

internal fun homeDeckPhase(progress: Float, index: Int): Float {
    val delay = index.coerceIn(0, 4) * .025f
    return ((progress.coerceIn(0f, 1f) - delay) / (1f - delay)).coerceIn(0f, 1f)
}

internal fun homeDeckFade(progress: Float, start: Float, end: Float): Float {
    val phase = ((progress - start) / (end - start)).coerceIn(0f, 1f)
    return phase * phase * (3f - 2f * phase)
}

internal fun homeDeckCardPose(
    slot: Rect,
    stack: Rect?,
    progress: Float,
    index: Int,
    scrollOffset: Int = 0,
    scrollPath: HomeDeckScrollPath? = null,
    layerOffset: Offset = Offset.Zero,
): HomeDeckCardPose {
    val phase = homeDeckPhase(progress, index)
    val alpha = homeDeckFade(phase, .04f, .34f)
    val contentAlpha = homeDeckFade(phase, .42f, .88f)
    if (stack == null || stack.isEmpty || slot.isEmpty) return HomeDeckCardPose(slot, alpha, contentAlpha)

    // Keep viewport endpoints fixed while the scroll range changes underneath the cards.
    val compact = stack.translate(layerOffset + Offset(0f, (scrollOffset - (scrollPath?.compactOffset ?: scrollOffset)).toFloat()))
    val expanded = slot.translate(Offset(0f, (scrollOffset - (scrollPath?.expandedOffset ?: scrollOffset)).toFloat()))
    return HomeDeckCardPose(homeDeckLerpRect(compact, expanded, phase), alpha, contentAlpha)
}

internal fun homeDeckLerpRect(from: Rect, to: Rect, progress: Float): Rect {
    val phase = progress.coerceIn(0f, 1f)
    return Rect(
        from.left + (to.left - from.left) * phase,
        from.top + (to.top - from.top) * phase,
        from.right + (to.right - from.right) * phase,
        from.bottom + (to.bottom - from.bottom) * phase,
    )
}

/** Both endpoints remain measured; only the containing height and drawing change per frame. */
@Composable
internal fun HomeDeckTransitionLayout(
    progress: () -> Float,
    measurements: HomeDeckMeasurements,
    expandedOnTop: Boolean,
    modifier: Modifier = Modifier,
    compact: @Composable () -> Unit,
    expanded: @Composable () -> Unit,
    top: @Composable () -> Unit = {},
    tail: @Composable () -> Unit = {},
) {
    Layout(
        modifier = modifier,
        content = {
            Box { compact() }
            Box { expanded() }
            Box { tail() }
            Box { top() }
        },
    ) { children, constraints ->
        val fullHeight = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val compactChild = children[0].measure(fullHeight)
        val expandedChild = children[1].measure(fullHeight)
        val tailChild = children[2].measure(fullHeight)
        val deckHeight = homeDeckHeight(compactChild.height, expandedChild.height, progress())
        val topChild = children[3].measure(fullHeight)
        measurements.compactHeight = topChild.height + compactChild.height + tailChild.height
        measurements.expandedHeight = topChild.height + expandedChild.height + tailChild.height
        val height = constraints.constrainHeight(topChild.height + deckHeight + tailChild.height)
        val width = constraints.constrainWidth(maxOf(compactChild.width, expandedChild.width, tailChild.width, topChild.width))
        layout(width, height) {
            topChild.placeRelative(0, 0, zIndex = 2f)
            compactChild.placeRelative(0, topChild.height, zIndex = if (expandedOnTop) 0f else 1f)
            expandedChild.placeRelative(0, topChild.height, zIndex = if (expandedOnTop) 1f else 0f)
            tailChild.placeRelative(0, topChild.height + deckHeight, zIndex = 2f)
        }
    }
}
