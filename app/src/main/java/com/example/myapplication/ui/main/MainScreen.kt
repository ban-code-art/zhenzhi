package com.example.myapplication.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.appearance.AppearanceDockMotion
import com.example.myapplication.data.appearance.AppearanceNavigation
import com.example.myapplication.data.appearance.AppearanceTabMotion
import com.example.myapplication.ui.ai.AgentSourceUi
import com.example.myapplication.ui.components.AppBottomBar
import com.example.myapplication.ui.components.AppNavigationRail
import com.example.myapplication.ui.components.BottomNavItem
import com.example.myapplication.ui.components.DockBringIntoViewSpec
import com.example.myapplication.ui.components.LocalMainDockBottomInset
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.LocalUiMotionBusy
import com.example.myapplication.ui.components.mainDockHeight
import com.example.myapplication.ui.components.rememberAdaptiveWindowInfo
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.knowledge.KnowledgeScreen
import com.example.myapplication.ui.mine.AboutScreen
import com.example.myapplication.ui.mine.AppearanceScreen
import com.example.myapplication.ui.mine.ChangePasswordScreen
import com.example.myapplication.ui.mine.EditProfileScreen
import com.example.myapplication.ui.mine.EnhancementSettingsViewModel
import com.example.myapplication.ui.mine.FavoritesScreen
import com.example.myapplication.ui.mine.MineDestination
import com.example.myapplication.ui.mine.KnowledgeSettingsScreen
import com.example.myapplication.ui.mine.rememberEnhancementSettingsViewModel
import com.example.myapplication.ui.mine.MineScreen
import com.example.myapplication.ui.mine.NotificationsScreen
import com.example.myapplication.ui.mine.ScoreDetailScreen
import com.example.myapplication.ui.mine.ScoreListScreen
import com.example.myapplication.ui.mine.SettingsScreen
import com.example.myapplication.ui.mine.StudyProgressScreen
import com.example.myapplication.ui.mine.ai.AiConfigScreen
import com.example.myapplication.ui.mine.ai.KnowledgeStatsScreen
import com.example.myapplication.ui.mine.isHostedByNavHost
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.LocalNavigationPageActive
import com.example.myapplication.ui.navigation.UiPageStack
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.encyclopedia.EncyclopediaScreen
import com.example.myapplication.ui.test.TestScreen
import com.example.myapplication.ui.theme.LocalAppearance
import com.example.myapplication.ui.theme.UiIcons
import kotlinx.serialization.json.*
import kotlinx.coroutines.delay

private fun MainTabKey.uiIcon(): ImageVector = when (this) {
    MainTabKey.KNOWLEDGE -> UiIcons.Books
    MainTabKey.TEST -> UiIcons.Quiz
    MainTabKey.ENCYCLOPEDIA -> UiIcons.Encyclopedia
    MainTabKey.MINE -> UiIcons.Person
}

