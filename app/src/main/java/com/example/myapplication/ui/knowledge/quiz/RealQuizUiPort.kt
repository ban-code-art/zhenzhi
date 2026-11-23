package com.example.myapplication.ui.knowledge.quiz

import com.example.myapplication.data.quiz.GradeAssessmentService
import com.example.myapplication.data.quiz.GradeAssessment
import com.example.myapplication.data.quiz.AiRubricReviewer
import com.example.myapplication.data.quiz.QuizAiReviewService
import com.example.myapplication.data.quiz.QuizGenerationCoordinator
import com.example.myapplication.data.quiz.OpenAttemptResult
import com.example.myapplication.data.quiz.QuizAttemptSnapshot
import com.example.myapplication.data.quiz.QuizRepository
import com.example.myapplication.data.quiz.ReviseGradeResult
import com.example.myapplication.data.quiz.SaveAnswerResult
import com.example.myapplication.data.quiz.SubmitResult
import com.example.myapplication.data.quiz.QuizSubmissionService
import com.example.myapplication.data.quiz.QuizTemplate
import com.example.myapplication.data.local.dao.FavoriteDao
import com.example.myapplication.data.local.dao.QuizAttemptDao
import com.example.myapplication.data.local.dao.ReaderCatalogDao
import com.example.myapplication.data.session.SessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.ReadingScope
import com.example.myapplication.domain.reader.ScopeRequest
import com.example.myapplication.domain.quiz.QuizCodecs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 真实 [QuizUiPort]：QuizRepository + Submission/Grade 服务 + 生成协调（W4-11 接线）。
 *
 * UI 不直接发 HTTP、不写 DB；所有写操作都经既有服务（幂等 submitKey/assessmentKey、
 * CAS revision）。失败以异常上抛，由 ViewModel 转成可读错误，不静默改状态。
 */
