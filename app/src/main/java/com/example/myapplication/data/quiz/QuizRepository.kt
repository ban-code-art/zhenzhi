package com.example.myapplication.data.quiz

import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.OpenAttemptOutcome
import com.example.myapplication.data.local.dao.QuizAttemptDao
import com.example.myapplication.data.local.dao.SaveAnswerOutcome
import com.example.myapplication.data.local.entity.QuizAttemptAnswerEntity
import com.example.myapplication.data.local.entity.QuizAttemptEntity
import com.example.myapplication.data.local.entity.QuizItemEntity
import com.example.myapplication.data.local.entity.QuizPaperEntity
import com.example.myapplication.domain.quiz.QuizCodecs
import com.example.myapplication.domain.quiz.QuizGeneratedBy
import com.example.myapplication.domain.quiz.QuizItemSnapshot
import com.example.myapplication.domain.quiz.QuizItemType
import com.example.myapplication.domain.quiz.QuizPaperSnapshot
import com.example.myapplication.domain.quiz.QuizPaperStatus
import com.example.myapplication.domain.reader.AccessContext
import java.util.UUID

/**
 * 开卷结果（FX-30 / 契约 §8.3）。
 *
 * `Opened` 表示本次新建，`Reused` 表示同一 proposedAttemptId/resume 命中了既有 attempt；
 * 两者都返回同一个 attemptId，调用方不得再自行生成第二份。
 */
sealed interface OpenAttemptResult {
    data class Opened(val attempt: QuizAttemptView) : OpenAttemptResult
    data class Reused(val attempt: QuizAttemptView) : OpenAttemptResult
    data class Rejected(val code: String) : OpenAttemptResult
}

/** 单题作答保存结果（契约 §8.3：旧/重复 editSequence 只回 Ack，不覆盖新答案）。 */
sealed interface SaveAnswerResult {
    data class Accepted(val attemptRevision: Long, val editSequence: Long) : SaveAnswerResult
    data class StaleEdit(val attemptRevision: Long, val editSequence: Long) : SaveAnswerResult
    data class Rejected(val code: String) : SaveAnswerResult
}

/** attempt 的只读视图（不含答案正文）。 */
data class QuizAttemptView(
    val attemptId: String,
    val paperId: String,
    val userId: Long,
    val status: String,
    val gradingStatus: String,
    val revision: Long,
    val gradeRevision: Long,
    val submitKey: String,
    val currentOrdinal: Int,
    val activeUsedMs: Long,
    val totalScore: Int?,
    val submittedAt: Long?,
) {
    val isInProgress: Boolean get() = status == QuizAttemptDao.STATUS_IN_PROGRESS
    val isSubmitted: Boolean get() = !isInProgress
    val isPendingReview: Boolean get() = status == QuizAttemptDao.STATUS_SUBMITTED_PENDING_REVIEW
}

/** 已保存答案的只读视图（评分类字段在交卷/修分后才有值）。 */
data class QuizAttemptAnswerView(
    val itemId: String,
    val rawAnswerJson: String?,
    val editSequence: Long,
    val score: Int?,
    val isCorrect: Boolean?,
    val matchedRubricJson: String,
    val gradingMethod: String,
    val feedback: String,
    val updatedAt: Long,
)

/**
 * 判分/结果页所需的整份快照：固定 paper/items + 当前答案。
 *
 * [itemQuestionIds] 用于写错题（favorites.question_id）；[paper] 是领域快照，直接喂给
 * [LocalRubricGrader] 与 [com.example.myapplication.domain.quiz.QuizScoreSummary]。
 */
data class QuizAttemptSnapshot(
    val attempt: QuizAttemptView,
    val paper: QuizPaperSnapshot,
    val answers: Map<String, QuizAttemptAnswerView>,
    val itemQuestionIds: Map<String, Long>,
    /**
     * 发布时写入 `quiz_items.evidence_json` 的出处快照（itemId → 原始 JSON）。
     *
     * 只读、按 item 分组；结果页/答题页据此还原逐题出处（W4-02/W4-11）。
     * 坏 JSON 的容错在 UI 映射层完成，仓储层不吞异常也不伪造出处。
     */
    val evidenceJsonByItem: Map<String, String> = emptyMap(),
) {
    fun answerFor(itemId: String): QuizAttemptAnswerView? = answers[itemId]

    val pendingCount: Int get() = paper.items.count { answers[it.itemId]?.score == null }

    val gradedScore: Int get() = paper.items.sumOf { answers[it.itemId]?.score ?: 0 }
}

