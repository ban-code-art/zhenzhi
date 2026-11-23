package com.example.myapplication.ui.knowledge.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.ViewCompat
import androidx.core.view.doOnDetach
import androidx.core.view.doOnLayout
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.ui.navigation.*
import kotlinx.coroutines.CancellationException
import com.example.myapplication.domain.reader.ExtractionQuality
import com.example.myapplication.data.reader.importer.FormatRegistry
import com.example.myapplication.data.reader.importer.ReaderFormat
import com.example.myapplication.data.reader.ink.InkTool
import com.example.myapplication.data.reader.ink.InkStroke
import com.example.myapplication.data.reader.notes.AnnotationColor
import com.example.myapplication.data.reader.notes.AnnotationStyle
import com.example.myapplication.data.reader.notes.ReaderAnnotationView
import com.example.myapplication.data.reader.notes.AnnotationKind
import com.example.myapplication.data.reader.notes.ReaderSelectionSnapshot
import com.example.myapplication.ui.ai.AgentLaunchContext
import com.example.myapplication.ui.components.AnchoredActionMenu
import com.example.myapplication.ui.components.AnchoredMenuChoice
import com.example.myapplication.ui.components.ColorSwatchOption
import com.example.myapplication.ui.components.ColorSwatchRow
import com.example.myapplication.ui.components.PopupAnchor
import com.example.myapplication.ui.components.PopupDrag
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiActionGroup
import com.example.myapplication.ui.components.UiAlertHost
import com.example.myapplication.ui.components.UiBackdrop
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.UiGlassImageBackdrop
import com.example.myapplication.ui.components.UiGlassQuality
import com.example.myapplication.ui.components.LocalUiGlassQuality
import com.example.myapplication.ui.components.UiNoticeHost
import com.example.myapplication.ui.components.UiNoticeVisuals
import com.example.myapplication.ui.components.UiPane
import com.example.myapplication.ui.components.UiPaneRole
import com.example.myapplication.ui.components.UiSettingsSwitch
import com.example.myapplication.ui.components.UiSheet
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.theme.LocalAppDarkTheme
import com.example.myapplication.ui.theme.ReaderTokens
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import com.example.myapplication.ui.components.UiConfirmDialog
import com.example.myapplication.ui.components.AdaptiveWindowInfo
import com.example.myapplication.ui.components.rememberAdaptiveWindowInfo
import com.example.myapplication.ui.knowledge.reader.search.ReaderSearchSheet
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import com.example.myapplication.domain.reader.ReaderOpenRequest
import com.example.myapplication.domain.reader.ReaderTurnDirection
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import com.example.myapplication.ui.knowledge.course.*

private enum class ReaderDrawerTab(val label: String) {
    OUTLINE("目录"),
    ANNOTATIONS("标注"),
    BOOKMARKS("书签"),
}

private class ReaderMoreMenuState(
    val openFrom: Rect,
    val drag: PopupDrag?,
) {
    var backdropImage by mutableStateOf<ImageBitmap?>(null)
}

private val readerAnnotationColorOptions = AnnotationColor.entries.map {
    ColorSwatchOption(key = it.name, hex = it.hex, displayName = it.label)
}

