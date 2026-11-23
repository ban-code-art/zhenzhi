package com.example.myapplication.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import com.example.myapplication.ui.components.LocalUiMotionBusy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import kotlinx.coroutines.CancellationException

private const val CoveredOffset = .30f
private const val PageStiffness = 438.65f
private const val CommittedSettleStiffness = 1200f
private const val PushDurationMs = 260
private const val PopDurationMs = 230
private val PopDecelerationEasing = CubicBezierEasing(0.18f, 0.92f, 0.28f, 1f)

private fun <T> pageMotion(forward: Boolean) =
    if (forward) tween<T>(PushDurationMs, easing = FastOutSlowInEasing)
    else tween<T>(PopDurationMs, easing = PopDecelerationEasing)

internal fun uiPageEnter(forward: Boolean, direction: Int, preview: PageBackPreview? = null): EnterTransition {
    val resolvedDirection = if (forward) direction else preview?.committedDirection ?: direction
    return slideInHorizontally(pageMotion(forward)) { width ->
        if (forward) width * resolvedDirection else (-width * CoveredOffset * resolvedDirection).toInt()
    }
}

internal fun uiPageExit(forward: Boolean, direction: Int, preview: PageBackPreview? = null): ExitTransition {
    val resolvedDirection = if (forward) direction else preview?.committedDirection ?: direction
    return slideOutHorizontally(pageMotion(forward)) { width ->
        if (forward) (-width * CoveredOffset * resolvedDirection).toInt() else width * resolvedDirection
    }
}

internal val LocalNavigationPageActive = compositionLocalOf { true }
internal val LocalPageBackPreview = staticCompositionLocalOf<PageBackPreview?> { null }

internal class PageBackPreview {
    var offset by mutableFloatStateOf(0f)
        private set
    private var revision = 0L
    private var pendingCommit: Long? = null
    var committedDirection: Int? = null
        private set

    fun follow(progress: Float, fromRight: Boolean): Long {
        revision += 1
        pendingCommit = null
        committedDirection = null
        offset = progress.coerceIn(0f, 1f) * .22f * if (fromRight) -1f else 1f
        return revision
    }

    fun commit(expectedRevision: Long) {
        if (revision == expectedRevision) {
            pendingCommit = expectedRevision
            committedDirection = if (offset < 0f) -1 else 1
        }
    }

    fun hasPendingCommit(): Boolean = pendingCommit != null

    /** Transfers the held gesture offset directly into a stack exit animation to avoid reverse drift. */
    fun consumeCommittedOffset(): Float? {
        if (pendingCommit == null) return null
        val held = offset
        reset()
        return held
    }

    suspend fun settleAfterNavigation() {
        pendingCommit?.let { restore(it, CommittedSettleStiffness) }
    }

    suspend fun restoreUnchangedCommit(expectedRevision: Long) {
        if (pendingCommit == expectedRevision) restore(expectedRevision)
    }

    suspend fun restore(expectedRevision: Long = revision, stiffness: Float = PageStiffness) {
        if (revision != expectedRevision) return
        pendingCommit = null
        val restoration = ++revision
        try {
            animate(offset, 0f, animationSpec = spring(dampingRatio = 1f, stiffness = stiffness)) { value, _ ->
                // A new gesture owns its position immediately, even if an older restore is still queued.
                if (revision != restoration) throw CancellationException("Back preview superseded")
                offset = value
            }
        } finally {
            if (revision == restoration) reset()
        }
    }

    fun reset() {
        revision += 1
        pendingCommit = null
        committedDirection = null
        offset = 0f
    }
}

@Composable
internal fun UiNavigationFrame(navigationKey: Any? = Unit, content: @Composable () -> Unit) {
    val preview = remember { PageBackPreview() }
    val backPreviewActive by remember(preview) { derivedStateOf { preview.offset != 0f } }
    var previousNavigationKey by remember { mutableStateOf(navigationKey) }
    var navigating by remember { mutableStateOf(false) }
    LaunchedEffect(navigationKey) {
        if (previousNavigationKey != navigationKey) {
            previousNavigationKey = navigationKey
            navigating = true
            val settle = launch { preview.settleAfterNavigation() }
            delay(PopDurationMs.toLong())
            navigating = false
            settle.join()
        }
    }
    val background = MaterialTheme.colorScheme.surfaceContainer
    Box(Modifier.fillMaxSize().clipToBounds().background(background)) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = size.width * preview.offset
            },
        ) {
            CompositionLocalProvider(LocalPageBackPreview provides preview,
                LocalUiMotionBusy provides (navigating || backPreviewActive), content = content)
        }
    }
}

