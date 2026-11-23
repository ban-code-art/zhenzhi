package com.example.myapplication.ui.knowledge.course

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.quiz.SourcePracticeScope
import com.example.myapplication.domain.reader.*
import com.example.myapplication.ui.ai.AgentLaunchContext
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.UiNoticeHost
import com.example.myapplication.ui.components.UiNoticeVisuals
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.knowledge.reader.*
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.UiPageStack
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.UUID

private class CourseFactory<T : ViewModel>(private val create: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <M : ViewModel> create(modelClass: Class<M>): M = create() as M
}

@Composable
fun CourseLibraryRoute(onOpen: (String) -> Unit, onBack: () -> Unit, onOpenDraft: (() -> Unit)? = null,
    onAskAi: ((SourcePracticeScope) -> Unit)? = null) {
    var openBundledDraft by rememberSaveable { mutableStateOf(false) }
    var bundledBookKey by rememberSaveable { mutableStateOf<String?>(null) }
    var bundledReload by remember { mutableIntStateOf(0) }
    var bundledCatalog by remember { mutableStateOf<BundledCourseCatalog?>(null) }
    var bundledLoading by remember { mutableStateOf(true) }
    var bundledError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val savedScreens = rememberSaveableStateHolder()
    LaunchedEffect(context, bundledReload) {
        bundledLoading = true; bundledError = null
        try {
            bundledCatalog = withContext(Dispatchers.IO) {
                context.assets.open(BundledCourseCatalog.ASSET_PATH).use { BundledCourseCatalog.fromBytes(it.readBytes()) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { bundledError = "内置教材载入失败，请重新载入。" }
        finally { bundledLoading = false }
    }
    val container = (context.applicationContext as LearningApplication).container
    val factory = remember(container) { CourseFactory {
        CourseLibraryViewModel(container.sessionManager.identity, { container.database.courseBookDao().observeBooks() },
            { container.courseCatalog(it) }, { access, book, _ ->
                container.courseReadingSequenceProvider.load(access, book, allowDevelopmentDraft = false)
            }, allowDevelopmentDraft = false)
    } }
    val model: CourseLibraryViewModel = viewModel(factory = factory)
    val bundledPath = if (openBundledDraft) listOf("bundled:${bundledBookKey.orEmpty()}") else emptyList()
    UiPageStack(bundledPath) { visiblePath ->
        val key = visiblePath.singleOrNull()?.removePrefix("bundled:")
        if (key != null) {
            savedScreens.SaveableStateProvider("bundled-course:$key") {
                BundledCourseDraftPreviewRoute(
                    onBack = { openBundledDraft = false },
                    initialBookKey = key.ifEmpty { null },
                    onAskAi = onAskAi,
                )
            }
        } else {
            val requestBack = coursePageBack(onBack)
            savedScreens.SaveableStateProvider("course-library") {
                CourseLibraryScreen(model, onOpen, requestBack, onOpenDraft = onOpenDraft,
                    onOpenBundledDraft = { bundledBookKey = null; openBundledDraft = true },
                    bundledCatalog = bundledCatalog, bundledLoading = bundledLoading, bundledError = bundledError,
                    onReloadBundled = { bundledReload++ },
                    onOpenBundledBook = { bookKey -> bundledBookKey = bookKey; openBundledDraft = true })
            }
        }
    }
}

@Composable
fun CourseBookRoute(bookId: String, initialPosition: String? = null, onPosition: (String) -> Unit = {},
    onOpen: (ReaderOpenRequest, String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as LearningApplication).container
    val factory = remember(container, bookId) { CourseFactory {
        CourseBookViewModel(bookId, container.sessionManager.identity, { container.courseCatalog(it) },
            { access, book -> container.courseReadingSequenceProvider.load(access, book, allowDevelopmentDraft = false) },
            { user, target -> when (val opened = container.readerRepository.openSession(user, target.documentId, target.contentRevision)) {
                is OperationResult.Ok -> opened.value.sections.firstOrNull { it.id == target.firstSectionId }?.title
                    ?: opened.value.document.title
                else -> "章节暂不可用"
            } },
            readState = { user, doc -> container.database.readerStateDao().find(user, doc) },
            studyProgress = { container.database.studyProgressDao().observeByUser(it) },
            quizAttempts = { user, docs -> container.database.quizAttemptDao().observeCourseAttempts(user, docs) })
    } }
    val model: CourseBookViewModel = viewModel(factory = factory)
    val state by model.state.collectAsState()
    val scroll = rememberLazyListState()
    var restoring by remember(bookId) { mutableStateOf(true) }
    var restored by remember(bookId) { mutableStateOf(false) }
    val latestPosition by rememberUpdatedState(onPosition)
    val latestState by rememberUpdatedState(state)
    val summary by produceState<String?>(null, bookId) {
        value = try { withContext(Dispatchers.IO) { container.courseCatalog(bookId)?.book?.summary } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.reload() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(initialPosition, state.loading, state.rows.map { it.target.chapterKey }, state.combinedQuizzes.size) {
        if (!state.loading && !state.closed && state.error == null) {
            restoring = true
            if (!restored) CourseDirectoryPositionCodec.decodeOrNull(initialPosition.orEmpty(), bookId)?.let { saved ->
                val layout = CourseDirectoryPositionProjector.layout(state.rows.map { it.target.chapterKey!! }, state.combinedQuizzes.size)
                val index = CourseDirectoryPositionProjector.restoreListIndex(saved, layout)
                if (index != null) scroll.scrollToItem(index, saved.offset) else scroll.scrollToItem(0, 0)
            }
            restored = true
            restoring = false
        }
    }
    fun snapshot(): String {
        val current = model.state.value
        val layout = CourseDirectoryPositionProjector.layout(current.rows.map { it.target.chapterKey!! }, current.combinedQuizzes.size)
        return CourseDirectoryPositionCodec.encode(CourseDirectoryPositionProjector.capture(bookId, layout,
            scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset))
    }
    LaunchedEffect(scroll, bookId) {
        snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect {
            if (!restoring && !latestState.loading && !latestState.closed && latestState.error == null) latestPosition(snapshot())
        }
    }
    val checkpointBack = coursePageBack {
        if (!state.closed) onPosition(snapshot())
        onBack()
    }
    CourseBookScreen(model, onOpen = { target ->
        val row = model.state.value.rows.singleOrNull { it.target == target }
        val locator = row?.resumeLocator
        val request = ReaderOpenRequest(target, if (locator == null) ReaderPlacement.START else ReaderPlacement.AT_LOCATOR, locator)
        onPosition(snapshot()); onOpen(request, snapshot())
    }, onBack = checkpointBack, onContinue = { next ->
        val request = ReaderOpenRequest(next.target, if (next.locator == null) ReaderPlacement.START else ReaderPlacement.AT_LOCATOR, next.locator)
        onPosition(snapshot()); onOpen(request, snapshot())
    }, scroll = scroll, summary = summary)
}

@Composable
internal fun coursePageBack(onBack: () -> Unit): () -> Unit =
    unifiedBackRequest(BackState(page = BackPage.CHILD, imeVisible = isBackImeVisible())) { decision ->
        if (decision == BackDecision.PopPage) onBack()
    }

@Composable
fun CourseReaderRoute(bookId: String, chapterKey: String, placement: ReaderPlacement, initialLocatorJson: String?,
    onBack: () -> Unit, onNavigate: (ReaderOpenRequest) -> Unit, onOpenNotebook: (String) -> Unit,
    onAskAi: (AgentLaunchContext) -> Unit, onGenerateQuiz: (String, String?) -> Unit, isSourceReader: Boolean = false,
    consumedPositionJson: String? = null, returnLocatorJson: String? = null, onPositionReady: (String) -> Unit = {}) {
    val context = LocalContext.current
    val container = (context.applicationContext as LearningApplication).container
    val locator = remember(initialLocatorJson) { initialLocatorJson?.let(ReaderLocatorCodec::decodeOrNull) }
    val factory = remember(container, bookId, chapterKey, placement, locator, consumedPositionJson) {
        CourseFactory { CourseReaderViewModel(container, bookId, chapterKey, placement, locator, consumedPositionJson) }
    }
    val model: CourseReaderViewModel = viewModel(factory = factory, key = "course-binding")
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var turning by remember { mutableStateOf(false) }
    val binding = state.binding
    if (binding == null) {
        val requestBack = coursePageBack(onBack)
        Box(
            Modifier.fillMaxSize().background(UiRoles.surface(UiSurfaceRole.Page)).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (state.loading) {
                CircularProgressIndicator()
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        state.error ?: "课程阅读不可用",
                        style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                        color = UiRoles.foreground,
                    )
                    UiAction(
                        label = "返回目录",
                        onClick = requestBack,
                        actionId = "courseReader.backToDirectory",
                    )
                }
            }
        }
    } else {
        val identity by container.sessionManager.identity.collectAsState(initial = null)
        val noticeOwner = binding.request.target to identity
        val noticeHostState = remember(noticeOwner) { SnackbarHostState() }
        val noticeBackdrop = rememberUiBackdrop()
        LaunchedEffect(noticeOwner, state.error) {
            val message = state.error ?: return@LaunchedEffect
            try {
                noticeHostState.showSnackbar(
                    UiNoticeVisuals(
                        eventId = UUID.randomUUID().toString(),
                        ownerKey = noticeOwner,
                        message = message,
                        duration = SnackbarDuration.Long,
                    ),
                )
            } finally {
                val latest = model.state.value
                if (latest.binding?.request?.target == noticeOwner.first && latest.error == message) {
                    model.clearNotice()
                }
            }
        }
        val request = remember(binding, returnLocatorJson) {
            val target = binding.request.target
            val returned = returnLocatorJson?.let(ReaderLocatorCodec::decodeOrNull)?.takeIf {
                ReaderLocatorValidator.validateForDocument(it, target.documentId, target.contentRevision).isValid
            }
            // The legacy reader rechecks the locator against the bound chapter's allowed sections.
            if (returned == null) binding.request else binding.request.copy(placement = ReaderPlacement.AT_LOCATOR, locator = returned)
        }
        Box(Modifier.fillMaxSize()) {
            UiGlassBackdrop(
                backdrop = noticeBackdrop,
                modifier = Modifier.fillMaxSize(),
                captureEnabled = noticeHostState.currentSnackbarData != null,
            ) {
                ReaderScreen(request.target.documentId, request.locator?.let(ReaderLocatorCodec::encode), onBack, isSourceReader,
                    onOpenNotebook = { onOpenNotebook(request.target.documentId) }, onAskAi = onAskAi,
                    onGenerateQuiz = onGenerateQuiz, courseRequest = request, onPositionReady = onPositionReady,
                    courseQuizAvailable = state.quizAvailable,
                    courseNavigation = CourseReaderNavigation(state.previousTitle, state.nextTitle, busy = turning) { direction ->
                        if (!turning && !state.closed) {
                            turning = true
                            scope.launch(Dispatchers.Main.immediate) {
                                try {
                                    val request = model.confirmedTurn(direction)
                                    if (request != null) withContext(Dispatchers.Main.immediate) { onNavigate(request) }
                                } finally {
                                    withContext(NonCancellable + Dispatchers.Main.immediate) { turning = false }
                                }
                            }
                        }
                    })
            }
            UiNoticeHost(
                hostState = noticeHostState,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                ownerKey = noticeOwner,
                backdrop = noticeBackdrop,
            )
        }
    }
}
