package com.example.myapplication.ui.knowledge.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiBackdrop
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.components.learning.StudyCardParticleLayout
import com.example.myapplication.ui.components.learning.StudyCardParticleLayer
import com.example.myapplication.ui.components.learning.StudyCardParticleTarget
import com.example.myapplication.ui.knowledge.home.bookshelf.HomeBookshelf
import com.example.myapplication.ui.knowledge.home.bookshelf.HomeShelfEntry
import com.example.myapplication.ui.knowledge.KnowledgeHomeBook
import com.example.myapplication.ui.knowledge.KnowledgeHomeProjection
import com.example.myapplication.ui.knowledge.KnowledgeHomeUiState
import com.example.myapplication.ui.knowledge.course.BundledCourseBook
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

private data class HomeEntry(
    val key: String,
    val tag: String,
    val gripTag: String,
    val title: String,
    val metadata: String,
    val detail: String?,
    val action: String,
    val actionTag: String? = null,
    val canOpen: Boolean = true,
    val open: () -> Unit,
)

private data class HomeDeckGeometry(
    val viewportSize: Size,
    val density: Float,
    val fontScale: Float,
    val dockBottomInset: Dp,
)

@Composable
internal fun KnowledgeHomeDeckContent(
    state: KnowledgeHomeUiState,
    onOpenReader: (String) -> Unit,
    onOpenBundledCourse: (String) -> Unit,
    onOpenCourseLibrary: () -> Unit,
    onOpenImportedLibrary: () -> Unit,
    onReloadCourses: () -> Unit,
    onReloadLibrary: () -> Unit,
    dockBottomInset: Dp,
    modifier: Modifier = Modifier,
    routeActive: Boolean = true,
    reviewHeader: @Composable () -> Unit = {},
) = key(state.identity.userId, state.identity.epoch) {
    var frozenState by remember { mutableStateOf<KnowledgeHomeUiState?>(null) }
    val contentState = frozenState ?: state
    val openBundledCourse by rememberUpdatedState(onOpenBundledCourse)
    val openReader by rememberUpdatedState(onOpenReader)
    val shelfCourses = contentState.catalog?.books.orEmpty()
    val courseEntries = remember(shelfCourses) { shelfCourses.map { it.homeEntry { key -> openBundledCourse(key) } } }
    val courses = remember(courseEntries) { courseEntries.take(4) }
    val books = remember(contentState.importedBooks, contentState.signedIn) {
        if (contentState.signedIn) {
            contentState.importedBooks.take(KnowledgeHomeProjection.PREVIEW_BOOKS)
                .map { it.homeEntry { id -> openReader(id) } }
        } else emptyList()
    }
    val cards = remember(courses, books) { (courses + books).associateBy(HomeEntry::key) }
    val shelfImports = contentState.shelfBooks.takeIf { contentState.signedIn }.orEmpty()
    val shelfEntries = remember(courseEntries, shelfImports) {
        (courseEntries + shelfImports.map { it.homeEntry { id -> openReader(id) } })
            .take(KnowledgeHomeProjection.SHELF_BOOKS)
    }
    val shelfBooks = remember(shelfEntries) {
        shelfEntries.map { HomeShelfEntry(it.key, it.title, it.detail ?: it.metadata, it.action, it.canOpen) }
    }
    val shelfOpenActions = remember(shelfEntries) { shelfEntries.associateBy(HomeEntry::key) }
    val shelfCount = if (contentState.coursesLoading || contentState.libraryLoading ||
        contentState.courseError != null || contentState.libraryError != null) null else
        shelfCourses.size + contentState.importedBookCount

    val scroll = rememberScrollState()
    val drag = remember { HomeDeckDrag() }
    val deckFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    val headerFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var requestFocus by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var transitioning by remember { mutableStateOf(false) }
    var motionVersion by remember { mutableStateOf(0) }
    var motionVelocity by remember { mutableStateOf(0f) }
    var motionImmediate by remember { mutableStateOf(false) }
    var layoutSnapshot by remember { mutableStateOf<HomeDeckGeometry?>(null) }
    var scrollPath by remember { mutableStateOf<HomeDeckScrollPath?>(null) }
    val measurements = remember { HomeDeckMeasurements() }
    var courseOrder by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var bookOrder by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var rootBounds by remember { mutableStateOf(Rect.Zero) }
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    var stackBounds by remember { mutableStateOf<Rect?>(null) }
    val particleAnchors = remember { mutableStateMapOf<String, Rect>() }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = routeActive && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val particleMotionActive = routeActive && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val reducedMotion = motionScale <= 0f || !motionScale.isFinite()
    var announcement by remember { mutableStateOf("") }
    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    val activeKey by remember(drag) { derivedStateOf { drag.active?.key } }
    val density = LocalDensity.current
    val dockPx = with(density) { dockBottomInset.toPx() }
    val edge = with(density) { 52.dp.toPx() }
    val viewport = listBounds.copy(bottom = minOf(listBounds.bottom, rootBounds.bottom - dockPx).coerceAtLeast(listBounds.top))
    val courseKeys = remember(courseOrder, courses) { reconcileHomeOrder(courseOrder, courses.map(HomeEntry::key)) }
    val bookKeys = remember(bookOrder, books) { reconcileHomeOrder(bookOrder, books.map(HomeEntry::key)) }
    val motionOrder = remember(courseKeys, bookKeys) { courseKeys + bookKeys }
    val compactInteractive = !expanded && !transitioning && active
    val expandedInteractive = expanded && !transitioning && active

    fun toggle() {
        if (!transitioning) {
            frozenState = state
            scrollPath = HomeDeckScrollPath(
                compactHeight = measurements.compactHeight,
                expandedHeight = measurements.expandedHeight,
                padding = with(density) { (28.dp + dockBottomInset).roundToPx() },
                viewportHeight = listBounds.height.roundToInt(),
                compactOffset = if (expanded) 0 else scroll.value,
                expandedOffset = if (expanded) scroll.value else 0,
            )
        }
        // Preserve the lifted card and cancel its reorder, rather than teleporting it to the slot.
        if (drag.active != null) drag.finish(false, viewport)
        focusManager.clearFocus(force = true)
        headerFocus.requestFocus()
        announcement = ""
        requestFocus = true
        transitioning = true
        motionVelocity = progress.velocity
        motionImmediate = false
        motionVersion++
        expanded = !expanded
    }

    fun move(source: String, target: String): Boolean {
        val fromCourse = source.startsWith("course:")
        val old = if (fromCourse) courseKeys else bookKeys
        val next = moveHomeCard(old, source, target)
        if (next == old) return false
        if (fromCourse) courseOrder = ArrayList(next) else bookOrder = ArrayList(next)
        announcement = "${cards[source]?.title.orEmpty()}已移至第 ${next.indexOf(source) + 1} 位"
        return true
    }

    LaunchedEffect(courseKeys, bookKeys, contentState.coursesLoading, contentState.libraryLoading, contentState.libraryError) {
        drag.retain(cards.keys)
        (particleAnchors.keys - cards.keys).forEach(particleAnchors::remove)
        if (!contentState.coursesLoading && courseOrder != courseKeys) courseOrder = ArrayList(courseKeys)
        if (!contentState.libraryLoading && contentState.libraryError == null && bookOrder != bookKeys) bookOrder = ArrayList(bookKeys)
    }
    LaunchedEffect(motionVersion, active) {
        if (!transitioning || !active) return@LaunchedEffect
        val version = motionVersion
        val target = if (expanded) 1f else 0f
        val path = checkNotNull(scrollPath)
        scroll.scroll(MutatePriority.PreventUserInput) {
            if (motionImmediate) {
                progress.snapTo(target)
                scrollBy(-scroll.value.toFloat())
            } else {
                progress.homeSpringTo(target, .86f, 520f, motionVelocity) { amount ->
                    scrollBy((path.offset(amount) - scroll.value).toFloat())
                }
            }
            // The expanded range is published by measure. Flush the last scroll after that frame.
            withFrameNanos { }
            scrollBy((path.offset(target) - scroll.value).toFloat())
        }
        if (version != motionVersion) return@LaunchedEffect
        transitioning = false
        scrollPath = null
        frozenState = null
        announcement = if (expanded) "学习卡片已展开" else "学习卡片已收纳"
    }
    LaunchedEffect(listBounds.size, density.density, density.fontScale, dockBottomInset) {
        if (listBounds.isEmpty) return@LaunchedEffect
        val next = HomeDeckGeometry(listBounds.size, density.density, density.fontScale, dockBottomInset)
        val previous = layoutSnapshot
        layoutSnapshot = next
        if (previous != null && previous != next && transitioning) {
            // A resized viewport has different endpoints. End the old path before exposing input.
            scrollPath = HomeDeckScrollPath(
                measurements.compactHeight, measurements.expandedHeight,
                with(density) { (28.dp + dockBottomInset).roundToPx() }, listBounds.height.roundToInt(), 0, 0,
            )
            motionImmediate = true
            motionVelocity = 0f
            motionVersion++
            drag.reset()
        }
    }
    LaunchedEffect(expanded, transitioning, requestFocus, cards.isNotEmpty()) {
        if (!requestFocus || transitioning) return@LaunchedEffect
        val version = motionVersion
        if (cards.isEmpty()) {
            requestFocus = false
            return@LaunchedEffect
        }
        withFrameNanos { }
        if (version != motionVersion || transitioning) return@LaunchedEffect
        if (expanded) {
            if (scroll.value == 0) groupFocus.requestFocus() else headerFocus.requestFocus()
        } else {
            val stack = stackBounds?.translate(Offset(0f, -scroll.value.toFloat()))
            if (stack != null && stack.top >= viewport.top && stack.bottom <= viewport.bottom) deckFocus.requestFocus()
            else headerFocus.requestFocus()
        }
        requestFocus = false
    }
    LaunchedEffect(activeKey, viewport, active) {
        while (active && expanded && !transitioning && drag.active != null) {
            withFrameNanos { }
            val lift = drag.active ?: break
            if (lift.pointer.x !in viewport.left..viewport.right) continue
            val fromTop = lift.pointer.y - viewport.top
            val fromBottom = viewport.bottom - lift.pointer.y
            val delta = when {
                fromTop in 0f..edge -> -(edge - fromTop) * .22f
                fromBottom in 0f..edge -> (edge - fromBottom) * .22f
                else -> 0f
            }
            if (delta != 0f) scroll.scrollBy(delta)
            drag.refreshTarget(viewport)
        }
    }
    BoxWithConstraints(
        modifier.fillMaxSize().background(UiRoles.surface(UiSurfaceRole.Page))
            .onGloballyPositioned {
                val next = it.fullBoundsInRoot()
                if (rootBounds != Rect.Zero && rootBounds.size != next.size) drag.reset()
                rootBounds = next
            }.testTag("knowledge-home"),
    ) {
        val width = minOf(maxWidth, 920.dp)
        val singleColumn = width < 360.dp || density.fontScale >= 1.3f
        val margin = if (width < 360.dp) 16.dp else 20.dp
        val grid: @Composable (List<String>) -> Unit = { order ->
            HomeDeckGrid(
                entries = order.mapNotNull(cards::get),
                columns = if (singleColumn) 1 else 2,
                interactive = expandedInteractive,
                drag = drag,
                stackBounds = { stackBounds?.translate(Offset(0f, -scroll.value.toFloat())) },
                viewport = viewport,
                progress = { progress.value },
                scrollOffset = { scroll.value },
                scrollPath = { scrollPath },
                motionOrder = motionOrder,
                onStableBounds = { key, rect -> if (particleAnchors[key] != rect) particleAnchors[key] = rect },
                onFinish = { commit -> drag.finish(commit, viewport)?.let { move(it.first, it.second) } },
                onMove = ::move,
            )
        }
        val courseStatus: @Composable (Boolean) -> Unit = { interactive ->
            HomeCourseStatus(contentState, courses.isNotEmpty(), onReloadCourses, onOpenCourseLibrary, interactive)
        }
        val libraryStatus: @Composable (Boolean) -> Unit = { interactive ->
            HomeLibraryStatus(contentState, books.isNotEmpty(), onReloadLibrary, onOpenImportedLibrary, interactive)
        }

        val headingHeight = if (density.fontScale >= 1.3f) 96.dp else 60.dp
        val backdrop = rememberUiBackdrop()
        Box(Modifier.align(Alignment.TopCenter).widthIn(max = 920.dp).fillMaxSize()) {
            UiGlassBackdrop(backdrop, Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize().onGloballyPositioned { listBounds = it.fullBoundsInRoot() }
                        .homeDeckTransitionInput(transitioning)
                        .verticalScroll(scroll, enabled = active && !transitioning).testTag("knowledge-home-list"),
                ) {
                    HomeDeckTransitionLayout(
                        progress = { progress.value },
                        measurements = measurements,
                        expandedOnTop = expanded || transitioning,
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = margin, end = margin, top = headingHeight + 4.dp, bottom = 20.dp + dockBottomInset)
                            .testTag("home-deck-transition").semantics {
                                stateDescription = when {
                                    transitioning && expanded -> "正在展开"
                                    transitioning -> "正在收纳"
                                    expanded -> "已展开"
                                    else -> "已收纳"
                                }
                            },
                    top = reviewHeader,
                    compact = {
                        val compactMotion = Modifier.graphicsLayer {
                            val path = scrollPath
                            translationY = if (path == null) 0f else (scroll.value - path.compactOffset).toFloat()
                        }
                        Column(
                            Modifier.fillMaxWidth().homeDeckInput(compactInteractive).graphicsLayer {
                                alpha = 1f - homeDeckFade(progress.value, .12f, .58f)
                            },
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (cards.isNotEmpty()) Box(Modifier.fillMaxWidth().onGloballyPositioned {
                                val bounds = it.fullBoundsInRoot()
                                val layers = (cards.size - 1).coerceIn(0, 2)
                                stackBounds = Rect(
                                    bounds.topLeft + Offset(0f, scroll.value.toFloat()),
                                    Size(bounds.width - with(density) { (4 * layers).dp.toPx() },
                                        bounds.height - with(density) { (8 * layers).dp.toPx() }),
                                )
                            }) {
                                Box(compactMotion) {
                                    HomeStackCard(
                                        courses.size, books.size, ::toggle,
                                        Modifier.focusRequester(deckFocus).testTag("home-deck-expand")
                                            .semantics { stateDescription = "已收纳" },
                                        interactive = compactInteractive,
                                        showAtmosphere = false,
                                    )
                                }
                            }
                            if (singleColumn) Column(compactMotion, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                HomeDeckShortcut("内置课程", onOpenCourseLibrary,
                                    Modifier.fillMaxWidth().testTag("home-course-library"), compactInteractive)
                                HomeDeckShortcut("个人书库", onOpenImportedLibrary,
                                    Modifier.fillMaxWidth().testTag("home-imported-library"), compactInteractive)
                            } else Row(compactMotion, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HomeDeckShortcut("内置课程", onOpenCourseLibrary,
                                    Modifier.weight(1f).testTag("home-course-library"), compactInteractive)
                                HomeDeckShortcut("个人书库", onOpenImportedLibrary,
                                    Modifier.weight(1f).testTag("home-imported-library"), compactInteractive)
                            }
                            Column(compactMotion, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                courseStatus(compactInteractive)
                                libraryStatus(compactInteractive)
                            }
                        }
                    },
                    expanded = {
                        val chrome = Modifier.graphicsLayer {
                            alpha = homeDeckFade(progress.value, .34f, .92f)
                        }
                        val course: @Composable () -> Unit = {
                            HomeDeckGroup(
                                "内置课程", contentState.catalog?.let { "${it.books.size} 本教材 · ${it.chapterCount} 章" },
                                "home-courses-section", "home-course-library", onOpenCourseLibrary,
                                if (courses.isNotEmpty()) groupFocus else null,
                                expandedInteractive, chrome,
                            ) {
                                Box(chrome) { courseStatus(expandedInteractive) }
                                grid(courseKeys)
                            }
                        }
                        val personal: @Composable () -> Unit = {
                            HomeDeckGroup(
                                "个人书库", contentState.importedBookCount.takeIf { it > 0 }?.let { "$it 本图书" },
                                "home-personal-library-section", "home-imported-library", onOpenImportedLibrary,
                                if (courses.isEmpty() && books.isNotEmpty()) groupFocus else null,
                                expandedInteractive, chrome,
                            ) {
                                Box(chrome) { libraryStatus(expandedInteractive) }
                                grid(bookKeys)
                                if (books.isNotEmpty()) UiAction(
                                    label = "导入图书", onClick = { if (expandedInteractive) onOpenImportedLibrary() },
                                    enabled = expandedInteractive,
                                    keepEnabledStyle = true,
                                    modifier = chrome.then(if (expandedInteractive) Modifier.testTag("home-import-book") else Modifier),
                                    actionId = "home-import-book",
                                )
                            }
                        }
                        if (!singleColumn && width >= 840.dp) {
                            Row(Modifier.fillMaxWidth().homeDeckInput(expandedInteractive), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                Box(Modifier.weight(1f)) { course() }
                                Box(Modifier.weight(1f)) { personal() }
                            }
                        } else Column(
                            Modifier.fillMaxWidth().homeDeckInput(expandedInteractive), verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            course()
                            personal()
                        }
                    },
                    tail = {
                        HomeBookshelf(
                            entries = shelfBooks,
                            totalCount = shelfCount,
                            enabled = active && !transitioning,
                            onOpen = { key -> shelfOpenActions[key]?.takeIf(HomeEntry::canOpen)?.open?.invoke() },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        )
                    },
                )
            }
        }
        if (!rootBounds.isEmpty && !viewport.isEmpty) {
            val scrollY = scroll.value
            fun local(rect: Rect, offset: Int): Rect =
                rect.translate(Offset(-rootBounds.left, -rootBounds.top - offset))
            val stack = stackBounds?.let { local(it, scrollPath?.compactOffset ?: scrollY) }
            if (stack != null && !stack.isEmpty) {
                val targets = motionOrder.mapNotNull { key ->
                    particleAnchors[key]?.let { StudyCardParticleTarget(key, local(it, scrollPath?.expandedOffset ?: scrollY)) }
                }
                val clippedViewport = viewport.translate(-rootBounds.topLeft)
                val edges = listOf(
                    Rect(0f, 0f, rootBounds.width, clippedViewport.top.coerceIn(0f, rootBounds.height)),
                    Rect(0f, clippedViewport.bottom.coerceIn(0f, rootBounds.height), rootBounds.width, rootBounds.height),
                )
                StudyCardParticleLayer(
                    modifier = Modifier.matchParentSize(),
                    layout = StudyCardParticleLayout(stack, targets, edges, layoutSnapshot.hashCode().toLong()),
                    progress = { progress.value },
                    active = particleMotionActive &&
                        (stack.overlaps(clippedViewport) || targets.any { it.bounds.overlaps(clippedViewport) }),
                    reducedMotion = reducedMotion,
                )
            }
        }
        if (announcement.isNotEmpty()) {
            Box(Modifier.size(1.dp).semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            })
        }
            HomeDeckLiftedCard(drag, cards, viewport, rootBounds,
                { stackBounds?.translate(Offset(0f, -scroll.value.toFloat())) }, motionOrder,
                { progress.value }, { scroll.value }, { scrollPath })
            HomeDeckHeading(
                margin = margin,
                stacked = density.fontScale >= 1.3f,
                expanded = expanded,
                transitioning = transitioning,
                focus = headerFocus,
                backdrop = backdrop,
                onToggle = ::toggle,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun HomeDeckLiftedCard(
    drag: HomeDeckDrag,
    cards: Map<String, HomeEntry>,
    viewport: Rect,
    rootBounds: Rect,
    stackBounds: () -> Rect?,
    motionOrder: List<String>,
    progress: () -> Float,
    scrollOffset: () -> Int,
    scrollPath: () -> HomeDeckScrollPath?,
) {
    val lift = drag.active
    val landing = drag.landing
    val entry = (lift?.key ?: landing?.key)?.let(cards::get) ?: return
    val origin = lift?.source ?: landing?.from ?: return
    val source = entry.key.substringBefore(':')
    val hasGrip = cards.keys.count { it.substringBefore(':') == source } > 1
    val settle = remember(landing) { Animatable(0f) }
    val landingScroll = remember(landing) { scrollOffset() }
    val density = LocalDensity.current
    val index = motionOrder.indexOf(entry.key).coerceAtLeast(0)
    val layer = index.coerceAtMost(2)
    val layerOffset = with(density) { Offset((4 * layer).dp.toPx(), (7 * layer).dp.toPx()) }
    val bounds = {
        if (lift != null) liftedCardBounds(lift, viewport) else {
            val transition = checkNotNull(landing)
            val destination = drag.position(transition.key) ?: transition.to
            val from = transition.from.translate(Offset(0f, (landingScroll - scrollOffset()).toFloat()))
            homeDeckLerpRect(from, destination, settle.value)
        }
    }
    val pose = {
        homeDeckCardPose(bounds(), stackBounds(), progress(), index, scrollOffset(), scrollPath(), layerOffset)
    }
    LaunchedEffect(landing) {
        if (landing != null) {
            settle.homeSpringTo(1f, .74f, 550f)
            drag.complete(landing)
        }
    }
    entry.render(
        interactive = false,
        modifier = Modifier.zIndex(1f).offset {
            val offset = bounds().center - Offset(origin.width / 2f, origin.height / 2f) - rootBounds.topLeft
            IntOffset(offset.x.roundToInt(), offset.y.roundToInt())
        }.requiredSize(with(density) { origin.width.toDp() }, with(density) { origin.height.toDp() })
            .graphicsLayer {
                val base = bounds()
                val motion = pose()
                val liftScale = if (lift != null) 1.025f else 1f + .025f * (1f - settle.value.coerceIn(0f, 1f))
                translationX = motion.bounds.center.x - base.center.x
                translationY = motion.bounds.center.y - base.center.y
                rotationZ = if (lift != null) (lift.travel.x / 95f).coerceIn(-2f, 2f) else 0f
                scaleX = motion.bounds.width / origin.width * liftScale
                scaleY = motion.bounds.height / origin.height * liftScale
                alpha = motion.alpha
                shadowElevation = 12.dp.toPx()
                shape = RoundedCornerShape(18.dp)
            }.clearAndSetSemantics { },
        handle = if (hasGrip) { { Spacer(Modifier.size(48.dp)) } } else null,
        contentAlpha = { pose().contentAlpha },
    )
}

@Composable
private fun HomeDeckHeading(
    margin: Dp, stacked: Boolean, expanded: Boolean, transitioning: Boolean, focus: FocusRequester,
    backdrop: UiBackdrop? = null,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageBackground = UiRoles.surface(UiSurfaceRole.Page)
    val title: @Composable (Modifier) -> Unit = { titleModifier ->
        Text(
            "知识学习", titleModifier.semantics { heading() },
            color = UiRoles.pageForeground,
            style = UiRoles.text(MaterialTheme.typography.headlineSmall), fontWeight = FontWeight.SemiBold,
        )
    }
    val toggle: @Composable () -> Unit = {
        UiAction(
            label = if (expanded) "一键收纳" else "展开卡片",
            onClick = onToggle,
            backdrop = backdrop,
            modifier = Modifier.focusRequester(focus).testTag(if (expanded) "home-deck-collapse" else "home-deck-header-expand")
                .semantics { stateDescription = when {
                    transitioning && expanded -> "正在展开"
                    transitioning -> "正在收纳"
                    expanded -> "已展开"
                    else -> "已收纳"
                } },
            actionId = "home.deck.toggle",
        )
    }
    val body = modifier.fillMaxWidth()
        .background(
            Brush.verticalGradient(
                listOf(pageBackground.copy(alpha = .92f), pageBackground.copy(alpha = .68f), Color.Transparent),
            ),
        )
        .padding(start = margin, end = margin, top = 12.dp, bottom = 8.dp)
    if (stacked) Column(body) {
        title(Modifier)
        Box(Modifier.align(Alignment.End)) { toggle() }
    } else Row(body, verticalAlignment = Alignment.CenterVertically) {
        title(Modifier.weight(1f))
        toggle()
    }
}

@Composable
private fun HomeDeckGroup(
    title: String, info: String?, tag: String, actionTag: String, onViewAll: () -> Unit,
    focus: FocusRequester?, interactive: Boolean, chrome: Modifier,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(chrome.fillMaxWidth().then(if (interactive) Modifier.testTag(tag) else Modifier), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val focusModifier = if (focus == null || !interactive) Modifier else Modifier.focusRequester(focus).focusable()
                Text(title, style = UiRoles.text(MaterialTheme.typography.titleLarge),
                    color = UiRoles.pageForeground, modifier = focusModifier.semantics { heading() })
                if (info != null) Text(info, color = UiRoles.pageSupporting,
                    style = UiRoles.text(MaterialTheme.typography.bodySmall))
            }
            UiAction(label = "查看全部", onClick = { if (interactive) onViewAll() }, enabled = interactive,
                keepEnabledStyle = true, actionId = actionTag,
                modifier = (if (interactive) Modifier.testTag(actionTag) else Modifier)
                    .semantics { contentDescription = "查看全部$title" })
        }
        content()
    }
}

