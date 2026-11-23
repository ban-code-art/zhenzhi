package com.example.myapplication.data.quiz

import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.QuizItemEntity
import com.example.myapplication.domain.quiz.QuizCodecs
import com.example.myapplication.domain.quiz.QuizEvidenceRef
import com.example.myapplication.domain.quiz.QuizItemSnapshot
import com.example.myapplication.domain.quiz.QuizItemType
import com.example.myapplication.domain.quiz.QuizPaperSnapshot
import com.example.myapplication.domain.quiz.QuizValidationReport
import com.example.myapplication.domain.quiz.ValidationIssue
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.data.session.SessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/**
 * 随堂测发布（W4-07 / 详细设计 §7.2/§14；Q-01/Q-02；05 契约 §4 v6）。
 *
 * 规则：
 *  - 只有 VALIDATING 通过的整卷才能在**一个 Room 事务**里发布：quiz_items + 私有
 *    questions（enabled=false、owner=当前用户、origin=AI、chapter_id 仅真实内置章节）
 *    + 来源快照（documentId/Locator/quote/revision）+ paper 状态 READY；
 *  - 任一校验/来源校验失败则调用方置 FAILED：本类 publish 不做半卷写入，
 *    失败时 paper 保持 GENERATING/FAILED 且 quiz_items 为 0（无可作答半卷）；
 *  - 来源 revision/scope 在发布前再次核对：当前 reader document 的 contentRevision
 *    必须等于固定 sourceRevision，否则 SOURCE_CHANGED；
 *  - 不裁题、不把错误题忽略后继续发布。
 */