@Composable
fun MainScreen(
    onLogout: () -> Unit,
    onOpenReader: (String) -> Unit,
    onOpenQuizHistory: () -> Unit = {},
    onOpenCourseLibrary: (() -> Unit)? = null,
    onOpenImportedLibrary: (() -> Unit)? = null,
    onOpenFavoriteSource: (AgentSourceUi) -> Unit = {},
    initialReturnPosition: String = "{}",
    onSourceCheckpoint: (String) -> Unit = {},
    onOpenBundledCourse: ((String) -> Unit)? = null,
    onOpenKnowledgeReview: (() -> Unit)? = null,
    onOpenSourcePracticeArchive: (String, String?) -> Unit = { _, _ -> },
    onOpenEncyclopediaBundledChapter: ((bookKey: String, chapterKey: String, quote: String?) -> Unit)? = null,
    onAskEncyclopediaAi: ((com.example.myapplication.data.encyclopedia.EncyclopediaAgentLaunchContext) -> Unit)? = null,
    pendingEncyclopediaJump: String? = null,
    onEncyclopediaJumpConsumed: () -> Unit = {},
) {
    var selectedTabWire by rememberSaveable { mutableStateOf(MainTabKey.KNOWLEDGE.wireKey) }
    val selectedTab = resolveMainTabByKey(selectedTabWire)
    val selectedIndex = selectedTab.ordinal
    var encyclopediaHidesChrome by remember { mutableStateOf(false) }
    val parsedEncJump = remember(pendingEncyclopediaJump) {
        val raw = pendingEncyclopediaJump?.takeIf(String::isNotBlank) ?: return@remember null
        val sep1 = raw.indexOf('|')
        if (sep1 <= 0) return@remember null
        val sep2 = raw.indexOf('|', sep1 + 1)
        if (sep2 <= sep1 + 1) return@remember null
        val nonce = raw.substring(0, sep1)
        val kind = raw.substring(sep1 + 1, sep2)
        val payload = raw.substring(sep2 + 1)
        when (kind) {
            "acupoint" -> com.example.myapplication.ui.encyclopedia.EncyclopediaJumpRequest(
                nonce = nonce,
                pointCode = payload,
            )
            "symptom" -> com.example.myapplication.ui.encyclopedia.EncyclopediaJumpRequest(
                nonce = nonce,
                symptomId = payload,
            )
            "checkpoint" -> com.example.myapplication.ui.encyclopedia.EncyclopediaJumpRequest(
                nonce = nonce,
                checkpointJson = payload,
            )
            else -> null
        }
    }
    var visitedTabs by remember { mutableStateOf(setOf(selectedTab)) }
    LaunchedEffect(Unit) {
        // Warm the common destination after the initial home frame, before a tap has to construct it.
        withFrameNanos { }
        delay(400)
        visitedTabs = visitedTabs + MainTabKey.MINE
    }
    val adaptive = rememberAdaptiveWindowInfo()
    var mineStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var handledLegacyDispatchPosition by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(parsedEncJump?.nonce) {
        if (parsedEncJump != null) {
            mineStack = emptyList()
            visitedTabs = visitedTabs + MainTabKey.ENCYCLOPEDIA
            selectedTabWire = MainTabKey.ENCYCLOPEDIA.wireKey
        }
    }
    val tabStates = rememberSaveableStateHolder()
    val settingsState = rememberSaveableStateHolder()
    val appearance = LocalAppearance.current.preferences
    val direction = LocalLayoutDirection.current
    val navItems = remember {
        MainTabKey.entries.map { BottomNavItem(it.label, it.uiIcon()) }
    }

    // 统一 Mine 入口分发：NavHost 全屏路由（当前只有随堂测记录）转交 onOpenQuizHistory，
    // 其余仍压 Mine 内部栈。这样 MineDestination.QuizHistory 有真实落点，不再是空分支。
    val openMineDestination: (MineDestination) -> Unit = { destination ->
        if (destination.isHostedByNavHost()) onOpenQuizHistory() else mineStack = mineStack + destination.toSavedKey()
    }

    val requestBack = unifiedBackRequest(
        BackState(
            page = if (mineStack.isEmpty()) BackPage.ROOT else BackPage.CHILD,
            imeVisible = isBackImeVisible(),
        ),
    ) { decision ->
        if (decision == BackDecision.PopPage) mineStack = mineStack.dropLast(1)
    }

    LaunchedEffect(initialReturnPosition) {
        val restored = try { Json.parseToJsonElement(initialReturnPosition) as? JsonObject } catch (_: Exception) { null }
            ?: return@LaunchedEffect
        val resolution = resolveMainReturnRestore(restored, ::mineDestinationFromSavedKey)
        resolution.tab?.let { tab ->
            visitedTabs = visitedTabs + tab
            selectedTabWire = tab.wireKey
        }
        resolution.mineStack?.let { mineStack = it }
        val dispatch = resolution.dispatchLegacyDestination
        if (dispatch != null && handledLegacyDispatchPosition != initialReturnPosition) {
            handledLegacyDispatchPosition = initialReturnPosition
            openMineDestination(dispatch)
        }
    }
    fun checkpointFavorite(position: String) {
        val fields = try {
            Json.parseToJsonElement(position).jsonObject.filterKeys { it != "mainTab" }
        } catch (_: Exception) {
            emptyMap()
        }
        onSourceCheckpoint(
            JsonObject(
                fields + mapOf(
                    "mainTabKey" to JsonPrimitive(selectedTab.wireKey),
                    "mineStack" to JsonArray(mineStack.map(::JsonPrimitive)),
                ),
            ).toString(),
        )
    }
    UiPageStack(mineStack) { visualStack ->
        val current = visualStack.lastOrNull()?.let(::mineDestinationFromSavedKey)
        if (current != null) {
            // These two settings pages share one ViewModel and retain the endpoint draft.
            val enhancementSettings = if (current == MineDestination.Settings || current == MineDestination.KnowledgeSettings)
                rememberEnhancementSettingsViewModel() else null
            val page: @Composable () -> Unit = {
                MineSubScreen(
                    destination = current,
                    onBack = requestBack,
                    onNavigate = openMineDestination,
                    onOpenScoreDetail = { id -> mineStack = mineStack + MineDestination.ScoreDetail(id).toSavedKey() },
                    onOpenQuizHistory = onOpenQuizHistory,
                    onLogout = onLogout,
                    enhancementSettings = enhancementSettings,
                    onOpenSource = onOpenFavoriteSource,
                    initialReturnPosition = initialReturnPosition,
                    onSourceCheckpoint = ::checkpointFavorite,
                    onOpenSourcePracticeArchive = onOpenSourcePracticeArchive,
                )
            }
            if (current == MineDestination.Settings) settingsState.SaveableStateProvider("settings", page)
            else page()
        } else {
            val transition = updateTransition(selectedIndex, label = "main tabs")
            CompositionLocalProvider(LocalUiMotionBusy provides (LocalUiMotionBusy.current || transition.isRunning)) {
            MainTabShell(
                items = navItems,
                selectedIndex = selectedIndex,
                onSelect = { index ->
                    val nextTab = MainTabKey.entries[index]
                    visitedTabs = visitedTabs + nextTab
                    selectedTabWire = nextTab.wireKey
                },
                expanded = adaptive.isExpanded,
                navigation = appearance.navigation,
                dockMotion = appearance.dockMotion,
                showBottomChrome = !(selectedTab == MainTabKey.ENCYCLOPEDIA && encyclopediaHidesChrome),
            ) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                  visitedTabs.sortedBy { it.ordinal }.forEach { tabKey -> key(tabKey.wireKey) {
                    val index = tabKey.ordinal
                    val opacity = transition.animateFloat(
                        transitionSpec = { tween(180, easing = FastOutSlowInEasing) }, label = "tab opacity",
                    ) { selected -> if (selected == index) 1f else 0f }
                    val offset = transition.animateFloat(
                        transitionSpec = { tween(220, easing = FastOutSlowInEasing) }, label = "tab offset",
                    ) { selected -> if (selected == index) 0f else if (index < selected) -1f else 1f }
                    tabStates.SaveableStateProvider(tabKey.wireKey) {
                        val outgoing = index != selectedIndex
                        val tabActive = LocalNavigationPageActive.current && !outgoing
                        CompositionLocalProvider(LocalNavigationPageActive provides tabActive) {
                            Box(
                                Modifier.fillMaxSize().zIndex(if (outgoing) 0f else 1f).graphicsLayer {
                                    alpha = opacity.value
                                    translationX = if (appearance.tabMotion == AppearanceTabMotion.Slide)
                                        offset.value * size.width * .14f * if (direction == LayoutDirection.Ltr) 1f else -1f else 0f
                                    clip = true
                                }.then(
                                    if (outgoing) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier,
                                ),
                            ) {
                                when (tabKey) {
                                    MainTabKey.KNOWLEDGE -> KnowledgeScreen(
                                        onOpenReader = onOpenReader,
                                        onOpenCourseLibrary = onOpenCourseLibrary,
                                        onOpenImportedLibrary = onOpenImportedLibrary,
                                        onOpenBundledCourse = onOpenBundledCourse,
                                        onOpenReview = onOpenKnowledgeReview,
                                        homeActive = tabActive,
                                    )
                                    MainTabKey.TEST -> TestScreen(
                                        onOpenSource = onOpenFavoriteSource,
                                        initialReturnPosition = initialReturnPosition,
                                        onSourceCheckpoint = ::checkpointFavorite,
                                    )
                                    MainTabKey.ENCYCLOPEDIA -> EncyclopediaScreen(
                                        onOpenBundledChapter = onOpenEncyclopediaBundledChapter,
                                        onAskEncyclopediaAi = onAskEncyclopediaAi,
                                        jumpRequest = parsedEncJump,
                                        onJumpConsumed = { onEncyclopediaJumpConsumed() },
                                        onHideBottomChromeChange = { hidden ->
                                            encyclopediaHidesChrome = hidden
                                        },
                                    )
                                    MainTabKey.MINE -> MineScreen(onNavigate = openMineDestination)
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
                  } }
                }
            }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainTabShell(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    expanded: Boolean,
    navigation: AppearanceNavigation,
    dockMotion: AppearanceDockMotion,
    showBottomChrome: Boolean = true,
    content: @Composable () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    BoxWithConstraints(
        Modifier.fillMaxSize().background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        val density = LocalDensity.current
        val bottomSystem = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
        val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
        if (expanded) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                if (showBottomChrome) {
                    Box(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        AppNavigationRail(items, selectedIndex, onSelect)
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()) { content() }
            }
        } else if (navigation == AppearanceNavigation.Fixed) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                if (!keyboardVisible && showBottomChrome) {
                    NavigationBar(
                        modifier = Modifier.testTag("ui03-main-fixed"),
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    ) {
                        items.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = selectedIndex == index,
                                onClick = { if (index != selectedIndex) onSelect(index) },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag("main-tab-$index"),
                            )
                        }
                    }
                }
            }
        } else {
            val backdrop = rememberUiBackdrop()
            val dockBottom = maxOf(21.dp, bottomSystem)
            val dockHeight = with(density) { mainDockHeight(maxWidth, items.size) }
            val dockInset = if (keyboardVisible || !showBottomChrome) 0.dp else dockBottom + dockHeight
            val dockInsetPx = with(density) { dockInset.toPx() }
            val baseBringIntoViewSpec = LocalBringIntoViewSpec.current
            val bringIntoViewSpec = remember(baseBringIntoViewSpec, dockInsetPx) {
                if (dockInsetPx > 0f) DockBringIntoViewSpec(baseBringIntoViewSpec, dockInsetPx)
                else baseBringIntoViewSpec
            }
            UiGlassBackdrop(backdrop, Modifier.fillMaxSize()) {
                val contentModifier = Modifier.fillMaxSize().background(background).let { base ->
                    if (keyboardVisible) {
                        base.padding(bottom = bottomSystem)
                            .consumeWindowInsets(PaddingValues(bottom = bottomSystem))
                    } else {
                        base
                    }
                }
                Box(contentModifier) {
                    CompositionLocalProvider(
                        LocalMainDockBottomInset provides dockInset,
                        LocalBringIntoViewSpec provides bringIntoViewSpec,
                    ) {
                        content()
                    }
                }
            }
            if (!keyboardVisible && showBottomChrome) {
                AppBottomBar(
                    items, selectedIndex, onSelect, backdrop, dockHeight,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 21.dp, end = 21.dp, bottom = dockBottom),
                    motion = dockMotion,
                )
            }
        }
    }
}