@Composable
internal fun UiPageStack(
    page: List<String>,
    preview: PageBackPreview? = LocalPageBackPreview.current,
    followBackPreview: Boolean = false,
    content: @Composable (List<String>) -> Unit,
) {
    val direction = if (androidx.compose.ui.platform.LocalLayoutDirection.current ==
        androidx.compose.ui.unit.LayoutDirection.Rtl) -1 else 1
    val parentActive = LocalNavigationPageActive.current
    val pages = remember { mutableStateListOf(page) }
    if (page !in pages) pages.add(page)
    val background = MaterialTheme.colorScheme.background
    val previousTop = remember { arrayOf(page) }
    val previewOwner = previousTop[0]
    SideEffect { previousTop[0] = page }
    Box(Modifier.fillMaxSize().clipToBounds()) {
    pages.toList().forEach { visualPage -> key(visualPage) {
        val position = remember {
            val initialPosition = when {
                visualPage == previewOwner -> 0f
                visualPage == page && previewOwner.take(page.size) == page -> -CoveredOffset
                else -> 1f
            }
            Animatable(initialPosition)
        }
        val ancestor = page.take(visualPage.size) == visualPage
        val target = when { visualPage == page -> 0f; ancestor -> -CoveredOffset; else -> 1f }
        var exitDirection by remember { mutableFloatStateOf(1f) }
        LaunchedEffect(target) {
            if (target == 1f && visualPage == previewOwner) {
                val held = preview?.consumeCommittedOffset()
                if (held != null && held != 0f) {
                    exitDirection = if (held * direction < 0f) -1f else 1f
                    position.snapTo(kotlin.math.abs(held))
                }
            }
            position.animateTo(target, pageMotion(forward = if (target == 0f) position.value > 0f else target < 0f))
            // Keep covered ancestors measured and alive; dispose only completed pops.
            if (target == 1f) pages.remove(visualPage)
            else exitDirection = 1f
        }
        val active = parentActive && visualPage == page
        val moving by remember(target) { derivedStateOf { position.isRunning || position.value != target } }
        CompositionLocalProvider(LocalNavigationPageActive provides active,
            LocalUiMotionBusy provides (LocalUiMotionBusy.current || moving)) {
            Box(Modifier.fillMaxSize().zIndex(visualPage.size.toFloat()).graphicsLayer {
                val tracksGesture = followBackPreview && (active ||
                    (visualPage == previewOwner && target == 1f && preview?.hasPendingCommit() == true))
                val gestureOffset = if (tracksGesture) preview?.offset ?: 0f else 0f
                translationX = (position.value * direction * exitDirection + gestureOffset) * size.width
                alpha = if (visualPage != page && ancestor && !moving) 0f else 1f
            }.background(background).then(
                if (active) Modifier else Modifier
                    .clearAndSetSemantics { hideFromAccessibility() }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    },
            )) {
                content(visualPage)
            }
        }
    } }
    }
}

/** HOME remains mounted while the NavHost covers it, including all measured Dock tabs. */
@Composable
internal fun RetainedNavigationHome(active: Boolean, visible: Boolean, direction: Int, content: @Composable () -> Unit) {
    val position = animateFloatAsState(
        if (active) 0f else -CoveredOffset,
        pageMotion(forward = !active),
        label = "retained home",
    )
    Box(Modifier.fillMaxSize().graphicsLayer {
        translationX = size.width * position.value * direction
        alpha = if (visible || active) 1f else 0f
    }.then(if (active) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }
        .pointerInput(Unit) { awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        } })) {
        CompositionLocalProvider(LocalNavigationPageActive provides active, content = content)
    }
}
