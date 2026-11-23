package com.example.myapplication.ui.knowledge.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.reader.ReaderSession
import com.example.myapplication.data.reader.readingSequence
import com.example.myapplication.data.reader.importer.FormatRegistry
import com.example.myapplication.data.reader.notes.AnnotationKind
import com.example.myapplication.data.reader.notes.AnnotationStyle
import com.example.myapplication.data.reader.notes.ReaderAnnotationView
import com.example.myapplication.data.reader.notes.AnnotationColor
import com.example.myapplication.data.reader.notes.AnnotationTargetInput
import com.example.myapplication.data.reader.notes.AnnotationWriteGuard
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ReflowReaderRuntimeUiState(
    val loading: Boolean = true,
    val title: String = "",
    val turnState: ReaderTurnState? = null,
    val readOnly: Boolean = false,
    val error: String? = null,
    val saveFailure: ReaderProgressSaveFailure? = null,
    val cleanupFailure: String? = null,
    val closed: Boolean = false,
    val busy: Boolean = false,
    val preferences: ReaderLayoutPreferences = ReaderLayoutPreferences(1f, 1.5f, 16, null, null, "#FFFFFF", ReaderFlowMode.PAGINATED),
    val settings: ReaderSettings = ReaderSettings(flowMode = ReaderFlowMode.PAGINATED),
    val settingsPending: Boolean = false,
    val settingsSaving: Boolean = false,
    val settingsFailure: String? = null,
    val settingsOutcomeUnknown: Boolean = false,
    val settingsRetryable: Boolean = true,
    val settingsInheritsDefaults: Boolean = true,
    val settingsUnsupported: String? = null,
    val settingsApplyFailure: String? = null,
    val settingsLeaveConfirmation: Boolean = false,
    val settingsAbandoned: Boolean = false,
    val annotations: List<ReaderAnnotationView> = emptyList(),
    val annotationUnmappableIds: Set<String> = emptySet(),
)

data class CourseReaderScope(
    val bookId: String,
    val initialChapterKey: String?,
    val loadSequence: suspend (AccessContext) -> OperationResult<ReadingSequence>,
)

