package com.example.myapplication.data.agent.notes

import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.AiNoteProposalEntity
import com.example.myapplication.data.local.entity.ReaderMutationReceiptEntity
import com.example.myapplication.data.reader.notes.NoteRepository
import com.example.myapplication.data.reader.notes.NoteSaveOutcome
import com.example.myapplication.data.reader.notes.ReaderMutationHasher
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.ReaderAccessDecision
import com.example.myapplication.domain.reader.ReaderAccessPolicy
import com.example.myapplication.domain.reader.ReaderDocumentAccessRecord
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * W4-05：笔记修改提议的创建、确认（CAS）、拒绝与查询。
 *
 * 设计约束（第四期详细设计 §6/§12/§13/§20、04-接口模型 §5、06-验证 FX-05/06/22）：
 * - 提议持久化在 `ai_note_proposals`（schema v5 已存在，不需要 v6）；
 * - `createProposal` 只接受当前 AccessContext 下从真实目标读到的 revision/正文，
 *   readReceipt 必须与 run / 目标 / revision /正文哈希一致，模型自填 baseRevision 不算读过；
 * - `applyProposal` 在**同一事务**内：用当前 ctx 再授权 → 核验 owner / revision / previewHash / receipt →
 *   检测 dirty 草稿与外部更新 → 经 [NoteRepository] 的 CAS 写正文与历史 → 提议 APPROVED；
 * - 重复确认幂等：返回首次结果（operationId = `proposal-apply-<id>` 的 receipt 为权威），
 *   不重复加 revision、不追加历史；STALE 提议不复用原 id 重改 payload；
 * - 进程重启后 PENDING 提议仍可查询、可确认，确认使用当前 ctx 而非旧 run epoch；
 * - 【模块边界】本文件只提供应用侧能力，不注册工具、不做 UI：
 *   `propose_note_patch` 工具在 [NoteProposalTools]（本包），确认/拒绝 UI 在 `ui/ai`，
 *   两者都只通过 [NoteProposalPort] 调用本服务，不存在绕过服务的写正文路径。
 */
