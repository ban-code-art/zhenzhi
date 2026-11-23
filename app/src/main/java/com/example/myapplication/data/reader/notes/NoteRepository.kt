package com.example.myapplication.data.reader.notes

import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.ReaderDraftEntity
import com.example.myapplication.data.local.entity.ReaderMutationReceiptEntity
import com.example.myapplication.data.local.entity.ReaderNotebookEntity
import com.example.myapplication.data.local.entity.ReaderNoteVersionEntity
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderAccessDecision
import com.example.myapplication.domain.reader.ReaderAccessPolicy
import com.example.myapplication.domain.reader.ReaderDocumentAccessRecord
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class NoteRepository(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val notebooks get() = database.readerNotebookDao()
    private val catalog get() = database.readerCatalogDao()

    suspend fun load(context: AccessContext, documentId: String): ReaderNoteLoad? {
        if (!isReadable(context, documentId)) return null
        val notebook = notebooks.findNotebookForDocument(context.userId, documentId)
            ?: return ReaderNoteLoad(documentId, null, null, null)
        val drafts = notebooks.listDrafts(context.userId, notebook.id)
        val version = notebooks.listVersions(notebook.id, 1).firstOrNull()
        return ReaderNoteLoad(
            documentId = documentId,
            notebook = ReaderNoteSnapshot(
                notebookId = notebook.id,
                documentId = notebook.documentId,
                body = notebook.bodyMarkdown,
                revision = notebook.revision,
                updatedAt = notebook.updatedAt,
            ),
            draft = drafts.firstOrNull()?.let(::toDraft),
            lastActor = version?.actor,
        )
    }

    /**
     * 查询某编辑器在该笔记上最近一次已提交保存的 savedSequence（跨会话恢复序列用）。
     *
     * UI 的 editorSequence 是内存计数器，重新进入会话时会从 0 重新自增；若同一 editorId 之前
     * 已用更大序列保存过（例如从草稿恢复出了旧编辑器），旧 receipt 会把后续保存误判为乱序写。
     * 调用方据此把内存计数器抬到持久化值（只抬不降）。
     *
     * @return 无记录、无权限或 receipt 不匹配时返回 0。
     */
    suspend fun latestSavedSequence(
        context: AccessContext,
        notebookId: String,
        editorId: String,
    ): Long {
        if (editorId.isBlank() || editorId.length > 128) return 0L
        val notebook = notebooks.findNotebook(context.userId, notebookId) ?: return 0L
        if (!isReadable(context, notebook.documentId)) return 0L
        val receipt = notebooks.findLatestNoteReceiptForEditor(
            userId = context.userId,
            notebookId = notebookId,
            editorPattern = "%\"${NoteSequenceGuard.KEY_SAVED_EDITOR_ID}\":\"$editorId\"%",
        ) ?: return 0L
        // LIKE 只是粗筛（大小写、通配符都可能放宽），这里再精确校验编辑器身份，避免种错序列。
        if (NoteSequenceGuard.parseSavedEditorId(receipt.resultJson) != editorId) return 0L
        return NoteSequenceGuard.parseSavedSequence(receipt.resultJson) ?: 0L
    }

    suspend fun save(
        context: AccessContext,
        documentId: String,
        notebookId: String?,
        baseRevision: Long,
        editorId: String,
        editorSequence: Long,
        body: String,
        actor: String,
    ): NoteSaveOutcome {
        if (editorId.isBlank() || editorId.length > 128 || editorSequence < 0) {
            return NoteSaveOutcome.Rejected("INVALID_ARGUMENT")
        }
        if (body.length > MAX_BODY_CHARS) return NoteSaveOutcome.Rejected("INVALID_ARGUMENT")
        if (actor.isBlank() || actor.length > 128) return NoteSaveOutcome.Rejected("INVALID_ARGUMENT")
        return database.withTransaction {
            if (!isReadable(context, documentId)) {
                return@withTransaction NoteSaveOutcome.Rejected("NOT_FOUND")
            }
            val targetId = notebookId ?: deterministicNotebookId(context, documentId, context.requestId)
            val bodyHash = ReaderMutationHasher.sha256(body)
            val payload = buildString {
                append("note|").append(documentId).append('|').append(editorId).append('|')
                append(editorSequence).append('|').append(actor).append('|').append(bodyHash)
            }
            val requestHash = ReaderMutationHasher.requestHash("NOTE", targetId, baseRevision, payload)
            val existingReceipt = notebooks.findReceipt(context.userId, context.requestId)
            if (existingReceipt != null) {
                if (existingReceipt.requestHash != requestHash) {
                    return@withTransaction NoteSaveOutcome.Rejected("IDEMPOTENCY_CONFLICT")
                }
                return@withTransaction receiptOutcome(existingReceipt)
            }
            val notebook = resolveNotebook(context, documentId, notebookId, targetId, body, allowCreate = true)
                ?: return@withTransaction NoteSaveOutcome.Rejected("NOT_FOUND")
            if (notebook.documentId != documentId) {
                return@withTransaction NoteSaveOutcome.Rejected("NOT_FOUND")
            }
            val latestReceipt = notebooks.findLatestReceipt(context.userId, "NOTE", notebook.id)
                ?: notebooks.findLatestReceipt(context.userId, "NOTE", targetId)
            // 同编辑器乱序写才拒绝；跨编辑器（如 agent 用时间戳序列）或旧 receipt 无编辑器身份时，
            // 不触发该守卫，交给下方 revision CAS 判定冲突。
            val staleSequence = NoteSequenceGuard.isStaleSequence(
                latestReceiptJson = latestReceipt?.resultJson,
                editorId = editorId,
                editorSequence = editorSequence,
                notebookBody = notebook.bodyMarkdown,
                requestedBodyHash = bodyHash,
            )
            if (staleSequence) {
                return@withTransaction NoteSaveOutcome.Rejected("STALE_SEQUENCE")
            }
            if (notebook.revision != baseRevision) {
                if (notebook.bodyMarkdown == body) {
                    return@withTransaction completeUnchangedSave(
                        context, notebook, editorId, editorSequence, bodyHash, requestHash, targetId,
                    )
                }
                val draft = notebooks.findDraft(context.userId, notebook.id, editorId)
                return@withTransaction NoteSaveOutcome.Conflict(
                    currentRevision = notebook.revision,
                    currentBody = notebook.bodyMarkdown,
                    draftBody = draft?.bodyMarkdown,
                )
            }
            if (notebook.bodyMarkdown == body) {
                return@withTransaction completeUnchangedSave(
                    context, notebook, editorId, editorSequence, bodyHash, requestHash, targetId,
                )
            }
            val newRevision = notebook.revision + 1
            val now = clock()
            val changed = notebooks.compareAndSetBody(
                userId = context.userId,
                notebookId = notebook.id,
                baseRevision = baseRevision,
                body = body,
                now = now,
            )
            if (changed != 1) {
                val current = notebooks.findNotebook(context.userId, notebook.id)
                return@withTransaction NoteSaveOutcome.Conflict(
                    currentRevision = current?.revision ?: baseRevision,
                    currentBody = current?.bodyMarkdown.orEmpty(),
                    draftBody = notebooks.findDraft(context.userId, notebook.id, editorId)?.bodyMarkdown,
                )
            }
            notebooks.insertVersion(
                ReaderNoteVersionEntity(
                    id = "version-${UUID.randomUUID()}",
                    notebookId = notebook.id,
                    revision = newRevision,
                    operationId = context.requestId,
                    bodyMarkdown = body,
                    actor = actor,
                    createdAt = now,
                ),
            )
            notebooks.insertReceipt(
                ReaderMutationReceiptEntity(
                    userId = context.userId,
                    operationId = context.requestId,
                    targetKind = "NOTE",
                    targetId = notebook.id,
                    requestHash = requestHash,
                    beforeRevision = baseRevision,
                    afterRevision = newRevision,
                    resultJson = NoteSequenceGuard.receiptJson(
                        notebookId = notebook.id,
                        revision = newRevision,
                        bodyHash = bodyHash,
                        savedSequence = editorSequence,
                        savedEditorId = editorId,
                    ),
                    createdAt = now,
                ),
            )
            notebooks.deleteDraftThrough(context.userId, notebook.id, editorId, editorSequence)
            notebooks.trimVersions(notebook.id, KEEP_VERSIONS)
            NoteSaveOutcome.Saved(
                ReaderNoteSaveAck(notebook.id, newRevision, bodyHash, editorSequence),
            )
        }
    }

    suspend fun flushDraft(
        context: AccessContext,
        documentId: String,
        notebookId: String?,
        editorId: String,
        editorSequence: Long,
        baseRevision: Long,
        body: String,
    ): OperationResult<ReaderNoteDraftSnapshot> {
        if (editorId.isBlank() || editorId.length > 128 || editorSequence < 0 || body.length > MAX_BODY_CHARS) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        return database.withTransaction {
            if (!isReadable(context, documentId)) {
                return@withTransaction OperationResult.Rejected("NOT_FOUND")
            }
            val targetId = notebookId ?: deterministicNotebookId(context, documentId, context.requestId)
            val notebook = resolveNotebook(context, documentId, notebookId, targetId, body, allowCreate = true)
                ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
            val now = clock()
            val draft = ReaderDraftEntity(
                userId = context.userId,
                notebookId = notebook.id,
                editorId = editorId,
                editorSequence = editorSequence,
                baseRevision = baseRevision,
                bodyMarkdown = body,
                updatedAt = now,
            )
            notebooks.insertDraftIfAbsent(draft)
            notebooks.updateDraftIfNewer(
                userId = context.userId,
                notebookId = notebook.id,
                editorId = editorId,
                editorSequence = editorSequence,
                baseRevision = baseRevision,
                body = body,
                now = now,
            )
            val saved = notebooks.findDraft(context.userId, notebook.id, editorId) ?: draft
            OperationResult.Ok(
                toDraft(saved),
                context.requestId,
            )
        }
    }

    suspend fun deleteDraft(
        context: AccessContext,
        notebookId: String,
        editorId: String,
    ): OperationResult<Unit> {
        val notebook = notebooks.findNotebook(context.userId, notebookId)
            ?: return OperationResult.Rejected("NOT_FOUND")
        if (!isReadable(context, notebook.documentId)) {
            return OperationResult.Rejected("NOT_FOUND")
        }
        notebooks.deleteDraft(context.userId, notebookId, editorId)
        return OperationResult.Ok(Unit, context.requestId)
    }

    suspend fun listVersions(
        context: AccessContext,
        notebookId: String,
        limit: Int = KEEP_VERSIONS,
    ): List<ReaderNoteVersionEntity> {
        val notebook = notebooks.findNotebook(context.userId, notebookId) ?: return emptyList()
        if (!isReadable(context, notebook.documentId)) return emptyList()
        return notebooks.listVersions(notebookId, limit.coerceIn(1, KEEP_VERSIONS))
    }

    suspend fun restoreVersion(
        context: AccessContext,
        notebookId: String,
        versionRevision: Long,
        baseRevision: Long,
        editorSequence: Long,
        editorId: String = "restore",
        actor: String = "USER",
    ): NoteSaveOutcome {
        val notebook = notebooks.findNotebook(context.userId, notebookId)
            ?: return NoteSaveOutcome.Rejected("NOT_FOUND")
        if (!isReadable(context, notebook.documentId)) return NoteSaveOutcome.Rejected("NOT_FOUND")
        val version = notebooks.findVersion(notebookId, versionRevision)
            ?: return NoteSaveOutcome.Rejected("VERSION_NOT_FOUND")
        return save(
            context = context.copy(requestId = "restore-${UUID.randomUUID()}"),
            documentId = notebook.documentId,
            notebookId = notebookId,
            baseRevision = baseRevision,
            editorId = editorId,
            editorSequence = editorSequence,
            body = version.bodyMarkdown,
            actor = actor,
        )
    }

    private suspend fun resolveNotebook(
        context: AccessContext,
        documentId: String,
        requestedId: String?,
        fallbackId: String,
        body: String,
        allowCreate: Boolean,
    ): ReaderNotebookEntity? {
        if (requestedId != null) {
            return notebooks.findNotebook(context.userId, requestedId)
        }
        notebooks.findNotebookForDocument(context.userId, documentId)?.let { return it }
        if (!allowCreate || body.isBlank()) return null
        val now = clock()
        val notebook = ReaderNotebookEntity(
            id = fallbackId,
            userId = context.userId,
            documentId = documentId,
            bodyMarkdown = "",
            revision = 0,
            createdAt = now,
            updatedAt = now,
        )
        return runCatching {
            notebooks.insertNotebook(notebook)
            notebook
        }.getOrElse {
            notebooks.findNotebookForDocument(context.userId, documentId)
        }
    }

    private suspend fun completeUnchangedSave(
        context: AccessContext,
        notebook: ReaderNotebookEntity,
        editorId: String,
        editorSequence: Long,
        bodyHash: String,
        requestHash: String,
        targetId: String,
    ): NoteSaveOutcome {
        val now = clock()
        notebooks.insertReceipt(
            ReaderMutationReceiptEntity(
                userId = context.userId,
                operationId = context.requestId,
                targetKind = "NOTE",
                targetId = notebook.id,
                requestHash = requestHash,
                beforeRevision = notebook.revision,
                afterRevision = notebook.revision,
                resultJson = NoteSequenceGuard.receiptJson(
                    notebookId = notebook.id,
                    revision = notebook.revision,
                    bodyHash = bodyHash,
                    savedSequence = editorSequence,
                    savedEditorId = editorId,
                ),
                createdAt = now,
            ),
        )
        notebooks.deleteDraftThrough(context.userId, notebook.id, editorId, editorSequence)
        return NoteSaveOutcome.Saved(
            ReaderNoteSaveAck(notebook.id, notebook.revision, bodyHash, editorSequence),
        )
    }

    private fun receiptOutcome(receipt: ReaderMutationReceiptEntity): NoteSaveOutcome {
        val root = runCatching { Json.parseToJsonElement(receipt.resultJson).jsonObject }.getOrNull()
            ?: return NoteSaveOutcome.Failed("RECEIPT_CORRUPT", retryable = false)
        val ack = ReaderNoteSaveAck(
            notebookId = root["notebookId"]?.let { (it as? JsonPrimitive)?.content } ?: receipt.targetId,
            revision = root["revision"]?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() }
                ?: receipt.afterRevision,
            bodyHash = root["bodyHash"]?.let { (it as? JsonPrimitive)?.content }.orEmpty(),
            savedSequence = root["savedSequence"]?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() }
                ?: 0,
        )
        return NoteSaveOutcome.Saved(ack)
    }

    private fun toDraft(entity: ReaderDraftEntity): ReaderNoteDraftSnapshot =
        ReaderNoteDraftSnapshot(
            notebookId = entity.notebookId,
            editorId = entity.editorId,
            editorSequence = entity.editorSequence,
            baseRevision = entity.baseRevision,
            body = entity.bodyMarkdown,
            updatedAt = entity.updatedAt,
        )

    private suspend fun isReadable(context: AccessContext, documentId: String): Boolean {
        val document = catalog.findDocument(documentId) ?: return false
        val decision = ReaderAccessPolicy.canRead(
            context.userId,
            ReaderDocumentAccessRecord(
                documentId = document.id,
                kind = document.kind,
                ownerUserId = document.ownerUserId,
                contentRevision = document.contentRevision,
                accessEpoch = document.accessEpoch,
                importStatus = document.importStatus,
                deletedAt = document.deletedAt,
            ),
        )
        return decision is ReaderAccessDecision.Allowed
    }

    private fun isReadableUserId(userId: Long, document: com.example.myapplication.data.local.entity.ReaderDocumentEntity?): Boolean {
        if (document == null) return false
        return ReaderAccessPolicy.canRead(
            userId,
            ReaderDocumentAccessRecord(
                documentId = document.id,
                kind = document.kind,
                ownerUserId = document.ownerUserId,
                contentRevision = document.contentRevision,
                accessEpoch = document.accessEpoch,
                importStatus = document.importStatus,
                deletedAt = document.deletedAt,
            ),
        ) is ReaderAccessDecision.Allowed
    }


    private fun deterministicNotebookId(context: AccessContext, documentId: String, requestId: String): String =
        "notebook-${ReaderMutationHasher.sha256("${context.userId}|$documentId|$requestId").take(32)}"

    companion object {
        const val KEEP_VERSIONS = 50
        private const val MAX_BODY_CHARS = 8_000_000
    }
}

