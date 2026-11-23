package com.example.myapplication.data.quiz

import androidx.room.withTransaction
import com.example.myapplication.data.agent.ConversationRepository
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.AiRunEntity
import com.example.myapplication.data.local.entity.AiRunEventEntity
import com.example.myapplication.data.local.entity.QuizPaperEntity
import com.example.myapplication.data.local.dao.ReaderCatalogDao
import com.example.myapplication.domain.agent.AgentHashing
import com.example.myapplication.domain.agent.AgentTool
import com.example.myapplication.domain.agent.ToolArgumentException
import com.example.myapplication.domain.agent.ToolArgs
import com.example.myapplication.domain.agent.ToolError
import com.example.myapplication.domain.agent.ToolErrorCode
import com.example.myapplication.domain.agent.ToolExecutionContext
import com.example.myapplication.domain.agent.ToolGroup
import com.example.myapplication.domain.agent.ToolResult
import com.example.myapplication.domain.agent.ToolSpec
import com.example.myapplication.domain.agent.boundedObject
import com.example.myapplication.domain.agent.stringSchema
import com.example.myapplication.domain.quiz.QuizDifficulty
import com.example.myapplication.domain.quiz.QuizTemplateSpec
import com.example.myapplication.domain.quiz.ValidationIssue
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.ContentBlock
import com.example.myapplication.domain.reader.DocumentAccess
import com.example.myapplication.domain.reader.ExtractionQuality
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReadingScope
import com.example.myapplication.domain.reader.ScopeRequest
import com.example.myapplication.domain.reader.SourceEvidence
import com.example.myapplication.domain.reader.SourceReadBudget
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 随堂测生成协调（W4-07 / 详细设计 §7.2/§14/§20；计划 §7；Q-01/Q-02）。
 *
 * - workflow=QUIZ：独立的 ai_conversations(CHAT 不受影响) + ai_runs；同会话 active_slot
 *   由 DB 唯一约束兜底（不同范围的第二个生成 → BUSY；相同来源范围可继续已有任务）；
 * - 先落 GENERATING paper 占位（固定 source_revision / scope_json / template_version /
 *   generation_run_id），再执行 READING→PLANNING→GENERATING→VALIDATING→PUBLISHING；
 * - 整条流水线限 75s；校验不通过最多一次定向修复，仍失败整卷 FAILED，
 *   不发布半卷、不裁题；来源 revision/scope 变化 → SOURCE_CHANGED；
 * - 计划级覆盖不足（章节覆盖率 < 阈值 / 覆盖章节内无知识点 / 无题槽）→ 生成前直接 FAILED
 *   （COVERAGE_INSUFFICIENT），不进入生成/修复循环：重写题目改不了计划，白烧调用没有意义；
 * - 取消/被杀：取消 run、paper 置 CANCELLED/INTERRUPTED（绝不 READY）；
 *   启动恢复只把未终态置 INTERRUPTED，不自动重新执行/扣费；
 * - 生成内容由可注入的 [QuizDraftSource] 提供（生产接模型调用，测试用固定 JSON/fake）。
 */
