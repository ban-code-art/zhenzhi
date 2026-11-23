package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit host binding; all failed native owners can outlive the screen through cleanupRegistry. */
class ReflowReaderRuntimeFactory(
    private val prepare: suspend (AccessContext, ReaderTarget) -> OperationResult<PreparedReflowDocument>,
    private val access: DocumentAccess,
    private val openProgress: suspend (AccessContext, ReaderTarget) -> OperationResult<ReflowProgressSink>,
    private val identity: suspend () -> SessionIdentity,
    private val host: ReflowRuntimeHost,
    private val cleanupRegistry: ReflowCleanupRegistry = ReflowCleanupRegistry(),
) {
    private val operation = Mutex()
    private val prefix = UUID.randomUUID().toString()
    private val contexts = mutableMapOf<ReaderRuntimeController, AccessContext>()
    val cleanupFailure = cleanupRegistry.cleanupFailure

    /** Explicit isolated course consumer; the loader must retain the caller's access/review policy. */
    suspend fun openCourse(
        context: AccessContext,
        request: ReaderOpenRequest,
        loadSequence: suspend () -> OperationResult<ReadingSequence>,
        preferences: ReaderLayoutPreferences? = null,
    ): OperationResult<ReflowReaderRuntime> {
        currentCoroutineContext().ensureActive()
        try {
            if (!matches(context)) return OperationResult.Rejected("SESSION_CHANGED")
            val loaded = loadSequence()
            currentCoroutineContext().ensureActive()
            if (loaded !is OperationResult.Ok) return loaded.failure()
            val sequence = loaded.value
            if (request.target.courseBookId == null || request.target.chapterKey == null ||
                request.target !in sequence.orderedTargets || sequence.orderedTargets.any {
                    it.courseBookId != request.target.courseBookId || it.chapterKey == null || it.firstSectionId != it.lastSectionId
                }) return OperationResult.Rejected("INVALID_COURSE_SEQUENCE")
            return openScoped(context, request, preferences, sequence) {
                val latest = loadSequence()
                currentCoroutineContext().ensureActive()
                latest is OperationResult.Ok && latest.value.version == sequence.version &&
                    latest.value.orderedTargets == sequence.orderedTargets
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return OperationResult.Failed("COURSE_RUNTIME_OPEN_FAILED", true)
        }
    }

    suspend fun open(context: AccessContext, request: ReaderOpenRequest, preferences: ReaderLayoutPreferences? = null): OperationResult<ReflowReaderRuntime> =
        openScoped(context, request, preferences)

    private suspend fun openScoped(context: AccessContext, request: ReaderOpenRequest,
        preferences: ReaderLayoutPreferences?, course: ReadingSequence? = null,
        validateCourse: suspend () -> Boolean = { true },
    ): OperationResult<ReflowReaderRuntime> = operation.withLock {
        if (cleanupFailure.value != null) return@withLock OperationResult.Rejected("REFLOW_RUNTIME_CLEANUP_REQUIRED")
        if (!matches(context)) return@withLock OperationResult.Rejected("SESSION_CHANGED")
        val baselines = mutableMapOf<Pair<String, String>, PreparedReflowDocument>()
        suspend fun scopedPrepare(ctx: AccessContext, target: ReaderTarget): OperationResult<PreparedReflowDocument> {
            if (!matches(ctx)) return OperationResult.Rejected("SESSION_CHANGED")
            if (course != null && (target !in course.orderedTargets || !validateCourse()))
                return OperationResult.Rejected("COURSE_SEQUENCE_CHANGED")
            val physical = if (course == null) target else target.copy(courseBookId = null, chapterKey = null)
            val result = prepare(ctx, physical)
            currentCoroutineContext().ensureActive()
            if (result !is OperationResult.Ok) return result
            if (result.value.resources.target != physical) return OperationResult.Rejected("INVALID_REFLOW_DOCUMENT")
            val key = target.documentId to target.contentRevision
            val baseline = baselines[key]
            if (baseline != null && (result.value.resources.snapshot != baseline.resources.snapshot ||
                    result.value.resources.sequence.version != baseline.resources.sequence.version ||
                    result.value.resources.sequence.orderedTargets != baseline.resources.sequence.orderedTargets))
                return OperationResult.Rejected("REFLOW_SEQUENCE_CHANGED")
            if (course != null && !validateCourse()) return OperationResult.Rejected("COURSE_SEQUENCE_CHANGED")
            if (!matches(ctx)) return OperationResult.Rejected("SESSION_CHANGED")
            baselines[key] = result.value
            return OperationResult.Ok(result.value.copy(resources = result.value.resources.copy(target = target)), ctx.requestId)
        }
        val resources = scopedPrepare(context, request.target)
        if (resources !is OperationResult.Ok) return@withLock resources.failure()
        var appliedPreferences = preferences
        fun candidate() = OwnedAdapter(context, ::scopedPrepare, validateCourse, { appliedPreferences }, { appliedPreferences = it })
        val initial = candidate()
        var report: ReaderFeatureReport? = null
        var sink: ReflowProgressSink? = null
        var controller: ReaderRuntimeController? = null
        val sinks = mutableMapOf<Pair<String, String>, ReflowProgressSink>()
        var transferred = false
        try {
            val opened = initial.open(context, request)
            if (opened !is OperationResult.Ok) return@withLock opened.failure()
            report = opened.value
            val ready = checkNotNull(report)
            if (!ready.renderable || ready.token.handle() != opened.value.token.handle() ||
                ready.eventSequence < 0 || !ReaderLocatorValidator.validateForDocument(
                    ready.currentLocator, request.target.documentId, request.target.contentRevision).isValid)
                return@withLock OperationResult.Rejected("INVALID_REFLOW_RECOVERY_REPORT")
            if (!matches(context)) return@withLock OperationResult.Rejected("SESSION_CHANGED")
            val progress = openProgress(context, request.target)
            val readOnly = progress is OperationResult.Rejected && progress.code == "HISTORICAL_READ_ONLY"
            if (progress is OperationResult.Ok) sink = progress.value
            else if (!readOnly) return@withLock progress.failure()
            currentCoroutineContext().ensureActive()
            if (!matches(context)) return@withLock OperationResult.Rejected("SESSION_CHANGED")
            if (course != null && !validateCourse()) return@withLock OperationResult.Rejected("COURSE_SEQUENCE_CHANGED")
            currentCoroutineContext().ensureActive()
            if (!matches(context)) return@withLock OperationResult.Rejected("SESSION_CHANGED")
            if (course != null && readOnly) return@withLock OperationResult.Rejected("COURSE_HISTORICAL_UNSUPPORTED")
            sink?.let { sinks[request.target.documentId to request.target.contentRevision] = it }
            val writer = sink
            val runtime = ReaderRuntimeController(context, ActiveReaderSession(request.target, ready, initial),
                course ?: resources.value.resources.sequence, { candidate() },
                { _, locator ->
                    if (!matches(context)) OperationResult.Rejected("SESSION_CHANGED")
                    else if (course == null) writer?.commit(locator) ?: OperationResult.Ok(Unit, context.requestId)
                    else if (!validateCourse()) OperationResult.Rejected("COURSE_SEQUENCE_CHANGED")
                    else {
                        val target = course.orderedTargets.singleOrNull { it.documentId == locator.documentId &&
                            it.contentRevision == locator.contentRevision && it.firstSectionId == locator.sectionId }
                        if (target == null) OperationResult.Rejected("INVALID_COURSE_PROGRESS")
                        else {
                            val key = target.documentId to target.contentRevision
                            val existing = sinks[key]
                            if (existing != null) existing.commit(locator)
                            else when (val acquired = openProgress(context, target)) {
                                is OperationResult.Ok -> {
                                    sinks[key] = acquired.value
                                    currentCoroutineContext().ensureActive()
                                    if (!matches(context) || !validateCourse()) OperationResult.Rejected("COURSE_SEQUENCE_CHANGED")
                                    else acquired.value.commit(locator)
                                }
                                else -> acquired.failure()
                            }
                        }
                    }
                }, {
                    var failure: Exception? = null
                    for ((key, owned) in sinks.toMap()) {
                        try { owned.close(); sinks.remove(key) } catch (error: Exception) { failure = error }
                    }
                    failure?.let { throw it }
                }, if (course == null) resources.value.resources.sections.map { it.id }.toSet()
                    else course.orderedTargets.map { it.firstSectionId }.toSet())
            controller = runtime
            runtime.start()
            currentCoroutineContext().ensureActive()
            if (!matches(context)) return@withLock OperationResult.Rejected("SESSION_CHANGED")
            synchronized(contexts) { contexts[runtime] = context }
            transferred = true
            OperationResult.Ok(ReflowReaderRuntime(runtime, readOnly), context.requestId)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { OperationResult.Failed("REFLOW_RUNTIME_OPEN_FAILED", true)
        } finally {
            if (!transferred) withContext(NonCancellable) {
                val runtime = controller
                cleanupRegistry.retain("$prefix:opening:${initial.key}") {
                    if (runtime != null) runtime.close()
                    else {
                        var clean = true
                        try {
                            val known = report
                            if (known != null) initial.close(known.token.handle())
                            else if (!initial.cleanupFailedOpen()) clean = false
                        }
                        catch (_: Exception) { clean = false }
                        try { sink?.close() } catch (_: Exception) { clean = false }
                        clean
                    }
                }
            }
        }
    }

    suspend fun revealActive(controller: ReaderRuntimeController): Boolean {
        val context = synchronized(contexts) { contexts[controller] } ?: return false
        val committed = controller.state.value.active
        val adapter = committed.adapter as? OwnedAdapter ?: return false
        val slot = adapter.lazySurface.slot ?: return false
        val token = committed.report.token
        val port = adapter.selectionPort
        fun stillCommitted(): Boolean {
            val active = controller.state.value.active
            return !controller.isClosed.value && synchronized(contexts) { contexts[controller] == context } &&
                active.adapter === adapter && active.report.token == token && adapter.lazySurface.slot === slot &&
                port.isActive(token)
        }
        suspend fun allowed(): Boolean = stillCommitted() && matches(context) &&
            adapter.selectionScopeFailure(token) == null && stillCommitted()
        try {
            if (!allowed()) { stopReveal(adapter, token); return false }
            host.showSurface(slot.id)
            // Show is suspendable: never activate a token whose committed owner changed in it.
            if (!allowed()) { stopReveal(adapter, token); return false }
            if (committed.report.capabilities.selectableText) port.activate(token)
            if (!allowed()) { stopReveal(adapter, token); return false }
            // A locally unavailable selection source does not prevent the reading view.
            return true
        } catch (cancelled: CancellationException) {
            stopReveal(adapter, token)
            throw cancelled
        } catch (_: Exception) {
            stopReveal(adapter, token)
            return false
        }
    }

    private suspend fun stopReveal(adapter: OwnedAdapter, token: ReaderSessionToken) = withContext(NonCancellable) {
        adapter.stopSelection(token)
        try { host.hideAll() } catch (_: Exception) { /* Native ownership remains retained. */ }
    }
    suspend fun retainCleanup(controller: ReaderRuntimeController): Boolean = withContext(NonCancellable) {
        val key = "$prefix:controller:${System.identityHashCode(controller)}"
        cleanupRegistry.retain(key) {
            val hidden = try { host.hideAll(); true } catch (_: Exception) { false }
            val closed = try { controller.close() } catch (_: Exception) { false }
            (hidden && closed).also { if (it) synchronized(contexts) { contexts.remove(controller) } }
        }
    }
    suspend fun retryCleanup(): Boolean = cleanupRegistry.retryCleanup()
    private suspend fun matches(context: AccessContext): Boolean {
        val current = identity()
        return current.userId == context.userId && current.epoch == context.sessionEpoch
    }

    private inner class LazySurface : ReflowReaderSurface {
        val selectionPort: ReflowSelectionSurfacePort = object : ReflowSelectionSurfacePort {
            override fun bookmarkState(binding: ReflowSelectionBinding): ReflowBookmarkNativeState? = slot?.selectionPort?.bookmarkState(binding)
            override suspend fun proveBookmark(expected: ReflowBookmarkNativeState): Boolean = slot?.selectionPort?.proveBookmark(expected) == true
            override suspend fun bind(token: ReaderSessionToken): ReflowSelectionBinding? = slot?.selectionPort?.bind(token)
            override fun events(binding: ReflowSelectionBinding): Flow<ReflowSelectionEnvelope> =
                slot?.selectionPort?.events(binding) ?: emptyFlow()
            override suspend fun activate(binding: ReflowSelectionBinding): OperationResult<Unit> =
                slot?.selectionPort?.activate(binding) ?: selectionUnavailable()
            override suspend fun setPresented(presented: Boolean) { slot?.selectionPort?.setPresented(presented) }
            override suspend fun suspendBinding(binding: ReflowSelectionBinding) { slot?.selectionPort?.suspendBinding(binding) }
            override fun revokeBinding(handle: ReaderSessionHandle) { slot?.selectionPort?.revokeBinding(handle) }
            override suspend fun confirmStable(capture: ReflowSelectionCapture): OperationResult<Unit> =
                slot?.selectionPort?.confirmStable(capture) ?: selectionUnavailable()
            override suspend fun copy(request: ReflowSelectionCopyRequest): OperationResult<ReflowSelectionCopyReceipt> =
                copySelection(request)
            override fun committedCopyReceipt(request: ReflowSelectionCopyRequest): ReflowSelectionCopyReceipt? {
                val native = slot?.selectionPort
                if (native != null) return native.committedCopyReceipt(request)
                    ?.takeIf { ReflowSelectionValidation.copyReceipt(request, it) }
                return lastCommittedCopy?.takeIf { it.first == request }?.second
            }
            override suspend fun temporaryFocusHandoff(capture: ReflowSelectionCapture): OperationResult<Unit> =
                slot?.selectionPort?.temporaryFocusHandoff(capture) ?: selectionUnavailable()
            override suspend fun compareAndClear(request: SelectionNativeClearRequest,
                capture: ReflowSelectionCapture): OperationResult<Unit> =
                slot?.selectionPort?.compareAndClear(request, capture) ?: selectionUnavailable()
        }
        @Volatile var slot: HostedReflowSurface? = null
        @Volatile var closeRequested = false
        @Volatile private var lastCommittedCopy: Pair<ReflowSelectionCopyRequest, ReflowSelectionCopyReceipt>? = null
        private suspend fun copySelection(request: ReflowSelectionCopyRequest): OperationResult<ReflowSelectionCopyReceipt> {
            val native = slot?.selectionPort ?: return OperationResult.Rejected("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
            try { return native.copy(request) }
            finally {
                native.committedCopyReceipt(request)?.takeIf { ReflowSelectionValidation.copyReceipt(request, it) }
                    ?.let { lastCommittedCopy = request to it }
            }
        }
        private fun selectionUnavailable(): OperationResult<Unit> =
            OperationResult.Rejected("REFLOW_SELECTION_SOURCE_UNAVAILABLE")
        suspend fun obtain(): ReflowReaderSurface {
            check(!closeRequested)
            if (slot == null) withContext(NonCancellable) { slot = host.createSurface() }
            currentCoroutineContext().ensureActive()
            return checkNotNull(slot).surface
        }
        override suspend fun open(document: PreparedReflowDocument, placement: ReaderPlacement, blockId: String?) = obtain().open(document, placement, blockId)
        override suspend fun turn(direction: ReaderTurnDirection) = obtain().turn(direction)
        override val viewportNeedsRefresh: Boolean get() = slot?.surface?.viewportNeedsRefresh == true
        override suspend fun refreshViewport(): ReflowViewport? = slot?.surface?.refreshViewport()
        override suspend fun decorate(items: List<com.example.myapplication.domain.reader.ReaderDecoration>): Boolean =
            slot?.surface?.decorate(items) == true
        override val supportsExactLocations: Boolean get() = slot?.surface?.supportsExactLocations == true
        override fun currentPosition(): ReaderLocator.Reflow? = slot?.surface?.currentPosition()
        override suspend fun locate(locator: ReaderLocator.Reflow) = obtain().locate(locator)
        override suspend fun locate(blockId: String) = obtain().locate(blockId)
        override suspend fun applyPreferences(preferences: ReaderLayoutPreferences, blockId: String) = obtain().applyPreferences(preferences, blockId)
        override suspend fun applyOpeningPreferences(preferences: ReaderLayoutPreferences, placement: ReaderPlacement, blockId: String) = obtain().applyOpeningPreferences(preferences, placement, blockId)
        override suspend fun close() = withContext(NonCancellable) {
            closeRequested = true
            slot?.let { owned -> owned.surface.close(); host.releaseSurface(owned.id); slot = null }
            Unit
        }
    }
    private inner class OwnedAdapter(private val owner: AccessContext,
        resolve: suspend (AccessContext, ReaderTarget) -> OperationResult<PreparedReflowDocument>,
        private val validateScope: suspend () -> Boolean,
        private val preferences: () -> ReaderLayoutPreferences?, private val rememberPreferences: (ReaderLayoutPreferences) -> Unit) : ReaderEngineAdapter, ReflowSelectionPortOwner {
        val key = UUID.randomUUID().toString()
        val lazySurface = LazySurface()
        private val delegate = ReflowReaderEngineAdapter(resolve, access, lazySurface, lazySurface.selectionPort)
        override val selectionPort: ReflowSelectionPort = object : ReflowSelectionPort {
            override fun bookmarkState(token: ReaderSessionToken): ReflowBookmarkNativeState? =
                if (isActive(token)) delegate.selectionPort.bookmarkState(token) else null
            override suspend fun proveBookmark(expected: ReflowBookmarkNativeState): Boolean {
                val token = expected.binding.token
                return try {
                    if (selectionScopeFailure(token) != null || bookmarkState(token) != expected) false
                    else delegate.selectionPort.proveBookmark(expected) &&
                        selectionScopeFailure(token) == null && bookmarkState(token) == expected
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            }
            override fun originalSnapshot(token: ReaderSessionToken): DocumentSnapshot? =
                if (isActive(token)) delegate.selectionPort.originalSnapshot(token) else null
            override fun readingOrder(token: ReaderSessionToken): BookmarkReadingOrder? =
                if (isActive(token)) delegate.selectionPort.readingOrder(token) else null
            override fun isActive(token: ReaderSessionToken): Boolean = token.userId == owner.userId &&
                token.sessionEpoch == owner.sessionEpoch && delegate.selectionPort.isActive(token)
            override fun validateSource(token: ReaderSessionToken, fragments: List<ReaderSelectionFragment>): LocatorValidation =
                if (isActive(token)) delegate.selectionPort.validateSource(token, fragments)
                else LocatorValidation(listOf("STALE_REFLOW_TOKEN"))
            override fun events(token: ReaderSessionToken): Flow<ReflowSelectionEnvelope> = flow {
                if (!isActive(token)) return@flow
                // Register with the actual accepted stream before any suspendable scope check.
                // Reveal/activation checks scope, and each callback is rechecked before emit.
                delegate.selectionPort.events(token)
                    .takeWhile { selectionScopeFailure(token) == null }
                    .collect { emit(it) }
            }
            override suspend fun activate(token: ReaderSessionToken): OperationResult<Unit> =
                selectionOperation(token) { delegate.selectionPort.activate(token) }
            override suspend fun confirmStable(capture: ReflowSelectionCapture): OperationResult<Unit> =
                selectionOperation(capture.binding.token) { delegate.selectionPort.confirmStable(capture) }
            override suspend fun copy(request: ReflowSelectionCopyRequest): OperationResult<ReflowSelectionCopyReceipt> =
                copySelection(request)
            override fun committedCopyReceipt(request: ReflowSelectionCopyRequest): ReflowSelectionCopyReceipt? =
                committedCopy(request)
            override suspend fun temporaryFocusHandoff(capture: ReflowSelectionCapture): OperationResult<Unit> =
                selectionOperation(capture.binding.token) { delegate.selectionPort.temporaryFocusHandoff(capture) }
            override suspend fun compareAndClear(request: SelectionNativeClearRequest,
                capture: ReflowSelectionCapture): OperationResult<Unit> {
                if (request.token != capture.binding.token || request.selectionId != capture.selectionId)
                    return OperationResult.Rejected("STALE_SELECTION")
                return selectionOperation(capture.binding.token) { delegate.selectionPort.compareAndClear(request, capture) }
            }
        }
        private val cleanupKey = "$prefix:adapter:$key"
        suspend fun stopSelection(token: ReaderSessionToken) = withContext(NonCancellable) {
            try { delegate.suspendSelection(token) } catch (_: Exception) { /* The Kotlin source was stopped first. */ }
        }
        suspend fun selectionScopeFailure(token: ReaderSessionToken): String? {
            try {
                val failure = when {
                    !selectionPort.isActive(token) -> "STALE_REFLOW_TOKEN"
                    !matches(owner) -> "SESSION_CHANGED"
                    !validateScope() -> "COURSE_SEQUENCE_CHANGED"
                    !matches(owner) -> "SESSION_CHANGED"
                    !selectionPort.isActive(token) -> "STALE_REFLOW_TOKEN"
                    else -> null
                }
                currentCoroutineContext().ensureActive()
                if (failure != null) stopSelection(token)
                return failure
            } catch (cancelled: CancellationException) {
                stopSelection(token)
                throw cancelled
            } catch (_: Exception) {
                stopSelection(token)
                return "REFLOW_SELECTION_SCOPE_FAILED"
            }
        }
        private suspend fun selectionOperation(token: ReaderSessionToken,
            action: suspend () -> OperationResult<Unit>): OperationResult<Unit> {
            try {
                selectionScopeFailure(token)?.let { return OperationResult.Rejected(it) }
                val result = action()
                selectionScopeFailure(token)?.let { return OperationResult.Rejected(it) }
                return result
            } catch (cancelled: CancellationException) {
                stopSelection(token)
                throw cancelled
            } catch (_: Exception) {
                stopSelection(token)
                return OperationResult.Failed("REFLOW_SELECTION_NATIVE_FAILED", false)
            } finally { withContext(NonCancellable) { trackPending() } }
        }
        private fun committedCopy(request: ReflowSelectionCopyRequest): ReflowSelectionCopyReceipt? =
            try { delegate.selectionPort.committedCopyReceipt(request)?.takeIf { ReflowSelectionValidation.copyReceipt(request, it) } }
            catch (_: Exception) { null }

        private suspend fun copySelection(request: ReflowSelectionCopyRequest): OperationResult<ReflowSelectionCopyReceipt> {
            val token = request.capture.binding.token
            try {
                if (!ReflowSelectionValidation.copyRequest(request)) return OperationResult.Rejected("INVALID_REFLOW_SELECTION_COPY_REQUEST")
                selectionScopeFailure(token)?.let { return OperationResult.Rejected(it) }
                val result = delegate.selectionPort.copy(request)
                val committed = committedCopy(request)
                if (committed != null) {
                    // Revoke an obsolete source, while preserving the write that already happened.
                    withContext(NonCancellable) { selectionScopeFailure(token) }
                    return OperationResult.Ok(committed, request.operationId)
                }
                selectionScopeFailure(token)?.let { return OperationResult.Rejected(it) }
                return if (result is OperationResult.Ok) OperationResult.Rejected("REFLOW_SELECTION_COPY_NOT_COMMITTED") else result
            } catch (cancelled: CancellationException) {
                val committed = committedCopy(request)
                stopSelection(token)
                if (committed != null) return OperationResult.Ok(committed, request.operationId)
                throw cancelled
            } catch (_: Exception) {
                val committed = committedCopy(request)
                stopSelection(token)
                return if (committed != null) OperationResult.Ok(committed, request.operationId)
                    else OperationResult.Failed("REFLOW_SELECTION_NATIVE_FAILED", false)
            } finally { withContext(NonCancellable) { trackPending() } }
        }
        private suspend fun trackPending() {
            if (lazySurface.closeRequested && lazySurface.slot != null) cleanupRegistry.retain(cleanupKey) { delegate.retryCleanup() }
            else if (lazySurface.closeRequested) cleanupRegistry.forget(cleanupKey)
        }
        suspend fun cleanupFailedOpen(): Boolean {
            val result = delegate.retryCleanup()
            if (!result) trackPending()
            return result
        }
        override suspend fun open(context: AccessContext, request: ReaderOpenRequest): OperationResult<ReaderFeatureReport> {
            try {
                if (cleanupFailure.value != null) return OperationResult.Rejected("REFLOW_RUNTIME_CLEANUP_REQUIRED")
                if (context != owner || !matches(owner)) return OperationResult.Rejected("SESSION_CHANGED")
                val opened = delegate.open(context, request)
                val wanted = preferences()
                val result = if (opened is OperationResult.Ok && wanted != null)
                    delegate.applyOpeningPreferences(opened.value.token, wanted, request) else opened
                if (opened is OperationResult.Ok && result !is OperationResult.Ok) {
                    // A rejected layout has not invalidated the successfully opened owner.
                    // No public report will escape, so this owner must close it here.
                    withContext(NonCancellable) {
                        try { delegate.close(opened.value.token.handle()) } catch (_: Exception) { /* trackPending retains it. */ }
                    }
                }
                if (result is OperationResult.Ok) {
                    var accepted = false
                    try {
                        if (!matches(owner)) return OperationResult.Rejected("SESSION_CHANGED")
                        if (!validateScope()) return OperationResult.Rejected("COURSE_SEQUENCE_CHANGED")
                        currentCoroutineContext().ensureActive()
                        if (!matches(owner)) return OperationResult.Rejected("SESSION_CHANGED")
                        accepted = true
                    } finally {
                        if (!accepted) withContext(NonCancellable) {
                            try { delegate.close(result.value.token.handle()) } catch (_: Exception) { /* retained below */ }
                        }
                    }
                }
                return result
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                return OperationResult.Failed("REFLOW_RUNTIME_OPEN_TIMEOUT", true)
            } finally { withContext(NonCancellable) { trackPending() } }
        }
        override suspend fun turn(token: ReaderSessionToken, direction: ReaderTurnDirection, origin: ReaderTurnOrigin): ReaderTurnResult {
            try { return if (matches(owner)) delegate.turn(token, direction, origin) else ReaderTurnResult.Failed("SESSION_CHANGED", false) }
            finally { withContext(NonCancellable) { trackPending() } }
        }
        override suspend fun navigate(token: ReaderSessionToken, locator: ReaderLocator): ReaderNavigationResult {
            try { return if (matches(owner)) delegate.navigate(token, locator) else ReaderNavigationResult.Unavailable("SESSION_CHANGED") }
            finally { withContext(NonCancellable) { trackPending() } }
        }
        override fun selectionEvents(token: ReaderSessionToken) = selectionPort.events(token).mapNotNull { it.event }
        override fun featureEvents(token: ReaderSessionToken) = delegate.featureEvents(token)
        override suspend fun clearSelection(token: ReaderSessionToken) =
            selectionOperation(token) { delegate.clearSelection(token) }
        override suspend fun decorate(token: ReaderSessionToken, items: List<ReaderDecoration>) = delegate.decorate(token, items)
        override suspend fun applyPreferences(token: ReaderSessionToken, preferences: ReaderLayoutPreferences): OperationResult<ReaderFeatureReport> {
            try {
                val result = if (matches(owner)) delegate.applyPreferences(token, preferences) else OperationResult.Rejected("SESSION_CHANGED")
                if (result is OperationResult.Ok) rememberPreferences(preferences)
                return result
            }
            finally { withContext(NonCancellable) { trackPending() } }
        }
        override suspend fun close(handle: ReaderSessionHandle) {
            try { delegate.close(handle) } finally { withContext(NonCancellable) { trackPending() } }
        }
    }
    private fun OperationResult<*>.failure(): OperationResult<Nothing> = when (this) {
        is OperationResult.Ok -> error("expected failure")
        is OperationResult.Rejected -> this
        is OperationResult.Failed -> this
        is OperationResult.Conflict -> this
    }
}