@Composable
private fun HomeDeckGrid(
    entries: List<HomeEntry>, columns: Int, interactive: Boolean, drag: HomeDeckDrag,
    stackBounds: () -> Rect?, viewport: Rect, progress: () -> Float,
    scrollOffset: () -> Int, scrollPath: () -> HomeDeckScrollPath?, motionOrder: List<String>,
    onStableBounds: (String, Rect) -> Unit,
    onFinish: (Boolean) -> Unit, onMove: (String, String) -> Boolean,
) {
    val highlighted by remember(drag) { derivedStateOf { drag.active?.target } }
    val density = LocalDensity.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.chunked(columns).forEachIndexed { row, group ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                group.forEachIndexed { column, entry ->
                    key(entry.key) {
                        val index = row * columns + column
                        var anchor by remember { mutableStateOf(Rect.Zero) }
                        val motionIndex = motionOrder.indexOf(entry.key).coerceAtLeast(0)
                        val layer = motionIndex.coerceAtMost(2)
                        val layerOffset = with(density) { Offset((4 * layer).dp.toPx(), (7 * layer).dp.toPx()) }
                        val target = highlighted == entry.key
                        val frame = Modifier.weight(1f).fillMaxHeight()
                            .onGloballyPositioned {
                                val bounds = it.fullBoundsInRoot()
                                anchor = bounds.translate(Offset(0f, scrollOffset().toFloat()))
                                drag.place(entry.key, bounds)
                                onStableBounds(entry.key, anchor)
                            }
                            .liftHomeCard(interactive, entry.key,
                                { drag.start(entry.key, it) }, { drag.move(it, viewport) }, onFinish)
                        Box(if (target) frame.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)) else frame) {
                            val pose = {
                                val offset = scrollOffset()
                                homeDeckCardPose(anchor.translate(Offset(0f, -offset.toFloat())), stackBounds(),
                                    progress(), motionIndex, offset, scrollPath(), layerOffset)
                            }
                            entry.render(interactive, Modifier.fillMaxSize().graphicsLayer {
                                val bounds = anchor.translate(Offset(0f, -scrollOffset().toFloat()))
                                val framePose = pose()
                                if (!bounds.isEmpty) {
                                    translationX = framePose.bounds.center.x - bounds.center.x
                                    translationY = framePose.bounds.center.y - bounds.center.y
                                    scaleX = framePose.bounds.width / bounds.width
                                    scaleY = framePose.bounds.height / bounds.height
                                }
                                alpha = when (entry.key) {
                                    drag.active?.key -> framePose.alpha * .25f
                                    drag.landing?.key -> 0f
                                    else -> framePose.alpha
                                }
                            }, contentAlpha = { pose().contentAlpha }, handle = if (entries.size == 1) null else {
                                {
                                    if (interactive) {
                                        HomeDeckGrip(entry.title, entry.gripTag, index > 0, index < entries.lastIndex) { step ->
                                            val next = entries.getOrNull(index + step) ?: return@HomeDeckGrip false
                                            onMove(entry.key, next.key)
                                        }
                                    } else Spacer(Modifier.size(48.dp))
                                }
                            })
                        }
                    }
                }
                repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun HomeEntry.render(
    interactive: Boolean, modifier: Modifier, handle: (@Composable () -> Unit)?, contentAlpha: (() -> Float)? = null,
) = HomeDeckBookCard(title, metadata, detail, action, canOpen, tag, actionTag,
    open, modifier, handle, interactive, contentAlpha)