class QuizPublisher(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val personalReviewStore: QuizReviewDraftStore? = null,
    private val identityProvider: (suspend () -> SessionIdentity)? = null,
) {
    data class Request(
        val context: AccessContext,
        val paperId: String,
        val paper: QuizPaperSnapshot,
        val bound: QuizEvidenceBinder.BoundPaper,
        val documentId: String?,
        /** 只有真实内置章节才落 chapter_id；导入文档/无章节时为 null。 */
        val chapterId: Long?,
        val generationRunId: String?,
        val sourceRevision: String,
        val generatedBy: String = "AI",
        val supportAssessments: List<QuizSupportAssessment> = emptyList(),
        val personalSourcePaperId: String? = null,
        val personalDraftSha256: String? = null,
    )

    sealed interface Outcome {
        data class Published(
            val paperId: String,
            val itemIds: List<String>,
            val questionIds: List<Long>,
            val questionCount: Int,
            val maxScore: Int,
        ) : Outcome

        data class Rejected(
            val code: String,
            val issues: List<ValidationIssue> = emptyList(),
            val message: String = "",
        ) : Outcome
    }

    private class PublishRollback(val outcome: Outcome) : Exception()

    private suspend fun requireIdentity(request: Request) {
        val current = identityProvider?.invoke() ?: return
        if (current.userId != request.context.userId || current.epoch != request.context.sessionEpoch)
            throw CancellationException("Quiz publish session changed")
    }

    suspend fun publish(request: Request): Outcome = try {
        database.withTransaction { publishInTransaction(request) }
    } catch (rollback: PublishRollback) {
        rollback.outcome
    }

    private suspend fun publishInTransaction(request: Request): Outcome {
        requireIdentity(request)
        val paper = findPaperRow(request.paperId) ?: return Outcome.Rejected("NOT_FOUND", message = "paper placeholder missing")
        if (paper.userId != request.context.userId) return Outcome.Rejected("ACCESS_REVOKED")
        if (paper.status !in PUBLISHABLE_STATUSES) {
            // 已取消/已发布/已失败的 paper 不允许再次发布（延迟响应不得改 READY）。
            return Outcome.Rejected("QUIZ_INVALID", message = "paper status ${paper.status} is not publishable")
        }
        if (paper.sourceRevision != request.sourceRevision || request.paper.sourceRevision != request.sourceRevision) {
            return Outcome.Rejected("SOURCE_CHANGED", message = "paper sourceRevision drifted")
        }
        if (paper.documentId != request.documentId || paper.generationRunId != request.generationRunId ||
            paper.scopeJson != request.paper.scopeJson) return Outcome.Rejected("SOURCE_CHANGED", message = "paper scope identity drifted")
        val scope = try { kotlinx.serialization.json.Json.parseToJsonElement(paper.scopeJson) as kotlinx.serialization.json.JsonObject } catch (_: Exception) { return Outcome.Rejected("SOURCE_CHANGED") }
        val sectionIds = (scope["sectionIds"] as? kotlinx.serialization.json.JsonArray)?.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }?.toSet().orEmpty()
        if (request.bound.byItem.values.flatMap { it.refs }.any { it.documentId != request.documentId || (sectionIds.isNotEmpty() && it.sectionId !in sectionIds) }) return Outcome.Rejected("SOURCE_CHANGED", message = "evidence outside fixed scope")
        // 来源 revision 复核：固定 revision 已被替换/删除时停止，不发布旧内容冒充当前。
        if (request.documentId != null) {
            val document = database.readerCatalogDao().findDocumentIncludingDeleted(request.documentId)
                ?: return Outcome.Rejected("SOURCE_CHANGED", message = "source document missing")
            val revision = database.readerCatalogDao().findContentRevision(request.documentId, request.sourceRevision)
                ?: return Outcome.Rejected("SOURCE_CHANGED", message = "source revision metadata missing")
            fun frozenMatches(key: String, value: String) = (scope[key] as? JsonPrimitive)?.content == value
            if (!frozenMatches("accessEpoch", document.accessEpoch.toString()) ||
                !frozenMatches("metadataRevision", document.metadataRevision.toString()) ||
                !frozenMatches("sourceOwnerUserId", document.ownerUserId?.toString() ?: "PUBLIC") ||
                !frozenMatches("extractorVersion", revision.extractorVersion)) return Outcome.Rejected("SOURCE_CHANGED", message = "source generation identity changed")
            if (document.deletedAt != null || document.contentRevision != request.sourceRevision) {
                return Outcome.Rejected("SOURCE_CHANGED", message = "source document revision changed")
            }
        }
        val template = QuizTemplate.chapterQuizV1()
        if (paper.templateVersion != request.paper.templateVersion || request.paper.templateId != template.templateId ||
            request.paper.templateVersion != template.templateVersion) return Outcome.Rejected("QUIZ_INVALID", message = "paper template identity drifted")
        val boundRefs = request.bound.byItem.values.flatMap { it.refs }.associateBy { it.evidenceId }
        val structural = QuizValidator(template, evidenceResolver = { id ->
            boundRefs[id]?.let { ref -> com.example.myapplication.domain.reader.SourceEvidence(
                evidenceId = ref.evidenceId, documentId = ref.documentId, sectionId = ref.sectionId,
                contentRevision = ref.contentRevision, locator = ref.locator, quote = ref.quote,
                quoteSha256 = ref.quoteSha256, sourceTitleSnapshot = ref.sourceTitleSnapshot,
                extractorVersion = (scope["extractorVersion"] as? JsonPrimitive)?.content.orEmpty()) }
        }, expectedSourceRevision = request.sourceRevision).validate(request.paper)
        if (!structural.isValid) return Outcome.Rejected("QUIZ_INVALID", structural.issues, "complete paper validation failed")
        if (request.paper.items.map { it.ordinal } != (1..template.itemCount).toList())
            return Outcome.Rejected("QUIZ_INVALID", message = "paper ordinal sequence is invalid")
        val savedCoverage = try { Json.parseToJsonElement(paper.coverageJson).jsonObject } catch (_: Exception) { return Outcome.Rejected("QUIZ_INVALID", message = "saved coverage is invalid") }
        val suppliedCoverage = try { Json.parseToJsonElement(request.paper.coverageJson).jsonObject } catch (_: Exception) { return Outcome.Rejected("QUIZ_INVALID", message = "paper coverage is invalid") }
        val mergedCoverage = savedCoverage.toMutableMap().apply {
            suppliedCoverage.filterKeys { it != "supportReview" }.forEach { (key,value) -> put(key,value) }
            (savedCoverage["supportReview"] as? JsonObject)?.let { review ->
                put("supportReview", JsonObject(review + ("state" to JsonPrimitive("PUBLISHED"))))
            }
        }
        // 逐题出处必须全部绑定成功（validator 已核验，这里防御性复核）。
        if (!request.bound.isValid) {
            return Outcome.Rejected("EVIDENCE_UNVERIFIED", issues = request.bound.issues)
        }
        if (request.paper.items.isEmpty()) return Outcome.Rejected("QUIZ_INVALID", message = "empty paper")
        request.paper.items.forEachIndexed { index, item ->
            val itemRefs = request.bound.byItem[index]?.refs.orEmpty()
            if (itemRefs.isEmpty()) {
                return Outcome.Rejected(
                    "EVIDENCE_UNVERIFIED",
                    message = "item ${item.ordinal} has no verified source reference",
                )
            }
            if (itemRefs.any { it.contentRevision != request.sourceRevision }) {
                return Outcome.Rejected("SOURCE_CHANGED", message = "item ${item.ordinal} evidence revision drifted")
            }
        }

        if (request.supportAssessments.size != request.paper.items.size) return Outcome.Rejected("SUPPORT_REVIEW_REQUIRED")
        val personalDraft = request.personalSourcePaperId?.let { sourceId ->
            val source = findPaperRow(sourceId) ?: return@let null
            if (source.userId != request.context.userId || source.status != "FAILED" ||
                source.sourceRevision != request.sourceRevision || source.scopeJson != paper.scopeJson) return@let null
            val reviewStore = personalReviewStore ?: return@let null
            val coverage = runCatching { Json.parseToJsonElement(source.coverageJson).jsonObject }.getOrNull() ?: return@let null
            val reference = reviewStore.referenceFromCoverage(coverage) ?: return@let null
            if (reference.draftSha256 != request.personalDraftSha256) return@let null
            val draft = reviewStore.load(request.context.userId,sourceId,reference,request.sourceRevision,scope) ?: return@let null
            if (draft.items.size != request.paper.items.size || draft.supportAssessments.size != draft.items.size ||
                draft.items.map(QuizSupportAssessor::itemHash) != request.paper.items.map(QuizSupportAssessor::itemHash)) return@let null
            val storedRefs = runCatching { QuizCodecs.decodeEvidence(draft.sourceSnapshots.toString()) }.getOrNull() ?: return@let null
            if (request.bound.byItem.values.flatMap { it.refs }.any { it !in storedRefs }) return@let null
            draft
        }
        request.paper.items.forEachIndexed { index, item ->
            val assessment = request.supportAssessments[index]
            val hashes = request.bound.byItem[index]?.refs.orEmpty().map { it.quoteSha256.lowercase() }.toSet()
            val personal = assessment.approval?.let { approval ->
                val storedAssessment = personalDraft?.supportAssessments?.getOrNull(index)?.let { raw ->
                    runCatching { QuizPersonalReview.decodeAssessment(raw.jsonObject) }.getOrNull()
                }
                val sourceId=request.personalSourcePaperId
                val source=sourceId?.let(::findPaperRow)
                val reference=source?.let { row -> runCatching { Json.parseToJsonElement(row.coverageJson).jsonObject["supportReview"]?.jsonObject }.getOrNull() }
                personalDraft != null && source != null && source.userId == request.context.userId && source.status == "FAILED" &&
                    source.sourceRevision == request.sourceRevision && source.scopeJson == paper.scopeJson &&
                    reference?.get("state")?.jsonPrimitive?.content == "REVIEW_REQUIRED" &&
                    reference?.get("draftSha256")?.jsonPrimitive?.content == request.personalDraftSha256 &&
                    approval.draftHash == request.personalDraftSha256 &&
                    storedAssessment != null && QuizPersonalReview.approve(item,storedAssessment,approval,request.context.userId) == assessment
            } == true
            val audited=assessment.state == QuizSupportState.SUPPORTED && assessment.authority == "AUDITED_TEMPLATE" &&
                assessment.factReviewSha256?.matches(Regex("[a-fA-F0-9]{64}")) == true &&
                assessment.assertionChecks.isNotEmpty() && assessment.assertionChecks.all {it.judgement == "SUPPORTED"}
            if ((!audited && !personal) ||
                assessment.itemDraftHash != QuizSupportAssessor.itemHash(item) ||
                assessment.sourceRevision != request.sourceRevision || assessment.evidenceHashes.toSet() != hashes ||
                assessment.assertionChecks.isEmpty()) {
                return Outcome.Rejected("SUPPORT_REVIEW_REQUIRED", message = "item ${item.ordinal} support proof is missing or stale")
            }
        }

        // ---- 以下开始写入：任何异常由 withTransaction 回滚 ----
        requireIdentity(request)
        val now = clock()
        val firstQuestionId = (maxQuestionId() + 1)
        val questionIds = request.paper.items.indices.map { firstQuestionId + it }
        val itemIds = request.paper.items.indices.map { "qitem-${request.paperId}-${it + 1}" }

        request.paper.items.forEachIndexed { index, item ->
            val refs = request.bound.byItem[index]?.refs.orEmpty()
            insertPrivateQuestion(
                questionId = questionIds[index],
                userId = request.context.userId,
                chapterId = request.chapterId,
                item = item,
                refs = refs,
                now = now,
            )
        }

        database.quizPaperDao().insertItems(
            request.paper.items.mapIndexed { index, item ->
                QuizItemEntity(
                    id = itemIds[index],
                    paperId = request.paperId,
                    questionId = questionIds[index],
                    ordinal = item.ordinal,
                    questionSnapshotJson = QuizCodecs.encodeItemSnapshot(item.copy(itemId = itemIds[index])),
                    rubricJson = QuizCodecs.encodeRubric(item.rubric),
                    evidenceJson = QuizCodecs.encodeEvidence(request.bound.byItem[index]?.refs.orEmpty()),
                )
            },
        )

        val affected = database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM quiz_papers WHERE id = ? AND user_id = ? AND status IN ('GENERATING','VALIDATING')",
            arrayOf<Any?>(request.paperId, request.context.userId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        if (affected == 0) {
            throw PublishRollback(
                Outcome.Rejected("QUIZ_INVALID", message = "paper left GENERATING/VALIDATING before publish"),
            )
        }
        database.openHelper.writableDatabase.execSQL(
            "UPDATE quiz_papers SET status = 'READY', question_count = ?, max_score = ?, " +
                "generation_coverage_json = ?, generated_by = ?, generation_run_id = ?, " +
                "template_version = ? WHERE id = ? AND user_id = ? AND status IN ('GENERATING','VALIDATING')",
            arrayOf<Any?>(
                request.paper.items.size,
                request.paper.items.sumOf { it.score },
                JsonObject(mergedCoverage).toString(),
                request.generatedBy,
                request.generationRunId,
                request.paper.templateVersion,
                request.paperId,
                request.context.userId,
            ),
        )
        if (!paperStatusIs(request.paperId, "READY")) {
            throw PublishRollback(Outcome.Rejected("QUIZ_INVALID", message = "paper status update failed"))
        }
        requireIdentity(request)
        return Outcome.Published(
            paperId = request.paperId,
            itemIds = itemIds,
            questionIds = questionIds,
            questionCount = request.paper.items.size,
            maxScore = request.paper.items.sumOf { it.score },
        )
    }

    /** 失败置账：只允许从 GENERATING/VALIDATING 迁移，绝不覆盖 READY/CANCELLED。 */
    suspend fun markFailed(paperId: String, userId: Long): Boolean =
        markStatus(paperId, userId, "FAILED", setOf("GENERATING", "VALIDATING"))

    suspend fun markStatus(paperId: String, userId: Long, status: String, from: Set<String>): Boolean {
        if (status !in ALLOWED_STATUSES) return false
        val placeholders = from.joinToString(",") { "?" }
        val args = mutableListOf<Any?>(status, paperId, userId)
        args.addAll(from)
        val queryArgs = (listOf<Any?>(paperId, userId) + from).toTypedArray()
        val affected = database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM quiz_papers WHERE id = ? AND user_id = ? AND status IN ($placeholders)",
            queryArgs,
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        if (affected == 0) return false
        database.openHelper.writableDatabase.execSQL(
            "UPDATE quiz_papers SET status = ? WHERE id = ? AND user_id = ? AND status IN ($placeholders)",
            args.toTypedArray(),
        )
        return true
    }

    private suspend fun insertPrivateQuestion(
        questionId: Long,
        userId: Long,
        chapterId: Long?,
        item: QuizItemSnapshot,
        refs: List<QuizEvidenceRef>,
        now: Long,
    ) {
        val source = refs.firstOrNull()
        val type = when (item.type) {
            QuizItemType.CHOICE -> "SINGLE"
            QuizItemType.ESSAY -> "ESSAY"
        }
        val optionsJson = if (item.options.isEmpty()) {
            null
        } else {
            buildJsonArray { item.options.forEach { add(it) } }.toString()
        }
        val answerValue = when (item.type) {
            QuizItemType.CHOICE -> item.answer.firstOrNull().orEmpty().trim().uppercase()
            QuizItemType.ESSAY -> buildJsonArray { item.answer.forEach { add(it) } }.toString()
        }
        val locatorJson = source?.let {
            com.example.myapplication.domain.reader.ReaderLocatorCodec.encode(it.locator)
        }
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO questions(id, type, chapter_id, stem, options, answer, synonyms, knowledge_point, " +
                "analysis, score, enabled, created_at, owner_user_id, origin, source_document_id, " +
                "source_locator_json, source_excerpt, source_revision) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                questionId,
                type,
                chapterId,
                item.stem,
                optionsJson,
                answerValue,
                null,
                item.knowledgePoint,
                item.analysis,
                item.score,
                now,
                userId,
                "AI",
                source?.documentId,
                locatorJson,
                source?.quote,
                source?.contentRevision,
            ),
        )
    }

    private fun maxQuestionId(): Long =
        database.openHelper.readableDatabase.query("SELECT COALESCE(MAX(id),0) FROM questions").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun paperStatusIs(paperId: String, status: String): Boolean =
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM quiz_papers WHERE id = ? AND status = ?",
            arrayOf<Any?>(paperId, status),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0) > 0
        }

    private data class PaperRow(
        val id: String,
        val userId: Long,
        val status: String,
        val sourceRevision: String,
        val generationRunId: String?,
        val templateVersion: String,
        val scopeJson: String,
        val documentId: String?,
        val coverageJson: String,
    )

    private fun findPaperRow(paperId: String): PaperRow? =
        database.openHelper.readableDatabase.query(
            "SELECT id, user_id, status, source_revision, generation_run_id, template_version, scope_json, document_id, generation_coverage_json " +
                "FROM quiz_papers WHERE id = ? LIMIT 1",
            arrayOf<Any?>(paperId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            PaperRow(
                id = cursor.getString(0),
                userId = cursor.getLong(1),
                status = cursor.getString(2),
                sourceRevision = cursor.getString(3),
                generationRunId = if (cursor.isNull(4)) null else cursor.getString(4),
                templateVersion = cursor.getString(5),
                scopeJson = cursor.getString(6),
                documentId = if (cursor.isNull(7)) null else cursor.getString(7),
                coverageJson = cursor.getString(8),
            )
        }

    companion object {
        val PUBLISHABLE_STATUSES = setOf("GENERATING", "VALIDATING")
        val ALLOWED_STATUSES = setOf("GENERATING", "VALIDATING", "READY", "FAILED", "CANCELLED", "INTERRUPTED")
    }
}