private fun MineDestination.toSavedKey(): String = when (this) {
    MineDestination.EditProfile -> "edit-profile"
    MineDestination.ChangePassword -> "change-password"
    MineDestination.Scores -> "scores"
    is MineDestination.ScoreDetail -> "score-detail:$testId"
    MineDestination.StudyProgress -> "study-progress"
    MineDestination.QuizHistory -> "quiz-history"
    MineDestination.Favorites -> "favorites"
    MineDestination.WrongQuestions -> "wrong-questions"
    MineDestination.Notifications -> "notifications"
    MineDestination.Settings -> "settings"
    MineDestination.Appearance -> "appearance"
    MineDestination.About -> "about"
    MineDestination.KnowledgeSettings -> "knowledge-settings"
    MineDestination.AiConfig -> "ai-config"
    MineDestination.KnowledgeStats -> "knowledge-stats"
}

private fun mineDestinationFromSavedKey(key: String): MineDestination? = when (key) {
    "edit-profile" -> MineDestination.EditProfile
    "change-password" -> MineDestination.ChangePassword
    "scores" -> MineDestination.Scores
    "study-progress" -> MineDestination.StudyProgress
    "quiz-history" -> MineDestination.QuizHistory
    "favorites" -> MineDestination.Favorites
    "wrong-questions" -> MineDestination.WrongQuestions
    "notifications" -> MineDestination.Notifications
    "settings" -> MineDestination.Settings
    "appearance" -> MineDestination.Appearance
    "about" -> MineDestination.About
    "knowledge-settings" -> MineDestination.KnowledgeSettings
    "ai-config" -> MineDestination.AiConfig
    "knowledge-stats" -> MineDestination.KnowledgeStats
    else -> key.removePrefix("score-detail:").toLongOrNull()?.let(MineDestination::ScoreDetail)
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun MineSubScreen(
    destination: MineDestination,
    onBack: () -> Unit,
    onNavigate: (MineDestination) -> Unit,
    onOpenScoreDetail: (Long) -> Unit,
    onOpenQuizHistory: () -> Unit,
    onLogout: () -> Unit,
    enhancementSettings: EnhancementSettingsViewModel?,
    onOpenSource: (AgentSourceUi) -> Unit,
    initialReturnPosition: String,
    onSourceCheckpoint: (String) -> Unit,
    onOpenSourcePracticeArchive: (String, String?) -> Unit,
) {
    when (destination) {
        MineDestination.EditProfile -> EditProfileScreen(onBack = onBack)
        MineDestination.ChangePassword -> ChangePasswordScreen(onBack = onBack)
        MineDestination.Scores -> ScoreListScreen(onBack = onBack, onOpen = onOpenScoreDetail)
        is MineDestination.ScoreDetail -> ScoreDetailScreen(
            testId = destination.testId,
            onBack = onBack, onOpenSource = onOpenSource,
            initialReturnPosition = initialReturnPosition, onSourceCheckpoint = onSourceCheckpoint,
        )
        MineDestination.StudyProgress -> StudyProgressScreen(onBack = onBack)
        MineDestination.Favorites -> FavoritesScreen(
            title = "收藏题目",
            source = "MANUAL",
            onBack = onBack, onOpenSource = onOpenSource,
            initialReturnPosition = initialReturnPosition, onSourceCheckpoint = onSourceCheckpoint,
            onOpenSourcePracticeArchive = onOpenSourcePracticeArchive,
        )
        MineDestination.WrongQuestions -> FavoritesScreen(
            title = "错题本",
            source = "WRONG",
            onBack = onBack, onOpenSource = onOpenSource,
            initialReturnPosition = initialReturnPosition, onSourceCheckpoint = onSourceCheckpoint
        )
        MineDestination.Notifications -> NotificationsScreen(onBack = onBack)
        MineDestination.Settings -> {
            val enhancement = requireNotNull(enhancementSettings)
            val state by enhancement.state.collectAsState()
            var showDataManagement by rememberSaveable { mutableStateOf(false) }
            UiPageStack(if (showDataManagement) listOf("data-management") else emptyList()) { visualStack ->
                if (visualStack.isNotEmpty()) {
                    com.example.myapplication.ui.mine.datamanagement.AppDataManagementRoute(
                        onBack = { showDataManagement = false },
                    )
                } else {
                    SettingsScreen(onBack, onNavigate, onLogout, state,
                        enhancement::setWebSearchEnabled, enhancement::setAiReviewEnabled,
                        onOpenDataManagement = { showDataManagement = true })
                }
            }
        }
        MineDestination.Appearance -> AppearanceScreen(onBack = onBack)
        MineDestination.About -> AboutScreen(onBack = onBack)
        MineDestination.KnowledgeSettings -> {
            val enhancement = requireNotNull(enhancementSettings)
            val state by enhancement.state.collectAsState()
            KnowledgeSettingsScreen(onBack, state, enhancement::updateSearchEndpointDraft,
                { callback -> enhancement.saveSearchEndpoint(callback) },
                enhancement::discardSearchEndpointDraft, enhancement::setVectorEncoding)
        }
        MineDestination.AiConfig -> AiConfigScreen(onBack = onBack)
        MineDestination.KnowledgeStats -> KnowledgeStatsScreen(onBack = onBack)
        // 随堂测记录由 NavHost 全屏路由承载：真跳一次并弹出内部栈（原来这里是 Unit，
        // 一旦有人压栈进来会停在空白页）。正常入口在 openMineDestination 已被拦截。
        MineDestination.QuizHistory -> QuizHistoryRedirect(
            onOpenQuizHistory = onOpenQuizHistory,
            onBack = onBack
        )
    }
}

/**
 * QuizHistory 的全屏路由转交器：先 navigate 到 `Routes.QUIZ_HISTORY`，再把误入的
 * Mine 内部栈弹回，避免“我的→随堂测记录”出现空白中间页或双返回。
 */
@Composable
private fun QuizHistoryRedirect(
    onOpenQuizHistory: () -> Unit,
    onBack: () -> Unit,
) {
    val open by rememberUpdatedState(onOpenQuizHistory)
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(Unit) {
        open()
        back()
    }
}