@Composable
private fun HomeCourseStatus(
    state: KnowledgeHomeUiState, hasContent: Boolean, retry: () -> Unit, open: () -> Unit, interactive: Boolean,
) {
    when {
        state.coursesLoading && state.catalog == null -> HomeLoading("正在载入内置教材…", "home-courses-loading", interactive)
        state.courseError != null -> HomeStatus("教材载入失败", state.courseError, "home-courses-error", "重新载入", retry,
            error = true, interactive = interactive)
        !hasContent && !state.coursesLoading -> HomeStatus("暂无内置教材", null, "home-courses-empty", "查看课程", open,
            interactive = interactive)
    }
}

@Composable
private fun HomeLibraryStatus(
    state: KnowledgeHomeUiState, hasContent: Boolean, retry: () -> Unit, open: () -> Unit, interactive: Boolean,
) {
    when {
        !state.signedIn -> HomeStatus("登录后查看个人书库", null, "home-library-login", "打开个人书库", open,
            interactive = interactive)
        state.libraryLoading -> HomeLoading("正在载入个人书库…", "home-library-loading", interactive)
        state.libraryError != null -> HomeStatus("书库载入失败", state.libraryError, "home-library-error", "重新载入", retry,
            error = true, interactive = interactive)
        !hasContent -> HomeStatus("书库里还没有图书", null, "home-library-empty", "导入图书", open, "home-import-book",
            interactive = interactive)
    }
}

