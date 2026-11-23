package com.example.myapplication.ui.knowledge.home.bookshelf

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import kotlin.math.sign

internal data class HomeShelfEntry(
    val key: String,
    val title: String,
    val subtitle: String?,
    val actionLabel: String,
    val canOpen: Boolean,
)

private enum class ShelfFilter(val label: String, val prefix: String?) {
    All("全部", null),
    Course("内置课程", "course:"),
    Imported("个人书库", "import:");

    fun includes(entry: HomeShelfEntry): Boolean = prefix?.let(entry.key::startsWith) ?: true
}

@Composable
internal fun HomeBookshelf(
    entries: List<HomeShelfEntry>,
    totalCount: Int?,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val candidates = remember(entries) { entries.distinctBy { it.key }.take(ShelfEntryLimit) }
    var filterName by rememberSaveable { mutableStateOf(ShelfFilter.All.name) }
    val filter = ShelfFilter.entries.firstOrNull { it.name == filterName } ?: ShelfFilter.All
    var allSelection by rememberSaveable { mutableStateOf<String?>(null) }
    var courseSelection by rememberSaveable { mutableStateOf<String?>(null) }
    var importedSelection by rememberSaveable { mutableStateOf<String?>(null) }
    val savedSelection = when (filter) {
        ShelfFilter.All -> allSelection
        ShelfFilter.Course -> courseSelection
        ShelfFilter.Imported -> importedSelection
    }
    val currentEntries = remember(candidates, filter) { candidates.filter(filter::includes) }
    val motion = remember(filter) { ShelfInteraction(currentEntries, savedSelection) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val touchExploration = rememberShelfTouchExploration()
    val flat = density.fontScale >= 1.5f || touchExploration || motion.degraded
    val latestEntries by rememberUpdatedState(currentEntries)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOpen by rememberUpdatedState(onOpen)

    DisposableEffect(motion) {
        onDispose { motion.dispose() }
    }

    fun openIfStable(key: String) {
        val stableKey = motion.stableIndex?.let { motion.entries.getOrNull(it)?.key }
        if (latestEnabled && stableKey == key && latestEntries.any { it.key == key && it.canOpen }) latestOpen(key)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "书架",
                modifier = Modifier.semantics { heading() },
                color = UiRoles.pageForeground,
                style = UiRoles.text(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (candidate in ShelfFilter.entries) {
                    UiAction(
                        label = candidate.label,
                        modifier = Modifier.semantics { selected = filter == candidate },
                        enabled = enabled,
                        filled = filter == candidate,
                        actionId = "home-shelf-filter-${candidate.name}",
                        animatePress = true,
                        keepEnabledStyle = true,
                        onClick = { if (latestEnabled) filterName = candidate.name },
                    )
                }
            }
        }

        BoxWithConstraints(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
            val stageSize = ShelfStageSize.forWidth(maxWidth.value)
            val widthPx = with(density) { maxWidth.roundToPx().toFloat() }
            val heightPx = with(density) { stageSize.height.dp.roundToPx().toFloat() }
            val metrics = remember(widthPx, heightPx, density.density, flat) {
                if (flat) null else ShelfMetrics.create(widthPx, heightPx, density.density)
            }
            val savedKey = motion.selectedKey
            SideEffect {
                motion.synchronize(currentEntries, enabled, metrics, rtl, savedSelection, scope)
                if (savedKey != null) {
                    when (filter) {
                        ShelfFilter.All -> allSelection = savedKey
                        ShelfFilter.Course -> courseSelection = savedKey
                        ShelfFilter.Imported -> importedSelection = savedKey
                    }
                }
            }

            val displayed = motion.entries
            val current = displayed.firstOrNull { it.key == motion.selectedKey }
            if (currentEntries.isEmpty() || current == null) {
                Text(
                    "暂无可预览的图书",
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = UiRoles.pageSupporting,
                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                )
            } else {
                val base = motion.commandIndex
                fun step(delta: Int): Boolean {
                    if (!latestEnabled) return false
                    return motion.select(motion.commandIndex + delta, scope)
                }

                Column(Modifier.fillMaxWidth()) {
                    if (metrics != null) {
                        HomeShelfStage(
                            motion = motion,
                            metrics = metrics,
                            rtl = rtl,
                            coverLoading = enabled,
                            modifier = Modifier.fillMaxWidth().height(stageSize.height.dp).clipToBounds()
                                .onPreviewKeyEvent {
                                    if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    val sigma = if (rtl) -1 else 1
                                    when (it.key) {
                                        Key.DirectionLeft -> step(-sigma)
                                        Key.DirectionRight -> step(sigma)
                                        else -> false
                                    }
                                }
                                .focusable(enabled)
                                .semantics {
                                    contentDescription = "立体书架"
                                    if (enabled) {
                                        customActions = buildList {
                                            if (base > 0) add(CustomAccessibilityAction("上一册") { step(-1) })
                                            if (base < displayed.lastIndex) add(CustomAccessibilityAction("下一册") { step(1) })
                                        }
                                    }
                                }
                                .shelfGestures(metrics, motion, enabled, rtl, scope, ::openIfStable),
                        )
                    }
                    val captionIndex = displayed.indexOf(current)
                    val selecting = motion.phase == ShelfInteraction.Phase.Drag ||
                        motion.phase == ShelfInteraction.Phase.Settle
                    ShelfSelection(
                        current = current,
                        captionIndex = captionIndex,
                        count = displayed.size,
                        canOpen = enabled && motion.stableIndex == captionIndex && current.canOpen,
                        navigationSuspended = !enabled || motion.phase == ShelfInteraction.Phase.Suspended,
                        selecting = selecting,
                        previous = base > 0,
                        next = base < displayed.lastIndex,
                        onStep = { step(it) },
                        onOpen = { openIfStable(current.key) },
                        modifier = Modifier.padding(top = if (metrics == null) 0.dp else 8.dp),
                    )
                }
            }
        }
        if (totalCount != null && totalCount > candidates.size) {
            Text(
                "首页预览 ${candidates.size} 本，共 $totalCount 本",
                modifier = Modifier.fillMaxWidth(),
                color = UiRoles.pageSupporting,
                style = UiRoles.text(MaterialTheme.typography.bodySmall),
            )
        }
    }
}

