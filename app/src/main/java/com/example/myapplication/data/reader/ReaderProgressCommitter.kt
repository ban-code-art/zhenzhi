package com.example.myapplication.data.reader

import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.DocumentAccess
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Session-bound production progress sink. Historical readers never acquire a writer lease. */
class ReaderProgressCommitter private constructor(
    private val context: AccessContext,
    private val documentId: String,
    private val contentRevision: String,
    private val access: DocumentAccess,
    private val repository: ReaderDocumentRepository,
    private val identity: suspend () -> SessionIdentity,
    private val lease: WriterLease,
) {
    private val mutex = Mutex()
    private var closed = false
    private var sequence = 0L

    suspend fun commit(locator: ReaderLocator): OperationResult<Unit> = mutex.withLock {
        if (closed) return@withLock OperationResult.Rejected("PROGRESS_CLOSED")
        if (!matches(context, identity())) return@withLock OperationResult.Rejected("SESSION_CHANGED")
        if (locator.documentId != documentId || locator.contentRevision != contentRevision) {
            return@withLock OperationResult.Rejected("SOURCE_CHANGED")
        }
        val current = access.open(context, documentId, null)
        if (current !is OperationResult.Ok) return@withLock current.failure()
        if (current.value.documentId != documentId || current.value.contentRevision != contentRevision) {
            return@withLock OperationResult.Rejected("SOURCE_CHANGED")
        }
        val encoded = try { ReaderLocatorCodec.encode(locator) } catch (_: IllegalArgumentException) {
            return@withLock OperationResult.Rejected("INVALID_ARGUMENT")
        }
        if (!matches(context, identity())) return@withLock OperationResult.Rejected("SESSION_CHANGED")
        if (sequence == Long.MAX_VALUE) return@withLock OperationResult.Rejected("SEQUENCE_EXHAUSTED")
        repository.saveLocator(context.userId, documentId, lease, ++sequence, encoded)
    }

    suspend fun close() = mutex.withLock { closed = true }

    companion object {
        suspend fun open(
            context: AccessContext,
            documentId: String,
            contentRevision: String,
            access: DocumentAccess,
            repository: ReaderDocumentRepository,
            identity: suspend () -> SessionIdentity,
        ): OperationResult<ReaderProgressCommitter> {
            if (documentId.isBlank() || contentRevision.isBlank()) return OperationResult.Rejected("INVALID_ARGUMENT")
            if (!matches(context, identity())) return OperationResult.Rejected("SESSION_CHANGED")
            val current = access.open(context, documentId, null)
            if (current !is OperationResult.Ok) return current.failure()
            if (current.value.documentId != documentId) return OperationResult.Rejected("SOURCE_CHANGED")
            if (current.value.contentRevision != contentRevision) return OperationResult.Rejected("HISTORICAL_READ_ONLY")
            if (!matches(context, identity())) return OperationResult.Rejected("SESSION_CHANGED")
            val lease = repository.acquireWriter(context.userId, documentId, contentRevision)
            if (lease !is OperationResult.Ok) return lease.failure()
            return OperationResult.Ok(ReaderProgressCommitter(context, documentId, contentRevision,
                access, repository, identity, lease.value), context.requestId)
        }

        private fun matches(context: AccessContext, current: SessionIdentity): Boolean =
            current.userId == context.userId && current.epoch == context.sessionEpoch

        private fun OperationResult<*>.failure(): OperationResult<Nothing> = when (this) {
            is OperationResult.Rejected -> this
            is OperationResult.Failed -> this
            is OperationResult.Conflict -> this
            is OperationResult.Ok -> error("expected failure")
        }
    }
}