class NoteProposalService(
    private val database: AppDatabase,
    private val noteRepository: NoteRepository = NoteRepository(database),
    private val clock: () -> Long = System::currentTimeMillis,
) : NoteProposalPort {
    private val proposals get() = database.aiRunDao()
    private val notebooks get() = database.readerNotebookDao()
    private val annotations get() = database.readerAnnotationDao()
    private val catalog get() = database.readerCatalogDao()

    override suspend fun createProposal(
        context: AccessContext,
        runId: String,
        targetKind: NoteProposalTargetKind,
        targetId: String,
        baseRevision: Long,
        readReceipt: NoteReadReceipt,
        edits: List<NotePatchEdit>,
        reason: String,
    ): ProposalCreateOutcome {
        if (runId.isBlank() || runId.length > 128) return rejected(NotePatchValidator.CODE_INVALID_ARGUMENT)
        if (targetId.isBlank() || targetId.length > 128) return rejected(NotePatchValidator.CODE_INVALID_ARGUMENT)
        if (reason.isBlank() || reason.length > MAX_REASON_CHARS) {
            return rejected(NotePatchValidator.CODE_INVALID_ARGUMENT)
        }
        if (baseRevision < 0) return rejected(NotePatchValidator.CODE_INVALID_ARGUMENT)
        if (readReceipt.receiptId.isBlank()) return rejected(CODE_INVALID_RECEIPT)

        val target = loadTarget(context, targetKind, targetId) ?: return rejected(CODE_NOT_FOUND)
        if (target.revision != baseRevision) return rejected(CODE_STALE_REVISION)

        receiptProblem(runId, targetKind, targetId, target, readReceipt)?.let { return rejected(it) }

        val run = proposals.findRun(context.userId, runId) ?: return rejected(CODE_NOT_FOUND)
        if (readReceipt.eventSeq < 0 || readReceipt.eventSeq > run.lastEventSeq) {
            return rejected(CODE_INVALID_RECEIPT)
        }

        val validation = NotePatchValidator.validate(target, edits)
        if (validation is NotePatchValidation.Invalid) return rejected(validation.code)
        val valid = validation as NotePatchValidation.Valid

        return database.withTransaction {
            // 同一 run + 同一 preview 的重试只返回原提议，不重复追加；
            // 但 STALE/REJECTED 后必须用新提议 id（不得复活旧 id）。
            val existing = proposals.findProposalsForRun(runId)
                .firstOrNull {
                    it.userId == context.userId &&
                        it.previewHash == valid.previewHash &&
                        it.status != NoteProposalStatus.STALE &&
                        it.status != NoteProposalStatus.REJECTED
                }
            if (existing != null) {
                return@withTransaction ProposalCreateOutcome.Created(existing.toRecord() ?: return@withTransaction rejected(CODE_CORRUPT))
            }
            val proposalId = "proposal-${UUID.randomUUID()}"
            val entity = AiNoteProposalEntity(
                id = proposalId,
                runId = runId,
                userId = context.userId,
                targetKind = targetKind.wire,
                targetId = targetId,
                baseRevision = baseRevision,
                readReceiptId = readReceipt.receiptId,
                readReceiptJson = readReceipt.toJson(),
                patchJson = encodePatch(target.documentId, reason, edits),
                previewHash = valid.previewHash,
                status = NoteProposalStatus.PENDING,
                resolvedAt = null,
            )
            proposals.insertProposal(entity)
            ProposalCreateOutcome.Created(entity.toRecord() ?: return@withTransaction rejected(CODE_CORRUPT))
        }
    }

    override suspend fun applyProposal(
        context: AccessContext,
        proposalId: String,
    ): ProposalApplyOutcome {
        val operationId = operationId(proposalId)
        return try {
            database.withTransaction {
                val entity = proposals.findProposal(context.userId, proposalId)
                    ?: return@withTransaction ProposalApplyOutcome.Rejected(CODE_NOT_FOUND)

                when (entity.status) {
                    NoteProposalStatus.APPROVED -> {
                        // 幂等：返回首次结果，不再写正文/历史。
                        val receipt = notebooks.findReceipt(context.userId, operationId)
                        val applied = parseReceiptResult(receipt?.resultJson)
                        return@withTransaction ProposalApplyOutcome.Applied(
                            proposalId = entity.id,
                            targetKind = NoteProposalTargetKind.fromWire(entity.targetKind)
                                ?: NoteProposalTargetKind.NOTEBOOK,
                            targetId = entity.targetId,
                            revision = applied?.first ?: entity.baseRevision,
                            bodyHash = applied?.second.orEmpty(),
                        )
                    }

                    NoteProposalStatus.REJECTED -> return@withTransaction ProposalApplyOutcome
                        .Rejected(CODE_PROPOSAL_REJECTED)

                    NoteProposalStatus.STALE -> {
                        val target = loadTargetOrNull(context, entity)
                        return@withTransaction ProposalApplyOutcome.Stale(
                            proposalId = entity.id,
                            reason = NoteProposalStaleReason.REVISION_CHANGED,
                            currentRevision = target?.revision ?: entity.baseRevision,
                        )
                    }
                }

                val record = entity.toRecord()
                    ?: return@withTransaction ProposalApplyOutcome.Failed(CODE_CORRUPT, retryable = false)
                val target = loadTarget(context, record.targetKind, record.targetId)
                    ?: return@withTransaction ProposalApplyOutcome.Rejected(CODE_NOT_FOUND)

                val persistedReceipt = NoteReadReceipt.parse(entity.readReceiptJson)
                    ?: return@withTransaction markStaleAndReturn(
                        entity, NoteProposalStaleReason.RECEIPT_MISMATCH, target.revision,
                    )

                // 持久字段与 receipt 互校：防篡改/损坏。
                if (persistedReceipt.receiptId != entity.readReceiptId ||
                    persistedReceipt.runId != entity.runId ||
                    persistedReceipt.targetKind != record.targetKind ||
                    persistedReceipt.targetId != record.targetId ||
                    persistedReceipt.revision != entity.baseRevision
                ) {
                    return@withTransaction markStaleAndReturn(
                        entity, NoteProposalStaleReason.RECEIPT_MISMATCH, target.revision,
                    )
                }

                // payload 完整性：previewHash 必须与持久化 edits 重算一致。
                val recomputed = NotePatchValidator.previewHash(
                    record.targetKind, record.targetId, entity.baseRevision, record.edits,
                )
                if (recomputed != entity.previewHash) {
                    return@withTransaction markStaleAndReturn(
                        entity, NoteProposalStaleReason.PAYLOAD_MISMATCH, target.revision,
                    )
                }

                // 外部更新：目标 revision 已变则 STALE（不覆盖）。
                if (target.revision != entity.baseRevision) {
                    return@withTransaction markStaleAndReturn(
                        entity, NoteProposalStaleReason.REVISION_CHANGED, target.revision,
                    )
                }
                if (persistedReceipt.contentHash != ReaderMutationHasher.sha256(target.body)) {
                    return@withTransaction markStaleAndReturn(
                        entity, NoteProposalStaleReason.RECEIPT_MISMATCH, target.revision,
                    )
                }

                // dirty 本地草稿：保留用户草稿，提议 STALE，不用 Agent 正文覆盖。
                if (record.targetKind == NoteProposalTargetKind.NOTEBOOK) {
                    val dirty = notebooks.listDrafts(context.userId, record.targetId)
                        .firstOrNull { it.editorId != AGENT_EDITOR_ID }
                    if (dirty != null) {
                        markStale(entity)
                        return@withTransaction ProposalApplyOutcome.Stale(
                            proposalId = entity.id,
                            reason = NoteProposalStaleReason.DIRTY_DRAFT,
                            currentRevision = target.revision,
                            draftBody = dirty.bodyMarkdown,
                        )
                    }
                }

                val validation = NotePatchValidator.validate(target, record.edits)
                if (validation !is NotePatchValidation.Valid) {
                    return@withTransaction markStaleAndReturn(
                        entity, NoteProposalStaleReason.PAYLOAD_MISMATCH, target.revision,
                    )
                }

                when (record.targetKind) {
                    NoteProposalTargetKind.NOTEBOOK -> applyNotebook(
                        context, entity, record, target, validation.newBody, operationId,
                    )

                    NoteProposalTargetKind.ANNOTATION_NOTE -> applyAnnotation(
                        context, entity, record, target, validation.newBody, operationId,
                    )
                }
            }
        } catch (abort: ProposalTransactionAbort) {
            ProposalApplyOutcome.Failed(CODE_STATE_RACE, retryable = true)
        }
    }

    override suspend fun rejectProposal(
        context: AccessContext,
        proposalId: String,
    ): ProposalRejectOutcome {
        val entity = proposals.findProposal(context.userId, proposalId) ?: return ProposalRejectOutcome.NotFound
        if (entity.status != NoteProposalStatus.PENDING) {
            return ProposalRejectOutcome.AlreadyResolved(entity.status)
        }
        val changed = proposals.resolveProposal(
            context.userId, proposalId, NoteProposalStatus.REJECTED, clock(),
        )
        return if (changed == 1) {
            ProposalRejectOutcome.Rejected(proposalId)
        } else {
            val latest = proposals.findProposal(context.userId, proposalId)
            ProposalRejectOutcome.AlreadyResolved(latest?.status ?: NoteProposalStatus.REJECTED)
        }
    }

    /** 进程重启后仍可查看待确认提议（不要求 run 活跃）。 */
    suspend fun pendingProposals(context: AccessContext): List<NoteProposalRecord> =
        proposals.findPendingProposals(context.userId).mapNotNull { it.toRecord() }

    /**
     * UI 用：会话内仍待审的提议（按 run→conversation 关联，不要求 run 活跃）。
     *
     * 为什么用 SQL 而不是逐条过滤：run 与 conversation 的归属在 ai_runs，服务不缓存；
     * 只返回当前 user 的行，其他会话/他人提议不可见。
     */
    override suspend fun pendingProposalsForConversation(
        context: AccessContext,
        conversationId: String,
    ): List<NoteProposalRecord> {
        if (conversationId.isBlank()) return emptyList()
        val ids = database.openHelper.readableDatabase.query(
            "SELECT p.id FROM ai_note_proposals p JOIN ai_runs r ON r.id = p.run_id " +
                "WHERE p.user_id = ? AND p.status = ? AND r.conversation_id = ? ORDER BY p.id ASC",
            arrayOf<Any?>(context.userId, NoteProposalStatus.PENDING, conversationId),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return ids.mapNotNull { proposals.findProposal(context.userId, it)?.toRecord() }
    }

    /**
     * read_note 回执签发用：当前 ctx 下读取目标快照（正文/版本/受保护文本）。
     * 与创建提议使用同一套访问校验 [loadTarget]，避免“读得到但提不了”的分叉。
     */
    override suspend fun readTarget(
        context: AccessContext,
        kind: NoteProposalTargetKind,
        targetId: String,
    ): NoteProposalTarget? = loadTarget(context, kind, targetId)

    suspend fun findProposal(context: AccessContext, proposalId: String): NoteProposalRecord? =
        proposals.findProposal(context.userId, proposalId)?.toRecord()

    // ---------------------------------------------------------------------

    private suspend fun applyNotebook(
        context: AccessContext,
        entity: AiNoteProposalEntity,
        record: NoteProposalRecord,
        target: NoteProposalTarget,
        newBody: String,
        operationId: String,
    ): ProposalApplyOutcome {
        val sequence = nextEditorSequence(context.userId, target.targetId)
        val outcome = noteRepository.save(
            context = context.copy(requestId = operationId),
            documentId = target.documentId,
            notebookId = target.targetId,
            baseRevision = record.baseRevision,
            editorId = AGENT_EDITOR_ID,
            editorSequence = sequence,
            body = newBody,
            actor = ACTOR_AGENT,
        )
        return when (outcome) {
            is NoteSaveOutcome.Saved -> {
                approve(entity)
                ProposalApplyOutcome.Applied(
                    proposalId = entity.id,
                    targetKind = NoteProposalTargetKind.NOTEBOOK,
                    targetId = target.targetId,
                    revision = outcome.ack.revision,
                    bodyHash = outcome.ack.bodyHash,
                )
            }

            is NoteSaveOutcome.Conflict -> {
                markStale(entity)
                ProposalApplyOutcome.Stale(
                    proposalId = entity.id,
                    reason = if (outcome.draftBody != null) {
                        NoteProposalStaleReason.DIRTY_DRAFT
                    } else {
                        NoteProposalStaleReason.REVISION_CHANGED
                    },
                    currentRevision = outcome.currentRevision,
                    draftBody = outcome.draftBody,
                )
            }

            is NoteSaveOutcome.Rejected -> ProposalApplyOutcome.Rejected(outcome.code)
            is NoteSaveOutcome.Failed -> ProposalApplyOutcome.Failed(outcome.code, outcome.retryable)
        }
    }

    private suspend fun applyAnnotation(
        context: AccessContext,
        entity: AiNoteProposalEntity,
        record: NoteProposalRecord,
        target: NoteProposalTarget,
        newBody: String,
        operationId: String,
    ): ProposalApplyOutcome {
        val current = annotations.findAnnotation(context.userId, target.targetId)
            ?: return ProposalApplyOutcome.Rejected(CODE_NOT_FOUND)
        val now = clock()
        val updated = annotations.updateAnnotation(
            userId = context.userId,
            annotationId = target.targetId,
            expectedRevision = record.baseRevision,
            locatorJson = current.locatorJson,
            quoteSnapshot = current.quoteSnapshot,
            noteText = newBody,
            styleJson = current.styleJson,
            inkBlobPath = current.inkBlobPath,
            now = now,
        )
        if (updated != 1) {
            markStale(entity)
            return ProposalApplyOutcome.Stale(
                proposalId = entity.id,
                reason = NoteProposalStaleReason.REVISION_CHANGED,
                currentRevision = annotations.findAnnotation(context.userId, target.targetId)?.revision
                    ?: record.baseRevision,
            )
        }
        val newRevision = record.baseRevision + 1
        val bodyHash = ReaderMutationHasher.sha256(newBody)
        notebooks.insertReceipt(
            ReaderMutationReceiptEntity(
                userId = context.userId,
                operationId = operationId,
                targetKind = NoteProposalTargetKind.ANNOTATION_NOTE.wire,
                targetId = target.targetId,
                requestHash = ReaderMutationHasher.requestHash(
                    NoteProposalTargetKind.ANNOTATION_NOTE.wire,
                    target.targetId,
                    record.baseRevision,
                    bodyHash,
                ),
                beforeRevision = record.baseRevision,
                afterRevision = newRevision,
                resultJson = buildJsonObject {
                    put("revision", newRevision)
                    put("bodyHash", bodyHash)
                }.toString(),
                createdAt = now,
            ),
        )
        approve(entity)
        return ProposalApplyOutcome.Applied(
            proposalId = entity.id,
            targetKind = NoteProposalTargetKind.ANNOTATION_NOTE,
            targetId = target.targetId,
            revision = newRevision,
            bodyHash = bodyHash,
        )
    }

    private suspend fun approve(entity: AiNoteProposalEntity) {
        val changed = proposals.resolveProposal(
            entity.userId, entity.id, NoteProposalStatus.APPROVED, clock(),
        )
        // PENDING→APPROVED 只能是唯一赢家；否则整个事务回滚，避免“已写正文但状态未变”。
        if (changed != 1) throw ProposalTransactionAbort()
    }

    private suspend fun markStale(entity: AiNoteProposalEntity): Boolean =
        proposals.resolveProposal(entity.userId, entity.id, NoteProposalStatus.STALE, clock()) == 1

    private suspend fun markStaleAndReturn(
        entity: AiNoteProposalEntity,
        reason: NoteProposalStaleReason,
        currentRevision: Long,
    ): ProposalApplyOutcome {
        markStale(entity)
        return ProposalApplyOutcome.Stale(
            proposalId = entity.id,
            reason = reason,
            currentRevision = currentRevision,
        )
    }

    private suspend fun nextEditorSequence(userId: Long, notebookId: String): Long {
        val latest = notebooks.findLatestReceipt(userId, "NOTE", notebookId)
        val lastSequence = parseSavedSequence(latest?.resultJson) ?: 0L
        return maxOf(clock(), lastSequence + 1)
    }

    private suspend fun loadTarget(
        context: AccessContext,
        kind: NoteProposalTargetKind,
        targetId: String,
    ): NoteProposalTarget? = when (kind) {
        NoteProposalTargetKind.NOTEBOOK -> {
            val notebook = notebooks.findNotebook(context.userId, targetId)
            if (notebook == null || !isReadable(context, notebook.documentId)) {
                null
            } else {
                NoteProposalTarget(
                    kind = kind,
                    targetId = notebook.id,
                    documentId = notebook.documentId,
                    revision = notebook.revision,
                    body = notebook.bodyMarkdown,
                )
            }
        }

        NoteProposalTargetKind.ANNOTATION_NOTE -> {
            val annotation = annotations.findAnnotation(context.userId, targetId)
            if (annotation == null || annotation.deletedAt != null || annotation.kind == KIND_INK) {
                null
            } else if (!isReadable(context, annotation.documentId)) {
                null
            } else {
                NoteProposalTarget(
                    kind = kind,
                    targetId = annotation.id,
                    documentId = annotation.documentId,
                    revision = annotation.revision,
                    body = annotation.noteText.orEmpty(),
                    protectedTexts = listOfNotNull(annotation.quoteSnapshot),
                )
            }
        }
    }

    private suspend fun loadTargetOrNull(
        context: AccessContext,
        entity: AiNoteProposalEntity,
    ): NoteProposalTarget? {
        val record = entity.toRecord() ?: return null
        return runCatching { loadTarget(context, record.targetKind, record.targetId) }.getOrNull()
    }

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

    private fun receiptProblem(
        runId: String,
        kind: NoteProposalTargetKind,
        targetId: String,
        target: NoteProposalTarget,
        receipt: NoteReadReceipt,
    ): String? = when {
        receipt.runId != runId -> CODE_INVALID_RECEIPT
        receipt.targetKind != kind -> CODE_INVALID_RECEIPT
        receipt.targetId != targetId -> CODE_INVALID_RECEIPT
        receipt.revision != target.revision -> CODE_STALE_REVISION
        receipt.contentHash != ReaderMutationHasher.sha256(target.body) -> CODE_STALE_REVISION
        else -> null
    }

    private fun encodePatch(documentId: String, reason: String, edits: List<NotePatchEdit>): String =
        buildJsonObject {
            put("documentId", documentId)
            put("reason", reason)
            put("edits", buildJsonArray {
                edits.forEach { edit ->
                    add(
                        buildJsonObject {
                            put("oldText", edit.oldText)
                            put("newText", edit.newText)
                            put("occurrencePolicy", edit.occurrencePolicy)
                        },
                    )
                }
            })
        }.toString()

    private fun parsePatch(json: String): ParsedPatch? {
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val documentId = root.string("documentId") ?: return null
        val reason = root.string("reason") ?: return null
        val editsJson = root.array("edits") ?: return null
        val edits = editsJson.mapNotNull { element ->
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val oldText = obj.string("oldText") ?: return@mapNotNull null
            val newText = obj.string("newText") ?: return@mapNotNull null
            NotePatchEdit(
                oldText = oldText,
                newText = newText,
                occurrencePolicy = obj.string("occurrencePolicy") ?: NotePatchEdit.OCCURRENCE_UNIQUE,
            )
        }
        if (edits.size != editsJson.size) return null
        return ParsedPatch(documentId = documentId, reason = reason, edits = edits)
    }

    private fun AiNoteProposalEntity.toRecord(): NoteProposalRecord? {
        val kind = NoteProposalTargetKind.fromWire(targetKind) ?: return null
        val patch = parsePatch(patchJson) ?: return null
        return NoteProposalRecord(
            id = id,
            runId = runId,
            userId = userId,
            targetKind = kind,
            targetId = targetId,
            documentId = patch.documentId,
            baseRevision = baseRevision,
            readReceiptId = readReceiptId,
            readReceipt = NoteReadReceipt.parse(readReceiptJson),
            edits = patch.edits,
            reason = patch.reason,
            previewHash = previewHash,
            status = status,
        )
    }

    /** 解析 receipt.resultJson 的 {revision, bodyHash}。 */
    private fun parseReceiptResult(json: String?): Pair<Long, String>? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val revision = root.long("revision") ?: return null
        val bodyHash = root.string("bodyHash").orEmpty()
        return revision to bodyHash
    }

    /** NOTE receipt 的 savedSequence：编辑器序号口径（NoteRepository 负责维护）。 */
    private fun parseSavedSequence(json: String?): Long? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        return root.long("savedSequence")
    }

    private fun operationId(proposalId: String): String = "proposal-apply-$proposalId"

    private fun rejected(code: String): ProposalCreateOutcome = ProposalCreateOutcome.Rejected(code)

    private class ProposalTransactionAbort : RuntimeException()

    private data class ParsedPatch(
        val documentId: String,
        val reason: String,
        val edits: List<NotePatchEdit>,
    )

    companion object {
        const val AGENT_EDITOR_ID = "agent"
        const val ACTOR_AGENT = "AGENT"
        private const val KIND_INK = "INK"
        private const val MAX_REASON_CHARS = 2_000

        const val CODE_NOT_FOUND = "NOT_FOUND"
        const val CODE_STALE_REVISION = "STALE_REVISION"
        const val CODE_INVALID_RECEIPT = "INVALID_RECEIPT"
        const val CODE_PROPOSAL_REJECTED = "PROPOSAL_REJECTED"
        const val CODE_CORRUPT = "PROPOSAL_CORRUPT"
        const val CODE_STATE_RACE = "PROPOSAL_STATE_RACE"
    }
}