class RealQuizUiPort(
    private val sessionManager: SessionManager,
    private val repository: QuizRepository,
    private val submissionService: QuizSubmissionService,
    private val gradeAssessmentService: GradeAssessmentService,
    private val generationCoordinator: QuizGenerationCoordinator,
    /** favorites 只读与删除：结果页展示“已在错题本”，并提供用户显式移除入口。 */
    private val favoriteDao: FavoriteDao,
    private val aiReviewService: QuizAiReviewService,
    private val readerCatalogDao: ReaderCatalogDao? = null,
) : QuizUiPort {

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private val identityMutex = Mutex()
    private var owner: SessionIdentity? = null

    private suspend fun context(): AccessContext {
        val identity = sessionManager.identity.first()
        val userId = identity.userId?.takeIf { it > 0 } ?: throw IllegalStateException("未登录，无法访问随堂测")
        identityMutex.withLock {
            if (owner == null) owner = identity
            if (owner != identity) throw CancellationException("Quiz session changed")
        }
        return AccessContext(userId, identity.epoch, "ui-${UUID.randomUUID()}")
    }

    private suspend fun requireIdentity(ctx: AccessContext) {
        val current = sessionManager.identity.first()
        if (current.userId != ctx.userId || current.epoch != ctx.sessionEpoch) throw CancellationException("Quiz session changed")
    }

    private suspend fun <T> guardRequest(block: suspend () -> T): T {
        val ctx = context()
        val result = block()
        requireIdentity(ctx)
        return result
    }

    override fun observeSessionIdentity(): Flow<SessionIdentity> = sessionManager.identity

    override suspend fun startGeneration(documentId: String, sectionId: String?): GenerationStartUi =
        guardRequest { startGenerationOwned(documentId, sectionId) }
    override suspend fun cancelGeneration(generationRunId: String): Unit =
        guardRequest { cancelGenerationOwned(generationRunId) }
    override suspend fun loadAttempt(attemptId: String): QuizAttemptUi =
        guardRequest { loadAttemptOwned(attemptId) }
    override suspend fun saveAnswer(attemptId: String, itemId: String, answer: String, editSequence: Long): Long =
        guardRequest { saveAnswerOwned(attemptId, itemId, answer, editSequence) }
    override suspend fun submit(attemptId: String, expectedRevision: Long, submitKey: String): SubmitReplyUi =
        guardRequest { submitOwned(attemptId, expectedRevision, submitKey) }
    override suspend fun loadResult(attemptId: String): QuizResultUi =
        guardRequest { loadResultOwned(attemptId) }
    override suspend fun reviewAnswer(attemptId: String, itemId: String): AiRubricReviewer.ReviewOutcome =
        guardRequest { reviewAnswerOwned(attemptId, itemId) }
    override suspend fun adoptAiReview(attemptId: String, suggestion: AiRubricReviewer.ReviewSuggestion): QuizResultUi =
        guardRequest { adoptAiReviewOwned(attemptId, suggestion) }
    override suspend fun setManualFavorite(questionId: Long, selected: Boolean): Unit =
        guardRequest { setManualFavoriteOwned(questionId, selected) }
    override suspend fun removeWrongFavorite(questionId: Long): Unit =
        guardRequest { removeWrongFavoriteOwned(questionId) }
    override suspend fun reviseGrade(attemptId: String, itemId: String, score: Int, note: String, assessmentKey: String): QuizResultUi =
        guardRequest { reviseGradeOwned(attemptId, itemId, score, note, assessmentKey) }
    override suspend fun loadHistory(offset: Int, limit: Int): List<QuizHistoryItemUi> =
        guardRequest { loadHistoryPageOwned(offset, limit).items }
    override suspend fun loadHistoryPage(offset: Int, limit: Int): QuizHistoryPageUi =
        guardRequest { loadHistoryPageOwned(offset, limit) }
    override suspend fun retrainPaper(paperId: String): String =
        guardRequest { retrainPaperOwned(paperId) }
    override suspend fun openPaper(paperId: String): String =
        guardRequest { openPaperOwned(paperId) }
    override suspend fun saveResumeState(attemptId: String, currentOrdinal: Int, deltaActiveMs: Long): Unit =
        guardRequest { saveResumeStateOwned(attemptId, currentOrdinal, deltaActiveMs) }
    override suspend fun regenerate(paperId: String): GenerationStartUi =
        guardRequest { regenerateOwned(paperId) }

    private suspend fun startGenerationOwned(documentId: String, sectionId: String?): GenerationStartUi {
        val ctx = context()
        val sectionIds = sectionId?.takeIf { it.isNotBlank() }?.let(::setOf).orEmpty()
        val sourceLabel = generationSourceLabel(ctx, documentId, sectionIds)
        val scope = ScopeRequest(
            documentIds = listOf(documentId),
            readingScope = if (sectionIds.isEmpty()) ReadingScope.CURRENT_DOCUMENT else ReadingScope.CURRENT_SECTION,
        )
        return when (
            val outcome = generationCoordinator.startGeneration(
                ctx = ctx,
                sourceScope = scope,
                templateId = QuizTemplate.CHAPTER_QUIZ_V1_ID,
                difficulty = null,
                requestKey = "ui-quiz-${UUID.randomUUID()}",
                sectionIds = sectionIds,
                reuseMatching = true,
            )
        ) {
            is QuizGenerationCoordinator.StartOutcome.Started ->
                GenerationStartUi(outcome.generationRunId, outcome.paperId, sourceLabel = sourceLabel)

            is QuizGenerationCoordinator.StartOutcome.Existing ->
                GenerationStartUi(outcome.generationRunId, outcome.paperId, existing = true, sourceLabel = sourceLabel)

            is QuizGenerationCoordinator.StartOutcome.Busy ->
                throw IllegalStateException("已有生成任务进行中：${outcome.activeRunId}")

            is QuizGenerationCoordinator.StartOutcome.Rejected ->
                throw IllegalStateException("生成被拒绝：${outcome.code}")
        }
    }

    override fun observeGeneration(generationRunId: String): Flow<QuizGenerationUi> =
        kotlinx.coroutines.flow.flow {
            val ctx = context()
            generationCoordinator.observeGeneration(ctx.userId, generationRunId).collect { progress ->
                requireIdentity(ctx)
                emit(progress.toUi())
            }
        }

    private suspend fun cancelGenerationOwned(generationRunId: String) {
        check(generationCoordinator.cancelGeneration(context(), generationRunId)) {
            "任务已结束或不可用"
        }
    }

    private suspend fun generationSourceLabel(ctx: AccessContext, documentId: String, sectionIds: Set<String>): String? {
        val dao = readerCatalogDao ?: return null
        val document = dao.findDocument(documentId)
            ?.takeIf { it.kind == "BUILTIN" || it.ownerUserId == ctx.userId }
            ?: return null
        if (sectionIds.isEmpty()) return document.title
        val sections = dao.findSections(document.id, document.contentRevision).filter { it.id in sectionIds }
        return when (sections.size) {
            0 -> document.title
            1 -> "${document.title} · ${sections.single().title}"
            else -> "${document.title} · ${sections.size} 个章节"
        }
    }

    private suspend fun loadAttemptOwned(attemptId: String): QuizAttemptUi {
        val ctx = context()
        val snapshot = repository.attemptSnapshot(ctx, attemptId)
            ?: throw IllegalStateException("作答不存在或无权访问：$attemptId")
        return QuizUiMappers.toAttemptUi(
            snapshot = snapshot,
            title = sourceTitle(ctx, attemptId),
            courseLabel = QUIZ_COURSE_LABEL,
            sourcesByItem = evidenceSources(ctx, snapshot),
        )
    }

    private suspend fun saveAnswerOwned(attemptId: String, itemId: String, answer: String, editSequence: Long): Long {
        val ctx = context()
        // 选择/简答的 raw JSON 形状以 domain QuizRawAnswers 为准，由 ViewModel 传入
        // （此处 answer 已经是 raw_answer_json）。
        return when (
            val result = repository.saveAnswer(
                ctx = ctx,
                attemptId = attemptId,
                itemId = itemId,
                editSequence = editSequence,
                rawAnswerJson = answer.takeIf { it.isNotBlank() },
                now = System.currentTimeMillis(),
            )
        ) {
            is SaveAnswerResult.Accepted -> result.attemptRevision
            is SaveAnswerResult.StaleEdit -> result.attemptRevision
            is SaveAnswerResult.Rejected -> throw IllegalStateException("保存被拒绝：${result.code}")
        }
    }

    private suspend fun submitOwned(attemptId: String, expectedRevision: Long, submitKey: String): SubmitReplyUi {
        val ctx = context()
        return when (
            val result = submissionService.submit(
                ctx = ctx,
                attemptId = attemptId,
                expectedAttemptRevision = expectedRevision,
                submitKey = submitKey,
            )
        ) {
            is SubmitResult.Submitted -> SubmitReplyUi(
                attemptId = result.attemptId,
                status = result.status,
                gradingStatus = result.gradingStatus,
                gradedScore = result.gradedScore,
                maxScore = result.maxScore,
                pendingCount = result.pendingCount,
                unansweredCount = result.unansweredCount,
                alreadySubmitted = result.alreadySubmitted,
            )

            is SubmitResult.Conflict -> {
                // 并发/双击：以服务端当前快照为准，避免 UI 以为失败。
                val snapshot = repository.attemptSnapshot(ctx, attemptId)
                if (snapshot != null && snapshot.attempt.isSubmitted) {
                    SubmitReplyUi(
                        attemptId = attemptId,
                        status = snapshot.attempt.status,
                        gradingStatus = snapshot.attempt.gradingStatus,
                        gradedScore = snapshot.gradedScore,
                        maxScore = snapshot.paper.maxScore,
                        pendingCount = snapshot.pendingCount,
                        unansweredCount = snapshot.paper.items.count { item ->
                            snapshot.answers[item.itemId ?: ""]?.rawAnswerJson.isNullOrBlank()
                        },
                        alreadySubmitted = true,
                    )
                } else {
                    throw IllegalStateException("试卷版本已变化（当前 revision=${result.currentRevision}），请刷新后重试")
                }
            }

            is SubmitResult.Rejected -> throw IllegalStateException("交卷被拒绝：${result.code}")
            is SubmitResult.Failed ->
                throw IllegalStateException("交卷失败：${result.code}（可重试=${result.retryable}）")
        }
    }

    private suspend fun loadResultOwned(attemptId: String): QuizResultUi {
        val ctx = context()
        val snapshot = repository.attemptSnapshot(ctx, attemptId)
            ?: throw IllegalStateException("结果不存在或无权访问：$attemptId")
        check(snapshot.attempt.status in setOf(QuizAttemptDao.STATUS_SUBMITTED_FINAL, QuizAttemptDao.STATUS_SUBMITTED_PENDING_REVIEW)) {
            "ATTEMPT_NOT_SUBMITTED: 本次作答尚未交卷，请继续作答"
        }
        return QuizUiMappers.toResultUi(
            snapshot = snapshot,
            title = sourceTitle(ctx, attemptId),
            courseLabel = QUIZ_COURSE_LABEL,
            sourcesByItem = evidenceSources(ctx, snapshot),
            wrongQuestionIds = wrongQuestionIds(ctx.userId),
        ).copy(aiReviewEnabled = aiReviewServiceEnabled(ctx.userId))
    }

    /**
     * 来源文档标题（G-04）：`quiz_papers.source_revision` → `reader_content_revisions`
     * → `reader_documents.title`，由 [QuizRepository.paperSource] 一次性只读查完。
     *
     * 回退语义：查不到来源（文档已删/别人的书/历史无 revision 的卷）就退回默认文案
     * "随堂测"，不猜测、不伪造文档名；`courseLabel` 两种情况下都保持"来源文档"，不新造枚举。
     */
    private suspend fun sourceTitle(ctx: AccessContext, attemptId: String): String =
        repository.paperSource(ctx, attemptId)?.title ?: DEFAULT_QUIZ_TITLE

    private suspend fun reviewAnswerOwned(
        attemptId: String,
        itemId: String,
    ): AiRubricReviewer.ReviewOutcome {
        val ctx = context()
        val snapshot = repository.attemptSnapshot(ctx, attemptId)
            ?: throw IllegalStateException("作答不存在或无权访问：$attemptId")
        return aiReviewService.review(ctx, snapshot, itemId)
    }

    private suspend fun adoptAiReviewOwned(
        attemptId: String,
        suggestion: AiRubricReviewer.ReviewSuggestion,
    ): QuizResultUi {
        val ctx = context()
        val snapshot = repository.attemptSnapshot(ctx, attemptId)
            ?: throw IllegalStateException("作答不存在或无权访问：$attemptId")
        requireIdentity(ctx)
        when (val adopted = aiReviewService.adopt(ctx, suggestion, snapshot.attempt.gradeRevision)) {
            is ReviseGradeResult.Applied, is ReviseGradeResult.Duplicate -> Unit
            is ReviseGradeResult.Conflict -> throw IllegalStateException("评分已更新，请刷新后再采纳")
            is ReviseGradeResult.Rejected -> throw IllegalStateException("采纳被拒绝：${adopted.code}")
            is ReviseGradeResult.Failed -> throw IllegalStateException("采纳失败：${adopted.code}")
        }
        val updated = loadResult(attemptId)
        return updated.copy(
            questions = updated.questions.map { question ->
                if (question.itemId == suggestion.itemId) question.copy(aiReviewSuggestion = null) else question
            },
            gradeNote = "已采纳 AI 复核建议：第 ${snapshot.paper.items.firstOrNull { it.itemId == suggestion.itemId }?.ordinal ?: "?"} 题",
        )
    }

    private suspend fun aiReviewServiceEnabled(userId: Long): Boolean =
        aiReviewService.isEnabled(userId)

    /**
     * `quiz_items.evidence_json` → 逐题来源卡片（W4-02/W4-11）。
     *
     * 发布端写入的是应用侧已核验的 locator/quote 快照；这里只解码，不重新猜出处。
     * 单条坏 JSON 只丢该题出处，不影响整卷可读（不伪造、不抛出）。
     */
    private suspend fun evidenceSources(ctx: AccessContext, snapshot: QuizAttemptSnapshot): Map<String, List<com.example.myapplication.ui.ai.AgentSourceUi>> {
        val result = linkedMapOf<String, List<com.example.myapplication.ui.ai.AgentSourceUi>>()
        for ((itemId, raw) in snapshot.evidenceJsonByItem) {
            val refs = runCatching { QuizCodecs.decodeEvidence(raw) }.getOrDefault(emptyList())
            val sources = mutableListOf<com.example.myapplication.ui.ai.AgentSourceUi>()
            for (ref in refs) {
                val source = QuizUiMappers.evidenceSources(listOf(ref)).single()
                val document = readerCatalogDao?.findDocument(ref.documentId)
                val unavailable = when {
                    readerCatalogDao == null -> null
                    document == null -> "来源已删除，保留历史摘录"
                    document.kind != "BUILTIN" && document.ownerUserId != ctx.userId -> "当前账号无法访问来源，保留历史摘录"
                    document.contentRevision != ref.contentRevision -> "来源已改版，保留历史摘录"
                    else -> null
                }
                sources += source.copy(unavailableReason = unavailable)
            }
            if (sources.isNotEmpty()) result[itemId] = sources
        }
        return result
    }

    /**
     * 错题本集合（favorites.source=WRONG）的只读查询。
     *
     * 查询失败时返回空集合：宁可少显示“已在错题本”入口，也不伪造状态或误删数据。
     */
    private suspend fun wrongQuestionIds(userId: Long): Set<Long> =
        runCatching {
            favoriteDao.findQuestionIdsBySource(userId, QuizRepository.FAVORITE_SOURCE_WRONG).toSet()
        }.getOrDefault(emptySet())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeManualFavorites(): Flow<Set<Long>> = sessionManager.identity.flatMapLatest { identity ->
        val userId = identity.userId
        if (userId == null) kotlinx.coroutines.flow.flowOf(emptySet())
        else favoriteDao.observeByUser(userId).map { rows -> rows.filter { it.source == "MANUAL" }.map { it.questionId }.toSet() }
    }
    private suspend fun setManualFavoriteOwned(questionId: Long, selected: Boolean) {
        val ctx = context()
        if (selected) {
            favoriteDao.insertManualOwned(ctx.userId, questionId, System.currentTimeMillis())
            check(questionId in favoriteDao.findQuestionIdsBySource(ctx.userId, "MANUAL")) { "题目不可收藏" }
        } else favoriteDao.delete(ctx.userId, questionId, "MANUAL")
    }

    private suspend fun removeWrongFavoriteOwned(questionId: Long) {
        val ctx = context()
        // 只删本用户该题的 WRONG 行（既有 DAO 删除路径）；不改作答、解析、评分与历史试卷。
        favoriteDao.delete(ctx.userId, questionId, QuizRepository.FAVORITE_SOURCE_WRONG)
    }

    private suspend fun reviseGradeOwned(
        attemptId: String,
        itemId: String,
        score: Int,
        note: String,
        assessmentKey: String,
    ): QuizResultUi {
        val ctx = context()
        val snapshot = repository.attemptSnapshot(ctx, attemptId)
            ?: throw IllegalStateException("作答不存在或无权访问：$attemptId")
        requireIdentity(ctx)
        val result = gradeAssessmentService.reviseGrade(
            ctx = ctx,
            attemptId = attemptId,
            itemId = itemId,
            baseGradeRevision = snapshot.attempt.gradeRevision,
            newAssessment = GradeAssessment(score = score, note = note),
            reason = note.ifBlank { "用户自评" },
            assessmentKey = assessmentKey,
        )
        when (result) {
            is ReviseGradeResult.Applied,
            is ReviseGradeResult.Duplicate -> Unit

            is ReviseGradeResult.Conflict ->
                throw IllegalStateException("评分已被更新（当前 gradeRevision=${result.currentGradeRevision}），请刷新")

            is ReviseGradeResult.Rejected ->
                throw IllegalStateException("自评被拒绝：${result.code}")

            is ReviseGradeResult.Failed ->
                throw IllegalStateException("自评失败：${result.code}（可重试=${result.retryable}）")
        }
        return loadResult(attemptId)
    }

    private suspend fun loadHistoryPageOwned(offset: Int, limit: Int): QuizHistoryPageUi {
        require(offset >= 0 && limit > 0) { "INVALID_HISTORY_PAGE" }
        val ctx = context()
        val papers = repository.listPapers(ctx, limit = limit, offset = offset)
        val items = ArrayList<QuizHistoryItemUi>(papers.size)
        for (paper in papers) {
            val paperId = paper.paperId ?: continue
            val latest = repository.listAttempts(ctx, paperId, limit = 1, offset = 0).firstOrNull() ?: continue
            val subtitle = if (paper.generatedBy.name == "OFFLINE_TEMPLATE") "内置练习卷" else "来源文档"
            items += QuizUiMappers.toHistoryItem(
                paper = paper,
                attempt = latest,
                submittedAtLabel = latest.submittedAt?.let { timeFormat.format(Date(it)) } ?: "作答中",
                title = "随堂测（${paper.questionCount} 题）",
                courseLabel = subtitle,
            )
        }
        return QuizHistoryPageUi(items, offset + papers.size, papers.size >= limit)
    }

    private suspend fun retrainPaperOwned(paperId: String): String {
        val ctx = context()
        // “重练”语义：同一 paper 新建一次作答，不复用未完成的旧 attempt。
        return openNewAttempt(ctx, paperId)
    }

    /**
     * 打开 READY paper：优先续接**本人该卷未交卷**的作答，其次才新建。
     *
     * 与 [retrainPaper] 的区别：openPaper 是“进入试卷”（生成完成/历史点开），
     * retrainPaper 是用户显式“重练原卷”。两者都经 repository 的幂等开卷路径。
     */
    private suspend fun openPaperOwned(paperId: String): String {
        val ctx = context()
        return when (val result = repository.openOrResume(ctx, paperId, System.currentTimeMillis())) {
            is OpenAttemptResult.Opened -> result.attempt.attemptId
            is OpenAttemptResult.Reused -> result.attempt.attemptId
            is OpenAttemptResult.Rejected -> throw IllegalStateException("打开试卷失败：${result.code}")
        }
    }

    /** 续答状态回写：当前题号 + 本次前台有效时长（单调累计，交卷后拒绝）。 */
    private suspend fun saveResumeStateOwned(attemptId: String, currentOrdinal: Int, deltaActiveMs: Long) {
        check(repository.resumeState(context(), attemptId, currentOrdinal, deltaActiveMs)) { "续答位置保存失败" }
    }

    private suspend fun openNewAttempt(ctx: AccessContext, paperId: String): String =
        when (
            val result = repository.openAttempt(
                ctx = ctx,
                paperId = paperId,
                proposedAttemptId = "attempt-${UUID.randomUUID()}",
                resumeAttemptId = null,
                now = System.currentTimeMillis(),
            )
        ) {
            is OpenAttemptResult.Opened -> result.attempt.attemptId
            is OpenAttemptResult.Reused -> result.attempt.attemptId
            is OpenAttemptResult.Rejected -> throw IllegalStateException("打开试卷失败：${result.code}")
        }

    private suspend fun regenerateOwned(paperId: String): GenerationStartUi {
        val ctx = context()
        val paper = repository.paperSnapshot(ctx, paperId)
            ?: throw IllegalStateException("原试卷不存在或无权访问：$paperId")
        // scope_json 由生成协调写入固定 scope（含文档与选择范围）；重新生成必须用原 scope。
        val documentId = QuizScopeParser.parseDocumentId(paper.scopeJson)
            ?: throw IllegalStateException("原试卷缺少来源 scope，无法重新生成")
        val sectionIds = (Json.parseToJsonElement(paper.scopeJson).jsonObject["sectionIds"] as? JsonArray)
            ?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        val sourceLabel = generationSourceLabel(ctx, documentId, sectionIds)
        val scope = ScopeRequest(
            documentIds = listOf(documentId),
            readingScope = QuizScopeParser.parseReadingScope(paper.scopeJson),
        )
        return when (
            val outcome = generationCoordinator.startGeneration(
                ctx = ctx,
                sourceScope = scope,
                templateId = QuizTemplate.CHAPTER_QUIZ_V1_ID,
                difficulty = null,
                requestKey = "ui-quiz-regen-${UUID.randomUUID()}",
                sectionIds = sectionIds,
            )
        ) {
            is QuizGenerationCoordinator.StartOutcome.Started ->
                GenerationStartUi(outcome.generationRunId, outcome.paperId, sourceLabel = sourceLabel)

            is QuizGenerationCoordinator.StartOutcome.Existing ->
                GenerationStartUi(outcome.generationRunId, outcome.paperId, existing = true, sourceLabel = sourceLabel)

            is QuizGenerationCoordinator.StartOutcome.Busy ->
                throw IllegalStateException("已有生成任务进行中：${outcome.activeRunId}")

            is QuizGenerationCoordinator.StartOutcome.Rejected ->
                throw IllegalStateException("重新生成被拒绝：${outcome.code}")
        }
    }

    private fun QuizGenerationCoordinator.GenerationProgress.toUi(): QuizGenerationUi = QuizGenerationUi(
        generationRunId = generationRunId,
        paperId = paperId,
        stage = QuizGenerationStageUi.fromWire(stage),
        message = message.ifBlank { QuizGenerationStageUi.fromWire(stage).label },
        paperStatus = paperStatus,
        questionCount = questionCount,
        maxScore = maxScore,
        ready = ready,
        terminal = terminal,
        errorCode = errorCode,
    )

    private companion object {
        /** 来源查不到时的默认标题（沿用页面既有文案，不新造枚举）。 */
        const val DEFAULT_QUIZ_TITLE = "随堂测"

        /** 来源标注：与历史页/结果页既有文案一致，表示"这份卷出自某本书"。 */
        const val QUIZ_COURSE_LABEL = "来源文档"
    }
}

