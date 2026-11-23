package com.example.myapplication.ui.encyclopedia

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.encyclopedia.EncyclopediaAgentLaunchContext
import com.example.myapplication.data.encyclopedia.EncyclopediaEntryKind
import com.example.myapplication.data.encyclopedia.EncyclopediaEvidenceExtractor
import com.example.myapplication.data.encyclopedia.EncyclopediaLoadState
import com.example.myapplication.data.encyclopedia.EncyclopediaMutationOutcome
import com.example.myapplication.data.encyclopedia.EncyclopediaPackRepository
import com.example.myapplication.data.encyclopedia.EncyclopediaReviewAddStatus
import com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot
import com.example.myapplication.data.encyclopedia.EncyclopediaSourceLocator
import com.example.myapplication.data.encyclopedia.EncyclopediaSourceRef
import com.example.myapplication.data.encyclopedia.EncyclopediaUserRepository
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.ui.components.LocalMainDockBottomInset
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.UiPane
import com.example.myapplication.ui.components.UiPaneRole
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.encyclopedia.atlas.AtlasFullscreenView
import com.example.myapplication.ui.encyclopedia.atlas.AtlasMainSection
import com.example.myapplication.ui.encyclopedia.atlas.LocalAtlasScreen
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.LocalNavigationPageActive
import com.example.myapplication.ui.navigation.LocalPageBackPreview
import com.example.myapplication.ui.navigation.PageBackPreview
import com.example.myapplication.ui.navigation.UiPageStack
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal data class EncyclopediaJumpRequest(
    val nonce: String,
    val pointCode: String? = null,
    val symptomId: String? = null,
    val detailTab: AcupointDetailTab = AcupointDetailTab.LOCATION,
    val openClues: List<Int> = emptyList(),
    val checkpointJson: String? = null,
)

@Composable
internal fun EncyclopediaSourceDestinationScreen(
    locator: EncyclopediaSourceLocator,
    onBack: () -> Unit,
    onOpenBundledChapter: ((bookKey: String, chapterKey: String, quote: String?) -> Unit)? = null,
) {
    val detailTab = locator.detailTab?.let { raw ->
        AcupointDetailTab.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) || it.wireKey.equals(raw, ignoreCase = true) }
    } ?: AcupointDetailTab.LOCATION
    val initialRequest = remember(locator) {
        EncyclopediaJumpRequest(
            nonce = "source-${locator.kind.wire}-${locator.entryId}-${locator.sectionId}",
            pointCode = locator.entryId.takeIf { locator.kind == EncyclopediaEntryKind.ACUPOINT },
            symptomId = locator.entryId.takeIf { locator.kind == EncyclopediaEntryKind.SYMPTOM },
            detailTab = detailTab,
            openClues = locator.openClues,
        )
    }
    EncyclopediaScreen(
        onOpenBundledChapter = onOpenBundledChapter,
        jumpRequest = initialRequest,
        onRootBackOverride = onBack,
    )
}

/**
 * 经学百科第三栏总入口：负责只读资料包加载/重试、三页签切换、图谱与学习双向调度、液态玻璃反馈、账号收藏/回顾与来源阅读接线。
 */
