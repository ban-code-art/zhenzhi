package com.example.myapplication.ui.test

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.learning.OrdinaryQuestionAiContext
import com.example.myapplication.data.learning.OrdinaryQuestionAiPhase
import com.example.myapplication.data.local.entity.QuestionEntity
import com.example.myapplication.data.reader.content.ContentPackageQuestionGrading
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.test.BuiltinOrdinaryQuestionBank
import com.example.myapplication.data.test.OrdinaryTestRules
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.ui.ai.AgentSourceUi
import com.example.myapplication.ui.components.LocalMainDockBottomInset
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiConfirmDialog
import com.example.myapplication.ui.components.UiPane
import com.example.myapplication.ui.components.UiPaneRole
import com.example.myapplication.ui.components.learning.LearningAnalysis
import com.example.myapplication.ui.components.learning.LearningSource
import com.example.myapplication.ui.components.learning.QuestionAnalysisPanel
import com.example.myapplication.ui.knowledge.quiz.OrdinaryQuestionAiWindow
import com.example.myapplication.ui.mine.GlassPageBarHeight
import com.example.myapplication.ui.mine.ScoreDetailScreen
import com.example.myapplication.ui.mine.ScoreListScreen
import com.example.myapplication.ui.mine.SubPageScaffold
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackLayer
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.LocalNavigationPageActive
import com.example.myapplication.ui.navigation.LocalPageBackPreview
import com.example.myapplication.ui.navigation.PageBackPreview
import com.example.myapplication.ui.navigation.UiPageStack
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import com.example.myapplication.ui.theme.elevatedForSheet
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Composable
fun TestScreen(
    testId: Long? = null,
    onBack: () -> Unit = {},
    onOpenSource: (AgentSourceUi) -> Unit = {},
    initialReturnPosition: String = "{}",
    onSourceCheckpoint: (String) -> Unit = {},
    initialOrdinal: Int? = null,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as LearningApplication).container
    val identity by container.sessionManager.identity.collectAsState(initial = SessionIdentity(null, 0))
    val userId = identity.userId
    if (userId == null) {
        val onRequestBack = unifiedBackRequest(BackState(page = if (testId == null) BackPage.ROOT else BackPage.CHILD)) {
            if (it == BackDecision.PopPage) onBack()
        }
        if (testId != null) {
            SubPageScaffold(title = "随机测试回顾", onBack = onRequestBack) {
                Text("请先登录", Modifier.padding(24.dp), color = UiRoles.pageForeground)
            }
        } else {
            Text("请先登录", Modifier.padding(24.dp), color = UiRoles.pageForeground)
        }
        return
    }
    val scope = rememberCoroutineScope()
    val factory = remember(identity) { OrdinaryTestViewModel.Factory(context, identity) }
    val model: OrdinaryTestViewModel = viewModel(key = "ordinary:$userId:${identity.epoch}:$testId", factory = factory)
    val state by model.state.collectAsState()
    var leaving by remember(model) { mutableStateOf(false) }
    var sourceRequestGeneration by remember(model) { mutableLongStateOf(0L) }
    val returnedPosition = remember(initialReturnPosition) {
        runCatching { Json.parseToJsonElement(initialReturnPosition).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }
    val returnedTestId = (returnedPosition["ordinaryTestId"] as? JsonPrimitive)?.longOrNull?.takeIf { id ->
        testId == null && id > 0 &&
            (returnedPosition["ordinaryOwnerUserId"] as? JsonPrimitive)?.longOrNull == userId &&
            (returnedPosition["ordinarySessionEpoch"] as? JsonPrimitive)?.longOrNull == identity.epoch
    }
    var pageStack by rememberSaveable(identity, testId) {
        mutableStateOf(
            if (testId == null && returnedTestId != null) listOf("home", "session") else listOf("home"),
        )
    }
    fun invalidateSourceRequests(clearCheckpoint: Boolean) {
        sourceRequestGeneration++
        if (clearCheckpoint) {
            onSourceCheckpoint(JsonObject(returnedPosition.filterKeys { !it.startsWith("ordinary") }).toString())
        }
    }
    val owner = LocalLifecycleOwner.current
    val navActive = LocalNavigationPageActive.current
    LaunchedEffect(model, testId, initialOrdinal) {
        if (testId != null) model.open(testId, initialOrdinal = initialOrdinal)
    }
    LaunchedEffect(model, returnedTestId) {
        if (returnedTestId != null && pageStack.lastOrNull() == "home" && model.state.value.session == null && !model.state.value.loading) {
            pageStack = listOf("home", "session")
            model.open(returnedTestId)
        }
    }
    DisposableEffect(model, owner, navActive, testId, pageStack) {
        val activeOnTest = navActive && (testId != null || pageStack.lastOrNull() == "session")
        model.setForeground(activeOnTest && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.setForeground(activeOnTest)
            if (event == Lifecycle.Event.ON_STOP) model.setForeground(false)
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            sourceRequestGeneration++
            model.setForeground(false)
        }
    }

    val leavePage: () -> Unit = {
        invalidateSourceRequests(clearCheckpoint = testId == null)
        if (testId == null) {
            pageStack = listOf("home")
            model.refreshHomeSummary()
        } else {
            onBack()
        }
    }
    val saveAndLeave: () -> Unit = {
        if (!leaving && !model.state.value.loading) {
            if (model.state.value.session?.record?.status == "IN_PROGRESS") {
                leaving = true
                model.saveAndLeave { saved ->
                    leaving = false
                    if (saved) leavePage()
                }
            } else {
                leavePage()
            }
        }
    }

    @Composable
    fun renderSessionContent(contentState: OrdinaryTestUiState, isSessionPage: Boolean) {
        OrdinaryTestContent(
            state = contentState,
            onOpen = { id ->
                invalidateSourceRequests(clearCheckpoint = id == null)
                if (testId == null) pageStack = listOf("home", "session")
                model.open(id)
            },
            onAnswer = remember(model) { model::updateAnswer },
            onSelectOrdinal = { ordinal ->
                if (ordinal != model.state.value.session?.currentOrdinal) sourceRequestGeneration++
                model.selectOrdinal(ordinal)
            },
            onSave = remember(model) { model::save },
            onSubmit = remember(model) { { model.submit(autoTimeout = false) } },
            onFavorite = remember(model) { model::toggleFavorite },
            onBack = leavePage,
            onOpenSource = { question ->
                val requestedIdentity = identity
                val capturedSession = state.session
                val sourceTicket = ++sourceRequestGeneration
                scope.launch {
                    val current = capturedSession ?: return@launch
                    suspend fun sourceRequestIsCurrent(): Boolean {
                        if (container.ordinaryTestService.currentIdentity() != requestedIdentity) return false
                        val displayed = model.state.value.session ?: return false
                        val stillShowingSession = testId != null || pageStack.lastOrNull() == "session"
                        return sourceTicket == sourceRequestGeneration && stillShowingSession && !leaving &&
                            displayed.record.id == current.record.id && displayed.record.status == "SUBMITTED" &&
                            displayed.currentOrdinal == current.currentOrdinal
                    }
                    try {
                        if (current.record.status != "SUBMITTED" || !sourceRequestIsCurrent()) return@launch
                        val fresh = container.ordinaryTestService.load(userId, current.record.id, requestedIdentity)
                        if (!sourceRequestIsCurrent()) return@launch
                        if (question.id !in fresh.availableSources) {
                            model.open(current.record.id)
                            return@launch
                        }
                        val sourcePosition = question.sourceLocatorJson ?: return@launch
                        val locator = ReaderLocatorCodec.decode(sourcePosition)
                        if (!sourceRequestIsCurrent()) return@launch
                        onOpenSource(
                            AgentSourceUi(
                                current.currentOrdinal + 1,
                                locator.documentId,
                                "原题出处",
                                locator.sectionId,
                                null,
                                question.sourceExcerpt.orEmpty(),
                                sourcePosition,
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (sourceRequestIsCurrent()) model.open(current.record.id)
                    }
                }
            },
            showBack = testId != null,
            initialReturnPosition = initialReturnPosition,
            onSourceCheckpoint = onSourceCheckpoint,
            identityKey = identity,
            requestedTestId = testId ?: returnedTestId?.takeIf { id ->
                isSessionPage && (state.session == null || state.session?.record?.id == id)
            },
            onLeave = saveAndLeave,
            leaving = leaving,
            onBrowseBank = if (testId == null) {
                { pageStack = listOf("home", "bank") }
            } else null,
            onOpenRecords = if (testId == null) {
                { pageStack = listOf("home", "scores") }
            } else null,
            onRemoveWrongFavorite = remember(model) { model::removeWrongFavorite },
            onDismissNotice = remember(model) { model::clearNotice },
        )
    }

    if (testId != null) {
        renderSessionContent(state, isSessionPage = true)
        return
    }

    val localTestPreview = remember(identity) { PageBackPreview() }
    val dockInset = LocalMainDockBottomInset.current
    CompositionLocalProvider(LocalPageBackPreview provides localTestPreview) {
        UiPageStack(pageStack) { visualStack ->
            val currentRoute = visualStack.lastOrNull() ?: "home"
            val childModifier = if (visualStack.size > 1) {
                Modifier.fillMaxSize().graphicsLayer { translationX = size.width * localTestPreview.offset }
            } else {
                Modifier.fillMaxSize()
            }
            Box(childModifier) {
                when {
                    currentRoute == "bank" -> OrdinaryQuestionBankScreen(
                        manualFavorites = state.manualFavorites,
                        onToggleFavorite = remember(model) { model::toggleFavorite },
                        onBack = {
                            pageStack = listOf("home")
                            model.refreshHomeSummary()
                        },
                        identity = identity,
                    )
                    currentRoute == "scores" -> Box(
                        Modifier.fillMaxSize()
                            .background(UiRoles.surface(UiSurfaceRole.Page))
                            .padding(bottom = dockInset),
                    ) {
                        ScoreListScreen(
                            onBack = { pageStack = listOf("home") },
                            onOpen = { pickedId -> pageStack = listOf("home", "scores", "score:$pickedId") },
                        )
                    }
                    currentRoute.startsWith("score:") -> {
                        val scoreId = currentRoute.removePrefix("score:").toLongOrNull() ?: -1L
                        Box(
                            Modifier.fillMaxSize()
                                .background(UiRoles.surface(UiSurfaceRole.Page))
                                .padding(bottom = dockInset),
                        ) {
                            ScoreDetailScreen(
                                testId = scoreId,
                                onBack = { pageStack = listOf("home", "scores") },
                                onOpenSource = onOpenSource,
                                initialReturnPosition = initialReturnPosition,
                                onSourceCheckpoint = onSourceCheckpoint,
                            )
                        }
                    }
                    currentRoute == "session" -> renderSessionContent(state, isSessionPage = true)
                    else -> renderSessionContent(state.copy(session = null, loading = false), isSessionPage = false)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrdinaryTestContent(
    state: OrdinaryTestUiState,
    onOpen: (Long?) -> Unit,
    onAnswer: (String) -> Unit,
    onSelectOrdinal: (Int) -> Unit,
    onSave: () -> Unit,
    onSubmit: () -> Unit,
    onFavorite: (Long) -> Unit,
    onBack: () -> Unit = {},
    onOpenSource: (QuestionEntity) -> Unit = {},
    showBack: Boolean = false,
    initialReturnPosition: String = "{}",
    onSourceCheckpoint: (String) -> Unit = {},
    identityKey: SessionIdentity? = null,
    requestedTestId: Long? = null,
    onLeave: (() -> Unit)? = null,
    leaving: Boolean = false,
    onBrowseBank: (() -> Unit)? = null,
    onOpenRecords: (() -> Unit)? = null,
    onRemoveWrongFavorite: ((Long) -> Unit)? = null,
    onDismissNotice: () -> Unit = {},
) {
    val session = state.session
    val submitted = session?.record?.status == "SUBMITTED"
    val viewingSavedPaper = showBack || requestedTestId != null
    val title = if (submitted || viewingSavedPaper) "随机测试回顾" else "随机测试"
    var confirmSubmit by remember(session?.record?.id, identityKey) { mutableStateOf(false) }
    var showAnswerSheet by remember(session?.record?.id, identityKey) { mutableStateOf(false) }
    var showQuestionAi by remember(session?.record?.id, identityKey) { mutableStateOf(false) }
    var confirmRemoveWrongId by remember(session?.record?.id, identityKey) { mutableStateOf<Long?>(null) }
    val scroll = rememberScrollState()
    val scrollScope = rememberCoroutineScope()
    fun moveToQuestion(ordinal: Int) {
        if (ordinal != session?.currentOrdinal) {
            if (submitted) {
                scrollScope.launch {
                    scroll.scrollTo(0)
                    onSelectOrdinal(ordinal)
                }
            } else {
                onSelectOrdinal(ordinal)
            }
        }
    }

    val topOverlay = when {
        confirmSubmit -> BackLayer.Overlay("ordinary-submit")
        else -> null
    }
    val requestBack = unifiedBackRequest(
        BackState(
            page = if (showBack || requestedTestId != null || (session != null && onLeave != null)) BackPage.CHILD else BackPage.ROOT,
            imeVisible = isBackImeVisible(),
            topLayer = topOverlay,
        ),
    ) { decision ->
        when (decision) {
            is BackDecision.CloseLayer -> {
                confirmSubmit = false
            }
            BackDecision.PopPage -> {
                if (showBack && session?.record?.status != "IN_PROGRESS") onBack() else (onLeave ?: onBack)()
            }
            else -> Unit
        }
    }

    val returned = remember(initialReturnPosition) {
        runCatching { Json.parseToJsonElement(initialReturnPosition).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }
    fun returnedValue(key: String) = returned[key] as? JsonPrimitive
    val canRestore = session != null && returnedValue("ordinaryTestId")?.longOrNull == session.record.id &&
        returnedValue("ordinaryOwnerUserId")?.longOrNull == session.record.userId &&
        (identityKey == null || returnedValue("ordinarySessionEpoch")?.longOrNull == identityKey.epoch)
    val returnedOrdinal = returnedValue("ordinaryOrdinal")?.intOrNull?.takeIf { it in 0..39 }
    var restored by rememberSaveable(session?.record?.id, identityKey, initialReturnPosition) { mutableStateOf(false) }
    LaunchedEffect(session?.record?.id, identityKey, initialReturnPosition) {
        if (canRestore && returnedOrdinal != null && !restored) onSelectOrdinal(returnedOrdinal)
    }
    LaunchedEffect(session?.record?.id, session?.currentOrdinal, identityKey, initialReturnPosition) {
        if (canRestore && returnedOrdinal == session?.currentOrdinal && !restored) {
            scroll.scrollTo(returnedValue("ordinaryScroll")?.intOrNull?.coerceAtLeast(0) ?: 0)
            restored = true
        }
    }

    val dockBottomInset = if (showBack) 0.dp else LocalMainDockBottomInset.current
    val usable = !state.loading && !leaving
    val currentOrdinal = session?.currentOrdinal ?: 0
    val currentQuestion = session?.questions?.getOrNull(currentOrdinal)
    val currentAnswer = session?.answers?.getOrNull(currentOrdinal)
    val isCurrentFavorite = currentQuestion != null && currentQuestion.id in state.manualFavorites

    val activeAiSnapshot = remember(
        showQuestionAi,
        session?.record?.id,
        session?.record?.status,
        currentOrdinal,
        currentQuestion?.id,
        currentAnswer?.userAnswer,
        currentAnswer?.score,
    ) {
        if (!showQuestionAi || session == null || currentQuestion == null) null
        else {
            val phase = if (submitted) OrdinaryQuestionAiPhase.REVIEW else OrdinaryQuestionAiPhase.ANSWERING
            val label = if (submitted) {
                "交卷回顾 · 第 ${currentOrdinal + 1} / 40 题"
            } else {
                "随机测试 · 第 ${currentOrdinal + 1} / 40 题"
            }
            OrdinaryQuestionAiContext.fromQuestion(
                question = currentQuestion,
                phase = phase,
                sourceLabel = label,
                provenance = session.provenanceByQuestionId[currentQuestion.id],
                ordinalNumber = currentOrdinal + 1,
                testId = session.record.id,
                userAnswer = currentAnswer?.userAnswer,
                userScore = currentAnswer?.score,
            )
        }
    }

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxSize()
                .background(UiRoles.surface(UiSurfaceRole.Page))
                .imePadding(),
        ) {
            if (session != null && !showBack) {
                // Dedicated top action bar for Answering & Review tabs matching confirmed design
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UiAction(
                        label = "返回",
                        icon = UiIcons.Back,
                        onClick = { if (submitted) onBack() else (onLeave ?: onBack)() },
                        enabled = usable,
                        horizontalPadding = 10.dp,
                        modifier = Modifier.testTag("ordinary-test-top-back"),
                        actionId = "ordinary.top.back",
                    )
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = UiRoles.text(UiRoles.pageTitle),
                    )
                    if (currentQuestion != null) {
                        UiAction(
                            label = if (isCurrentFavorite) "已收藏" else "收藏",
                            icon = if (isCurrentFavorite) UiIcons.FavoriteFilled else UiIcons.Favorite,
                            onClick = { onFavorite(currentQuestion.id) },
                            enabled = usable,
                            filled = isCurrentFavorite,
                            horizontalPadding = 10.dp,
                            modifier = Modifier.testTag("ordinary-test-favorite"),
                            actionId = "ordinary.top.favorite",
                        )
                        UiAction(
                            label = "问 AI",
                            icon = UiIcons.Ai,
                            onClick = { showQuestionAi = true },
                            enabled = usable,
                            horizontalPadding = 10.dp,
                            modifier = Modifier.testTag("ordinary-test-ask-ai"),
                            actionId = "ordinary.top.askAi",
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            @Composable
            fun StatusNoticeAndErrors() {
                if (state.loading || (requestedTestId != null && session == null && state.error == null)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (session == null) Text(if (requestedTestId == null) "正在准备测试…" else "正在读取成绩…")
                }
                state.notice?.let { notice ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        color = UiRoles.accent.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, UiRoles.accent.copy(alpha = 0.3f)),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(notice, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = UiRoles.foreground)
                            UiAction(label = "知道了", onClick = onDismissNotice, horizontalPadding = 8.dp)
                        }
                    }
                }
                val missingBank = state.error?.contains("QUESTION_BANK_UNAVAILABLE") == true
                state.error?.let { error ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            if (missingBank) "当前内置题库不足，暂时不能组卷。请在可用题库补齐后重试。" else error,
                            Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (session != null && !submitted) {
                val remaining = session.record.remainingSeconds ?: session.record.durationLimitSeconds
                val answeredCount = session.answers.count { hasEffectiveOrdinaryAnswer(it.userAnswer) }
                Column(
                    modifier = Modifier.weight(1f).testTag("ordinary-test-content"),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .padding(
                                start = 20.dp,
                                top = if (showBack) GlassPageBarHeight + 12.dp else 12.dp,
                                end = 20.dp,
                                bottom = 10.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatusNoticeAndErrors()
                        UiPane(
                            role = UiPaneRole.Regular,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                                .border(1.dp, UiRoles.accent.copy(alpha = 0.25f), RoundedCornerShape(18.dp)),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "已答 $answeredCount / 40",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = UiRoles.foreground,
                                    )
                                    Text(
                                        text = "剩余 ${formatOrdinaryCountdown(remaining)}",
                                        modifier = Modifier.testTag("ordinary-test-timer"),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (remaining <= 300) MaterialTheme.colorScheme.error else UiRoles.accent,
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { (answeredCount / 40f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = UiRoles.accent,
                                )
                                if (remaining <= 300) {
                                    Text("剩余时间不足 5 分钟，请注意把握作答节奏。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                                if (leaving) {
                                    Text("正在保存，保存成功后退出…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    AnimatedContent(
                        targetState = currentOrdinal,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = {
                            val direction = if (targetState >= initialState) 1 else -1
                            (slideInHorizontally(tween(200, easing = FastOutSlowInEasing)) { width ->
                                (width * 0.16f * direction).roundToInt()
                            } + fadeIn(tween(180))) togetherWith
                                (slideOutHorizontally(tween(160, easing = FastOutSlowInEasing)) { width ->
                                    (-width * 0.12f * direction).roundToInt()
                                } + fadeOut(tween(130))) using SizeTransform(clip = true)
                        },
                        label = "ordinary-answering-question",
                    ) { targetOrdinal ->
                        val targetQuestion = session.questions.getOrNull(targetOrdinal)
                        val targetAnswer = session.answers.getOrNull(targetOrdinal)
                        val questionScroll = remember(session.record.id, targetOrdinal) { ScrollState(0) }
                        if (targetQuestion != null && targetAnswer != null) {
                            Column(
                                modifier = Modifier.fillMaxSize()
                                    .verticalScroll(questionScroll)
                                    .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                QuestionCardBody(
                                    ordinal = targetOrdinal,
                                    question = targetQuestion,
                                    userAnswer = targetAnswer.userAnswer.orEmpty(),
                                    scoreEarned = targetAnswer.score,
                                    submitted = false,
                                    usable = usable,
                                    provenance = session.provenanceByQuestionId[targetQuestion.id],
                                    onAnswer = onAnswer,
                                )
                            }
                        }
                    }
                }
            } else {
                val clipAboveDock = !showBack && submitted && dockBottomInset > 0.dp
                Column(
                    modifier = Modifier.weight(1f)
                        .let { if (clipAboveDock) it.padding(bottom = dockBottomInset) else it }
                        .verticalScroll(scroll)
                        .padding(
                            start = 20.dp,
                            top = when {
                                showBack -> GlassPageBarHeight + 12.dp
                                session == null -> 20.dp
                                else -> 12.dp
                            },
                            end = 20.dp,
                            bottom = if (clipAboveDock) 20.dp else 24.dp + dockBottomInset,
                        )
                        .testTag("ordinary-test-content"),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (!showBack && session == null) {
                        Text(title, style = UiRoles.text(MaterialTheme.typography.headlineSmall))
                    }
                    StatusNoticeAndErrors()

                    if (session == null) {
                        if (requestedTestId == null) {
                            OrdinaryHomeSection(
                                summary = state.homeSummary,
                                usable = usable,
                                onStart = { onOpen(null) },
                                onBrowseBank = onBrowseBank,
                                onOpenRecords = onOpenRecords,
                            )
                        } else if (state.error != null) {
                            UiAction(
                                label = "重试加载",
                                onClick = { onOpen(requestedTestId) },
                                enabled = usable,
                                filled = true,
                                actionId = "ordinary.result.reload",
                            )
                            if (!showBack && onLeave != null) {
                                UiAction(
                                    label = "返回测试首页",
                                    onClick = onLeave,
                                    enabled = usable,
                                    actionId = "ordinary.returnToStart",
                                )
                            }
                        }
                        return@Column
                    }

                    val question = currentQuestion ?: return@Column
                    if (showBack) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("回顾作答", style = UiRoles.text(MaterialTheme.typography.titleMedium))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                UiAction(
                                    label = if (isCurrentFavorite) "已收藏" else "收藏",
                                    icon = if (isCurrentFavorite) UiIcons.FavoriteFilled else UiIcons.Favorite,
                                    onClick = { onFavorite(question.id) },
                                    enabled = usable,
                                    filled = isCurrentFavorite,
                                    horizontalPadding = 10.dp,
                                    modifier = Modifier.testTag("ordinary-test-favorite"),
                                    actionId = "ordinary.detail.favorite",
                                )
                                UiAction(
                                    label = "问 AI",
                                    icon = UiIcons.Ai,
                                    onClick = { showQuestionAi = true },
                                    enabled = usable,
                                    horizontalPadding = 10.dp,
                                    modifier = Modifier.testTag("ordinary-test-ask-ai"),
                                    actionId = "ordinary.detail.askAi",
                                )
                            }
                        }
                    }

                    ReviewSummaryCard(
                        session = session,
                        currentOrdinal = currentOrdinal,
                        onOpenSheet = { showAnswerSheet = true },
                    )

                    AnimatedContent(
                        targetState = currentOrdinal,
                        transitionSpec = {
                            val direction = if (targetState >= initialState) 1 else -1
                            (slideInHorizontally(tween(200, easing = FastOutSlowInEasing)) { width ->
                                (width * 0.16f * direction).roundToInt()
                            } + fadeIn(tween(180))) togetherWith
                                (slideOutHorizontally(tween(160, easing = FastOutSlowInEasing)) { width ->
                                    (-width * 0.12f * direction).roundToInt()
                                } + fadeOut(tween(130))) using SizeTransform(clip = false)
                        },
                        label = "ordinary-review-question",
                    ) { targetOrdinal ->
                        val targetQuestion = session.questions.getOrNull(targetOrdinal)
                        val targetAnswer = session.answers.getOrNull(targetOrdinal)
                        if (targetQuestion != null && targetAnswer != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                QuestionCardBody(
                                    ordinal = targetOrdinal,
                                    question = targetQuestion,
                                    userAnswer = targetAnswer.userAnswer.orEmpty(),
                                    scoreEarned = targetAnswer.score,
                                    submitted = true,
                                    usable = usable,
                                    provenance = session.provenanceByQuestionId[targetQuestion.id],
                                    onAnswer = onAnswer,
                                )

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
                                ) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "本题得分：${targetAnswer.score} / ${targetQuestion.score} 分",
                                                style = UiRoles.text(MaterialTheme.typography.titleSmall),
                                                color = if (targetAnswer.score == targetQuestion.score) UiRoles.accent else MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            if (targetQuestion.id in state.wrongFavorites && onRemoveWrongFavorite != null) {
                                                UiAction(
                                                    label = "移出错题本",
                                                    onClick = { confirmRemoveWrongId = targetQuestion.id },
                                                    foreground = UiRoles.extended.destructive,
                                                    horizontalPadding = 10.dp,
                                                    modifier = Modifier.testTag("ordinary-review-remove-wrong"),
                                                    actionId = "ordinary.review.removeWrong",
                                                )
                                            }
                                        }
                                        Text(
                                            text = "你的作答：${formatOrdinaryUserAnswer(targetQuestion, targetAnswer.userAnswer)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = UiRoles.foreground,
                                        )
                                        var expanded by rememberSaveable(session.record.id, targetOrdinal, identityKey) {
                                            mutableStateOf(
                                                if (canRestore && targetOrdinal == returnedOrdinal) {
                                                    returnedValue("ordinaryExpanded")?.booleanOrNull ?: true
                                                } else {
                                                    true
                                                },
                                            )
                                        }
                                        val sources = if (!targetQuestion.sourceExcerpt.isNullOrBlank()) {
                                            listOf(
                                                LearningSource(
                                                    targetQuestion.id.toString(),
                                                    "查看来源",
                                                    targetQuestion.sourceExcerpt,
                                                    targetQuestion.id in session.availableSources,
                                                    "来源已失效或没有跳转位置，保留历史摘录",
                                                ),
                                            )
                                        } else {
                                            emptyList()
                                        }
                                        val analysis = LearningAnalysis(
                                            answer = formatOrdinaryOfficialAnswer(targetQuestion),
                                            analysis = targetQuestion.analysis,
                                            knowledgePoint = targetQuestion.knowledgePoint.orEmpty(),
                                            rubric = buildList {
                                                addAll(formatOrdinaryFillSynonyms(targetQuestion))
                                                if (targetQuestion.type == "ESSAY" && ContentPackageQuestionGrading.hasEnvelope(targetQuestion)) {
                                                    addAll(ContentPackageQuestionGrading.displayLines(targetQuestion))
                                                }
                                            },
                                            sources = sources,
                                        )
                                        QuestionAnalysisPanel(
                                            analysis = analysis,
                                            submitted = true,
                                            expanded = expanded,
                                            onExpandedChange = { expanded = it },
                                            isFavorite = false,
                                            onToggleFavorite = null,
                                            onOpenSource = {
                                                onSourceCheckpoint(
                                                    buildJsonObject {
                                                        put("ordinaryTestId", session.record.id)
                                                        put("ordinaryOwnerUserId", session.record.userId)
                                                        identityKey?.let { put("ordinarySessionEpoch", it.epoch) }
                                                        put("ordinaryOrdinal", targetOrdinal)
                                                        put("ordinaryExpanded", expanded)
                                                        put("ordinaryScroll", scroll.value)
                                                    }.toString(),
                                                )
                                                onOpenSource(targetQuestion)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        UiAction(
                            label = "上一题",
                            onClick = { moveToQuestion(currentOrdinal - 1) },
                            enabled = currentOrdinal > 0 && usable,
                            horizontalPadding = 12.dp,
                            actionId = "ordinary.review.previous",
                        )
                        UiAction(
                            label = "答题卡",
                            onClick = { showAnswerSheet = true },
                            enabled = usable,
                            horizontalPadding = 12.dp,
                            modifier = Modifier.testTag("ordinary-test-answer-sheet"),
                            actionId = "ordinary.review.sheet",
                        )
                        UiAction(
                            label = "下一题",
                            onClick = { moveToQuestion(currentOrdinal + 1) },
                            enabled = currentOrdinal < session.questions.lastIndex && usable,
                            horizontalPadding = 12.dp,
                            actionId = "ordinary.review.next",
                        )
                    }
                    if (!showBack && onLeave != null) {
                        UiAction(
                            label = "返回测试首页",
                            onClick = onLeave,
                            modifier = Modifier.fillMaxWidth(),
                            visualModifier = Modifier.fillMaxWidth(),
                            actionId = "ordinary.returnToStart",
                        )
                    }
                }
            }

            if (session != null && !submitted) {
                Surface(
                    color = UiRoles.surface(UiSurfaceRole.Card),
                    shadowElevation = 6.dp,
                ) {
                    Column(Modifier.fillMaxWidth().padding(bottom = dockBottomInset)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UiAction(
                                label = "上一题",
                                onClick = { moveToQuestion(currentOrdinal - 1) },
                                enabled = currentOrdinal > 0 && usable,
                                modifier = Modifier.weight(1f),
                                visualModifier = Modifier.fillMaxWidth(),
                                horizontalPadding = 8.dp,
                                actionId = "ordinary.previous",
                            )
                            val answeredCount = session.answers.count { hasEffectiveOrdinaryAnswer(it.userAnswer) }
                            UiAction(
                                label = "答题卡 $answeredCount/40",
                                onClick = { showAnswerSheet = true },
                                enabled = usable,
                                modifier = Modifier.weight(1.25f).testTag("ordinary-test-answer-sheet"),
                                visualModifier = Modifier.fillMaxWidth(),
                                horizontalPadding = 8.dp,
                                actionId = "ordinary.answerSheet",
                            )
                            if (currentOrdinal < session.questions.lastIndex) {
                                UiAction(
                                    label = "下一题",
                                    onClick = { moveToQuestion(currentOrdinal + 1) },
                                    enabled = usable,
                                    modifier = Modifier.weight(1f),
                                    visualModifier = Modifier.fillMaxWidth(),
                                    horizontalPadding = 8.dp,
                                    actionId = "ordinary.next",
                                )
                            } else {
                                UiAction(
                                    label = "交卷",
                                    onClick = { confirmSubmit = true },
                                    enabled = usable,
                                    filled = true,
                                    modifier = Modifier.weight(1f).testTag("ordinary-test-submit"),
                                    visualModifier = Modifier.fillMaxWidth(),
                                    horizontalPadding = 8.dp,
                                    actionId = "ordinary.submit",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    CompositionLocalProvider(LocalContentColor provides UiRoles.pageForeground) {
        if (showBack) {
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                SubPageScaffold(
                    title = title,
                    onBack = requestBack,
                    glassBack = true,
                    actionId = "ordinary.result.back",
                    content = content,
                )
            }
        } else {
            content()
        }
    }

    if (showAnswerSheet && session != null) {
        val unansweredCount = session.answers.count { !hasEffectiveOrdinaryAnswer(it.userAnswer) }
        OrdinaryAnswerSheetDialog(
            title = if (submitted) "作答总览（40 题）" else "答题卡 · 还有 $unansweredCount 题未答",
            onDismiss = { showAnswerSheet = false },
            actions = {
                if (!submitted) {
                    if (onLeave != null) {
                        UiAction(
                            label = "保存退出",
                            onClick = {
                                showAnswerSheet = false
                                onLeave()
                            },
                            enabled = usable,
                            horizontalPadding = 10.dp,
                            modifier = Modifier.testTag("ordinary-test-leave"),
                            actionId = "ordinary.leave",
                        )
                    }
                    UiAction(
                        label = "交卷",
                        onClick = { confirmSubmit = true },
                        enabled = usable,
                        filled = true,
                        horizontalPadding = 12.dp,
                        modifier = Modifier.testTag("ordinary-test-submit"),
                        actionId = "ordinary.sheet.submit",
                    )
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                session.answers.chunked(5).forEachIndexed { row, rowAnswers ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowAnswers.forEachIndexed { column, item ->
                            val index = row * 5 + column
                            val isCurrent = index == currentOrdinal
                            val isAnswered = hasEffectiveOrdinaryAnswer(item.userAnswer)
                            val questionMax = session.questions.getOrNull(index)?.score ?: 0
                            val badge = when {
                                submitted && item.score == questionMax -> "${index + 1}✓"
                                submitted -> "${index + 1}✕"
                                isAnswered -> "${index + 1}·"
                                else -> "${index + 1}"
                            }
                            UiAction(
                                label = badge,
                                onClick = {
                                    moveToQuestion(index)
                                    showAnswerSheet = false
                                },
                                enabled = usable,
                                filled = isCurrent || (!submitted && isAnswered),
                                horizontalPadding = 4.dp,
                                actionId = "ordinary.card.${index + 1}",
                                modifier = Modifier.weight(1f).testTag("ordinary-sheet-tile-${index + 1}"),
                                visualModifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmSubmit && session != null && !submitted) {
        val unanswered = session.answers.count { !hasEffectiveOrdinaryAnswer(it.userAnswer) }
        UiConfirmDialog(
            title = "确认交卷",
            message = "还有 $unanswered 题未作答。交卷后不能修改答案，未作答按 0 分计；低于满分的题目将进入「错题本」。",
            confirmLabel = "确认交卷",
            dismissLabel = "继续作答",
            onConfirm = {
                confirmSubmit = false
                showAnswerSheet = false
                onSubmit()
            },
            onDismiss = { confirmSubmit = false },
            ownerKey = session.record.id to identityKey,
            canConfirm = usable,
            destructive = false,
            actionId = "ordinary.submit.confirmation",
        )
    }

    confirmRemoveWrongId?.let { targetQuestionId ->
        UiConfirmDialog(
            title = "移出错题本",
            message = "将此题从「错题本」移除？不会删除手动收藏，也不会修改本次及历史测试成绩。",
            confirmLabel = "确认移除",
            dismissLabel = "取消",
            onConfirm = {
                confirmRemoveWrongId = null
                onRemoveWrongFavorite?.invoke(targetQuestionId)
            },
            onDismiss = { confirmRemoveWrongId = null },
            ownerKey = targetQuestionId to identityKey,
            destructive = true,
            actionId = "ordinary.review.removeWrong.confirm",
        )
    }

    OrdinaryQuestionAiWindow(
        visible = activeAiSnapshot != null,
        snapshot = activeAiSnapshot,
        returnLabel = if (submitted) "返回回顾" else "返回作答",
        remainingSeconds = if (!submitted) session?.record?.remainingSeconds ?: session?.record?.durationLimitSeconds else null,
        onDismiss = { showQuestionAi = false },
    )
}

@Composable
private fun OrdinaryHomeSection(
    summary: com.example.myapplication.data.test.OrdinaryHomeSummary?,
    usable: Boolean,
    onStart: () -> Unit,
    onBrowseBank: (() -> Unit)?,
    onOpenRecords: (() -> Unit)?,
) {
    val totalCount = summary?.totalBankCount ?: BuiltinOrdinaryQuestionBank.EXPECTED_TOTAL
    val choiceCount = summary?.countsByType?.get("CHOICE") ?: BuiltinOrdinaryQuestionBank.EXPECTED_CHOICE
    val judgeCount = summary?.countsByType?.get("JUDGE") ?: BuiltinOrdinaryQuestionBank.EXPECTED_JUDGE
    val fillCount = summary?.countsByType?.get("FILL") ?: BuiltinOrdinaryQuestionBank.EXPECTED_FILL
    val essayCount = summary?.countsByType?.get("ESSAY") ?: BuiltinOrdinaryQuestionBank.EXPECTED_ESSAY
    val inProgress = summary?.inProgressTestId != null

    UiPane(
        role = UiPaneRole.Regular,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, UiRoles.accent.copy(alpha = 0.25f), RoundedCornerShape(22.dp)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "40 道随机抽题 · 满分 100 分 · 40 分钟",
                style = UiRoles.text(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.SemiBold,
                color = UiRoles.foreground,
            )
            Text(
                text = "固定配比：20 选择 ×2 分 + 10 判断 ×2 分 + 5 填空 ×4 分 + 5 应用 ×4 分。中途退出自动保留当前题号与作答，交卷后自动归集错题与成绩。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (inProgress && summary != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = UiRoles.accent.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, UiRoles.accent.copy(alpha = 0.25f)),
                ) {
                    Text(
                        text = "有未完成试卷 · 已答 ${summary.inProgressAnsweredCount}/40 题 · 停在第 ${summary.inProgressOrdinal + 1} 题 · 剩余 ${formatOrdinaryCountdown(summary.inProgressRemainingSeconds)}",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("ordinary-home-in-progress"),
                        style = MaterialTheme.typography.bodySmall,
                        color = UiRoles.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            UiAction(
                label = if (inProgress && summary != null) {
                    "继续测试 · 第 ${summary.inProgressOrdinal + 1} 题"
                } else {
                    "开始测试"
                },
                onClick = onStart,
                enabled = usable,
                filled = true,
                modifier = Modifier.fillMaxWidth().testTag("ordinary-test-start"),
                visualModifier = Modifier.fillMaxWidth(),
                actionId = "ordinary.start",
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("内置题库分布", style = UiRoles.text(MaterialTheme.typography.titleSmall), fontWeight = FontWeight.SemiBold)
                Text(
                    text = "共 $totalCount 题",
                    modifier = Modifier.testTag("ordinary-home-bank-total"),
                    style = MaterialTheme.typography.labelLarge,
                    color = UiRoles.accent,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BankCountTile("选择题", choiceCount, "每题2分", Modifier.weight(1f))
                BankCountTile("判断题", judgeCount, "每题2分", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BankCountTile("填空题", fillCount, "每题4分", Modifier.weight(1f))
                BankCountTile("应用题", essayCount, "每题4分", Modifier.weight(1f))
            }
        }
    }

    if (onBrowseBank != null || onOpenRecords != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onBrowseBank != null) {
                UiAction(
                    label = "浏览题库（$totalCount）",
                    icon = UiIcons.Search,
                    onClick = onBrowseBank,
                    enabled = usable,
                    modifier = Modifier.weight(1f).testTag("ordinary-test-browse-bank"),
                    visualModifier = Modifier.fillMaxWidth(),
                    horizontalPadding = 10.dp,
                    actionId = "ordinary.browseBank",
                )
            }
            if (onOpenRecords != null) {
                val countLabel = summary?.submittedCount?.takeIf { it > 0 }?.let { "（$it）" }.orEmpty()
                UiAction(
                    label = "学习记录$countLabel",
                    icon = UiIcons.Records,
                    onClick = onOpenRecords,
                    enabled = usable,
                    modifier = Modifier.weight(1f).testTag("ordinary-test-records"),
                    visualModifier = Modifier.fillMaxWidth(),
                    horizontalPadding = 10.dp,
                    actionId = "ordinary.openRecords",
                )
            }
        }
    }
}

@Composable
private fun BankCountTile(
    title: String,
    count: Int,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = UiRoles.surface(UiSurfaceRole.Block),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "$count 道",
                style = UiRoles.text(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.Bold,
                color = UiRoles.foreground,
            )
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = UiRoles.accent)
        }
    }
}

@Composable
private fun ReviewSummaryCard(
    session: com.example.myapplication.data.test.OrdinaryTestSession,
    currentOrdinal: Int,
    onOpenSheet: () -> Unit,
) {
    val totalScore = session.record.totalScore
    val correctCount = session.answers.indices.count { i ->
        session.answers[i].score == session.questions.getOrNull(i)?.score
    }
    UiPane(
        role = UiPaneRole.Regular,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, UiRoles.accent.copy(alpha = 0.25f), RoundedCornerShape(20.dp)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = totalScore?.let { "得分 $it / 100" } ?: "成绩未记录",
                        modifier = Modifier.testTag("ordinary-review-total-score"),
                        style = UiRoles.text(MaterialTheme.typography.headlineSmall),
                        fontWeight = FontWeight.Bold,
                        color = UiRoles.foreground,
                    )
                    Text(
                        text = "满分题数：$correctCount / 40 · 当前第 ${currentOrdinal + 1} 题",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                UiAction(
                    label = "答题卡",
                    onClick = onOpenSheet,
                    horizontalPadding = 12.dp,
                    modifier = Modifier.testTag("ordinary-test-answer-sheet"),
                    actionId = "ordinary.review.openSheet",
                )
            }
            Text(
                text = "选择 ${session.record.choiceScore ?: "—"} · 判断 ${session.record.judgeScore ?: "—"} · 填空 ${session.record.fillScore ?: "—"} · 应用 ${session.record.essayScore ?: "—"}",
                style = MaterialTheme.typography.bodyMedium,
                color = UiRoles.foreground,
            )
            if (!session.originalSnapshot) {
                Text("此历史卷缺少独立快照，题面来自当前题库。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun QuestionCardBody(
    ordinal: Int,
    question: QuestionEntity,
    userAnswer: String,
    scoreEarned: Int,
    submitted: Boolean,
    usable: Boolean,
    provenance: com.example.myapplication.data.test.OrdinaryQuestionProvenance?,
    onAnswer: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "第 ${ordinal + 1}/40 题 · ${ordinaryTypeLabel(question.type)} ${question.score} 分",
                    style = UiRoles.text(MaterialTheme.typography.labelLarge),
                    color = UiRoles.accent,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = ordinaryOriginSummary(question.id, provenance),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ordinaryReviewBadge(provenance)?.let { badge ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = badge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Text(
                text = question.stem,
                style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                color = UiRoles.foreground,
            )

            when (question.type) {
                "CHOICE" -> {
                    val options = remember(question.id, question.options) {
                        OrdinaryTestRules.choiceOptions(question.options)
                    }
                    val selectedKey = OrdinaryTestRules.choiceAnswer(userAnswer)
                    val officialKey = if (submitted) OrdinaryTestRules.choiceAnswer(question.answer) else null
                    if (options.isEmpty()) {
                        Text("该题缺少选项文本。", color = MaterialTheme.colorScheme.error)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEach { (optionKey, text) ->
                                key(optionKey) {
                                    val isSelected = selectedKey == optionKey
                                    val isOfficial = submitted && officialKey == optionKey
                                    val isWrongPick = submitted && isSelected && officialKey != optionKey
                                    if (!submitted) {
                                        OrdinaryChoiceOptionRow(
                                            label = "$optionKey. $text",
                                            selected = isSelected,
                                            onSelect = { onAnswer(optionKey) },
                                            enabled = usable,
                                        )
                                    } else {
                                        ReviewOptionRow(
                                            label = "$optionKey. $text",
                                            isOfficial = isOfficial,
                                            isSelected = isSelected,
                                            isWrongPick = isWrongPick,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                "JUDGE" -> {
                    val userBool = when (OrdinaryTestRules.normalize(userAnswer).trim('"')) {
                        "true", "对", "正确", "是", "1" -> "对"
                        "false", "错", "错误", "否", "0" -> "错"
                        else -> ""
                    }
                    val officialBool = if (submitted) {
                        when (OrdinaryTestRules.normalize(question.answer).trim('"')) {
                            "true", "对", "正确", "是", "1" -> "对"
                            "false", "错", "错误", "否", "0" -> "错"
                            else -> ""
                        }
                    } else ""
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("对" to "正确", "错" to "错误").forEach { (value, display) ->
                            key(value) {
                                val isSelected = userBool == value
                                if (!submitted) {
                                    OrdinaryChoiceOptionRow(
                                        label = "$value · $display",
                                        selected = isSelected,
                                        onSelect = { onAnswer(value) },
                                        enabled = usable,
                                    )
                                } else {
                                    ReviewOptionRow(
                                        label = "$value · $display",
                                        isOfficial = officialBool == value,
                                        isSelected = isSelected,
                                        isWrongPick = isSelected && officialBool != value,
                                    )
                                }
                            }
                        }
                    }
                }

                "FILL" -> {
                    val blankCount = ordinaryFillBlankCount(question)
                    val currentBlanks = decodeOrdinaryFillBlanks(userAnswer, blankCount)
                    if (!submitted) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (blankCount == 1) "请填写答案：" else "请依次填写以下 $blankCount 个空：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            repeat(blankCount) { idx ->
                                OutlinedTextField(
                                    value = currentBlanks[idx],
                                    onValueChange = { updated ->
                                        val next = currentBlanks.toMutableList()
                                        next[idx] = updated
                                        onAnswer(encodeOrdinaryFillBlanks(next))
                                    },
                                    enabled = usable,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("ordinary-fill-blank-${idx + 1}"),
                                    label = { Text(if (blankCount == 1) "我的填空" else "第 ${idx + 1} 空") },
                                )
                            }
                        }
                    } else {
                        Text(
                            text = if (scoreEarned == question.score) "填空正确" else "可核对下方标准答案与同义词",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    if (!submitted) {
                        OutlinedTextField(
                            value = userAnswer,
                            onValueChange = onAnswer,
                            enabled = usable,
                            modifier = Modifier.fillMaxWidth().testTag("ordinary-essay-input"),
                            minLines = 4,
                            label = { Text("我的作答（支持分点简要作答）") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewOptionRow(
    label: String,
    isOfficial: Boolean,
    isSelected: Boolean,
    isWrongPick: Boolean,
) {
    val containerColor = when {
        isOfficial -> UiRoles.accent.copy(alpha = 0.16f)
        isWrongPick -> MaterialTheme.colorScheme.errorContainer
        else -> UiRoles.surface(UiSurfaceRole.Block)
    }
    val borderColor = when {
        isOfficial -> UiRoles.accent
        isWrongPick -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val statusSuffix = when {
        isOfficial && isSelected -> "你选对了"
        isOfficial -> "正确答案"
        isWrongPick -> "你的选择"
        else -> null
    }
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                color = if (isWrongPick) MaterialTheme.colorScheme.onErrorContainer else UiRoles.foreground,
            )
            statusSuffix?.let { suffix ->
                Spacer(Modifier.size(8.dp))
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isWrongPick) MaterialTheme.colorScheme.error else UiRoles.accent,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun OrdinaryChoiceOptionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    enabled: Boolean,
) {
    val targetBg = if (selected) MaterialTheme.colorScheme.secondaryContainer else UiRoles.surface(UiSurfaceRole.Block)
    val bgColor by animateColorAsState(targetValue = targetBg, animationSpec = tween(110), label = "opt-bg")
    val borderColor by animateColorAsState(
        targetValue = if (selected) UiRoles.accent.copy(alpha = 0.72f) else Color.Transparent,
        animationSpec = tween(110),
        label = "opt-border",
    )
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    interactionSource = interaction,
                    indication = null,
                    onClick = { if (!selected) onSelect() },
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, enabled = enabled, onClick = null)
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else UiRoles.foreground,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrdinaryAnswerSheetDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val latestDismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val dismissDistance = with(density) { 64.dp.toPx() }
    val defaultSlidePx = with(density) { 420.dp.toPx() }
    val openAnim = remember { Animatable(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var allowSheetDrag by remember { mutableStateOf(true) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        openAnim.animateTo(1f, tween(210, easing = FastOutSlowInEasing))
    }

    fun requestDismiss() {
        if (closing) return
        closing = true
        scope.launch {
            openAnim.animateTo(0f, tween(160, easing = LinearOutSlowInEasing))
            latestDismiss()
        }
    }

    fun dragBy(delta: Float): Float {
        if (closing) return 0f
        val before = dragOffset
        dragOffset = (before + delta).coerceAtLeast(0f)
        return dragOffset - before
    }

    fun settleDrag() {
        if (closing || dragOffset == 0f) return
        if (dragOffset >= dismissDistance) {
            requestDismiss()
        } else {
            scope.launch {
                animate(dragOffset, 0f, animationSpec = tween(160, easing = LinearOutSlowInEasing)) { value, _ ->
                    dragOffset = value
                }
            }
        }
    }

    val headerDrag = rememberDraggableState { dragBy(it) }
    val nestedConnection = remember(ime, density) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && dragOffset > 0f && available.y < 0f) {
                    Offset(0f, dragBy(available.y))
                } else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && allowSheetDrag && available.y > 0f) {
                    Offset(0f, dragBy(available.y))
                } else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragOffset == 0f) return Velocity.Zero
                settleDrag()
                return Velocity(0f, available.y)
            }
        }
    }

    val dark = UiRoles.dark
    val currentScheme = MaterialTheme.colorScheme
    val elevatedScheme = remember(currentScheme, dark) { currentScheme.elevatedForSheet(dark) }
    val scrimAlpha = UiRoles.extended.modalScrimAlpha
    val maxContentHeight = (LocalConfiguration.current.screenHeightDp * 0.60f).dp

    Dialog(
        onDismissRequest = { requestDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.setWindowAnimations(0)
            window?.setDimAmount(0f)
            onDispose {}
        }
        BackHandler(enabled = !closing) { requestDismiss() }

        Box(
            modifier = Modifier.fillMaxSize().semantics { paneTitle = title },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha * openAnim.value))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { requestDismiss() },
                    )
                    .testTag("ordinary-sheet-scrim"),
            )

            Surface(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val slideBase = maxOf(size.height, defaultSlidePx)
                        translationY = slideBase * (1f - openAnim.value) + dragOffset
                    }
                    .pointerInput(ime, density) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            allowSheetDrag = scroll.value == 0 && ime.getBottom(density) == 0
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = elevatedScheme.surfaceContainerHigh,
                contentColor = elevatedScheme.onSurface,
                tonalElevation = 2.dp,
            ) {
                MaterialTheme(colorScheme = elevatedScheme, typography = MaterialTheme.typography) {
                    Column(
                        modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .draggable(
                                    state = headerDrag,
                                    orientation = Orientation.Vertical,
                                    enabled = !closing && ime.getBottom(density) == 0,
                                    onDragStopped = { settleDrag() },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            BottomSheetDefaults.DragHandle(
                                modifier = Modifier.testTag("ordinary-sheet-drag-handle"),
                            )
                            Text(
                                text = title,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 6.dp)
                                    .semantics { heading() },
                                style = UiRoles.text(UiRoles.pageTitle),
                                color = UiRoles.foreground,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxContentHeight)
                                .nestedScroll(nestedConnection)
                                .verticalScroll(scroll)
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            content = content,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End,
                            content = actions,
                        )
                    }
                }
            }
        }
    }
}
