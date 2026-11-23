package com.example.myapplication.ui.knowledge.reader

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.reader.ReaderSession
import com.example.myapplication.data.reader.readingSequence
import com.example.myapplication.data.reader.importer.ReaderFormat
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.reader.notes.*
import com.example.myapplication.domain.reader.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A preference writer belongs to one writable content snapshot, never merely to a screen. */
internal data class PdfSettingsBinding(
    val identity: SessionIdentity,
    val revision: String,
    val readOnly: Boolean,
) {
    fun matches(currentIdentity: SessionIdentity?, currentRevision: String?, currentReadOnly: Boolean): Boolean =
        !readOnly && !currentReadOnly && identity == currentIdentity && revision == currentRevision

    suspend fun valid(identityNow: suspend () -> SessionIdentity?, revisionNow: suspend () -> String?): Boolean =
        !readOnly && identityNow() == identity && revisionNow() == revision && identityNow() == identity
}

data class PdfReaderRuntimeUiState(
    val loading: Boolean = true,
    val title: String = "",
    val bitmap: Bitmap? = null,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val readOnly: Boolean = false,
    val error: String? = null,
    val saveFailure: ReaderProgressSaveFailure? = null,
    val cleanupFailure: String? = null,
    val closed: Boolean = false,
    val busy: Boolean = false,
    val settings: ReaderSettings = ReaderSettings(),
    val settingsPending: Boolean = false,
    val settingsSaving: Boolean = false,
    val settingsFailure: String? = null,
    val settingsOutcomeUnknown: Boolean = false,
    val settingsRetryable: Boolean = true,
    val settingsInheritsDefaults: Boolean = true,
    val settingsFollowsGlobalTheme: Boolean = true,
    val settingsAbandoned: Boolean = false,
    val settingsLeaveConfirmation: Boolean = false,
    val textPages: List<PdfTextPage> = emptyList(),
    val adjacentBitmap: Bitmap? = null,
    val selection: ReaderSelectionEvent? = null,
    val annotations: List<ReaderAnnotationView> = emptyList(),
    val decorations: List<ReaderDecoration> = emptyList(),
    val selectionError: String? = null,
    val frameToken: ReaderSessionToken? = null,
    val frameEventSequence: Long = -1L,
    val selectionPending: Boolean = false,
    val annotationUnmappableIds: Set<String> = emptySet(),
    val ownedFrame: OwnedPdfFrame? = null,
)

