package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.notes.AnnotationKind

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import com.example.myapplication.domain.reader.ReaderFlowMode
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderPlacement
import com.example.myapplication.domain.reader.ReaderTurnDirection
import com.example.myapplication.domain.reader.ReaderTurnOrigin
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackLayer
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackSelection
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Explicit isolated route: staged views stay attached and measured until commit or cleanup. */
@Composable
fun ReflowReaderRuntimeScreen(
    model: ReflowReaderRuntimeViewModel,
    host: WebViewReflowRuntimeHost,
    onExited: () -> Unit,
) {
    val state by model.state.collectAsState()
    val presentation by host.state.collectAsState()
    com.example.myapplication.ui.knowledge.TrackCourseReadingActivity(
        activityKey = "reflow:${state.title}",
        readingReady = presentation.activeSurfaceId != null && !state.loading && !state.closed && state.error == null,
    )
    val selection by model.selectionState.collectAsState()
    val annotation by model.annotationState.collectAsState()
    val existingAnnotation by model.existingAnnotationState.collectAsState()
    val bookmark by model.bookmarkState.collectAsState()
    val selectionScope = rememberCoroutineScope()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var annotationsOpen by rememberSaveable { mutableStateOf(false) }
    var annotationOpening by remember(model) { mutableStateOf(false) }
    var annotationClosing by remember(model) { mutableStateOf(false) }
    var protectAnnotationId by remember(model) { mutableStateOf<String?>(null) }
    var annotationActionError by remember(model, annotation.draft?.draftId) { mutableStateOf<String?>(null) }
    var selectionAction by remember(model) { mutableStateOf<ReflowSelectionToolbarUiAction?>(null) }
    var selectionJob by remember(model) { mutableStateOf<Job?>(null) }
    var dismissedCapture by remember(model) { mutableStateOf<ReflowSelectionCapture?>(null) }
    var topChromeHeightPx by remember { mutableIntStateOf(0) }
    val selectionOwner = state.selectionToolbarOwner()
    val selectionToolbar = projectReflowSelectionToolbar(selection, selectionOwner, selectionAction, dismissedCapture)
        .forAnnotationOpening(annotationOpening)

    fun currentSelectionToolbar() = projectReflowSelectionToolbar(model.selectionState.value,
        model.state.value.selectionToolbarOwner(), selectionAction, dismissedCapture).forAnnotationOpening(annotationOpening)

    fun cancelSelectionAction() {
        selectionJob?.cancel()
        selectionJob = null
        selectionAction = null
    }

    fun actionIsCurrent(job: Job, capture: ReflowSelectionCapture): Boolean {
        val action = selectionAction ?: return false
        return job.isActive && selectionJob === job && action.capture == capture && isCurrentReflowSelectionUiAction(
            action, model.selectionState.value, model.state.value.selectionToolbarOwner())
    }

    fun finishSelectionFeedback(job: Job, capture: ReflowSelectionCapture, feedback: String) {
        if (!job.isActive || selectionJob !== job) return
        val current = currentSelectionToolbar()
        selectionAction = if (current.capture == capture && current.blocksReaderInput)
            ReflowSelectionToolbarUiAction(capture, ReflowSelectionUiActionPhase.IDLE, feedback) else null
    }

    fun beginSelectionAction(capture: ReflowSelectionCapture, phase: ReflowSelectionUiActionPhase,
        fromBack: Boolean = false) {
        if (annotationOpening || model.annotationState.value.draft != null) return
        val current = currentSelectionToolbar()
        val enabled = when (phase) {
            ReflowSelectionUiActionPhase.CONFIRMING -> current.confirmEnabled
            ReflowSelectionUiActionPhase.COPYING -> current.copyEnabled
            ReflowSelectionUiActionPhase.CLEARING -> current.clearEnabled || fromBack && current.blocksReaderInput
            else -> false
        }
        if (current.capture != capture || !enabled) return
        val failureMessage = when (phase) {
            ReflowSelectionUiActionPhase.COPYING -> "复制失败，请重试"
            ReflowSelectionUiActionPhase.CLEARING -> "清除失败，请重试"
            else -> "确认未完成，请重试"
        }
        cancelSelectionAction()
        selectionAction = ReflowSelectionToolbarUiAction(capture, phase)
        val task = selectionScope.launch(start = CoroutineStart.LAZY) {
            val ownJob = checkNotNull(currentCoroutineContext()[Job])
            try {
                when (phase) {
                    ReflowSelectionUiActionPhase.CONFIRMING -> {
                        val result = model.confirmSelection(capture)
                        if (!actionIsCurrent(ownJob, capture)) return@launch
                        if (result !is OperationResult.Ok) {
                            finishSelectionFeedback(ownJob, capture, "确认未完成，请重试")
                            return@launch
                        }
                        selectionAction = ReflowSelectionToolbarUiAction(capture, ReflowSelectionUiActionPhase.WAITING_STABLE)
                        // The VM operation has returned: only this UI coroutine waits for real source state.
                        val observed = awaitReflowSelectionConfirmation(model, capture)
                        if (selectionJob !== ownJob) return@launch
                        if (observed == null) finishSelectionFeedback(ownJob, capture, "确认未完成，请重试")
                        else selectionAction = null
                    }
                    ReflowSelectionUiActionPhase.COPYING -> {
                        val result = model.copySelection(capture)
                        val copied = result is OperationResult.Ok && result.value.capture == capture &&
                            result.value.operationId.isNotBlank() && result.value.operationId == result.operationId
                        finishSelectionFeedback(ownJob, capture, if (copied) "已复制" else "复制失败，请重试")
                    }
                    ReflowSelectionUiActionPhase.CLEARING -> {
                        val result = model.clearSelection(capture)
                        if (result !is OperationResult.Ok) finishSelectionFeedback(ownJob, capture, "清除失败，请重试")
                        else if (selectionJob === ownJob) selectionAction = null
                    }
                    else -> Unit
                }
            } catch (timeout: TimeoutCancellationException) {
                if (!ownJob.isActive) throw timeout
                finishSelectionFeedback(ownJob, capture, failureMessage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                finishSelectionFeedback(ownJob, capture, failureMessage)
            } finally {
                if (selectionJob === ownJob) {
                    selectionJob = null
                    if (selectionAction?.phase != ReflowSelectionUiActionPhase.IDLE) selectionAction = null
                }
            }
        }
        selectionJob = task
        task.start()
    }

    fun beginAnnotation(capture: ReflowSelectionCapture, kind: AnnotationKind = AnnotationKind.NOTE) {
        val current = currentSelectionToolbar()
        if (annotationOpening || model.annotationState.value.draft != null || model.state.value.readOnly ||
            current.capture != capture || !model.canBeginAnnotation(capture)) return
        cancelSelectionAction()
        annotationOpening = true
        selectionScope.launch {
            try {
                val result = model.beginAnnotation(capture, kind)
                if (result !is OperationResult.Ok && model.annotationState.value.draft == null &&
                    model.selectionState.value.capture == capture) {
                    selectionAction = ReflowSelectionToolbarUiAction(capture, ReflowSelectionUiActionPhase.IDLE,
                        "附注未打开，请确认选区后重试")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (model.annotationState.value.draft == null && model.selectionState.value.capture == capture) {
                    selectionAction = ReflowSelectionToolbarUiAction(capture, ReflowSelectionUiActionPhase.IDLE,
                        "附注未打开，请重试")
                }
            } finally {
                annotationOpening = false
            }
        }
    }

    suspend fun dismissAnnotation(draftId: String) {
        val current = model.annotationState.value
        if (current.draft?.draftId != draftId || current.saving || current.outcomeUnknown || annotationClosing) return
        annotationClosing = true
        try {
            val result = model.dismissAnnotation(draftId)
            if (result is OperationResult.Ok) {
                protectAnnotationId = null
                annotationActionError = null
            } else if (model.annotationState.value.draft?.draftId == draftId) {
                annotationActionError = "附注未关闭，请重试；草稿已保留"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (model.annotationState.value.draft?.draftId == draftId) {
                annotationActionError = "附注未关闭，请重试；草稿已保留"
            }
        } finally {
            annotationClosing = false
        }
    }

    fun requestAnnotationClose() {
        val current = model.annotationState.value
        val draft = current.draft ?: return
        if (current.saving || current.outcomeUnknown || annotationClosing) return
        if (current.dirty) protectAnnotationId = draft.draftId
        else selectionScope.launch { dismissAnnotation(draft.draftId) }
    }

    fun saveAnnotation(draftId: String, closeAfterSave: Boolean = false) {
        val current = model.annotationState.value
        if (current.draft?.draftId != draftId || current.saving || annotationClosing ||
            current.sourceUnavailable && !current.outcomeUnknown) return
        annotationActionError = null
        protectAnnotationId = null
        selectionScope.launch {
            try {
                // The VM owns the actual save task; disposal of this waiter cannot cancel the write.
                val result = model.saveAnnotation(draftId)
                val latest = model.annotationState.value
                if (latest.draft?.draftId != draftId) return@launch
                if (result is OperationResult.Ok && closeAfterSave && !latest.dirty && !latest.saving && !latest.outcomeUnknown) {
                    dismissAnnotation(draftId)
                } else if (result !is OperationResult.Ok && latest.error == null && !latest.outcomeUnknown) {
                    annotationActionError = "附注未保存，请重试；草稿已保留"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (model.annotationState.value.draft?.draftId == draftId) {
                    annotationActionError = "保存未完成，请重试；草稿已保留"
                }
            }
        }
    }

    LaunchedEffect(annotation.draft?.draftId) {
        if (annotation.draft?.draftId != protectAnnotationId) protectAnnotationId = null
    }

    LaunchedEffect(selectionOwner, selection.capture, selection.usableNativeRange, selection.selection.session?.phase) {
        selectionAction?.let { action ->
            if (!isCurrentReflowSelectionUiAction(action, selection, selectionOwner)) cancelSelectionAction()
        }
    }
    DisposableEffect(model) { onDispose { selectionJob?.cancel() } }

    fun readerActionsAvailable(): Boolean {
        val current = model.state.value
        return !current.loading && !current.busy && !current.closed && current.turnState?.phase == ReaderTurnPhase.IDLE &&
            !currentSelectionToolbar().blocksReaderInput && !annotationOpening && model.annotationState.value.draft == null && !settingsOpen &&
            !annotationsOpen && !model.existingAnnotationState.value.isOpen && !current.settingsLeaveConfirmation
    }

    val turn = state.turnState
    val confirmation = turn?.confirmation
    val backCapture = selectionToolbar.capture?.takeIf { selectionToolbar.backSelection == BackSelection.TEXT }
    val requestBack = unifiedBackRequest(BackState(page = BackPage.READER, imeVisible = isBackImeVisible(),
        topLayer = when {
            state.settingsLeaveConfirmation -> BackLayer.Overlay("reflow-settings-leave-confirmation")
            settingsOpen -> BackLayer.Panel("reflow-settings")
            annotationsOpen -> BackLayer.Panel("reflow-annotations")
            protectAnnotationId != null && protectAnnotationId == annotation.draft?.draftId ->
                BackLayer.Overlay("reflow-annotation-close-confirmation")
            annotation.draft != null -> BackLayer.Editor("reflow-annotation-${annotation.draft?.draftId}",
                annotation.dirty || annotation.saving || annotation.outcomeUnknown)
            annotationOpening -> BackLayer.Panel("reflow-annotation-opening")
            !state.closed && confirmation != null -> BackLayer.Overlay("reflow-chapter-confirmation")
            else -> null
        },
        selection = selectionToolbar.backSelection)) { decision ->
        when (decision) {
            is BackDecision.ProtectDraft -> requestAnnotationClose()
            is BackDecision.CloseLayer -> when {
                decision.layer.key == "reflow-settings-leave-confirmation" -> model.cancelLeaveWithoutSettings()
                decision.layer.key == "reflow-settings" -> settingsOpen = false
                decision.layer.key == "reflow-annotations" -> annotationsOpen = false
                decision.layer.key == "reflow-annotation-close-confirmation" -> protectAnnotationId = null
                decision.layer is BackLayer.Editor -> requestAnnotationClose()
                decision.layer.key == "reflow-annotation-opening" -> Unit
                else -> model.cancel()
            }
            is BackDecision.ClearSelection -> if (decision.selection == BackSelection.TEXT) backCapture?.let {
                beginSelectionAction(it, ReflowSelectionUiActionPhase.CLEARING, fromBack = true)
            }
            BackDecision.SaveReadingPositionAndLeave -> if (!annotationOpening && model.annotationState.value.draft == null && !existingAnnotation.isOpen) {
                cancelSelectionAction(); model.requestLeave()
            }
            else -> Unit
        }
    }
    // Delivery belongs to this composition; a recreated Activity must receive finish again.
    var exitDelivered by remember(model) { mutableStateOf(false) }
    val latestOnExited by rememberUpdatedState(onExited)
    LaunchedEffect(state.closed, state.busy, state.cleanupFailure) {
        if (state.closed && !state.busy && state.cleanupFailure == null && !exitDelivered) {
            exitDelivered = true
            latestOnExited()
        }
    }
    val canTurn = !state.loading && !state.busy && !state.closed && turn?.phase == ReaderTurnPhase.IDLE &&
        !selectionToolbar.blocksReaderInput && !annotationOpening && annotation.draft == null && !settingsOpen && !annotationsOpen && !existingAnnotation.isOpen && !state.settingsLeaveConfirmation
    val context=LocalContext.current
    val window=remember(context){reflowReaderWindow(context)}
    val readerKey=remember(model){Any()}
    val conditions=ReaderChromeConditions(state.settings.immersivePreference&&window!=null,readerVisible=!state.closed,
        selectionActive=selectionToolbar.blocksReaderInput,imeVisible=isBackImeVisible(),editorOpen=annotation.draft!=null||annotationOpening||existingAnnotation.isOpen,
        accessibilityActive=rememberReflowAccessibility(context),overlayOpen=settingsOpen||annotationsOpen||state.settingsLeaveConfirmation||state.loading||state.error!=null||state.settingsFailure!=null)
    val chrome=if(window!=null)rememberReaderPresentationBinding(window,readerKey,conditions) else rememberReaderChromeController(readerKey,conditions)
    val chromeVisible=chrome.isVisible
    val density = LocalDensity.current
    val gestures = WindowInsets.systemGestures
    val edgePx = maxOf(gestures.getLeft(density, LayoutDirection.Ltr), gestures.getRight(density, LayoutDirection.Ltr),
        with(density) { 24.dp.roundToPx() }).toFloat()
    SideEffect {
        host.updateInput(!canTurn, edgePx, { action ->
            chrome.onInteraction()
            if (readerActionsAvailable()) when (action) {
                ReaderTapAction.PREVIOUS -> if(state.settings.edgeTap)model.turn(ReaderTurnDirection.PREVIOUS, ReaderTurnOrigin.EDGE_TAP) else chrome.toggle()
                ReaderTapAction.NEXT -> if(state.settings.edgeTap)model.turn(ReaderTurnDirection.NEXT, ReaderTurnOrigin.EDGE_TAP) else chrome.toggle()
                ReaderTapAction.TOGGLE_CHROME -> chrome.toggle()
            }
        }, { direction -> chrome.onInteraction(); if (readerActionsAvailable() && state.preferences.flowMode==ReaderFlowMode.PAGINATED) model.turn(direction, ReaderTurnOrigin.SWIPE) })
    }
    DisposableEffect(host) { onDispose { host.clearInput() } }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(
            if (state.settings.immersivePreference) Modifier.windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
            ) else Modifier.statusBarsPadding().navigationBarsPadding(),
        )) {
            Box(Modifier.fillMaxSize().testTag("reflow-viewport").onSizeChanged { size ->
                model.viewportChanged(size.width, size.height)
            }, contentAlignment = Alignment.Center) {
                presentation.slots.forEach { slot -> key(slot.id) {
                    val visible = !state.closed && presentation.activeSurfaceId == slot.id
                    AndroidView(
                        factory = {
                            (slot.view.parent as? ViewGroup)?.removeView(slot.view)
                            slot.view
                        },
                        update = { it.alpha = if (visible) 1f else 0f },
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (visible) 1f else 0f }
                            .testTag("reflow-surface-${slot.id}"),
                    )
                } }
                if (state.closed) Text("阅读已关闭", Modifier.testTag("reflow-closed"))
                else if (state.loading || state.busy && confirmation == null) CircularProgressIndicator(
                    Modifier.align(Alignment.TopEnd).padding(12.dp).size(24.dp).testTag("reflow-loading"))
            }
            Column(Modifier.fillMaxSize()) {
                if (chromeVisible) {
                    Surface(Modifier.fillMaxWidth().onSizeChanged { topChromeHeightPx = it.height }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = requestBack, modifier = Modifier.testTag("reflow-back")) { Text("返回") }
                        Text(state.title.ifBlank { "重排阅读" }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        if (state.readOnly) Text("历史版本 · 只读", style = MaterialTheme.typography.labelMedium)
                        if (annotation.draft == null) BookmarkToggleButton(bookmark, model::toggleBookmark,
                            model::retryBookmarkWrite, model::retryBookmarkObservation, showMessage = false)
                    }
                    }
                    HorizontalDivider()
                    if (annotation.draft == null) BookmarkCandidateChoices(bookmark, model::selectBookmark)
                }
                Spacer(Modifier.weight(1f))
                Surface(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
            state.error?.let { error ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("操作未完成：$error", Modifier.weight(1f).testTag("reflow-error"),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (!annotationOpening && model.annotationState.value.draft == null) model.retryOpen() },
                        enabled = !state.closed && !state.busy && !state.loading && !annotationOpening && annotation.draft == null,
                        modifier = Modifier.testTag("reflow-reopen")) { Text("重新打开") }
                }
            }
            state.saveFailure?.let { failure ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("进度未保存：${failure.code}", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (!annotationOpening && model.annotationState.value.draft == null) model.retrySave() },
                        enabled = !state.closed && !state.busy && !annotationOpening && annotation.draft == null) { Text("重试保存") }
                }
            }
            state.cleanupFailure?.let { failure ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("清理未完成：$failure", Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (!annotationOpening && model.annotationState.value.draft == null) model.retryCleanup() },
                        enabled = !state.busy && !annotationOpening && annotation.draft == null) { Text("重试清理") }
                }
            }
            if(state.settingsFailure!=null || state.settingsOutcomeUnknown || state.settingsApplyFailure!=null) Row(
                Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(if(state.settingsOutcomeUnknown)"偏好保存结果待确认" else "偏好尚未保存或应用",Modifier.weight(1f),color=MaterialTheme.colorScheme.error)
                TextButton(onClick={settingsOpen=true;chrome.show()},enabled=!state.closed&&!existingAnnotation.isOpen&&annotation.draft==null){Text("查看设置")}
            }
            if (state.settingsAbandoned && !state.closed) Text("已停止重试偏好，已保存的偏好不会回滚。",
                Modifier.padding(horizontal = 12.dp).testTag("reflow-settings-abandoned"), style = MaterialTheme.typography.bodySmall)
            if (chromeVisible) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { if (readerActionsAvailable()) {
                    val settings = model.state.value.settings
                    model.updateSettings(settings.copy(fontScale = maxOf(.8f, settings.fontScale - .1f)))
                } },
                    enabled = canTurn && state.settings.fontScale > .8f, modifier = Modifier.testTag("reflow-font-smaller")) { Text("字号 −") }
                TextButton(onClick = { if (readerActionsAvailable()) {
                    val settings = model.state.value.settings
                    model.updateSettings(settings.copy(fontScale = minOf(1.8f, settings.fontScale + .1f)))
                } },
                    enabled = canTurn && state.settings.fontScale < 1.8f, modifier = Modifier.testTag("reflow-font-larger")) { Text("字号 +") }
                TextButton(onClick={settingsOpen=true;chrome.show()},enabled=canTurn,modifier=Modifier.testTag("reflow-settings")){Text("设置")}
                TextButton(onClick={annotationsOpen=true;chrome.show()},enabled=canTurn,modifier=Modifier.testTag("reflow-annotations")){Text("标注")}
            }
            if (chromeVisible) Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { if (readerActionsAvailable()) model.previous() }, enabled = canTurn, modifier = Modifier.testTag("reflow-previous")) { Text("上一页") }
                val locator = turn?.active?.report?.currentLocator
                if(state.settings.pageIndicator) Text(if (state.closed || locator == null) "—" else "本章 ${((locator.progression ?: 0f) * 100).toInt()}%",
                    Modifier.testTag("reflow-progress"), style = MaterialTheme.typography.labelLarge)
                Button(onClick = { if (readerActionsAvailable()) model.next() }, enabled = canTurn, modifier = Modifier.testTag("reflow-next")) { Text("下一页") }
            }
                }
                }
            }
            if (annotation.draft == null) SelectionActionToolbar(
                state = selectionToolbar,
                onConfirm = { beginSelectionAction(it, ReflowSelectionUiActionPhase.CONFIRMING) },
                onCopy = { beginSelectionAction(it, ReflowSelectionUiActionPhase.COPYING) },
                onClear = { beginSelectionAction(it, ReflowSelectionUiActionPhase.CLEARING) },
                onDismiss = { capture ->
                    val current = currentSelectionToolbar()
                    if (!annotationOpening && model.annotationState.value.draft == null && current.capture == capture && current.dismissEnabled) {
                        cancelSelectionAction()
                        dismissedCapture = capture
                    }
                },
                onAnnotate = { beginAnnotation(it) },
                onHighlight = { beginAnnotation(it, AnnotationKind.HIGHLIGHT) },
                annotationEnabled = !state.readOnly && !annotationOpening &&
                    selectionToolbar.capture?.let(model::canBeginAnnotation) == true,
                bookmarkContent = { BookmarkCandidateChoices(bookmark, model::selectBookmark)
                    BookmarkEnsureButton(bookmark, model::ensureBookmark,
                    model::retryBookmarkWrite, model::retryBookmarkObservation) },
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .padding(top = if (chromeVisible) with(density) { topChromeHeightPx.toDp() } else 0.dp)
                    .widthIn(max = 480.dp).fillMaxWidth(),
            )
            if (annotation.draft != null) {
                Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .32f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = requestBack))
                    ReflowAnnotationEditorPanel(
                        state = annotation,
                        onNoteChange = { draftId, text ->
                            val current = model.annotationState.value
                            if (current.draft?.draftId == draftId && !current.editBlocked && !current.sourceUnavailable && !annotationClosing) {
                                val result = model.changeAnnotationNote(draftId, text)
                                annotationActionError = if (result is OperationResult.Ok) null
                                    else if (text.length > 1_000_000) "笔记超过 100 万字符，本次修改未应用，请缩短后重试"
                                    else "笔记修改未完成，请重试；草稿已保留"
                            }
                        },
                        onStyleChange = { draftId, style ->
                            val current = model.annotationState.value
                            if (current.draft?.draftId == draftId && !current.editBlocked && !current.sourceUnavailable && !annotationClosing) {
                                annotationActionError = if (model.changeAnnotationStyle(draftId, style) is OperationResult.Ok) null
                                    else "颜色修改未完成，请重试；草稿已保留"
                            }
                        },
                        onSave = { saveAnnotation(it) },
                        onReload = { draftId -> selectionScope.launch {
                            val result = model.reloadAnnotationConflict(draftId)
                            annotationActionError = if (result is OperationResult.Ok) null else "最新版本加载失败；本地草稿已保留"
                        } },
                        onClose = requestBack,
                        actionError = annotationActionError,
                        closing = annotationClosing,
                        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = 640.dp),
                    )
                }
            }
        }
    }
    if (state.settingsLeaveConfirmation && !state.closed) AlertDialog(
        onDismissRequest = model::cancelLeaveWithoutSettings,
        modifier = Modifier.testTag("reflow-settings-leave-confirmation"),
        title = { Text("偏好尚未确认保存") },
        text = { Text(if (state.settingsOutcomeUnknown)
            "偏好可能已经保存。放弃后将停止重试，已保存的内容不会回滚；阅读进度仍需保存成功才能退出。"
            else "本次偏好未能保存。可以继续阅读，或放弃待保存偏好并退出；阅读进度仍会受到保护。") },
        confirmButton = { TextButton(onClick = model::confirmLeaveWithoutSettings, enabled = !state.busy,
            modifier = Modifier.testTag("reflow-settings-abandon-and-leave")) { Text("放弃未确认偏好并退出") } },
        dismissButton = { TextButton(onClick = model::cancelLeaveWithoutSettings,
            modifier = Modifier.testTag("reflow-settings-keep-reading")) { Text("继续阅读") } },
    )
    if(settingsOpen)ReflowReaderSettingsPanel(state,model::updateSettings,model::makeDefaultSettings,model::useDefaultSettings,
        model::retrySettingsSave,{settingsOpen=false},window!=null)
    if(annotationsOpen)AlertDialog(onDismissRequest={annotationsOpen=false},title={Text("本章标注")},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState())) {
            if(state.annotations.isEmpty())Text("本章暂无标注")
            state.annotations.forEach { item ->
                Column(Modifier.fillMaxWidth()) {
                    Text(item.quote.orEmpty().take(100));item.noteText?.takeIf { it.isNotBlank() }?.let { Text(it.take(100)) }
                    if (item.id in state.annotationUnmappableIds) Text("部分标注无法映射到当前原文", color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("reflow-annotation-unmappable-${item.id}"))
                    Row {
                        TextButton(onClick={annotationsOpen=false;model.navigateToAnnotation(item.id)},
                            enabled = !state.busy && !selection.usableNativeRange && selection.selection.session?.phase != SelectionSessionPhase.EDITING,
                            modifier=Modifier.testTag("reflow-annotation-locate-${item.id}")) { Text("定位") }
                        TextButton(onClick={annotationsOpen=false;model.openExistingAnnotation(item.id)},
                            enabled = !state.busy && !state.readOnly,
                            modifier=Modifier.testTag("reflow-annotation-edit-${item.id}")) { Text("编辑") }
                    }
                }
            }
        }
    },confirmButton={TextButton(onClick={annotationsOpen=false}){Text("关闭")}})
    if(existingAnnotation.isOpen)AnnotationActionSheet(existingAnnotation,model::editExistingAnnotationNote,
        model::chooseExistingAnnotationColor,model::saveExistingAnnotation,model::deleteExistingAnnotation,
        model::undoExistingAnnotation,model::retryExistingAnnotation,model::reloadExistingAnnotation,model::closeExistingAnnotation)
    if (protectAnnotationId != null && protectAnnotationId == annotation.draft?.draftId) AlertDialog(
        onDismissRequest = requestBack,
        modifier = Modifier.testTag("reflow-annotation-protect-draft"),
        title = { Text("未保存的附注") },
        text = { Text(if (annotation.outcomeUnknown) "结果待确认，请先确认保存结果。"
            else if (annotation.saving) "正在保存，请稍候。"
            else if (annotation.sourceUnavailable) "阅读会话已结束，无法保存。可明确放弃修改后关闭，或继续保留草稿。"
            else "保存后关闭、明确放弃修改，或继续编辑。") },
        confirmButton = {
            TextButton(onClick = { protectAnnotationId?.let { saveAnnotation(it, closeAfterSave = true) } },
                enabled = !annotation.saving && !annotationClosing && (!annotation.sourceUnavailable || annotation.outcomeUnknown),
                modifier = Modifier.testTag("reflow-annotation-save-and-close")) {
                Text(if (annotation.outcomeUnknown) "确认保存结果" else "保存并关闭")
            }
        },
        dismissButton = { Row {
            TextButton(onClick = {
                val draftId = protectAnnotationId
                val current = model.annotationState.value
                if (draftId != null && current.draft?.draftId == draftId && !current.saving && !current.outcomeUnknown && !annotationClosing) {
                    protectAnnotationId = null
                    selectionScope.launch { dismissAnnotation(draftId) }
                }
            }, enabled = !annotation.saving && !annotation.outcomeUnknown && !annotationClosing,
                modifier = Modifier.testTag("reflow-annotation-discard")) { Text("放弃修改") }
            TextButton(onClick = { protectAnnotationId = null },
                modifier = Modifier.testTag("reflow-annotation-continue")) { Text("继续编辑") }
        } },
    )
    if (annotation.draft == null && !annotationOpening && !state.closed && confirmation != null) AlertDialog(
        onDismissRequest = requestBack,
        title = { Text(if (confirmation.placement == ReaderPlacement.END) "进入上一章？" else "进入下一章？") },
        text = { Text("确认后切换章节；加载成功前保留当前页面。") },
        confirmButton = { TextButton(onClick = { model.confirm() }, enabled = turn?.phase == ReaderTurnPhase.CONFIRMING,
            modifier = Modifier.testTag("reflow-confirm")) { Text("切换章节") } },
        dismissButton = { TextButton(onClick = { model.cancel() }, modifier = Modifier.testTag("reflow-cancel")) { Text("取消") } },
    )
}