@Composable
internal fun EncyclopediaScreen(
    packRepository: EncyclopediaPackRepository? = null,
    userRepository: EncyclopediaUserRepository? = null,
    favoriteCodesOverride: Set<String>? = null,
    canToggleFavoriteOverride: Boolean? = null,
    onToggleFavoriteOverride: ((String) -> Unit)? = null,
    inReviewCodesOverride: Set<String>? = null,
    onAddReviewOverride: ((String) -> Unit)? = null,
    onOpenSourceRef: ((EncyclopediaSourceRef) -> Unit)? = null,
    onOpenBundledChapter: ((bookKey: String, chapterKey: String, quote: String?) -> Unit)? = null,
    onAskEncyclopediaAi: ((EncyclopediaAgentLaunchContext) -> Unit)? = null,
    onRootBackOverride: (() -> Unit)? = null,
    jumpRequest: EncyclopediaJumpRequest? = null,
    onJumpConsumed: (String) -> Unit = {},
    onHideBottomChromeChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val appContainer = remember(context) {
        (context.applicationContext as? LearningApplication)?.container
    }
    val resolvedRepo = packRepository ?: appContainer?.encyclopediaPackRepository
        ?: remember(context) { EncyclopediaPackRepository(context.assets) }
    val resolvedUserRepo = userRepository ?: appContainer?.encyclopediaUserRepository

    val sessionIdentity by (appContainer?.sessionManager?.identity
        ?.collectAsState(initial = SessionIdentity(null, 0L))
        ?: remember { mutableStateOf(SessionIdentity(null, 0L)) })
    val favoriteFlow = remember(resolvedUserRepo) { resolvedUserRepo?.observeFavoriteCodes() }
    val reviewFlow = remember(resolvedUserRepo) { resolvedUserRepo?.observeReviewPointCodes() }
    val repoFavorites by (favoriteFlow
        ?.collectAsState(initial = emptySet())
        ?: remember { mutableStateOf(emptySet()) })
    val repoReviewPoints by (reviewFlow
        ?.collectAsState(initial = emptySet())
        ?: remember { mutableStateOf(emptySet()) })

    val favoriteCodes = favoriteCodesOverride ?: repoFavorites
    val inReviewCodes = inReviewCodesOverride ?: repoReviewPoints
    val loggedIn = sessionIdentity.userId != null
    val canToggleFavorite = canToggleFavoriteOverride ?: (resolvedUserRepo != null && loggedIn)

    val loadState by resolvedRepo.state.collectAsState()
    val scope = rememberCoroutineScope()
    var mutationNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(resolvedRepo) {
        resolvedRepo.loadIfNeeded()
    }

    val handleToggleFavorite: (String) -> Unit = onToggleFavoriteOverride ?: { pointCode ->
        val repo = resolvedUserRepo
        val id = sessionIdentity
        val snap = (loadState as? EncyclopediaLoadState.Ready)?.snapshot
        if (repo != null && id.userId != null && snap != null) {
            scope.launch {
                when (val outcome = repo.toggleFavorite(id, pointCode, snap)) {
                    is EncyclopediaMutationOutcome.Success ->
                        mutationNotice = if (outcome.value) "已收藏到当前账号" else "已取消收藏"
                    EncyclopediaMutationOutcome.SessionChanged -> Unit
                    is EncyclopediaMutationOutcome.InvalidInput -> mutationNotice = outcome.message
                }
            }
        }
    }

    val handleAddReview: ((String) -> Unit)? = onAddReviewOverride ?: if (resolvedUserRepo != null && loggedIn) {
        { pointCode ->
            val repo = resolvedUserRepo
            val id = sessionIdentity
            val snap = (loadState as? EncyclopediaLoadState.Ready)?.snapshot
            if (snap != null && id.userId != null) {
                scope.launch {
                    mutationNotice = when (repo.addAcupointToReview(id, pointCode, snap)) {
                        EncyclopediaReviewAddStatus.ADDED -> "已加入知识回顾"
                        EncyclopediaReviewAddStatus.ALREADY_EXISTS -> "已在知识回顾中"
                        EncyclopediaReviewAddStatus.INVALID_POINT -> "未找到有效穴位资料"
                        EncyclopediaReviewAddStatus.SESSION_CHANGED -> null
                    }
                }
            }
        }
    } else null

    val dockInset = LocalMainDockBottomInset.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(UiRoles.surface(UiSurfaceRole.Page))
            .testTag("encyclopedia-screen"),
    ) {
        when (val current = loadState) {
            EncyclopediaLoadState.Idle,
            EncyclopediaLoadState.Loading,
            -> {
                LaunchedEffect(Unit) { onHideBottomChromeChange(false) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = dockInset),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = UiRoles.accent)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "正在载入经学百科离线资料包…",
                        style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                        color = UiRoles.pageForeground,
                    )
                }
            }

            is EncyclopediaLoadState.Error -> {
                LaunchedEffect(Unit) { onHideBottomChromeChange(false) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = dockInset),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = UiRoles.surface(UiSurfaceRole.Card),
                        border = BorderStroke(1.dp, UiRoles.color(UiColorRole.OutlineVariant)),
                        modifier = Modifier.fillMaxWidth().testTag("encyclopedia-error-card"),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "经学百科资料包暂时不可用",
                                style = UiRoles.text(MaterialTheme.typography.titleMedium),
                                fontWeight = FontWeight.Bold,
                                color = UiRoles.foreground,
                            )
                            Text(
                                text = current.message,
                                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                            )
                            UiAction(
                                label = "重试加载",
                                filled = true,
                                onClick = { scope.launch { resolvedRepo.reload() } },
                                actionId = "encyclopedia.pack.retry",
                                modifier = Modifier.testTag("encyclopedia-retry-button"),
                            )
                        }
                    }
                }
            }

            is EncyclopediaLoadState.Ready -> {
                EncyclopediaLoadedContent(
                    snapshot = current.snapshot,
                    favoriteCodes = favoriteCodes,
                    canToggleFavorite = canToggleFavorite,
                    onToggleFavorite = handleToggleFavorite,
                    inReviewCodes = inReviewCodes,
                    onAddReview = handleAddReview,
                    onOpenSourceRef = onOpenSourceRef,
                    onOpenBundledChapter = onOpenBundledChapter,
                    onAskEncyclopediaAi = onAskEncyclopediaAi,
                    onRootBackOverride = onRootBackOverride,
                    jumpRequest = jumpRequest,
                    onJumpConsumed = onJumpConsumed,
                    externalNotice = mutationNotice,
                    onClearExternalNotice = { mutationNotice = null },
                    onHideBottomChromeChange = onHideBottomChromeChange,
                )
            }
        }
    }
}