@Composable
private fun ShelfSelection(
    current: HomeShelfEntry,
    captionIndex: Int,
    count: Int,
    canOpen: Boolean,
    navigationSuspended: Boolean,
    selecting: Boolean,
    previous: Boolean,
    next: Boolean,
    onStep: (Int) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stacked = LocalDensity.current.fontScale >= 1.3f
    val caption: @Composable (Modifier) -> Unit = { captionModifier ->
        Column(captionModifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
            stateDescription = "第 ${captionIndex + 1} 册，共 $count 册"
        }) {
            Text(
                current.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = UiRoles.pageForeground,
                style = UiRoles.text(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.SemiBold,
            )
            current.subtitle?.let {
                Text(
                    it,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = UiRoles.pageSupporting,
                    style = UiRoles.text(MaterialTheme.typography.bodySmall),
                )
            }
        }
    }
    val openButton: @Composable () -> Unit = {
        UiAction(
            label = current.actionLabel,
            modifier = if (selecting) Modifier.semantics { stateDescription = "正在选书" } else Modifier,
            enabled = canOpen,
            filled = true,
            actionId = "home-shelf-open",
            animatePress = true,
            keepEnabledStyle = current.canOpen,
            onClick = onOpen,
        )
    }
    val stepping: @Composable () -> Unit = {
        if (count > 1) {
            UiAction(
                label = "上一册",
                icon = UiIcons.Back,
                enabled = previous && !navigationSuspended,
                keepEnabledStyle = previous,
                actionId = "home-shelf-previous",
                animatePress = true,
                onClick = { onStep(-1) },
            )
            UiAction(
                label = "下一册",
                icon = UiIcons.Chevron,
                enabled = next && !navigationSuspended,
                keepEnabledStyle = next,
                actionId = "home-shelf-next",
                animatePress = true,
                onClick = { onStep(1) },
            )
        }
    }
    Column(modifier.fillMaxWidth()) {
        if (stacked) {
            caption(Modifier.fillMaxWidth())
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                stepping()
                openButton()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                caption(Modifier.weight(1f).padding(end = 6.dp))
                openButton()
            }
            if (count > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) { stepping() }
            }
        }
    }
}

private fun Modifier.shelfGestures(
    metrics: ShelfMetrics,
    motion: ShelfInteraction,
    enabled: Boolean,
    rtl: Boolean,
    scope: CoroutineScope,
    open: (String) -> Unit,
): Modifier = pointerInput(metrics, motion, enabled, rtl) {
    if (!enabled) return@pointerInput
    val sigma = if (rtl) -1f else 1f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
        if (currentEvent.changes.count { it.pressed } != 1 || motion.entries.isEmpty()) return@awaitEachGesture
        val start = down.position
        val stable = motion.stableIndex
        val hit = metrics.hitTest(motion.entries.size, motion.q, ShelfPoint(start.x, start.y), rtl)
        val pressedKey = hit?.index?.let { motion.entries.getOrNull(it)?.key }
        val canCenterOpen = stable != null && hit?.index == stable
        val token = motion.beginPress()
        val velocity = VelocityTracker()
        velocity.addPosition(down.uptimeMillis, start)
        val touchSlop = viewConfiguration.touchSlop
        var dragging = false
        try {
            if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) return@awaitEachGesture
            while (motion.owns(token)) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) }) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break
                val displacement = change.position - start
                velocity.addPosition(change.uptimeMillis, change.position)
                if (!change.pressed) {
                    if (dragging) {
                        change.consume()
                        motion.release(token, -sigma * velocity.calculateVelocity().x / metrics.pitch, scope)
                    } else if (
                        hit != null && pressedKey != null && displacement.getDistance() <= touchSlop &&
                        change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis &&
                        motion.entries.getOrNull(hit.index)?.key == pressedKey
                    ) {
                        val up = metrics.hitTest(
                            motion.entries.size, motion.q, ShelfPoint(change.position.x, change.position.y), rtl,
                        )
                        if (up?.index == hit.index) {
                            change.consume()
                            motion.endPress(token, hit.index, scope)
                            if (canCenterOpen) open(pressedKey)
                        }
                    }
                    break
                }
                val dx = displacement.x
                val dy = displacement.y
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    if (abs(dx) < 1.15f * abs(dy)) break
                    dragging = true
                    change.consume()
                    motion.drag(token, -sigma * (dx - dx.sign * touchSlop) / metrics.pitch)
                } else if (dragging) {
                    change.consume()
                    motion.drag(token, -sigma * (change.position.x - change.previousPosition.x) / metrics.pitch)
                } else if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) {
                    break
                }
            }
        } finally {
            motion.endPress(token, null, scope)
        }
    }
}

@Composable
private fun rememberShelfTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var exploring by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploring = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        exploring = manager?.isTouchExplorationEnabled == true
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return exploring
}
