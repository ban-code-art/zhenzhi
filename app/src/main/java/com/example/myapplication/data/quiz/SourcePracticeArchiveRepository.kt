package com.example.myapplication.data.quiz

import com.example.myapplication.data.session.SessionIdentity
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PortablePracticeExport(val recordToPortable: Map<String, String>, val entries: Map<String, ByteArray>)
data class PortablePracticeImport(val recordIds: Map<String, String>, val unavailable: Set<String>)

/** Private learning records. Every disk path is user-scoped, every mutation checks the live epoch. */
class SourcePracticeArchiveRepository(
    filesDir: File,
    val identityFlow: Flow<SessionIdentity>,
    private val onQuizActivity: suspend (SessionIdentity, String) -> Unit = { _, _ -> },
) : Closeable {
    private val store = SourcePracticeArchiveStore(filesDir)
    private val mutex = Mutex()
    private val changes = MutableStateFlow(0L)
    private var loadedIdentity: SessionIdentity? = null
    private val cache = linkedMapOf<String, SourcePracticeRecord>()
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class PendingSave(val identity: SessionIdentity, val recordId: String,
        val progress: SourcePracticeProgress, val result: CompletableDeferred<SourcePracticeRecord>)
    private val saves = Channel<PendingSave>(Channel.UNLIMITED, onUndeliveredElement = { save ->
        save.result.completeExceptionally(SourcePracticeArchiveException("随堂记录保存已停止，请重新打开应用。"))
    })

    init {
        writerScope.launch {
            try {
                for (save in saves) {
                    try { save.result.complete(saveProgress(save.identity, save.recordId, save.progress)) }
                    catch (error: Throwable) {
                        save.result.completeExceptionally(error)
                        if (error is CancellationException || error is Error) throw error
                    }
                }
            } finally { saves.cancel() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRecords(): Flow<List<SourcePracticeRecord>> = identityFlow.distinctUntilChanged().flatMapLatest { identity ->
        flow {
            emit(emptyList())
            if (identity.userId != null && identity.userId > 0 && identity.epoch >= 0) {
                emitAll(changes.mapLatest {
                    try { access(identity) { cache.values.sortedWith(compareByDescending<SourcePracticeRecord> { it.updatedAt }.thenBy { it.id }) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: SourcePracticeArchiveException) { emptyList() }
                }.distinctUntilChanged())
            }
        }
    }

    suspend fun listForReview(identity: SessionIdentity): List<SourcePracticeRecord> = access(identity) {
        cache.values.sortedWith(compareByDescending<SourcePracticeRecord> { it.updatedAt }.thenBy { it.id })
    }

    suspend fun exportPortable(identity: SessionIdentity, recordIds: Set<String>): PortablePracticeExport = access(identity) {
        require(recordIds.size <= MAX_PORTABLE_RECORDS)
        val references = linkedMapOf<String, String>()
        val entries = linkedMapOf<String, ByteArray>()
        for (id in recordIds.sorted()) {
            val record = cache[id] ?: throw SourcePracticeArchiveException("所选随堂记录已不可用。")
            val (portableId, bytes) = SourcePracticeArchiveCodec.portable(record)
            references[id] = portableId
            entries[portableId] = bytes
        }
        PortablePracticeExport(references, entries)
    }

    /** Existing attempts are kept. The source map must come from the actual applied V2 restore. */
    suspend fun importPortable(
        identity: SessionIdentity,
        entries: Map<String, ByteArray>,
        rebind: suspend (SourcePracticeDocumentBinding) -> SourcePracticeDocumentBinding?,
        mapSection: (String) -> String?,
    ): PortablePracticeImport {
        require(entries.size <= MAX_PORTABLE_RECORDS)
        val portableRecords = entries.toSortedMap().mapValues { (id, payload) ->
            SourcePracticeArchiveCodec.readPortable(id, payload)
        }
        val owner = checkNotNull(identity.userId)
        authorize(identity)
        // Resolve Room bindings before holding the archive mutex. Review reads take the Room lock first.
        val resolved = portableRecords.mapValues { (_, source) -> rebound(source, owner, rebind, mapSection) }
        return access(identity) {
            val mapped = linkedMapOf<String, String>()
            val unavailable = linkedSetOf<String>()
            for ((portableId, rebound) in resolved) {
                if (rebound == null) {
                    unavailable += portableId
                    continue
                }
                authorize(identity)
                val existing = cache[rebound.id] ?: store.read(owner, rebound.id)
                if (existing == null) {
                    SourcePracticeArchiveCodec.validate(rebound)
                    store.write(rebound)
                    cache[rebound.id] = rebound
                    changed()
                } else {
                    cache[existing.id] = existing
                }
                mapped[portableId] = rebound.id
            }
            PortablePracticeImport(mapped, unavailable)
        }
    }

    private suspend fun rebound(
        record: SourcePracticeRecord,
        owner: Long,
        rebind: suspend (SourcePracticeDocumentBinding) -> SourcePracticeDocumentBinding?,
        mapSection: (String) -> String?,
    ): SourcePracticeRecord? {
        val scope = record.scope
        val primary = scope.documentId?.let { oldId ->
            rebind(SourcePracticeDocumentBinding(oldId, scope.sourceRevision, scope.sectionIds))
                ?: return null
        }
        val sources = scope.relatedSources.map { source -> rebind(source) ?: return null }
        val questions = record.practice.questions.map { question ->
            question.copy(sectionIds = question.sectionIds.map { mapSection(it) ?: return null })
        }
        val practice = record.practice.copy(questions = questions)
        val bound = scope.copy(
            documentId = primary?.documentId,
            sectionIds = primary?.sectionIds ?: scope.sectionIds.map { mapSection(it) ?: return null },
            documentOwnerUserId = primary?.ownerUserId,
            accessEpoch = primary?.accessEpoch,
            metadataRevision = primary?.metadataRevision,
            extractorVersion = primary?.extractorVersion ?: scope.extractorVersion,
            relatedSources = sources,
        ).normalized(practice)
        val id = recordId(owner, practice, bound, record.batchNumber)
        return record.copy(id = id, ownerUserId = owner, practice = practice, scope = bound)
    }

    suspend fun load(identity: SessionIdentity, recordId: String): SourcePracticeRecord? = access(identity) {
        if (!recordId.matches(Regex("[a-f0-9]{64}"))) throw SourcePracticeArchiveException("随堂记录编号无效。")
        cache[recordId] ?: store.read(checkNotNull(identity.userId), recordId)?.also { cache[recordId] = it }
    }

    suspend fun ensure(identity: SessionIdentity, practice: SourcePractice, scope: SourcePracticeScope, batchNumber: Int = 1): SourcePracticeRecord {
        return access(identity) {
            val canonical = scope.normalized(practice)
            if ((canonical.documentOwnerUserId != null && canonical.documentOwnerUserId != identity.userId) ||
                canonical.relatedSources.any { it.ownerUserId != null && it.ownerUserId != identity.userId })
                throw SourcePracticeArchiveException("当前用户无法保存这份练习来源。")
            val userId = checkNotNull(identity.userId)
            val id = recordId(userId, practice, canonical, batchNumber)
            cache[id]?.let {
                if (it.practice != practice || it.scope != canonical || it.batchNumber != batchNumber)
                    throw SourcePracticeArchiveException("这组练习内容已变化，请重新生成。")
                return@access it
            }
            val now = System.currentTimeMillis().coerceAtLeast(1L)
            val record = SourcePracticeRecord(id, userId, practice, canonical,
                SourcePracticeProgress().normalized(practice), createdAt = now, updatedAt = now, batchNumber = batchNumber)
            store.write(record)
            authorize(identity)
            cache[id] = record
            changed()
            record
        }
    }

    suspend fun saveProgress(identity: SessionIdentity, recordId: String, progress: SourcePracticeProgress): SourcePracticeRecord {
        var activity = false
        val saved = access(identity) {
            val record = cache[recordId] ?: throw SourcePracticeArchiveException("这份随堂记录已不可用，请重新打开练习。")
            val normalized = progress.normalized(record.practice)
            if (normalized == record.progress) return@access record
            activity = normalized.submitted.indices.any { index -> normalized.submitted[index] &&
                (!record.progress.submitted[index] || normalized.responses[index] != record.progress.responses[index]) }
            val next = record.copy(progress = normalized, updatedAt = nextTime(record.updatedAt))
            store.write(next)
            authorize(identity)
            cache[recordId] = next
            changed()
            next
        }
        if (activity) {
            authorize(identity)
            try { onQuizActivity(identity, saved.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Logger.getLogger("SourcePracticeArchive").warning("Quiz activity callback failed: ${error.javaClass.simpleName}") }
        }
        return saved
    }

    suspend fun toggleFavorite(identity: SessionIdentity, recordId: String, questionId: String): SourcePracticeRecord = access(identity) {
        val record = cache[recordId] ?: throw SourcePracticeArchiveException("这份随堂记录已不可用，请重新打开练习。")
        if (record.practice.questions.none { it.id == questionId }) throw SourcePracticeArchiveException("这道题不在当前随堂记录中。")
        val favorites = if (questionId in record.favoriteQuestionIds) record.favoriteQuestionIds - questionId else record.favoriteQuestionIds + questionId
        val next = record.copy(favoriteQuestionIds = favorites, updatedAt = nextTime(record.updatedAt))
        store.write(next)
        authorize(identity)
        cache[recordId] = next
        changed()
        next
    }

    /** FIFO writer survives a composable leaving; the returned completion allows Back to flush. */
    internal fun enqueueProgress(identity: SessionIdentity, recordId: String, progress: SourcePracticeProgress): Deferred<SourcePracticeRecord> {
        val result = CompletableDeferred<SourcePracticeRecord>()
        if (!saves.trySend(PendingSave(identity, recordId, progress, result)).isSuccess)
            result.completeExceptionally(SourcePracticeArchiveException("随堂记录保存已停止，请重新打开应用。"))
        return result
    }

    private suspend fun <T> access(identity: SessionIdentity, operation: suspend () -> T): T = withContext(Dispatchers.IO) {
        authorize(identity)
        mutex.withLock {
            authorize(identity)
            if (loadedIdentity != identity) {
                cache.clear()
                loadedIdentity = null
                val records = store.readAll(checkNotNull(identity.userId))
                authorize(identity)
                records.forEach { cache[it.id] = it }
                loadedIdentity = identity
            }
            val result = operation()
            authorize(identity)
            result
        }
    }

    private suspend fun authorize(identity: SessionIdentity) {
        currentCoroutineContext().ensureActive()
        if (identity.userId == null || identity.userId <= 0 || identity.epoch < 0 || identityFlow.first() != identity)
            throw SourcePracticeArchiveException("登录状态已变化，请重新打开当前用户的练习。")
    }

    private fun recordId(userId: Long, practice: SourcePractice, scope: SourcePracticeScope, batchNumber: Int): String {
        val value = "$userId\n$batchNumber\n${SourcePracticeArchiveCodec.practice(practice)}\n${SourcePracticeArchiveCodec.scope(scope)}"
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun nextTime(previous: Long) = maxOf(System.currentTimeMillis(), Math.addExact(previous, 1L))
    private fun changed() { changes.value = changes.value + 1L }
    companion object {
        private const val MAX_PORTABLE_RECORDS = 128

        fun validatePortablePractice(id: String, bytes: ByteArray): SourcePracticeScope =
            SourcePracticeArchiveCodec.readPortable(id, bytes).scope
    }

    override fun close() { saves.cancel(); writerScope.cancel() }
}