/**
 * NOTE 保存序列守卫与 receipt 编解码（纯函数，便于 JVM 单测）。
 *
 * 契约语义：只有**同一编辑器**的乱序写才算过期。
 * receipt 里同时记录 `savedEditorId`（编辑器身份）与 `savedSequence`（该编辑器的序号）；
 * 不同编辑器（例如 agent 用毫秒时间戳当序号）或旧 receipt 没有 `savedEditorId` 时一律不触发
 * STALE_SEQUENCE，交给 revision CAS 判定冲突，避免跨编辑器/跨会话误杀正文保存。
 */
internal object NoteSequenceGuard {
    /** receipt 中记录编辑器身份的字段名。 */
    const val KEY_SAVED_EDITOR_ID = "savedEditorId"

    /** 生成 NOTE 保存 receipt 的 resultJson（变更保存与幂等重放共用同一份口径）。 */
    fun receiptJson(
        notebookId: String,
        revision: Long,
        bodyHash: String,
        savedSequence: Long,
        savedEditorId: String,
    ): String = buildJsonObject {
        put("notebookId", notebookId)
        put("revision", revision)
        put("bodyHash", bodyHash)
        put("savedSequence", savedSequence)
        put(KEY_SAVED_EDITOR_ID, savedEditorId)
    }.toString()