/**
 * 试卷的来源文档（G-04：评分/答题页标题）。
 *
 * 只读、只给 UI 展示用；不带正文、不带 locator，不改变 [QuizPaperSnapshot] 的形状。
 */
data class PaperSource(
    val documentId: String,
    val title: String,
)

/**
 * 作答仓库（W4-08 / 契约 §8.3、设计 §8）。
 *
 * 责任边界：
 *  - paper/items 只读快照，历史题目停用后不回写旧卷；
 *  - openAttempt 幂等（同 proposedAttemptId 复用；明确“重练”才用新 id）；
 *  - saveAnswer 的 owner/IN_PROGRESS/题目归属校验与 revision 推进都在 DAO 事务内
 *    （[QuizAttemptDao.saveAnswer]），本类只做前置拒绝与结果映射；
 *  - 续答恢复当前题与有效时长（active_used_ms 单调累计，后台时间不计）；
 *  - 不实现评分/提交/修分（见 QuizSubmissionService / GradeAssessmentService）。
 */
class QuizRepository(
    private val db: AppDatabase,
) {
    private val paperDao get() = db.quizPaperDao()
    private val attemptDao get() = db.quizAttemptDao()
    private val readerCatalogDao get() = db.readerCatalogDao()

    // ---- 试卷只读快照 ----

    /** 按当前用户读取已发布试卷快照；非 owner/不存在返回 null（不泄露他人试卷）。 */
    suspend fun paperSnapshot(ctx: AccessContext, paperId: String): QuizPaperSnapshot? {
        val paper = paperDao.findPaperForUser(ctx.userId, paperId) ?: return null
        return paper.toSnapshot(paperDao.findItems(paperId))
    }

    /** 当前用户最近生成的试卷（含 GENERATING/FAILED，调用方按 status 过滤）。 */
    suspend fun listPapers(ctx: AccessContext, limit: Int = 20, offset: Int = 0): List<QuizPaperSnapshot> {
        if (limit <= 0 || limit > MAX_PAGE_SIZE) return emptyList()
        return paperDao.listPapers(ctx.userId, limit, offset).map { paper ->
            paper.toSnapshot(paperDao.findItems(paper.id))
        }
    }

    // ---- 来源文档（只读，G-04）----

    /**
     * 作答对应的来源文档（评分/答题页标题用）。
     *
     * 链路严格按验证文档 §5.2：
     * `quiz_attempts.paper_id` → `quiz_papers.source_revision`
     * → `reader_content_revisions.revision` → `reader_documents.title`。
     *
     * 语义与约束：
     *  - **鉴权先行**：attempt 与 paper 都用 `ctx.userId` 过滤，别人的 attempt 一律 null，
     *    跨用户查询不会因为 revision 相同而泄露书名；
     *  - 同一 revision 可能命中多行（不同文档导入了同一份文件），优先当前用户自己的书，
     *    匹配不到再接受全局内置书（`kind = 'BUILTIN'`）；回收站文档不参与；
     *  - 只读查询：不改 schema、不加 migration、不改 [QuizPaperSnapshot]，
     *    也不碰生成/提交/评分链路；
     *  - 查不到（无 paper、revision 为空、文档已删、标题为空）一律返回 null，
     *    由 UI 退回默认文案，不伪造文档名。
     */
    suspend fun paperSource(ctx: AccessContext, attemptId: String): PaperSource? {
        val attempt = attemptDao.findAttemptForUser(ctx.userId, attemptId) ?: return null
        val paper = paperDao.findPaperForUser(ctx.userId, attempt.paperId) ?: return null
        val revision = paper.sourceRevision.takeIf { it.isNotBlank() } ?: return null
        val document = readerCatalogDao.findDocumentByRevisionForUser(ctx.userId, revision) ?: return null
        val title = document.title.trim().ifBlank { return null }
        return PaperSource(documentId = document.id, title = title)
    }

    // ---- attempt 生命周期 ----

    /**
     * 打开/恢复一次作答（FX-30）。
     *
     * - [resumeAttemptId] 非空：只允许恢复**已有的本人 attempt**，不新建、不校验题号；
     * - 否则用 [proposedAttemptId] 幂等创建：同一次打开动作重试复用同一条记录；
     *   用户明确“重练”必须传新的 proposedAttemptId；
     * - submitKey 在 attempt 首次创建时固定（由 attemptId 派生，重试不重生）。
     */
    suspend fun openAttempt(
        ctx: AccessContext,
        paperId: String,
        proposedAttemptId: String? = null,
        resumeAttemptId: String? = null,
        now: Long,
    ): OpenAttemptResult {
        val paper = paperDao.findPaperForUser(ctx.userId, paperId)
            ?: return OpenAttemptResult.Rejected(REJECT_PAPER_NOT_FOUND)
        if (paper.status != PAPER_STATUS_READY) {
            return OpenAttemptResult.Rejected(REJECT_PAPER_NOT_READY)
        }
        if (resumeAttemptId != null) {
            val existing = attemptDao.findAttemptForUser(ctx.userId, resumeAttemptId)
                ?: return OpenAttemptResult.Rejected(REJECT_ATTEMPT_NOT_FOUND)
            if (existing.paperId != paperId) {
                return OpenAttemptResult.Rejected(REJECT_ATTEMPT_PAPER_MISMATCH)
            }
            return OpenAttemptResult.Reused(existing.toView())
        }
        val attemptId = proposedAttemptId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val entity = QuizAttemptEntity(
            id = attemptId,
            paperId = paperId,
            userId = ctx.userId,
            status = QuizAttemptDao.STATUS_IN_PROGRESS,
            currentOrdinal = 0,
            startedAt = now,
            gradingStatus = GRADING_NOT_GRADED,
            submitKey = submitKeyFor(attemptId),
        )
        return when (val outcome = attemptDao.openAttempt(entity)) {
            is OpenAttemptOutcome.Opened -> OpenAttemptResult.Opened(outcome.attempt.toView())
            is OpenAttemptOutcome.Reused -> OpenAttemptResult.Reused(outcome.attempt.toView())
            is OpenAttemptOutcome.Rejected -> OpenAttemptResult.Rejected(outcome.reason)
        }
    }

    suspend fun attempt(ctx: AccessContext, attemptId: String): QuizAttemptView? =
        attemptDao.findAttemptForUser(ctx.userId, attemptId)?.toView()

    /**
     * 打开试卷：优先续接本人该卷**最近一个未交卷** attempt，否则新建。
     *
     * 与“重练原卷”（调用方显式传新 proposedAttemptId）区分；本方法只负责
     * “进入试卷”的默认语义，幂等由 [openAttempt] 保证。
     */
    suspend fun openOrResume(ctx: AccessContext, paperId: String, now: Long): OpenAttemptResult {
        val inProgress = attemptDao
            .listAttempts(paperId, ctx.userId, MAX_PAGE_SIZE, 0)
            .firstOrNull { it.status == QuizAttemptDao.STATUS_IN_PROGRESS }
        return if (inProgress != null) {
            openAttempt(ctx, paperId, proposedAttemptId = null, resumeAttemptId = inProgress.id, now = now)
        } else {
            openAttempt(ctx, paperId, proposedAttemptId = "attempt-${UUID.randomUUID()}", resumeAttemptId = null, now = now)
        }
    }

    suspend fun listAttempts(
        ctx: AccessContext,
        paperId: String,
        limit: Int = 20,
        offset: Int = 0,
    ): List<QuizAttemptView> {
        if (limit <= 0 || limit > MAX_PAGE_SIZE) return emptyList()
        return attemptDao.listAttempts(paperId, ctx.userId, limit, offset).map { it.toView() }
    }

    /** 续答恢复：只回写当前题号并累计有效时长；交卷后拒绝（不能让交卷后继续改题号/计时）。 */
    suspend fun resumeState(
        ctx: AccessContext,
        attemptId: String,
        currentOrdinal: Int,
        deltaActiveMs: Long,
    ): Boolean {
        if (currentOrdinal < 0 || deltaActiveMs < 0) return false
        val existing = attemptDao.findAttemptForUser(ctx.userId, attemptId) ?: return false
        if (existing.status != QuizAttemptDao.STATUS_IN_PROGRESS) return false
        return attemptDao.updateResumeState(attemptId, ctx.userId, currentOrdinal, deltaActiveMs) == 1
    }

    // ---- 作答保存 ----

    /**
     * 保存单题作答。
     *
     * 前置拒绝给出明确 code（跨卷/他人/已交卷），真正的事务内校验仍由
     * [QuizAttemptDao.saveAnswer] 完成：新 editSequence 才推进 attempt.revision。
     */
    suspend fun saveAnswer(
        ctx: AccessContext,
        attemptId: String,
        itemId: String,
        editSequence: Long,
        rawAnswerJson: String?,
        now: Long,
    ): SaveAnswerResult {
        if (editSequence <= 0) return SaveAnswerResult.Rejected(REJECT_INVALID_EDIT_SEQUENCE)
        val attempt = attemptDao.findAttemptForUser(ctx.userId, attemptId)
            ?: return SaveAnswerResult.Rejected(REJECT_ATTEMPT_NOT_FOUND)
        if (attempt.status != QuizAttemptDao.STATUS_IN_PROGRESS) {
            return SaveAnswerResult.Rejected(REJECT_ATTEMPT_NOT_IN_PROGRESS)
        }
        if (attemptDao.itemBelongsToPaper(itemId, attempt.paperId) == 0) {
            return SaveAnswerResult.Rejected(REJECT_ITEM_NOT_IN_PAPER)
        }
        return when (val outcome = attemptDao.saveAnswer(ctx.userId, attemptId, itemId, rawAnswerJson, editSequence, now)) {
            is SaveAnswerOutcome.Accepted -> SaveAnswerResult.Accepted(outcome.attemptRevision, outcome.editSequence)
            is SaveAnswerOutcome.StaleEdit -> SaveAnswerResult.StaleEdit(outcome.attemptRevision, outcome.editSequence)
            SaveAnswerOutcome.Rejected -> SaveAnswerResult.Rejected(REJECT_ANSWER_REJECTED)
        }
    }

    // ---- 判分/交卷快照 ----

    /** 读取 attempt 整份快照；非 owner、paper/items 缺失返回 null。判分在事务外基于它进行。 */
    suspend fun attemptSnapshot(ctx: AccessContext, attemptId: String): QuizAttemptSnapshot? {
        val attempt = attemptDao.findAttemptForUser(ctx.userId, attemptId) ?: return null
        val paper = paperDao.findPaperForUser(ctx.userId, attempt.paperId) ?: return null
        val itemEntities = paperDao.findItems(paper.id)
        val answers = attemptDao.findAnswers(attemptId).associate { answer ->
            answer.itemId to answer.toView()
        }
        return QuizAttemptSnapshot(
            attempt = attempt.toView(),
            paper = paper.toSnapshot(itemEntities),
            answers = answers,
            itemQuestionIds = itemEntities.associate { it.id to it.questionId },
            evidenceJsonByItem = itemEntities
                .filter { it.evidenceJson.isNotBlank() }
                .associate { it.id to it.evidenceJson },
        )
    }

    /** 读整卷 revision（提交前固定期望值；CAS 冲突时用于提示重新读取）。 */
    suspend fun readRevision(ctx: AccessContext, attemptId: String): Long? =
        attemptDao.readRevision(attemptId, ctx.userId)

    // ---- 映射 ----

    private fun QuizPaperEntity.toSnapshot(items: List<QuizItemEntity>): QuizPaperSnapshot = QuizPaperSnapshot(
        // quiz_papers 只存 template_version（DDL 无 template_id 列）；生成期 templateId 不在持久层。
        templateId = "",
        templateVersion = templateVersion,
        sourceRevision = sourceRevision,
        scopeJson = scopeJson,
        questionCount = questionCount,
        maxScore = maxScore,
        items = items.sortedBy { it.ordinal }.map { it.toItemSnapshot(templateVersion) },
        generatedBy = if (generatedBy == QuizGeneratedBy.OFFLINE_TEMPLATE.name) {
            QuizGeneratedBy.OFFLINE_TEMPLATE
        } else {
            QuizGeneratedBy.AI
        },
        coverageJson = generationCoverageJson,
        paperId = id,
        status = QuizPaperStatus.entries.firstOrNull { it.name == status } ?: QuizPaperStatus.VALIDATING,
    )

    private fun QuizItemEntity.toItemSnapshot(paperTemplateVersion: String): QuizItemSnapshot {
        val decoded = runCatching { QuizCodecs.decodeItemSnapshot(questionSnapshotJson) }.getOrNull()
        val rubric = runCatching { QuizCodecs.decodeRubric(rubricJson) }.getOrNull()
            ?: decoded?.rubric.orEmpty()
        if (decoded == null) {
            // 快照损坏时仍保留可定位的题目条目（评分为 0 而不是静默跳过）。
            return QuizItemSnapshot(
                ordinal = ordinal,
                type = QuizItemType.CHOICE,
                stem = "",
                answer = emptyList(),
                score = 0,
                rubric = rubric,
                templateVersion = paperTemplateVersion,
                itemId = id,
            )
        }
        return decoded.copy(
            ordinal = ordinal,
            rubric = rubric,
            templateVersion = decoded.templateVersion.ifEmpty { paperTemplateVersion },
            itemId = id,
        )
    }

    private fun QuizAttemptEntity.toView(): QuizAttemptView = QuizAttemptView(
        attemptId = id,
        paperId = paperId,
        userId = userId,
        status = status,
        gradingStatus = gradingStatus,
        revision = revision,
        gradeRevision = gradeRevision,
        submitKey = submitKey,
        currentOrdinal = currentOrdinal,
        activeUsedMs = activeUsedMs,
        totalScore = totalScore,
        submittedAt = submittedAt,
    )

    private fun QuizAttemptAnswerEntity.toView(): QuizAttemptAnswerView = QuizAttemptAnswerView(
        itemId = itemId,
        rawAnswerJson = rawAnswerJson,
        editSequence = editSequence,
        score = score,
        isCorrect = isCorrect,
        matchedRubricJson = matchedRubricJson,
        gradingMethod = gradingMethod,
        feedback = feedback,
        updatedAt = updatedAt,
    )

    companion object {
        const val PAPER_STATUS_READY = "READY"

        /** quiz_attempts.grading_status 字面量（DDL 只约束非空，这里固定三态）。 */
        const val GRADING_NOT_GRADED = QuizAttemptDao.GRADING_NOT_GRADED
        const val GRADING_FINAL = QuizAttemptDao.GRADING_FINAL
        const val GRADING_PENDING_REVIEW = QuizAttemptDao.GRADING_PENDING_REVIEW

        const val FAVORITE_SOURCE_WRONG = "WRONG"
        const val ACTIVITY_KIND_QUIZ_SUBMIT = "QUIZ_SUBMIT"
        const val ACTIVITY_DEDUP_PREFIX = "QUIZ_SUBMIT:"

        /** submitKey 在 attempt 首次创建时固定，重试不重生（契约 §8.3）。 */
        fun submitKeyFor(attemptId: String): String = "quiz-submit:$attemptId"

        const val REJECT_PAPER_NOT_FOUND = "PAPER_NOT_FOUND"
        const val REJECT_PAPER_NOT_READY = "PAPER_NOT_READY"
        const val REJECT_ATTEMPT_NOT_FOUND = "ATTEMPT_NOT_FOUND"
        const val REJECT_ATTEMPT_NOT_IN_PROGRESS = "ATTEMPT_NOT_IN_PROGRESS"
        const val REJECT_ATTEMPT_PAPER_MISMATCH = "ATTEMPT_PAPER_MISMATCH"
        const val REJECT_ITEM_NOT_IN_PAPER = "ITEM_NOT_IN_PAPER"
        const val REJECT_INVALID_EDIT_SEQUENCE = "INVALID_EDIT_SEQUENCE"
        const val REJECT_ANSWER_REJECTED = "ANSWER_REJECTED"

        const val MAX_PAGE_SIZE = 100
    }
}