private fun ReflowSelectionToolbarState.forAnnotationOpening(opening: Boolean): ReflowSelectionToolbarState =
    if (!opening) this else copy(confirmEnabled = false, copyEnabled = false, clearEnabled = false,
        dismissEnabled = false, blocksReaderInput = true, statusText = "正在打开附注…")

private enum class ReflowSelectionConfirmationObservation { STABLE, CANCELLED }

private suspend fun awaitReflowSelectionConfirmation(
    model: ReflowReaderRuntimeViewModel,
    capture: ReflowSelectionCapture,
): ReflowSelectionConfirmationObservation? = withTimeoutOrNull(5_000) {
    val waiting = ReflowSelectionToolbarUiAction(capture, ReflowSelectionUiActionPhase.WAITING_STABLE)
    combine(model.state, model.selectionState) { runtime, selection ->
        when {
            !isCurrentReflowSelectionUiAction(waiting, selection, runtime.selectionToolbarOwner()) ->
                ReflowSelectionConfirmationObservation.CANCELLED
            selection.selection.session?.phase == SelectionSessionPhase.STABLE &&
                (selection.capture?.eventSequence ?: -1) > capture.eventSequence -> ReflowSelectionConfirmationObservation.STABLE
            else -> null
        }
    }.first { it != null }
}