@Composable
private fun HomeStatus(
    title: String, message: String?, tag: String, label: String, action: () -> Unit,
    actionTag: String = "$tag-action", error: Boolean = false, interactive: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().then(if (interactive) Modifier.testTag(tag) else Modifier),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (error) colors.errorContainer else UiRoles.surface(UiSurfaceRole.Card),
            contentColor = if (error) colors.onErrorContainer else UiRoles.foreground,
        ),
        border = BorderStroke(1.dp, if (error) colors.error else colors.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(title, style = UiRoles.text(MaterialTheme.typography.titleMedium))
            if (message != null) Text(message, style = UiRoles.text(MaterialTheme.typography.bodyMedium))
            UiAction(label = label, onClick = { if (interactive) action() }, enabled = interactive,
                keepEnabledStyle = true,
                modifier = if (interactive) Modifier.testTag(actionTag) else Modifier, actionId = actionTag)
        }
    }
}

@Composable
private fun HomeLoading(message: String, tag: String, interactive: Boolean) {
    Column(Modifier.fillMaxWidth().then(if (interactive) Modifier.testTag(tag) else Modifier), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(message, style = UiRoles.text(MaterialTheme.typography.bodySmall))
    }
}

private fun BundledCourseBook.homeEntry(open: (String) -> Unit): HomeEntry {
    val exercises = if (questionCount > 0) " · $questionCount 道练习" else ""
    return HomeEntry(
        key = "course:$key", tag = "home-course-book-$key", gripTag = "home-drag-course-$key", title = title,
        metadata = "${subject.label} · ${chapters.size} 章$exercises",
        detail = chapters.firstOrNull()?.let { "首章 · ${it.title}" },
        action = "查看目录", open = { open(key) },
    )
}

