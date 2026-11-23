package com.example.myapplication.ui.knowledge.reader

import android.graphics.Bitmap
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.myapplication.domain.reader.ReaderTurnDirection
import com.example.myapplication.domain.reader.ReaderTurnOrigin
import com.example.myapplication.data.reader.notes.AnnotationKind
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiActionGroup
import com.example.myapplication.ui.components.UiConfirmDialog
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.UiPane
import com.example.myapplication.ui.components.UiPaneRole
import com.example.myapplication.ui.components.UiSettingsSwitch
import com.example.myapplication.ui.components.UiSheet
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.LocalAppDarkTheme
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdfReaderRuntimeScreen(viewModel: PdfReaderRuntimeViewModel, onExited: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val globalDark = LocalAppDarkTheme.current
    LaunchedEffect(globalDark) { viewModel.setGlobalDarkTheme(globalDark) }
    val effectiveTheme = if (state.settingsFollowsGlobalTheme) {
        if (globalDark) ReaderTheme.NIGHT else ReaderTheme.LIGHT
    } else state.settings.theme
    val effectiveSettings = if (effectiveTheme != state.settings.theme) state.settings.copy(theme = effectiveTheme) else state.settings
    val displayedFrame = rememberPdfFrame(state.ownedFrame)
    val displayedBitmap = displayedFrame?.bitmap
    com.example.myapplication.ui.knowledge.TrackCourseReadingActivity(
        activityKey = "pdf:${state.frameToken?.sessionId}",
        readingReady = displayedBitmap != null && !state.loading && !state.closed && state.error == null,
    )
    val creation by viewModel.annotationCreationState.collectAsState()
    val existing by viewModel.existingAnnotationState.collectAsState()
    val bookmark by viewModel.bookmarkState.collectAsState()
    var annotationsOpen by rememberSaveable { mutableStateOf(false) }
    // Delivery belongs to this composition; a recreated Activity must receive finish again.
    var exitDelivered by remember(viewModel) { mutableStateOf(false) }
    val latestOnExited by rememberUpdatedState(onExited)
    val requestBack = unifiedBackRequest(BackState(page = BackPage.READER, imeVisible = isBackImeVisible())) { decision ->
        if (decision == BackDecision.SaveReadingPositionAndLeave) {
            if(state.selection!=null)viewModel.clearTextSelection() else viewModel.requestLeave()
        }
    }
    LaunchedEffect(state.closed, state.cleanupFailure, state.busy) {
        if (state.closed && !state.busy && state.cleanupFailure == null && !exitDelivered) {
            exitDelivered = true
            latestOnExited()
        }
    }
    Box(Modifier.fillMaxSize()) {
        PdfReaderRuntimeScreenContent(
            state.title, displayedBitmap, state.pageIndex + 1, state.pageCount,
            state.loading || state.busy, state.closed, state.readOnly, state.error,
            state.saveFailure?.code, state.cleanupFailure,
            viewModel::previous, viewModel::next, requestBack, viewModel::retrySave, viewModel::retryCleanup,
            viewModel::turn, viewModel::retryOpen, installBackHandler = false,
            settings = effectiveSettings,
            onSettings = viewModel::updateReadingOptions,
            onSelectTheme = viewModel::selectTheme,
            followsGlobalTheme = state.settingsFollowsGlobalTheme,
            onUseDefaults = viewModel::useDefaultSettings, onMakeDefaults = viewModel::makeDefaultSettings,
            onRetrySettings = viewModel::retrySettingsSave, settingsPending = state.settingsPending,
            settingsSaving = state.settingsSaving, settingsError = state.settingsFailure,
            settingsOutcomeUnknown = state.settingsOutcomeUnknown, settingsRetryable = state.settingsRetryable,
            settingsInheritsDefaults = state.settingsInheritsDefaults,
            settingsAbandoned = state.settingsAbandoned, settingsLeaveConfirmation = state.settingsLeaveConfirmation,
            onConfirmAbandonSettingsAndLeave = viewModel::confirmAbandonSettingsAndLeave,
            onDismissSettingsLeaveConfirmation = viewModel::dismissSettingsLeaveConfirmation,
            saveOutcomeUnknown = state.saveFailure?.outcomeUnknown == true,
            saveRetryable = state.saveFailure?.retryable != false,
            selectionActive=state.selection!=null||state.selectionPending,editorOpen=creation.draft!=null||existing.isOpen,
            pageLayer=if(state.textPages.isEmpty())null else { zoom,modifier -> displayedBitmap?.let { bitmap ->
                PdfTextSelectionLayer(bitmap,displayedFrame?.adjacentBitmap,state.textPages,state.selection,state.decorations,zoom,
                    enabled=!state.loading&&!state.busy&&creation.draft==null&&!existing.isOpen,
                    onSelect={ ranges,id -> state.frameToken?.let { viewModel.selectText(it,state.frameEventSequence,ranges,id) } },
                    modifier=modifier,ownerKey=state.frameToken to state.frameEventSequence)
            } },
            extraToolbar={
                BookmarkToggleButton(
                    bookmark,
                    viewModel::toggleBookmark,
                    viewModel::retryBookmark,
                    viewModel::retryBookmarkObservation,
                    showMessage = false,
                )
                UiAction(
                    label = "标注",
                    onClick = { annotationsOpen = true },
                    enabled = !state.busy && !state.closed && creation.draft == null && !existing.isOpen,
                    actionId = "pdf.annotations",
                    modifier = Modifier.testTag("pdf-annotations"),
                )
            },
        )
        if (state.selection != null && creation.draft == null && !existing.isOpen) {
            UiPane(
                role = UiPaneRole.Banner,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 160.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "长按原文选择，拖动手柄；最多跨当前页与后一页。",
                        style = MaterialTheme.typography.bodySmall,
                        color = UiRoles.foreground,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        UiAction(
                            label = "高亮",
                            onClick = { viewModel.beginTextAnnotation(AnnotationKind.HIGHLIGHT) },
                            enabled = !state.readOnly,
                            inheritedPane = true,
                            actionId = "pdf.selection.highlight",
                            modifier = Modifier.testTag("pdf-selection-highlight"),
                        )
                        UiAction(
                            label = "附注",
                            onClick = { viewModel.beginTextAnnotation(AnnotationKind.NOTE) },
                            enabled = !state.readOnly,
                            inheritedPane = true,
                            actionId = "pdf.selection.note",
                            modifier = Modifier.testTag("pdf-selection-note"),
                        )
                        BookmarkEnsureButton(
                            bookmark,
                            viewModel::ensureBookmark,
                            viewModel::retryBookmark,
                            viewModel::retryBookmarkObservation,
                            inheritedPane = true,
                        )
                        UiAction(
                            label = "清除",
                            onClick = viewModel::clearTextSelection,
                            inheritedPane = true,
                            actionId = "pdf.selection.clear",
                            modifier = Modifier.testTag("pdf-selection-clear"),
                        )
                    }
                }
            }
        }
        state.selectionError?.let {
            Text("选区未完成：$it", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
        }
    }
    if (!state.closed) AnnotationCreationSheet(creation, viewModel::editNewAnnotationNote, viewModel::chooseNewAnnotationColor,
        viewModel::saveNewAnnotation, viewModel::retryNewAnnotation, viewModel::closeNewAnnotation)
    if (!state.closed) AnnotationActionSheet(existing, viewModel::editExistingAnnotationNote, viewModel::chooseExistingAnnotationColor,
        viewModel::saveExistingAnnotation, viewModel::deleteExistingAnnotation, viewModel::undoExistingAnnotation,
        viewModel::retryExistingAnnotation, viewModel::reloadExistingAnnotation, viewModel::closeExistingAnnotation)
    if (annotationsOpen) {
        UiSheet(
            title = "文档标注",
            onDismiss = { annotationsOpen = false },
            maxContentFraction = 0.72f,
            actions = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    UiAction(
                        label = "关闭",
                        onClick = { annotationsOpen = false },
                        inheritedPane = true,
                        actionId = "pdf.annotations.close",
                    )
                }
            },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.annotations.isEmpty()) {
                    Text("暂无标注", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.annotations.forEach { row ->
                    UiPane(role = UiPaneRole.Regular, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                row.quote.orEmpty().take(100),
                                style = MaterialTheme.typography.bodySmall,
                                color = UiRoles.foreground,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                UiAction(
                                    label = "定位原文",
                                    onClick = { annotationsOpen = false; viewModel.navigateToAnnotation(row.id) },
                                    enabled = !state.busy && creation.draft == null && !existing.isOpen &&
                                        state.selection == null && row.id !in state.annotationUnmappableIds,
                                    inheritedPane = true,
                                    foreground = UiRoles.accent,
                                    actionId = "pdf.annotation.locate.${row.id}",
                                    modifier = Modifier.testTag("pdf-annotation-locate-${row.id}"),
                                )
                                UiAction(
                                    label = "编辑",
                                    onClick = { annotationsOpen = false; viewModel.openExistingAnnotation(row.id) },
                                    enabled = !state.readOnly && !state.busy && row.id !in state.annotationUnmappableIds,
                                    inheritedPane = true,
                                    actionId = "pdf.annotation.edit.${row.id}",
                                    modifier = Modifier.testTag("pdf-annotation-edit-${row.id}"),
                                )
                            }
                            if (row.id in state.annotationUnmappableIds) {
                                Text(
                                    "原文位置暂不可用",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Stateless frame surface; toolbar overlays never change the measured PDF viewport. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdfReaderRuntimeScreenContent(
    title: String,
    bitmap: Bitmap?,
    pageNumber: Int,
    pageCount: Int,
    loading: Boolean,
    closed: Boolean,
    readOnly: Boolean,
    error: String?,
    saveError: String?,
    cleanupError: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onRetrySave: () -> Unit,
    onRetryCleanup: () -> Unit,
    onTurn: (ReaderTurnDirection, ReaderTurnOrigin) -> Unit = { direction, _ ->
        if (direction == ReaderTurnDirection.PREVIOUS) onPrevious() else onNext()
    },
    onRetryOpen: () -> Unit = {},
    installBackHandler: Boolean = true,
    settings: ReaderSettings = ReaderSettings(),
    onSettings: (ReaderSettings) -> Unit = {},
    onUseDefaults: () -> Unit = {},
    onMakeDefaults: () -> Unit = {},
    onRetrySettings: () -> Unit = {},
    settingsPending: Boolean = false,
    settingsSaving: Boolean = false,
    settingsError: String? = null,
    settingsOutcomeUnknown: Boolean = false,
    settingsRetryable: Boolean = true,
    settingsInheritsDefaults: Boolean = true,
    saveOutcomeUnknown: Boolean = false,
    saveRetryable: Boolean = true,
    settingsAbandoned: Boolean = false,
    settingsLeaveConfirmation: Boolean = false,
    onConfirmAbandonSettingsAndLeave: () -> Unit = {},
    onDismissSettingsLeaveConfirmation: () -> Unit = {},
    selectionActive: Boolean = false,
    editorOpen: Boolean = false,
    pageLayer: (@Composable (PdfZoomState,Modifier) -> Unit)? = null,
    extraToolbar: @Composable RowScope.() -> Unit = {},
    followsGlobalTheme: Boolean = false,
    onSelectTheme: ((ReaderTheme) -> Unit)? = null,
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var gestureActive by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val window = remember(context) { pdfReaderWindow(context) }
    val readerKey = remember { Any() }
    val accessibilityActive = rememberPdfAccessibility(context)
    val conditions = ReaderChromeConditions(settings.immersivePreference && window != null,
        readerVisible = !closed, imeVisible = isBackImeVisible(), accessibilityActive = accessibilityActive,
        selectionActive=selectionActive,editorOpen=editorOpen,
        overlayOpen = settingsOpen || settingsLeaveConfirmation || gestureActive || loading || error != null || saveError != null || settingsError != null)
    val chrome = if (window != null) rememberReaderPresentationBinding(window, readerKey, conditions)
        else rememberReaderChromeController(readerKey, conditions)
    val chromeVisible = chrome.isVisible
    val zoom = remember(pageNumber) { PdfZoomState() }
    val backdrop = rememberUiBackdrop()
    val pageColors = readerAppearanceColors(settings, followsGlobalTheme)
    val pageBackdropColor = pageColors.background
    val pageTextFallbackColor = pageColors.foreground
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val systemEdgePx = maxOf(with(density) { 24.dp.toPx() },
        WindowInsets.systemGestures.getLeft(density, layoutDirection).toFloat(),
        WindowInsets.systemGestures.getRight(density, layoutDirection).toFloat())
    val canTurn = !loading && !closed && bitmap != null && !bitmap.isRecycled && !selectionActive && !editorOpen
    if (settingsOpen) BackHandler { settingsOpen = false }
    else if (installBackHandler) BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize(), color = pageBackdropColor) {
        Box(Modifier.fillMaxSize().then(
            if (settings.immersivePreference) Modifier.windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
            ) else Modifier.statusBarsPadding().navigationBarsPadding(),
        )) {
            UiGlassBackdrop(
                backdrop = backdrop,
                modifier = Modifier.fillMaxSize(),
                captureEnabled = chromeVisible || settingsOpen || settingsLeaveConfirmation ||
                    error != null || saveError != null || cleanupError != null || settingsError != null,
            ) {
                Box(Modifier.fillMaxSize().clipToBounds().background(pageBackdropColor)
                    .testTag("pdf-viewport")
                    .readerRuntimeViewportInput(
                        blocked = !canTurn || settingsOpen,
                        systemEdgePx = systemEdgePx,
                        onTap = { action ->
                            when (action) {
                                ReaderTapAction.PREVIOUS -> if (zoom.scale == 1f) onTurn(ReaderTurnDirection.PREVIOUS, ReaderTurnOrigin.EDGE_TAP)
                                ReaderTapAction.NEXT -> if (zoom.scale == 1f) onTurn(ReaderTurnDirection.NEXT, ReaderTurnOrigin.EDGE_TAP)
                                ReaderTapAction.TOGGLE_CHROME -> { chrome.updateConditions(conditions.copy(overlayOpen = false)); chrome.toggle() }
                            }
                        },
                        onSwipe = { direction -> if (zoom.scale == 1f) onTurn(direction, ReaderTurnOrigin.SWIPE) },
                        edgeTapEnabled = settings.edgeTap,
                        onGestureActive = { active ->
                            gestureActive = active
                            chrome.updateConditions(conditions.copy(overlayOpen = active || settingsOpen || loading || error != null))
                            chrome.onInteraction()
                        },
                    ).padding(horizontal = 12.dp, vertical = if (settings.immersivePreference) 0.dp else 12.dp),
                    contentAlignment = Alignment.Center) {
                    when {
                        closed -> Text("阅读已关闭", Modifier.testTag("pdf-closed"), color = pageTextFallbackColor)
                        bitmap != null && !bitmap.isRecycled -> if(pageLayer!=null)pageLayer(zoom,Modifier.fillMaxSize().testTag("pdf-page"))
                            else PdfZoomContent(bitmap, "PDF 第 $pageNumber 页", zoom,Modifier.fillMaxSize().testTag("pdf-page"))
                        error != null -> Text("无法加载：$error", Modifier.testTag("pdf-error"), color = MaterialTheme.colorScheme.error)
                        loading -> CircularProgressIndicator(Modifier.testTag("pdf-loading"))
                        else -> Text("尚无可显示页面", Modifier.testTag("pdf-empty"), color = pageTextFallbackColor)
                    }
                    if (loading && bitmap != null && !closed && error == null) CircularProgressIndicator(
                        Modifier.align(Alignment.TopEnd).size(24.dp).testTag("pdf-busy"), strokeWidth = 2.dp)
                }
            }
            if (chromeVisible) {
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(UiRoles.surface(UiSurfaceRole.Chrome))) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UiAction(
                            label = "返回",
                            icon = UiIcons.Back,
                            onClick = onBack,
                            backdrop = backdrop,
                            actionId = "pdf.back",
                            modifier = Modifier.testTag("pdf-back"),
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(
                                title.ifBlank { "PDF 阅读" },
                                style = UiRoles.text(UiRoles.pageTitle),
                                color = UiRoles.foreground,
                                maxLines = 2,
                            )
                            if (readOnly) {
                                Text(
                                    "历史版本 · 只读",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            extraToolbar()
                            UiAction(
                                label = "设置",
                                icon = UiIcons.Settings,
                                onClick = { settingsOpen = true; chrome.show() },
                                enabled = !closed && !selectionActive && !editorOpen,
                                backdrop = backdrop,
                                actionId = "pdf.settings",
                                modifier = Modifier.testTag("pdf-settings"),
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                if (settingsError != null || settingsOutcomeUnknown) {
                    UiPane(
                        role = UiPaneRole.Banner,
                        shape = RoundedCornerShape(16.dp),
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (settingsOutcomeUnknown) "偏好保存结果待确认" else "偏好尚未保存",
                                Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            UiAction(
                                label = "查看偏好",
                                onClick = { settingsOpen = true; chrome.show() },
                                enabled = !closed,
                                inheritedPane = true,
                                foreground = UiRoles.accent,
                                actionId = "pdf.settings.banner",
                            )
                        }
                    }
                }
                if (error != null && !closed) {
                    UiPane(
                        role = UiPaneRole.Banner,
                        shape = RoundedCornerShape(16.dp),
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UiAction(
                                label = "重新打开",
                                onClick = onRetryOpen,
                                enabled = !loading,
                                inheritedPane = true,
                                foreground = UiRoles.accent,
                                actionId = "pdf.reopen",
                                modifier = Modifier.testTag("pdf-reopen"),
                            )
                        }
                    }
                }
                if (error != null && bitmap != null && !closed) {
                    UiPane(
                        role = UiPaneRole.Banner,
                        shape = RoundedCornerShape(16.dp),
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "操作未完成：$error，可重试翻页或返回。",
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).testTag("pdf-turn-error"),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (saveError != null) {
                    UiPane(
                        role = UiPaneRole.Banner,
                        shape = RoundedCornerShape(16.dp),
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (saveOutcomeUnknown) "保存结果待确认：$saveError" else "进度未保存：$saveError",
                                Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            UiAction(
                                label = if (saveOutcomeUnknown) "确认保存结果" else "重试保存",
                                onClick = onRetrySave,
                                enabled = !loading && !closed && saveRetryable,
                                inheritedPane = true,
                                foreground = UiRoles.accent,
                                actionId = "pdf.save.retry",
                            )
                        }
                    }
                }
                if (cleanupError != null) {
                    UiPane(
                        role = UiPaneRole.Banner,
                        shape = RoundedCornerShape(16.dp),
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "关闭未完成：$cleanupError",
                                Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            UiAction(
                                label = "重试关闭",
                                onClick = onRetryCleanup,
                                enabled = !loading,
                                inheritedPane = true,
                                foreground = UiRoles.accent,
                                actionId = "pdf.cleanup.retry",
                            )
                        }
                    }
                }
                if (chromeVisible) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(UiRoles.surface(UiSurfaceRole.Chrome))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        UiActionGroup(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            backdrop = backdrop,
                        ) {
                            UiAction(
                                label = "缩小",
                                onClick = { zoom.zoomOut(); chrome.onInteraction() },
                                enabled = canTurn && zoom.scale > PdfZoomState.MIN_SCALE,
                                actionId = "pdf.zoom.out",
                                modifier = Modifier.testTag("pdf-zoom-out"),
                            )
                            UiAction(
                                label = "${(zoom.scale * 100).roundToInt()}% · 适合页面",
                                onClick = { zoom.reset(); chrome.onInteraction() },
                                enabled = canTurn,
                                actionId = "pdf.zoom.fit",
                                modifier = Modifier.testTag("pdf-zoom-fit"),
                            )
                            UiAction(
                                label = "放大",
                                onClick = { zoom.zoomIn(); chrome.onInteraction() },
                                enabled = canTurn && zoom.scale < PdfZoomState.MAX_SCALE,
                                actionId = "pdf.zoom.in",
                                modifier = Modifier.testTag("pdf-zoom-in"),
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UiAction(
                                label = "上一页",
                                onClick = { onTurn(ReaderTurnDirection.PREVIOUS, ReaderTurnOrigin.BOTTOM_BUTTON) },
                                enabled = canTurn && pageNumber > 1,
                                backdrop = backdrop,
                                actionId = "pdf.previous",
                                modifier = Modifier.testTag("pdf-previous"),
                            )
                            if (settings.pageIndicator) {
                                Text(
                                    if (bitmap != null && !closed) "$pageNumber / $pageCount" else "— / —",
                                    Modifier.testTag("pdf-page-number"),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = UiRoles.foreground,
                                )
                            }
                            UiAction(
                                label = "下一页",
                                onClick = { onTurn(ReaderTurnDirection.NEXT, ReaderTurnOrigin.BOTTOM_BUTTON) },
                                enabled = canTurn && pageNumber < pageCount,
                                backdrop = backdrop,
                                filled = true,
                                actionId = "pdf.next",
                                modifier = Modifier.testTag("pdf-next"),
                            )
                        }
                    }
                }
            }
        }
    }
    if (settingsOpen && !settingsLeaveConfirmation) {
        val canEditSettings = !readOnly && !closed
        UiSheet(
            title = "PDF 阅读设置",
            onDismiss = { settingsOpen = false },
            maxContentFraction = 0.78f,
            actions = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    UiAction(
                        label = "完成",
                        onClick = { settingsOpen = false },
                        filled = true,
                        actionId = "pdf.settings.done",
                    )
                }
            },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "原版 PDF 不支持修改字体、字号、行距或字色。双指缩放或使用底部缩放按钮。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "长按原页文字选择，拖动两端手柄；支持当前页与后一页的跨页标注。扫描页或无法证明坐标的文字不可选择。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val onPrimaryColor = UiRoles.color(UiColorRole.OnPrimary)
                val foregroundColor = UiRoles.foreground
                FlowRow(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ReaderTheme.entries.forEach { theme ->
                        val selected = settings.theme == theme
                        val contentColor = if (selected) onPrimaryColor else foregroundColor
                        UiPane(
                            role = if (selected) UiPaneRole.Tinted else UiPaneRole.Regular,
                            shape = CircleShape,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .alpha(if (canEditSettings) 1f else 0.45f)
                                .selectable(
                                    selected = selected,
                                    enabled = canEditSettings,
                                    role = Role.RadioButton,
                                    onClick = { onSelectTheme?.invoke(theme) ?: onSettings(settings.copy(theme = theme)) },
                                ),
                        ) {
                            Row(
                                modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (selected) {
                                    Icon(UiIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = contentColor)
                                }
                                Text(
                                    when (theme) { ReaderTheme.LIGHT -> "白色背景"; ReaderTheme.SEPIA -> "护眼背景"; ReaderTheme.NIGHT -> "夜间背景" },
                                    style = UiRoles.text(UiRoles.actionText),
                                    color = contentColor,
                                )
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    UiSettingsSwitch(
                        title = "点击两侧翻页",
                        checked = settings.edgeTap,
                        enabled = canEditSettings,
                        onCheckedChange = { onSettings(settings.copy(edgeTap = it)) },
                    )
                    UiSettingsSwitch(
                        title = "显示阅读位置",
                        checked = settings.pageIndicator,
                        enabled = canEditSettings,
                        onCheckedChange = { onSettings(settings.copy(pageIndicator = it)) },
                    )
                    UiSettingsSwitch(
                        title = "沉浸阅读",
                        checked = settings.immersivePreference,
                        enabled = canEditSettings && window != null,
                        onCheckedChange = { onSettings(settings.copy(immersivePreference = it)) },
                    )
                }
                Text(
                    if (settingsInheritsDefaults) "继承全局默认偏好" else "使用此文档的偏好",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    UiAction(
                        label = "设为全局默认",
                        onClick = onMakeDefaults,
                        enabled = canEditSettings && !settingsSaving,
                        inheritedPane = true,
                        foreground = UiRoles.accent,
                        actionId = "pdf.settings.makeDefaults",
                    )
                    UiAction(
                        label = "恢复继承",
                        onClick = onUseDefaults,
                        enabled = canEditSettings && !settingsSaving,
                        inheritedPane = true,
                        actionId = "pdf.settings.useDefaults",
                    )
                }
                if (settingsError != null || settingsOutcomeUnknown) {
                    Text(
                        if (settingsOutcomeUnknown) "保存结果待确认，修改已保留" else "偏好未保存，修改已保留",
                        color = MaterialTheme.colorScheme.error,
                    )
                    UiAction(
                        label = "重试保存",
                        onClick = onRetrySettings,
                        enabled = settingsRetryable && !settingsSaving && !closed,
                        inheritedPane = true,
                        foreground = UiRoles.accent,
                        actionId = "pdf.settings.retry",
                    )
                } else if (settingsPending) {
                    Text(
                        if (settingsSaving) "正在保存偏好" else "等待保存偏好",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (settingsAbandoned) {
                    Text(
                        "已停止重试，已保存的偏好不会回滚。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (settingsLeaveConfirmation) {
        UiConfirmDialog(
            title = "停止重试偏好并退出？",
            message = "偏好可能已经保存。此操作只停止重试并放弃未确认状态，不会回滚已保存内容。阅读进度仍会正常保存。",
            confirmLabel = "停止重试并退出",
            dismissLabel = "继续阅读",
            onConfirm = onConfirmAbandonSettingsAndLeave,
            onDismiss = onDismissSettingsLeaveConfirmation,
            canConfirm = !loading,
            destructive = true,
            ownerKey = "pdf-settings-leave",
            actionId = "pdf.settings.leave",
        )
    }
}

private fun pdfReaderWindow(context: Context): android.view.Window? {
    var current = context
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
    while (seen.add(current)) {
        if (current is Activity) return current.window
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

@Composable
private fun rememberPdfAccessibility(context: Context): Boolean {
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var enabled by remember(manager) { mutableStateOf(manager?.isEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { enabled = it }
        manager?.addAccessibilityStateChangeListener(listener)
        onDispose { manager?.removeAccessibilityStateChangeListener(listener) }
    }
    return enabled
}