class QuizGenerationCoordinator(
    private val database: AppDatabase,
    private val conversations: ConversationRepository,
    private val documentAccess: DocumentAccess,
    private val catalog: ReaderCatalogDao,
    private val scope: CoroutineScope,
    private val draftSource: QuizDraftSource? = null,
    private val planner: QuizPlanner = QuizPlanner(),
    private val publisher: QuizPublisher = QuizPublisher(database, System::currentTimeMillis),
    private val clock: () -> Long = System::currentTimeMillis,
    private val reviewDraftStore: QuizReviewDraftStore? = null,
    private val supportAssessor: QuizSupportAssessor = QuizSupportAssessor(),
    private val auditedFactProvider: (com.example.myapplication.domain.quiz.QuizItemSnapshot) -> QuizAuditedFact? = { null },
    private val totalDeadlineMs: Long = DEFAULT_TOTAL_DEADLINE_MS,
) {
    /** 生成器端口：真实实现走模型；测试/未接通时注入固定 JSON。 */
    fun interface QuizDraftSource {
        suspend fun generate(request: QuizDraftRequest): QuizDraftSourceResult
    }

    data class QuizDraftRequest(
        val generationRunId: String,
        val paperId: String,
        val userId: Long,
        val requestKey: String,
        val sourceRevision: String,
        val scopeJson: String,
        val template: QuizTemplateSpec,
        val plan: QuizGenerationPlan,
        val difficulty: QuizDifficulty?,
        /** 0=首轮，1..=定向修复轮。 */
        val repairRound: Int,
        val previousIssues: List<ValidationIssue>,
        val sections: List<SourceSection>,
        /** 应用侧已核验的 S# 证据，模型只能引用编号。 */
        val evidence: Map<String, SourceEvidence>,
        val isCancelled: () -> Boolean,
    )

    sealed interface QuizDraftSourceResult {
        data class Draft(val rawJson: String) : QuizDraftSourceResult
        data class Failed(val code: String, val message: String) : QuizDraftSourceResult
        data object Cancelled : QuizDraftSourceResult
        data class CapabilityUnsupported(val message: String) : QuizDraftSourceResult
    }

    sealed interface StartOutcome {
        data class Started(val generationRunId: String, val paperId: String) : StartOutcome
        data class Existing(val generationRunId: String, val paperId: String?, val status: String) : StartOutcome
        data class Busy(val activeRunId: String) : StartOutcome
        data class Rejected(val code: String) : StartOutcome
    }

    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val starts = Mutex()
    private val cancellations = Mutex()
    private val events = Mutex()

    // ---------------------------------------------------------------- start

    suspend fun startGeneration(
        ctx: AccessContext,
        sourceScope: ScopeRequest,
        templateId: String,
        difficulty: QuizDifficulty? = null,
        requestKey: String,
        sectionIds: Set<String> = emptySet(),
        reuseMatching: Boolean = false,
    ): StartOutcome = starts.withLock {
        startGenerationLocked(ctx, sourceScope, templateId, difficulty, requestKey, sectionIds, reuseMatching)
    }

    private suspend fun startGenerationLocked(
        ctx: AccessContext,
        sourceScope: ScopeRequest,
        templateId: String,
        difficulty: QuizDifficulty?,
        requestKey: String,
        sectionIds: Set<String>,
        reuseMatching: Boolean,
    ): StartOutcome {
        if (ctx.userId <= 0) return StartOutcome.Rejected("NOT_SIGNED_IN")
        if (requestKey.isBlank() || requestKey.length > MAX_REQUEST_KEY_CHARS) {
            return StartOutcome.Rejected("INVALID_REQUEST_KEY")
        }
        if (sourceScope.documentIds.isEmpty()) return StartOutcome.Rejected("EMPTY_SCOPE")
        if (sourceScope.documentIds.size != 1) return StartOutcome.Rejected("MULTI_DOCUMENT_SCOPE_UNAVAILABLE")
        if (sourceScope.readingScope == ReadingScope.LIBRARY) return StartOutcome.Rejected("UNSUPPORTED_SCOPE")
        val stableSections = sectionIds.toSortedSet().toSet()
        if (sourceScope.readingScope == ReadingScope.CURRENT_SECTION && stableSections.isEmpty()) return StartOutcome.Rejected("SECTION_SCOPE_REQUIRED")
        if (sourceScope.readingScope == ReadingScope.CURRENT_DOCUMENT && stableSections.isNotEmpty()) return StartOutcome.Rejected("INVALID_SECTION_SCOPE")
        if (templateId != QuizTemplate.CHAPTER_QUIZ_V1_ID) return StartOutcome.Rejected("QUIZ_INVALID")
        if (draftSource == null) return StartOutcome.Rejected("CAPABILITY_UNSUPPORTED")

        val documentId = sourceScope.documentIds.first()
        // 固定 revision 由 DocumentAccess 解析（核验授权/会话 epoch），不依赖知识库索引是否就绪。
        val opened = documentAccess.open(ctx, documentId, null)
        if (opened !is OperationResult.Ok) {
            val code = (opened as? OperationResult.Rejected)?.code
            return StartOutcome.Rejected(
                when (code) {
                    "ACCESS_REVOKED" -> "ACCESS_REVOKED"
                    "NOT_FOUND" -> "NOT_FOUND"
                    null -> "CAPABILITY_UNSUPPORTED"
                    else -> "QUIZ_INVALID"
                },
            )
        }
        val snapshot = opened.value
        if (snapshot.sections.isEmpty()) return StartOutcome.Rejected("QUIZ_INVALID")
        if (stableSections.any { id -> allDescriptors(ctx, snapshot).none { it.id == id } }) return StartOutcome.Rejected("SECTION_NOT_FOUND")
        val pinnedRevision = snapshot.contentRevision
        val template = QuizTemplate.chapterQuizV1()
        val existing = database.aiRunDao().findRunByRequestKey(ctx.userId, requestKey)
        if (existing != null) {
            val paper = findPaperIdByRun(existing.id)?.let { database.quizPaperDao().findPaperForUser(ctx.userId, it) }
            if (paper == null || !matchesSourceScope(paper, snapshot, sourceScope, stableSections, difficulty))
                return StartOutcome.Rejected("REQUEST_KEY_SCOPE_MISMATCH")
            return StartOutcome.Existing(existing.id, paper.id, existing.status)
        }
        val quizConversation = ensureQuizConversation(ctx, sourceScope)
        database.aiRunDao().findActiveRun(quizConversation.id)?.let { active ->
            val paper = findPaperIdByRun(active.id)?.let { database.quizPaperDao().findPaperForUser(ctx.userId, it) }
            if (reuseMatching && paper != null && matchesSourceScope(paper, snapshot, sourceScope, stableSections, difficulty)) {
                return StartOutcome.Existing(active.id, paper.id, active.status)
            }
            return StartOutcome.Busy(active.id)
        }
        if (reuseMatching) {
            val cached = database.quizPaperDao().listPapersForDocument(ctx.userId, documentId, 32, 0)
            for (paper in cached) {
                if (!matchesSourceScope(paper, snapshot, sourceScope, stableSections, difficulty)) continue
                val run = paper.generationRunId?.let { database.aiRunDao().findRun(ctx.userId, it) } ?: continue
                val usable = if (paper.status == "READY" && run.status == STATUS_COMPLETED)
                    paper.questionCount > 0 && database.quizPaperDao().countItems(paper.id) == paper.questionCount
                else if (paper.status == STATUS_FAILED && reviewDraftStore != null)
                    QuizReviewService(database, reviewDraftStore, documentAccess).load(ctx.userId, paper.id) != null
                else false
                if (usable) return StartOutcome.Existing(run.id, paper.id, run.status)
            }
        }
        val now = clock()
        val runId = "quiz-run-${UUID.randomUUID()}"
        val paperId = "quiz-paper-${UUID.randomUUID()}"
        val scopeJson = buildJsonObject {
            put("documentId", documentId)
            // Freeze the sorted explicit section set; whole-document scopes use an empty set.
            put("readingScope", sourceScope.readingScope.name)
            putJsonArray("sectionIds") { stableSections.forEach { add(it) } }
            put("snapshotId", snapshot.snapshotId)
            put("sourceRevision", pinnedRevision)
            put("accessEpoch", snapshot.accessEpoch)
            put("metadataRevision", snapshot.metadataRevision)
            put("sourceOwnerUserId", snapshot.ownerUserId?.toString() ?: "PUBLIC")
            put("extractorVersion", snapshot.extractorVersion)
            put("difficulty", difficulty?.name ?: "DEFAULT")
        }.toString()

        database.withTransaction {
            database.aiRunDao().insertRun(
                AiRunEntity(
                    id = runId,
                    userId = ctx.userId,
                    conversationId = quizConversation.id,
                    requestKey = requestKey,
                    configRevision = 0,
                    profileSnapshotJson = "{}",
                    scopeSnapshotJson = scopeJson,
                    status = STATUS_RUNNING,
                    activeSlot = 1,
                    rounds = 0,
                    tokenUsage = 0,
                    deadlineAt = now + totalDeadlineMs,
                    lastEventSeq = 1,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            database.aiRunDao().insertRunEvent(
                AiRunEventEntity(
                    runId = runId,
                    seq = 1,
                    type = EVENT_STAGE,
                    payloadJson = stageJson(STAGE_REGISTERED, paperId),
                    createdAt = now,
                ),
            )
            database.quizPaperDao().insertPaper(
                QuizPaperEntity(
                    id = paperId,
                    userId = ctx.userId,
                    documentId = documentId,
                    scopeJson = scopeJson,
                    sourceRevision = pinnedRevision,
                    templateVersion = template.templateVersion,
                    questionCount = 0,
                    maxScore = 0,
                    status = STATUS_GENERATING,
                    generationRunId = runId,
                    generationCoverageJson = "{}",
                    generatedBy = "AI",
                    createdAt = now,
                ),
            )
        }

        if (!scope.isActive) {
            withContext(NonCancellable) { markCancelled(ctx, runId, paperId) }
            return StartOutcome.Started(runId, paperId)
        }
        val dispatched = java.util.concurrent.atomic.AtomicBoolean(false)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            dispatched.set(true)
            runPipeline(
                ctx = ctx,
                runId = runId,
                paperId = paperId,
                documentId = documentId,
                pinnedRevision = pinnedRevision,
                scopeJson = scopeJson,
                template = template,
                difficulty = difficulty,
                requestKey = requestKey,
            )
        }
        jobs[runId] = job
        job.invokeOnCompletion {
            if (dispatched.get()) {
                jobs.remove(runId, job)
            } else {
                // A cancelled parent may prevent the lazy body, including its finally, from running.
                scope.launch(NonCancellable + Dispatchers.IO) {
                    try { markCancelled(ctx, runId, paperId) }
                    finally { jobs.remove(runId, job) }
                }
            }
        }
        job.start()
        return StartOutcome.Started(runId, paperId)
    }

    private fun matchesSourceScope(
        paper: QuizPaperEntity,
        snapshot: com.example.myapplication.domain.reader.DocumentSnapshot,
        scopeRequest: ScopeRequest,
        sectionIds: Set<String>,
        difficulty: QuizDifficulty?,
    ): Boolean = runCatching {
        if (paper.sourceRevision != snapshot.contentRevision || paper.generatedBy != "AI" ||
            paper.templateVersion != QuizTemplate.chapterQuizV1().templateVersion) return@runCatching false
        val saved = kotlinx.serialization.json.Json.parseToJsonElement(paper.scopeJson).jsonObject
        fun text(key: String) = (saved[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val sections = (saved["sectionIds"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .map { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.toSet()
        text("documentId") == snapshot.documentId && text("readingScope") == scopeRequest.readingScope.name &&
            sections == sectionIds && text("accessEpoch") == snapshot.accessEpoch.toString() &&
            text("metadataRevision") == snapshot.metadataRevision.toString() &&
            text("sourceOwnerUserId") == (snapshot.ownerUserId?.toString() ?: "PUBLIC") &&
            text("extractorVersion") == snapshot.extractorVersion &&
            (text("difficulty") ?: "DEFAULT") == (difficulty?.name ?: "DEFAULT")
    }.getOrDefault(false)

    private suspend fun ensureQuizConversation(ctx: AccessContext, scopeRequest: ScopeRequest): com.example.myapplication.data.local.entity.AiConversationEntity {
        val existing = conversations.listConversations(
            userId = ctx.userId,
            workflow = ConversationRepository.WORKFLOW_QUIZ,
            includeArchived = true,
            limit = 20,
        ).firstOrNull { it.archivedAt == null }
        if (existing != null) return existing
        return conversations.createConversation(
            userId = ctx.userId,
            title = "随堂测验",
            workflow = ConversationRepository.WORKFLOW_QUIZ,
            documentId = scopeRequest.documentIds.firstOrNull(),
        )
    }

    // ------------------------------------------------------------- pipeline

    private suspend fun runPipeline(
        ctx: AccessContext,
        runId: String,
        paperId: String,
        documentId: String,
        pinnedRevision: String,
        scopeJson: String,
        template: QuizTemplateSpec,
        difficulty: QuizDifficulty?,
        requestKey: String,
    ) {
        try {
            withTimeout(totalDeadlineMs) {
                runPipelineWithinBudget(ctx, runId, paperId, documentId, pinnedRevision, scopeJson, template, difficulty, requestKey)
            }
        } catch (_: TimeoutCancellationException) {
            withContext(NonCancellable) {
                if (database.aiRunDao().findRun(ctx.userId, runId)?.status == STATUS_RUNNING)
                    failRun(ctx, runId, paperId, "GENERATION_TIMEOUT", "AI 生成超时，可先使用原文练习。", emptyList())
            }
        }
    }

    private suspend fun runPipelineWithinBudget(
        ctx: AccessContext,
        runId: String,
        paperId: String,
        documentId: String,
        pinnedRevision: String,
        scopeJson: String,
        template: QuizTemplateSpec,
        difficulty: QuizDifficulty?,
        requestKey: String,
    ) {
        try {
            stage(runId, STAGE_READING, paperId)
            val loaded = loadSections(ctx, documentId, pinnedRevision, scopeJson)
            val sections = loaded.sections
            if (sections.isEmpty()) {
                failRun(ctx, runId, paperId, "QUIZ_INVALID", "NO_READABLE_SOURCE", emptyList())
                return
            }
            stage(runId, STAGE_PLANNING, paperId)
            val plan = planner.plan(
                QuizPlanRequest(
                    sourceRevision = pinnedRevision,
                    scopeJson = scopeJson,
                    sections = sections,
                    template = template,
                    // Complete bounded source reads and model excerpts have separate budgets.
                    maxPromptChars = MAX_SOURCE_TEXT_CHARS,
                ),
            )
            var coverage = generationCoverage(plan, loaded)
            database.openHelper.writableDatabase.execSQL(
                "UPDATE quiz_papers SET generation_coverage_json = ? WHERE id = ? AND user_id = ? AND status IN ('GENERATING','VALIDATING')",
                arrayOf<Any?>(coverage.toString(), paperId, ctx.userId))
            if (!plan.sufficient || plan.slots.isEmpty()) {
                // 计划级缺陷（预算截断了全章 / 覆盖章节内没有知识点 / 没有题槽）是“计划算出来的”，
                // 修复轮只重写题目，无法改变计划，因此在任何模型调用前收尾。
                val reason = if (plan.slots.isEmpty()) {
                    "计划没有可生成的题槽（模板题数为 0），不能出卷"
                } else {
                    plan.coverageInsufficiencyMessage()
                }
                failRun(ctx, runId, paperId, "COVERAGE_INSUFFICIENT", reason, emptyList())
                return
            }
            val evidence = buildEvidence(ctx, documentId, pinnedRevision, loaded, scopeJson)
            coverage = generationCoverage(plan, loaded)
            database.openHelper.writableDatabase.execSQL(
                "UPDATE quiz_papers SET generation_coverage_json = ? WHERE id = ? AND user_id = ? AND status IN ('GENERATING','VALIDATING')",
                arrayOf<Any?>(coverage.toString(), paperId, ctx.userId))
            var repairRound = 0
            var lastIssues: List<ValidationIssue> = emptyList()
            while (true) {
                if (isCancelled(runId) || paperStatus(paperId, ctx.userId) !in PUBLISHABLE) {
                    markCancelled(ctx, runId, paperId)
                    return
                }
                stage(runId, if (repairRound == 0) STAGE_GENERATING else STAGE_REPAIR, paperId)
                val result = draftSource!!.generate(
                    QuizDraftRequest(
                        generationRunId = runId,
                        paperId = paperId,
                        userId = ctx.userId,
                        requestKey = requestKey,
                        sourceRevision = pinnedRevision,
                        scopeJson = scopeJson,
                        template = template,
                        plan = plan,
                        difficulty = difficulty,
                        repairRound = repairRound,
                        previousIssues = lastIssues,
                        sections = sections,
                        evidence = evidence,
                        isCancelled = { isCancelled(runId) || paperStatus(paperId, ctx.userId) !in PUBLISHABLE },
                    ),
                )
                when (result) {
                    QuizDraftSourceResult.Cancelled -> {
                        markCancelled(ctx, runId, paperId)
                        return
                    }

                    is QuizDraftSourceResult.CapabilityUnsupported -> {
                        failRun(ctx, runId, paperId, "CAPABILITY_UNSUPPORTED", result.message, emptyList())
                        return
                    }

                    is QuizDraftSourceResult.Failed -> {
                        failRun(ctx, runId, paperId, result.code, result.message, emptyList())
                        return
                    }

                    is QuizDraftSourceResult.Draft -> {
                        if (isCancelled(runId)) {
                            markCancelled(ctx, runId, paperId)
                            return
                        }
                        stage(runId, STAGE_VALIDATING, paperId)
                        setPaperStatus(paperId, ctx.userId, STATUS_VALIDATING, setOf(STATUS_GENERATING))
                        val resolver = { id: String -> evidence[id] }
                        val validator = QuizValidator(
                            template = template,
                            evidenceResolver = resolver,
                            plan = plan,
                            expectedSourceRevision = pinnedRevision,
                        )
                        val validation = validator.validateDraft(
                            rawJson = result.rawJson,
                            sourceRevision = pinnedRevision,
                            scopeJson = scopeJson,
                        )
                        if (!validation.isValid) {
                            lastIssues = validation.report.issues
                            if (repairRound >= MAX_REPAIR_ROUNDS) {
                                failRun(ctx, runId, paperId, "QUIZ_INVALID", "VALIDATION_FAILED", lastIssues)
                                return
                            }
                            repairRound += 1
                            setPaperStatus(paperId, ctx.userId, STATUS_GENERATING, setOf(STATUS_VALIDATING))
                            continue
                        }
                        val paper = validation.paper?.copy(coverageJson = coverage.toString())
                            ?: run {
                                failRun(ctx, runId, paperId, "QUIZ_INVALID", "PAPER_MISSING", validation.report.issues)
                                return
                            }
                        // 发布前复核固定 revision（源被替换/删除 → SOURCE_CHANGED）。
                        val current = catalog.findDocumentIncludingDeleted(documentId)
                        if (current == null || current.deletedAt != null || current.contentRevision != pinnedRevision) {
                            failRun(
                                ctx,
                                runId,
                                paperId,
                                "SOURCE_CHANGED",
                                "source revision changed during generation",
                                emptyList(),
                            )
                            return
                        }
                        val bound = QuizEvidenceBinder(resolver, pinnedRevision).bindPaper(paper)
                        val assessments = paper.items.mapIndexed { index, item ->
                            supportAssessor.assess(item, bound.byItem[index]?.refs.orEmpty(), pinnedRevision,
                                fact = auditedFactProvider(item), structureValid = bound.isValid,
                                coverageComplete = !plan.truncated)
                        }
                        verifyCurrentSource(ctx, documentId, pinnedRevision, scopeJson)
                        if (assessments.any { it.state == QuizSupportState.UNSUPPORTED } && repairRound < MAX_REPAIR_ROUNDS) {
                            lastIssues = assessments.mapIndexedNotNull { index, assessment ->
                                if (assessment.state != QuizSupportState.UNSUPPORTED) null else ValidationIssue(
                                    com.example.myapplication.domain.quiz.QuizValidationCode.STRUCTURE_INVALID,
                                    index, "support", assessment.reasonCodes.joinToString(","))
                            }
                            repairRound++
                            setPaperStatus(paperId, ctx.userId, STATUS_GENERATING, setOf(STATUS_VALIDATING))
                            continue
                        }
                        if (assessments.any { it.state != QuizSupportState.SUPPORTED } || reviewDraftStore != null) {
                            val store = reviewDraftStore
                            if (store == null) {
                                failRun(ctx, runId, paperId, "REVIEW_STORAGE_UNAVAILABLE", "Support review required; private draft storage is unavailable", emptyList())
                                return
                            }
                            val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT).apply {
                                timeZone = java.util.TimeZone.getTimeZone("UTC")
                            }.format(java.util.Date(clock()))
                            val reference = store.save(QuizReviewDraftStore.Draft(
                                paperId, ctx.userId, pinnedRevision,
                                kotlinx.serialization.json.Json.parseToJsonElement(scopeJson).jsonObject,
                                paper.items,
                                kotlinx.serialization.json.JsonArray(assessments.map { QuizSupportAssessor.encodeAssessment(it) }),
                                kotlinx.serialization.json.Json.parseToJsonElement(com.example.myapplication.domain.quiz.QuizCodecs.encodeEvidence(bound.byItem.values.flatMap { it.refs }.distinctBy { it.evidenceId })) as kotlinx.serialization.json.JsonArray,
                                now, now))
                            val reviewCoverage = store.mergeCoverage(coverage, reference)
                            database.openHelper.writableDatabase.execSQL(
                                "UPDATE quiz_papers SET generation_coverage_json = ? WHERE id = ? AND user_id = ? AND status IN ('GENERATING','VALIDATING')",
                                arrayOf<Any?>(reviewCoverage.toString(), paperId, ctx.userId))
                            if (assessments.any { it.state != QuizSupportState.SUPPORTED }) {
                                failRun(ctx, runId, paperId, "REVIEW_REQUIRED", assessments.flatMap { it.reasonCodes }.distinct().joinToString(","), emptyList())
                                return
                            }
                        }
                        stage(runId, STAGE_PUBLISHING, paperId)
                        val chapterId = resolveChapterId(documentId)
                        val outcome = publisher.publish(
                            QuizPublisher.Request(
                                context = ctx,
                                paperId = paperId,
                                paper = paper,
                                bound = bound,
                                documentId = documentId,
                                chapterId = chapterId,
                                generationRunId = runId,
                                sourceRevision = pinnedRevision,
                                generatedBy = "AI",
                                supportAssessments = assessments,
                            ),
                        )
                        when (outcome) {
                            is QuizPublisher.Outcome.Published -> {
                                stage(runId, STAGE_READY, paperId, questionCount = outcome.questionCount, maxScore = outcome.maxScore)
                                finishRun(ctx, runId, STATUS_COMPLETED)
                            }

                            is QuizPublisher.Outcome.Rejected -> failRun(
                                ctx,
                                runId,
                                paperId,
                                outcome.code,
                                outcome.message,
                                outcome.issues,
                            )
                        }
                        return
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (cancelled is TimeoutCancellationException)
                    failRun(ctx, runId, paperId, "GENERATION_TIMEOUT", "AI 生成超时，可先使用原文练习。", emptyList())
                else markCancelled(ctx, runId, paperId)
            }
            throw cancelled
        } catch (budgetExceeded: SourceBudgetExceededException) {
            withContext(NonCancellable) {
                failRun(ctx, runId, paperId, "SOURCE_IO_BUDGET_EXCEEDED", "原文读取已达本次预算，可缩小章节范围或使用原文练习。", emptyList())
            }
        } catch (sourceChanged: SourceChangedException) {
            withContext(NonCancellable) {
                failRun(ctx, runId, paperId, "SOURCE_CHANGED", sourceChanged.message.orEmpty(), emptyList())
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                failRun(ctx, runId, paperId, "QUIZ_INVALID", error.javaClass.simpleName, emptyList())
            }
        } finally {
            withContext(NonCancellable) {
                val run = database.aiRunDao().findRun(ctx.userId, runId)
                if (run != null && run.status == STATUS_RUNNING) {
                    // 兜底：未显式终态时按 INTERRUPTED 处理，绝不保持可复用 RUNNING 租约。
                    finishRun(ctx, runId, STATUS_INTERRUPTED)
                    publisher.markStatus(paperId, ctx.userId, STATUS_INTERRUPTED, PUBLISHABLE)
                }
            }
        }
    }

    // ------------------------------------------------------------ cancel/recover

    suspend fun cancelGeneration(ctx: AccessContext, generationRunId: String): Boolean {
        val run = database.aiRunDao().findRun(ctx.userId, generationRunId) ?: return false
        if (run.status in TERMINAL_RUN_STATUSES) return false
        jobs[generationRunId]?.cancel()
        withContext(NonCancellable) { markCancelled(ctx, run.id, findPaperIdByRun(run.id)) }
        return true
    }

    /**
     * 进程启动时对**所有用户**做一次未终态回收（P1-2：此前 `recoverInterrupted` 无生产调用点，
     * 被杀进程留下的 GENERATING 试卷会永久卡住）。启动时登录态可能尚未就绪，因此不依赖
     * SessionManager，而是直接从 quiz_papers 反查有哪些用户需要收敛。
     */
    suspend fun recoverInterruptedForAllUsers(): Int {
        val userIds = database.openHelper.readableDatabase.query(
            "SELECT DISTINCT user_id FROM quiz_papers WHERE status IN ('GENERATING','VALIDATING')",
            emptyArray<Any?>(),
        ).use { cursor ->
            val ids = mutableListOf<Long>()
            while (cursor.moveToNext()) ids += cursor.getLong(0)
            ids
        }
        var recovered = 0
        for (userId in userIds) {
            runCatching { recoverInterrupted(userId) }.onSuccess { recovered += it }
        }
        return recovered
    }

    /**
     * 启动恢复（模拟进程被杀）：未终态 run → INTERRUPTED，paper 从 GENERATING/VALIDATING
     * → INTERRUPTED；不重新生成、不重新扣费。返回处理的 paper 数。
     */
    suspend fun recoverInterrupted(userId: Long): Int {
        val now = clock()
        val paperRows = database.openHelper.readableDatabase.query(
            "SELECT id, generation_run_id FROM quiz_papers WHERE user_id = ? AND status IN ('GENERATING','VALIDATING')",
            arrayOf<Any?>(userId),
        ).use { cursor ->
            val rows = mutableListOf<Pair<String, String?>>()
            while (cursor.moveToNext()) {
                rows += cursor.getString(0) to if (cursor.isNull(1)) null else cursor.getString(1)
            }
            rows
        }
        var recovered = 0
        for ((paperId, runId) in paperRows) {
            if (!publisher.markStatus(paperId, userId, STATUS_INTERRUPTED, PUBLISHABLE)) continue
            recovered += 1
            if (runId != null) {
                val run = database.aiRunDao().findRun(userId, runId)
                database.aiRunDao().updateRunState(
                    userId = userId,
                    runId = runId,
                    status = STATUS_INTERRUPTED,
                    activeSlot = null,
                    rounds = run?.rounds ?: 0,
                    tokenUsage = run?.tokenUsage ?: 0,
                    lastEventSeq = database.aiRunDao().maxEventSeq(runId) + 1,
                    now = now,
                )
                emitEvent(runId, EVENT_RUN_INTERRUPTED, buildJsonObject { put("paperId", paperId) })
            }
        }
        return recovered
    }

    /** 测试/UI：等待 run 进入终态（轮询 DB，最多 [timeoutMs]）。 */
    suspend fun awaitTerminal(generationRunId: String, timeoutMs: Long = 10_000): String? {
        val deadline = clock() + timeoutMs
        while (clock() <= deadline) {
            val run = database.openHelper.readableDatabase.query(
                "SELECT status FROM ai_runs WHERE id = ? LIMIT 1",
                arrayOf<Any?>(generationRunId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(0)
            }
            if (run == null) return null
            if (run in TERMINAL_RUN_STATUSES) return run
            delay(20)
        }
        return null
    }

    /**
     * 生成进度快照（UI 约定，供 task-14 生成页/历史页消费）。
     *
     * - [paperId] 在 REGISTERED 阶段即可用（GENERATING 占位已落库），UI 可直接以它打开生成页；
     * - [stage]：REGISTERED→READING→PLANNING→GENERATING→（GENERATING_REPAIR）→VALIDATING→
     *   PUBLISHING→READY；失败/终止为 FAILED / CANCELLED / INTERRUPTED；
     * - [ready] 仅当 paper.status=READY 时为 true（未校验通过不得 READY）；
     * - [errorCode]/[message]：FAILED 时的结构化原因（QUIZ_INVALID/SOURCE_CHANGED/…），
     *   [terminal] 为 true 后 UI 应停止收集并刷新历史列表。
     */
    data class GenerationProgress(
        val generationRunId: String,
        val paperId: String?,
        val stage: String,
        val message: String = "",
        val errorCode: String? = null,
        val paperStatus: String? = null,
        val questionCount: Int = 0,
        val maxScore: Int = 0,
        val ready: Boolean = false,
        val terminal: Boolean = false,
    )

    /**
     * 订阅一次 QUIZ 生成进度（UI 可直接 collect；Flow 每 [PROGRESS_POLL_MS] 轮询 DB，
     * 进入终态后自动结束）。只允许本人 run：找不到或非本人返回 null 快照后结束。
     *
     * 纯读取接口：不启动生成、不改状态；与 [tools] 的 generationRunId 一致。
     */
    fun observeGeneration(userId: Long, generationRunId: String): Flow<GenerationProgress> = flow {
        while (true) {
            val progress = readProgress(userId, generationRunId)
            if (progress == null) {
                emit(
                    GenerationProgress(
                        generationRunId = generationRunId,
                        paperId = null,
                        stage = PROGRESS_UNKNOWN,
                        message = "generation run not found or not owned by user",
                        terminal = true,
                    ),
                )
                return@flow
            }
            emit(progress)
            if (progress.terminal) return@flow
            delay(PROGRESS_POLL_MS)
        }
    }

    private fun readProgress(userId: Long, generationRunId: String): GenerationProgress? {
        val runStatus = database.openHelper.readableDatabase.query(
            "SELECT status FROM ai_runs WHERE id = ? AND user_id = ? LIMIT 1",
            arrayOf<Any?>(generationRunId, userId),
        ).use { cursor -> if (!cursor.moveToFirst()) null else cursor.getString(0) }
            ?: return null
        val paper = findPaperStatusRowByRun(generationRunId)
        var stage: String? = null
        var message = ""
        var errorCode: String? = null
        database.openHelper.readableDatabase.query(
            "SELECT type, payload_json FROM ai_run_events WHERE run_id = ? AND type IN (?, ?) ORDER BY seq ASC",
            arrayOf<Any?>(generationRunId, EVENT_STAGE, EVENT_RUN_ERROR),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getString(0)
                val payload = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(cursor.getString(1)).jsonObject
                }.getOrNull() ?: continue
                when (type) {
                    EVENT_STAGE -> stage = (payload["stage"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    EVENT_RUN_ERROR -> {
                        errorCode = (payload["code"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                        message = (payload["message"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
                    }
                }
            }
        }
        val resolvedStage = when (runStatus) {
            STATUS_CANCELLED -> STAGE_CANCELLED
            STATUS_INTERRUPTED -> STAGE_INTERRUPTED
            STATUS_ERROR -> STAGE_FAILED
            STATUS_COMPLETED -> if (paper?.status == "READY") STAGE_READY else stage ?: STAGE_PUBLISHING
            else -> stage ?: STAGE_REGISTERED
        }
        val terminal = runStatus in TERMINAL_RUN_STATUSES
        return GenerationProgress(
            generationRunId = generationRunId,
            paperId = paper?.paperId,
            stage = resolvedStage,
            message = message,
            errorCode = errorCode,
            paperStatus = paper?.status,
            questionCount = paper?.questionCount ?: 0,
            maxScore = paper?.maxScore ?: 0,
            ready = paper?.status == "READY",
            terminal = terminal,
        )
    }

    fun activeJobCount(): Int = jobs.size

    // ------------------------------------------------------------- agent tools

    /** create_quiz_draft / get_quiz_status（QUIZ_PRODUCT 组，非只读学习产物）。 */
    fun tools(): List<AgentTool> = listOf(CreateQuizDraftTool(), GetQuizStatusTool())

    private inner class CreateQuizDraftTool : AgentTool {
        override val spec: ToolSpec = ToolSpec(
            name = "create_quiz_draft",
            group = ToolGroup.QUIZ_PRODUCT,
            description = "按固定当前 scope 生成随堂测草稿（独立 QUIZ 运行；返回 generationRunId/paperId）。",
            readOnly = false,
            parameters = boundedObject(
                required = listOf("documentId"),
                properties = mapOf(
                    "documentId" to stringSchema(1, 256),
                    "templateId" to stringSchema(1, 64),
                    "difficulty" to stringSchema(1, 16),
                    "sectionIds" to buildJsonObject {
                        put("type", "array"); put("maxItems", MAX_SECTIONS)
                        put("items", stringSchema(1, 256))
                    },
                ),
            ),
            maxArgumentBytes = 2 * 1024,
        )

        override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult {
            if (draftSource == null) {
                return ToolResult.failure(
                    "",
                    spec.name,
                    ToolError(ToolErrorCode.CAPABILITY_UNSUPPORTED, "quiz generation is not configured"),
                )
            }
            return try {
                val documentId = ToolArgs.requireString(arguments, "documentId", 1, 256)
                val templateId = ToolArgs.optionalString(arguments, "templateId", 64)
                    ?: QuizTemplate.CHAPTER_QUIZ_V1_ID
                val difficulty = ToolArgs.optionalString(arguments, "difficulty", 16)?.let { raw ->
                    QuizDifficulty.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                        ?: throw ToolArgumentException("difficulty", "unknown difficulty: $raw")
                }
                val sectionIds = when (val raw = arguments["sectionIds"]) {
                    null -> emptySet()
                    is kotlinx.serialization.json.JsonArray -> {
                        if (raw.size > MAX_SECTIONS) throw ToolArgumentException("sectionIds", "too many sections")
                        raw.map { value ->
                            val text = (value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                                ?: throw ToolArgumentException("sectionIds", "section IDs must be strings")
                            if (text.isBlank() || text.length > 256) throw ToolArgumentException("sectionIds", "invalid section ID")
                            text
                        }.toSet()
                    }
                    else -> throw ToolArgumentException("sectionIds", "sectionIds must be an array")
                }
                val requestKey = "quiz-tool:" + AgentHashing.sha256(
                    context.runId + "|" + arguments.toString(),
                ).take(40)
                val outcome = startGeneration(
                    ctx = context.accessContext(),
                    sourceScope = ScopeRequest(listOf(documentId), if (sectionIds.isEmpty()) ReadingScope.CURRENT_DOCUMENT else ReadingScope.CURRENT_SECTION),
                    templateId = templateId,
                    difficulty = difficulty,
                    requestKey = requestKey,
                    sectionIds = sectionIds,
                )
                when (outcome) {
                    is StartOutcome.Started -> ToolResult.success(
                        "",
                        spec.name,
                        buildJsonObject {
                            put("generationRunId", outcome.generationRunId)
                            put("paperId", outcome.paperId)
                            put("status", STATUS_RUNNING)
                        },
                    )

                    is StartOutcome.Existing -> ToolResult.success(
                        "",
                        spec.name,
                        buildJsonObject {
                            put("generationRunId", outcome.generationRunId)
                            outcome.paperId?.let { put("paperId", it) }
                            put("status", outcome.status)
                            put("existing", true)
                        },
                    )

                    is StartOutcome.Busy -> ToolResult.failure(
                        "",
                        spec.name,
                        ToolError(
                            ToolErrorCode.QUIZ_INVALID,
                            "another quiz generation is active: ${outcome.activeRunId}",
                            retryable = false,
                        ),
                    )

                    is StartOutcome.Rejected -> ToolResult.failure(
                        "",
                        spec.name,
                        ToolError(mapStartRejection(outcome.code), "quiz generation rejected: ${outcome.code}"),
                    )
                }
            } catch (error: ToolArgumentException) {
                ToolResult.failure(
                    "",
                    spec.name,
                    ToolError(ToolErrorCode.INVALID_ARGUMENT, error.message.orEmpty(), true, error.field),
                )
            }
        }
    }

    private inner class GetQuizStatusTool : AgentTool {
        override val spec: ToolSpec = ToolSpec(
            name = "get_quiz_status",
            group = ToolGroup.QUIZ_PRODUCT,
            description = "按 paperId 或 generationRunId 查询本用户随堂测生成状态/失败原因（owner 校验）。",
            readOnly = false,
            parameters = boundedObject(
                required = emptyList(),
                properties = mapOf(
                    "paperId" to stringSchema(1, 256),
                    "generationRunId" to stringSchema(1, 256),
                ),
            ),
            maxArgumentBytes = 1 * 1024,
        )

        override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult {
            return try {
                val paperId = ToolArgs.optionalString(arguments, "paperId", 256)
                val runId = ToolArgs.optionalString(arguments, "generationRunId", 256)
                if ((paperId == null) == (runId == null)) {
                    throw ToolArgumentException("target", "exactly one of paperId/generationRunId is required")
                }
                val row = if (paperId != null) {
                    findPaperStatusRow(paperId)
                } else {
                    findPaperStatusRowByRun(runId!!)
                } ?: return ToolResult.failure(
                    "",
                    spec.name,
                    ToolError(ToolErrorCode.NOT_FOUND, "quiz paper not found"),
                )
                if (row.userId != context.userId) {
                    return ToolResult.failure(
                        "",
                        spec.name,
                        ToolError(ToolErrorCode.ACCESS_REVOKED, "quiz paper belongs to another user"),
                    )
                }
                val failure = latestFailure(row.generationRunId)
                ToolResult.success(
                    "",
                    spec.name,
                    buildJsonObject {
                        put("paperId", row.paperId)
                        row.generationRunId?.let { put("generationRunId", it) }
                        put("status", row.status)
                        put("questionCount", row.questionCount)
                        put("maxScore", row.maxScore)
                        put("ready", row.status == "READY")
                        failure?.let { put("failure", it) }
                    },
                )
            } catch (error: ToolArgumentException) {
                ToolResult.failure(
                    "",
                    spec.name,
                    ToolError(ToolErrorCode.INVALID_ARGUMENT, error.message.orEmpty(), true, error.field),
                )
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private data class PaperStatusRow(
        val paperId: String,
        val userId: Long,
        val status: String,
        val questionCount: Int,
        val maxScore: Int,
        val generationRunId: String?,
    )

    private fun findPaperStatusRow(paperId: String): PaperStatusRow? =
        database.openHelper.readableDatabase.query(
            "SELECT id, user_id, status, question_count, max_score, generation_run_id FROM quiz_papers WHERE id = ? LIMIT 1",
            arrayOf<Any?>(paperId),
        ).use { cursor -> if (!cursor.moveToFirst()) null else cursor.toPaperStatusRow() }

    private fun findPaperStatusRowByRun(generationRunId: String): PaperStatusRow? =
        database.openHelper.readableDatabase.query(
            "SELECT id, user_id, status, question_count, max_score, generation_run_id FROM quiz_papers " +
                "WHERE generation_run_id = ? ORDER BY created_at DESC LIMIT 1",
            arrayOf<Any?>(generationRunId),
        ).use { cursor -> if (!cursor.moveToFirst()) null else cursor.toPaperStatusRow() }

    private fun android.database.Cursor.toPaperStatusRow() = PaperStatusRow(
        paperId = getString(0),
        userId = getLong(1),
        status = getString(2),
        questionCount = getInt(3),
        maxScore = getInt(4),
        generationRunId = if (isNull(5)) null else getString(5),
    )

    private fun latestFailure(generationRunId: String?): JsonObject? {
        if (generationRunId == null) return null
        return database.openHelper.readableDatabase.query(
            "SELECT payload_json FROM ai_run_events WHERE run_id = ? AND type = ? ORDER BY seq DESC LIMIT 1",
            arrayOf<Any?>(generationRunId, EVENT_RUN_ERROR),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(cursor.getString(0)).jsonObject
            }.getOrNull()
        }
    }

    private data class LoadedSource(
        val sections: List<SourceSection>,
        val reads: Map<String, SectionRead>,
        val sourceBudget: SourceReadBudget,
        val textChars: Int,
    )

    private suspend fun loadSections(
        ctx: AccessContext,
        documentId: String,
        pinnedRevision: String,
        scopeJson: String,
    ): LoadedSource {
        val opened = documentAccess.open(ctx, documentId, pinnedRevision)
        if (opened !is OperationResult.Ok) {
            throw SourceChangedException("source not openable: ${(opened as? OperationResult.Rejected)?.code}")
        }
        val snapshot = opened.value
        verifySnapshot(snapshot, documentId, pinnedRevision, scopeJson)
        val coroutine = currentCoroutineContext()
        val budget = SourceReadBudget(MAX_SOURCE_READ_BYTES, { coroutine.ensureActive() })
        val sections = mutableListOf<SourceSection>()
        val reads = linkedMapOf<String, SectionRead>()
        var totalChars = 0
        val selectedIds = (kotlinx.serialization.json.Json.parseToJsonElement(scopeJson).jsonObject["sectionIds"] as? kotlinx.serialization.json.JsonArray)
            ?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }?.toSet().orEmpty()
        val descriptors = allDescriptors(ctx, snapshot)
        if (selectedIds.any { id -> descriptors.none { it.id == id } }) throw SourceChangedException("selected section disappeared")
        descriptors.filter { selectedIds.isEmpty() || it.id in selectedIds }.forEachIndexed { index, descriptor ->
            if (index >= MAX_SECTIONS || totalChars >= MAX_SOURCE_TEXT_CHARS || budget.remaining <= 0) {
                sections += SourceSection(descriptor.id, descriptor.title, "", descriptor.orderIndex, fullyRead = false)
                return@forEachIndexed
            }
            val read = readSectionBlocks(ctx, snapshot.snapshotId, descriptor.id, budget, MAX_SOURCE_TEXT_CHARS - totalChars)
            reads[descriptor.id] = read
            val blocks = read.blocks
            val quality = when {
                blocks.any { it.extractionQuality == ExtractionQuality.NO_TEXT } -> SectionQuality.EXTRACTION_BLOCKED
                blocks.any { it.extractionQuality == ExtractionQuality.OCR_REVIEW_REQUIRED } -> SectionQuality.LOW_OCR_CONFIDENCE
                else -> SectionQuality.OK
            }
            val fullText = blocks.joinToString("\n") { it.text }
            totalChars += fullText.length
            sections += SourceSection(
                sectionId = descriptor.id,
                heading = descriptor.title,
                text = fullText,
                order = descriptor.orderIndex,
                knowledgePoints = knowledgePointsOf(descriptor.title, blocks),
                quality = quality,
                fullyRead = read.complete && blocks.isNotEmpty(),
            )
        }
        verifySnapshot((documentAccess.open(ctx, documentId, pinnedRevision) as? OperationResult.Ok)?.value
            ?: throw SourceChangedException("source changed after complete read"), documentId, pinnedRevision, scopeJson)
        return LoadedSource(sections, reads, budget, totalChars)
    }

    private fun generationCoverage(plan: QuizGenerationPlan, loaded: LoadedSource): JsonObject = buildJsonObject {
        kotlinx.serialization.json.Json.parseToJsonElement(plan.coverageJson()).jsonObject.forEach { (key, value) -> put(key, value) }
        put("coverageBasis", "COMPLETE_SOURCE_READ")
        put("sourceReadTextChars", loaded.textChars)
        put("sourceReadRawBytes", loaded.sourceBudget.bytesRead)
        put("sourceReadRawBudgetBytes", loaded.sourceBudget.maxBytes)
        put("sourceReadTextBudgetChars", MAX_SOURCE_TEXT_CHARS)
        put("promptTextMode", "REPRESENTATIVE_EXCERPTS")
        put("promptIncludesEntireSource", false)
        putJsonArray("sourceReads") {
            loaded.sections.forEach { section ->
                addJsonObject {
                    put("sectionId", section.sectionId)
                    put("textChars", section.text.length)
                    put("fullyRead", section.fullyRead)
                    put("quality", section.quality.name)
                }
            }
        }
    }

    private fun verifySnapshot(snapshot: com.example.myapplication.domain.reader.DocumentSnapshot, documentId: String, revision: String, scopeJson: String) {
        val frozen = kotlinx.serialization.json.Json.parseToJsonElement(scopeJson).jsonObject
        fun matches(key: String, current: String) = (frozen[key] as? kotlinx.serialization.json.JsonPrimitive)?.content == current
        if (snapshot.documentId != documentId || snapshot.contentRevision != revision ||
            !matches("accessEpoch", snapshot.accessEpoch.toString()) || !matches("metadataRevision", snapshot.metadataRevision.toString()) ||
            !matches("extractorVersion", snapshot.extractorVersion) || !matches("sourceOwnerUserId", snapshot.ownerUserId?.toString() ?: "PUBLIC")) throw SourceChangedException("source identity changed")
    }

    private suspend fun verifyCurrentSource(ctx: AccessContext, documentId: String, revision: String, scopeJson: String) {
        val doc = catalog.findDocumentIncludingDeleted(documentId)
        if (doc == null || doc.deletedAt != null || doc.contentRevision != revision) throw SourceChangedException("source changed during support review")
        val opened = documentAccess.open(ctx, documentId, revision)
        if (opened !is OperationResult.Ok) throw SourceChangedException("source access changed during support review")
        verifySnapshot(opened.value, documentId, revision, scopeJson)
    }

    private suspend fun allDescriptors(ctx: AccessContext, snapshot: com.example.myapplication.domain.reader.DocumentSnapshot): List<com.example.myapplication.domain.reader.SectionDescriptor> {
        val descriptors = snapshot.sections.toMutableList()
        var cursor = snapshot.sectionsNextCursor
        val seen = mutableSetOf<String>()
        while (cursor != null) {
            if (!seen.add(cursor) || descriptors.size >= 1024) throw SourceChangedException("section metadata budget exhausted")
            val result = documentAccess.readSectionDescriptors(ctx, snapshot.snapshotId, cursor, 64)
            if (result !is OperationResult.Ok) throw SourceChangedException("section metadata unavailable")
            descriptors += result.value.items
            cursor = result.value.nextCursor
        }
        if (descriptors.any { it.documentId != snapshot.documentId || it.contentRevision != snapshot.contentRevision }) throw SourceChangedException("section metadata source mismatch")
        if (descriptors.map { it.id }.distinct().size != descriptors.size) throw SourceChangedException("duplicate section identities")
        return descriptors
    }

    private data class ReadPage(val cursor: String?, val blocks: List<ContentBlock>)
    private data class SectionRead(val blocks: List<ContentBlock>, val complete: Boolean, val pages: List<ReadPage>)

    private suspend fun readSectionBlocks(
        ctx: AccessContext,
        snapshotId: String,
        sectionId: String,
        budget: SourceReadBudget,
        maxTextChars: Int,
    ): SectionRead {
        val blocks = mutableListOf<ContentBlock>()
        val seenBlockIds = mutableSetOf<String>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        val readPages = mutableListOf<ReadPage>()
        var pages = 0
        var textChars = 0
        do {
            currentCoroutineContext().ensureActive()
            if (++pages > MAX_PAGES_PER_SECTION || (cursor != null && !seenCursors.add(cursor)))
                return SectionRead(blocks, false, readPages)
            val bounded = documentAccess.readBlocksBounded(ctx, snapshotId, sectionId, cursor, MAX_PAGE_BYTES, budget)
            val result = if (bounded is OperationResult.Failed && bounded.code == "BOUNDED_READ_UNSUPPORTED")
                documentAccess.readBlocks(ctx, snapshotId, sectionId, cursor, MAX_PAGE_BYTES) else bounded
            val page = when (result) {
                is OperationResult.Ok -> result.value
                is OperationResult.Failed -> {
                    if (result.code == "SOURCE_IO_BUDGET_EXCEEDED") return SectionRead(blocks, false, readPages)
                    throw SourceChangedException("source block read failed: " + result.code)
                }
                else -> throw SourceChangedException("source block read rejected")
            }
            readPages += ReadPage(cursor, page.items)
            for (block in page.items) {
                if (block.sectionId != sectionId || block.canonicalEnd - block.canonicalStart != block.text.length ||
                    !seenBlockIds.add(block.blockId) ||
                    blocks.lastOrNull()?.let { block.canonicalStart != it.canonicalEnd + 1 } == true)
                    throw SourceChangedException("source block identity mismatch")
                val extraChars = block.text.length + if (blocks.isEmpty()) 0 else 1
                if (textChars + extraChars > maxTextChars || blocks.size >= MAX_BLOCKS_PER_SECTION)
                    return SectionRead(blocks, false, readPages)
                textChars += extraChars
                blocks += block
            }
            cursor = page.nextCursor
        } while (cursor != null)
        return SectionRead(blocks, true, readPages)
    }

    private fun knowledgePointsOf(heading: String, blocks: List<ContentBlock>): List<String> {
        val points = linkedSetOf<String>()
        blocks.firstOrNull()?.headingPath?.forEach { path ->
            splitKnowledgePoint(path).forEach(points::add)
        }
        splitKnowledgePoint(heading).forEach(points::add)
        if (points.isEmpty() && heading.isNotBlank()) points += heading.trim()
        return points.toList()
    }

    private fun splitKnowledgePoint(raw: String): List<String> = raw
        .split(Regex("[、，。；;：:/／|｜\\s]+"))
        .map { it.trim() }
        .filter { it.length >= 2 && it.length <= 40 }

    private suspend fun buildEvidence(
        ctx: AccessContext,
        documentId: String,
        pinnedRevision: String,
        loaded: LoadedSource,
        scopeJson: String,
    ): Map<String, SourceEvidence> {
        val opened = documentAccess.open(ctx, documentId, pinnedRevision)
        if (opened !is OperationResult.Ok) throw SourceChangedException("source access changed before evidence validation")
        val snapshot = opened.value
        verifySnapshot(snapshot, documentId, pinnedRevision, scopeJson)
        val evidence = linkedMapOf<String, SourceEvidence>()
        val sections = loaded.sections.filter { it.fullyRead && it.quality == SectionQuality.OK }
        val sourceTitle = catalog.findDocumentIncludingDeleted(documentId)?.title.orEmpty()
        suspend fun register(section: SourceSection, requestedStart: Int, requestedEnd: Int) {
            val read = loaded.reads[section.sectionId] ?: return
            val blocks = read.blocks
            if (!read.complete || blocks.isEmpty()) return
            val first = blocks.firstOrNull { it.canonicalEnd > requestedStart } ?: return
            var start = maxOf(requestedStart, first.canonicalStart)
            var end = minOf(requestedEnd, blocks.last().canonicalEnd)
            if (start >= end) return
            if (Character.isLowSurrogate(first.text[start - first.canonicalStart])) start++
            val last = blocks.lastOrNull { it.canonicalStart < end } ?: return
            val lastIndex = minOf(end - last.canonicalStart, last.text.length) - 1
            if (lastIndex >= 0 && Character.isHighSurrogate(last.text[lastIndex])) end--
            if (end <= start) return
            val quote = reconstruct(blocks, start, end)
            if (quote.isBlank() || evidence.values.any { it.sectionId == section.sectionId && it.quote == quote }) return
            // Revalidate relevant cached pages under the shared raw budget; do not reread every section in full.
            val firstPage = read.pages.firstOrNull { page -> page.blocks.any { it.blockId == first.blockId } }
                ?: throw SourceChangedException("source evidence page missing")
            var cursor = firstPage.cursor
            val freshBlocks = mutableListOf<ContentBlock>()
            val cursors = mutableSetOf<String?>()
            do {
                currentCoroutineContext().ensureActive()
                if (!cursors.add(cursor)) throw SourceChangedException("source evidence cursor repeated")
                val bounded = documentAccess.readBlocksBounded(ctx, snapshot.snapshotId, section.sectionId,
                    cursor, MAX_PAGE_BYTES, loaded.sourceBudget)
                val result = if (bounded is OperationResult.Failed && bounded.code == "BOUNDED_READ_UNSUPPORTED")
                    documentAccess.readBlocks(ctx, snapshot.snapshotId, section.sectionId, cursor, MAX_PAGE_BYTES) else bounded
                if (result is OperationResult.Failed && result.code == "SOURCE_IO_BUDGET_EXCEEDED")
                    throw SourceBudgetExceededException()
                val page = (result as? OperationResult.Ok)?.value
                    ?: throw SourceChangedException("source evidence could not be revalidated")
                for (block in page.items) {
                    if (block.canonicalEnd <= start || block.canonicalStart >= end) continue
                    if (block.extractionQuality != ExtractionQuality.VERIFIED_TEXT ||
                        blocks.firstOrNull { it.blockId == block.blockId } != block)
                        throw SourceChangedException("source evidence changed during validation")
                    freshBlocks += block
                }
                if (freshBlocks.lastOrNull()?.canonicalEnd?.let { it >= end } == true) break
                cursor = page.nextCursor
            } while (cursor != null && cursors.size <= MAX_PAGES_PER_SECTION)
            if (reconstruct(freshBlocks, start, end) != quote) throw SourceChangedException("source evidence quote changed")
            val locator = ReaderLocator.Reflow(
                documentId = documentId,
                sectionId = section.sectionId,
                contentRevision = pinnedRevision,
                progression = null,
                nativeLocator = first.nativeLocator,
                exactQuote = quote,
                canonicalStart = start,
                canonicalEnd = end,
                blockId = first.blockId,
            )
            val id = "S" + (evidence.size + 1)
            evidence[id] = SourceEvidence(id, documentId, section.sectionId, pinnedRevision, locator,
                quote, AgentHashing.sha256(quote), sourceTitle, snapshot.extractorVersion)
        }
        // Preserve first-pass S1/S2/... section numbering; append middle and end excerpts afterward.
        for (section in sections) {
            val blocks = loaded.reads[section.sectionId]?.blocks.orEmpty()
            if (blocks.isEmpty()) continue
            val start = blocks.first().canonicalStart
            register(section, start, minOf(blocks.last().canonicalEnd, start + MAX_EVIDENCE_CHARS))
        }
        for (section in sections) {
            val blocks = loaded.reads[section.sectionId]?.blocks.orEmpty()
            if (blocks.isEmpty()) continue
            val base = blocks.first().canonicalStart
            val end = blocks.last().canonicalEnd
            if (end - base <= MAX_EVIDENCE_CHARS) continue
            for (start in listOf(base + (end - base) / 2, maxOf(base, end - MAX_EVIDENCE_CHARS))) {
                register(section, start, minOf(end, start + MAX_EVIDENCE_CHARS))
            }
        }
        verifySnapshot((documentAccess.open(ctx, documentId, pinnedRevision) as? OperationResult.Ok)?.value
            ?: throw SourceChangedException("source changed after evidence validation"), documentId, pinnedRevision, scopeJson)
        return evidence
    }

    private fun reconstruct(blocks: List<ContentBlock>, start: Int, end: Int): String {
        val builder = StringBuilder()
        for (block in blocks) {
            if (block.canonicalEnd <= start) continue
            if (block.canonicalStart >= end) break
            val localStart = maxOf(start, block.canonicalStart) - block.canonicalStart
            val localEnd = minOf(end, block.canonicalEnd) - block.canonicalStart
            if (localEnd <= localStart) continue
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(block.text, localStart, localEnd)
        }
        return builder.toString()
    }

    private suspend fun resolveChapterId(documentId: String): Long? {
        val document = catalog.findDocumentIncludingDeleted(documentId) ?: return null
        if (document.kind != "BUILTIN") return null
        val chapterId = document.legacyChapterId ?: return null
        return database.chapterDao().findById(chapterId)?.id
    }

    private suspend fun failRun(
        ctx: AccessContext,
        runId: String,
        paperId: String,
        code: String,
        message: String,
        issues: List<ValidationIssue>,
    ) {
        val payload = buildJsonObject {
            put("paperId", paperId)
            put("code", code)
            put("message", message)
            putJsonArray("issues") {
                issues.take(MAX_RECORDED_ISSUES).forEach { issue ->
                    addJsonObject {
                        put("code", issue.code.name)
                        issue.itemIndex?.let { put("itemIndex", it) }
                        issue.field?.let { put("field", it) }
                        put("message", issue.message)
                    }
                }
            }
        }.toString()
        emitEvent(runId, EVENT_RUN_ERROR, kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject)
        publisher.markStatus(paperId, ctx.userId, STATUS_FAILED, PUBLISHABLE)
        finishRun(ctx, runId, STATUS_ERROR)
    }

    private suspend fun markCancelled(ctx: AccessContext, runId: String, paperId: String?) = cancellations.withLock {
        val run = database.aiRunDao().findRun(ctx.userId, runId) ?: return@withLock
        if (run.status in TERMINAL_RUN_STATUSES) return@withLock
        if (paperId != null) {
            publisher.markStatus(paperId, ctx.userId, STATUS_CANCELLED, PUBLISHABLE)
        }
        emitEvent(runId, EVENT_RUN_CANCELLED, buildJsonObject { paperId?.let { put("paperId", it) } })
        finishRun(ctx, runId, STATUS_CANCELLED)
    }

    private suspend fun finishRun(ctx: AccessContext, runId: String, status: String) {
        val run = database.aiRunDao().findRun(ctx.userId, runId) ?: return
        if (run.status in TERMINAL_RUN_STATUSES) return
        database.aiRunDao().updateRunState(
            userId = ctx.userId,
            runId = runId,
            status = status,
            activeSlot = null,
            rounds = run.rounds,
            tokenUsage = run.tokenUsage,
            lastEventSeq = database.aiRunDao().maxEventSeq(runId),
            now = clock(),
        )
    }

    private suspend fun stage(
        runId: String,
        stage: String,
        paperId: String,
        questionCount: Int? = null,
        maxScore: Int? = null,
    ) {
        val payload = buildJsonObject {
            put("stage", stage)
            put("paperId", paperId)
            questionCount?.let { put("questionCount", it) }
            maxScore?.let { put("maxScore", it) }
        }
        emitEvent(runId, EVENT_STAGE, payload)
    }

    private suspend fun emitEvent(runId: String, type: String, payload: JsonObject) = events.withLock {
        val seq = database.aiRunDao().maxEventSeq(runId) + 1
        database.aiRunDao().insertRunEvent(
            AiRunEventEntity(
                runId = runId,
                seq = seq,
                type = type,
                payloadJson = payload.toString(),
                createdAt = clock(),
            ),
        )
    }

    private fun isCancelled(runId: String): Boolean = jobs[runId]?.isCancelled == true

    private suspend fun setPaperStatus(paperId: String, userId: Long, status: String, from: Set<String>) {
        publisher.markStatus(paperId, userId, status, from)
    }

    private fun paperStatus(paperId: String, userId: Long): String? =
        database.openHelper.readableDatabase.query(
            "SELECT status FROM quiz_papers WHERE id = ? AND user_id = ? LIMIT 1",
            arrayOf<Any?>(paperId, userId),
        ).use { cursor -> if (!cursor.moveToFirst()) null else cursor.getString(0) }

    private fun findPaperIdByRun(runId: String): String? =
        database.openHelper.readableDatabase.query(
            "SELECT id FROM quiz_papers WHERE generation_run_id = ? LIMIT 1",
            arrayOf<Any?>(runId),
        ).use { cursor -> if (!cursor.moveToFirst()) null else cursor.getString(0) }

    private class SourceChangedException(message: String) : Exception(message)
    private class SourceBudgetExceededException : Exception("SOURCE_IO_BUDGET_EXCEEDED")

    private fun mapStartRejection(code: String): ToolErrorCode = when (code) {
        "ACCESS_REVOKED" -> ToolErrorCode.ACCESS_REVOKED
        "NOT_FOUND" -> ToolErrorCode.NOT_FOUND
        "CAPABILITY_UNSUPPORTED" -> ToolErrorCode.CAPABILITY_UNSUPPORTED
        "QUIZ_INVALID" -> ToolErrorCode.QUIZ_INVALID
        else -> ToolErrorCode.INVALID_ARGUMENT
    }

    private fun stageJson(stage: String, paperId: String): String = buildJsonObject {
        put("stage", stage)
        put("paperId", paperId)
    }.toString()

    companion object {
        const val DEFAULT_TOTAL_DEADLINE_MS = 75_000L
        const val MAX_REQUEST_KEY_CHARS = 128
        const val MAX_REPAIR_ROUNDS = 1
        const val MAX_SECTIONS = 64
        const val MAX_BLOCKS_PER_SECTION = 4096
        const val MAX_PAGES_PER_SECTION = 256
        const val MAX_PAGE_BYTES = 256 * 1024
        const val MAX_SOURCE_TEXT_CHARS = 512 * 1024
        const val MAX_SOURCE_READ_BYTES = 32L * 1024 * 1024
        const val MAX_SECTION_CHARS = MAX_SOURCE_TEXT_CHARS
        const val MAX_EVIDENCE_CHARS = 2_000
        const val MAX_RECORDED_ISSUES = 20

        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_ERROR = "ERROR"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_INTERRUPTED = "INTERRUPTED"

        const val STATUS_GENERATING = "GENERATING"
        const val STATUS_VALIDATING = "VALIDATING"
        const val STATUS_FAILED = "FAILED"

        val PUBLISHABLE = setOf(STATUS_GENERATING, STATUS_VALIDATING)
        val TERMINAL_RUN_STATUSES = setOf(STATUS_COMPLETED, STATUS_ERROR, STATUS_CANCELLED, STATUS_INTERRUPTED)

        const val EVENT_STAGE = "STAGE"
        const val EVENT_RUN_ERROR = "RUN_ERROR"
        const val EVENT_RUN_CANCELLED = "RUN_CANCELLED"
        const val EVENT_RUN_INTERRUPTED = "RUN_INTERRUPTED"

        const val STAGE_REGISTERED = "REGISTERED"
        const val STAGE_READING = "READING"
        const val STAGE_PLANNING = "PLANNING"
        const val STAGE_GENERATING = "GENERATING"
        const val STAGE_REPAIR = "GENERATING_REPAIR"
        const val STAGE_VALIDATING = "VALIDATING"
        const val STAGE_PUBLISHING = "PUBLISHING"
        const val STAGE_READY = "READY"
        const val STAGE_FAILED = "FAILED"
        const val STAGE_CANCELLED = "CANCELLED"
        const val STAGE_INTERRUPTED = "INTERRUPTED"
        const val PROGRESS_UNKNOWN = "UNKNOWN"
        const val PROGRESS_POLL_MS = 250L
    }
}

/** AppContainer/UI 便捷别名：com.example.myapplication.data.quiz.GenerationProgress。 */
typealias GenerationProgress = QuizGenerationCoordinator.GenerationProgress