private fun KnowledgeHomeBook.homeEntry(open: (String) -> Unit): HomeEntry = HomeEntry(
    key = "import:$documentId", tag = "home-imported-book-$documentId", gripTag = "home-drag-import-$documentId", title = title,
    metadata = "$formatLabel · $chapterCount $chapterUnit" + author?.let { " · $it" }.orEmpty(),
    detail = when {
        !canOpen -> "在书库中检查或重新解析"
        isCompleted -> "已读完"
        canResume && resumeChapterTitle != null -> "上次读到 · $resumeChapterTitle"
        firstChapterTitle != null -> "从「$firstChapterTitle」开始"
        else -> null
    },
    action = readingAction, actionTag = "home-book-action-$documentId", canOpen = canOpen, open = { open(documentId) },
)

private fun LayoutCoordinates.fullBoundsInRoot(): Rect =
    Rect(positionInRoot(), Size(size.width.toFloat(), size.height.toFloat()))

private fun Modifier.homeDeckInput(interactive: Boolean): Modifier = if (interactive) this else
    clearAndSetSemantics { }.focusProperties { canFocus = false }

private fun Modifier.homeDeckTransitionInput(transitioning: Boolean): Modifier = if (!transitioning) this else
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }

private suspend fun Animatable<Float, *>.homeSpringTo(
    target: Float, damping: Float, stiffness: Float, initialVelocity: Float? = null, onFrame: (Float) -> Unit = {},
) {
    val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    if (scale == 0f) {
        snapTo(target)
        onFrame(target)
    } else animateTo(target, spring(dampingRatio = if (scale < 1f) 1f else damping, stiffness = stiffness),
        initialVelocity = initialVelocity ?: velocity) {
        onFrame(value)
    }
}