/** Explicit opt-in owner; staged native views become visible only after coordinator commit. */
class ReflowReaderRuntimeViewModel(
    private val documentId: String,
    private val initialLocatorJson: String? = null,
    identities: Flow<SessionIdentity>,
    private val openSession: suspend (Long, String, String?) -> OperationResult<ReaderSession>,
    private val runtimeFactory: ReflowReaderRuntimeFactory,
    private val cleanupScope: CoroutineScope,
    private val recovery: ReaderRuntimeRecovery? = null,
    private val courseScope: CourseReaderScope? = null,
    private val courseRecovery: CourseReaderRuntimeRecovery? = null,
    private val annotationServices: ReflowAnnotationServices? = null,
    bookmarkServices: ReflowBookmarkServices? = null,
    private val settingsServices: ReflowReaderSettingsServices? = null,
) : ViewModel() {
    constructor(documentId: String, initialLocatorJson: String?, identities: Flow<SessionIdentity>,
        openSession: suspend (Long, String, String?) -> OperationResult<ReaderSession>,
        runtimeFactory: ReflowReaderRuntimeFactory, cleanupScope: CoroutineScope,
        recovery: ReaderRuntimeRecovery?, courseScope: CourseReaderScope?, courseRecovery: CourseReaderRuntimeRecovery?,
        annotationServices: ReflowAnnotationServices?, bookmarkServices: ReflowBookmarkServices?) :
        this(documentId,initialLocatorJson,identities,openSession,runtimeFactory,cleanupScope,recovery,courseScope,courseRecovery,
            annotationServices,bookmarkServices,null)
    /** Retains the original Java/reflection constructor used by existing isolated hosts. */
    constructor(documentId: String, initialLocatorJson: String?, identities: Flow<SessionIdentity>,
        openSession: suspend (Long, String, String?) -> OperationResult<ReaderSession>,
        runtimeFactory: ReflowReaderRuntimeFactory, cleanupScope: CoroutineScope,
        recovery: ReaderRuntimeRecovery?, courseScope: CourseReaderScope?,
        courseRecovery: CourseReaderRuntimeRecovery?, annotationServices: ReflowAnnotationServices?) :
        this(documentId, initialLocatorJson, identities, openSession, runtimeFactory, cleanupScope,
            recovery, courseScope, courseRecovery, annotationServices, null)

    private val mutableState = MutableStateFlow(ReflowReaderRuntimeUiState())
    val state = mutableState.asStateFlow()
    private val operation = Mutex()
    @Volatile private var stopped = false
    @Volatile private var runtime: ReflowReaderRuntime? = null
    private var opening: Job? = null
    private var action: Job? = null
    private var observer: Job? = null
    private var features: Job? = null
    private var annotationObserver: Job? = null
    private var settingsPersistence: ReaderSettingsPersistence? = null
    private var settingsDocumentId: String? = null
    private var settingsRevision: String? = null
    private var settingsObserver: Job? = null
    private var defaultsObserver: Job? = null
    private var settingsLayoutJob: Job? = null
    private val selectionBinding = ReflowSelectionRuntimeBinding()
    internal val selectionState: StateFlow<ReflowSelectionRuntimeState> = selectionBinding.state
    private val annotationEditor = annotationServices?.let { ReflowAnnotationEditor(it, ::validateAnnotationOwner) }
    internal val annotationState: StateFlow<ReflowAnnotationEditorState> = annotationEditor?.state
        ?: MutableStateFlow(ReflowAnnotationEditorState()).asStateFlow()
    @Volatile private var annotationSaveJob: Deferred<OperationResult<ReaderAnnotationView>>? = null
    private var retainedAnnotation: Pair<ReflowSelectionCapture,ReaderAnnotationView>? = null
    private val annotationActions=annotationServices?.let { services -> AnnotationActionController(
        update={ctx,id,revision,note,style,guard -> if(guard==null)OperationResult.Rejected("SOURCE_PROOF_REQUIRED")
            else services.update(ctx,id,revision,note,style ?: AnnotationStyle(),guard)},
        remove={ctx,id,revision,guard -> if(guard==null)OperationResult.Rejected("SOURCE_PROOF_REQUIRED") else
            services.removeText?.invoke(ctx,id,revision,guard) ?: OperationResult.Rejected("ANNOTATION_DELETE_UNAVAILABLE")},
        restore={ctx,id,revision,guard -> if(guard==null)OperationResult.Rejected("SOURCE_PROOF_REQUIRED") else
            services.restoreText?.invoke(ctx,id,revision,guard) ?: OperationResult.Rejected("ANNOTATION_RESTORE_UNAVAILABLE")},
        newRequestId={UUID.randomUUID().toString()}) }
    val existingAnnotationState: StateFlow<AnnotationActionState> = annotationActions?.state ?: MutableStateFlow(AnnotationActionState())
    @Volatile private var selectionCollector: Job? = null
    @Volatile private var selectionCopyJob: Job? = null
    @Volatile private var selectionCollectorGeneration = 0L
    private class SelectionOwner(val controller: ReaderRuntimeController, val token: ReaderSessionToken,
        val port: ReflowSelectionPort, val generation: Long) {
        @Volatile var sourceTerminated = false
    }
    @Volatile private var selectionOwner: SelectionOwner? = null
    @Volatile private var ownerIdentity: SessionIdentity? = null
    private var bookmarkRefreshJob: Job? = null
    private val bookmarkInput = bookmarkServices?.let { services -> BookmarkHostInputBinding(BookmarkHostInputServices(
        identityNow = services.identityNow,
        hostNow = { bookmarkHostSnapshot() },
        proveLive = { expected -> proveBookmarkPosition(expected) },
        openCurrentDocument = { context, id, revision -> services.access.open(context, id, revision) },
        validateCanonical = { token, fragments, viewport ->
            val port = selectionOwner?.takeIf { it.token == token && !it.sourceTerminated }?.port
            if (port == null) LocatorValidation(listOf("STALE_BOOKMARK_SOURCE"))
            else if (fragments != null) port.validateSource(token, fragments)
            else if (viewport == null) LocatorValidation(listOf("NO_BOOKMARK_VIEWPORT"))
            else ReaderLocatorValidator.validateForDocument(viewport, token.documentId, token.contentRevision)
        },
    )) }
    private val bookmarkActions = bookmarkServices?.let { services -> BookmarkActionController(viewModelScope, BookmarkActionServices(
        identityNow = services.identityNow,
        ownsPosition = { input -> bookmarkInput?.state?.value?.position?.let {
            actionInput(it) == input && bookmarkInput.ownsPosition(it)
        } == true },
        observeBookmarks = services.observe,
        resolvePosition = { document, revision, fragments, viewport, bookmarks ->
            when (val result = BookmarkPositionResolver.resolve(document, revision, fragments, viewport, bookmarks,
                selectionOwner?.let { it.port.readingOrder(it.token) },
                bookmarkInput?.state?.value?.position?.viewport?.visibleRanges.orEmpty())) {
                is BookmarkPositionResolver.Result.Resolved -> BookmarkActionResolution.Resolved(
                    BookmarkActionResolution.Target(result.target.locator, result.target.anchorHash, result.target.preview), result.bookmark)
                is BookmarkPositionResolver.Result.Ambiguous -> BookmarkActionResolution.Ambiguous(
                    BookmarkActionResolution.Target(result.target.locator, result.target.anchorHash, result.target.preview), result.bookmarkIds)
                is BookmarkPositionResolver.Result.Unavailable -> BookmarkActionResolution.Unavailable(result.reason.name)
            }
        }, ensureBookmark = services.ensure, toggleBookmark = services.toggle,
    )) }
    val bookmarkState: StateFlow<BookmarkActionState> = bookmarkActions?.state ?: MutableStateFlow(BookmarkActionState()).asStateFlow()
    internal val bookmarkInputDiagnostics: BookmarkHostInputState? get() = bookmarkInput?.state?.value
    private var committedLocator: ReaderLocator.Reflow? = null
    private var runtimeReady = false
    @Volatile private var layoutRequested = false
    private var recoveryRestored = false
    private var courseSequence: ReadingSequence? = null
    private var lastRecovery: ReaderRuntimeCheckpoint? = null
    @Volatile private var leaveRequested = false
    private var viewportSize: Pair<Int, Int>? = null
    private fun actionInput(position: BookmarkHostPosition) = BookmarkPositionInput(position.identity, position.token,
        position.positionId, position.writable, position.selection?.fragments, position.viewport?.locator,
        position.viewport?.visibleRanges.orEmpty())

    private fun bookmarkHostSnapshot(): BookmarkHostSnapshot? {
        val controller = runtime?.controller ?: return null
        val active = controller.state.value.active
        val owner = selectionOwner?.takeIf { it.token == active.report.token && !it.sourceTerminated } ?: return null
        val native = owner.port.bookmarkState(owner.token) ?: return null
        val selection = if (annotationState.value.draft != null) BookmarkHostSelection(BookmarkHostSelectionPhase.EDITING)
            else if (native.capture == null) BookmarkHostSelection(BookmarkHostSelectionPhase.NONE)
            else BookmarkHostSelection(BookmarkHostSelectionPhase.STABLE, native.binding.bindingId,
                native.eventSequence, native.capture.selectionId, native.fragments, true, true)
        return BookmarkHostSnapshot(owner.token, owner.port.originalSnapshot(owner.token) ?: return null,
            controller.state.value.phase, runtimeReady && !layoutRequested && !leaveRequested,
            stopped || controller.isClosed.value, controller.cleanupFailure.value?.code,
            state.value.readOnly, selection, native.viewport)
    }

    private suspend fun proveBookmarkPosition(position: BookmarkHostPosition): Boolean {
        if (stopped || leaveRequested || layoutRequested || !runtimeReady || annotationState.value.draft != null) return false
        val owner = selectionOwner?.takeIf { it.token == position.token && !it.sourceTerminated } ?: return false
        val native = owner.port.bookmarkState(owner.token) ?: return false
        val expected = position.selection
        if (expected == null) {
            if (native.capture != null || native.viewport != position.viewport) return false
        } else if (native.capture?.selectionId != expected.selectionId || native.binding.bindingId != expected.bindingId ||
            native.eventSequence != expected.eventSequence || native.fragments != expected.fragments) return false
        return owner.port.proveBookmark(native) && selectionOwner === owner && !stopped &&
            annotationState.value.draft == null && !layoutRequested
    }

    private fun requestBookmarkRefresh() {
        val input = bookmarkInput ?: return
        if (stopped) return
        bookmarkRefreshJob?.cancel()
        bookmarkRefreshJob = viewModelScope.launch {
            try {
                val result = input.refresh()
                bookmarkActions?.updatePosition((result as? OperationResult.Ok)?.value?.let(::actionInput))
            } catch (cancelled: CancellationException) { throw cancelled }
        }
    }

    fun selectBookmark(bookmarkId: String) { bookmarkActions?.selectBookmark(bookmarkId) }
    fun toggleBookmark() { viewModelScope.launch { bookmarkActions?.toggleBookmark() } }
    fun ensureBookmark() { viewModelScope.launch { bookmarkActions?.ensureBookmark() } }
    fun retryBookmarkWrite() { viewModelScope.launch { bookmarkActions?.retryWrite() } }
    fun retryBookmarkObservation() { requestBookmarkRefresh() }

    init {
        viewModelScope.launch {
            var first = true
            var owner: SessionIdentity? = null
            identities.distinctUntilChanged().collect { identity ->
                if (first) {
                    first = false; owner = identity; ownerIdentity = identity
                    if (identity.userId == null) {
                        stopped = true
                        mutableState.update { it.copy(loading = false, closed = true, error = "LOGIN_REQUIRED") }
                    } else opening = viewModelScope.launch(Dispatchers.IO) { load(identity) }
                } else if (identity != owner) {
                    stopInteractions("SESSION_CHANGED")
                    cleanupScope.launch { closeAndAwait() }
                }
            }
        }
        viewModelScope.launch {
            runtimeFactory.cleanupFailure.collect { failure ->
                mutableState.update { it.copy(cleanupFailure = failure ?: runtime?.controller?.cleanupFailure?.value?.code) }
            }
        }
    }
    private suspend fun load(identity: SessionIdentity, reopening: Boolean = false) {
        try {
            operation.withLock {
                if (stopped) return
                mutableState.update { it.copy(loading = true, busy = true, error = null) }
                if (!recoveryRestored && courseScope == null) {
                    when (val restored = recovery?.restore(identity)) {
                        null -> Unit
                        is OperationResult.Ok -> restored.value?.let { checkpoint ->
                            val locator = checkpoint.locator
                            if (locator !is ReaderLocator.Reflow || locator.documentId != documentId ||
                                !ReaderLocatorValidator.validate(locator).isValid || checkpoint.preferences == null)
                                return fail("INVALID_RECOVERY_LOCATOR")
                            committedLocator = locator
                            mutableState.update { it.copy(preferences = checkpoint.preferences) }
                        }
                        else -> {
                            val code = restored.code()
                            if (code == "SESSION_CHANGED" || code == "RUNTIME_CLOSED") {
                                stopInteractions(code)
                                cleanupScope.launch { closeAndAwait() }
                            }
                            return fail(code)
                        }
                    }
                    recoveryRestored = true
                }
                if (reopening) {
                    observer?.cancel(); observer = null
                    features?.cancel(); features = null
                    annotationObserver?.cancel(); annotationObserver = null
                    if (runtimeReady) committedLocator = runtime?.controller?.state?.value?.active?.report?.currentLocator as? ReaderLocator.Reflow ?: committedLocator
                    runtimeReady = false
                    releaseRuntime()
                    mutableState.update { it.copy(turnState = null) }
                    if (!runtimeFactory.retryCleanup()) return fail("REFLOW_RUNTIME_CLEANUP_REQUIRED")
                    if (stopped) return
                }
                val ctx = AccessContext(checkNotNull(identity.userId), identity.epoch, UUID.randomUUID().toString())
                val scope = courseScope
                val course = if (scope == null) null else when (val loaded = scope.loadSequence(ctx)) {
                    is OperationResult.Ok -> loaded.value
                    else -> return fail(loaded.code())
                }
                if (course != null && !recoveryRestored) {
                    when (val restored = courseRecovery?.restore(identity, course)) {
                        null -> Unit
                        is OperationResult.Ok -> restored.value?.let {
                            committedLocator = it.locator as? ReaderLocator.Reflow ?: return fail("INVALID_RECOVERY_LOCATOR")
                            mutableState.update { state -> state.copy(preferences = it.preferences) }
                        }
                        else -> return fail(restored.code())
                    }
                    recoveryRestored = true
                }
                val explicit = committedLocator ?: initialLocatorJson?.let(ReaderLocatorCodec::decodeOrNull)
                if ((committedLocator != null || initialLocatorJson != null) && (explicit !is ReaderLocator.Reflow ||
                        scope == null && explicit.documentId != documentId)) return fail("INVALID_INITIAL_LOCATOR")
                val selected = if (course == null) null else if (explicit != null) course.orderedTargets.singleOrNull {
                    it.documentId == explicit.documentId && it.contentRevision == explicit.contentRevision && it.firstSectionId == explicit.sectionId
                } else course.orderedTargets.firstOrNull { it.chapterKey == scope?.initialChapterKey }
                    ?: if (scope?.initialChapterKey == null) course.orderedTargets.firstOrNull() else null
                if (course != null && selected == null) return fail("COURSE_CHAPTER_NOT_FOUND")
                val selectedDocument = selected?.documentId ?: documentId
                val session = when (val result = openSession(checkNotNull(identity.userId), selectedDocument, explicit?.contentRevision ?: selected?.contentRevision)) {
                    is OperationResult.Ok -> result.value
                    else -> return fail(result.code())
                }
                if ((scope == null && session.document.kind != "IMPORTED") || FormatRegistry.capabilitiesOf(session.format).fixedLayout) return fail("REFLOW_IMPORTED_ONLY")
                if (!installSettings(identity,session)) return fail("REFLOW_SETTINGS_LOAD_FAILED")
                val sequence = course ?: session.readingSequence()
                val saved = explicit ?: if (scope == null) session.state?.locatorJson?.let(ReaderLocatorCodec::decodeOrNull) else null
                val locator = (saved as? ReaderLocator.Reflow)?.takeIf {
                    ReaderLocatorValidator.validateForDocument(it, selectedDocument, session.revision.revision).isValid &&
                        sequence.orderedTargets.any { target -> target.documentId == it.documentId && target.firstSectionId == it.sectionId }
                }
                if (explicit != null && locator == null) return fail("INVALID_INITIAL_LOCATOR")
                val target = selected ?: locator?.let { at -> sequence.orderedTargets.first { it.firstSectionId == at.sectionId } } ?: sequence.orderedTargets.first()
                val request = ReaderOpenRequest(target, if(locator == null) ReaderPlacement.START else ReaderPlacement.AT_LOCATOR, locator)
                val result = if (scope == null) runtimeFactory.open(ctx, request, state.value.preferences)
                    else runtimeFactory.openCourse(ctx, request, { scope.loadSequence(ctx) }, state.value.preferences)
                courseSequence = course
                if (result !is OperationResult.Ok) return fail(result.code())
                runtime = result.value
                currentCoroutineContext().ensureActive()
                if (stopped) return
                synchronizeSelection(result.value.controller)
                if (!runtimeFactory.revealActive(result.value.controller)) return fail("REFLOW_REVEAL_FAILED")
                if (stopped) return
                runtimeReady = true
                committedLocator = result.value.controller.state.value.active.report.currentLocator as? ReaderLocator.Reflow
                mutableState.update { it.copy(loading = false, title = session.document.title, readOnly = result.value.readOnly,
                    turnState = result.value.controller.state.value, error = null) }
                persistRecovery(result.value.controller)
                observe(result.value.controller)
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            fail("REFLOW_RUNTIME_LOAD_TIMEOUT")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { fail("REFLOW_RUNTIME_LOAD_FAILED")
        } finally {
            if (!stopped) { mutableState.update { it.copy(busy = leaveRequested) }; requestBookmarkRefresh() }
            if (stopped || !currentCoroutineContext().isActive) withContext(NonCancellable) {
                try { operation.withLock { releaseRuntime() } }
                catch (_: Exception) { mutableState.update { it.copy(cleanupFailure = "REFLOW_RUNTIME_CLEANUP_REQUIRED") } }
            }
            if (!stopped && layoutRequested) scheduleViewportRefresh()
            if (!stopped) scheduleSettingsLayout()
        }
    }

    /** Called with operation held; binding/subscription precede reveal and recovery IO. */
    private suspend fun synchronizeSelection(controller: ReaderRuntimeController) {
        if (stopped || runtime?.controller !== controller) return
        val active = controller.state.value.active
        val token = active.report.token
        val port = (active.adapter as? ReflowSelectionPortOwner)?.selectionPort
        if (controller.isClosed.value || port == null || !port.isActive(token)) {
            releaseSelection()
            return
        }
        val before = selectionOwner
        val sameOwner = before != null && before.controller === controller && before.token == token && before.port === port
        if (!sameOwner && before != null) endAnnotationSource("REFLOW_ANNOTATION_OWNER_CHANGED")
        val bound = selectionBinding.bind(active.report, port)
        if (bound !is OperationResult.Ok) {
            releaseSelection()
            return
        }
        if (sameOwner) return // A feature update must never reset the native gate or replay a flow.
        selectionCollectorGeneration = Math.addExact(selectionCollectorGeneration, 1L)
        // Its NonCancellable finally needs operation. Joining here would deadlock it.
        selectionCollector?.cancel()
        selectionCollector = null
        val owner = SelectionOwner(controller, token, port, selectionCollectorGeneration)
        selectionOwner = owner
        selectionCollector = viewModelScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try {
                port.events(token).collect { envelope ->
                    operation.withLock {
                        if (stopped) return@withLock
                        if (!isCurrentSelectionOwner(owner)) {
                            if (selectionOwner === owner) endAnnotationSource("REFLOW_ANNOTATION_OWNER_CHANGED")
                            return@withLock
                        }
                        if (controller.isClosed.value || !port.isActive(token)) {
                            endAnnotationSource("REFLOW_SELECTION_OWNER_UNAVAILABLE")
                            selectionBinding.sourceEnded(token, port)
                            selectionCollector?.cancel()
                            return@withLock
                        }
                        // The panel owns immutable A until explicit dismissal. Native focus/clear
                        // or a later B must not replace its business snapshot while editing.
                        if (annotationState.value.draft != null) return@withLock
                        val report = controller.state.value.active.report
                        if (selectionBinding.bind(report, port) is OperationResult.Ok)
                            selectionBinding.accept(report, port, envelope)
                        else {
                            selectionBinding.sourceEnded(token, port)
                            selectionCollector?.cancel()
                        }
                        requestBookmarkRefresh()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Selection failure does not fail the readable runtime. */ }
            finally {
                withContext(NonCancellable) {
                    operation.withLock {
                        if (selectionOwner === owner && selectionCollectorGeneration == owner.generation) {
                            endAnnotationSource("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
                            selectionBinding.sourceEnded(token, port)
                            selectionCollector = null
                        }
                    }
                }
            }
        }
    }

    private fun isCurrentSelectionOwner(owner: SelectionOwner): Boolean {
        if (selectionOwner !== owner || selectionCollectorGeneration != owner.generation ||
            runtime?.controller !== owner.controller) return false
        val active = owner.controller.state.value.active
        return active.report.token == owner.token &&
            (active.adapter as? ReflowSelectionPortOwner)?.selectionPort === owner.port
    }

    private suspend fun releaseSelection() {
        if (stopped) annotationEditor?.invalidate("REFLOW_SELECTION_OWNER_UNAVAILABLE")
        else endAnnotationSource("REFLOW_SELECTION_OWNER_UNAVAILABLE")
        val handle = selectionOwner?.token?.handle() ?: selectionBinding.state.value.selection.token?.handle()
        selectionCollectorGeneration = Math.addExact(selectionCollectorGeneration, 1L)
        selectionOwner = null
        selectionCollector?.cancel()
        selectionCollector = null
        handle?.let { selectionBinding.close(it) }
    }

    private fun observe(controller: ReaderRuntimeController) {
        annotationObserver?.cancel()
        annotationServices?.let { services ->
            annotationObserver = viewModelScope.launch {
                controller.state.map { it.active.report.token }.distinctUntilChanged().collectLatest { token ->
                    try {
                        services.observe(token.userId, token.documentId).collect { rows ->
                            val active = controller.state.value.active
                            if (stopped || runtime?.controller !== controller || active.report.token != token ||
                                ownerIdentity != SessionIdentity(token.userId, token.sessionEpoch)) return@collect
                            mutableState.update { it.copy(annotations=rows.filter { row -> row.documentId==token.documentId && row.kind!=AnnotationKind.INK &&
                                (row.targets.map { target -> target.locator }.ifEmpty { listOfNotNull(row.locator) }).all { target ->
                                    target.sectionId==active.report.currentLocator.sectionId && target.contentRevision==token.contentRevision
                                } }) }
                            val unmappable = linkedSetOf<String>()
                            val items = rows.filter { it.documentId == token.documentId && it.kind != AnnotationKind.INK }.mapNotNull { row ->
                                val targets = row.targets.map { it.locator }.ifEmpty { listOfNotNull(row.locator) }
                                    .filterIsInstance<ReaderLocator.Reflow>().filter {
                                        it.documentId == token.documentId && it.contentRevision == token.contentRevision &&
                                            it.sectionId == active.report.currentLocator.sectionId
                                    }
                                val proven = targets.filter { target ->
                                    val valid = selectionOwner?.takeIf { it.token == token && !it.sourceTerminated }?.port
                                        ?.validateSource(token, listOf(ReaderSelectionFragment(target, target.exactQuote)))?.isValid == true
                                    if (!valid) unmappable += row.id
                                    valid
                                }
                                if (proven.isEmpty()) null else ReaderDecoration(row.id, row.revision, proven, row.style.color.hex, row.style.underline)
                            }
                            mutableState.update { it.copy(annotationUnmappableIds = unmappable.toSet()) }
                            val result = active.adapter.decorate(token, items)
                            if (runtime?.controller === controller && controller.state.value.active.report.token == token && !stopped) {
                                if (result !is OperationResult.Ok) {
                                    active.adapter.decorate(token, emptyList())
                                    mutableState.update { it.copy(error = "REFLOW_ANNOTATION_DECORATION_FAILED") }
                                }
                                else mutableState.update { if (it.error == "REFLOW_ANNOTATION_DECORATION_FAILED") it.copy(error = null) else it }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (!stopped) mutableState.update { it.copy(error = "REFLOW_ANNOTATION_DECORATION_FAILED") } }
                }
            }
        }
        features = viewModelScope.launch {
            try { controller.collectFeatureEvents() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail("REFLOW_EVENTS_FAILED") }
        }
        observer = viewModelScope.launch {
            combine(controller.state, controller.saveFailure, controller.cleanupFailure) { turn, save, cleanup -> Triple(turn, save, cleanup) }
                .collect { (_, _, _) -> operation.withLock {
                    if (stopped) return@withLock
                    try { refresh(controller) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { fail("REFLOW_REVEAL_FAILED") }
                } }
        }
    }
    private suspend fun refresh(controller: ReaderRuntimeController) {
        if (stopped || runtime?.controller !== controller) return
        synchronizeSelection(controller)
        committedLocator = controller.state.value.active.report.currentLocator as? ReaderLocator.Reflow ?: committedLocator
        val revealed = runtimeFactory.revealActive(controller)
        if (stopped) return
        val active = controller.state.value.active
        if (settingsServices != null && settingsDocumentId != active.target.documentId) {
            val identity = ownerIdentity ?: return
            val loaded = openSession(checkNotNull(identity.userId),active.target.documentId,active.target.contentRevision)
            if (loaded !is OperationResult.Ok || !installSettings(identity,loaded.value)) {
                mutableState.update { it.copy(settingsFailure="REFLOW_SETTINGS_LOAD_FAILED") }
            }
        }
        val activeTitle = if (courseScope == null) state.value.title else {
            when (val session = openSession(checkNotNull(ownerIdentity?.userId), active.target.documentId, active.target.contentRevision)) {
                is OperationResult.Ok -> session.value.document.title
                else -> state.value.title
            }
        }
        if (stopped) return
        mutableState.update { it.copy(title = activeTitle, turnState = controller.state.value, saveFailure = controller.saveFailure.value,
            cleanupFailure = controller.cleanupFailure.value?.code ?: runtimeFactory.cleanupFailure.value,
            error = if (revealed) it.error else "REFLOW_REVEAL_FAILED") }
        if (revealed) persistRecovery(controller)
        requestBookmarkRefresh()
    }
    fun turn(direction: ReaderTurnDirection, origin: ReaderTurnOrigin) = perform { it.turn(direction, origin) }
    fun previous() = turn(ReaderTurnDirection.PREVIOUS, ReaderTurnOrigin.BOTTOM_BUTTON)
    fun next() = turn(ReaderTurnDirection.NEXT, ReaderTurnOrigin.BOTTOM_BUTTON)
    fun confirm() = perform { controller -> controller.state.value.confirmation?.let { controller.confirm(it) } }
    fun cancel() = perform { controller -> controller.state.value.confirmation?.let { controller.cancel(it) } }
    fun retrySave() = perform { it.retrySave() }
    internal suspend fun confirmSelection(capture: ReflowSelectionCapture): OperationResult<Unit> =
        selectionOperation(capture) { selectionBinding.confirmStable(capture) }
    internal suspend fun copySelection(capture: ReflowSelectionCapture): OperationResult<ReflowSelectionCopyReceipt> =
        selectionOperation(capture) {
            if (state.value.loading || state.value.busy || state.value.closed ||
                state.value.turnState?.phase != ReaderTurnPhase.IDLE) return@selectionOperation OperationResult.Rejected("REFLOW_SELECTION_OWNER_UNAVAILABLE")
            val job = currentCoroutineContext().job
            selectionCopyJob = job
            try {
                if (stopped || leaveRequested) return@selectionOperation OperationResult.Rejected("STALE_SELECTION")
                currentCoroutineContext().ensureActive()
                selectionBinding.copy(capture)
            } finally { if (selectionCopyJob === job) selectionCopyJob = null }
        }
    internal suspend fun clearSelection(capture: ReflowSelectionCapture): OperationResult<Unit> =
        selectionOperation(capture) { selectionBinding.clear(capture) }

    /** Stored annotation editing has its own capability; it never enables native copy or clear. */
    internal fun canBeginAnnotation(capture: ReflowSelectionCapture): Boolean {
        val selected = selectionState.value
        val session = selected.selection.session ?: return false
        val owner = selectionOwner ?: return false
        if (stopped || leaveRequested || state.value.loading || state.value.busy || state.value.closed || state.value.readOnly ||
            annotationState.value.draft != null || existingAnnotationState.value.isOpen ||
            state.value.turnState?.phase != ReaderTurnPhase.IDLE || owner.sourceTerminated ||
            !isCurrentSelectionOwner(owner) || !owner.port.isActive(owner.token) || selected.capture != capture ||
            owner.token != capture.binding.token || session.token != owner.token || session.selectionId != capture.selectionId ||
            session.phase != SelectionSessionPhase.STABLE || !owner.port.validateSource(owner.token, session.fragments).isValid)
            return false
        if (selected.usableNativeRange) return true
        val saved = retainedAnnotation?.takeIf { it.first == capture }?.second ?: return false
        return saved.kind != AnnotationKind.INK && saved.revision > 0 && saved.documentId == owner.token.documentId &&
            saved.targets == session.fragments.map { AnnotationTargetInput(it.locator, "EXACT") }
    }

    /** Read the persisted baseline outside the VM lock, then recheck the exact owner under it. */
    internal suspend fun beginAnnotation(capture: ReflowSelectionCapture,
        kind: AnnotationKind = AnnotationKind.NOTE): OperationResult<Unit> {
        val savedOwner = selectionOwner
        val savedIdentity = ownerIdentity
        val savedSnapshot = savedOwner?.port?.originalSnapshot(capture.binding.token)
        val saved = if (!selectionState.value.usableNativeRange) {
            val retained = retainedAnnotation?.takeIf { it.first == capture }?.second
                ?: return OperationResult.Rejected("REFLOW_ANNOTATION_STABLE_REQUIRED")
            val services = annotationServices ?: return OperationResult.Rejected("REFLOW_ANNOTATION_UNAVAILABLE")
            if (savedOwner == null || savedIdentity?.userId == null || savedSnapshot == null ||
                savedOwner.sourceTerminated || savedOwner.token != capture.binding.token ||
                !isCurrentSelectionOwner(savedOwner)) return OperationResult.Rejected("STALE_SELECTION")
            try {
                if (services.identityNow() != savedIdentity) return OperationResult.Rejected("SESSION_CHANGED")
                val context = AccessContext(checkNotNull(savedIdentity.userId), savedIdentity.epoch, UUID.randomUUID().toString())
                val opened = services.access.open(context, savedSnapshot.documentId, savedSnapshot.contentRevision)
                if (opened !is OperationResult.Ok || opened.value.documentId != savedSnapshot.documentId ||
                    opened.value.contentRevision != savedSnapshot.contentRevision ||
                    opened.value.accessEpoch != savedSnapshot.accessEpoch || opened.value.ownerUserId != savedSnapshot.ownerUserId)
                    return OperationResult.Rejected("SOURCE_PROOF_STALE")
                val latest = services.observe(context.userId, savedSnapshot.documentId).first()
                    .singleOrNull { it.id == retained.id } ?: return OperationResult.Rejected("ANNOTATION_NOT_FOUND")
                if (services.identityNow() != savedIdentity || latest.revision < retained.revision || latest.kind != retained.kind)
                    return OperationResult.Rejected("SOURCE_PROOF_STALE")
                latest
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return OperationResult.Failed("ANNOTATION_REOPEN_FAILED", true) }
        } else null
        return selectionOperation(capture) {
            val editor = annotationEditor ?: return@selectionOperation OperationResult.Rejected("REFLOW_ANNOTATION_UNAVAILABLE")
            if (kind != AnnotationKind.NOTE && kind != AnnotationKind.HIGHLIGHT)
                return@selectionOperation OperationResult.Rejected("INVALID_ANNOTATION_DRAFT")
            if (state.value.loading || state.value.busy || state.value.readOnly ||
                state.value.turnState?.phase != ReaderTurnPhase.IDLE)
                return@selectionOperation OperationResult.Rejected("REFLOW_ANNOTATION_OWNER_UNAVAILABLE")
            val owner = selectionOwner ?: return@selectionOperation OperationResult.Rejected("STALE_SELECTION")
            val selected = selectionState.value.selection.session
                ?: return@selectionOperation OperationResult.Rejected("NO_SELECTION")
            val identity = ownerIdentity ?: return@selectionOperation OperationResult.Rejected("SESSION_CHANGED")
            val fragments = selected.fragments
            if (fragments.isEmpty()) return@selectionOperation OperationResult.Rejected("NO_SELECTION")
            if (selected.phase != SelectionSessionPhase.STABLE || selected.token != owner.token ||
                !owner.port.validateSource(owner.token, fragments).isValid)
                return@selectionOperation OperationResult.Rejected("REFLOW_ANNOTATION_STABLE_REQUIRED")
            val original = owner.port.originalSnapshot(owner.token)
                ?: return@selectionOperation OperationResult.Rejected("REFLOW_ANNOTATION_SOURCE_PROOF_REQUIRED")
            if (saved != null) {
                if (owner !== savedOwner || identity != savedIdentity || original != savedSnapshot)
                    return@selectionOperation OperationResult.Rejected("SOURCE_PROOF_STALE")
            } else {
                val handed = selectionBinding.temporaryFocusHandoff(capture)
                if (handed !is OperationResult.Ok) return@selectionOperation handed
            }
            if (stopped || leaveRequested || !isCurrentSelectionOwner(owner))
                return@selectionOperation OperationResult.Rejected("STALE_SELECTION")
            val begun = editor.begin(capture, fragments, kind, identity, original)
            if (begun !is OperationResult.Ok) return@selectionOperation begun
            val draftId = editor.state.value.draft?.draftId
                ?: return@selectionOperation OperationResult.Rejected("STALE_SELECTION")
            val targets=fragments.map { AnnotationTargetInput(it.locator,"EXACT") }
            val retained=retainedAnnotation?.takeIf { it.first==capture }?.second
            if (saved != null && saved.targets != targets) {
                editor.dismiss(draftId)
                return@selectionOperation OperationResult.Rejected("ANNOTATION_SELECTION_MISMATCH")
            }
            val matches=(state.value.annotations+listOfNotNull(retained,saved)).filter { it.targets==targets }.groupBy { it.id }
            if(matches.size>1){editor.dismiss(draftId);return@selectionOperation OperationResult.Rejected("ANNOTATION_SELECTION_AMBIGUOUS")}
            (saved ?: matches.values.singleOrNull()?.maxByOrNull { it.revision })?.let { existing ->
                val attached=editor.attachExisting(draftId,existing)
                if(attached !is OperationResult.Ok){editor.dismiss(draftId);return@selectionOperation attached}
            }
            val editing = if (saved == null) selectionBinding.beginEditing(capture, draftId)
                else selectionBinding.beginSavedEditing(capture, draftId, fragments)
            if (editing !is OperationResult.Ok) editor.dismiss(draftId)
            if (stopped || leaveRequested) {
                editor.invalidate("REFLOW_ANNOTATION_OWNER_UNAVAILABLE")
                return@selectionOperation OperationResult.Rejected("STALE_SELECTION")
            }
            editing
        }
    }

    internal fun changeAnnotationNote(draftId: String, text: String): OperationResult<Unit> =
        if (stopped || leaveRequested) OperationResult.Rejected("REFLOW_ANNOTATION_OWNER_UNAVAILABLE")
        else annotationEditor?.changeNote(draftId, text) ?: OperationResult.Rejected("REFLOW_ANNOTATION_UNAVAILABLE")

    internal fun changeAnnotationStyle(draftId: String, style: AnnotationStyle): OperationResult<Unit> =
        if (stopped || leaveRequested) OperationResult.Rejected("REFLOW_ANNOTATION_OWNER_UNAVAILABLE")
        else annotationEditor?.changeStyle(draftId, style) ?: OperationResult.Rejected("REFLOW_ANNOTATION_UNAVAILABLE")

    internal suspend fun saveAnnotation(draftId: String): OperationResult<ReaderAnnotationView> {
        val editor = annotationEditor ?: return OperationResult.Rejected("REFLOW_ANNOTATION_UNAVAILABLE")
        val pending = operation.withLock {
            val editing = annotationState.value
            val draft = editing.draft
            if (stopped || leaveRequested || state.value.closed || draft == null || draft.draftId != draftId ||
                ownerIdentity != draft.identity)
                return OperationResult.Rejected("REFLOW_ANNOTATION_OWNER_UNAVAILABLE")
            // A lost source can only replay the already frozen uncertain operation. Editor
            // checks its exact pending ticket and current identity; its Guard still rejects writes.
            val receiptOnly = editing.sourceUnavailable && editing.outcomeUnknown
            if (!receiptOnly && !validateAnnotationOwner(draft))
                return OperationResult.Rejected("REFLOW_ANNOTATION_OWNER_UNAVAILABLE")
            if (annotationSaveJob?.isCompleted == false) return OperationResult.Rejected("REFLOW_ANNOTATION_SAVE_IN_PROGRESS")
            // LAZY prevents the repository from starting before this lock is released.
            viewModelScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) { editor.save(draftId) }
                .also { annotationSaveJob = it }
        }
        pending.invokeOnCompletion { if (annotationSaveJob === pending) annotationSaveJob = null }
        pending.start()
        return try { pending.await().also { result ->
            if(result is OperationResult.Ok)annotationState.value.draft?.takeIf { it.draftId==draftId }?.let { retainedAnnotation=it.capture to result.value }
        } }
        catch (cancelled: CancellationException) {
            if (stopped) OperationResult.Rejected("REFLOW_ANNOTATION_OWNER_UNAVAILABLE") else throw cancelled
        }
    }

    internal suspend fun reloadAnnotationConflict(draftId: String): OperationResult<Unit> {
        val editor = annotationEditor ?: return OperationResult.Rejected("REFLOW_ANNOTATION_UNAVAILABLE")
        val draft = annotationState.value.draft
        if (draft == null || draft.draftId != draftId || !validateAnnotationOwner(draft))
            return OperationResult.Rejected("REFLOW_ANNOTATION_OWNER_UNAVAILABLE")
        return editor.reloadConflict(draftId)
    }

    fun navigateToAnnotation(annotationId: String) {
        val services = annotationServices ?: return
        val owner = selectionOwner ?: return
        val identity = ownerIdentity ?: return
        val view = state.value.annotations.singleOrNull { it.id == annotationId } ?: return
        val snapshot = owner.port.originalSnapshot(owner.token) ?: return
        val targets = view.targets.map { it.locator }.ifEmpty { listOfNotNull(view.locator) }
        val locator = targets.firstOrNull() as? ReaderLocator.Reflow ?: return
        fun owns(): Boolean = !stopped && !leaveRequested && !state.value.closed && !state.value.loading &&
            !state.value.settingsLeaveConfirmation && annotationState.value.draft == null && !existingAnnotationState.value.isOpen &&
            !selectionState.value.usableNativeRange && selectionState.value.selection.session?.phase != SelectionSessionPhase.EDITING &&
            !layoutRequested && isCurrentSelectionOwner(owner) &&
            !owner.sourceTerminated && ownerIdentity == identity && owner.port.originalSnapshot(owner.token) == snapshot &&
            state.value.turnState?.phase == ReaderTurnPhase.IDLE &&
            state.value.annotations.singleOrNull { it.id == annotationId } == view
        if (state.value.busy || !owns()) return
        perform { controller ->
            if (!owns() || services.identityNow() != identity) return@perform OperationResult.Rejected("STALE_ANNOTATION_NAVIGATION")
            val latest = services.observe(checkNotNull(identity.userId), owner.token.documentId).first()
                .singleOrNull { it.id == annotationId }
            if (latest != view || !owns() || services.identityNow() != identity ||
                locator.documentId != owner.token.documentId || locator.contentRevision != owner.token.contentRevision ||
                targets.any { it.sectionId != controller.state.value.active.report.currentLocator.sectionId } ||
                !owner.port.validateSource(owner.token, targets.map { target ->
                    ReaderSelectionFragment(target, (target as? ReaderLocator.Reflow)?.exactQuote.orEmpty())
                }).isValid) return@perform OperationResult.Rejected("STALE_ANNOTATION_NAVIGATION")
            // The adapter checks live access before and after native range positioning.
            // Controller.navigate commits the successful locator through the production progress sink.
            when (val result = controller.navigate(locator)) {
                is ReaderNavigationResult.Exact -> OperationResult.Ok(Unit, "annotation-navigation")
                is ReaderNavigationResult.Approximate -> OperationResult.Rejected("ANNOTATION_NAVIGATION_APPROXIMATE")
                is ReaderNavigationResult.Unavailable -> OperationResult.Rejected(result.code)
            }
        }
    }

    fun openExistingAnnotation(annotationId:String) {
        val services=annotationServices ?: return
        val owner=selectionOwner ?: return
        val identity=ownerIdentity ?: return
        val view=state.value.annotations.singleOrNull { it.id==annotationId } ?: return
        val snapshot=owner.port.originalSnapshot(owner.token) ?: return
        if(stopped || leaveRequested || state.value.loading || state.value.busy || state.value.readOnly ||
            annotationState.value.draft!=null || existingAnnotationState.value.isOpen)return
        val targets=view.targets.ifEmpty { listOfNotNull(view.locator).map { AnnotationTargetInput(it,"UNRESOLVED") } }
        fun owns():Boolean = !stopped && !leaveRequested && selectionOwner===owner && ownerIdentity==identity &&
            !owner.sourceTerminated && isCurrentSelectionOwner(owner) && owner.port.originalSnapshot(owner.token)==snapshot &&
            !state.value.readOnly && owner.port.validateSource(owner.token,targets.map {
                ReaderSelectionFragment(it.locator,(it.locator as? ReaderLocator.Reflow)?.exactQuote.orEmpty()) }).isValid
        if(!owns())return
        val guard=AnnotationWriteGuard(snapshot.documentId,snapshot.contentRevision,snapshot.accessEpoch){ctx,_,actual ->
            if(ctx.userId!=identity.userId || ctx.sessionEpoch!=identity.epoch || actual!=targets || !owns() || services.identityNow()!=identity)
                OperationResult.Rejected("SOURCE_PROOF_STALE")
            else {
                val latest=services.access.open(ctx,snapshot.documentId,snapshot.contentRevision)
                if(latest !is OperationResult.Ok || latest.value.accessEpoch!=snapshot.accessEpoch || latest.value.ownerUserId!=snapshot.ownerUserId ||
                    !owns() || services.identityNow()!=identity)OperationResult.Rejected("SOURCE_PROOF_STALE")
                else OperationResult.Ok(Unit,ctx.requestId)
            }
        }
        annotationActions?.open(AccessContext(checkNotNull(identity.userId),identity.epoch,UUID.randomUUID().toString()),view,guard)
    }
    fun editExistingAnnotationNote(note:String?){annotationActions?.editNote(note)}
    fun chooseExistingAnnotationColor(color:AnnotationColor){annotationActions?.chooseColor(color)}
    fun saveExistingAnnotation(){viewModelScope.launch { annotationActions?.save() }}
    fun deleteExistingAnnotation(){viewModelScope.launch { annotationActions?.delete();if(existingAnnotationState.value.deleted)retainedAnnotation=null }}
    fun undoExistingAnnotation(){viewModelScope.launch { annotationActions?.undo() }}
    fun retryExistingAnnotation(){viewModelScope.launch { annotationActions?.retry() }}
    fun closeExistingAnnotation() {
        if (annotationActions?.discard() == true && !stopped && layoutRequested) scheduleViewportRefresh()
    }
    fun reloadExistingAnnotation(){viewModelScope.launch {
        val before=existingAnnotationState.value
        val original=before.original ?: return@launch
        val services=annotationServices ?: return@launch
        val identity=ownerIdentity ?: return@launch
        if(services.identityNow()!=identity)return@launch
        val latest=services.observe(checkNotNull(identity.userId),original.documentId).first().singleOrNull { it.id==original.id } ?: return@launch
        if(!stopped && services.identityNow()==identity && existingAnnotationState.value===before)annotationActions?.rebase(latest)
    }}

    /** The UI already obtained explicit discard/close intent, including failed unchanged drafts. */
    internal suspend fun dismissAnnotation(draftId: String): OperationResult<Unit> {
        val result = operation.withLock {
            val editor = annotationEditor ?: return@withLock OperationResult.Rejected("REFLOW_ANNOTATION_UNAVAILABLE")
            val draft = annotationState.value.draft
            if (stopped || leaveRequested || draft == null || draft.draftId != draftId)
                return@withLock OperationResult.Rejected("REFLOW_ANNOTATION_OWNER_UNAVAILABLE")
            if (annotationSaveJob?.isCompleted == false || annotationState.value.editBlocked)
                return@withLock OperationResult.Rejected("REFLOW_ANNOTATION_SAVE_IN_PROGRESS")
            val session = selectionState.value.selection.session
            if (session?.phase == SelectionSessionPhase.EDITING && session.editingNoteDraftId == draftId) {
                val finished = selectionBinding.finishEditing(draft.capture, draftId)
                if (finished !is OperationResult.Ok) return@withLock finished
            }
            // The saved StateFlow can reach the UI before saveAnnotation's awaiting caller resumes.
            // Keep the confirmed row here before explicit close consumes that draft.
            annotationState.value.saved?.takeIf { saved ->
                saved.id == draft.annotationId && saved.revision == draft.baseRevision &&
                    saved.targets == draft.fragments.map { AnnotationTargetInput(it.locator, "EXACT") }
            }?.let { retainedAnnotation = draft.capture to it }
            // A revoked/replaced binding may already have consumed its EDITING state. The
            // explicit discard remains successful without clearing an unrelated selection.
            editor.dismiss(draftId)
        }
        if (result is OperationResult.Ok && !stopped && layoutRequested) scheduleViewportRefresh()
        return result
    }

    /** IO-free callback: Room Guard must never acquire the VM lock held by reader/recovery IO. */
    private fun validateAnnotationOwner(draft: ReflowAnnotationDraft): Boolean {
        if (stopped || leaveRequested || ownerIdentity != draft.identity || state.value.closed || state.value.readOnly ||
            annotationState.value.sourceUnavailable) return false
        val owner = selectionOwner ?: return false
        if (owner.sourceTerminated || !isCurrentSelectionOwner(owner) || owner.controller.isClosed.value || owner.token != draft.capture.binding.token ||
            !owner.port.isActive(owner.token)) return false
        val selected = selectionState.value
        val session = selected.selection.session ?: return false
        if (selected.capture != draft.capture || selected.selection.token != owner.token || session.token != owner.token ||
            session.selectionId != draft.capture.selectionId || session.phase != SelectionSessionPhase.EDITING ||
            session.editingNoteDraftId != draft.draftId || session.fragments != draft.fragments) return false
        return try {
            owner.port.originalSnapshot(owner.token) == draft.originalSnapshot &&
                owner.port.validateSource(owner.token, draft.fragments).isValid
        } catch (_: Exception) { false }
    }

    private fun endAnnotationSource(code: String) {
        selectionOwner?.sourceTerminated = true
        annotationEditor?.sourceLost(code)
    }
    private suspend fun <T> selectionOperation(capture: ReflowSelectionCapture,
        action: suspend () -> OperationResult<T>): OperationResult<T> = operation.withLock {
        if (annotationState.value.draft != null) return@withLock OperationResult.Rejected("REFLOW_ANNOTATION_EDITOR_ACTIVE")
        val owner = selectionOwner
        if (stopped || leaveRequested || owner == null || owner.sourceTerminated || !isCurrentSelectionOwner(owner) ||
            owner.controller.isClosed.value || capture.binding.token != owner.token ||
            selectionBinding.state.value.capture != capture) return@withLock OperationResult.Rejected("STALE_SELECTION")
        if (!owner.port.isActive(owner.token)) {
            selectionBinding.sourceEnded(owner.token, owner.port)
            return@withLock OperationResult.Rejected("REFLOW_SELECTION_OWNER_UNAVAILABLE")
        }
        action()
    }
    @Synchronized
    fun retryOpen() {
        val identity = ownerIdentity ?: return
        if (stopped || leaveRequested || annotationState.value.draft != null || existingAnnotationState.value.isOpen || state.value.loading || state.value.busy) return
        val pending = action
        mutableState.update { it.copy(loading = true, busy = true, error = null) }
        opening = viewModelScope.launch(Dispatchers.IO) { pending?.join(); load(identity, reopening = true) }
    }
    fun applyPreferences(preferences: ReaderLayoutPreferences) = perform { controller ->
        val result = controller.applyPreferences(preferences)
        if (result is OperationResult.Ok) {
            mutableState.update { it.copy(preferences = preferences) }
        }
        result
    }

    private suspend fun installSettings(identity: SessionIdentity, session: ReaderSession): Boolean {
        val services=settingsServices ?: return true
        if(settingsDocumentId==session.document.id && settingsRevision==session.revision.revision && settingsPersistence!=null) return true
        if(settingsPersistence?.flush()==false)return false
        val user=identity.userId ?: return false
        val defaults=try { services.observeDefaults(user).first() }
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){mutableState.update { it.copy(settingsFailure="READER_DEFAULTS_LOAD_FAILED") };return false}
        settingsObserver?.cancel();defaultsObserver?.cancel();settingsPersistence?.close()
        settingsDocumentId=session.document.id;settingsRevision=session.revision.revision
        val catalogRevision=services.currentRevision(session.document.id) ?: return false
        val historical=catalogRevision!=session.revision.revision
        suspend fun valid():Boolean = !stopped && ownerIdentity==identity && services.identityNow()==identity &&
            settingsDocumentId==session.document.id && settingsRevision==session.revision.revision &&
            (historical || services.currentRevision(session.document.id)==session.revision.revision)
        val persistence=ReaderSettingsPersistence(viewModelScope,defaults,session.state?.settingsJson,historical,::valid,
            { json -> services.saveForRevision(user,session.document.id,json,session.revision.revision,::valid) },
            { value -> if(valid()) services.saveDefaults(user,value) else OperationResult.Rejected("SESSION_CHANGED") })
        settingsPersistence=persistence
        val initial=persistence.state.value
        mutableState.update { it.copy(settings=initial.settings,settingsInheritsDefaults=initial.inheritsDefaults,readOnly=historical,
            settingsUnsupported=reflowSettingsUnsupported(initial.settings),settingsFailure=null,
            preferences=if(!runtimeReady)reflowLayoutSettings(initial.settings) else it.preferences) }
        settingsObserver=viewModelScope.launch {
            persistence.state.collect { saved ->
                if(settingsPersistence===persistence){
                    mutableState.update { it.copy(settings=saved.settings,settingsPending=saved.pending,settingsSaving=saved.saving,
                        settingsFailure=saved.failure,settingsOutcomeUnknown=saved.outcomeUnknown,settingsRetryable=saved.retryable,
                        settingsAbandoned=saved.abandoned,
                        settingsInheritsDefaults=saved.inheritsDefaults,
                        settingsUnsupported=if(it.settings!=saved.settings)null else it.settingsUnsupported) }
                    scheduleSettingsLayout()
                }
            }
        }
        defaultsObserver=viewModelScope.launch {
            try { services.observeDefaults(user).collect { if(settingsPersistence===persistence && valid())persistence.updateDefaults(it) } }
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(settingsPersistence===persistence)mutableState.update { it.copy(settingsFailure="READER_DEFAULTS_LOAD_FAILED") }}
        }
        return true
    }

    fun updateSettings(settings: ReaderSettings) {
        if(stopped || leaveRequested || state.value.loading || state.value.readOnly || annotationState.value.draft!=null || existingAnnotationState.value.isOpen)return
        if(reflowSettingsUnsupported(settings)!=null){mutableState.update { it.copy(settingsUnsupported="REFLOW_SCROLL_UNSUPPORTED") };return}
        if(settingsPersistence!=null)settingsPersistence?.edit(settings)
        else { mutableState.update { it.copy(settings=settings,settingsUnsupported=null) };applyPreferences(reflowLayoutSettings(settings)) }
    }
    fun useDefaultSettings(){if(!stopped && !leaveRequested && !state.value.readOnly && annotationState.value.draft==null && !existingAnnotationState.value.isOpen)settingsPersistence?.useDefaults()}
    fun makeDefaultSettings(){if(!stopped && !leaveRequested && !state.value.readOnly && annotationState.value.draft==null && !existingAnnotationState.value.isOpen)settingsPersistence?.makeDefaults()}
    fun retrySettingsSave(){settingsPersistence?.retry();scheduleSettingsLayout()}

    /** Coalesce requested layouts without cancelling a native layout operation already in flight. */
    private fun scheduleSettingsLayout() {
        // Legacy hosts supply layout preferences directly and have no settings persistence.
        // Projecting unrelated default settings would silently rebind their native selection.
        if(settingsServices==null || stopped || !runtimeReady || settingsLayoutJob?.isActive==true)return
        var applyFailed=false
        settingsLayoutJob=viewModelScope.launch(Dispatchers.IO) {
            while(!stopped && !leaveRequested) {
                if(reflowLayoutSettings(state.value.settings)==state.value.preferences)return@launch
                if(state.value.busy || state.value.loading || annotationState.value.draft!=null || existingAnnotationState.value.isOpen ||
                    selectionState.value.selection.session?.phase in setOf(SelectionSessionPhase.DRAGGING,SelectionSessionPhase.STABLE,SelectionSessionPhase.EDITING)) {
                    delay(50);continue
                }
                var failed=false
                operation.withLock {
                    val controller=runtime?.controller ?: return@withLock
                    if(stopped || leaveRequested || state.value.loading || state.value.busy || annotationState.value.draft!=null ||
                        existingAnnotationState.value.isOpen || controller.state.value.phase!=ReaderTurnPhase.IDLE ||
                        selectionState.value.selection.session?.phase in setOf(SelectionSessionPhase.DRAGGING,SelectionSessionPhase.STABLE,SelectionSessionPhase.EDITING))return@withLock
                    val desired=reflowLayoutSettings(state.value.settings)
                    if(desired==state.value.preferences)return@withLock
                    mutableState.update { it.copy(busy=true) }
                    try {
                        val result=controller.applyPreferences(desired)
                        if(result is OperationResult.Ok){
                            mutableState.update { it.copy(preferences=desired,settingsApplyFailure=null,settingsUnsupported=null) }
                            refresh(controller)
                            persistRecovery(controller)
                        }else{failed=true;mutableState.update { it.copy(settingsApplyFailure="REFLOW_SETTINGS_APPLY_FAILED",
                            settingsUnsupported=(result as? OperationResult.Rejected)?.code?.takeIf { code -> code.contains("UNSUPPORTED") }) }}
                    }catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){failed=true;mutableState.update { it.copy(settingsApplyFailure="REFLOW_SETTINGS_APPLY_FAILED") }
                    }finally{mutableState.update { it.copy(busy=false) }}
                }
                if(failed){applyFailed=true;return@launch}
                delay(10)
            }
        }
        settingsLayoutJob?.invokeOnCompletion { cause ->
            if(cause==null && !applyFailed)viewModelScope.launch {
                if(!stopped && !leaveRequested && reflowLayoutSettings(state.value.settings)!=state.value.preferences)
                    scheduleSettingsLayout()
            }
        }
    }
    /** Size changes are coalesced; a confirmation is never converted into an implicit chapter turn. */
    fun viewportChanged(width: Int, height: Int) {
        if (stopped || width <= 0 || height <= 0) return
        val previous = viewportSize
        viewportSize = width to height
        if (runtimeReady && !state.value.loading && previous != null && previous != viewportSize) viewportChanged()
    }
    fun viewportChanged() {
        if (stopped) return
        layoutRequested = true
        scheduleViewportRefresh()
    }
    private fun scheduleViewportRefresh() {
        if (stopped || leaveRequested || annotationState.value.draft != null || existingAnnotationState.value.isOpen || state.value.loading || state.value.busy || action?.isActive == true ||
            runtime?.controller?.state?.value?.phase != ReaderTurnPhase.IDLE) return
        perform { controller ->
            layoutRequested = false
            val result = controller.applyPreferences(state.value.preferences)
            result
        }
    }
    @Synchronized
    private fun perform(block: suspend (ReaderRuntimeController) -> Any?) {
        if (stopped || leaveRequested || annotationState.value.draft != null || existingAnnotationState.value.isOpen || state.value.loading || state.value.busy || action?.isActive == true) return
        mutableState.update { it.copy(busy = true, error = null) }
        action = viewModelScope.launch(Dispatchers.IO) {
            operation.withLock {
                val controller = runtime?.controller ?: return@withLock
                if (stopped || annotationState.value.draft != null) return@withLock
                mutableState.update { it.copy(busy = true, error = null) }
                try {
                    val decision = block(controller)
                    if (decision is TurnDecision.Failed) fail(decision.code)
                    if (decision is OperationResult<*> && decision !is OperationResult.Ok<*>) fail(decision.code())
                    refresh(controller)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { fail("REFLOW_OPERATION_FAILED")
                }
            }
            // Completion runs after this action is no longer active, allowing a queued resize.
        }
        action?.invokeOnCompletion { viewModelScope.launch {
            if (!stopped) mutableState.update { it.copy(busy = leaveRequested) }
            if (!stopped && layoutRequested) scheduleViewportRefresh()
        } }
    }
    fun retryCleanup() { cleanupScope.launch {
        if (stopped) closeAndAwait()
        else try { operation.withLock {
            runtime?.controller?.retryCleanup()
            runtimeFactory.retryCleanup()
            runtime?.controller?.let { refresh(it) }
        } } catch (_: Exception) { mutableState.update { it.copy(cleanupFailure = "REFLOW_RUNTIME_CLEANUP_REQUIRED") } }
    } }
    private suspend fun persistRecovery(controller: ReaderRuntimeController): Boolean {
        val identity = ownerIdentity ?: return false
        val courseSink = courseRecovery
        if (courseScope != null) {
            if (courseSink == null) return true
            val sequence = courseSequence ?: return false
            if (stopped || !runtimeReady || runtime?.controller !== controller) return false
            val context = AccessContext(checkNotNull(identity.userId), identity.epoch, UUID.randomUUID().toString())
            val latest = courseScope.loadSequence(context)
            currentCoroutineContext().ensureActive()
            if (stopped || ownerIdentity != identity) return false
            if (latest !is OperationResult.Ok || latest.value.version != sequence.version ||
                latest.value.orderedTargets != sequence.orderedTargets) {
                fail("COURSE_SEQUENCE_CHANGED")
                return false
            }
            val active = controller.state.value.active
            val result = courseSink.save(identity, sequence, active.target, active.report.currentLocator, state.value.preferences)
            if (result is OperationResult.Ok) return true
            fail(result.code())
            return false
        }
        val sink = recovery ?: return true
        if (stopped || !runtimeReady || runtime?.controller !== controller) return false
        val locator = controller.state.value.active.report.currentLocator as? ReaderLocator.Reflow ?: return false
        val checkpoint = ReaderRuntimeCheckpoint(locator, state.value.preferences)
        if (lastRecovery == checkpoint) return true
        val saved = sink.save(identity, locator, checkpoint.preferences)
        if (saved is OperationResult.Ok) { lastRecovery = checkpoint; return true }
        fail(saved.code())
        return false
    }
    fun requestLeave() = requestLeave(abandonSettings = false)
    fun cancelLeaveWithoutSettings() { mutableState.update { it.copy(settingsLeaveConfirmation = false) } }
    fun confirmLeaveWithoutSettings() {
        if (state.value.settingsLeaveConfirmation) requestLeave(abandonSettings = true)
    }
    @Synchronized
    private fun requestLeave(abandonSettings: Boolean) {
        if (stopped || leaveRequested || annotationState.value.draft != null || existingAnnotationState.value.isOpen) return
        leaveRequested = true
        val pendingOpen = opening
        val pendingAction = action
        mutableState.update { it.copy(busy = true, settingsLeaveConfirmation = false) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                pendingOpen?.join(); pendingAction?.join()
                val canLeave = operation.withLock {
                    if (stopped) return@withLock false
                    if (abandonSettings) settingsPersistence?.abandonPending()
                    else if (settingsPersistence?.flush() == false) {
                        mutableState.update { it.copy(settingsLeaveConfirmation = true) }
                        return@withLock false
                    }
                    val controller = runtime?.controller
                    if (controller != null) {
                        if (controller.saveFailure.value != null) {
                            val saved = controller.retrySave()
                            mutableState.update { it.copy(saveFailure = controller.saveFailure.value) }
                            if (saved !is OperationResult.Ok) return@withLock false
                        }
                        if (runtimeReady && !persistRecovery(controller)) return@withLock false
                    }
                    !stopped
                }
                if (canLeave) withContext(NonCancellable) {
                    if (!stopped) { recovery?.markClosed(); courseRecovery?.markClosed(); closeAndAwait() }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (!stopped) fail("REFLOW_LEAVE_FAILED")
            } finally {
                leaveRequested = false
                if (!stopped) mutableState.update { it.copy(busy = false) }
            }
        }
    }
    fun close() { stopInteractions(null); cleanupScope.launch { closeAndAwait() } }
    private fun stopInteractions(error: String?) {
        stopped = true
        annotationActions?.close()
        settingsObserver?.cancel();defaultsObserver?.cancel();settingsLayoutJob?.cancel();settingsPersistence?.close()
        annotationEditor?.invalidate(error ?: "REFLOW_ANNOTATION_OWNER_UNAVAILABLE")
        bookmarkRefreshJob?.cancel()
        bookmarkInput?.close()
        bookmarkActions?.close()
        annotationSaveJob?.cancel(CancellationException("REFLOW_ANNOTATION_OWNER_STOPPED"))
        mutableState.update { it.copy(loading = false, turnState = null, closed = true, busy = true, error = error ?: it.error) }
        selectionCopyJob?.cancel(CancellationException("REFLOW_SELECTION_OWNER_STOPPED"))
        opening?.cancel(); action?.cancel(); observer?.cancel(); features?.cancel(); selectionCollector?.cancel(); annotationObserver?.cancel()
    }
    suspend fun closeAndAwait(): Boolean = withContext(NonCancellable) {
        stopInteractions(null)
        try {
            operation.withLock {
                releaseRuntime()
                val clean = runtimeFactory.retryCleanup()
                mutableState.update { it.copy(busy = false, cleanupFailure = runtimeFactory.cleanupFailure.value ?: if(clean) null else "REFLOW_RUNTIME_CLEANUP_REQUIRED") }
                clean
            }
        } catch (_: Exception) {
            mutableState.update { it.copy(busy = false, cleanupFailure = "REFLOW_RUNTIME_CLEANUP_REQUIRED") }; false
        }
    }
    private suspend fun releaseRuntime() {
        releaseSelection()
        val current = runtime ?: return
        runtimeFactory.retainCleanup(current.controller)
        runtime = null
    }
    private fun fail(code: String) { mutableState.update { it.copy(loading = false, error = code) } }
    override fun onCleared() { stopInteractions(null); cleanupScope.launch { closeAndAwait() }; super.onCleared() }
    private fun OperationResult<*>.code(): String = when(this) {
        is OperationResult.Rejected -> code
        is OperationResult.Failed -> code
        is OperationResult.Conflict -> "REVISION_CONFLICT"
        is OperationResult.Ok -> error("expected failure")
    }
}
