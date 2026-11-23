package com.example.myapplication.ui.knowledge.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.myapplication.data.reader.PreparedReaderResources
import com.example.myapplication.data.reader.ReaderResourcePreparer
import com.example.myapplication.domain.reader.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Opaque adapter-owned original-source proof prepared before entering a Room transaction. */
internal interface PdfAnnotationSourceProof {
    val token: ReaderSessionToken
    val frameEventSequence: Long
    val originalSnapshot: DocumentSnapshot
}

/** Isolated PDF runtime. No UI or progress persistence is enabled by constructing this adapter. */
class PdfReaderEngineAdapter(
    private val preparer: ReaderResourcePreparer,
    private val access: DocumentAccess,
    private val maxBitmapEdge: Int = 1600,
    private val textSurface: PdfReaderSurface = AndroidPdfReaderSurface(),
) : ReaderEngineAdapter {
    init { require(maxBitmapEdge in 64..4096) }
    private val mutex = Mutex()
    private val reports = MutableStateFlow<ReaderFeatureReport?>(null)
    private val selections = MutableStateFlow<ReaderSelectionEvent?>(null)
    @Volatile private var session: Session? = null
    private val annotationSource = AtomicReference<AnnotationSourceState?>(null)

    private class AnnotationSourceState(val owner: Session, val frameEventSequence: Long,
        val selection: ReaderSelectionEvent?, val ready: Boolean = true)

    private class OriginalAnnotationProof(val adapter: PdfReaderEngineAdapter, val state: AnnotationSourceState,
        val targets: List<ReaderLocator.Fixed>, val selected: ReaderSelectionEvent?, val sourceSha256: String,
        override val originalSnapshot: DocumentSnapshot) : PdfAnnotationSourceProof {
        override val token = state.owner.token
        override val frameEventSequence = state.frameEventSequence
    }

    private class Session(
        val context: AccessContext,
        val resources: PreparedReaderResources,
        val token: ReaderSessionToken,
        val descriptor: ParcelFileDescriptor,
        val renderer: PdfRenderer,
        val sourceSha256: String,
        val sectionByPage: List<SectionDescriptor>,
        var bitmap: Bitmap,
        var pageIndex: Int,
        var eventSequence: Long = 0,
    ) {
        var textPages: List<PdfTextPage> = emptyList()
        var selection: ReaderSelectionEvent? = null
        var marks: List<ReaderDecoration> = emptyList()
        var preciseLocator: ReaderLocator.Fixed? = null
        @Volatile var closing = false
        @Volatile var invalidated = false
        private var rendererClosed = false
        private var descriptorClosed = false
        fun locator() = preciseLocator ?: ReaderLocator.Fixed(token.documentId, sectionByPage[pageIndex].id,
            token.contentRevision, if (renderer.pageCount == 1) 0f else pageIndex.toFloat() / (renderer.pageCount - 1),
            pageIndex, NormalizedRect(0f, 0f, 1f, 1f), 0, emptyList())
        fun report(): ReaderFeatureReport {
            val page = textPages.firstOrNull { it.pageIndex == pageIndex }
            val selectable = page?.selectable == true
            // NO_TEXT describes this surface, not proof that the image contains words.
            // Unsupported extraction/geometry is neither an OCR attempt nor a reviewed result.
            val ocr = when {
                selectable -> ReaderOcrState.NOT_REQUIRED
                page?.unavailableReason == "NO_TEXT" && page.glyphs.isEmpty() -> ReaderOcrState.REQUIRED
                else -> ReaderOcrState.UNAVAILABLE
            }
            return ReaderFeatureReport(token, locator(),
                ReaderCapabilities(selectable, true, true, selectable, false, false),
                renderable = true, preciseSelectionGeometry = selectable, reflowable = false,
                ocrState = ocr, eventSequence = eventSequence)
        }
        fun dispose() {
            if (!bitmap.isRecycled) bitmap.recycle()
            if (!rendererClosed) { renderer.close(); rendererClosed = true }
            if (!descriptorClosed) { descriptor.close(); descriptorClosed = true }
        }
    }

    override suspend fun open(context: AccessContext, request: ReaderOpenRequest): OperationResult<ReaderFeatureReport> {
        val handle = ReaderSessionHandle(context.userId, context.sessionEpoch, request.target.documentId,
            request.target.contentRevision, UUID.randomUUID().toString())
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    if (session != null) return@withLock OperationResult.Rejected("PDF_SESSION_ALREADY_OPEN")
                    val prepared = preparer.prepare(context, request.target, fixedLayout = true)
                    if (prepared !is OperationResult.Ok) return@withLock prepared.failure()
                    val resources = prepared.value
                    val source = resources.pdfSource ?: return@withLock OperationResult.Rejected("PDF_SOURCE_MISSING")
                    val sourceSha256 = sourceHash(source)
                    var fd: ParcelFileDescriptor? = null
                    var renderer: PdfRenderer? = null
                    var bitmap: Bitmap? = null
                    var installed = false
                    try {
                        fd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
                        renderer = PdfRenderer(fd)
                        val pages = resources.sections.sortedBy { it.pageStart }
                        require(pages.size == renderer.pageCount && renderer.pageCount > 0) { "PDF section coverage differs" }
                        pages.forEachIndexed { index, section ->
                            require(section.pageStart == index && section.pageEnd == index) { "PDF requires one verified section per page" }
                        }
                        val index = when (request.placement) {
                            ReaderPlacement.START -> 0
                            ReaderPlacement.END -> renderer.pageCount - 1
                            ReaderPlacement.AT_LOCATOR -> validatedPage(request.locator, request.target.documentId,
                                request.target.contentRevision, pages, allowPrecise = true) ?: return@withLock OperationResult.Rejected("INVALID_PDF_LOCATOR")
                        }
                        bitmap = render(renderer, index)
                        currentCoroutineContext().ensureActive()
                        val check = access.open(context, resources.snapshot.documentId, resources.snapshot.contentRevision)
                        if (check !is OperationResult.Ok) return@withLock check.failure()
                        if (check.value != resources.snapshot) return@withLock OperationResult.Rejected("PDF_ACCESS_CHANGED")
                        val token = ReaderSessionToken(handle.userId, handle.sessionEpoch, handle.documentId,
                            handle.contentRevision, handle.sessionId, 0)
                        val active = Session(context, resources, token, fd, renderer, sourceSha256, pages, bitmap, index)
                        active.textPages = loadTextPages(active, index)
                        if (sourceHash(source) != sourceSha256) return@withLock OperationResult.Rejected("PDF_SOURCE_CHANGED")
                        val requested = request.locator as? ReaderLocator.Fixed
                        if (request.placement == ReaderPlacement.AT_LOCATOR && requested?.rects?.isNotEmpty() == true) {
                            if (!provenTarget(active, requested)) return@withLock OperationResult.Rejected("PDF_UNPROVEN_GEOMETRY")
                            active.preciseLocator = requested.copy(rects=requested.rects.toList())
                        }
                        currentCoroutineContext().ensureActive()
                        if (!authorized(active)) return@withLock OperationResult.Rejected("PDF_ACCESS_CHANGED")
                        session = active
                        annotationSource.set(AnnotationSourceState(active, active.eventSequence, active.selection))
                        if (active.closing || active.invalidated) revokeAnnotationSource(active)
                        reports.value = active.report()
                        installed = true
                        OperationResult.Ok(active.report(), context.requestId)
                    } catch (_: OutOfMemoryError) {
                        OperationResult.Failed("PDF_MEMORY_LIMIT", retryable = false)
                    } catch (error: CancellationException) { throw error
                    } catch (_: Exception) {
                        OperationResult.Failed("PDF_OPEN_FAILED", retryable = false)
                    } finally {
                        if (!installed) {
                            bitmap?.recycle()
                            try { renderer?.close() } finally { fd?.close() }
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            // withContext can cancel at its return boundary after installation.
            withContext(NonCancellable) { close(handle) }
            throw error
        }
    }

    override suspend fun turn(token: ReaderSessionToken, direction: ReaderTurnDirection, origin: ReaderTurnOrigin): ReaderTurnResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val active = session?.takeIf { it.token == token && !it.closing }
                    ?: return@withLock ReaderTurnResult.Failed("STALE_PDF_TOKEN", false)
                if (!authorized(active)) return@withLock ReaderTurnResult.Failed("PDF_ACCESS_CHANGED", false)
                val next = active.pageIndex + if (direction == ReaderTurnDirection.NEXT) 1 else -1
                if (next !in active.sectionByPage.indices) return@withLock ReaderTurnResult.UnitBoundary(direction)
                if (!move(active, next)) return@withLock ReaderTurnResult.Failed("PDF_RENDER_FAILED", false)
                ReaderTurnResult.Moved(active.locator())
            }
        }

    override suspend fun navigate(token: ReaderSessionToken, locator: ReaderLocator): ReaderNavigationResult {
        // The caller may own a mutable rectangle list. Freeze it before waiting for any lock or IO.
        val frozen = try { if (locator is ReaderLocator.Fixed) locator.copy(rects=immutable(locator.rects)) else locator }
            catch (_: Exception) { return ReaderNavigationResult.Unavailable("INVALID_PDF_LOCATOR") }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val active = session?.takeIf { it.token == token && !it.closing }
                    ?: return@withLock ReaderNavigationResult.Unavailable("STALE_PDF_TOKEN")
                if (!authorized(active)) return@withLock ReaderNavigationResult.Unavailable("PDF_ACCESS_CHANGED")
                val page = validatedPage(frozen, token.documentId, token.contentRevision, active.sectionByPage, allowPrecise = true)
                    ?: return@withLock ReaderNavigationResult.Unavailable("INVALID_PDF_LOCATOR")
                val fixed = frozen as ReaderLocator.Fixed
                if (fixed.rects.isNotEmpty() && !provenTarget(active, fixed)) return@withLock ReaderNavigationResult.Unavailable("PDF_UNPROVEN_GEOMETRY")
                if (!move(active, page, fixed.takeIf { it.rects.isNotEmpty() })) return@withLock ReaderNavigationResult.Unavailable("PDF_RENDER_FAILED")
                ReaderNavigationResult.Exact(active.locator())
            }
        }
    }

    /** Caller owns this copy and must recycle it. A stale or revoked token never exposes a frame. */
    suspend fun copyPageBitmap(token: ReaderSessionToken): OperationResult<Bitmap> {
        var allocated: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    val active = session?.takeIf { it.token == token && !it.closing }
                        ?: return@withLock OperationResult.Rejected("STALE_PDF_TOKEN")
                    if (!authorized(active)) return@withLock OperationResult.Rejected("PDF_ACCESS_CHANGED")
                    val copy = active.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        ?: return@withLock OperationResult.Failed("PDF_BITMAP_COPY_FAILED", false)
                    allocated = copy
                    currentCoroutineContext().ensureActive()
                    if (!authorized(active)) {
                        copy.recycle()
                        return@withLock OperationResult.Rejected("PDF_ACCESS_CHANGED")
                    }
                    OperationResult.Ok(copy, active.context.requestId)
                }
            }
        } catch (error: CancellationException) {
            allocated?.recycle()
            throw error
        } catch (_: OutOfMemoryError) {
            allocated?.recycle()
            return OperationResult.Failed("PDF_MEMORY_LIMIT", false)
        }
    }
    fun originalSnapshot(token: ReaderSessionToken): DocumentSnapshot? =
        session?.takeIf { it.token == token && !it.closing && !it.invalidated }?.resources?.snapshot

    fun readingOrder(token: ReaderSessionToken): BookmarkReadingOrder? =
        session?.takeIf { it.token == token && !it.closing && !it.invalidated }?.let { active ->
            BookmarkReadingOrder(token.documentId,token.contentRevision,emptyList(),
                fixedPages=active.sectionByPage.mapIndexed { index, section -> section.id to index })
        }

    suspend fun copyTextPages(token: ReaderSessionToken): OperationResult<List<PdfTextPage>> = withContext(Dispatchers.IO) { mutex.withLock {
        val active = session?.takeIf { it.token == token && !it.closing } ?: return@withLock OperationResult.Rejected("STALE_PDF_TOKEN")
        if (!authorized(active)) return@withLock OperationResult.Rejected("PDF_ACCESS_CHANGED")
        OperationResult.Ok(immutable(active.textPages), active.context.requestId)
    } }

    /** Adjacent preview shares the same page-space and remains bounded to one extra bitmap. */
    suspend fun copyAdjacentPageBitmap(token: ReaderSessionToken): OperationResult<Bitmap?> {
        var allocated: Bitmap? = null
        try { return withContext(Dispatchers.IO) { mutex.withLock {
            val active = session?.takeIf { it.token == token && !it.closing } ?: return@withLock OperationResult.Rejected("STALE_PDF_TOKEN")
            if (!authorized(active)) return@withLock OperationResult.Rejected("PDF_ACCESS_CHANGED")
            if (active.pageIndex + 1 !in active.sectionByPage.indices) return@withLock OperationResult.Ok(null, active.context.requestId)
            var bitmap: Bitmap? = null
            try {
                bitmap = render(active.renderer, active.pageIndex + 1)
                allocated = bitmap
                currentCoroutineContext().ensureActive()
                if (!authorized(active)) { bitmap.recycle(); return@withLock OperationResult.Rejected("PDF_ACCESS_CHANGED") }
                OperationResult.Ok(bitmap, active.context.requestId)
            } catch (cancelled: CancellationException) { bitmap?.recycle(); throw cancelled
            } catch (_: OutOfMemoryError) { bitmap?.recycle(); OperationResult.Failed("PDF_MEMORY_LIMIT", false)
            } catch (_: Exception) { bitmap?.recycle(); OperationResult.Failed("PDF_RENDER_FAILED", true) }
        } } } catch (cancelled: CancellationException) { allocated?.recycle(); throw cancelled }
    }

    suspend fun selectText(token: ReaderSessionToken, ranges: List<PdfPageTextRange>, selectionId: String? = null,
        phase: ReaderSelectionPhase = ReaderSelectionPhase.STABLE): OperationResult<ReaderSelectionEvent> = withContext(Dispatchers.IO) { mutex.withLock {
        val active = session?.takeIf { it.token == token && !it.closing } ?: return@withLock OperationResult.Rejected("STALE_PDF_TOKEN")
        if (!authorized(active)) return@withLock OperationResult.Rejected("PDF_ACCESS_CHANGED")
        if (phase == ReaderSelectionPhase.CLEARED || ranges.isEmpty() || ranges.size > 2 ||
            ranges.any { range -> active.textPages.none { it == range.page } } ||
            (selectionId != null && active.selection?.selectionId != selectionId))
            return@withLock OperationResult.Rejected("INVALID_PDF_SELECTION")
        val chosen = selectAcrossPdfPages(ranges) ?: return@withLock OperationResult.Rejected("INVALID_PDF_SELECTION")
        if (chosen.sumOf { it.text.length } > 8192) return@withLock OperationResult.Rejected("PDF_SELECTION_LIMIT")
        val fragments = chosen.map { selection ->
            val page = active.textPages.single { it.pageIndex == selection.pageIndex }
            ReaderSelectionFragment(ReaderLocator.Fixed(token.documentId, active.sectionByPage[page.pageIndex].id,
                token.contentRevision, if(active.sectionByPage.size == 1) 0f else page.pageIndex.toFloat()/(active.sectionByPage.size-1),
                page.pageIndex, NormalizedRect(0f,0f,1f,1f), page.rotationDegrees, selection.rects.toList(), selection.text), selection.text)
        }
        if (fragments.any { !ReaderLocatorValidator.validate(it.locator).isValid }) return@withLock OperationResult.Rejected("INVALID_PDF_GEOMETRY")
        if (!authorized(active)) return@withLock OperationResult.Rejected("PDF_ACCESS_CHANGED")
        val event = ReaderSelectionEvent(token,selectionId ?: UUID.randomUUID().toString(),phase,
            java.util.Collections.unmodifiableList(fragments.map { fragment ->
                fragment.copy(locator=(fragment.locator as ReaderLocator.Fixed).let { it.copy(rects=java.util.Collections.unmodifiableList(it.rects.toList())) })
            }),emptyList())
        changeAnnotationSource(active) {
            active.selection=event
            selections.value=event
        }
        OperationResult.Ok(event, active.context.requestId)
    } }

    suspend fun proveSelection(event: ReaderSelectionEvent): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        val active=session?.takeIf { it.token==event.token && !it.closing } ?: return@withLock false
        if (active.selection != event || event.phase != ReaderSelectionPhase.STABLE || !authorized(active)) return@withLock false
        val proofPages = linkedMapOf<Int, PdfTextPage>()
        event.fragments.all { provenTarget(active,it.locator,proofPages) } && authorized(active) && active.selection == event
    } }

    suspend fun validateAnnotationTargets(token: ReaderSessionToken, targets: List<ReaderLocator>): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        val active=session?.takeIf { it.token==token && !it.closing } ?: return@withLock false
        if (targets.isEmpty() || targets.size > 128 || !authorized(active)) return@withLock false
        val proofPages = linkedMapOf<Int, PdfTextPage>()
        targets.sortedBy { (it as? ReaderLocator.Fixed)?.pageIndex }.all { provenTarget(active,it,proofPages) } && authorized(active)
    } }

    internal suspend fun prepareAnnotationSourceProof(token: ReaderSessionToken, frameEventSequence: Long,
        targets: List<ReaderLocator>, selection: ReaderSelectionEvent? = null): OperationResult<PdfAnnotationSourceProof> {
        // Copy before any suspension: callers may own mutable locator/rectangle containers.
        val frozen = try {
            if (targets.isEmpty() || targets.size > 128) return OperationResult.Rejected("INVALID_PDF_ANNOTATION_TARGETS")
            immutable(targets.map { locator ->
                val fixed = locator as? ReaderLocator.Fixed
                    ?: return OperationResult.Rejected("INVALID_PDF_ANNOTATION_TARGETS")
                fixed.copy(rects=immutable(fixed.rects))
            })
        } catch (_: Exception) { return OperationResult.Rejected("INVALID_PDF_ANNOTATION_TARGETS") }
        val selected = try { selection?.let { event -> event.copy(fragments=immutable(event.fragments.map { fragment ->
            fragment.copy(locator=(fragment.locator as? ReaderLocator.Fixed)?.let { it.copy(rects=immutable(it.rects)) }
                ?: fragment.locator)
        }),screenRects=immutable(event.screenRects)) } }
        catch (_: Exception) { return OperationResult.Rejected("INVALID_PDF_SELECTION") }
        return try { withContext(Dispatchers.IO) { mutex.withLock {
            val active = session?.takeIf { it.token == token && !it.closing && !it.invalidated }
                ?: return@withLock OperationResult.Rejected("STALE_PDF_TOKEN")
            val state = annotationSource.get()?.takeIf { it.ready && it.owner === active && it.frameEventSequence == frameEventSequence }
                ?: return@withLock OperationResult.Rejected("STALE_PDF_SOURCE_PROOF")
            if (!authorized(active)) return@withLock OperationResult.Rejected("PDF_ACCESS_CHANGED")
            if (selected != null && (selected.token != token || selected.phase != ReaderSelectionPhase.STABLE ||
                    active.selection != selected)) return@withLock OperationResult.Rejected("INVALID_PDF_SELECTION")
            val proofPages = linkedMapOf<Int, PdfTextPage>()
            if (!frozen.all { provenTarget(active,it,proofPages) } ||
                (selected != null && !selected.fragments.all { provenTarget(active,it.locator,proofPages) }))
                return@withLock OperationResult.Rejected("PDF_UNPROVEN_GEOMETRY")
            currentCoroutineContext().ensureActive()
            if (!authorized(active) || annotationSource.get() !== state || active.closing || active.invalidated ||
                (selected != null && active.selection != selected)) return@withLock OperationResult.Rejected("STALE_PDF_SOURCE_PROOF")
            val snapshot = active.resources.snapshot.let { it.copy(sections=immutable(it.sections)) }
            OperationResult.Ok(OriginalAnnotationProof(this@PdfReaderEngineAdapter,state,frozen,selected,
                active.sourceSha256,snapshot), active.context.requestId)
        } } } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { OperationResult.Failed("PDF_ANNOTATION_SOURCE_PROOF_FAILED", false) }
    }

    /** Transaction-safe: no Room query, adapter mutex, renderer, or text extraction is permitted here. */
    internal suspend fun validateAnnotationSourceProof(proof: PdfAnnotationSourceProof): Boolean {
        currentCoroutineContext().ensureActive()
        val original = proof as? OriginalAnnotationProof ?: return false
        if (original.adapter !== this) return false
        val state = original.state
        fun current() = state.ready && annotationSource.get() === state && session === state.owner &&
            !state.owner.closing && !state.owner.invalidated &&
            (original.selected == null || original.selected == state.selection)
        if (!current()) return false
        val matches = try { sourceHash(checkNotNull(state.owner.resources.pdfSource)) == original.sourceSha256
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { false }
        currentCoroutineContext().ensureActive()
        if (!matches) {
            // A later byte restoration cannot revive a lease which observed changed source bytes.
            annotationSource.compareAndSet(state, null)
            state.owner.invalidated = true
            return false
        }
        return current()
    }

    suspend fun decorations(token: ReaderSessionToken): List<ReaderDecoration> = withContext(Dispatchers.IO) { mutex.withLock {
        val active=session?.takeIf { it.token==token && !it.closing } ?: return@withLock emptyList()
        if(authorized(active)) immutable(active.marks) else emptyList()
    } }

    override fun selectionEvents(token: ReaderSessionToken): Flow<ReaderSelectionEvent> =
        selections.takeWhile { session?.token == token && session?.closing == false && session?.invalidated == false }.filterNotNull().filter { it.token == token }
    override fun featureEvents(token: ReaderSessionToken): Flow<ReaderFeatureReport> = flow {
        reports.takeWhile { it?.token == token }.filterNotNull().collect { emit(it) }
    }
    override suspend fun clearSelection(token: ReaderSessionToken): OperationResult<Unit> = withContext(Dispatchers.IO) { mutex.withLock {
        val active=session?.takeIf { it.token==token && !it.closing } ?: return@withLock OperationResult.Rejected("STALE_PDF_TOKEN")
        if(!authorized(active)) return@withLock OperationResult.Rejected("PDF_ACCESS_CHANGED")
        changeAnnotationSource(active) {
            active.selection?.let { selections.value=it.copy(phase=ReaderSelectionPhase.CLEARED,fragments=emptyList(),screenRects=emptyList()) }
            active.selection=null
        }
        OperationResult.Ok(Unit,active.context.requestId)
    } }
    override suspend fun decorate(token: ReaderSessionToken, items: List<ReaderDecoration>): OperationResult<Unit> = withContext(Dispatchers.IO) { mutex.withLock {
        val active=session?.takeIf { it.token==token && !it.closing } ?: return@withLock OperationResult.Rejected("STALE_PDF_TOKEN")
        if(!authorized(active)) return@withLock OperationResult.Rejected("PDF_ACCESS_CHANGED")
        if (items.size > 1000 || items.sumOf { it.targets.size.toLong() } > 4096L ||
            items.any { it.targets.size > 128 } ||
            items.sumOf { item -> item.targets.sumOf { (it as? ReaderLocator.Fixed)?.rects?.size?.toLong() ?: 0L } } > 262144L)
            return@withLock OperationResult.Rejected("PDF_DECORATION_LIMIT")
        val candidates = items.filter { it.targets.isNotEmpty() && it.annotationId.isNotBlank() && it.annotationId.length <= 512 &&
            it.revision >= 0 && Regex("#[0-9a-fA-F]{6}").matches(it.colorHex) }.map { item ->
            item.copy(targets=immutable(item.targets.map { target ->
                if (target is ReaderLocator.Fixed) target.copy(rects=immutable(target.rects)) else target
            }))
        }
        // Validate each source page once per batch, with a bounded temporary cache. Never retain an open PDF.
        val proofPages = linkedMapOf<Int, PdfTextPage>()
        val proven = candidates.flatMap { it.targets }.distinct().sortedBy { (it as? ReaderLocator.Fixed)?.pageIndex }
            .associateWith { provenTarget(active,it,proofPages) }
        val accepted = candidates.filter { item -> item.targets.all { proven[it] == true } }
        if (!authorized(active)) return@withLock OperationResult.Rejected("PDF_ACCESS_CHANGED")
        active.marks=immutable(accepted)
        OperationResult.Ok(Unit,active.context.requestId)
    } }
    override suspend fun applyPreferences(token: ReaderSessionToken, preferences: ReaderLayoutPreferences): OperationResult<ReaderFeatureReport> = unsupported(token)
    private suspend fun unsupported(token: ReaderSessionToken): OperationResult<Nothing> = mutex.withLock {
        OperationResult.Rejected(if (session?.token != token) "STALE_PDF_TOKEN" else "PDF_OPERATION_UNSUPPORTED")
    }
    override suspend fun close(handle: ReaderSessionHandle) = withContext(NonCancellable + Dispatchers.IO) {
        session?.takeIf { it.token.handle() == handle }?.let { active ->
            active.closing = true
            revokeAnnotationSource(active)
        }
        mutex.withLock {
            val active = session?.takeIf { it.token.handle() == handle } ?: return@withLock
            active.closing = true
            selections.value=null
            reports.value = null
            active.dispose()
            session = null
        }
    }

    private suspend fun authorized(active: Session): Boolean {
        if (active.invalidated || active.closing) return false
        return try {
            val result = access.open(active.context, active.token.documentId, active.token.contentRevision)
            (result is OperationResult.Ok && result.value == active.resources.snapshot &&
                sourceHash(checkNotNull(active.resources.pdfSource)) == active.sourceSha256).also {
                if (!it) invalidate(active)
            }
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) { invalidate(active); false }
    }
    private fun invalidate(active: Session) {
        revokeAnnotationSource(active)
        active.invalidated = true
        active.selection = null
        active.marks = emptyList()
        active.textPages = emptyList()
        reports.value = null
        selections.value = null
    }
    private suspend fun move(active: Session, index: Int, finalLocator: ReaderLocator.Fixed? = null): Boolean {
        var next: Bitmap? = null
        try {
            next = render(active.renderer, index)
            currentCoroutineContext().ensureActive()
            if (!authorized(active)) return false
            val textPages = loadTextPages(active,index)
            currentCoroutineContext().ensureActive()
            if (!authorized(active)) return false
            val old = active.bitmap
            changeAnnotationSource(active) {
                active.bitmap = checkNotNull(next)
                next = null
                active.pageIndex = index
                active.textPages=textPages
                active.preciseLocator=finalLocator
                active.selection=null
                selections.value=null
                active.eventSequence++
            }
            old.recycle()
            reports.value = active.report()
            return true
        } catch (error: CancellationException) { throw error
        } catch (_: OutOfMemoryError) { return false
        } catch (_: Exception) { return false
        } finally { next?.recycle() }
    }
    private fun render(renderer: PdfRenderer, index: Int): Bitmap = renderer.openPage(index).use { page ->
        require(page.width > 0 && page.height > 0)
        val scale = maxBitmapEdge.toDouble() / maxOf(page.width, page.height)
        val width = (page.width * scale).toInt().coerceIn(1, maxBitmapEdge)
        val height = (page.height * scale).toInt().coerceIn(1, maxBitmapEdge)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (error: Throwable) { bitmap.recycle(); throw error }
    }
    private suspend fun loadTextPages(active: Session, index: Int): List<PdfTextPage> =
        (index..minOf(index+1,active.sectionByPage.lastIndex)).map { pageIndex ->
            active.textPages.firstOrNull { it.pageIndex == pageIndex } ?: loadTextPage(active,pageIndex)
        }

    private suspend fun loadTextPage(active: Session, pageIndex: Int): PdfTextPage {
        currentCoroutineContext().ensureActive()
        val dimensions = active.renderer.openPage(pageIndex).use { it.width to it.height }
        val page = textSurface.loadPage(checkNotNull(active.resources.pdfSource),pageIndex,dimensions.first,dimensions.second)
        currentCoroutineContext().ensureActive()
        // The injected surface is a boundary, including dimensions and immutable UTF-16 geometry.
        if (page.pageIndex != pageIndex || page.width != dimensions.first || page.height != dimensions.second ||
            page.glyphs.size > 50000 || page.glyphs.sumOf { it.text.length.toLong() } > 262144L)
            return PdfTextPage(pageIndex,dimensions.first,dimensions.second,0,emptyList(),"INVALID_SURFACE_PAGE")
        return page.copy(glyphs=immutable(page.glyphs))
    }

    private fun <T> immutable(items: List<T>): List<T> = java.util.Collections.unmodifiableList(items.toList())

    private fun revokeAnnotationSource(active: Session) {
        while (true) {
            val state = annotationSource.get() ?: return
            if (state.owner !== active || annotationSource.compareAndSet(state,null)) return
        }
    }

    /** Publish a retired marker before changing source ownership, then a fresh immutable lease. */
    private inline fun changeAnnotationSource(active: Session, change: () -> Unit) {
        val before = annotationSource.get()
        val retired = AnnotationSourceState(active,active.eventSequence,active.selection,ready=false)
        val owns = before?.owner === active && annotationSource.compareAndSet(before,retired)
        change()
        if (owns && session === active && !active.closing && !active.invalidated)
            annotationSource.compareAndSet(retired,AnnotationSourceState(active,active.eventSequence,active.selection))
    }

    private suspend fun sourceHash(source: java.io.File): String = withContext(Dispatchers.IO) {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        source.inputStream().buffered().use { stream ->
            val buffer=ByteArray(65536)
            while(true) {
                currentCoroutineContext().ensureActive()
                val count=stream.read(buffer)
                if(count<0) break
                digest.update(buffer,0,count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private suspend fun provenTarget(active: Session, locator: ReaderLocator,
        proofPages: MutableMap<Int, PdfTextPage> = linkedMapOf()): Boolean {
        val fixed=locator as? ReaderLocator.Fixed ?: return false
        if(validatedPage(fixed,active.token.documentId,active.token.contentRevision,active.sectionByPage,true)==null) return false
        if(fixed.rects.isEmpty()) return fixed.textQuote==null && fixed.rotationDegrees==0
        if (fixed.rects.size > 8192) return false
        val page=active.textPages.firstOrNull { it.pageIndex==fixed.pageIndex } ?: proofPages[fixed.pageIndex] ?: run {
            val loaded = loadTextPage(active,fixed.pageIndex)
            if (proofPages.size >= 2) proofPages.remove(proofPages.keys.first())
            proofPages[fixed.pageIndex] = loaded
            loaded
        }
        if(!page.selectable || page.rotationDegrees!=fixed.rotationDegrees) return false
        val first=page.glyphs.firstOrNull { it.rect==fixed.rects.first() } ?: return false
        val end=first.start+(fixed.textQuote?.length ?: return false)
        val selected=page.select(first.start,end) ?: return false
        return selected.text==fixed.textQuote && selected.rects==fixed.rects
    }

    private fun validatedPage(locator: ReaderLocator?, documentId: String, revision: String, pages: List<SectionDescriptor>, allowPrecise: Boolean = false): Int? {
        val fixed = locator as? ReaderLocator.Fixed ?: return null
        if (!ReaderLocatorValidator.validate(fixed).isValid || fixed.documentId != documentId || fixed.contentRevision != revision ||
            fixed.pageIndex !in pages.indices || pages[fixed.pageIndex].id != fixed.sectionId ||
            fixed.pageBox != NormalizedRect(0f, 0f, 1f, 1f) ||
            (!allowPrecise && (fixed.textQuote != null || fixed.rects.isNotEmpty() || fixed.rotationDegrees != 0)) ||
            (fixed.rects.isEmpty() && (fixed.textQuote != null || fixed.rotationDegrees != 0)) ||
            (fixed.rects.isNotEmpty() && fixed.textQuote.isNullOrBlank())) return null
        return fixed.pageIndex
    }
    private fun OperationResult<*>.failure(): OperationResult<Nothing> = when (this) {
        is OperationResult.Conflict -> this
        is OperationResult.Rejected -> this
        is OperationResult.Failed -> this
        is OperationResult.Ok -> error("expected failure")
    }
}