/**
 * `quiz_papers.scope_json` 的只读解析（重新生成必须还原原 scope）。
 *
 * 生产写入方（[QuizGenerationCoordinator]）写的是**顶层** `documentId`；
 * 早期/手写数据里出现过 `documents: [{documentId}]` 的形状，这里同时兼容，
 * 避免“重新生成”因为读错键位而永远报“缺少来源 scope”。
 */
internal object QuizScopeParser {

    fun parseDocumentId(scopeJson: String): String? = runCatching {
        val root = parseRoot(scopeJson) ?: return@runCatching null
        topLevelDocumentId(root) ?: legacyDocumentId(root)
    }.getOrNull()

    fun parseReadingScope(scopeJson: String): ReadingScope = runCatching {
        val root = parseRoot(scopeJson)
        val raw = (root?.get("readingScope") as? JsonPrimitive)?.contentOrNull
        when (raw) {
            ReadingScope.CURRENT_SECTION.name -> ReadingScope.CURRENT_SECTION
            ReadingScope.LIBRARY.name -> ReadingScope.LIBRARY
            ReadingScope.CURRENT_DOCUMENT.name -> ReadingScope.CURRENT_DOCUMENT
            // 兼容旧快照（没有 readingScope 字段）：按字面量匹配。
            else -> if (scopeJson.contains(ReadingScope.CURRENT_SECTION.name)) {
                ReadingScope.CURRENT_SECTION
            } else {
                ReadingScope.CURRENT_DOCUMENT
            }
        }
    }.getOrDefault(ReadingScope.CURRENT_DOCUMENT)

    private fun parseRoot(scopeJson: String): JsonObject? =
        runCatching { Json.parseToJsonElement(scopeJson) as? JsonObject }.getOrNull()

    private fun topLevelDocumentId(root: JsonObject): String? =
        (root["documentId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun legacyDocumentId(root: JsonObject): String? {
        val documents = root["documents"] as? JsonArray ?: return null
        val first = documents.firstOrNull() as? JsonObject ?: return null
        return (first["documentId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
