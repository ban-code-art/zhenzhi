package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit production-HTML adapter. Locations are verified block anchors, not character geometry. */
class ReflowReaderEngineAdapter(
    private val prepare: suspend (AccessContext, ReaderTarget) -> OperationResult<PreparedReflowDocument>,
    private val access: DocumentAccess,
    private val surface: ReflowReaderSurface,
) : ReaderEngineAdapter {
    private var nativeSelectionPort: ReflowSelectionSurfacePort? = null
    internal val selectionPort: ReflowSelectionPort = object : ReflowSelectionPort {
        override fun bookmarkState(token: ReaderSessionToken): ReflowBookmarkNativeState? {
            val session = active?.takeIf { it.token == token && !it.closing } ?: return null
            val binding = session.selectionSource?.takeIf { !it.ended && it.activated }?.binding ?: return null
            return nativeSelectionPort?.bookmarkState(binding)
        }
        override suspend fun proveBookmark(expected: ReflowBookmarkNativeState): Boolean {
            if (bookmarkState(expected.binding.token) != expected) return false
            return nativeSelectionPort?.proveBookmark(expected) == true &&
                bookmarkState(expected.binding.token) == expected
        }
        override fun readingOrder(token: ReaderSessionToken): BookmarkReadingOrder? = current(token)?.readingOrder
        override fun originalSnapshot(token: ReaderSessionToken): DocumentSnapshot? = current(token)?.originalSnapshot
        override fun isActive(token: ReaderSessionToken): Boolean = current(token) != null
        override fun events(token: ReaderSessionToken): Flow<ReflowSelectionEnvelope> = selectionFlow(token)
        override fun validateSource(token: ReaderSessionToken, fragments: List<ReaderSelectionFragment>): LocatorValidation =
            current(token)?.let { validateSelection(it, token, fragments) }
                ?: LocatorValidation(listOf("STALE_REFLOW_TOKEN"))
        override suspend fun activate(token: ReaderSessionToken): OperationResult<Unit> {
            // An opening/layout operation may be holding lock while it waits for native ack.
            // Its unpublished tokens must fail immediately, instead of waiting on that lock.
            if (current(token) == null) return OperationResult.Rejected("STALE_REFLOW_TOKEN")
            return lock.withLock {
                val owner = current(token) ?: return@withLock OperationResult.Rejected("STALE_REFLOW_TOKEN")
                if (!authorized(owner)) { suspendSelectionSource(owner); return@withLock OperationResult.Rejected("REFLOW_ACCESS_CHANGED") }
                val source = owner.selectionSource?.takeUnless { it.ended } ?: return@withLock OperationResult.Rejected("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
                source.activated = true
                val result = try { checkNotNull(nativeSelectionPort).activate(source.binding) }
                    catch (cancelled: CancellationException) { source.finish(); throw cancelled }
                    catch (_: Exception) { OperationResult.Failed("REFLOW_SELECTION_ACTIVATION_FAILED", false) }
                if (result !is OperationResult.Ok) suspendSelectionSource(owner)
                result
            }
        }
        override suspend fun confirmStable(capture: ReflowSelectionCapture): OperationResult<Unit> =
            selectionAction(capture) { it.confirmStable(capture) }
        override suspend fun copy(request: ReflowSelectionCopyRequest): OperationResult<ReflowSelectionCopyReceipt> =
            copySelection(request)
        override fun committedCopyReceipt(request: ReflowSelectionCopyRequest): ReflowSelectionCopyReceipt? =
            committedCopy(request)
        override suspend fun temporaryFocusHandoff(capture: ReflowSelectionCapture): OperationResult<Unit> =
            selectionAction(capture) { it.temporaryFocusHandoff(capture) }
        override suspend fun compareAndClear(request: SelectionNativeClearRequest, capture: ReflowSelectionCapture): OperationResult<Unit> {
            if (request.token != capture.binding.token || request.selectionId != capture.selectionId)
                return OperationResult.Rejected("STALE_SELECTION")
            return selectionAction(capture) { it.compareAndClear(request, capture) }
        }
    }
    internal constructor(
        prepare: suspend (AccessContext, ReaderTarget) -> OperationResult<PreparedReflowDocument>,
        access: DocumentAccess,
        surface: ReflowReaderSurface,
        selectionSurfacePort: ReflowSelectionSurfacePort,
    ) : this(prepare, access, surface) {
        nativeSelectionPort = selectionSurfacePort
    }
    private val lock = Mutex()
    private val reports = MutableStateFlow<ReaderFeatureReport?>(null)
    @Volatile private var active: Session? = null
    @Volatile private var retired = false
    private class Session(val context: AccessContext, val document: PreparedReflowDocument,
        @Volatile var token: ReaderSessionToken, var viewport: ReflowViewport) {
        val originalSnapshot = freezeAnnotationSnapshot(document.resources.snapshot)
        var exactLocator: ReaderLocator.Reflow? = null
        var flowMode = ReaderFlowMode.PAGINATED
        var sequence = 0L
        @Volatile var closing = false
        @Volatile var selectionSource: NativeSelectionSource? = null
        val selectionLifecycle = Any()
        var revokedSelectionToken: ReaderSessionToken? = null
        val canonicalSource = SelectionCanonicalSource(token.handle(), document.blocks)
        val readingOrder = BookmarkReadingOrder(token.documentId, token.contentRevision,
            document.blocks.sortedWith(compareBy<ContentBlock> { block ->
                document.resources.snapshot.sections.singleOrNull { it.id == block.sectionId }?.orderIndex ?: Int.MAX_VALUE
            }.thenBy { it.canonicalStart }).map { it.sectionId to it.blockId })
        val sourceLocators = document.blocks.associate { (it.sectionId to it.blockId) to it.nativeLocator }
    }
    private class NativeSelectionSource(val binding: ReflowSelectionBinding) {
        val gate = ReflowSelectionEventGate(binding)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val listeners = LinkedHashSet<Channel<ReflowSelectionEnvelope>>()
        @Volatile var activated = false
        @Volatile var ended = false
        fun finish() {
            val pending = synchronized(listeners) {
                if (ended) return
                ended = true
                activated = false
                listeners.toList().also { listeners.clear() }
            }
            pending.forEach { it.cancel(CancellationException("REFLOW_SELECTION_SOURCE_ENDED")) }
            scope.cancel()
        }
    }
    override suspend fun open(context: AccessContext, request: ReaderOpenRequest): OperationResult<ReaderFeatureReport> {
        val handle = ReaderSessionHandle(context.userId, context.sessionEpoch, request.target.documentId,
            request.target.contentRevision, UUID.randomUUID().toString())
        try {
            return lock.withLock {
                if (active != null || retired) return@withLock OperationResult.Rejected("REFLOW_SESSION_UNAVAILABLE")
                val prepared = prepare(context, request.target)
                if (prepared !is OperationResult.Ok) return@withLock prepared.failure()
                val document = prepared.value
                if (!validDocument(document, context, request.target)) return@withLock OperationResult.Rejected("INVALID_REFLOW_DOCUMENT")
                val requested = if(request.placement == ReaderPlacement.AT_LOCATOR) validLocator(document, request.locator) else null
                if(request.placement == ReaderPlacement.AT_LOCATOR && requested == null) return@withLock OperationResult.Rejected("INVALID_REFLOW_LOCATOR")
                val seed = ReflowViewport(document.blocks.first().blockId, 0, 1)
                val owner = Session(context, document, ReaderSessionToken(handle.userId,handle.sessionEpoch,
                    handle.documentId,handle.contentRevision,handle.sessionId,0), seed)
                active = owner // Retain cleanup ownership even if opening throws or is cancelled.
                try {
                    if(!authorized(owner)) return@withLock OperationResult.Rejected("REFLOW_ACCESS_CHANGED")
                    var viewport = surface.open(document, request.placement, requested?.blockId)
                    if (requested != null && surface.supportsExactLocations) {
                        viewport = surface.locate(requested)
                        owner.exactLocator = requested
                    }
                    currentCoroutineContext().ensureActive()
                    require(validViewport(owner, viewport))
                    if(!authorized(owner)) return@withLock OperationResult.Rejected("REFLOW_ACCESS_CHANGED")
                    owner.viewport = viewport
                    bindSelectionSource(owner)
                    currentCoroutineContext().ensureActive()
                    if (!authorized(owner)) return@withLock OperationResult.Rejected("REFLOW_ACCESS_CHANGED")
                    val report = publishReport(owner)
                    OperationResult.Ok(report, context.requestId)
                } catch(e: CancellationException) { throw e
                } catch (_: Exception) { OperationResult.Failed("REFLOW_OPEN_FAILED", true)
                } finally {
                    if(reports.value == null) withContext(NonCancellable) {
                        try { dispose(owner) } catch (_: Exception) { /* Retained for retryCleanup. */ }
                    }
                }
            }
        } catch(e: CancellationException) {
            withContext(NonCancellable) {
                try { close(handle) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            }
            throw e
        }
    }
    override suspend fun turn(token: ReaderSessionToken, direction: ReaderTurnDirection, origin: ReaderTurnOrigin): ReaderTurnResult = lock.withLock {
        val owner = current(token) ?: return@withLock ReaderTurnResult.Failed("STALE_REFLOW_TOKEN", false)
        if(!authorized(owner)) { suspendSelectionSource(owner); return@withLock ReaderTurnResult.Failed("REFLOW_ACCESS_CHANGED", false) }
        try {
            val before = if (owner.flowMode == ReaderFlowMode.SCROLL) surface.refreshViewport()?.also {
                require(validViewport(owner, it)); owner.viewport = it
                owner.exactLocator = surface.currentPosition()?.let { point -> validLocator(owner.document, point) }
            } ?: owner.viewport else owner.viewport
            if((direction == ReaderTurnDirection.PREVIOUS && before.atStart) ||
                (direction == ReaderTurnDirection.NEXT && before.atEnd)) return@withLock ReaderTurnResult.UnitBoundary(direction)
            val moved = surface.turn(direction)
            require(validViewport(owner,moved))
            require(moved.pageCount == before.pageCount)
            if (owner.flowMode == ReaderFlowMode.SCROLL) require(
                if (direction == ReaderTurnDirection.NEXT) moved.progression > before.progression else moved.progression < before.progression)
            else require(moved.pageIndex == before.pageIndex + if(direction == ReaderTurnDirection.NEXT) 1 else -1)
            currentCoroutineContext().ensureActive()
            if(!authorized(owner)) { invalidate(owner); return@withLock ReaderTurnResult.Failed("REFLOW_ACCESS_CHANGED", false) }
            owner.exactLocator = if (owner.flowMode == ReaderFlowMode.SCROLL)
                surface.currentPosition()?.let { validLocator(owner.document, it) } else null
            publish(owner,moved)
            ReaderTurnResult.Moved(locator(owner))
        } catch(e: CancellationException) { invalidate(owner); throw e
        } catch (_: Exception) { invalidate(owner); ReaderTurnResult.Failed("REFLOW_TURN_FAILED",false) }
    }
    override suspend fun navigate(token: ReaderSessionToken, locator: ReaderLocator): ReaderNavigationResult = lock.withLock {
        val owner = current(token) ?: return@withLock ReaderNavigationResult.Unavailable("STALE_REFLOW_TOKEN")
        if(!authorized(owner)) { suspendSelectionSource(owner); return@withLock ReaderNavigationResult.Unavailable("REFLOW_ACCESS_CHANGED") }
        val wanted = validLocator(owner.document,locator) ?: return@withLock ReaderNavigationResult.Unavailable("INVALID_REFLOW_LOCATOR")
        try {
            val found = surface.locate(wanted)
            owner.exactLocator = wanted.takeIf { surface.supportsExactLocations }
            require(validViewport(owner,found))
            currentCoroutineContext().ensureActive()
            if(!authorized(owner)) { invalidate(owner); return@withLock ReaderNavigationResult.Unavailable("REFLOW_ACCESS_CHANGED") }
            publish(owner, found)
            val anchor = locator(owner)
            if(wanted.blockId == anchor.blockId && wanted.canonicalStart == anchor.canonicalStart && wanted.canonicalEnd == anchor.canonicalEnd && wanted.exactQuote == anchor.exactQuote &&
                (wanted.progression == null || wanted.progression == anchor.progression))
                ReaderNavigationResult.Exact(anchor)
            else ReaderNavigationResult.Approximate(anchor,"BLOCK_ANCHOR_ONLY")
        } catch(e: CancellationException) { invalidate(owner); throw e
        } catch (_: Exception) { invalidate(owner); ReaderNavigationResult.Unavailable("REFLOW_NAVIGATION_FAILED") }
    }
    override suspend fun applyPreferences(token: ReaderSessionToken, preferences: ReaderLayoutPreferences): OperationResult<ReaderFeatureReport> =
        applyLayout(token, preferences, null)
    internal suspend fun applyOpeningPreferences(token: ReaderSessionToken, preferences: ReaderLayoutPreferences, request: ReaderOpenRequest): OperationResult<ReaderFeatureReport> =
        applyLayout(token, preferences, request)
    private suspend fun applyLayout(token: ReaderSessionToken, preferences: ReaderLayoutPreferences, request: ReaderOpenRequest?): OperationResult<ReaderFeatureReport> = lock.withLock {
        val owner = current(token) ?: return@withLock OperationResult.Rejected("STALE_REFLOW_TOKEN")
        if(!authorized(owner)) { suspendSelectionSource(owner); return@withLock OperationResult.Rejected("REFLOW_ACCESS_CHANGED") }
        if(!preferences.fontScale.isFinite() || preferences.fontScale !in 0.5f..3f ||
            !preferences.lineHeight.isFinite() || preferences.lineHeight !in 1f..3f || preferences.marginDp !in 0..96 ||
            !preferences.backgroundHex.matches(Regex("#[0-9a-fA-F]{6}")) ||
            (preferences.foregroundHex != null && !preferences.foregroundHex.matches(Regex("#[0-9a-fA-F]{6}"))))
            return@withLock OperationResult.Rejected("UNSUPPORTED_REFLOW_PREFERENCES")
        try {
            if (owner.flowMode == ReaderFlowMode.SCROLL) surface.refreshViewport()?.let { owner.viewport = it }
            val restore = (request?.locator as? ReaderLocator.Reflow)?.let { validLocator(owner.document, it) }
                ?: surface.currentPosition()?.let { validLocator(owner.document, it) } ?: owner.exactLocator
            val block = restore?.blockId ?: owner.viewport.blockId
            // Stop the old Kotlin source before any CSS/DOM mutation, and before new identity.
            reports.value = null
            suspendSelectionSource(owner)
            var layout = if (request == null) surface.applyPreferences(preferences, block)
                else surface.applyOpeningPreferences(preferences, request.placement, block)
            if ((request == null || request.placement == ReaderPlacement.AT_LOCATOR) && restore != null && surface.supportsExactLocations) {
                layout = surface.locate(restore)
                owner.exactLocator = restore
            } else owner.exactLocator = null
            require(validViewport(owner,layout))
            currentCoroutineContext().ensureActive()
            if(!authorized(owner)) { invalidate(owner); return@withLock OperationResult.Rejected("REFLOW_ACCESS_CHANGED") }
            synchronized(owner.selectionLifecycle) {
                check(active === owner && !owner.closing) { "REFLOW_SESSION_CLOSED" }
                owner.token = owner.token.copy(layoutGeneration=Math.addExact(owner.token.layoutGeneration,1))
            }
            owner.viewport = layout
            owner.flowMode = preferences.flowMode
            bindSelectionSource(owner)
            currentCoroutineContext().ensureActive()
            if (!authorized(owner)) { invalidate(owner); return@withLock OperationResult.Rejected("REFLOW_ACCESS_CHANGED") }
            val report = publish(owner,layout)
            OperationResult.Ok(report,owner.context.requestId)
        } catch (_: TimeoutCancellationException) {
            invalidate(owner)
            currentCoroutineContext().ensureActive()
            OperationResult.Failed("REFLOW_LAYOUT_TIMEOUT", true)
        } catch(e: CancellationException) { invalidate(owner); throw e
        } catch (_: Exception) { invalidate(owner); OperationResult.Failed("REFLOW_LAYOUT_FAILED",false) }
    }
    override fun featureEvents(token: ReaderSessionToken): Flow<ReaderFeatureReport> = flow {
        val owner = current(token) ?: return@flow
        val allowed = authorized(owner) && current(token) === owner
        if (!allowed) suspendSelection(token)
        if(allowed) coroutineScope {
            val scrolling = launch {
                while (current(token) === owner) {
                    delay(180)
                    if (owner.flowMode != ReaderFlowMode.SCROLL || !surface.viewportNeedsRefresh) continue
                    lock.withLock {
                        if (current(token) !== owner) return@withLock
                        if (!authorized(owner)) { suspendSelectionSource(owner); return@withLock }
                        try {
                            val measured = surface.refreshViewport() ?: return@withLock
                            val exact = surface.currentPosition()?.let { validLocator(owner.document, it) }
                            if (validViewport(owner, measured) && (measured != owner.viewport || exact != owner.exactLocator)) {
                                owner.exactLocator = exact
                                publish(owner, measured)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { invalidate(owner) }
                    }
                }
            }
            try { emitAll(reports.takeWhile { it?.token == token }.filterNotNull()) }
            finally { scrolling.cancel() }
        }
    }
    override fun selectionEvents(token: ReaderSessionToken): Flow<ReaderSelectionEvent> =
        selectionFlow(token).mapNotNull { it.event }
    override suspend fun clearSelection(token: ReaderSessionToken): OperationResult<Unit> {
        val owner = current(token) ?: return OperationResult.Rejected("STALE_REFLOW_TOKEN")
        if (!authorized(owner)) { suspendSelection(token); return OperationResult.Rejected("REFLOW_ACCESS_CHANGED") }
        if (current(token) !== owner) return OperationResult.Rejected("STALE_REFLOW_TOKEN")
        return OperationResult.Rejected(if (nativeSelectionPort == null) "REFLOW_FEATURE_UNAVAILABLE" else "REFLOW_SELECTION_ID_REQUIRED")
    }
    override suspend fun decorate(token: ReaderSessionToken, items: List<ReaderDecoration>): OperationResult<Unit> = lock.withLock {
        val owner = current(token) ?: return@withLock OperationResult.Rejected("STALE_REFLOW_TOKEN")
        if (!authorized(owner)) return@withLock OperationResult.Rejected("REFLOW_ACCESS_CHANGED")
        val frozen = items.map { it.copy(targets = it.targets.toList()) }
        if (frozen.any { it.annotationId.isBlank() || it.revision <= 0 || !it.colorHex.matches(Regex("#[0-9a-fA-F]{6}")) ||
                it.targets.isEmpty() || it.targets.any { target -> validLocator(owner.document,target) == null } })
            return@withLock OperationResult.Rejected("INVALID_REFLOW_DECORATION")
        try {
            if (!surface.decorate(frozen)) return@withLock OperationResult.Rejected("REFLOW_DECORATION_SOURCE_UNAVAILABLE")
            if (current(token) !== owner || !authorized(owner)) {
                surface.decorate(emptyList())
                return@withLock OperationResult.Rejected("REFLOW_ACCESS_CHANGED")
            }
            OperationResult.Ok(Unit, owner.context.requestId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { OperationResult.Failed("REFLOW_DECORATION_FAILED",true) }
    }
    private suspend fun unsupported(token: ReaderSessionToken): OperationResult<Unit> = lock.withLock {
        val owner = current(token) ?: return@withLock OperationResult.Rejected("STALE_REFLOW_TOKEN")
        if(!authorized(owner)) { suspendSelectionSource(owner); OperationResult.Rejected("REFLOW_ACCESS_CHANGED") }
        else OperationResult.Rejected("REFLOW_FEATURE_UNAVAILABLE")
    }
    override suspend fun close(handle: ReaderSessionHandle) = withContext(NonCancellable) {
        val owner = active?.takeIf { it.token.handle() == handle } ?: return@withContext
        if (!beginClose(owner, handle)) return@withContext
        lock.withLock { if (active === owner && owner.token.handle() == handle) dispose(owner) }
        Unit
    }
    /** Failed open has no public token: its retained native owner remains explicitly retryable. */
    suspend fun retryCleanup(): Boolean = withContext(NonCancellable) {
        lock.withLock {
            val owner = active?.takeIf { it.closing } ?: return@withLock active == null
            try { dispose(owner); true } catch (_: Exception) { false }
        }
    }
    private suspend fun invalidate(owner: Session) = withContext(NonCancellable) {
        try { dispose(owner) } catch (_: Exception) { /* Preserve closing owner for retryCleanup. */ }
    }
    private suspend fun dispose(owner: Session) {
        beginClose(owner, owner.token.handle())
        surface.close() // A thrown close leaves active retained for retry by the same stable handle.
        if(active === owner) active = null
    }
    private fun beginClose(owner: Session, handle: ReaderSessionHandle): Boolean = synchronized(owner.selectionLifecycle) {
        if (active !== owner || owner.token.handle() != handle) return@synchronized false
        owner.closing = true
        reports.value = null
        retired = true
        try { revokeNativeSelection(owner, owner.token) }
        finally { owner.selectionSource?.finish() }
        true
    }
    private fun revokeNativeSelection(owner: Session, token: ReaderSessionToken) {
        // Called under selectionLifecycle: a stale token cannot revoke the next layout binding.
        if (owner.revokedSelectionToken == token) return
        nativeSelectionPort?.revokeBinding(token.handle())
        owner.revokedSelectionToken = token
    }
    private fun revokeSelectionSource(owner: Session, token: ReaderSessionToken): NativeSelectionSource? =
        synchronized(owner.selectionLifecycle) {
            if (active !== owner || owner.token != token) return@synchronized null
            val source = owner.selectionSource?.takeIf { it.binding.token == token } ?: return@synchronized null
            try { revokeNativeSelection(owner, token) }
            finally { source.finish() }
            source
        }
    private fun publishReport(owner: Session): ReaderFeatureReport = synchronized(owner.selectionLifecycle) {
        check(active === owner && !owner.closing) { "REFLOW_SESSION_CLOSED" }
        report(owner).also { reports.value = it }
    }
    private fun current(token: ReaderSessionToken) = active?.takeIf { it.token == token && !it.closing && reports.value != null }
    private suspend fun authorized(owner: Session): Boolean {
        val result = access.open(owner.context,owner.token.documentId,owner.token.contentRevision)
        return result is OperationResult.Ok && result.value == owner.document.resources.snapshot
    }
    internal suspend fun suspendSelection(token: ReaderSessionToken) {
        val owner = current(token) ?: return
        // Gate/EOS revoke is synchronous; only the native stop acknowledgement waits for action.
        val source = revokeSelectionSource(owner, token) ?: return
        lock.withLock {
            if (active === owner && owner.token == token && owner.selectionSource === source) suspendSelectionSource(owner)
        }
    }
    private suspend fun suspendSelectionSource(owner: Session) {
        val source = synchronized(owner.selectionLifecycle) { owner.selectionSource } ?: return
        source.finish()
        nativeSelectionPort?.suspendBinding(source.binding)
        synchronized(owner.selectionLifecycle) {
            if (owner.selectionSource === source) owner.selectionSource = null
        }
    }
    private suspend fun bindSelectionSource(owner: Session) {
        val native = nativeSelectionPort ?: return
        val token = synchronized(owner.selectionLifecycle) {
            if (active !== owner || owner.closing) return
            owner.token
        }
        val binding = try { native.bind(token) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        if (binding == null) return
        if (binding.token != token || !ReflowSelectionValidation.binding(binding)) {
            native.suspendBinding(binding)
            return
        }
        val source = NativeSelectionSource(binding)
        val retained = synchronized(owner.selectionLifecycle) {
            if (active !== owner || owner.closing || owner.token != token || owner.revokedSelectionToken == token) false
            else { owner.selectionSource = source; true }
        }
        if (!retained) {
            source.finish()
            native.suspendBinding(binding)
            return
        }
        // Subscribe once, before capability publication and before reveal/activation.
        source.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                native.events(binding).collect { envelope ->
                    if (active !== owner || owner.closing || owner.token != binding.token || source.ended)
                        throw CancellationException("STALE_REFLOW_SELECTION_BINDING")
                    if (!source.activated) return@collect
                    if (!authorized(owner)) {
                        source.finish()
                        withContext(NonCancellable) { native.suspendBinding(binding) }
                        return@collect
                    }
                    if (!ReflowSelectionValidation.envelope(envelope) || envelope.binding != binding) return@collect
                    val event = envelope.event
                    if (event != null && event.phase != ReaderSelectionPhase.CLEARED &&
                        !validateSelection(owner, binding.token, event.fragments).isValid) return@collect
                    if (!source.gate.accept(envelope)) return@collect
                    val subscribers = synchronized(source.listeners) { source.listeners.toList() }
                    for (subscriber in subscribers) {
                        try { subscriber.send(envelope) }
                        catch (_: ClosedSendChannelException) { /* A consumer unsubscribed. */ }
                        catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
                    }
                }
            } finally { source.finish() }
        }
    }
    private fun selectionFlow(token: ReaderSessionToken): Flow<ReflowSelectionEnvelope> = flow {
        val owner = current(token) ?: return@flow
        val source = owner.selectionSource?.takeUnless { it.ended } ?: return@flow
        // The single native queue is bounded by the Surface. Rendezvous fanout adds no
        // second pending-event budget and never lets consumers compete for gate state.
        val channel = Channel<ReflowSelectionEnvelope>(Channel.RENDEZVOUS)
        val registered = synchronized(source.listeners) {
            if (source.ended || current(token) !== owner) false else { source.listeners += channel; true }
        }
        if (!registered) { channel.cancel(); return@flow }
        try { for (envelope in channel) emit(envelope) }
        finally { synchronized(source.listeners) { source.listeners.remove(channel) }; channel.cancel() }
    }
    private fun validateSelection(owner: Session, token: ReaderSessionToken, fragments: List<ReaderSelectionFragment>): LocatorValidation {
        if (token != owner.token || fragments.isEmpty()) return LocatorValidation(listOf("STALE_OR_UNSUPPORTED_SELECTION"))
        val result = owner.canonicalSource.validate(token, fragments)
        if (!result.isValid) return result
        if (owner.readingOrder.normalize(fragments) != fragments) return LocatorValidation(listOf("INVALID_TARGET_ORDER"))
        return LocatorValidation(if (fragments.all { fragment ->
            val locator = fragment.locator as? ReaderLocator.Reflow
            locator != null && owner.sourceLocators[locator.sectionId to locator.blockId] == locator.nativeLocator
        }) emptyList() else listOf("REFLOW_NATIVE_LOCATOR_MISMATCH"))
    }
    private suspend fun selectionAction(capture: ReflowSelectionCapture,
        action: suspend (ReflowSelectionSurfacePort) -> OperationResult<Unit>): OperationResult<Unit> {
        if (!ReflowSelectionValidation.capture(capture) || current(capture.binding.token) == null)
            return OperationResult.Rejected("STALE_SELECTION")
        return lock.withLock {
            val owner = current(capture.binding.token) ?: return@withLock OperationResult.Rejected("STALE_SELECTION")
            if (!authorized(owner)) { suspendSelectionSource(owner); return@withLock OperationResult.Rejected("REFLOW_ACCESS_CHANGED") }
            val source = owner.selectionSource?.takeUnless { it.ended } ?: return@withLock OperationResult.Rejected("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
            if (source.binding != capture.binding || !source.activated) return@withLock OperationResult.Rejected("STALE_SELECTION")
            val result = action(checkNotNull(nativeSelectionPort))
            if (result is OperationResult.Ok &&
                (current(capture.binding.token) !== owner || owner.selectionSource !== source || source.ended || !source.activated))
                OperationResult.Rejected("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
            else result
        }
    }
    private fun committedCopy(request: ReflowSelectionCopyRequest): ReflowSelectionCopyReceipt? =
        try { nativeSelectionPort?.committedCopyReceipt(request)?.takeIf { ReflowSelectionValidation.copyReceipt(request, it) } }
        catch (_: Exception) { null }

    private suspend fun copySelection(request: ReflowSelectionCopyRequest): OperationResult<ReflowSelectionCopyReceipt> {
        val capture = request.capture
        if (!ReflowSelectionValidation.copyRequest(request) || current(capture.binding.token) == null)
            return OperationResult.Rejected("STALE_SELECTION")
        return lock.withLock {
            val owner = current(capture.binding.token) ?: return@withLock OperationResult.Rejected("STALE_SELECTION")
            if (!authorized(owner)) { suspendSelectionSource(owner); return@withLock OperationResult.Rejected("REFLOW_ACCESS_CHANGED") }
            val source = owner.selectionSource?.takeUnless { it.ended }
                ?: return@withLock OperationResult.Rejected("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
            if (current(capture.binding.token) !== owner || source.binding != capture.binding || !source.activated ||
                !validateSelection(owner, capture.binding.token, listOf(request.fragment)).isValid)
                return@withLock OperationResult.Rejected("STALE_SELECTION")
            val result = try { checkNotNull(nativeSelectionPort).copy(request) }
            catch (cancelled: CancellationException) {
                val committed = committedCopy(request)
                if (committed != null) return@withLock OperationResult.Ok(committed, request.operationId)
                revokeSelectionSource(owner, capture.binding.token)
                throw cancelled
            } catch (_: Exception) {
                OperationResult.Failed("REFLOW_SELECTION_NATIVE_FAILED", false)
            }
            // This is a query of an actual committed fact, independent of later owner validity.
            committedCopy(request)?.let { return@withLock OperationResult.Ok(it, request.operationId) }
            if (current(capture.binding.token) !== owner || owner.selectionSource !== source || source.ended || !source.activated)
                return@withLock OperationResult.Rejected("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
            if (result is OperationResult.Ok) OperationResult.Rejected("REFLOW_SELECTION_COPY_NOT_COMMITTED") else result
        }
    }
    private fun validViewport(owner: Session, value: ReflowViewport) = owner.document.blocks.any { it.blockId == value.blockId }
    private fun publish(owner: Session, viewport: ReflowViewport): ReaderFeatureReport {
        owner.viewport = viewport
        owner.sequence = Math.addExact(owner.sequence,1)
        return publishReport(owner)
    }
    private fun report(owner: Session): ReaderFeatureReport {
        val position = locator(owner)
        require(ReaderLocatorValidator.validate(position).isValid) { "invalid production block anchor" }
        val selectable = owner.selectionSource?.let { !it.ended } == true
        return ReaderFeatureReport(owner.token,position,ReaderCapabilities(selectable,true,false,true,false,false),
            true,false,true,ReaderOcrState.NOT_REQUIRED,owner.sequence)
    }
    private fun locator(owner: Session): ReaderLocator.Reflow {
        owner.exactLocator?.let { return it.copy(progression = owner.viewport.progression) }
        val block = owner.document.blocks.first { it.blockId == owner.viewport.blockId }
        val end = block.text.offsetByCodePoints(0,minOf(64,block.text.codePointCount(0,block.text.length)))
        return ReaderLocator.Reflow(owner.token.documentId,block.sectionId,owner.token.contentRevision,owner.viewport.progression,
            block.nativeLocator,block.text.substring(0,end),null,null,block.canonicalStart,block.canonicalStart+end,block.blockId)
    }
    private fun validLocator(document: PreparedReflowDocument, value: ReaderLocator?): ReaderLocator.Reflow? {
        val locator = value as? ReaderLocator.Reflow ?: return null
        val target = document.resources.target
        if(!ReaderLocatorValidator.validateForDocument(locator,target.documentId,target.contentRevision).isValid) return null
        val block = document.blocks.find { it.blockId == locator.blockId && it.sectionId == locator.sectionId && it.nativeLocator == locator.nativeLocator } ?: return null
        val start = locator.canonicalStart-block.canonicalStart; val end=locator.canonicalEnd-block.canonicalStart
        if(start !in 0..block.text.length || end !in start..block.text.length) return null
        if(!ReaderLocatorValidator.isWellFormedUtf16(block.text.substring(start,end)) || block.text.substring(start,end) != locator.exactQuote) return null
        return locator
    }
    private fun validDocument(document: PreparedReflowDocument, context: AccessContext, target: ReaderTarget): Boolean {
        val snapshot = document.resources.snapshot
        if(document.resources.target != target || target.firstSectionId != target.lastSectionId || document.resources.pdfSource != null ||
            snapshot.documentId != target.documentId || snapshot.contentRevision != target.contentRevision ||
            (snapshot.ownerUserId != null && snapshot.ownerUserId != context.userId) || document.blocks.isEmpty()) return false
        if(document.blocks.map { it.blockId }.distinct().size != document.blocks.size) return false
        var offset = 0L
        return document.blocks.all { block ->
            val good = block.sectionId == target.firstSectionId && block.canonicalStart.toLong() == offset &&
                block.canonicalEnd.toLong() == offset+block.text.length && block.text.isNotBlank() &&
                ReaderLocatorValidator.isWellFormedUtf16(block.text) && block.extractionQuality == ExtractionQuality.VERIFIED_TEXT
            offset = block.canonicalEnd.toLong()+1
            good
        }
    }
    private fun OperationResult<*>.failure(): OperationResult<Nothing> = when(this) {
        is OperationResult.Ok -> error("expected failure")
        is OperationResult.Rejected -> this
        is OperationResult.Failed -> this
        is OperationResult.Conflict -> this
    }
}