private suspend fun SnackbarHostState.showUiNotice(ownerKey: Any, message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(
        UiNoticeVisuals(
            eventId = UUID.randomUUID().toString(),
            ownerKey = ownerKey,
            message = message,
            duration = SnackbarDuration.Short,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)

@Composable
internal fun LegacyReaderScreen(
    documentId: String,
    /** 来源卡片/出处带入的持久 Locator（可空）；只用于恢复位置，不参与权限判断。 */
    initialLocatorJson: String? = null,
    onBack: () -> Unit,
    isSourceReader: Boolean = false,
    onOpenNotebook: () -> Unit = {},
    onAskAi: (AgentLaunchContext) -> Unit = {},
    onGenerateQuiz: (documentId: String, sectionId: String?) -> Unit = { _, _ -> },
    courseRequest: ReaderOpenRequest? = null,
    courseNavigation: CourseReaderNavigation? = null,
    onPositionReady: (String) -> Unit = {},
    courseQuizAvailable: Boolean = false,
    onOpenNativePdf: ((String?) -> Unit)? = null,
) {
    val adaptive = rememberAdaptiveWindowInfo()
    val context = LocalContext.current
    val factory = remember(documentId, initialLocatorJson, courseRequest) {
        ReaderViewModelFactory(context, documentId, initialLocatorJson, courseRequest)
    }
    val viewModel: ReaderViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    val globalDark = LocalAppDarkTheme.current
    LaunchedEffect(globalDark) { viewModel.setGlobalDarkTheme(globalDark) }
    val effectiveTheme = if (globalDark) ReaderTheme.NIGHT else ReaderTheme.LIGHT
    val effectiveSettings = if (effectiveTheme != state.settings.theme) state.settings.copy(theme = effectiveTheme) else state.settings
    val effectiveState = if (effectiveSettings != state.settings) state.copy(settings = effectiveSettings) else state
    val appearanceColors = readerAppearanceColors(effectiveSettings, state.settingsFollowsGlobalTheme)
    val backdrop = rememberUiBackdrop()
    val snackbarHostState = remember { SnackbarHostState() }
    val noticeOwnerKey = documentId to state.contentRevision
    val scope = rememberCoroutineScope()
    val canAskReaderAi = state.canAskReaderAi()
    fun askReaderAi(selection: ReaderSelectionSnapshot? = null) {
        val current = viewModel.state.value
        if (!current.canAskReaderAi()) return
        val reading = viewModel.captureReadingContext(expectedSelection = selection)
        if (selection != null && (reading?.quote.isNullOrBlank() || reading?.locatorJson == null)) {
            scope.launch(Dispatchers.Main.immediate) {
                snackbarHostState.showUiNotice(noticeOwnerKey, "选区已变化，请重新选择")
            }
            return
        }
        val launch = AgentLaunchContext(
            documentId = reading?.documentId ?: documentId,
            documentTitle = current.title,
            sectionId = reading?.sectionId ?: current.selection?.sectionId ?: current.currentSectionId,
            sectionTitle = reading?.sectionTitle ?: current.currentSectionTitle,
            quote = reading?.quote ?: current.selection?.quote,
            locatorJson = reading?.locatorJson,
        )
        if (selection != null) viewModel.clearSelection()
        onAskAi(launch)
    }
    val annotationAction by viewModel.annotationActions.state.collectAsState()
    val annotationCreation by viewModel.annotationCreation.state.collectAsState()
    val bookmarkAction by viewModel.bookmarkActions.state.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val webController = remember { ReaderWebController() }
    DisposableEffect(viewModel, webController) {
        viewModel.bindBookmarkOwner(webController::ownsBookmarkPosition)
        onDispose { viewModel.bindBookmarkOwner(null) }
    }
    val pagerController = remember { ReaderPagerController() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var drawerTab by rememberSaveable { mutableStateOf(ReaderDrawerTab.OUTLINE) }
    // O17/F01: retired dead NoteEditorDialog; selection/PDF/existing notes use SelectionActionSheet, PdfTextSheet, AnnotationActionSheet.
    val moreAnchor = remember { PopupAnchor() }
    var moreMenu by remember { mutableStateOf<ReaderMoreMenuState?>(null) }
    var sceneOrigin by remember { mutableStateOf(Offset.Zero) }
    var sceneWindowBounds by remember { mutableStateOf(Rect.Zero) }
    fun menuBounds(bounds: Rect?): Rect? = bounds?.translate(-sceneOrigin)
    var showPdfText by rememberSaveable { mutableStateOf(false) }
    var showOcrDialog by rememberSaveable { mutableStateOf(false) }
    // W5-04 书内搜索入口（只挂载 sheet，不改变既有阅读/进度语义）
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var displayedReadingBody by remember(viewModel) { mutableStateOf<String?>(null) }
    val readingBody = state.readerBodyIdentity()
    com.example.myapplication.ui.knowledge.TrackCourseReadingActivity(
        activityKey = "$documentId:${state.contentRevision}",
        readingReady = displayedReadingBody == readingBody && !state.isLoading && state.error == null &&
            !drawerState.isOpen && !showSettings && !showSearch,
    )
    var leaving by remember { mutableStateOf(false) }
    var showSettingsExitConfirmation by remember { mutableStateOf(false) }
    // Confirmation belongs to this live reader/request and is never replayed after recreation.
    var pendingCourseTurn by remember(viewModel, courseRequest) { mutableStateOf<String?>(null) }
    var courseBoundaryNotice by remember { mutableStateOf<String?>(null) }
    var samplingCourseTurn by remember { mutableStateOf(false) }
    val latestPositionReady by rememberUpdatedState(onPositionReady)
    LaunchedEffect(state.currentSectionId, state.currentPage, state.locateTarget, state.isLoading, state.error) {
        if (courseRequest != null && state.format != ReaderFormat.PDF && !state.isLoading && state.error == null) {
            viewModel.currentLocatorJson()?.let { raw ->
                val point = com.example.myapplication.domain.reader.ReaderLocatorCodec.decodeOrNull(raw)
                if (point != null && ReaderLocatorValidator.validate(point).isValid && viewModel.isAllowedLocator(point)) latestPositionReady(raw)
            }
        }
    }
    val readerView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val window = remember(readerView, context) {
        findReaderWindow(readerView.context) ?: findReaderWindow(context)
    }
    val readerKey = remember(documentId, initialLocatorJson, isSourceReader, viewModel, lifecycleOwner) { Any() }
    var immersiveRequested by rememberSaveable(documentId, initialLocatorJson, isSourceReader) {
        mutableStateOf(state.settings.immersivePreference)
    }
    LaunchedEffect(state.settings.immersivePreference) { immersiveRequested = state.settings.immersivePreference }
    // 设置面板等对话框关闭后重新断言系统栏隐藏（部分 OEM 在失焦窗口上忽略 hide()）。
    var immersiveReassertToken by remember { mutableStateOf(0) }
    val inkViewport = remember(readerKey, state.currentSectionId, state.contentRevision) {
        mutableStateOf<InkDocumentSpace?>(null)
    }
    val liveScrollProgress = remember(readerKey, state.currentSectionId, state.contentRevision) {
        mutableStateOf<Float?>(null)
    }
    val inkDocumentSpace = if (state.format == ReaderFormat.PDF) null else ({ inkViewport.value })
    val inkModifier = Modifier.widthIn(max = adaptive.contentMaxWidth)
    val webInkStrokes = if (state.inkMode) state.inkStrokes else state.displayInkStrokes
    var webPaintedInk by remember(readerKey, readingBody) { mutableStateOf<List<InkStroke>?>(null) }
    var inkCanvasHeightPx by remember(readerKey) { mutableStateOf(0f) }
    var selectionActionsRequested by remember(readerKey, state.currentSectionId, state.selectionClearToken) {
        mutableStateOf(false)
    }
    LaunchedEffect(state.selection == null) {
        if (state.selection == null) selectionActionsRequested = false
    }
    fun ownsInkGesture(): Boolean {
        val current = viewModel.state.value
        return current.inkMode && current.readerBodyIdentity() == readingBody
    }
    var pointerActive by remember(readerKey, window) { mutableStateOf(false) }
    val routeResumed by rememberReaderRouteResumed(lifecycleOwner)
    val accessibilityManager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    val accessibilityActive by rememberReaderAccessibilityActive(accessibilityManager, context, lifecycleOwner)
    val imeVisible = isBackImeVisible()
    val latestImeVisible by key(readerKey, window) { rememberUpdatedState(imeVisible) }

    // Event callbacks read the VM and mutable gate inputs now, rather than last frame's conditions.
    val currentChromeConditions: () -> ReaderChromeConditions = {
        val current = viewModel.state.value
        ReaderChromeConditions(
            immersiveEnabled = immersiveRequested && window != null,
            readerVisible = routeResumed && lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED && !leaving,
            selectionActive = current.selection != null,
            imeVisible = latestImeVisible ||
                ViewCompat.getRootWindowInsets(readerView)?.isVisible(WindowInsetsCompat.Type.ime()) == true,
            editorOpen = current.inkMode,
            directoryOpen = drawerState.currentValue != DrawerValue.Closed ||
                drawerState.targetValue != DrawerValue.Closed,
            accessibilityActive = accessibilityActive || readerAccessibilityEnabled(accessibilityManager),
            overlayOpen = annotationAction.isOpen || annotationCreation.draft != null || pendingCourseTurn != null || pointerActive || showSettings || showSearch || showPdfText || showOcrDialog ||
                moreMenu != null || current.isLoading || current.error != null,
        )
    }
    val presentationConditions = currentChromeConditions()
    val chrome = if (window != null) {
        rememberReaderPresentationBinding(window, readerKey, presentationConditions,
            immersiveReassertToken = immersiveReassertToken)
    } else {
        rememberReaderChromeController(readerKey, presentationConditions)
    }
    val synchronizeChromeForEvent: () -> ReaderChromeConditions = {
        val conditions = currentChromeConditions()
        chrome.setLifecycleResumed(routeResumed && lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED)
        chrome.updateConditions(conditions)
        if (conditions.accessibilityActive) chrome.show()
        conditions
    }
    val onPresentationInteraction: () -> Unit = {
        synchronizeChromeForEvent()
        chrome.onInteraction()
    }
    val onImmersiveChanged: (Boolean) -> Unit = { enabled ->
        if (enabled && viewModel.state.value.inkMode) viewModel.exitInkMode()
        immersiveRequested = enabled
        val latest = viewModel.state.value
        viewModel.updateReadingOptions(latest.settings.copy(immersivePreference = enabled))
        onPresentationInteraction()
    }
    SideEffect { if (accessibilityActive) chrome.show() }
    // 阅读态展示已保存笔迹：翻页/换节、标注数据回流后按当前页刷新；画笔模式下由可编辑层接管。
    LaunchedEffect(state.currentSectionId, state.currentPage, state.inkMode, state.annotations) {
        viewModel.refreshDisplayInk()
    }
    val chromeVisible = !immersiveRequested && (chrome.isVisible || accessibilityActive || state.inkMode)
    suspend fun sampleCourseBoundary(direction: ReaderTurnDirection): Boolean? {
        val current = viewModel.state.value
        if (current.isLoading || current.error != null || current.selection != null || current.inkMode) return null
        return if (current.format == ReaderFormat.PDF) {
            val sample = pagerController.boundary(direction) ?: return null
            viewModel.onPdfPageChanged(sample.first)
            sample.second
        } else {
            val sample = webController.courseBoundary(direction, current.documentId, current.contentRevision, current.currentSectionId) ?: return null
            sample.viewportJson?.let(viewModel::onSemanticViewport)
            sample.atBoundary
        }
    }
    fun turnPage(direction: ReaderTurnDirection) {
        if (courseNavigation == null) {
            if (viewModel.state.value.format == ReaderFormat.PDF) {
                if (direction == ReaderTurnDirection.NEXT) pagerController.next() else pagerController.prev()
            } else if (direction == ReaderTurnDirection.NEXT) webController.next() else webController.prev()
            return
        }
        if (samplingCourseTurn || pendingCourseTurn != null || leaving || courseNavigation.busy) return
        samplingCourseTurn = true
        scope.launch(Dispatchers.Main.immediate) {
            try {
                when (sampleCourseBoundary(direction)) {
                    false -> if (viewModel.state.value.format == ReaderFormat.PDF) {
                        if (direction == ReaderTurnDirection.NEXT) pagerController.next() else pagerController.prev()
                    } else if (direction == ReaderTurnDirection.NEXT) webController.next() else webController.prev()
                    true -> if (!viewModel.moveWithinCourse(direction)) {
                        val neighbor = if (direction == ReaderTurnDirection.NEXT) courseNavigation.nextTitle else courseNavigation.previousTitle
                        if (neighbor == null) courseBoundaryNotice = if (direction == ReaderTurnDirection.NEXT) "已经是最后一章" else "已经是第一章"
                        else { courseBoundaryNotice = null; pendingCourseTurn = direction.name }
                    }
                    null -> snackbarHostState.showUiNotice(noticeOwnerKey, "正文位置尚未就绪，请稍后重试")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { snackbarHostState.showUiNotice(noticeOwnerKey, failure.message ?: "阅读现场已保留，请重试") }
            finally { samplingCourseTurn = false }
        }
    }
    // Both default controls and edge taps share the real renderer boundary decision.
    val onViewportTap: (ReaderTapAction) -> Unit = { action ->
        val conditions = synchronizeChromeForEvent()
        if (conditions.readerVisible) {
            chrome.onInteraction()
            when (action) {
                ReaderTapAction.PREVIOUS -> turnPage(ReaderTurnDirection.PREVIOUS)
                ReaderTapAction.NEXT -> turnPage(ReaderTurnDirection.NEXT)
                ReaderTapAction.TOGGLE_CHROME ->
                    if (conditions.accessibilityActive) chrome.show()
                    else if (!immersiveRequested) chrome.toggle()
            }
        }
    }
    val requestBack = unifiedBackRequest(BackState(page = BackPage.READER, imeVisible = isBackImeVisible(),
        isSourceReader = isSourceReader,
        isImmersiveReader = immersiveRequested,
        topLayer = when {
            pendingCourseTurn != null -> BackLayer.Overlay("course-turn")
            showOcrDialog -> BackLayer.Overlay("reader-ocr")
            showPdfText -> BackLayer.Panel("reader-pdf-text")
            showSearch -> BackLayer.Panel("reader-search")
            showSettings -> BackLayer.Panel("reader-settings")
            moreMenu != null -> BackLayer.Overlay("reader-menu")
            drawerState.isOpen -> BackLayer.Panel("reader-drawer")
            else -> null
        }, selection = when {
            state.selection != null -> BackSelection.TEXT
            state.inkMode -> BackSelection.DRAWING
            else -> null
        })) { decision ->
        onPresentationInteraction()
        when (decision) {
            is BackDecision.CloseLayer -> when (decision.layer.key) {
                "course-turn" -> pendingCourseTurn = null
                "reader-ocr" -> showOcrDialog = false
                "reader-pdf-text" -> showPdfText = false
                "reader-search" -> showSearch = false
                "reader-settings" -> showSettings = false
                "reader-menu" -> moreMenu = null
                "reader-drawer" -> scope.launch(Dispatchers.Main.immediate) { drawerState.close() }
            }
            is BackDecision.ClearSelection -> viewModel.clearSelection()
            BackDecision.ExitImmersive -> onImmersiveChanged(false)
            BackDecision.FinishDrawing -> viewModel.exitInkMode()
            BackDecision.ReturnToCaller, BackDecision.SaveReadingPositionAndLeave -> if (!leaving) {
                leaving = true
                synchronizeChromeForEvent()
                scope.launch(Dispatchers.Main.immediate) {
                    try {
                        if (viewModel.flushBeforeLeave()) onBack()
                        else if (viewModel.hasFailedPendingSettings()) showSettingsExitConfirmation = true
                        else snackbarHostState.showUiNotice(noticeOwnerKey, "有未保存的标注、偏好或阅读位置，请完成保存后返回")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { snackbarHostState.showUiNotice(noticeOwnerKey, "保存失败，阅读现场已保留") }
                    finally { leaving = false; synchronizeChromeForEvent() }
                }
            }
            else -> Unit // Modal editors own their dirty-draft confirmation.
        }
    }

    pendingCourseTurn?.let { raw ->
        val direction = ReaderTurnDirection.valueOf(raw)
        val turnTitle = if (direction == ReaderTurnDirection.NEXT) "打开下一章？" else "打开上一章？"
        val turnMessage = (if (direction == ReaderTurnDirection.NEXT) courseNavigation?.nextTitle else courseNavigation?.previousTitle).orEmpty()
        UiAlertHost(
            title = turnTitle,
            ownerKey = "course-turn-$raw",
            onDismissRequest = { _, _ -> if (!leaving) pendingCourseTurn = null },
        ) { interaction, _, _ ->
            Text(
                turnTitle,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = UiRoles.foreground,
                textAlign = TextAlign.Center,
            )
            if (turnMessage.isNotBlank()) {
                Text(
                    turnMessage,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                Spacer(Modifier.height(12.dp))
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UiAction(
                    label = "取消",
                    onClick = { if (!leaving) pendingCourseTurn = null },
                    enabled = !leaving,
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    actionId = "course.turn.cancel",
                    modifier = Modifier.testTag("course-reader-cancel"),
                )
                UiAction(
                    label = "确认",
                    onClick = {
                        if (!leaving) {
                            leaving = true
                            scope.launch(Dispatchers.Main.immediate) {
                                try {
                                    if (sampleCourseBoundary(direction) != true) {
                                        pendingCourseTurn = null
                                        snackbarHostState.showUiNotice(noticeOwnerKey, "正文位置已变化，请重新翻页")
                                    } else if (viewModel.flushBeforeLeave()) {
                                        pendingCourseTurn = null
                                        courseNavigation?.onNavigate?.invoke(direction)
                                    } else snackbarHostState.showUiNotice(noticeOwnerKey, "有未保存的标注、偏好或阅读位置，请完成保存后换章")
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { snackbarHostState.showUiNotice(noticeOwnerKey, "保存失败，阅读现场已保留") }
                                finally { leaving = false }
                            }
                        }
                    },
                    enabled = !leaving,
                    busy = leaving,
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    foreground = UiRoles.accent,
                    actionId = "course.turn.confirm",
                    modifier = Modifier.testTag("course-reader-confirm"),
                )
            }
        }
    }
    if (showSettingsExitConfirmation) {
        UiConfirmDialog(
            title = "停止等待偏好保存？",
            message = "部分偏好可能已经保存。继续将停止重试未确认的偏好，不会撤销已保存的设置；阅读位置和标注仍需完成保存。",
            confirmLabel = "停止重试并返回",
            dismissLabel = "继续阅读",
            onConfirm = {
                if (!leaving) {
                    leaving = true
                    scope.launch(Dispatchers.Main.immediate) {
                        try {
                            showSettingsExitConfirmation = false
                            if (viewModel.abandonSettingsBeforeLeave()) onBack()
                            else snackbarHostState.showUiNotice(noticeOwnerKey, "偏好已停止重试，仍有未保存的标注或阅读位置")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { snackbarHostState.showUiNotice(noticeOwnerKey, "阅读现场已保留，请重试返回") }
                        finally { leaving = false; synchronizeChromeForEvent() }
                    }
                }
            },
            onDismiss = { if (!leaving) showSettingsExitConfirmation = false },
            canConfirm = !leaving,
            busy = leaving,
            destructive = true,
            ownerKey = "reader-settings-exit",
            actionId = "reader.settings.exit",
        )
    }
    // Clip the drawer inside reader bounds so NavHost popExitTransition cannot reveal the closed -320.dp TOC sheet.
    val drawerRevealed = !leaving && (
        drawerState.currentValue != DrawerValue.Closed ||
            drawerState.targetValue != DrawerValue.Closed ||
            drawerState.isAnimationRunning
    )
    ModalNavigationDrawer(
        modifier = Modifier.fillMaxSize().clipToBounds().observeReaderChromePointers(chrome) { active ->
            val wasActive = pointerActive
            pointerActive = active
            synchronizeChromeForEvent()
            if (wasActive && !active) chrome.onInteraction()
        },
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = UiRoles.surface(UiSurfaceRole.Card),
                drawerContentColor = UiRoles.foreground,
                modifier = Modifier
                    .width(320.dp)
                    .graphicsLayer { alpha = if (drawerRevealed) 1f else 0f }
                    .then(if (drawerRevealed) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }),
            ) {
                Text(
                    "阅读导航",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = UiRoles.foreground,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ReaderDrawerTab.entries.forEach { tab ->
                        val selected = drawerTab == tab
                        val contentColor = if (selected) UiRoles.color(UiColorRole.OnPrimary) else UiRoles.foreground
                        UiPane(
                            role = if (selected) UiPaneRole.Tinted else UiPaneRole.Regular,
                            shape = CircleShape,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { drawerTab = tab },
                                ),
                        ) {
                            Row(
                                modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (selected) {
                                    Icon(
                                        UiIcons.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = contentColor,
                                    )
                                }
                                Text(
                                    tab.label,
                                    style = UiRoles.text(UiRoles.actionText),
                                    color = contentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = ReaderTokens.Divider, modifier = Modifier.padding(top = 8.dp))
                when (drawerTab) {
                    ReaderDrawerTab.OUTLINE -> {
                        if (state.outline.isEmpty()) {
                            Text("本书暂无目录", color = ReaderTokens.TextSecondary, modifier = Modifier.padding(20.dp))
                        } else {
                            LazyColumn {
                                items(state.outline, key = { it.id }) { item ->
                                    NavigationDrawerItem(
                                        label = { Text(item.title) },
                                        selected = item.targetSectionId != null && item.targetSectionId == state.currentSectionId,
                                        onClick = {
                                            item.targetSectionId?.let(viewModel::selectSection)
                                            scope.launch(Dispatchers.Main.immediate) { drawerState.close() }
                                        },
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }

                    ReaderDrawerTab.ANNOTATIONS -> {
                        if (state.annotations.isEmpty()) {
                            // 能力由格式声明（P5-01）：图片漫画没有文字层，提示语不能让它去"选字"。
                            val caps = FormatRegistry.capabilitiesOf(state.format)
                            Text(
                                if (caps.selectableText) {
                                    "长按正文并选择文字后即可添加高亮或附注"
                                } else {
                                    "该格式没有可选中文字，可用画笔做手写标注"
                                },
                                color = ReaderTokens.TextSecondary,
                                modifier = Modifier.padding(20.dp),
                            )
                        } else {
                            LazyColumn {
                                items(state.annotations, key = { it.id }) { annotation ->
                                    NavigationDrawerItem(
                                        label = {
                                            Column {
                                                Text(annotationLabel(annotation), maxLines = 2)
                                                annotation.noteText?.takeIf { it.isNotBlank() }?.let {
                                                    Text(it, style = MaterialTheme.typography.bodySmall, color = ReaderTokens.TextSecondary, maxLines = 1)
                                                }
                                            }
                                        },
                                        badge = {
                                            if (annotation.kind != AnnotationKind.INK && !state.historyReadOnly) {
                                                UiAction(
                                                    label = "编辑",
                                                    onClick = {
                                                        viewModel.editAnnotation(annotation)
                                                        scope.launch { drawerState.close() }
                                                    },
                                                    inheritedPane = true,
                                                    foreground = UiRoles.accent,
                                                    actionId = "reader.drawer.edit.${annotation.id}",
                                                )
                                            }
                                        },
                                        selected = false,
                                        onClick = {
                                            viewModel.openAnnotation(annotation)
                                            scope.launch(Dispatchers.Main.immediate) { drawerState.close() }
                                        },
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }

                    ReaderDrawerTab.BOOKMARKS -> {
                        if (state.bookmarks.isEmpty()) {
                            Text("还没有书签", color = ReaderTokens.TextSecondary, modifier = Modifier.padding(20.dp))
                        } else {
                            LazyColumn {
                                items(state.bookmarks, key = { it.id }) { bookmark ->
                                    NavigationDrawerItem(
                                        label = { Text(bookmark.preview.ifBlank { "书签" }, maxLines = 2) },
                                        selected = false,
                                        onClick = {
                                            viewModel.openBookmark(bookmark)
                                            scope.launch(Dispatchers.Main.immediate) { drawerState.close() }
                                        },
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ) {
        val headerTitle = state.currentSectionTitle.ifBlank { state.title.ifBlank { "阅读" } }
        val canSampleMenu = Build.VERSION.SDK_INT >= 31 && readerView.isHardwareAccelerated &&
            LocalUiGlassQuality.current.desired != UiGlassQuality.None
        fun openMoreMenu(drag: PopupDrag?): Boolean {
            if (moreMenu != null) return false
            val rawBounds = menuBounds(moreAnchor.bounds)
            if (drag != null && rawBounds == null) return false
            val openFrom = rawBounds ?: Rect(0f, 0f, 48f, 48f)
            val menu = ReaderMoreMenuState(openFrom = openFrom, drag = drag)
            // Request the visible book frame before mounting the menu. Its glass samples this
            // bitmap while WebView continues drawing normally, without replay into a shader.
            if (canSampleMenu && Build.VERSION.SDK_INT >= 31 && window != null) {
                requestReaderMenuBackdrop(window, sceneWindowBounds) { image ->
                    if (moreMenu === menu) menu.backdropImage = image
                }
            }
            onPresentationInteraction()
            moreMenu = menu
            return true
        }
        val overlayActive = moreMenu != null
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    sceneOrigin = it.positionInRoot()
                    sceneWindowBounds = it.boundsInWindow()
                },
        ) {
            // Keep the native reader out of the layer replayed by the animated menu pane.
            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = if (overlayActive) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier,
                    containerColor = appearanceColors.background,
                    contentWindowInsets = if (chromeVisible) ScaffoldDefaults.contentWindowInsets
                        else ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal),
                    topBar = {
                        if (chromeVisible) {
                            ReaderBarSurface(glass = courseRequest == null) { chromeBackdrop ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding(),
                                ) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 72.dp)
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        UiAction(
                                            label = "返回",
                                            icon = UiIcons.Back,
                                            onClick = requestBack,
                                            backdrop = chromeBackdrop,
                                            actionId = "reader.back",
                                            modifier = if (courseRequest != null) Modifier.testTag("course-reader-back") else Modifier,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = headerTitle,
                                            modifier = Modifier.weight(1f),
                                            style = UiRoles.text(UiRoles.pageTitle),
                                            color = UiRoles.foreground,
                                            maxLines = if (adaptive.preferStackedControls) 2 else 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        UiActionGroup(backdrop = chromeBackdrop) {
                                            if (!adaptive.preferStackedControls) {
                                                UiAction(
                                                    label = "书内搜索",
                                                    icon = UiIcons.Search,
                                                    onClick = { showSearch = true; onPresentationInteraction() },
                                                    actionId = "reader.search",
                                                )
                                                UiAction(
                                                    label = "目录",
                                                    icon = UiIcons.Books,
                                                    onClick = {
                                                        onPresentationInteraction()
                                                        scope.launch(Dispatchers.Main.immediate) { drawerState.open() }
                                                    },
                                                    actionId = "reader.toc",
                                                )
                                                BookmarkToggleButton(
                                                    bookmarkAction,
                                                    onToggle = { viewModel.toggleCurrentBookmark(); onPresentationInteraction() },
                                                    onRetryWrite = viewModel::retryBookmark,
                                                    onRetryObservation = viewModel.bookmarkActions::retryObservation,
                                                    showMessage = false,
                                                )
                                            } else {
                                                UiAction(
                                                    label = "目录",
                                                    icon = UiIcons.Books,
                                                    onClick = {
                                                        onPresentationInteraction()
                                                        scope.launch(Dispatchers.Main.immediate) { drawerState.open() }
                                                    },
                                                    actionId = "reader.toc",
                                                )
                                            }
                                            UiAction(
                                                label = "更多",
                                                icon = UiIcons.More,
                                                onClick = { openMoreMenu(drag = null) },
                                                actionId = "reader.more",
                                                modifier = moreAnchor.visualModifier
                                                    .focusRequester(moreAnchor.focus)
                                                    .then(moreAnchor.inputModifier { drag -> openMoreMenu(drag) }),
                                                foregroundModifier = moreAnchor.foregroundModifier(),
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = ReaderTokens.Divider)
                                }
                            }
                        }
                    },
                    bottomBar = {
                        if (chromeVisible && !state.isLoading && state.error == null) {
                            ReaderBottomBar(
                                state = effectiveState,
                                displayedProgress = {
                                    if (courseRequest == null && effectiveState.format != ReaderFormat.PDF)
                                        liveScrollProgress.value ?: effectiveState.progress
                                    else effectiveState.progress
                                },
                                onPrev = { onViewportTap(ReaderTapAction.PREVIOUS) },
                                onNext = { onViewportTap(ReaderTapAction.NEXT) },
                                adaptive = adaptive,
                                courseReader = courseRequest != null,
                            )
                        }
                    },
                ) { padding ->
                    val showInfoStrip = chromeVisible && !state.isLoading && state.error == null &&
                        (state.historyReadOnly || (state.title.isNotBlank() && state.title != headerTitle))
                    val infoStripText = buildString {
                        if (state.title.isNotBlank() && state.title != headerTitle) append(state.title)
                        if (state.historyReadOnly) {
                            if (isNotEmpty()) append(" · ")
                            append("历史版本 · 只读")
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .testTag("reader-viewport")
                            .onSizeChanged { inkCanvasHeightPx = it.height.toFloat() }
                            .then(
                                if (courseRequest != null) {
                                    Modifier.testTag("course-reader-viewport").semantics {
                                        this[CourseReaderDocumentId] = state.documentId.orEmpty()
                                        this[CourseReaderSectionId] = state.currentSectionId.orEmpty()
                                        this[CourseReaderChapterKey] = courseRequest.target.chapterKey.orEmpty()
                                    }
                                } else Modifier,
                            ),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (showInfoStrip && infoStripText.isNotBlank()) {
                                Text(
                                    text = infoStripText,
                                    modifier = Modifier
                                        .widthIn(max = adaptive.contentMaxWidth)
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                                )
                            }
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                when {
                                    state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                                    state.error != null -> ReaderError(state.error.orEmpty(), requestBack, viewModel::retry)
                                    state.format == ReaderFormat.PDF -> PdfReaderContent(
                                        state = effectiveState,
                                        appearanceColors = appearanceColors,
                                        controller = pagerController,
                                        onPageChanged = viewModel::onPdfPageChanged,
                                        maxWidth = adaptive.contentMaxWidth,
                                        onViewportTap = onViewportTap,
                                        currentState = { viewModel.state.value },
                                        legacyCanvasHeight = inkCanvasHeightPx,
                                        onInkStroke = viewModel::appendInkStroke,
                                        onInkEraseMatching = viewModel::eraseInkMatching,
                                        onPageReady = { page ->
                                            val current = viewModel.state.value
                                            if (current.currentPage == page) displayedReadingBody =
                                                "${current.contentRevision}:${current.currentSectionId}:$page"
                                            if (courseRequest != null) {
                                                viewModel.onPdfPageChanged(page)
                                                viewModel.currentLocatorJson()?.let(latestPositionReady)
                                            }
                                        },
                                    )
                                    else -> WebReaderContent(
                                        state = effectiveState,
                                        controller = webController,
                                        colors = appearanceColors,
                                        // WebView 回调（onPageFinished / JS 桥）不在组合作用域内，直接读
                                        // ViewModel StateFlow 的最新值，连「组合滞后一帧」的陈旧窗口也一起消掉。
                                        currentStateProvider = {
                                            val latest = viewModel.state.value
                                            if (latest.settingsFollowsGlobalTheme) {
                                                latest.copy(settings = latest.settings.copy(
                                                    theme = if (globalDark) ReaderTheme.NIGHT else ReaderTheme.LIGHT,
                                                ))
                                            } else latest
                                        },
                                        onScrollProgress = { liveScrollProgress.value = it },
                                        onSelection = viewModel::onTextSelection,
                                        onSemanticViewport = viewModel::onSemanticViewport,
                                        onInvalidatePosition = viewModel::invalidateWebPosition,
                                        onClearSelection = viewModel::clearSelection,
                                        onInkViewport = { inkViewport.value = it },
                                        onInkPainted = { webPaintedInk = it },
                                        onViewportTap = onViewportTap,
                                        onBodyReady = { displayedReadingBody = it },
                                        modifier = Modifier.widthIn(max = adaptive.contentMaxWidth).fillMaxSize(),
                                    )
                                }
                                if (state.format != ReaderFormat.PDF && !state.isLoading && state.error == null) {
                                    if (state.inkMode) {
                                        key(readingBody) {
                                            InkOverlay(
                                                strokes = state.inkStrokes,
                                                tool = state.inkTool,
                                                color = state.inkColor,
                                                width = state.inkWidth,
                                                onStroke = { if (ownsInkGesture()) viewModel.appendInkStroke(it) },
                                                onEraseMatching = { if (ownsInkGesture()) viewModel.eraseInkMatching(it) },
                                                document = inkDocumentSpace,
                                                paintSavedStrokes = webPaintedInk != webInkStrokes,
                                                modifier = inkModifier.testTag("reader-ink-overlay"),
                                            )
                                        }
                                    } else if (state.displayInkStrokes.isNotEmpty()) {
                                        InkOverlay(
                                            strokes = state.displayInkStrokes,
                                            tool = state.inkTool,
                                            color = state.inkColor,
                                            width = state.inkWidth,
                                            onStroke = {},
                                            onEraseMatching = {},
                                            editable = false,
                                            document = inkDocumentSpace,
                                            paintSavedStrokes = webPaintedInk != webInkStrokes,
                                            modifier = inkModifier.testTag("reader-ink-display"),
                                        )
                                    }
                                }
                            }
                        }
                        if (state.inkMode && state.error == null) {
                            InkToolbar(
                                state = state,
                                adaptive = adaptive,
                                onTool = viewModel::setInkTool,
                                onColor = viewModel::setInkColor,
                                onWidth = viewModel::setInkWidth,
                                onUndo = viewModel::undoInk,
                                onRedo = viewModel::redoInk,
                                onExit = viewModel::exitInkMode,
                                onPrev = { onViewportTap(ReaderTapAction.PREVIOUS) },
                                onNext = { onViewportTap(ReaderTapAction.NEXT) },
                                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                            )
                        }
                        if (!state.inkMode && !state.isLoading && state.error == null && state.selection != null &&
                            !selectionActionsRequested && !annotationAction.isOpen && annotationCreation.draft == null) {
                            UiAction(
                                label = "选区操作",
                                onClick = { selectionActionsRequested = viewModel.state.value.selection != null },
                                filled = true,
                                actionId = "reader.selection.actions",
                                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("reader-selection-actions"),
                            )
                        }
                        // Keep the body viewport unchanged when a first/last chapter notice appears.
                        if (courseRequest != null && chromeVisible && !state.isLoading && state.error == null) {
                            courseBoundaryNotice?.let { message ->
                                UiPane(
                                    role = UiPaneRole.Banner,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                                ) {
                                    Text(
                                        message,
                                        Modifier.testTag("course-reader-boundary").padding(horizontal = 12.dp, vertical = 8.dp),
                                        color = UiRoles.foreground,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            moreMenu?.backdropImage?.let { image ->
                UiGlassImageBackdrop(backdrop, image, Modifier.matchParentSize())
            }
            UiNoticeHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .then(if (overlayActive) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier),
                ownerKey = noticeOwnerKey,
                backdrop = backdrop,
            )
            moreMenu?.let { menu ->
                val anchorRect = menuBounds(moreAnchor.bounds)
                val rootChoices = buildList {
                    add(AnchoredMenuChoice("tools", "阅读工具", icon = UiIcons.Edit, opensSubmenu = true, testTag = "reader-menu-tools"))
                    add(AnchoredMenuChoice("settings", "阅读设置", icon = UiIcons.Settings, testTag = "reader-menu-settings"))
                    add(AnchoredMenuChoice("immersive", if (immersiveRequested) "退出沉浸阅读" else "沉浸阅读", testTag = "reader-menu-immersive"))
                    // 没有文字层的格式（图片漫画）不提供问答/出题入口：
                    // 点了只会得到"证据覆盖不足"，属于无效操作（P5-01 能力声明）。
                    if (canAskReaderAi) {
                        add(AnchoredMenuChoice("ask_ai", "问 AI", icon = UiIcons.Ai, testTag = "reader-menu-ask-ai"))
                        add(
                            AnchoredMenuChoice(
                                "quiz",
                                if (courseRequest != null && !courseQuizAvailable) "本章练习正在准备中" else "生成随堂测",
                                icon = UiIcons.Quiz,
                                enabled = courseRequest == null || courseQuizAvailable,
                                testTag = "reader-menu-quiz",
                            ),
                        )
                    }
                    add(
                        AnchoredMenuChoice(
                            "completed",
                            if (state.isCompleted) "标记为未完成" else "标记为已完成",
                            icon = UiIcons.Check,
                            enabled = !state.historyReadOnly,
                            testTag = "reader-menu-completed",
                        ),
                    )
                }
                val toolChoices = buildList {
                    add(AnchoredMenuChoice("ink", "画笔标注", enabled = !state.historyReadOnly, testTag = "reader-menu-ink"))
                    add(AnchoredMenuChoice("notebook", "笔记本", testTag = "reader-menu-notebook"))
                    if (state.format == ReaderFormat.PDF) {
                        add(AnchoredMenuChoice("pdf_text", "本页文本与标注", testTag = "reader-menu-pdf-text"))
                        if (onOpenNativePdf != null) {
                            add(
                                AnchoredMenuChoice(
                                    "native_pdf",
                                    "原生 PDF 批注",
                                    enabled = !state.isLoading && state.error == null,
                                    testTag = "reader-native-pdf-preview",
                                ),
                            )
                        }
                    }
                    if (state.ocr.canRun) {
                        add(AnchoredMenuChoice("ocr", "扫描页识别与复核", testTag = "reader-menu-ocr"))
                    }
                    if (adaptive.preferStackedControls) {
                        add(AnchoredMenuChoice("search", "书内搜索", icon = UiIcons.Search, testTag = "reader-menu-search"))
                        val toggleEnabled = !bookmarkAction.showsProgress &&
                            (bookmarkAction.canRetryObservation ||
                                bookmarkAction.canRetryWrite(BookmarkActionIntent.TOGGLE) ||
                                bookmarkAction.canInvoke(BookmarkActionIntent.TOGGLE))
                        add(
                            AnchoredMenuChoice(
                                "bookmark",
                                if (bookmarkAction.isBookmarked) "移除书签" else "添加书签",
                                icon = if (bookmarkAction.isBookmarked) UiIcons.BookmarkFilled else UiIcons.Bookmark,
                                enabled = toggleEnabled,
                                testTag = "reader-menu-bookmark",
                            ),
                        )
                    }
                }
                AnchoredActionMenu(
                    anchor = anchorRect,
                    openFrom = menu.openFrom,
                    choices = rootChoices,
                    submenuLabel = "阅读工具",
                    childChoices = toolChoices,
                    paneTitleText = "阅读菜单",
                    onChoose = { value ->
                        when (value) {
                            "settings" -> {
                                showSettings = true
                                onPresentationInteraction()
                            }
                            "immersive" -> onImmersiveChanged(!immersiveRequested)
                            "ask_ai" -> askReaderAi()
                            "quiz" -> {
                                val reading = viewModel.captureReadingContext()
                                onGenerateQuiz(
                                    reading?.documentId ?: documentId,
                                    reading?.sectionId ?: state.selection?.sectionId ?: state.currentSectionId,
                                )
                            }
                            "completed" -> viewModel.toggleCompleted()
                            "ink" -> viewModel.enterInkMode()
                            "notebook" -> onOpenNotebook()
                            "pdf_text" -> showPdfText = true
                            "native_pdf" -> scope.launch {
                                if (viewModel.flushBeforeLeave()) onOpenNativePdf?.invoke(viewModel.currentLocatorJson())
                            }
                            "ocr" -> showOcrDialog = true
                            "search" -> {
                                showSearch = true
                                onPresentationInteraction()
                            }
                            "bookmark" -> {
                                when {
                                    bookmarkAction.canRetryObservation -> viewModel.bookmarkActions.retryObservation()
                                    bookmarkAction.canRetryWrite(BookmarkActionIntent.TOGGLE) -> viewModel.retryBookmark()
                                    else -> viewModel.toggleCurrentBookmark()
                                }
                                onPresentationInteraction()
                            }
                        }
                    },
                    onClosed = {
                        moreMenu = null
                        if (anchorRect != null && !showSettings && !showSearch && !showPdfText && !showOcrDialog) {
                            runCatching { moreAnchor.focus.requestFocus() }
                        }
                    },
                    backdrop = backdrop,
                    tagPrefix = "reader-more-menu",
                    sourceAnchor = moreAnchor,
                    drag = menu.drag,
                    ownerMatches = {
                        val latest = viewModel.state.value
                        !leaving && latest.documentId == state.documentId &&
                            latest.contentRevision == state.contentRevision &&
                            latest.currentSectionId == state.currentSectionId
                    },
                )
            }
        }
    }
    if (showSettings) ReaderSettingsSheet(
        state = effectiveState,
        onDismiss = {
            showSettings = false
            if (immersiveRequested) immersiveReassertToken += 1
            onPresentationInteraction()
        },
        onSettings = {
            viewModel.updateReadingOptions(it)
            onPresentationInteraction()
        },
        onSelectTheme = { viewModel.selectTheme(it); onPresentationInteraction() },
        immersiveEnabled = immersiveRequested,
        onImmersiveChanged = onImmersiveChanged,
        onUseDefaults = viewModel::useDefaultSettings,
        onMakeDefaults = viewModel::makeDefaultSettings,
        onRetry = viewModel::retrySettingsSave,
    )
    if (showSearch) {
        // 选中命中 -> ReaderLocator -> 既有 jumpToLocator（高亮/定位口径不变）
        ReaderSearchSheet(
            documentId = documentId,
            onDismiss = { showSearch = false },
            onOpenLocator = { locator ->
                if (viewModel.isAllowedLocator(locator)) { showSearch = false; viewModel.jumpToLocator(locator) }
                else scope.launch(Dispatchers.Main.immediate) {
                    snackbarHostState.showUiNotice(noticeOwnerKey, "该结果不在当前课程章节内")
                }
            },
        )
    }
    if (showPdfText) {
        PdfTextSheet(
            state = state,
            onDismiss = { showPdfText = false },
            onCreateHighlight = { quote, color ->
                viewModel.createPdfAnnotation(quote, state.currentPage, AnnotationStyle(color))
            },
            onCreateNote = { quote, color, note ->
                viewModel.createPdfAnnotation(quote, state.currentPage, AnnotationStyle(color), note)
            },
        )
    }
    if (showOcrDialog) {
        OcrReviewDialog(
            state = state.ocr,
            onDismiss = { showOcrDialog = false },
            onStart = viewModel::startOcr,
            onCancel = viewModel::cancelOcr,
            onOpenPage = { page ->
                showOcrDialog = false
                viewModel.openOcrPage(page)
            },
            onConfirmPage = viewModel::confirmOcrPage,
        )
    }
    val actionSelection = state.selection?.takeIf { selectionActionsRequested }
    if (actionSelection != null && !annotationAction.isOpen && annotationCreation.draft == null) {
        SelectionActionSheet(
            selection = actionSelection,
            onDismiss = viewModel::clearSelection,
            onHighlight = { color ->
                if (viewModel.createHighlight(AnnotationStyle(color))) selectionActionsRequested = false
            },
            onNote = { color, text ->
                if (viewModel.createHighlight(AnnotationStyle(color), text)) selectionActionsRequested = false
            },
            bookmarkState = bookmarkAction,
            onBookmark = viewModel::ensureCurrentBookmark,
            onRetryBookmark = viewModel::retryBookmark,
            onRetryBookmarkObservation = viewModel.bookmarkActions::retryObservation,
            onAskAi = if (canAskReaderAi) ({ askReaderAi(actionSelection) }) else null,
        )
    }
    if (annotationAction.isOpen && annotationAction.showEditor) AnnotationActionSheet(
        state = annotationAction, onNoteChange = viewModel.annotationActions::editNote,
        onColorChange = viewModel.annotationActions::chooseColor, onSave = viewModel::saveAnnotation,
        onDelete = viewModel::deleteAnnotation, onUndo = viewModel::undoAnnotation,
        onRetry = viewModel::retryAnnotation, onReload = viewModel::reloadAnnotation,
        onDismiss = { viewModel.annotationActions.discard() })
    if (annotationCreation.draft != null && annotationCreation.showEditor) AnnotationCreationSheet(
        state = annotationCreation, onNoteChange = viewModel.annotationCreation::editNote,
        onColorChange = viewModel.annotationCreation::chooseColor, onSave = viewModel::saveAnnotationCreation,
        onRetry = viewModel::retryAnnotationCreation, onDismiss = { viewModel.annotationCreation.discard() })
    var dismissedBookmarkChoice by remember { mutableStateOf<BookmarkPositionInput?>(null) }
    if (bookmarkAction.ambiguousBookmarkIds.isNotEmpty() && dismissedBookmarkChoice != bookmarkAction.input) {
        UiAlertHost(
            title = "选择当前位置的书签",
            ownerKey = bookmarkAction.input ?: "bookmark-ambiguous",
            onDismissRequest = { _, _ -> dismissedBookmarkChoice = bookmarkAction.input },
        ) { interaction, _, _ ->
            Text(
                "选择当前位置的书签",
                modifier = Modifier.fillMaxWidth().semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = UiRoles.foreground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            BookmarkCandidateChoices(bookmarkAction, viewModel::selectBookmark, inheritedPane = true)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                UiAction(
                    label = "取消",
                    onClick = { dismissedBookmarkChoice = bookmarkAction.input },
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    actionId = "reader.bookmark.ambiguous.cancel",
                )
            }
        }
    }
    LaunchedEffect(state.message, noticeOwnerKey) {
        state.message?.let { message ->
            snackbarHostState.showUiNotice(noticeOwnerKey, message)
            viewModel.consumeMessage()
        }
    }
}

private fun ReaderUiState.canAskReaderAi(): Boolean =
    !historyReadOnly && FormatRegistry.capabilitiesOf(format).knowledgeIndexable

private fun ReaderUiState.readerBodyIdentity(): String =
    "$contentRevision:$currentSectionId" + if (format == ReaderFormat.PDF) ":$currentPage" else ""

private fun findReaderWindow(context: Context): Window? {
    val visited = Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
    var current: Context? = context
    while (current != null && visited.add(current)) {
        if (current is Activity) return current.window
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

@RequiresApi(31)
private fun requestReaderMenuBackdrop(window: Window, bounds: Rect, onReady: (ImageBitmap) -> Unit) {
    val source = AndroidRect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
    if (source.isEmpty) return
    val scale = (960f / maxOf(source.width(), source.height())).coerceAtMost(1f)
    val bitmap = Bitmap.createBitmap(
        (source.width() * scale).toInt().coerceAtLeast(1),
        (source.height() * scale).toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    fun complete(status: Int) {
        if (status == PixelCopy.SUCCESS) onReady(bitmap.asImageBitmap()) else bitmap.recycle()
    }
    try {
        if (Build.VERSION.SDK_INT >= 34) {
            val request = PixelCopy.Request.Builder.ofWindow(window)
                .setSourceRect(source)
                .setDestinationBitmap(bitmap)
                .build()
            PixelCopy.request(request, window.context.mainExecutor) { complete(it.status) }
        } else {
            PixelCopy.request(window, source, bitmap, ::complete, Handler(Looper.getMainLooper()))
        }
    } catch (_: IllegalArgumentException) {
        bitmap.recycle()
    }
}

@Composable
private fun rememberReaderRouteResumed(owner: LifecycleOwner): State<Boolean> {
    val resumed = remember(owner) { mutableStateOf(owner.lifecycle.currentState == Lifecycle.State.RESUMED) }
    DisposableEffect(owner) {
        val lifecycle = owner.lifecycle
        var disposed = false
        val observer = LifecycleEventObserver { _, event ->
            if (!disposed) {
                resumed.value = when (event) {
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> false
                    else -> lifecycle.currentState == Lifecycle.State.RESUMED
                }
            }
        }
        try {
            lifecycle.addObserver(observer)
            resumed.value = lifecycle.currentState == Lifecycle.State.RESUMED
        } catch (failure: Throwable) {
            disposed = true
            try {
                lifecycle.removeObserver(observer)
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        onDispose {
            disposed = true
            lifecycle.removeObserver(observer)
        }
    }
    return resumed
}

private fun readerAccessibilityEnabled(manager: AccessibilityManager?): Boolean = try {
    manager != null && (manager.isEnabled || manager.isTouchExplorationEnabled)
} catch (_: RuntimeException) {
    // An unavailable accessibility service must not leave controls undiscoverable.
    true
}

@Composable
private fun rememberReaderAccessibilityActive(
    manager: AccessibilityManager?,
    context: Context,
    owner: LifecycleOwner,
): State<Boolean> {
    val active = remember(manager, owner) { mutableStateOf(readerAccessibilityEnabled(manager)) }
    DisposableEffect(manager, owner) {
        if (manager == null) return@DisposableEffect onDispose { }
        var disposed = false
        var registrationFailed = false
        var stateRegistered = false
        var touchRegistered = false
        var servicesRegistered = false
        var lifecycleRegistered = false
        val lifecycle = owner.lifecycle
        val handler = Handler(Looper.getMainLooper())
        fun refresh() {
            if (!disposed) active.value = registrationFailed || readerAccessibilityEnabled(manager)
        }
        val refreshOnMain = Runnable { refresh() }
        val stateListener = AccessibilityManager.AccessibilityStateChangeListener { handler.post(refreshOnMain) }
        val touchListener = AccessibilityManager.TouchExplorationStateChangeListener { handler.post(refreshOnMain) }
        val servicesListener = if (Build.VERSION.SDK_INT >= 33) {
            AccessibilityManager.AccessibilityServicesStateChangeListener { refresh() }
        } else null
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        fun releaseListeners() {
            disposed = true
            var firstFailure: RuntimeException? = null
            fun remove(action: () -> Unit) {
                try {
                    action()
                } catch (failure: RuntimeException) {
                    val previous = firstFailure
                    if (previous == null) firstFailure = failure
                    else if (previous !== failure) previous.addSuppressed(failure)
                }
            }
            remove { handler.removeCallbacks(refreshOnMain) }
            if (lifecycleRegistered) remove {
                lifecycle.removeObserver(lifecycleObserver)
                lifecycleRegistered = false
            }
            if (servicesRegistered && Build.VERSION.SDK_INT >= 33 && servicesListener != null) remove {
                manager.removeAccessibilityServicesStateChangeListener(servicesListener)
                servicesRegistered = false
            }
            if (touchRegistered) remove {
                manager.removeTouchExplorationStateChangeListener(touchListener)
                touchRegistered = false
            }
            if (stateRegistered) remove {
                manager.removeAccessibilityStateChangeListener(stateListener)
                stateRegistered = false
            }
            firstFailure?.let { android.util.Log.w("ReaderPresentation", "Accessibility listener cleanup failed", it) }
        }
        try {
            stateRegistered = true
            stateRegistered = manager.addAccessibilityStateChangeListener(stateListener)
            check(stateRegistered) { "Accessibility state listener registration was rejected" }
            touchRegistered = true
            touchRegistered = manager.addTouchExplorationStateChangeListener(touchListener)
            check(touchRegistered) { "Touch exploration listener registration was rejected" }
            if (Build.VERSION.SDK_INT >= 33 && servicesListener != null) {
                servicesRegistered = true
                manager.addAccessibilityServicesStateChangeListener(context.mainExecutor, servicesListener)
            }
            lifecycleRegistered = true
            lifecycle.addObserver(lifecycleObserver)
            refresh()
        } catch (failure: RuntimeException) {
            registrationFailed = true
            releaseListeners()
            active.value = true
            android.util.Log.w("ReaderPresentation", "Accessibility observation unavailable; keep controls visible", failure)
        }
        onDispose { releaseListeners() }
    }
    return active
}

/** Initial-pass observation only: child Web/pager/selection handlers retain gesture ownership. */
@Composable
private fun Modifier.observeReaderChromePointers(
    chrome: ReaderChromeController,
    onActivity: (Boolean) -> Unit,
): Modifier {
    // An old pointer coroutine's finally must never call a new controller's callback.
    val latestActivity by key(chrome) { rememberUpdatedState(onActivity) }
    return pointerInput(chrome) {
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    latestActivity(event.changes.any { it.pressed })
                }
            }
        } finally {
            // Cancellation may originate on an I/O worker; release the pointer gate on Main.
            withContext(NonCancellable + Dispatchers.Main.immediate) { latestActivity(false) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OcrReviewDialog(
    state: ReaderOcrUiState,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpenPage: (Int) -> Unit,
    onConfirmPage: (Int) -> Unit,
) {
    val active = state.workState in setOf("ENQUEUED", "RUNNING", "BLOCKED")
    val reviewPages = state.checkpoint?.pages.orEmpty()
        .filter { it.quality == ExtractionQuality.OCR_REVIEW_REQUIRED }
        .sortedBy { it.pageIndex }
    UiAlertHost(
        title = "扫描页识别与复核",
        ownerKey = "reader-ocr-review",
        onDismissRequest = { _, _ -> onDismiss() },
    ) { interaction, _, _ ->
        Text(
            "扫描页识别与复核",
            modifier = Modifier.fillMaxWidth().semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            color = UiRoles.foreground,
            textAlign = TextAlign.Center,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 14.dp)
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "仅在本机识别。已确认的页才进入索引和题目出处。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when (state.workState) {
                    "ENQUEUED" -> "等待后台条件满足"
                    "RUNNING" -> "正在识别扫描页"
                    "BLOCKED" -> "等待设备电量或存储条件"
                    "SUCCEEDED" -> when (state.outcomeCode) {
                        "PUBLISHED" -> "已发布 ${state.publishedPages} 页文字"
                        "NOTHING_TO_DO" -> "没有待识别的扫描页"
                        "COMPLETED_WITHOUT_PUBLICATION" -> state.outcomeMessage ?: "没有可发布的已确认文字"
                        else -> "识别任务完成"
                    }
                    "FAILED" -> "识别未完成：${state.outcomeMessage ?: state.outcomeCode ?: "可重试"}"
                    "CANCELLED" -> "识别已取消；已完成页保留，可继续"
                    else -> "后台按页识别，完成的页会保存检查点"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = UiRoles.foreground,
            )
            if (active && state.progressTotal > 0) {
                LinearProgressIndicator(
                    progress = { (state.progressDone.toFloat() / state.progressTotal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${state.progressDone}/${state.progressTotal} 页${state.progressPage?.let { " · 当前第 ${it + 1} 页" }.orEmpty()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.checkpoint?.let { checkpoint ->
                Text(
                    "已保存 ${checkpoint.pages.size} 页结果 · 模型 ${checkpoint.modelVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (reviewPages.isNotEmpty()) {
                Text(
                    "以下文字需逐页对照原 PDF。确认后才会发布。",
                    style = MaterialTheme.typography.labelMedium,
                    color = UiRoles.foreground,
                )
                reviewPages.forEach { page ->
                    UiPane(role = UiPaneRole.Regular, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("第 ${page.pageIndex + 1} 页 · 待复核", fontWeight = FontWeight.SemiBold, color = UiRoles.foreground)
                            Text(page.text.take(700), style = MaterialTheme.typography.bodySmall, color = UiRoles.foreground)
                            Text("识别质量：${page.algorithmVersion}；模型未提供置信度。", style = MaterialTheme.typography.labelSmall, color = ReaderTokens.TextSecondary)
                            UiAction(
                                label = "在阅读器打开原页",
                                onClick = { onOpenPage(page.pageIndex) },
                                inheritedPane = true,
                                actionId = "reader.ocr.open.${page.pageIndex}",
                            )
                            UiAction(
                                label = "我已对照原页，确认这页文字",
                                onClick = { onConfirmPage(page.pageIndex) },
                                inheritedPane = true,
                                foreground = UiRoles.accent,
                                actionId = "reader.ocr.confirm.${page.pageIndex}",
                            )
                        }
                    }
                }
            } else if (state.reviewRequiredPages > 0) {
                Text("有 ${state.reviewRequiredPages} 页等待复核；检查点正在更新。", style = MaterialTheme.typography.bodySmall, color = UiRoles.foreground)
            }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UiAction(
                label = "关闭",
                onClick = onDismiss,
                inheritedPane = true,
                interactionSource = interaction,
                animatePress = false,
                actionId = "reader.ocr.close",
            )
            if (active) {
                UiAction(
                    label = "取消识别",
                    onClick = onCancel,
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    foreground = UiRoles.extended.destructive,
                    actionId = "reader.ocr.cancel",
                )
            } else {
                UiAction(
                    label = "开始 / 继续识别",
                    onClick = onStart,
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    foreground = UiRoles.accent,
                    actionId = "reader.ocr.start",
                )
            }
        }
    }
}

@Composable
private fun PdfTextSheet(
    state: ReaderUiState,
    onDismiss: () -> Unit,
    onCreateHighlight: (String, AnnotationColor) -> Unit,
    onCreateNote: (String, AnnotationColor, String) -> Unit,
) {
    val text = remember(state.sectionContent?.html) {
        org.jsoup.Jsoup.parse(state.sectionContent?.html.orEmpty()).text()
    }
    var value by remember(text) { mutableStateOf(TextFieldValue(text)) }
    var color by rememberSaveable { mutableStateOf(AnnotationColor.YELLOW) }
    var note by rememberSaveable { mutableStateOf("") }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val dirty = note.isNotEmpty() || color != AnnotationColor.YELLOW
    val requestDismiss = { if (dirty) confirmDiscard = true else onDismiss() }
    val latestDirty by rememberUpdatedState(dirty)
    val selected = runCatching {
        val start = minOf(value.selection.start, value.selection.end).coerceAtLeast(0)
        val end = maxOf(value.selection.start, value.selection.end).coerceAtMost(text.length)
        if (end > start) text.substring(start, end).trim() else ""
    }.getOrDefault("")
    UiSheet(
        title = "本页提取文本",
        onDismiss = requestDismiss,
        confirmDismiss = {
            if (latestDirty) { confirmDiscard = true; false } else true
        },
        actions = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                UiAction(
                    label = "关闭",
                    onClick = requestDismiss,
                    inheritedPane = true,
                    actionId = "reader.pdfText.close",
                )
                UiAction(
                    label = if (note.isNotBlank()) "保存高亮与附注" else "保存高亮",
                    onClick = {
                        if (selected.isNotBlank()) {
                            if (note.isNotBlank()) onCreateNote(selected, color, note)
                            else onCreateHighlight(selected, color)
                        }
                    },
                    enabled = selected.isNotBlank(),
                    filled = true,
                    actionId = "reader.pdfText.save",
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "此面板保存文字摘录与页级关联，不在 PDF 原页绘制文字高亮。可选择摘录并添加附注。",
                color = ReaderTokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                readOnly = true,
                textStyle = UiRoles.text(TextStyle(fontSize = 16.sp, lineHeight = 25.sp, color = UiRoles.foreground)),
                cursorBrush = SolidColor(UiRoles.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp),
            )
            Text(
                if (selected.isBlank()) "尚未选择文字" else "已选择：${selected.take(120)}",
                style = MaterialTheme.typography.bodySmall,
                color = UiRoles.foreground,
            )
            ColorSwatchRow(
                options = readerAnnotationColorOptions,
                selectedKey = color.name,
                onSelected = { key -> AnnotationColor.entries.firstOrNull { it.name == key }?.let { color = it } },
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(1_000_000) },
                label = { Text("附注") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        }
    }
    if (confirmDiscard) {
        UiConfirmDialog(
            title = "放弃未保存的附注？",
            message = "附注和颜色尚未保存。",
            confirmLabel = "放弃",
            dismissLabel = "继续编辑",
            onConfirm = { confirmDiscard = false; onDismiss() },
            onDismiss = { confirmDiscard = false },
            destructive = true,
            ownerKey = "pdf-text-discard",
            actionId = "reader.pdfText.discard",
        )
    }
}

private enum class SelectionSheetDeparture { DISMISS, ASK_AI }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectionActionSheet(
    selection: ReaderSelectionSnapshot,
    onDismiss: () -> Unit,
    onHighlight: (AnnotationColor) -> Unit,
    onNote: (AnnotationColor, String) -> Unit,
    bookmarkState: BookmarkActionState,
    onBookmark: () -> Unit,
    onRetryBookmark: () -> Unit,
    onRetryBookmarkObservation: () -> Unit,
    onAskAi: (() -> Unit)?,
) = SelectionActionSheet(
    quote = selection.quote,
    selectionKey = selection,
    onDismiss = onDismiss,
    onHighlight = onHighlight,
    onNote = onNote,
    bookmark = {
        BookmarkEnsureButton(bookmarkState, onBookmark, onRetryBookmark, onRetryBookmarkObservation, inheritedPane = true)
    },
    onAskAi = onAskAi,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectionActionSheet(
    quote: String,
    selectionKey: Any,
    onDismiss: () -> Unit,
    onHighlight: (AnnotationColor) -> Unit,
    onAskAi: (() -> Unit)?,
    onNote: ((AnnotationColor, String) -> Unit)? = null,
    bookmark: (@Composable () -> Unit)? = null,
) {
    var color by rememberSaveable(selectionKey) { mutableStateOf(AnnotationColor.YELLOW) }
    var note by rememberSaveable(selectionKey) { mutableStateOf("") }
    var departure by rememberSaveable(selectionKey) { mutableStateOf<SelectionSheetDeparture?>(null) }
    val noteFocus = remember(selectionKey) { FocusRequester() }
    var noteFocused by remember(selectionKey) { mutableStateOf(false) }
    var restoreNoteFocus by remember(selectionKey) { mutableStateOf(false) }
    val dirty = note.isNotEmpty() || color != AnnotationColor.YELLOW

    fun requestDeparture(action: SelectionSheetDeparture) {
        if (departure == null) {
            restoreNoteFocus = noteFocused
            departure = action
        }
    }

    LaunchedEffect(departure, restoreNoteFocus) {
        if (departure == null && restoreNoteFocus) {
            androidx.compose.runtime.withFrameNanos { }
            noteFocus.requestFocus()
            restoreNoteFocus = false
        }
    }

    val requestDismiss = { if (dirty) requestDeparture(SelectionSheetDeparture.DISMISS) else onDismiss() }
    val latestDirty by rememberUpdatedState(dirty)
    UiSheet(
        title = "已选择原文",
        onDismiss = requestDismiss,
        actionsAtTop = true,
        confirmDismiss = {
            if (latestDirty) { requestDeparture(SelectionSheetDeparture.DISMISS); false } else true
        },
        actions = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UiAction(
                    label = "关闭",
                    onClick = requestDismiss,
                    inheritedPane = true,
                    actionId = "reader.selection.close",
                )
                Text(
                    "已选择原文",
                    style = UiRoles.text(UiRoles.pageTitle),
                    color = UiRoles.foreground,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp).semantics { heading() },
                )
                UiAction(
                    label = if (note.isNotBlank()) "保存高亮与附注" else "保存高亮",
                    onClick = { if (onNote != null && note.isNotBlank()) onNote(color, note) else onHighlight(color) },
                    filled = true,
                    actionId = "reader.selection.save",
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(quote.take(240), style = MaterialTheme.typography.bodyLarge, color = UiRoles.foreground)
            ColorSwatchRow(
                options = readerAnnotationColorOptions,
                selectedKey = color.name,
                onSelected = { key -> AnnotationColor.entries.firstOrNull { it.name == key }?.let { color = it } },
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                bookmark?.invoke()
                if (onAskAi != null) {
                    UiAction(
                        label = "问 AI",
                        onClick = { if (dirty) requestDeparture(SelectionSheetDeparture.ASK_AI) else onAskAi() },
                        inheritedPane = true,
                        foreground = UiRoles.accent,
                        actionId = "reader.selection.askAi",
                    )
                }
            }
            if (onNote != null) OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(1_000_000) },
                label = { Text("附注") },
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(noteFocus).onFocusChanged { noteFocused = it.isFocused },
                minLines = 2,
            )
        }
    }
    departure?.let { action ->
        val askingAi = action == SelectionSheetDeparture.ASK_AI
        UiConfirmDialog(
            title = if (onNote != null) "放弃未保存的附注？" else "放弃高亮颜色更改？",
            message = if (onNote != null) "附注和颜色尚未保存。" else "选中的高亮颜色尚未保存。",
            confirmLabel = if (askingAi) "放弃并问 AI" else "放弃",
            dismissLabel = "继续编辑",
            onConfirm = {
                restoreNoteFocus = false
                departure = null
                if (askingAi) onAskAi?.invoke() else onDismiss()
            },
            onDismiss = { departure = null },
            modifier = Modifier.testTag("reader-selection-discard-dialog"),
            ownerKey = selectionKey to action,
            actionId = "reader.annotation.discard",
        )
    }
}

@Composable
private fun ReaderError(error: String, onBack: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("无法打开这本书", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = UiRoles.foreground)
        Spacer(Modifier.height(8.dp))
        Text(error, color = ReaderTokens.TextSecondary)
        Spacer(Modifier.height(20.dp))
        UiAction(label = "重试", onClick = onRetry, filled = true, actionId = "reader.error.retry")
        Spacer(Modifier.height(8.dp))
        UiAction(label = "返回书架", onClick = onBack, inheritedPane = true, actionId = "reader.error.back")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InkToolbar(
    state: ReaderUiState,
    adaptive: AdaptiveWindowInfo,
    onTool: (InkTool) -> Unit,
    onColor: (String) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExit: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var brushExpanded by rememberSaveable { mutableStateOf(!adaptive.preferStackedControls) }
    val inkColorOptions = remember {
        AnnotationColor.entries.map { ColorSwatchOption(it.hex, it.hex, it.label) }
    }
    UiPane(
        role = UiPaneRole.Regular,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InkTool.entries.forEach { tool ->
                    val selected = state.inkTool == tool
                    val contentColor = if (selected) UiRoles.color(UiColorRole.OnPrimary) else UiRoles.foreground
                    UiPane(
                        role = if (selected) UiPaneRole.Tinted else UiPaneRole.Regular,
                        shape = CircleShape,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { onTool(tool) }),
                    ) {
                        Text(
                            when (tool) { InkTool.PEN -> "笔"; InkTool.MARKER -> "马克笔"; InkTool.ERASER -> "擦除" },
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp, vertical = 8.dp),
                            style = UiRoles.text(UiRoles.actionText),
                            color = contentColor,
                        )
                    }
                }
                if (adaptive.preferStackedControls) {
                    UiAction(
                        label = "笔刷",
                        onClick = { brushExpanded = !brushExpanded },
                        inheritedPane = true,
                        foreground = if (brushExpanded) UiRoles.accent else UiRoles.foreground,
                        actionId = "reader.ink.brush",
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                UiActionGroup {
                    UiAction("撤销", onClick = onUndo, actionId = "reader.ink.undo")
                    UiAction("重做", onClick = onRedo, actionId = "reader.ink.redo")
                    UiAction("上一页", onClick = onPrev, actionId = "reader.ink.prev")
                    UiAction("下一页", onClick = onNext, actionId = "reader.ink.next")
                }
                UiAction(label = "完成", onClick = onExit, filled = true, actionId = "reader.ink.done")
            }
            if (!adaptive.preferStackedControls || brushExpanded) {
                ColorSwatchRow(
                    options = inkColorOptions,
                    selectedKey = state.inkColor,
                    onSelected = onColor,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("粗细", style = MaterialTheme.typography.labelSmall, color = UiRoles.foreground)
                    Slider(
                        value = state.inkWidth,
                        onValueChange = onWidth,
                        valueRange = 0.002f..0.05f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderBarSurface(glass: Boolean, content: @Composable (UiBackdrop?) -> Unit) {
    val chrome = UiRoles.surface(UiSurfaceRole.Chrome)
    if (!glass) {
        Box(Modifier.fillMaxWidth().background(chrome)) { content(null) }
        return
    }
    val source = rememberUiBackdrop()
    val paper = UiRoles.surface(UiSurfaceRole.Page)
    Box(Modifier.fillMaxWidth()) {
        UiGlassBackdrop(source, Modifier.matchParentSize()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(paper, chrome))))
        }
        content(source)
    }
}

@Composable
private fun ReaderBottomBar(
    state: ReaderUiState,
    displayedProgress: () -> Float,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    adaptive: AdaptiveWindowInfo,
    courseReader: Boolean = false,
) {
    val bar = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
    if (!courseReader) {
        ReaderBarSurface(glass = true) { chromeBackdrop ->
            Column(bar) {
                ImportedReaderProgress(state, displayedProgress)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UiAction(
                        "上一页",
                        onClick = onPrev,
                        actionId = "reader.prev",
                        backdrop = chromeBackdrop,
                        modifier = Modifier.weight(1f),
                        visualModifier = Modifier.fillMaxWidth(),
                    )
                    UiAction(
                        "下一页",
                        onClick = onNext,
                        actionId = "reader.next",
                        backdrop = chromeBackdrop,
                        modifier = Modifier.weight(1f),
                        visualModifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        return
    }
    Box(Modifier.fillMaxWidth().background(UiRoles.surface(UiSurfaceRole.Chrome))) {
        Column(bar) {
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = ReaderTokens.Primary,
                trackColor = ReaderTokens.Divider,
            )
            val position = readerPositionLabel(state, state.progress)
            Spacer(Modifier.height(6.dp))
            if (adaptive.preferStackedControls) {
                if (position.isNotEmpty()) {
                    Text(
                        position,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = UiRoles.foreground,
                    )
                }
                UiActionGroup(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    UiAction(
                        label = "上一页",
                        onClick = onPrev,
                        actionId = "reader.prev",
                        modifier = Modifier.weight(1f).testTag("course-reader-prev"),
                    )
                    UiAction(
                        label = "下一页",
                        onClick = onNext,
                        actionId = "reader.next",
                        modifier = Modifier.weight(1f).testTag("course-reader-next"),
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    UiActionGroup {
                        UiAction(
                            label = "上一页",
                            onClick = onPrev,
                            actionId = "reader.prev",
                            modifier = Modifier.testTag("course-reader-prev"),
                        )
                        UiAction(
                            label = "下一页",
                            onClick = onNext,
                            actionId = "reader.next",
                            modifier = Modifier.testTag("course-reader-next"),
                        )
                    }
                    if (position.isNotEmpty()) {
                        Text(position, style = MaterialTheme.typography.labelLarge, color = UiRoles.foreground)
                    }
                }
            }
        }
    }
}

private fun readerPositionLabel(state: ReaderUiState, progress: Float): String = when {
    !state.settings.pageIndicator -> ""
    state.format == ReaderFormat.PDF -> "第 ${state.currentPage + 1} / ${state.totalPages.coerceAtLeast(1)} 页"
    else -> "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
}

@Composable
private fun ImportedReaderProgress(state: ReaderUiState, displayedProgress: () -> Float) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinearProgressIndicator(
            progress = { displayedProgress().coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(5.dp).clip(CircleShape).testTag("reader-live-progress"),
            color = UiRoles.accent,
            trackColor = UiRoles.accent.copy(alpha = .16f),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        if (state.settings.pageIndicator) {
            Text(
                readerPositionLabel(state, displayedProgress()),
                modifier = Modifier.widthIn(min = 42.dp)
                    .background(UiRoles.surface(UiSurfaceRole.Card), CircleShape)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = UiRoles.foreground,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderSettingsSheet(
    state: ReaderUiState,
    onDismiss: () -> Unit,
    onSettings: (ReaderSettings) -> Unit,
    onSelectTheme: (ReaderSettings) -> Unit,
    immersiveEnabled: Boolean,
    onImmersiveChanged: (Boolean) -> Unit,
    onUseDefaults: () -> Unit,
    onMakeDefaults: () -> Unit,
    onRetry: () -> Unit,
) {
    var fontScale by remember(state.settings.fontScale) { mutableStateOf(state.settings.fontScale) }
    var lineHeight by remember(state.settings.lineHeight) { mutableStateOf(state.settings.lineHeight) }
    // The legacy Web host has format declarations, not domain engine capabilities.
    val formatCaps = FormatRegistry.capabilitiesOf(state.format)
    val typographyReason = when {
        state.format == ReaderFormat.PDF -> "原版 PDF 不支持修改字体、字号、行距或字色"
        state.format == ReaderFormat.CBZ || formatCaps.fixedLayout -> "固定版式不支持修改字体、字号、行距或字色"
        !formatCaps.readable -> "此格式不支持字体设置"
        state.isLoading || state.sectionContent == null || state.error != null -> "正文加载完成后可调整字体设置"
        else -> null
    }
    val typographyEnabled = typographyReason == null
    // Resolve page colors before UiSheet supplies its elevated surface palette.
    val sampleColors = readerAppearanceColors(state.settings, state.settingsFollowsGlobalTheme)
    val automaticText = readerAppearanceColors(
        state.settings.copy(fontColor = ReaderFontColor.Automatic), state.settingsFollowsGlobalTheme,
    ).foregroundHex
    UiSheet(
        title = "阅读设置",
        onDismiss = onDismiss,
        maxContentFraction = 0.78f,
        stableDrag = true,
        actions = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                UiAction(
                    label = "应用设置",
                    onClick = {
                        if (fontScale != state.settings.fontScale || lineHeight != state.settings.lineHeight) {
                            onSettings(state.settings.copy(fontScale = fontScale, lineHeight = lineHeight))
                        }
                        onDismiss()
                    },
                    filled = true,
                    actionId = "reader.settings.apply",
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val foregroundColor = UiRoles.foreground
            val onPrimaryColor = UiRoles.color(UiColorRole.OnPrimary)
            val accentColor = UiRoles.accent
            val errorColor = MaterialTheme.colorScheme.error
            Box(
                Modifier
                    .fillMaxWidth()
                    .testTag("reader-settings-sample")
                    .clip(RoundedCornerShape(16.dp))
                    .background(sampleColors.background)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .padding(horizontal = (state.settings.marginDp / 2).coerceIn(8, 28).dp, vertical = 12.dp),
            ) {
                Text(
                    text = "行文样张：春风又绿江南岸，书页随心调整纸色、字号、行距与页边距。",
                    color = sampleColors.foreground,
                    fontSize = (15f * fontScale).sp,
                    lineHeight = (15f * fontScale * lineHeight).sp,
                )
            }

            Column(Modifier.fillMaxWidth()) {
                UiSettingsSwitch(
                    title = "沉浸阅读",
                    checked = immersiveEnabled,
                    onCheckedChange = onImmersiveChanged,
                )
                Text(
                    "沉浸阅读隐藏上下工具栏，按返回键退出。偏好随文档保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = ReaderTokens.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (typographyEnabled) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("字号 ${(fontScale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = UiRoles.foreground)
                    Slider(fontScale, { fontScale = it; onSettings(state.settings.copy(fontScale = it, lineHeight = lineHeight)) }, valueRange = 0.8f..1.8f)
                    Text("行距 ${String.format("%.2f", lineHeight)}", style = MaterialTheme.typography.labelLarge, color = UiRoles.foreground)
                    Slider(lineHeight, { lineHeight = it; onSettings(state.settings.copy(fontScale = fontScale, lineHeight = it)) }, valueRange = 1.2f..2.2f)
                    Text("页边距 ${state.settings.marginDp}", style = MaterialTheme.typography.labelLarge, color = UiRoles.foreground)
                    Slider(state.settings.marginDp.toFloat(), { onSettings(state.settings.copy(marginDp = it.toInt())) }, valueRange = 0f..64f)
                }
            }

            ReaderFontColorPicker(
                value = state.settings.fontColor,
                theme = state.settings.theme,
                automaticHex = automaticText,
                onChange = { color ->
                    if (typographyEnabled) onSettings(state.settings.copy(
                        fontScale = fontScale, lineHeight = lineHeight, fontColor = color,
                    ))
                },
                enabled = typographyEnabled,
                disabledReason = typographyReason,
            )

            Text("阅读背景跟随应用主题", style = MaterialTheme.typography.labelLarge, color = UiRoles.foreground)
            Text("在“设置 → 外观”选择自定义颜色，正文会同步更新。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Column(Modifier.fillMaxWidth()) {
                UiSettingsSwitch(
                    title = "点击两侧翻页",
                    checked = state.settings.edgeTap,
                    enabled = !state.historyReadOnly,
                    onCheckedChange = { onSettings(state.settings.copy(edgeTap = it)) },
                )
                UiSettingsSwitch(
                    title = "显示阅读位置",
                    checked = state.settings.pageIndicator,
                    enabled = !state.historyReadOnly,
                    onCheckedChange = { onSettings(state.settings.copy(pageIndicator = it)) },
                )
            }

            if (state.settings.flowMode != com.example.myapplication.domain.reader.ReaderFlowMode.SCROLL && typographyEnabled) {
                Text("当前阅读入口暂不支持分页模式，正文使用滚动阅读。", style = MaterialTheme.typography.bodySmall, color = ReaderTokens.TextSecondary)
            }
            Text(
                buildString {
                    append(if (state.settingsInheritsDefaults) "继承全局默认偏好" else "使用此文档的偏好")
                    if (state.settingsFollowsGlobalTheme) append(" · 背景跟随全局深浅色")
                },
                style = MaterialTheme.typography.bodySmall,
                color = ReaderTokens.TextSecondary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                UiAction(
                    label = "设为全局默认",
                    onClick = onMakeDefaults,
                    enabled = !state.historyReadOnly && !state.settingsSaving,
                    inheritedPane = true,
                    foreground = accentColor,
                    actionId = "reader.settings.makeDefaults",
                )
                UiAction(
                    label = "恢复继承",
                    onClick = onUseDefaults,
                    enabled = !state.historyReadOnly && !state.settingsSaving,
                    inheritedPane = true,
                    actionId = "reader.settings.useDefaults",
                )
            }
            if (state.settingsFailure != null) {
                Text(if (state.settingsOutcomeUnknown) "保存结果待确认，修改已保留" else "偏好保存失败，修改已保留", color = errorColor)
                UiAction(
                    label = "重试保存",
                    onClick = onRetry,
                    enabled = !state.settingsSaving && state.settingsRetryable,
                    inheritedPane = true,
                    foreground = accentColor,
                    actionId = "reader.settings.retry",
                )
            } else if (state.settingsPending) {
                Text(if (state.settingsSaving) "正在保存偏好" else "等待保存偏好", style = MaterialTheme.typography.bodySmall, color = ReaderTokens.TextSecondary)
            } else if (state.settingsAbandoned) {
                Text("已停止重试未确认的偏好，已保存的设置未撤销", style = MaterialTheme.typography.bodySmall, color = ReaderTokens.TextSecondary)
            }
        }
    }
}

@Composable
private fun WebReaderContent(
    state: ReaderUiState,
    controller: ReaderWebController,
    colors: ReaderAppearanceColors,
    currentStateProvider: () -> ReaderUiState,
    onScrollProgress: (Float) -> Unit,
    onSelection: (String?) -> Unit,
    onSemanticViewport: (String) -> Unit,
    onInvalidatePosition: () -> Unit,
    onClearSelection: () -> Unit,
    onViewportTap: (ReaderTapAction) -> Unit,
    onInkViewport: (InkDocumentSpace?) -> Unit,
    onInkPainted: (List<InkStroke>?) -> Unit,
    onBodyReady: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val content = state.sectionContent ?: return
    val currentState by rememberUpdatedState(currentStateProvider)
    val latestColors by rememberUpdatedState(colors)
    val latestScrollProgress by rememberUpdatedState(onScrollProgress)
    val latestSelection by rememberUpdatedState(onSelection)
    val latestSemanticViewport by rememberUpdatedState(onSemanticViewport)
    val latestInkViewport by rememberUpdatedState(onInkViewport)
    val latestInkPainted by rememberUpdatedState(onInkPainted)
    val latestViewportTap by rememberUpdatedState(onViewportTap)
    val latestBodyReady by rememberUpdatedState(onBodyReady)
    val inputScope = rememberCoroutineScope()
    val inputContext = LocalContext.current
    val inputDensity = inputContext.resources.displayMetrics.density
    val inputConfig = android.view.ViewConfiguration.get(inputContext)
    val inputArbiter = remember(inputDensity, inputConfig.scaledTouchSlop) {
        ReaderInputArbiter(inputConfig.scaledTouchSlop / inputDensity, android.view.ViewConfiguration.getLongPressTimeout().toLong())
    }
    // AndroidView.factory 的 lambda 只在首次组合时求值一次，闭包里直接读 state 会永久钉住
    // 「首次组合的快照」：切章/旋转后 onPageFinished 仍拿旧 sectionId 与旧进度去 seek/decorate，
    // 并把错误位置经 scheduleSave 写回库（P1-9）。因此 WebView 回调一律通过 currentState()
    // 读 ViewModel 的 StateFlow 最新值（rememberUpdatedState 也会滞后到下一次重组）。
    val loadTracker = remember { ReaderWebLoadTracker() }
    var inkSpace by remember { mutableStateOf<InkDocumentSpace?>(null) }
    var nativeInkScale by remember { mutableStateOf<Float?>(null) }

    fun publishInk(state: ReaderUiState) {
        val strokes = if (state.inkMode) state.inkStrokes else state.displayInkStrokes
        controller.displayInk(strokes, editing = state.inkMode) { displayed ->
            val current = currentState()
            val currentStrokes = if (current.inkMode) current.inkStrokes else current.displayInkStrokes
            if (current.readerBodyIdentity() == state.readerBodyIdentity() &&
                strokes == currentStrokes) {
                latestInkPainted(strokes.takeIf { displayed })
            }
        }
    }

    fun publishScrollProgress(view: ReaderScrollWebView) {
        if (view === controller.webView && view.visibility == View.VISIBLE &&
            loadTracker.isSettledOn(currentState().currentSectionId)) {
            latestScrollProgress(view.scrollProgress())
        }
    }

    fun updateInkViewport(view: WebView) {
        if (view !== controller.webView || !loadTracker.isSettledOn(currentState().currentSectionId)) return
        val old = inkSpace ?: return
        val space = old.copy(
            pixelsPerCssPixel = nativeInkScale ?: old.pixelsPerCssPixel,
            scrollXPx = view.scrollX.toFloat(),
            scrollYPx = view.scrollY.toFloat(),
        )
        inkSpace = space
        latestInkViewport(space)
    }

    val bridge = remember {
        ReaderJsBridge().apply {
            // Only semantic reports carry the current page epoch and a source-backed read point.
            progressCallback = { _, _ -> }
            selectionCallback = { raw -> inputScope.launch(Dispatchers.Main.immediate) {
                val payload = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                if ((payload?.get("sectionId") as? JsonPrimitive)?.contentOrNull == currentState().currentSectionId &&
                    (payload?.get("epoch") as? JsonPrimitive)?.contentOrNull == controller.inputEpoch &&
                    (payload?.get("selectionVersion") as? JsonPrimitive)?.contentOrNull ==
                        currentState().selectionClearToken.toString() &&
                    loadTracker.isSettledOn(currentState().currentSectionId)) latestSelection(raw)
            } }
            semanticViewportCallback = { raw -> inputScope.launch(Dispatchers.Main.immediate) {
                val payload = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                if ((payload?.get("sectionId") as? JsonPrimitive)?.contentOrNull == currentState().currentSectionId &&
                    (payload?.get("epoch") as? JsonPrimitive)?.contentOrNull == controller.inputEpoch &&
                    loadTracker.isSettledOn(currentState().currentSectionId)) latestSemanticViewport(raw)
            } }
            positionInvalidatedCallback = { section, epoch -> inputScope.launch(Dispatchers.Main.immediate) {
                if (section == currentState().currentSectionId && epoch == controller.inputEpoch) onInvalidatePosition()
            } }
            scrollMetricsCallback = { raw -> inputScope.launch(Dispatchers.Main.immediate) {
                val view = controller.webView ?: return@launch
                val metrics = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@launch
                val section = currentState().currentSectionId
                if (metrics["sectionId"]?.jsonPrimitive?.contentOrNull != section ||
                    metrics["epoch"]?.jsonPrimitive?.contentOrNull != controller.inputEpoch ||
                    !loadTracker.isSettledOn(section)) return@launch
                fun positive(name: String) = metrics[name]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() && it > 0f }
                val width = positive("scrollWidth") ?: return@launch
                val height = positive("scrollHeight") ?: return@launch
                val layoutWidth = positive("innerWidth") ?: return@launch
                val scale = nativeInkScale ?: positive("scale") ?: return@launch
                val containers = (metrics["containers"] as? JsonArray).orEmpty().mapNotNull { element ->
                    (element as? JsonObject)?.toInkScrollContainer()
                }
                inkSpace = InkDocumentSpace(
                    width, height, layoutWidth, scale, view.scrollX.toFloat(), view.scrollY.toFloat(), containers,
                )
                latestInkViewport(inkSpace)
            } }
            viewportTapCallback = { x, y, width, height, duration, movement, blocked, section, epoch ->
                inputScope.launch(Dispatchers.Main.immediate) {
                    val current = currentState()
                    if (epoch == controller.inputEpoch && section == current.currentSectionId &&
                        loadTracker.isSettledOn(section) && !current.isLoading && current.error == null &&
                        duration.isFinite() && duration >= 0 && duration < Long.MAX_VALUE.toDouble()) {
                        val action = inputArbiter.resolve(ReaderTapSample(
                            x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), duration.toLong(), movement.toFloat(),
                            consumed = blocked, selectionActive = current.selection != null, inkActive = current.inkMode,
                            startedInSystemEdge = x < 24 || x >= width - 24,
                        ))
                        if (action != null && (action == ReaderTapAction.TOGGLE_CHROME || currentState().settings.edgeTap)) latestViewportTap(action)
                    }
                }
            }
        }
    }
    var webViewState by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(state.settings, state.currentSectionId, colors) {
        // 只在「当前页确实已经落在当前 section」时即时应用设置：切章时这份状态属于新章节，
        // 若立刻应用到还没换页的旧页面上，会把新 sectionId 写进 bridge（选中文字会被
        // 归到错误章节）。切章后的首次应用统一交给 onPageFinished。
        if (!loadTracker.isSettledOn(state.currentSectionId)) return@LaunchedEffect
        controller.settings = state.settings
        controller.appearanceColors = colors
        controller.sectionId = state.currentSectionId
        controller.documentId = state.documentId
        controller.contentRevision = state.contentRevision
        controller.applySettings()
    }
    var clearedSelectionVersion by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.annotations, state.selectionClearToken, state.currentSectionId) {
        if (!loadTracker.isSettledOn(state.currentSectionId)) return@LaunchedEffect
        controller.annotations = state.annotations
        controller.decorate()
        if (clearedSelectionVersion != state.selectionClearToken) {
            controller.clearSelection(state.selectionClearToken)
            clearedSelectionVersion = state.selectionClearToken
        }
    }
    LaunchedEffect(state.locateQuoteToken) {
        if (loadTracker.isSettledOn(state.currentSectionId)) state.locateTarget?.let { controller.locateTarget(it) }
    }
    LaunchedEffect(state.inkStrokes, state.displayInkStrokes, state.inkMode, state.currentSectionId) {
        if (loadTracker.isSettledOn(state.currentSectionId)) publishInk(state)
    }
    AndroidView(
            modifier = modifier,
        factory = { context ->
            ReaderScrollWebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.blockNetworkLoads = true
                settings.textZoom = 100
                visibility = View.INVISIBLE
                setBackgroundColor(latestColors.background.toArgb())
                setOnScrollChangeListener { _, _, _, _, _ ->
                    publishScrollProgress(this)
                    updateInkViewport(this)
                }
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> publishScrollProgress(this) }
                installReaderBridge(bridge)
                webViewClient = object : WebViewClient() {
                    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                        if (view === controller.webView && newScale.isFinite() && newScale > 0f) {
                            nativeInkScale = newScale
                            updateInkViewport(view)
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        if (view !== controller.webView) return
                        view.doOnLayout {
                            if (view !== controller.webView || view.width <= 0 || view.height <= 0) return@doOnLayout
                            // 这里必须读最新状态（而不是 factory 闭包捕获的那份），否则切章后
                            // 会把上一章的 sectionId/进度写回 bridge。
                            val current = currentState()
                            // 只有「本次装载请求的就是当前 section 的页面」才允许落地：切章前
                            // 遗留的完成回调如果照旧执行，会把旧 sectionId 写回 bridge，并用
                            // 上一章的进度 seek 新章节（P1-9 的确定性回退）。
                            val finishedUrl = url ?: view.url
                            if (!loadTracker.isCurrentPage(finishedUrl, current.currentSectionId, current.sectionContent?.fileUrl)) return@doOnLayout
                            controller.settings = current.settings
                            controller.appearanceColors = latestColors
                            controller.sectionId = current.currentSectionId
                            controller.documentId = current.documentId
                            controller.contentRevision = current.contentRevision
                            controller.annotations = current.annotations
                            controller.applySettings()
                            controller.decorate()
                            publishInk(current)
                            controller.clearSelection(current.selectionClearToken)
                            clearedSelectionVersion = current.selectionClearToken
                            // 位置取最新 progress：切章时 loadSection 已把它重置为该章位置
                            // （0 或库中持久化值），旋转重建时它就是用户真实停留位置，
                            // 两种场景都不会回退成「上一章末尾 / 本章开头」。
                            controller.seek(current.progress)
                            current.locateTarget?.let { controller.locateTarget(it, current.restoreViewportOffset) }
                            val readyIdentity = "${current.contentRevision}:${current.currentSectionId}"
                            val epoch = controller.inputEpoch
                            // Existing imports can have an opaque paper color before the reader CSS
                            // runs. Reveal only the visual frame that has the active reader styles.
                            view.evaluateJavascript("Boolean(document.getElementById('reader-style'))") { styled ->
                                if (styled != "true" || view !== controller.webView || epoch != controller.inputEpoch) {
                                    return@evaluateJavascript
                                }
                                view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) {
                                        val latest = currentState()
                                        if (view !== controller.webView || epoch != controller.inputEpoch ||
                                            readyIdentity != "${latest.contentRevision}:${latest.currentSectionId}" ||
                                            !loadTracker.isSettledOn(latest.currentSectionId)
                                        ) return
                                        view.visibility = View.VISIBLE
                                        publishScrollProgress(this@apply)
                                        latestBodyReady(readyIdentity)
                                    }
                                })
                            }
                        }
                    }
                }
                controller.webView = this
                webViewState = this
                // 新建的 WebView 必须自己装载一次，同时登记「这次装载属于哪个 section」，
                // onPageFinished 才有依据判断回调是否属于当前页面。
                loadTracker.registerLoad(content.sectionId, content.fileUrl)
                doOnLayout {
                    if (this === controller.webView && width > 0 && height > 0 &&
                        loadTracker.isRegisteredFor(content.sectionId, content.fileUrl)) loadUrl(content.fileUrl)
                }
            }
        },
        update = { webView ->
            controller.webView = webView
            webViewState = webView
            val background = colors.background.toArgb()
            if ((webView.background as? android.graphics.drawable.ColorDrawable)?.color != background) {
                webView.setBackgroundColor(background)
            }
        },
        onRelease = { view ->
            if (controller.webView === view) {
                onInvalidatePosition()
                onClearSelection()
                inkSpace = null
                nativeInkScale = null
                latestInkViewport(null)
                controller.webView = null
                webViewState = null
            }
            view.stopLoading()
            view.removeJavascriptInterface("ReaderBridge")
            view.webViewClient = WebViewClient()
            // Compose releases the view before detaching its AndroidView holder.
            // WebView.destroy requires removal from the view hierarchy first.
            if (view.isAttachedToWindow) view.doOnDetach { view.destroy() } else view.destroy()
        },
    )
    LaunchedEffect(webViewState, state.currentSectionId, content.fileUrl) {
        val webView = webViewState ?: return@LaunchedEffect
        // factory 已经装载过同一页时不再重复 loadUrl（否则同一页收到两次
        // onPageFinished、各 seek 一次）；切章时 URL 变了，这里才真正发起新装载。
        if (loadTracker.isRegisteredFor(content.sectionId, content.fileUrl)) return@LaunchedEffect
        onInvalidatePosition()
        inkSpace = null
        nativeInkScale = null
        latestInkViewport(null)
        latestInkPainted(null)
        controller.invalidatePage()
        loadTracker.registerLoad(content.sectionId, content.fileUrl)
        webView.visibility = View.INVISIBLE
        webView.loadUrl(content.fileUrl)
    }
}

private class ReaderScrollWebView(context: Context) : WebView(context) {
    fun scrollProgress(): Float {
        val distance = computeVerticalScrollRange() - computeVerticalScrollExtent()
        return if (distance > 0) (scrollY.toFloat() / distance).coerceIn(0f, 1f) else 0f
    }
}

private fun JsonObject.toInkScrollContainer(): InkScrollContainer? {
    fun number(name: String) = (this[name] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() }
    fun size(name: String) = number(name)?.takeIf { it > 0f }
    val key = (this["key"] as? JsonPrimitive)?.contentOrNull?.takeIf {
        it.length in 1..192 && it.all { char -> char.isDigit() || char == '.' }
    } ?: return null
    return InkScrollContainer(
        key = key,
        leftCss = number("left") ?: return null,
        topCss = number("top") ?: return null,
        viewportWidthCss = size("width") ?: return null,
        viewportHeightCss = size("height") ?: return null,
        contentWidthCss = size("contentWidth") ?: return null,
        contentHeightCss = size("contentHeight") ?: return null,
        scrollXCss = number("scrollX") ?: return null,
        scrollYCss = number("scrollY") ?: return null,
    )
}

/**
 * 跟踪 WebView 的装载代次：记录「最近一次 loadUrl 请求针对哪个 section / 哪个 URL」，
 * 以及这次请求是否已经收到 onPageFinished。
 *
 * 存在的理由：WebViewClient 属于 [AndroidView] factory 的闭包，而 factory 只在首次组合时
 * 执行一次；回调里若直接读 Compose state，切章/旋转后就会拿过期的 sectionId 与进度去
 * seek/decorate，并把错误位置写回库。这里把「这次加载是谁」放在组合之外的可变对象里，
 * 回调只按最新请求判定，无法归属当前页面的回调直接丢弃。
 */
private class ReaderWebLoadTracker {
    // 写入都发生在主线程（组合 / onPageFinished），读取可能来自 JS 桥线程（进度回报），
    // 因此用 @Volatile 保证跨线程可见性。
    @Volatile
    private var requestedSectionId: String? = null

    @Volatile
    private var requestedUrl: String? = null

    /** 当前页面是否已确认装载完成（完成后才接收进度回报）。 */
    @Volatile
    private var settled: Boolean = false

    /**
     * 登记一次装载请求。同一 section 同一 URL 的重复登记视为同一次装载，
     * 不重置「已完成」状态（否则重复的 onPageFinished 会再 seek 一次，位置跳一下）。
     */
    fun registerLoad(sectionId: String?, url: String) {
        val key = canonicalReaderPageUrl(url) ?: return
        if (key == requestedUrl && sectionId == requestedSectionId) return
        requestedUrl = key
        requestedSectionId = sectionId
        settled = false
    }

    /** 当前登记的装载请求是否就是这两个参数（用于避免同一页重复 loadUrl）。 */
    fun isRegisteredFor(sectionId: String?, url: String): Boolean {
        val key = canonicalReaderPageUrl(url) ?: return false
        return key == requestedUrl && sectionId == requestedSectionId
    }

    /**
     * 判定这次 onPageFinished 是否属于「当前 section 的页面」：要求请求的 section 仍是
     * state 里的当前 section、state 里的当前 fileUrl 与请求一致，且回调 URL 就是请求的
     * URL。任一不满足都说明回调已经过期，不能拿它去写 bridge 或 seek。
     */
    fun isCurrentPage(actualUrl: String?, currentSectionId: String?, currentFileUrl: String?): Boolean {
        val requested = requestedUrl ?: return false
        if (requestedSectionId != currentSectionId) return false
        if (canonicalReaderPageUrl(currentFileUrl) != requested) return false
        if (canonicalReaderPageUrl(actualUrl) != requested) return false
        settled = true
        return true
    }

    /** 当前页是否已经确认落在 [sectionId] 上（未完成前不接收进度回报）。 */
    fun isSettledOn(sectionId: String?): Boolean {
        val requestedSection = requestedSectionId ?: return false
        return settled && requestedSection == sectionId
    }
}

/**
 * 归一化本地阅读页 URL 后再比较：WebView 会把空格/中文/特殊字符做百分号编码，
 * 直接字符串比较会把有效回调误判成「不是同一页」而丢弃。
 * 与 EpubDocumentParser 的 resolvePath 一致，先把 '+' 转义，
 * 避免 URLDecoder 把路径里的 '+' 当成空格。
 */
internal fun canonicalReaderPageUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // Strip actual URI query/fragment before decoding. An encoded %23 is part of the filename.
    val resource = value.substringBefore('#').substringBefore('?')
    val decoded = runCatching {
        java.net.URLDecoder.decode(resource.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(resource)
    return decoded.replace('\\', '/').trimEnd('/')
}


@Composable
private fun PdfReaderContent(
    state: ReaderUiState,
    appearanceColors: ReaderAppearanceColors,
    controller: ReaderPagerController,
    onPageChanged: (Int) -> Unit,
    maxWidth: androidx.compose.ui.unit.Dp,
    onViewportTap: (ReaderTapAction) -> Unit,
    currentState: () -> ReaderUiState,
    legacyCanvasHeight: Float,
    onInkStroke: (InkStroke) -> Unit,
    onInkEraseMatching: ((InkStroke) -> Boolean) -> Unit,
    onPageReady: (Int) -> Unit = {},
) {
    val pageStart = state.allowedPageStart.coerceIn(0, state.totalPages.coerceAtLeast(1) - 1)
    val pageEnd = (state.allowedPageEnd ?: (state.totalPages - 1)).coerceIn(pageStart, state.totalPages.coerceAtLeast(1) - 1)
    val pageCount = pageEnd - pageStart + 1
    val pagerState = rememberPagerState(initialPage = (state.currentPage - pageStart).coerceIn(0, pageCount - 1)) { pageCount }
    val scope = rememberCoroutineScope()
    val rendered = remember(state.documentId, state.contentRevision, state.sectionContent?.pdfFile) { mutableStateMapOf<Int, Boolean>() }
    val latestCurrentState by rememberUpdatedState(currentState)
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    val latestOnPageReady by rememberUpdatedState(onPageReady)
    LaunchedEffect(state.currentPage, pageCount, pageStart) {
        if (pagerState.currentPage != (state.currentPage - pageStart).coerceIn(0, pageCount - 1)) {
            pagerState.scrollToPage((state.currentPage - pageStart).coerceIn(0, pageCount - 1))
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { latestOnPageChanged(it + pageStart) }
    }
    controller.prev = { scope.launch(Dispatchers.Main.immediate) { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) } }
    controller.next = { scope.launch(Dispatchers.Main.immediate) { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(pageCount - 1)) } }
    controller.boundary = { direction ->
        if (rendered[pagerState.currentPage + pageStart] != true || pagerState.isScrollInProgress || kotlin.math.abs(pagerState.currentPageOffsetFraction) > .001f) null
        else (pagerState.currentPage + pageStart) to (if (direction == ReaderTurnDirection.NEXT) pagerState.currentPage == pageCount - 1 else pagerState.currentPage == 0)
    }
    DisposableEffect(controller, pagerState) { onDispose { controller.boundary = { null }; controller.prev = {}; controller.next = {} } }
    LaunchedEffect(pagerState.settledPage, rendered[pagerState.settledPage + pageStart], pageStart) {
        val physical = pagerState.settledPage + pageStart
        if (rendered[physical] == true && !pagerState.isScrollInProgress) latestOnPageReady(physical)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        HorizontalPager(
            state = pagerState,
            key = { "${state.documentId}:${state.contentRevision}:${it + pageStart}" },
            // Drawing owns single-pointer drags; arrows and two-finger zoom remain available.
            userScrollEnabled = !state.inkMode,
            modifier = Modifier.widthIn(max = maxWidth).fillMaxSize().readerViewportInput(
                blocked = state.inkMode || state.selection != null || state.isLoading || state.error != null,
                systemEdgePx = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() },
                onTap = onViewportTap,
            ),
        ) { page ->
            val physical = page + pageStart
            val inkSessionPage = state.inkMode && state.currentPage == physical
            fun ownsPageInk(): Boolean {
                val current = latestCurrentState()
                return current.inkMode && !current.isLoading && current.error == null && !current.historyReadOnly &&
                    current.documentId == state.documentId && current.contentRevision == state.contentRevision &&
                    current.currentPage == physical && pagerState.currentPage + pageStart == physical &&
                    !pagerState.isScrollInProgress && kotlin.math.abs(pagerState.currentPageOffsetFraction) <= .001f &&
                    rendered[physical] == true
            }
            PdfPageView(
                pdfFile = state.sectionContent?.pdfFile,
                pageIndex = physical,
                colors = appearanceColors,
                onRenderChanged = { rendered[physical] = it },
                inkStrokes = if (inkSessionPage) state.inkStrokes else state.pdfInkStrokes[physical].orEmpty(),
                inkTool = state.inkTool,
                inkColor = state.inkColor,
                inkWidth = state.inkWidth,
                inkSessionPage = inkSessionPage,
                inkEditable = ownsPageInk(),
                legacyCanvasHeight = legacyCanvasHeight,
                canEditInk = ::ownsPageInk,
                onInkStroke = { if (ownsPageInk()) onInkStroke(it) },
                onInkEraseMatching = { if (ownsPageInk()) onInkEraseMatching(it) },
            )
        }
    }
}

private class ReaderPagerController {
    var prev: () -> Unit = {}
    var next: () -> Unit = {}
    var boundary: (ReaderTurnDirection) -> Pair<Int, Boolean>? = { null }
}

/** 单页渲染结果：区分「仍在渲染」与「渲染失败/已降级」，避免失败页永远转圈。 */
private sealed interface PdfPageRender {
    data object Loading : PdfPageRender
    data class Ready(val frame: OwnedPdfFrame) : PdfPageRender
    data object Failed : PdfPageRender
}

@Composable
private fun PdfPageView(
    pdfFile: File?,
    pageIndex: Int,
    colors: ReaderAppearanceColors,
    onRenderChanged: (Boolean) -> Unit = {},
    inkStrokes: List<InkStroke> = emptyList(),
    inkTool: InkTool = InkTool.PEN,
    inkColor: String = "#2F3B36",
    inkWidth: Float = .006f,
    inkSessionPage: Boolean = false,
    inkEditable: Boolean = false,
    legacyCanvasHeight: Float = 0f,
    canEditInk: () -> Boolean = { false },
    onInkStroke: (InkStroke) -> Unit = {},
    onInkEraseMatching: ((InkStroke) -> Boolean) -> Unit = {},
) {
    val zoom = remember(pdfFile, pageIndex) { PdfZoomState() }
    var pageSize by remember(pdfFile, pageIndex) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    if (pdfFile == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("PDF 文件不可用") }
        return
    }
    val render by produceState<PdfPageRender>(initialValue = PdfPageRender.Loading, pdfFile, pageIndex) {
        var frame: OwnedPdfFrame? = null
        try {
            value = PdfPageRender.Loading
            frame = loadOwnedPdfFrame { renderPdfPage(pdfFile, pageIndex) }
            value = frame?.let { PdfPageRender.Ready(it) } ?: PdfPageRender.Failed
            awaitDispose { frame?.retire() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            // Wrapper/lease preparation is outside renderPdfPage's rendering catch.
            android.util.Log.w("ReaderPdf", "prepare page $pageIndex frame OOM, degrade to placeholder", oom)
            value = PdfPageRender.Failed
        } finally {
            // Also covers cancellation between completed IO and publishing/awaitDispose.
            frame?.retire()
        }
    }
    val displayedFrame = rememberPdfFrame((render as? PdfPageRender.Ready)?.frame)
    val latestRenderChanged by rememberUpdatedState(onRenderChanged)
    DisposableEffect(pdfFile, pageIndex) { latestRenderChanged(false); onDispose { latestRenderChanged(false) } }
    LaunchedEffect(render, displayedFrame, pdfFile, pageIndex) {
        latestRenderChanged(render is PdfPageRender.Ready && displayedFrame?.bitmap != null)
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { pageSize = it }
            .clipToBounds()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        when (render) {
            PdfPageRender.Loading -> CircularProgressIndicator(color = ReaderTokens.Primary)
            PdfPageRender.Failed -> Text(
                "第 ${pageIndex + 1} 页渲染失败，可左右滑动重试",
                color = colors.foreground,
                style = MaterialTheme.typography.bodyMedium,
            )
            is PdfPageRender.Ready -> displayedFrame?.bitmap?.let { bitmap ->
                PdfZoomContent(bitmap = bitmap, description = "第 ${pageIndex + 1} 页",
                    state = zoom, modifier = Modifier.fillMaxSize(), contentPadding = 8.dp,
                    allowSingleFingerPan = !inkSessionPage) {
                    if (inkSessionPage || inkStrokes.isNotEmpty()) {
                        InkOverlay(
                            strokes = inkStrokes,
                            tool = inkTool,
                            color = inkColor,
                            width = inkWidth,
                            onStroke = onInkStroke,
                            onEraseMatching = onInkEraseMatching,
                            editable = inkEditable,
                            fixedPageSpace = {
                                if (pageSize.width <= 0 || pageSize.height <= 0) null else PdfInkCanvasSpace(
                                    pageSize.width.toFloat(),
                                    legacyCanvasHeight.takeIf { it > 0f } ?: pageSize.height.toFloat(),
                                    pageSize.height.toFloat(),
                                )
                            },
                            canEdit = canEditInk,
                            modifier = Modifier.testTag(if (inkSessionPage) "reader-ink-overlay" else "reader-ink-display"),
                        )
                    }
                }
            }
        }
    }
}

private fun annotationLabel(annotation: ReaderAnnotationView): String = when (annotation.kind) {
    AnnotationKind.HIGHLIGHT -> annotation.quote?.take(80) ?: "高亮"
    AnnotationKind.NOTE -> annotation.noteText?.take(80) ?: "附注"
    AnnotationKind.INK -> "手写标注"
}

/** PDF 渲染位图最长边上限（像素）。2048 已高于主流手机屏宽，足以保留清晰度；
 *  再大只会成倍占用内存：A2/A0 图纸按 2 倍渲染单页就要 100 MB 以上，必然 OOM。 */
private const val PDF_MAX_BITMAP_EDGE = 2048

/** 正常清晰度倍数：页面尺寸不超上限时仍按 2 倍渲染，保持既有观感不变。 */
private const val PDF_RENDER_SCALE = 2

/**
 * 按「最长边不超过 [maxEdge]」等比计算 PDF 页面的渲染位图尺寸。
 *
 * 先尝试 [PDF_RENDER_SCALE] 倍（清晰度优先），只有放不下时才等比缩小到上限，
 * 因此小页面观感与旧实现完全一致，大页面不再一次分配超大位图。
 * 抽成纯函数便于 JVM 单测覆盖 0/负数/超大页等边界。
 */
internal fun fitPdfBitmapSize(
    srcWidth: Int,
    srcHeight: Int,
    maxEdge: Int = PDF_MAX_BITMAP_EDGE,
): Pair<Int, Int> {
    if (srcWidth <= 0 || srcHeight <= 0) return 1 to 1
    val limit = maxEdge.coerceAtLeast(1)
    val scaledWidth = srcWidth.toLong() * PDF_RENDER_SCALE
    val scaledHeight = srcHeight.toLong() * PDF_RENDER_SCALE
    val longest = maxOf(scaledWidth, scaledHeight)
    if (longest <= limit) return scaledWidth.toInt() to scaledHeight.toInt()
    // 整数运算 + 四舍五入：长边恰好落到 limit，短边等比且至少 1 像素。
    val width = ((scaledWidth * limit + longest / 2) / longest).toInt().coerceIn(1, limit)
    val height = ((scaledHeight * limit + longest / 2) / longest).toInt().coerceIn(1, limit)
    return width to height
}

private fun renderPdfPage(file: File, pageIndex: Int): Bitmap? {
    var allocated: Bitmap? = null
    var delivered = false
    return try {
        val bitmap = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    val (width, height) = fitPdfBitmapSize(page.width, page.height)
                    renderPdfBitmap({ Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { allocated = it } }) {
                        it.eraseColor(android.graphics.Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
        delivered = true
        bitmap
    } catch (oom: OutOfMemoryError) {
        // OOM 是 Error，旧实现的 catch (Exception) 接不住，会直接把 App 带崩；
        // 这里降级为占位页（PdfPageView 收到 null 显示「渲染失败」），阅读器其余功能继续可用。
        android.util.Log.w("ReaderPdf", "render page $pageIndex OOM, degrade to placeholder", oom)
        null
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        // 页码快速滑动导致 Page 被取消：按协程约定向上传播，不当作渲染失败。
        throw cancelled
    } catch (error: Throwable) {
        // 其它异常（损坏页、文件被回收）同样降级占位，不让单页问题拖垮整个阅读界面。
        android.util.Log.w("ReaderPdf", "render page $pageIndex failed: ${error.javaClass.simpleName}", error)
        null
    } finally {
        if (!delivered) allocated?.takeIf { !it.isRecycled }?.recycle()
    }
}