/** Explicit PDF surface. Retired immutable copies remain alive only while a display lease owns them. */
class PdfReaderRuntimeViewModel(
    private val documentId: String,
    private val initialLocatorJson: String? = null,
    identities: Flow<SessionIdentity>,
    private val openSession: suspend (Long, String, String?) -> OperationResult<ReaderSession>,
    private val openRuntime: suspend (AccessContext, ReaderOpenRequest) -> OperationResult<PdfReaderRuntime>,
    private val factoryCleanupFailure: StateFlow<String?>,
    private val retryFactoryCleanup: suspend () -> Boolean,
    private val retainCleanup: suspend (ReaderRuntimeController) -> Boolean,
    private val cleanupScope: CoroutineScope,
    private val recovery: ReaderRuntimeRecovery? = null,
    private val observeDefaults: ((Long) -> Flow<ReaderSettings>)? = null,
    private val saveDefaults: (suspend (Long, ReaderSettings) -> OperationResult<Unit>)? = null,
    private val saveDocumentSettings: (suspend (Long, String, String) -> OperationResult<Unit>)? = null,
    private val currentIdentity: (suspend () -> SessionIdentity?)? = null,
    private val currentCatalogRevision: (suspend (Long, String) -> String?)? = null,
    private val saveBoundDocumentSettings: (suspend (Long, String, String, String, suspend () -> Boolean) -> OperationResult<Unit>)? = null,
    private val annotationServices: ReflowAnnotationServices? = null,
    private val bookmarkServices: ReflowBookmarkServices? = null,
    private val observeDefaultsState: ((Long) -> Flow<com.example.myapplication.data.reader.ReaderPreferenceStore.ReaderDefaultsEntry>)? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PdfReaderRuntimeUiState())
    val state = mutableState.asStateFlow()
    private val operation = Mutex()
    private val frameOwnership = Any()
    @Volatile private var stopped = false
    @Volatile private var runtime: PdfReaderRuntime? = null
    private var opening: Job? = null
    private var action: Job? = null
    private var observer: Job? = null
    private var features: Job? = null
    @Volatile private var ownerIdentity: SessionIdentity? = null
    private var recoveryLocator: ReaderLocator.Fixed? = null
    private var pinnedRevision: String? = null
    private var recoveryRestored = false
    private var lastRecoveryLocator: ReaderLocator.Fixed? = null
    @Volatile private var leaveRequested = false
    private var leaving: Job? = null
    private var preferences: ReaderSettingsPersistence? = null
    private var preferenceObserver: Job? = null
    private var defaultsObserver: Job? = null
    @Volatile private var globalDarkTheme: Boolean = false
    @Volatile private var preferenceBinding: PdfSettingsBinding? = null
    private val identitySource = identities
    private var annotationObserver: Job? = null
    private var selectionObserver: Job? = null
    private var retainedAnnotation: Pair<ReaderSessionToken, ReaderAnnotationView>? = null
    private var featureGeneration = 0L
    private var selectionGeneration = 0L
    private val creation = AnnotationCreationController({ op ->
        val services = annotationServices ?: return@AnnotationCreationController OperationResult.Rejected("ANNOTATION_UNAVAILABLE")
        val guard = op.guard ?: return@AnnotationCreationController OperationResult.Rejected("SOURCE_PROOF_REQUIRED")
        services.create(op.context, op.locator.documentId, op.kind, op.locator, op.quote, op.style, op.noteText, op.targets, guard)
    }, { UUID.randomUUID().toString() })
    val annotationCreationState = creation.state
    private val annotationActions = AnnotationActionController(
        { ctx,id,revision,note,style,guard -> if(guard==null)OperationResult.Rejected("SOURCE_PROOF_REQUIRED") else
            annotationServices?.update?.invoke(ctx,id,revision,note,style ?: AnnotationStyle(),guard) ?: OperationResult.Rejected("ANNOTATION_UNAVAILABLE") },
        { ctx,id,revision,guard -> if(guard==null)OperationResult.Rejected("SOURCE_PROOF_REQUIRED") else
            annotationServices?.removeText?.invoke(ctx,id,revision,guard) ?: OperationResult.Rejected("ANNOTATION_UNAVAILABLE") },
        { ctx,id,revision,guard -> if(guard==null)OperationResult.Rejected("SOURCE_PROOF_REQUIRED") else
            annotationServices?.restoreText?.invoke(ctx,id,revision,guard) ?: OperationResult.Rejected("ANNOTATION_UNAVAILABLE") },
        { UUID.randomUUID().toString() })
    val existingAnnotationState = annotationActions.state
    private var bookmarkPosition: BookmarkPositionInput? = null
    private val bookmarks = bookmarkServices?.let { services -> BookmarkActionController(viewModelScope, BookmarkActionServices(
        services.identityNow, { input -> ownsBookmark(input) }, services.observe,
        { document, revision, fragments, viewport, rows ->
            val active=activePdf()
            val order=active?.takeIf { state.value.frameToken==it.report.token }
                ?.let { (it.adapter as? PdfReaderEngineAdapter)?.readingOrder(it.report.token) }
            BookmarkPositionResolver.resolve(document,revision,fragments,viewport,rows,order).toActionResolution()
        }, services.ensure, services.toggle)) }
    val bookmarkState: StateFlow<BookmarkActionState> = bookmarks?.state ?: MutableStateFlow(BookmarkActionState())
    internal val bookmarkInputDiagnostics: BookmarkPositionInput? get() = bookmarkPosition

    private fun editorOpen() = creation.state.value.draft != null || annotationActions.state.value.isOpen
    private fun activePdf() = runtime?.controller?.state?.value?.active
    private fun owns(token: ReaderSessionToken): Boolean = !stopped && !leaveRequested && !state.value.closed &&
        activePdf()?.report?.token == token && ownerIdentity == SessionIdentity(token.userId,token.sessionEpoch)
    private suspend fun live(token: ReaderSessionToken): Boolean = owns(token) &&
        (annotationServices?.identityNow?.invoke() ?: currentIdentity?.invoke() ?: ownerIdentity) == ownerIdentity && owns(token)

    fun selectText(ranges: List<PdfPageTextRange>, selectionId: String? = null) {
        val token = state.value.frameToken ?: return
        selectText(token, state.value.frameEventSequence, ranges, selectionId)
    }
    internal fun selectText(token: ReaderSessionToken, ranges: List<PdfPageTextRange>, selectionId: String? = null) {
        selectText(token,state.value.frameEventSequence,ranges,selectionId)
    }
    private fun frameCurrent(token:ReaderSessionToken,eventSequence:Long)=owns(token) && state.value.frameToken==token &&
        state.value.frameEventSequence==eventSequence && activePdf()?.report?.eventSequence==eventSequence

    @Synchronized
    internal fun selectText(token:ReaderSessionToken,frameEventSequence:Long,ranges:List<PdfPageTextRange>,selectionId:String?=null) {
        if(editorOpen() || state.value.busy || state.value.loading || !frameCurrent(token,frameEventSequence))return
        val generation = ++selectionGeneration
        val frozen = ranges.toList()
        mutableState.update { it.copy(selectionPending=true) }
        updateBookmark()
        viewModelScope.launch { try { operation.withLock {
            val active=activePdf() ?: return@withLock
            val adapter=active.adapter as? PdfReaderEngineAdapter ?: return@withLock
            if(editorOpen() || state.value.busy || generation!=selectionGeneration || !frameCurrent(token,frameEventSequence) || !live(token))return@withLock
            val result=adapter.selectText(token,frozen,selectionId ?: state.value.selection?.selectionId,ReaderSelectionPhase.STABLE)
            if(generation!=selectionGeneration || !frameCurrent(token,frameEventSequence) || !live(token))return@withLock
            // A refused drag must retain the last proven selection for retry.
            mutableState.update { it.copy(selection=(result as? OperationResult.Ok)?.value ?: it.selection,
                selectionError=if(result is OperationResult.Ok)null else result.code()) }
            updateBookmark()
        } }catch(cancelled:CancellationException){throw cancelled}
          catch(_:Exception){if(generation==selectionGeneration && frameCurrent(token,frameEventSequence))
              mutableState.update { it.copy(selectionError="PDF_SELECTION_FAILED") }}
          finally {if(generation==selectionGeneration){mutableState.update { it.copy(selectionPending=false) };updateBookmark()}}
        }
    }
    fun clearTextSelection() {
        if(editorOpen() || state.value.selectionPending)return
        val token = state.value.frameToken ?: return
        val selected = state.value.selection ?: return
        val generation = ++selectionGeneration
        viewModelScope.launch { operation.withLock {
            val active=activePdf() ?: return@withLock
            if(editorOpen() || generation != selectionGeneration || state.value.selection != selected || !live(token))return@withLock
            val result=active.adapter.clearSelection(token)
            if(generation == selectionGeneration && state.value.selection == selected && live(token))
                mutableState.update { if(result is OperationResult.Ok)it.copy(selection=null,selectionError=null)
                    else it.copy(selectionError=result.code()) }
            updateBookmark()
        } }
    }
    fun beginTextAnnotation(kind: AnnotationKind) {
        if(editorOpen() || state.value.readOnly || state.value.busy || state.value.selectionPending || kind !in setOf(AnnotationKind.NOTE,AnnotationKind.HIGHLIGHT))return
        val event = state.value.selection ?: return
        viewModelScope.launch { operation.withLock {
            if(editorOpen() || state.value.readOnly || state.value.busy || state.value.selectionPending || state.value.selection != event)return@withLock
            val active=activePdf() ?: return@withLock
            val adapter=active.adapter as? PdfReaderEngineAdapter ?: return@withLock
            if(!live(event.token) || !adapter.proveSelection(event) || !live(event.token))return@withLock
            if(editorOpen() || state.value.readOnly || state.value.busy || state.value.selectionPending || state.value.selection != event)return@withLock
            val targets=event.fragments.map { AnnotationTargetInput(it.locator,"EXACT") }
            val guard=annotationGuard(event.token,targets,event) ?: return@withLock
            if(editorOpen() || state.value.readOnly || state.value.busy || state.value.selectionPending ||
                state.value.selection!=event || activePdf()!=active || !live(event.token))return@withLock
            val retained = retainedAnnotation?.takeIf { it.first == event.token }?.second
            val existing=(state.value.annotations+listOfNotNull(retained)).filter { it.targets==targets }
                .groupBy { it.id }.values.mapNotNull { it.maxByOrNull { row -> row.revision } }
            if(existing.size>1){mutableState.update { it.copy(selectionError="ANNOTATION_SELECTION_AMBIGUOUS") };return@withLock}
            val context=AccessContext(event.token.userId,event.token.sessionEpoch,UUID.randomUUID().toString())
            if(existing.size==1)annotationActions.open(context,existing.single(),guard)
            else creation.open(context,targets.first().locator,event.fragments.joinToString("\n") { it.quote },kind,
                targets=targets,guard=guard)
            updateBookmark()
        } }
    }
    private suspend fun annotationGuard(token: ReaderSessionToken, targets: List<AnnotationTargetInput>, selection: ReaderSelectionEvent? = null): AnnotationWriteGuard? {
        val adapter=activePdf()?.adapter as? PdfReaderEngineAdapter ?: return null
        val sequence=state.value.frameEventSequence
        val frozenTargets=AnnotationTargetValidation.freezeTargets(targets)
        if(!live(token) || state.value.frameToken!=token)return null
        // Geometry and native ownership are proved before any repository write transaction.
        val prepared=adapter.prepareAnnotationSourceProof(token,sequence,frozenTargets.map { it.locator },selection)
        val proof=(prepared as? OperationResult.Ok)?.value ?: return null
        val snapshot=proof.originalSnapshot
        if(proof.token!=token || proof.frameEventSequence!=sequence || !live(token) ||
            activePdf()?.adapter!==adapter || state.value.frameToken!=token || state.value.frameEventSequence!=sequence ||
            adapter.originalSnapshot(token)!=snapshot)return null
        return AnnotationWriteGuard(snapshot.documentId,snapshot.contentRevision,snapshot.accessEpoch) { ctx,_,actual ->
            // Room invokes this inside its write transaction. This validation acquires neither
            // the adapter Mutex nor Room, so observers cannot reverse the transaction lock order.
            if(state.value.readOnly || actual!=frozenTargets || ctx.userId!=token.userId || ctx.sessionEpoch!=token.sessionEpoch ||
                !live(token) || activePdf()?.adapter!==adapter || state.value.frameToken!=token ||
                state.value.frameEventSequence!=sequence || adapter.originalSnapshot(token)!=snapshot ||
                !adapter.validateAnnotationSourceProof(proof) || !live(token) || state.value.readOnly ||
                activePdf()?.adapter!==adapter || state.value.frameToken!=token || state.value.frameEventSequence!=sequence)
                OperationResult.Rejected("SOURCE_PROOF_STALE")
            else OperationResult.Ok(Unit,ctx.requestId)
        }
    }
    fun editNewAnnotationNote(note:String?) = creation.editNote(note)
    fun chooseNewAnnotationColor(color:AnnotationColor) = creation.chooseColor(color)
    fun saveNewAnnotation(){viewModelScope.launch { creation.save() }}
    fun retryNewAnnotation(){viewModelScope.launch { creation.retry() }}
    fun closeNewAnnotation(){
        val committed=creation.state.value.committed
        val token=state.value.frameToken
        if(creation.discard()){
            if(committed!=null && token!=null && owns(token))retainedAnnotation=token to committed
            updateBookmark()
        }
    }
    fun openExistingAnnotation(id:String){
        if(editorOpen() || state.value.readOnly || state.value.busy || state.value.selectionPending)return
        val active=activePdf() ?: return
        val row=state.value.annotations.singleOrNull { it.id==id } ?: return
        val targets=row.targets.ifEmpty { listOfNotNull(row.locator).map { AnnotationTargetInput(it,"UNRESOLVED") } }
        viewModelScope.launch { operation.withLock {
            if(editorOpen() || state.value.readOnly || state.value.busy || state.value.selectionPending || activePdf()!=active ||
                state.value.annotations.singleOrNull { it.id==id }!=row)return@withLock
            val guard=annotationGuard(active.report.token,targets) ?: return@withLock
            if(editorOpen() || state.value.readOnly || state.value.busy || state.value.selectionPending || activePdf()!=active ||
                state.value.annotations.singleOrNull { it.id==id }!=row || !owns(active.report.token))return@withLock
            annotationActions.open(AccessContext(active.report.token.userId,active.report.token.sessionEpoch,UUID.randomUUID().toString()),row,guard)
            updateBookmark()
        } }
    }
    fun editExistingAnnotationNote(note:String?) = annotationActions.editNote(note)
    fun chooseExistingAnnotationColor(color:AnnotationColor) = annotationActions.chooseColor(color)
    fun saveExistingAnnotation(){viewModelScope.launch { annotationActions.save() }}
    fun deleteExistingAnnotation(){viewModelScope.launch { annotationActions.delete() }}
    fun undoExistingAnnotation(){viewModelScope.launch { annotationActions.undo() }}
    fun retryExistingAnnotation(){viewModelScope.launch { annotationActions.retry() }}
    fun closeExistingAnnotation(){
        val current=annotationActions.state.value
        val token=state.value.frameToken
        if(annotationActions.discard()){
            if(current.deleted)retainedAnnotation=null
            else current.original?.let { if(token!=null && owns(token))retainedAnnotation=token to it }
            updateBookmark()
        }
    }
    fun reloadExistingAnnotation(){viewModelScope.launch {
        val before=annotationActions.state.value
        val original=before.original ?: return@launch
        val token=activePdf()?.report?.token ?: return@launch
        if(!live(token))return@launch
        val latest=annotationServices?.observe?.invoke(token.userId,token.documentId)?.first()?.singleOrNull { it.id==original.id } ?: return@launch
        if(live(token) && annotationActions.state.value===before)annotationActions.rebase(latest)
    }}
    private suspend fun ownsBookmark(input:BookmarkPositionInput):Boolean {
        if(bookmarkPosition!=input || editorOpen() || state.value.loading || state.value.busy || state.value.selectionPending || !live(input.token))return false
        val active=activePdf() ?: return false
        val adapter=active.adapter as? PdfReaderEngineAdapter ?: return false
        val selection=state.value.selection
        return if(input.selection!=null)selection!=null && selection.fragments==input.selection && adapter.proveSelection(selection) && live(input.token)
            else selection==null && active.report.currentLocator==input.viewport && live(input.token)
    }
    private fun updateBookmark(){
        val active=activePdf()
        val identity=ownerIdentity
        val selected=state.value.selection
        bookmarkPosition=if(active==null || identity==null || !owns(active.report.token) || state.value.loading || state.value.busy ||
            state.value.frameToken!=active.report.token || state.value.selectionPending || editorOpen())null else BookmarkPositionInput(
            identity,active.report.token,"${active.report.token.sessionId}:${active.report.token.layoutGeneration}:${state.value.pageIndex}:$featureGeneration:$selectionGeneration:${selected?.selectionId ?: "page"}",
            !state.value.readOnly,selected?.fragments,active.report.currentLocator)
        bookmarks?.updatePosition(bookmarkPosition)
    }
    fun toggleBookmark(){viewModelScope.launch { bookmarks?.toggleBookmark() }}
    fun ensureBookmark(){viewModelScope.launch { bookmarks?.ensureBookmark() }}
    fun retryBookmark(){viewModelScope.launch { bookmarks?.retryWrite() }}
    fun retryBookmarkObservation(){bookmarks?.retryObservation()}
    fun selectBookmark(id:String){bookmarks?.selectBookmark(id)}

    fun navigateToAnnotation(id:String) {
        val services=annotationServices ?: return
        val active=activePdf() ?: return
        val adapter=active.adapter as? PdfReaderEngineAdapter ?: return
        val token=active.report.token
        val row=state.value.annotations.singleOrNull { it.id==id } ?: return
        val snapshot=adapter.originalSnapshot(token) ?: return
        val targets=row.targets.map { it.locator }.ifEmpty { listOfNotNull(row.locator) }
        val locator=targets.firstOrNull() as? ReaderLocator.Fixed ?: return
        fun ownerCurrent()=owns(token) && state.value.frameToken==token && !editorOpen() && !state.value.selectionPending && state.value.selection==null &&
            !state.value.loading && activePdf()?.adapter===adapter && adapter.originalSnapshot(token)==snapshot &&
            state.value.annotations.singleOrNull { it.id==id }==row
        if(state.value.busy || !ownerCurrent())return
        val previousOpen=opening
        val previousAction=action
        mutableState.update { it.copy(busy=true,error=null) }
        updateBookmark()
        action=viewModelScope.launch(Dispatchers.IO) {
            try {
                previousOpen?.join();previousAction?.join()
                operation.withLock {
                    val controller=runtime?.controller ?: return@withLock
                    if(!ownerCurrent() || !live(token))return@withLock
                    val latest=services.observe(token.userId,token.documentId).first().singleOrNull { it.id==id }
                    if(latest!=row || !ownerCurrent() || !live(token) ||
                        !adapter.validateAnnotationTargets(token,targets) || !ownerCurrent() || !live(token)) {
                        fail("STALE_ANNOTATION_NAVIGATION");return@withLock
                    }
                    when(val result=controller.navigate(locator)) {
                        is ReaderNavigationResult.Exact -> {
                            rememberCommitted(controller);refreshFrame(controller);persistRecovery(controller)
                        }
                        is ReaderNavigationResult.Approximate -> fail("ANNOTATION_NAVIGATION_APPROXIMATE")
                        is ReaderNavigationResult.Unavailable -> fail(result.code)
                    }
                }
            }catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(!stopped)fail("PDF_ANNOTATION_NAVIGATION_FAILED")}
            finally {if(!stopped){mutableState.update { it.copy(busy=leaveRequested) };updateBookmark()}}
        }
    }

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
            factoryCleanupFailure.collect { failure ->
                mutableState.update { it.copy(cleanupFailure = failure ?: runtime?.controller?.cleanupFailure?.value?.code) }
            }
        }
    }

    private suspend fun load(identity: SessionIdentity, reopen: Boolean = false) {
        try {
            operation.withLock {
                if (stopped) return
                if (!recoveryRestored) {
                    when (val restored = recovery?.restore(identity)) {
                        null -> Unit
                        is OperationResult.Ok -> {
                            val locator = restored.value?.locator
                            if (locator != null && (locator !is ReaderLocator.Fixed || locator.documentId != documentId ||
                                    !ReaderLocatorValidator.validate(locator).isValid)) return fail("INVALID_RECOVERY_LOCATOR")
                            recoveryLocator = locator as? ReaderLocator.Fixed
                            pinnedRevision = locator?.contentRevision
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
                if (reopen) {
                    observer?.cancelAndJoin(); observer = null
                    features?.cancelAndJoin(); features = null
                    runtime?.controller?.let { rememberCommitted(it) }
                    if (!releaseRuntime() || !retryFactoryCleanup()) {
                        mutableState.update { it.copy(cleanupFailure = factoryCleanupFailure.value ?: "PDF_RUNTIME_CLEANUP_REQUIRED") }
                        return fail("PDF_RUNTIME_CLEANUP_REQUIRED")
                    }
                    if (stopped) return
                    mutableState.update { it.copy(cleanupFailure = null, saveFailure = null) }
                }
                val explicit = recoveryLocator ?: initialLocatorJson?.let(ReaderLocatorCodec::decodeOrNull)
                if (initialLocatorJson != null && explicit == null) return fail("INVALID_INITIAL_LOCATOR")
                if (explicit != null && (explicit !is ReaderLocator.Fixed || explicit.documentId != documentId)) return fail("INVALID_INITIAL_LOCATOR")
                val session = when (val result = openSession(checkNotNull(identity.userId), documentId,
                    explicit?.contentRevision ?: pinnedRevision)) {
                    is OperationResult.Ok -> result.value
                    else -> return fail(result.code())
                }
                if (session.document.kind != "IMPORTED" || session.format != ReaderFormat.PDF) return fail("PDF_IMPORTED_ONLY")
                val target = session.readingSequence().orderedTargets.single()
                val saved = explicit ?: session.state?.locatorJson?.let(ReaderLocatorCodec::decodeOrNull)
                val locator = saved?.takeIf { it is ReaderLocator.Fixed &&
                    ReaderLocatorValidator.validateForDocument(it, target.documentId, target.contentRevision).isValid }
                if (explicit != null && locator == null) return fail("INVALID_INITIAL_LOCATOR")
                val request = ReaderOpenRequest(target, if (locator == null) ReaderPlacement.START else ReaderPlacement.AT_LOCATOR, locator)
                val context = AccessContext(checkNotNull(identity.userId), identity.epoch, UUID.randomUUID().toString())
                val result = openRuntime(context, request)
                if (result !is OperationResult.Ok) return fail(result.code())
                runtime = result.value
                pinnedRevision = target.contentRevision
                rememberCommitted(result.value.controller)
                currentCoroutineContext().ensureActive()
                if (stopped) return
                mutableState.update { it.copy(loading = false, title = session.document.title,
                    pageCount = session.sections.size, readOnly = result.value.readOnly, error = null) }
                bindPreferences(identity, session, result.value.readOnly)
                refreshFrame(result.value.controller)
                persistRecovery(result.value.controller)
                if (!stopped) observe(result.value.controller)
            }
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) { fail("PDF_RUNTIME_LOAD_FAILED")
        } finally {
            if (!stopped) { mutableState.update { it.copy(loading = false, busy = leaveRequested) };updateBookmark() }
            if (stopped || !currentCoroutineContext().isActive) withContext(NonCancellable) {
                try { operation.withLock { releaseRuntime() } }
                catch (_: Exception) { mutableState.update { it.copy(cleanupFailure = "PDF_RUNTIME_CLEANUP_REQUIRED") } }
            }
        }
    }

    private fun observe(controller: ReaderRuntimeController) {
        features = viewModelScope.launch {
            try { controller.collectFeatureEvents() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (!stopped && runtime?.controller === controller) fail("PDF_EVENTS_FAILED") }
        }
        observer = viewModelScope.launch {
            combine(controller.state, controller.saveFailure, controller.cleanupFailure) { current, save, cleanup -> Triple(current, save, cleanup) }
                .collect { operation.withLock {
                    if (stopped || runtime?.controller !== controller) return@withLock
                    // combine may have queued a snapshot before a turn acquired this mutex.
                    // Rendering and busy state must describe the committed owner now, not that snapshot.
                    val current = controller.state.value
                    mutableState.update { it.copy(saveFailure = controller.saveFailure.value,
                        cleanupFailure = controller.cleanupFailure.value?.code ?: factoryCleanupFailure.value,
                        busy = leaveRequested || current.phase != ReaderTurnPhase.IDLE) }
                    rememberCommitted(controller)
                    val locator = current.active.report.currentLocator as? ReaderLocator.Fixed
                    if (locator != null && (state.value.bitmap == null || state.value.pageIndex != locator.pageIndex ||
                            state.value.frameToken != current.active.report.token || state.value.frameEventSequence != current.active.report.eventSequence)) {
                        try { refreshFrame(controller) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { fail("PDF_FRAME_FAILED") }
                    }
                    persistRecovery(controller)
                    updateBookmark()
                } }
        }
    }

    private fun rememberCommitted(controller: ReaderRuntimeController) {
        val locator = controller.state.value.active.report.currentLocator as? ReaderLocator.Fixed ?: return
        if (ReaderLocatorValidator.validateForDocument(locator, documentId,
                controller.state.value.active.target.contentRevision).isValid) recoveryLocator = locator
    }

    private suspend fun persistRecovery(controller: ReaderRuntimeController): Boolean {
        rememberCommitted(controller)
        val sink = recovery ?: return true
        val identity = ownerIdentity ?: return false
        val locator = recoveryLocator ?: return false
        if (stopped || runtime?.controller !== controller) return false
        if (lastRecoveryLocator == locator) return true
        val saved = sink.save(identity, locator)
        if (saved is OperationResult.Ok) {
            lastRecoveryLocator = locator
            return true
        }
        fail(saved.code())
        return false
    }

    /** Reopens only under the original identity, using the last committed revision and page. */
    @Synchronized
    fun retryOpen() {
        val identity = ownerIdentity ?: return
        if (stopped || leaveRequested || state.value.loading || state.value.busy || state.value.selectionPending || editorOpen()) return
        val previousOpen = opening
        val previousAction = action
        mutableState.update { it.copy(loading = true, busy = true, error = null) }
        opening = viewModelScope.launch(Dispatchers.IO) {
            // UI idle can be published just before a job finishes. Preserve an accepted retry
            // and wait outside the operation mutex, so the previous owner can finish releasing it.
            previousOpen?.join()
            previousAction?.join()
            load(identity, reopen = true)
        }
    }

    private suspend fun refreshFrame(controller: ReaderRuntimeController) {
        // Turn/navigation may have committed their locator before the feature collector gets
        // the adapter's newer eventSequence. Bind this frame to that real report now.
        val prior=controller.state.value.active
        prior.adapter.featureEvents(prior.report.token).firstOrNull()?.let { controller.acceptFeatureReport(it) }
        val active = controller.state.value.active
        val token = active.report.token
        val adapter = active.adapter as? PdfReaderEngineAdapter ?: return fail("PDF_ADAPTER_UNAVAILABLE")
        val copied = adapter.copyPageBitmap(token)
        if (copied !is OperationResult.Ok) return fail(copied.code())
        val bitmap = copied.value
        var adjacentBitmap: Bitmap? = null
        var published = false
        try {
            currentCoroutineContext().ensureActive()
            if (!live(token) || runtime?.controller !== controller) return
            val pages=adapter.copyTextPages(token)
            if(!live(token))return
            val adjacent=adapter.copyAdjacentPageBitmap(token)
            adjacentBitmap=(adjacent as? OperationResult.Ok)?.value
            if(!live(token))return
            val locator = controller.state.value.active.report.currentLocator as? ReaderLocator.Fixed ?: return
            // Pixels and selectable glyphs are published together under the same native token.
            // Never expose new-page pixels with the previous page's geometry or selection.
            selectionGeneration++
            retainedAnnotation=null
            synchronized(frameOwnership) {
                // close may originate outside Main; it cannot race a final frame transfer.
                if (stopped || leaveRequested || mutableState.value.closed || runtime?.controller !== controller) return
                val frame = OwnedPdfFrame(bitmap, adjacentBitmap)
                val previous = mutableState.value.ownedFrame
                mutableState.update { it.copy(bitmap = bitmap, ownedFrame = frame, pageIndex = locator.pageIndex, frameToken=token,
                    frameEventSequence=active.report.eventSequence,selectionPending=false,
                    selection=null,selectionError=null,annotations=emptyList(),decorations=emptyList(),annotationUnmappableIds=emptySet(),
                    textPages=(pages as? OperationResult.Ok)?.value.orEmpty(),adjacentBitmap=adjacentBitmap,
                    saveFailure = controller.saveFailure.value,
                    cleanupFailure = controller.cleanupFailure.value?.code ?: factoryCleanupFailure.value) }
                published = true
                previous?.retire()
            }
            observeSelection(controller,token,adapter)
            observeAnnotations(controller,token,adapter)
            updateBookmark()
        } finally { if (!published) {bitmap.recycle();adjacentBitmap?.recycle()} }
    }

    private fun observeSelection(controller:ReaderRuntimeController,token:ReaderSessionToken,adapter:PdfReaderEngineAdapter) {
        selectionObserver?.cancel()
        selectionObserver=viewModelScope.launch {
            try {
                adapter.selectionEvents(token).collect { event -> operation.withLock {
                    if(runtime?.controller!==controller || state.value.frameToken!=token || !live(token))return@withLock
                    if(event.phase==ReaderSelectionPhase.CLEARED) {
                        if(state.value.selection?.selectionId==event.selectionId && !editorOpen())
                            mutableState.update { it.copy(selection=null,selectionError=null) }
                    } else if(adapter.proveSelection(event) && live(token) && !editorOpen()) {
                        mutableState.update { it.copy(selection=event,selectionError=null) }
                    }
                    updateBookmark()
                } }
            } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){
                if(owns(token) && state.value.frameToken==token)
                    mutableState.update { it.copy(selectionError="PDF_SELECTION_SOURCE_UNAVAILABLE") }
            }
            finally {
                if(currentCoroutineContext().isActive && owns(token) && state.value.frameToken==token) {
                    mutableState.update { it.copy(selection=if(editorOpen())it.selection else null,
                        textPages=emptyList(),selectionError="PDF_SELECTION_SOURCE_UNAVAILABLE") }
                    updateBookmark()
                }
            }
        }
    }

    private fun observeAnnotations(controller:ReaderRuntimeController,token:ReaderSessionToken,adapter:PdfReaderEngineAdapter){
        annotationObserver?.cancel()
        val generation=++featureGeneration
        val services=annotationServices ?: return
        annotationObserver=viewModelScope.launch {
          try {
            services.observe(token.userId,token.documentId).collectLatest { rows ->
                if(!live(token) || generation!=featureGeneration || runtime?.controller!==controller)return@collectLatest
                val visiblePages=state.value.textPages.map { it.pageIndex }.toSet()
                val current=rows.filter { row -> row.kind!=AnnotationKind.INK && row.documentId==token.documentId &&
                    row.targets.map { it.locator }.ifEmpty { listOfNotNull(row.locator) }.let { targets ->
                        targets.isNotEmpty() && targets.all { target -> target is ReaderLocator.Fixed && target.documentId==token.documentId &&
                            target.contentRevision==token.contentRevision } } }
                val decorations=mutableListOf<ReaderDecoration>()
                val unmappable=linkedSetOf<String>()
                for(row in current){
                    val targets=row.targets.map { it.locator }.ifEmpty { listOfNotNull(row.locator) }
                    if(targets.none { (it as? ReaderLocator.Fixed)?.pageIndex in visiblePages })continue
                    if(adapter.validateAnnotationTargets(token,targets) && live(token))
                        decorations+=ReaderDecoration(row.id,row.revision,targets,row.style.color.hex,row.style.underline)
                    else unmappable+=row.id
                }
                if(!live(token) || generation!=featureGeneration)return@collectLatest
                val decorated=adapter.decorate(token,decorations)
                if(!live(token) || generation!=featureGeneration)return@collectLatest
                mutableState.update { it.copy(annotations=current,annotationUnmappableIds=unmappable,
                    decorations=if(decorated is OperationResult.Ok)decorations else emptyList(),
                    selectionError=if(decorated is OperationResult.Ok)it.selectionError else "PDF_ANNOTATION_DECORATION_FAILED") }
            }
          }catch(cancelled:CancellationException){throw cancelled}
          catch(_:Exception){if(owns(token) && generation==featureGeneration)
              mutableState.update { it.copy(selectionError="PDF_ANNOTATION_OBSERVATION_FAILED") }}
        }
    }

    fun previous() = turn(ReaderTurnDirection.PREVIOUS, ReaderTurnOrigin.BOTTOM_BUTTON)
    fun next() = turn(ReaderTurnDirection.NEXT, ReaderTurnOrigin.BOTTOM_BUTTON)
    @Synchronized
    fun turn(direction: ReaderTurnDirection, origin: ReaderTurnOrigin) {
        if (stopped || leaveRequested || state.value.loading || state.value.busy || state.value.selectionPending || editorOpen() || state.value.selection!=null) return
        if (origin == ReaderTurnOrigin.EDGE_TAP && !state.value.settings.edgeTap) return
        val previousOpen = opening
        val previousAction = action
        mutableState.update { it.copy(busy = true, error = null) }
        action = viewModelScope.launch(Dispatchers.IO) {
            try {
                previousOpen?.join()
                previousAction?.join()
                operation.withLock {
                    val controller = runtime?.controller ?: return@withLock
                    if (stopped || leaveRequested || state.value.loading || state.value.selectionPending || editorOpen() || state.value.selection!=null) return@withLock
                    when (val result = controller.turn(direction, origin)) {
                        is TurnDecision.Failed -> fail(result.code)
                        is TurnDecision.Moved -> { rememberCommitted(controller); refreshFrame(controller); persistRecovery(controller) }
                        else -> Unit
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { fail("PDF_OPERATION_FAILED")
            } finally { if (!stopped) {mutableState.update { it.copy(busy = leaveRequested) };updateBookmark()} }
        }
    }
    fun retrySave() {
        if (stopped || leaveRequested) return
        viewModelScope.launch { operation.withLock { if (!stopped) runtime?.controller?.retrySave() } }
    }
    fun retryCleanup() { cleanupScope.launch {
        if (stopped) closeAndAwait()
        else {
            operation.withLock { runtime?.controller?.retryCleanup(); retryFactoryCleanup() }
            mutableState.update { it.copy(cleanupFailure = runtime?.controller?.cleanupFailure?.value?.code ?: factoryCleanupFailure.value) }
        }
    } }
    fun close() { stopInteractions(null); cleanupScope.launch { closeAndAwait() } }
    /** A committed position is already saved; only a failed write is retried before leaving. */
    @Synchronized
    fun requestLeave() = beginLeave(abandonSettings = false)

    fun dismissSettingsLeaveConfirmation() {
        mutableState.update { it.copy(settingsLeaveConfirmation = false) }
    }

    @Synchronized
    fun confirmAbandonSettingsAndLeave() {
        if (!state.value.settingsLeaveConfirmation || stopped || leaveRequested) return
        mutableState.update { it.copy(settingsLeaveConfirmation = false) }
        beginLeave(abandonSettings = true)
    }

    private fun beginLeave(abandonSettings: Boolean) {
        if (stopped || leaveRequested || state.value.selectionPending || editorOpen() || state.value.selection!=null) return
        leaveRequested = true
        val pendingOpen = opening
        val pendingAction = action
        mutableState.update { it.copy(busy = true) }
        leaving = viewModelScope.launch(Dispatchers.IO) {
            try {
                pendingOpen?.join()
                pendingAction?.join()
                val canLeave = operation.withLock {
                    if (stopped) return@withLock false
                    if (abandonSettings) {
                        preferences?.abandonPending()
                        preferences?.state?.value?.let(::publishPreferences)
                    } else if (preferences?.flush() == false) {
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
                        if (!persistRecovery(controller)) return@withLock false
                    }
                    !stopped
                }
                if (canLeave) withContext(NonCancellable) {
                    if (!stopped) {
                        recovery?.markClosed()
                        closeAndAwait()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (!stopped) fail("PDF_LEAVE_FAILED")
            } finally {
                leaveRequested = false
                if (!stopped) mutableState.update { it.copy(busy = false) }
            }
        }
    }
    private fun stopInteractions(error: String?) {
        stopped = true
        featureGeneration++;selectionGeneration++
        annotationObserver?.cancel();selectionObserver?.cancel();annotationActions.close();bookmarks?.close();bookmarkPosition=null
        creation.close();retainedAnnotation=null
        preferences?.close()
        preferenceObserver?.cancel(); defaultsObserver?.cancel()
        synchronized(frameOwnership) {
            val previous = mutableState.value.ownedFrame
            mutableState.update { it.copy(loading = false, bitmap = null, ownedFrame = null, adjacentBitmap=null,textPages=emptyList(),selection=null,
                decorations=emptyList(),annotations=emptyList(),frameToken=null,frameEventSequence=-1,selectionPending=false,annotationUnmappableIds=emptySet(),
                busy = true, closed = true, error = error ?: it.error) }
            previous?.retire()
        }
        opening?.cancel(); action?.cancel(); observer?.cancel(); features?.cancel()
    }
    suspend fun closeAndAwait(): Boolean = withContext(NonCancellable) {
        stopInteractions(null)
        try {
            operation.withLock {
                releaseRuntime()
                val factoryClosed = retryFactoryCleanup()
                mutableState.update { it.copy(busy = false, cleanupFailure = factoryCleanupFailure.value ?: if (factoryClosed) null else "PDF_RUNTIME_CLEANUP_REQUIRED") }
                factoryClosed
            }
        } catch (_: Exception) {
            mutableState.update { it.copy(busy = false, cleanupFailure = "PDF_RUNTIME_CLEANUP_REQUIRED") }
            false
        }
    }
    private suspend fun releaseRuntime(): Boolean {
        annotationObserver?.cancel();annotationObserver=null
        selectionObserver?.cancel();selectionObserver=null
        featureGeneration++;selectionGeneration++
        val current = runtime ?: return true
        val closed = retainCleanup(current.controller)
        // The long-lived factory now owns any failed cleanup; no lost resource owner.
        runtime = null
        return closed
    }
    private fun fail(code: String) { mutableState.update { it.copy(loading = false, error = code) } }
    private suspend fun bindPreferences(identity: SessionIdentity, session: ReaderSession, readOnly: Boolean) {
        val binding = PdfSettingsBinding(identity, session.revision.revision, readOnly)
        val previous = preferences
        if (previous != null && preferenceBinding == binding) return
        if (previous != null) {
            // Keep unresolved intent and unknown receipts observable. An obsolete binding is
            // revoked by preferenceWriterValid even while its pending state remains visible.
            if (!previous.flush()) {
                defaultsObserver?.cancel(); defaultsObserver = null
                publishPreferences(previous.state.value)
                return
            }
            previous.close()
            preferenceObserver?.cancel(); defaultsObserver?.cancel()
            preferences = null
            preferenceBinding = null
        }
        val stateSource = observeDefaultsState ?: observeDefaults?.let { legacy ->
            { uid: Long ->
                legacy(uid).map {
                    com.example.myapplication.data.reader.ReaderPreferenceStore.ReaderDefaultsEntry(
                        settings = it,
                        hasExplicitDefaults = true,
                    )
                }
            }
        } ?: return
        val defaultsWriter = saveDefaults ?: return
        val documentWriter = saveDocumentSettings ?: return
        val userId = checkNotNull(identity.userId)
        val initial = try { stateSource(userId).first() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.update { it.copy(settingsFailure = "READER_DEFAULTS_READ_FAILED") }
            return
        }
        if (stopped || ownerIdentity != identity) return
        preferenceBinding = binding
        val persistence = ReaderSettingsPersistence(viewModelScope, initial.settings, session.state?.settingsJson, readOnly,
            { preferenceWriterValid(binding) },
            { json -> if (preferenceWriterValid(binding)) {
                saveBoundDocumentSettings?.invoke(userId, documentId, json, binding.revision,
                    { preferenceRuntimeMatches(binding) && binding.identity ==
                        (if (currentIdentity != null) currentIdentity.invoke() else identitySource.first()) && preferenceRuntimeMatches(binding) })
                    ?: documentWriter(userId, documentId, json)
            } else OperationResult.Rejected("READER_SETTINGS_IDENTITY_CHANGED") },
            { value -> if (preferenceWriterValid(binding)) defaultsWriter(userId, value) else OperationResult.Rejected("READER_SETTINGS_IDENTITY_CHANGED") },
            defaultsExplicitTheme = initial.hasExplicitDefaults,
            initialGlobalDark = globalDarkTheme)
        preferences = persistence
        publishPreferences(persistence.state.value)
        preferenceObserver = viewModelScope.launch {
            persistence.state.collect { value -> if (!stopped && preferences === persistence) publishPreferences(value) }
        }
        defaultsObserver = viewModelScope.launch {
            try { stateSource(userId).collect { entry ->
                if (!stopped && ownerIdentity == identity && preferences === persistence) {
                    persistence.updateDefaults(entry.settings, entry.hasExplicitDefaults)
                }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (!stopped) mutableState.update { it.copy(settingsFailure = "READER_DEFAULTS_READ_FAILED") } }
        }
    }
    private fun publishPreferences(value: ReaderSettingsPersistenceState) {
        mutableState.update { it.copy(settings = value.settings, settingsPending = value.pending,
            settingsSaving = value.saving, settingsFailure = value.failure,
            settingsOutcomeUnknown = value.outcomeUnknown, settingsRetryable = value.retryable,
            settingsInheritsDefaults = value.inheritsDefaults, settingsAbandoned = value.abandoned,
            settingsFollowsGlobalTheme = value.followsGlobalTheme) }
    }
    private fun preferenceRuntimeMatches(binding: PdfSettingsBinding): Boolean {
        val active = runtime ?: return false
        return !stopped && preferenceBinding == binding && binding.matches(ownerIdentity,
            active.controller.state.value.active.target.contentRevision, active.readOnly)
    }
    private suspend fun preferenceWriterValid(binding: PdfSettingsBinding): Boolean {
        if (!preferenceRuntimeMatches(binding)) return false
        val userId = binding.identity.userId ?: return false
        val valid = binding.valid(
            { if (currentIdentity != null) currentIdentity.invoke() else identitySource.first() },
            { if (currentCatalogRevision != null) currentCatalogRevision.invoke(userId, documentId)
                else (openSession(userId, documentId, null) as? OperationResult.Ok<ReaderSession>)
                    ?.value?.document?.contentRevision })
        return valid && preferenceRuntimeMatches(binding)
    }
    private fun preferencesEditable(): Boolean = !leaveRequested && !state.value.loading && !state.value.selectionPending && !editorOpen() && state.value.selection==null &&
        !state.value.readOnly && preferenceBinding?.let(::preferenceRuntimeMatches) == true
    fun setGlobalDarkTheme(dark: Boolean) {
        globalDarkTheme = dark
        preferences?.setGlobalDarkTheme(dark)
    }

    fun updateSettings(settings: ReaderSettings) { if (preferencesEditable()) preferences?.edit(settings) }
    fun updateReadingOptions(settings: ReaderSettings) { if (preferencesEditable()) preferences?.editOptions(settings) }
    fun selectTheme(theme: ReaderTheme) {
        if (preferencesEditable()) preferences?.let {
            it.edit(it.state.value.settings.copy(theme = theme), forceThemeOverride = true)
        }
    }
    fun useDefaultSettings() { if (preferencesEditable()) preferences?.useDefaults() }
    fun makeDefaultSettings() { if (preferencesEditable()) preferences?.makeDefaults() }
    fun retrySettingsSave() {
        if (!stopped && !leaveRequested) {
            val current = preferences
            if (current == null) retryOpen() else current.retry()
        }
    }
    override fun onCleared() {
        stopInteractions(null)
        cleanupScope.launch { closeAndAwait() }
        super.onCleared()
    }
    private fun OperationResult<*>.code(): String = when (this) {
        is OperationResult.Rejected -> code
        is OperationResult.Failed -> code
        is OperationResult.Conflict -> "REVISION_CONFLICT"
        is OperationResult.Ok -> error("expected failure")
    }
}

class PdfReaderRuntimeViewModelFactory(
    private val application: LearningApplication,
    private val documentId: String,
    private val initialLocatorJson: String? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return createModel(modelClass, null)
    }
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
        createModel(modelClass, createReaderRuntimeRecovery(application, extras.createSavedStateHandle(), documentId, "PDF"))

    @Suppress("UNCHECKED_CAST")
    private fun <T : ViewModel> createModel(modelClass: Class<T>, recovery: ReaderRuntimeRecovery?): T {
        require(modelClass.isAssignableFrom(PdfReaderRuntimeViewModel::class.java))
        val container = application.container
        val factory = container.isolatedPdfRuntimeFactory
        val preferences = com.example.myapplication.data.reader.ReaderPreferenceStore(application)
        return PdfReaderRuntimeViewModel(documentId, initialLocatorJson, container.sessionManager.identity,
            { userId, id, revision -> container.readerRepository.openSession(userId, id, revision) },
            factory::open, factory.cleanupFailure, factory::retryCleanup, factory::retainCleanup,
            container.applicationScope, recovery, preferences::observeDefaults, preferences::saveDefaults,
            container.readerRepository::saveSettings,
            currentIdentity = { container.sessionManager.identity.first() },
            currentCatalogRevision = { _, id -> container.database.readerCatalogDao().findDocument(id)?.contentRevision },
            saveBoundDocumentSettings = { userId, id, json, revision, identityValid ->
                container.readerRepository.saveSettings(userId, id, json, revision, identityValid) },
            annotationServices=ReflowAnnotationServices({container.sessionManager.identity.first()},container.roomDocumentAccess,
                { ctx,id,kind,locator,quote,style,note,targets,guard -> container.annotationRepository.create(ctx,id,kind,locator,quote,style,noteText=note,targets=targets,writeGuard=guard) },
                { ctx,id,revision,note,style,guard -> container.annotationRepository.update(ctx,id,revision,noteText=note,style=style,writeGuard=guard) },
                container.annotationRepository::observeAnnotations,
                { ctx,id,revision,guard -> container.annotationRepository.removeText(ctx,id,revision,guard) },
                { ctx,id,revision,guard -> container.annotationRepository.restoreText(ctx,id,revision,guard) }),
            bookmarkServices=ReflowBookmarkServices({container.sessionManager.identity.first()},container.roomDocumentAccess,
                container.annotationRepository::observeBookmarks,container.annotationRepository::ensureBookmark,container.annotationRepository::toggleBookmark),
            observeDefaultsState = preferences::observeDefaultsState) as T
    }
}