@Composable
private fun EncyclopediaLoadedContent(
    snapshot: EncyclopediaSnapshot,
    favoriteCodes: Set<String>,
    canToggleFavorite: Boolean,
    onToggleFavorite: (String) -> Unit,
    inReviewCodes: Set<String>,
    onAddReview: ((String) -> Unit)?,
    onOpenSourceRef: ((EncyclopediaSourceRef) -> Unit)?,
    onOpenBundledChapter: ((bookKey: String, chapterKey: String, quote: String?) -> Unit)?,
    onAskEncyclopediaAi: ((EncyclopediaAgentLaunchContext) -> Unit)?,
    onRootBackOverride: (() -> Unit)?,
    jumpRequest: EncyclopediaJumpRequest?,
    onJumpConsumed: (String) -> Unit,
    externalNotice: String?,
    onClearExternalNotice: () -> Unit,
    onHideBottomChromeChange: (Boolean) -> Unit,
) {
    val navigator = remember(snapshot.manifest.contentRevision) { EncyclopediaNavigator() }
    var uiState by remember(navigator) { mutableStateOf(navigator.state) }
    fun applyNav(block: EncyclopediaNavigator.() -> Unit) {
        navigator.block()
        uiState = navigator.state
    }

    LaunchedEffect(jumpRequest?.nonce) {
        val req = jumpRequest ?: return@LaunchedEffect
        when {
            !req.checkpointJson.isNullOrBlank() -> {
                val obj = runCatching { Json.parseToJsonElement(req.checkpointJson) as? JsonObject }.getOrNull()
                if (obj != null) applyNav { restoreFromCheckpointJson(snapshot, obj) }
            }
            !req.pointCode.isNullOrBlank() ->
                applyNav { openPointDetail(snapshot, req.pointCode, req.detailTab) }
            !req.symptomId.isNullOrBlank() -> {
                applyNav {
                    openSymptom(snapshot, req.symptomId)
                    req.openClues.forEach { idx ->
                        val active = state.activePage as? EncyclopediaPageFrame.SymptomDetail
                        if (active != null && active.symptomId == req.symptomId && idx !in active.openClues) {
                            toggleClue(idx)
                        }
                    }
                }
            }
        }
        onJumpConsumed(req.nonce)
    }

    var sourceFallbackNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(externalNotice, sourceFallbackNotice, uiState.notice) {
        if (externalNotice != null || sourceFallbackNotice != null || uiState.notice != null) {
            kotlinx.coroutines.delay(2400)
            onClearExternalNotice()
            sourceFallbackNotice = null
            applyNav { clearNotice() }
        }
    }

    val uriHandler = LocalUriHandler.current
    val handleOpenSource: (EncyclopediaSourceRef) -> Unit = { ref ->
        if (onOpenSourceRef != null) {
            onOpenSourceRef(ref)
        } else {
            when (val resolved = EncyclopediaSourceResolver.resolve(ref)) {
                is EncyclopediaResolvedSource.BundledCourseChapter -> {
                    if (onOpenBundledChapter != null) {
                        onOpenBundledChapter(resolved.bookKey, resolved.chapterKey, resolved.quote)
                    } else {
                        runCatching { uriHandler.openUri(resolved.fallbackUrl) }.onFailure {
                            sourceFallbackNotice = "已保留出处「${resolved.sourceTitle}」，当前无法打开链接"
                        }
                    }
                }
                is EncyclopediaResolvedSource.ExternalUrl -> {
                    runCatching { uriHandler.openUri(resolved.url) }.onFailure {
                        sourceFallbackNotice = "无法打开外部来源「${resolved.title}」：${resolved.url}"
                    }
                }
                is EncyclopediaResolvedSource.FallbackNotice -> {
                    sourceFallbackNotice = resolved.reason
                }
            }
        }
    }

    val hidesDockChrome = !uiState.showsBottomDockControls || uiState.activeSheet != EncyclopediaSheet.NONE
    LaunchedEffect(hidesDockChrome) {
        onHideBottomChromeChange(hidesDockChrome)
    }

    val hasInnerStack = uiState.backStack.isNotEmpty()
    val exitOnBottomOfSourcePreview = onRootBackOverride != null && uiState.backStack.size <= 2
    val backClosesOverlay = uiState.activeSheet != EncyclopediaSheet.NONE ||
        uiState.activeRoot.fullscreen || uiState.activeRoot.peekPointCode != null
    val canHandleBack = backClosesOverlay || hasInnerStack || onRootBackOverride != null
    val localBackPreview = remember(navigator) { PageBackPreview() }
    val outerBackPreview = LocalPageBackPreview.current
    val frameCache = remember { HashMap<String, EncyclopediaPageFrame>() }
    val stackKeys = remember(uiState.backStack) {
        if (uiState.backStack.isEmpty()) {
            listOf("enc-root")
        } else {
            listOf("enc-root") + uiState.backStack.drop(1).mapIndexed { idx, frame ->
                "enc-${idx + 1}-${frameStableKey(frame)}"
            }
        }
    }
    val topKey = stackKeys.last()
    frameCache[topKey] = uiState.activePage

    val animate = rememberEncyclopediaAnimationsEnabled()
    val dockInset = LocalMainDockBottomInset.current

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalPageBackPreview provides when {
                backClosesOverlay -> null
                exitOnBottomOfSourcePreview -> outerBackPreview
                else -> localBackPreview
            },
        ) {
            val requestBack = unifiedBackRequest(
                BackState(
                    page = if (canHandleBack) BackPage.CHILD else BackPage.ROOT,
                    imeVisible = isBackImeVisible(),
                ),
            ) { decision ->
                if (decision == BackDecision.PopPage) {
                    if (!backClosesOverlay && exitOnBottomOfSourcePreview) {
                        onRootBackOverride?.invoke()
                    } else {
                        applyNav { back() }
                    }
                }
            }
            UiPageStack(stackKeys, preview = localBackPreview, followBackPreview = true) { visualStack ->
                val visualTopKey = visualStack.last()
                val displayFrame = if (visualTopKey == topKey) uiState.activePage else frameCache[visualTopKey]
                val visiblePage = LocalNavigationPageActive.current
                when (displayFrame) {
                    null -> Unit
                    is EncyclopediaPageFrame.Root -> EncyclopediaRootView(
                        snapshot = snapshot,
                        frame = displayFrame,
                        searchQuery = uiState.searchQuery,
                        originSymptomName = uiState.atlasOriginSymptom(snapshot),
                        favoriteCodes = favoriteCodes,
                        favoritesEnabled = canToggleFavorite,
                        inReviewCodes = inReviewCodes,
                        onAddReview = onAddReview,
                        onQueryChange = { q -> applyNav { updateSearchQuery(q) } },
                        onSelectSearchResult = { result ->
                            when (result.kind) {
                                EncyclopediaSearchKind.ACUPOINT -> applyNav { openPointDetail(snapshot, result.stableId) }
                                EncyclopediaSearchKind.MERIDIAN -> applyNav { selectMeridian(snapshot, result.stableId) }
                                EncyclopediaSearchKind.SYMPTOM -> applyNav { openSymptom(snapshot, result.stableId) }
                            }
                        },
                        onOpenFavorites = { applyNav { openPointsTabWithFilter(AcupointFilterOption.FAVORITES) } },
                        onSelectTab = { tab -> applyNav { switchTab(tab) } },
                        onOpenMeridianPicker = { applyNav { openSheet(EncyclopediaSheet.MERIDIAN_PICKER) } },
                        onSelectView = { v -> applyNav { selectView(v) } },
                        onCameraChange = { cam -> applyNav { updateBodyCamera(cam) } },
                        onZoomStep = { zoomIn -> applyNav { zoomBody(zoomIn) } },
                        onResetCamera = { applyNav { resetBodyCamera() } },
                        onToggleFullscreen = { applyNav { toggleFullscreen() } },
                        onTapAtlasPoint = { code -> applyNav { tapAtlasPoint(snapshot, code) } },
                        onDismissPeek = { applyNav { dismissPeek() } },
                        onOpenPointDetail = { code, tab -> applyNav { openPointDetail(snapshot, code, tab) } },
                        onOpenRegion = { code -> applyNav { openRegion(snapshot, code) } },
                        onOpenFilteredPoints = { filter -> applyNav { openPointsTabWithFilter(filter) } },
                        onSelectPointFilter = { filter -> applyNav { selectPointFilter(filter) } },
                        onPointScrollChanged = { idx, offset ->
                            if (visiblePage && navigator.state.activePage is EncyclopediaPageFrame.Root) {
                                navigator.updatePointListScroll(idx, offset)
                            }
                        },
                        onSelectSymptomCategory = { cat -> applyNav { selectSymptomCategory(cat) } },
                        onSymptomScrollChanged = { idx, offset ->
                            if (visiblePage && navigator.state.activePage is EncyclopediaPageFrame.Root) {
                                navigator.updateSymptomListScroll(idx, offset)
                            }
                        },
                        onOpenSymptom = { id -> applyNav { openSymptom(snapshot, id) } },
                        onOpenSourceRef = handleOpenSource,
                        onBackToOriginSymptom = requestBack,
                    )

                    is EncyclopediaPageFrame.Region -> LocalAtlasScreen(
                        snapshot = snapshot,
                        frame = displayFrame,
                        onSelectPoint = { code -> applyNav { selectRegionPoint(snapshot, code) } },
                        onCameraChange = { cam -> applyNav { updateRegionCamera(cam) } },
                        onZoomStep = { zoomIn -> applyNav { zoomRegion(zoomIn) } },
                        onResetCamera = { applyNav { resetRegionCamera() } },
                        onBack = requestBack,
                        onOpenDetail = { code -> applyNav { openPointDetail(snapshot, code) } },
                        onOpenStandardSource = handleOpenSource,
                        onScrollChanged = { offset ->
                            if (visiblePage && frameStableKey(navigator.state.activePage) == frameStableKey(displayFrame)) {
                                navigator.updateDetailScroll(offset)
                            }
                        },
                    )

                    is EncyclopediaPageFrame.PointDetail -> AcupointDetailScreen(
                        snapshot = snapshot,
                        frame = displayFrame,
                        isFavorite = displayFrame.pointCode in favoriteCodes,
                        onToggleFavorite = onToggleFavorite,
                        canToggleFavorite = canToggleFavorite,
                        onSelectDetailTab = { tab -> applyNav { selectDetailTab(tab) } },
                        onOpenRegion = { code -> applyNav { openRegion(snapshot, code) } },
                        onLocateOnAtlas = { code -> applyNav { locatePointOnAtlas(snapshot, code) } },
                        onOpenMeridianPoints = { meridianCode ->
                            applyNav {
                                selectMeridian(snapshot, meridianCode)
                                openPointsTabWithFilter(AcupointFilterOption.CURRENT_MERIDIAN)
                            }
                        },
                        onOpenSymptom = { id -> applyNav { openSymptom(snapshot, id) } },
                        onOpenSourceSheet = { applyNav { openSheet(EncyclopediaSheet.SOURCE_REFERENCES) } },
                        onOpenSourceRef = handleOpenSource,
                        onAddReview = onAddReview,
                        inReview = displayFrame.pointCode in inReviewCodes,
                        onAskAi = onAskEncyclopediaAi?.let { ask ->
                            {
                                val checkpoint = navigator.exportCheckpointJson().toString()
                                val ctx = EncyclopediaEvidenceExtractor.extractLaunchContext(
                                    snapshot = snapshot,
                                    kind = EncyclopediaEntryKind.ACUPOINT,
                                    entryId = displayFrame.pointCode,
                                    detailTabWire = displayFrame.detailTab.wireKey,
                                    returnCheckpointJson = checkpoint,
                                )
                                if (ctx != null) ask(ctx)
                            }
                        },
                        onBack = requestBack,
                        onScrollChanged = { offset ->
                            if (visiblePage && frameStableKey(navigator.state.activePage) == frameStableKey(displayFrame)) {
                                navigator.updateDetailScroll(offset)
                            }
                        },
                    )

                    is EncyclopediaPageFrame.SymptomDetail -> SymptomDetailScreen(
                        snapshot = snapshot,
                        frame = displayFrame,
                        backButtonText = if (exitOnBottomOfSourcePreview) "返回 AI" else uiState.backButtonLabel(snapshot),
                        onToggleClue = { idx -> applyNav { toggleClue(idx) } },
                        onOpenPointIndications = { code ->
                            applyNav { openPointDetail(snapshot, code, AcupointDetailTab.INDICATIONS) }
                        },
                        onOpenRelatedMeridian = { code -> applyNav { openMeridianFromSymptom(snapshot, code) } },
                        onOpenSourceSheet = { applyNav { openSheet(EncyclopediaSheet.SOURCE_REFERENCES) } },
                        onAskAi = onAskEncyclopediaAi?.let { ask ->
                            {
                                val checkpoint = navigator.exportCheckpointJson().toString()
                                val ctx = EncyclopediaEvidenceExtractor.extractLaunchContext(
                                    snapshot = snapshot,
                                    kind = EncyclopediaEntryKind.SYMPTOM,
                                    entryId = displayFrame.symptomId,
                                    openClues = displayFrame.openClues.sorted(),
                                    returnCheckpointJson = checkpoint,
                                )
                                if (ctx != null) ask(ctx)
                            }
                        },
                        onBack = requestBack,
                        onScrollChanged = { offset ->
                            if (visiblePage && frameStableKey(navigator.state.activePage) == frameStableKey(displayFrame)) {
                                navigator.updateDetailScroll(offset)
                            }
                        },
                    )
                }
            }
        }

        // 操作提示短条
        val visibleNotice = uiState.notice ?: sourceFallbackNotice ?: externalNotice
        AnimatedVisibility(
            visible = visibleNotice != null,
            enter = if (animate) fadeIn(tween(150)) + slideInVertically { it / 2 } else fadeIn(tween(0)),
            exit = if (animate) fadeOut(tween(150)) else fadeOut(tween(0)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = if (hidesDockChrome) 76.dp else dockInset + 16.dp),
        ) {
            UiPane(
                role = UiPaneRole.Banner,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("encyclopedia-notice"),
            ) {
                Text(
                    text = visibleNotice.orEmpty(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                    color = UiRoles.foreground,
                )
            }
        }

        // 模态弹层：十四经络选择器 / 资料来源清单
        when (uiState.activeSheet) {
            EncyclopediaSheet.NONE -> Unit
            EncyclopediaSheet.MERIDIAN_PICKER -> MeridianPickerSheet(
                snapshot = snapshot,
                selectedMeridianCode = uiState.activeRoot.meridianCode,
                onSelect = { code -> applyNav { selectMeridian(snapshot, code) } },
                onDismiss = { applyNav { dismissSheet() } },
            )
            EncyclopediaSheet.SOURCE_REFERENCES -> {
                val sections = remember(snapshot, uiState.activePage) {
                    buildSourceSections(snapshot, uiState.activePage)
                }
                SourceReferencesSheet(
                    title = "资料来源",
                    sections = sections,
                    onOpenReference = { ref ->
                        applyNav { dismissSheet() }
                        handleOpenSource(ref)
                    },
                    onDismiss = { applyNav { dismissSheet() } },
                )
            }
        }
    }
}