    /** 解析 receipt 的 savedSequence；旧 receipt 或损坏 JSON 返回 null。 */
    fun parseSavedSequence(resultJson: String?): Long? =
        parsePrimitive(resultJson, "savedSequence")?.toLongOrNull()

    /** 解析 receipt 的 savedEditorId；旧 receipt 无此字段或损坏 JSON 返回 null。 */
    fun parseSavedEditorId(resultJson: String?): String? =
        parsePrimitive(resultJson, KEY_SAVED_EDITOR_ID)

    /**
     * 是否属于同编辑器乱序写：最近 receipt 由本编辑器产生、其序号更大，且正文确实已变化
     * （同一正文的幂等重放不算乱序）。
     */
    fun isStaleSequence(
        latestReceiptJson: String?,
        editorId: String,
        editorSequence: Long,
        notebookBody: String,
        requestedBodyHash: String,
    ): Boolean {
        if (latestReceiptJson == null || editorId.isEmpty()) return false
        val savedEditorId = parseSavedEditorId(latestReceiptJson) ?: return false
        if (savedEditorId != editorId) return false
        val savedSequence = parseSavedSequence(latestReceiptJson) ?: return false
        if (savedSequence <= editorSequence) return false
        return ReaderMutationHasher.sha256(notebookBody) != requestedBodyHash
    }

    /** 跨会话恢复：内存序列只抬不降，避免重启后小整数被旧的大序列误杀。 */
    fun seedSequence(current: Long, persisted: Long): Long = maxOf(current, persisted)

    private fun parsePrimitive(resultJson: String?, key: String): String? {
        if (resultJson == null) return null
        return runCatching {
            val root = Json.parseToJsonElement(resultJson).jsonObject
            val element = root[key] as? JsonPrimitive ?: return@runCatching null
            if (element is JsonNull) null else element.content
        }.getOrNull()
    }
}

