package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.ReaderDocumentRepository
import com.example.myapplication.data.reader.ReaderProgressCommitter
import com.example.myapplication.data.reader.ReaderResourcePreparer
import com.example.myapplication.data.reader.storage.DocumentStorage
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit opt-in binding; creating this factory never changes the default reader. */
data class PdfReaderRuntime(val controller: ReaderRuntimeController, val readOnly: Boolean)

class PdfReaderRuntimeFactory(
    private val readingAccess: DocumentAccess,
    private val currentAccess: DocumentAccess,
    private val storage: DocumentStorage,
    private val repository: ReaderDocumentRepository,
    private val identity: suspend () -> SessionIdentity,
) {
    private val lock = Mutex()
    private var pendingCleanup: (suspend () -> Boolean)? = null
    private val mutableCleanupFailure = MutableStateFlow<String?>(null)
    val cleanupFailure = mutableCleanupFailure.asStateFlow()

    /** A destroyed screen transfers failed cleanup ownership to the application-lived factory. */
    suspend fun retainCleanup(controller: ReaderRuntimeController): Boolean = withContext(NonCancellable) {
        lock.withLock {
            val previous = pendingCleanup
            cleanup {
                val previousClosed = try { previous?.invoke() ?: true } catch (_: Exception) { false }
                val currentClosed = try { controller.close() } catch (_: Exception) { false }
                previousClosed && currentClosed
            }
        }
    }

    suspend fun retryCleanup(): Boolean = withContext(NonCancellable) {
        lock.withLock { cleanup(pendingCleanup ?: return@withLock true) }
    }

    private suspend fun cleanup(action: suspend () -> Boolean): Boolean {
        val ok = try { action() } catch (_: Exception) { false }
        pendingCleanup = if (ok) null else action
        mutableCleanupFailure.value = if (ok) null else "PDF_RUNTIME_CLEANUP_REQUIRED"
        return ok
    }

    suspend fun open(context: AccessContext, request: ReaderOpenRequest): OperationResult<PdfReaderRuntime> =
        lock.withLock { openLocked(context, request) }

    private suspend fun openLocked(context: AccessContext, request: ReaderOpenRequest): OperationResult<PdfReaderRuntime> {
        if (pendingCleanup != null) return OperationResult.Rejected("PDF_RUNTIME_CLEANUP_REQUIRED")
        val owner = identity()
        if (owner.userId != context.userId || owner.epoch != context.sessionEpoch) {
            return OperationResult.Rejected("SESSION_CHANGED")
        }
        val preparer = ReaderResourcePreparer(readingAccess, storage)
        val resources = preparer.prepare(context, request.target, fixedLayout = true)
        if (resources !is OperationResult.Ok) return resources.failure()
        val adapter = PdfReaderEngineAdapter(preparer, readingAccess)
        var report: ReaderFeatureReport? = null
        var committer: ReaderProgressCommitter? = null
        var controller: ReaderRuntimeController? = null
        var transferred = false
        try {
            when (val opened = adapter.open(context, request)) {
                is OperationResult.Ok -> report = opened.value
                else -> return opened.failure()
            }
            val writer = ReaderProgressCommitter.open(context, request.target.documentId,
                request.target.contentRevision, currentAccess, repository, identity)
            val readOnly = writer is OperationResult.Rejected && writer.code == "HISTORICAL_READ_ONLY"
            if (writer is OperationResult.Ok) committer = writer.value
            else if (!readOnly) return writer.failure()
            val sink = committer
            val runtime = ReaderRuntimeController(context,
                ActiveReaderSession(request.target, checkNotNull(report), adapter), resources.value.sequence,
                { PdfReaderEngineAdapter(preparer, readingAccess) },
                progressWriter = { _, locator -> sink?.commit(locator) ?: OperationResult.Ok(Unit, context.requestId) },
                progressClose = { sink?.close() },
                allowedSectionIds = resources.value.sections.map { it.id }.toSet())
            controller = runtime
            // A persistence failure remains observable on the usable rendered session.
            runtime.start()
            currentCoroutineContext().ensureActive()
            val confirmedOwner = identity()
            if (confirmedOwner.userId != context.userId || confirmedOwner.epoch != context.sessionEpoch) {
                return OperationResult.Rejected("SESSION_CHANGED")
            }
            transferred = true
            return OperationResult.Ok(PdfReaderRuntime(runtime, readOnly), context.requestId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return OperationResult.Failed("PDF_RUNTIME_OPEN_FAILED", true)
        } finally {
            if (!transferred) withContext(NonCancellable) {
                val runtime = controller
                cleanup {
                    if (runtime != null) runtime.close()
                    else {
                        try { report?.let { adapter.close(it.token.handle()) } }
                        finally { committer?.close() }
                        true
                    }
                }
            }
        }
    }

    private fun OperationResult<*>.failure(): OperationResult<Nothing> = when (this) {
        is OperationResult.Rejected -> this
        is OperationResult.Failed -> this
        is OperationResult.Conflict -> this
        is OperationResult.Ok -> error("expected failure")
    }
}