@Composable
private fun EncyclopediaRootView(
    snapshot: EncyclopediaSnapshot,
    frame: EncyclopediaPageFrame.Root,
    searchQuery: String,
    originSymptomName: String?,
    favoriteCodes: Set<String>,
    favoritesEnabled: Boolean,
    inReviewCodes: Set<String>,
    onAddReview: ((String) -> Unit)?,
    onQueryChange: (String) -> Unit,
    onSelectSearchResult: (EncyclopediaSearchResult) -> Unit,
    onOpenFavorites: () -> Unit,
    onSelectTab: (EncyclopediaTab) -> Unit,
    onOpenMeridianPicker: () -> Unit,
    onSelectView: (com.example.myapplication.data.encyclopedia.AtlasViewId) -> Unit,
    onCameraChange: (com.example.myapplication.ui.encyclopedia.atlas.AtlasCameraState) -> Unit,
    onZoomStep: (Boolean) -> Unit,
    onResetCamera: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onTapAtlasPoint: (String) -> Unit,
    onDismissPeek: () -> Unit,
    onOpenPointDetail: (String, AcupointDetailTab) -> Unit,
    onOpenRegion: (String) -> Unit,
    onOpenFilteredPoints: (AcupointFilterOption) -> Unit,
    onSelectPointFilter: (AcupointFilterOption) -> Unit,
    onPointScrollChanged: (Int, Int) -> Unit,
    onSelectSymptomCategory: (String) -> Unit,
    onSymptomScrollChanged: (Int, Int) -> Unit,
    onOpenSymptom: (String) -> Unit,
    onOpenSourceRef: (EncyclopediaSourceRef) -> Unit,
    onBackToOriginSymptom: () -> Unit,
) {
    val backdrop = rememberUiBackdrop()
    val animate = rememberEncyclopediaAnimationsEnabled()
    val dockInset = LocalMainDockBottomInset.current
    val searchResults = remember(snapshot, searchQuery) {
        if (searchQuery.isBlank()) emptyList() else EncyclopediaSearch.search(snapshot, searchQuery, limit = 12)
    }

    val meridianScroll = rememberScrollState()
    val pointScroll = key(frame.pointFilter) {
        rememberLazyListState(frame.pointListIndex, frame.pointListOffset)
    }
    val symptomScroll = key(frame.symptomCategory) {
        rememberLazyListState(frame.symptomListIndex, frame.symptomListOffset)
    }
    var visitedRootTabs by remember { mutableStateOf(setOf(frame.tab)) }
    val direction = LocalLayoutDirection.current

    LaunchedEffect(frame.tab) {
        visitedRootTabs = visitedRootTabs + frame.tab
    }
    val latestPointScrollChanged by rememberUpdatedState(onPointScrollChanged)
    val latestSymptomScrollChanged by rememberUpdatedState(onSymptomScrollChanged)
    val pageActive = LocalNavigationPageActive.current
    LaunchedEffect(pointScroll, frame.tab, pageActive) {
        if (pageActive && frame.tab == EncyclopediaTab.POINT) {
            snapshotFlow { pointScroll.firstVisibleItemIndex to pointScroll.firstVisibleItemScrollOffset }
                .collect { (index, offset) -> latestPointScrollChanged(index, offset) }
        }
    }
    LaunchedEffect(symptomScroll, frame.tab, pageActive) {
        if (pageActive && frame.tab == EncyclopediaTab.SYMPTOM) {
            snapshotFlow { symptomScroll.firstVisibleItemIndex to symptomScroll.firstVisibleItemScrollOffset }
                .collect { (index, offset) -> latestSymptomScrollChanged(index, offset) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (frame.fullscreen && frame.tab == EncyclopediaTab.MERIDIAN) {
            AtlasFullscreenView(
                snapshot = snapshot,
                frame = frame,
                backdrop = null,
                onSelectView = onSelectView,
                onCameraChange = onCameraChange,
                onZoomStep = onZoomStep,
                onResetCamera = onResetCamera,
                onExitFullscreen = onToggleFullscreen,
                onTapPoint = onTapAtlasPoint,
                onOpenPointDetail = { code -> onOpenPointDetail(code, AcupointDetailTab.LOCATION) },
            )
        } else {
            val tabTransition = updateTransition(frame.tab.ordinal, label = "encyclopediaRootTab")
            val activeBottomPad = dockInset + if (frame.peekPointCode != null) 148.dp else 20.dp
            UiGlassBackdrop(backdrop, Modifier.fillMaxSize(), captureEnabled = frame.peekPointCode != null) {
                Column(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EncyclopediaMainHeader(
                            query = searchQuery,
                            onQueryChange = onQueryChange,
                            searchResults = searchResults,
                            onSelectResult = onSelectSearchResult,
                            onOpenFavorites = onOpenFavorites,
                            hasFavorites = favoriteCodes.isNotEmpty(),
                            originSymptomName = originSymptomName,
                            onBackToOriginSymptom = onBackToOriginSymptom,
                            backdrop = null,
                        )

                        EncyclopediaSegmentedControl(
                            options = EncyclopediaTab.entries.map { tab ->
                                SegmentedTabOption(
                                    value = tab,
                                    label = tab.label,
                                    testTag = "encyclopedia-tab-${tab.wireKey}",
                                )
                            },
                            selected = frame.tab,
                            onSelect = onSelectTab,
                            backdrop = null,
                        )
                    }

                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        (visitedRootTabs + frame.tab).sortedBy { it.ordinal }.forEach { tabKey ->
                            key(tabKey) {
                                val index = tabKey.ordinal
                                val opacity by tabTransition.animateFloat(
                                    transitionSpec = {
                                        if (animate) tween(170, easing = FastOutSlowInEasing) else tween(0)
                                    },
                                    label = "encTabOpacity",
                                ) { selectedIdx -> if (selectedIdx == index) 1f else 0f }
                                val slideOffset by tabTransition.animateFloat(
                                    transitionSpec = {
                                        if (animate) tween(200, easing = FastOutSlowInEasing) else tween(0)
                                    },
                                    label = "encTabOffset",
                                ) { selectedIdx ->
                                    if (selectedIdx == index) 0f else if (index < selectedIdx) -1f else 1f
                                }
                                val outgoing = tabKey != frame.tab
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(if (outgoing) 0f else 1f)
                                        .graphicsLayer {
                                            alpha = opacity
                                            translationX = slideOffset * size.width * 0.14f *
                                                if (direction == LayoutDirection.Ltr) 1f else -1f
                                            clip = true
                                        }
                                        .then(
                                            if (outgoing) Modifier.clearAndSetSemantics { hideFromAccessibility() }
                                            else Modifier,
                                        ),
                                ) {
                                    val contentPadding = PaddingValues(
                                        start = 16.dp,
                                        end = 16.dp,
                                        top = 2.dp,
                                        bottom = activeBottomPad,
                                    )
                                    when (tabKey) {
                                        EncyclopediaTab.MERIDIAN -> Column(
                                            Modifier.fillMaxSize().verticalScroll(meridianScroll).padding(contentPadding),
                                        ) {
                                            AtlasMainSection(
                                                snapshot = snapshot,
                                                frame = frame,
                                                backdrop = null,
                                                onOpenMeridianPicker = onOpenMeridianPicker,
                                                onSelectView = onSelectView,
                                                onCameraChange = onCameraChange,
                                                onZoomStep = onZoomStep,
                                                onResetCamera = onResetCamera,
                                                onToggleFullscreen = onToggleFullscreen,
                                                onTapPoint = onTapAtlasPoint,
                                                onOpenPointDetail = { code -> onOpenPointDetail(code, AcupointDetailTab.LOCATION) },
                                                onOpenRegion = onOpenRegion,
                                                onOpenFilteredPoints = onOpenFilteredPoints,
                                                onOpenTextbookUrl = onOpenSourceRef,
                                            )
                                        }
                                        EncyclopediaTab.POINT -> PointIndexSection(
                                            snapshot = snapshot,
                                            frame = frame,
                                            listState = pointScroll,
                                            contentPadding = contentPadding,
                                            favoriteCodes = favoriteCodes,
                                            favoritesEnabled = favoritesEnabled,
                                            backdrop = null,
                                            onSelectFilter = { filter ->
                                                pointScroll.requestScrollToItem(0)
                                                onSelectPointFilter(filter)
                                            },
                                            onOpenPointDetail = { code -> onOpenPointDetail(code, AcupointDetailTab.LOCATION) },
                                        )
                                        EncyclopediaTab.SYMPTOM -> SymptomIndexSection(
                                            snapshot = snapshot,
                                            frame = frame,
                                            listState = symptomScroll,
                                            contentPadding = contentPadding,
                                            backdrop = null,
                                            onSelectCategory = { category ->
                                                symptomScroll.requestScrollToItem(0)
                                                onSelectSymptomCategory(category)
                                            },
                                            onOpenSymptom = onOpenSymptom,
                                        )
                                    }
                                    if (outgoing) {
                                        Box(Modifier.matchParentSize().pointerInput(Unit) {
                                            awaitPointerEventScope {
                                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                                            }
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 浮动快速预览卡：位于 Dock 上方（或全屏状态底端）
        val peekPoint = frame.peekPointCode?.let { snapshot.pointsByCode[it] }
        AnimatedVisibility(
            visible = peekPoint != null,
            enter = if (animate) {
                fadeIn(tween(180)) + slideInVertically(tween(210)) { it / 2 }
            } else fadeIn(tween(0)),
            exit = if (animate) {
                fadeOut(tween(140)) + slideOutVertically(tween(160)) { it / 2 }
            } else fadeOut(tween(0)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .then(if (frame.fullscreen) Modifier.navigationBarsPadding() else Modifier)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = if (frame.fullscreen) 14.dp else dockInset + 10.dp,
                ),
        ) {
            if (peekPoint != null) {
                val peekRegion = snapshot.regionByPointCode[peekPoint.code]
                val peekMeridian = snapshot.meridiansByCode[peekPoint.meridianCode]
                val peekOccluded = snapshot.occludedByPointCode[peekPoint.code]
                val metaLine = listOfNotNull(
                    peekMeridian?.name,
                    peekPoint.special.takeIf(String::isNotBlank),
                    peekOccluded?.regionLabel ?: peekPoint.region.takeIf(String::isNotBlank),
                ).joinToString(" · ")
                EncyclopediaPointPeekOverlay(
                    pointName = peekPoint.name,
                    pointCode = peekPoint.code,
                    metaLine = metaLine,
                    hasRegion = peekRegion != null,
                    regionTitle = peekRegion?.title,
                    inReview = peekPoint.code in inReviewCodes,
                    onClose = onDismissPeek,
                    onOpenDetail = { onOpenPointDetail(peekPoint.code, AcupointDetailTab.LOCATION) },
                    onOpenRegion = { onOpenRegion(peekPoint.code) },
                    onAddReview = onAddReview?.let { add -> { add(peekPoint.code) } },
                    backdrop = backdrop,
                )
            }
        }
    }
}

private fun frameStableKey(frame: EncyclopediaPageFrame): String = when (frame) {
    is EncyclopediaPageFrame.Root -> "root"
    is EncyclopediaPageFrame.Region -> "region-${frame.pointCode}"
    is EncyclopediaPageFrame.PointDetail -> "point-${frame.pointCode}"
    is EncyclopediaPageFrame.SymptomDetail -> "symptom-${frame.symptomId}"
}

private fun buildSourceSections(
    snapshot: EncyclopediaSnapshot,
    page: EncyclopediaPageFrame,
): List<SourceSheetSection> = when (page) {
    is EncyclopediaPageFrame.PointDetail -> {
        val point = snapshot.pointsByCode[page.pointCode] ?: return emptyList()
        val learning = snapshot.learningForPoint(point.code)
        val locationRefs = buildList {
            add(
                EncyclopediaSourceRef(
                    title = "GB/T 12346-2021《经穴名称与定位》§${point.clause}（第 ${point.pdfPage} 页）",
                    url = "${snapshot.learning.references.standardPdfUrl}#page=${point.pdfPage}",
                ),
            )
            if (point.meridianCode == "LU") {
                add(
                    EncyclopediaSourceRef(
                        title = snapshot.manifest.upstreamAttribution.courseware.title,
                        url = snapshot.atlas.lungFront.textbookUrl,
                    ),
                )
            }
        }
        buildList {
            add(SourceSheetSection(heading = "定位与循行依据", references = locationRefs))
            if (learning != null) {
                add(
                    SourceSheetSection(
                        heading = "${learning.displayHeading}出处",
                        references = listOf(learning.source),
                    ),
                )
            }
        }
    }

    is EncyclopediaPageFrame.SymptomDetail -> {
        val symptom = snapshot.symptomsById[page.symptomId] ?: return emptyList()
        val primaryRefs = listOfNotNull(symptom.source, symptom.additionalSource)
        val assocRefs = symptom.links.mapNotNull { link ->
            val point = snapshot.pointsByCode[link.pointCode] ?: return@mapNotNull null
            val pointLearn = snapshot.learningForPoint(point.code) ?: return@mapNotNull null
            val ref = link.resolveSource(pointLearn)
            ref.copy(title = "${point.name} ${point.code}：${ref.title}")
        }
        buildList {
            add(SourceSheetSection(heading = "课程与教材出处", references = primaryRefs))
            if (assocRefs.isNotEmpty()) {
                add(SourceSheetSection(heading = "关联穴位逐穴出处（${assocRefs.size} 项）", references = assocRefs))
            }
        }
    }

    else -> emptyList()
}
