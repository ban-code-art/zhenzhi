package com.example.myapplication.ui.knowledge.quiz

import com.example.myapplication.data.quiz.AiRubricReviewer
import com.example.myapplication.ui.ai.AgentSourceUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * W4-11 随堂测 UI 状态模型 + 端口（骨架）。
 *
 * 只描述 UI 需要的形状；真实接线时用 QuizRepository/QuizSubmissionService/
 * GradeAssessmentService/QuizGenerationCoordinator 适配 [QuizUiPort]。关键口径：
 *  - 生成阶段是真实阶段序列（REGISTERED→READING→PLANNING→GENERATING→VALIDATING→PUBLISHING→READY），
 *    取消/失败/中断分别落状态，不把空题卡当正式卷；
 *  - 结果页区分“已评/待评”：score == null 是 PENDING_SELF_REVIEW，**不得当 0 分**；
 *  - 历史里“重练原卷”（固定 item snapshot）与“重新生成”（新 paper）是两个动作。
 */
enum class QuizGenerationStageUi {
    REGISTERED, READING, PLANNING, GENERATING, GENERATING_REPAIR, VALIDATING, PUBLISHING, READY, FAILED, CANCELLED, INTERRUPTED, UNKNOWN;

    val label: String
        get() = when (this) {
            REGISTERED -> "已登记"
            READING -> "读取章节与目录"
            PLANNING -> "规划覆盖与难度"
            GENERATING -> "生成题目"
            GENERATING_REPAIR -> "定向修复题目"
            VALIDATING -> "校验题目与出处"
            PUBLISHING -> "发布试卷"
            READY -> "已就绪"
            FAILED -> "生成失败"
            CANCELLED -> "已取消"
            INTERRUPTED -> "已中断"
            UNKNOWN -> "状态未知（非本人运行或已清理）"
        }

    val isTerminal: Boolean
        get() = this == READY || this == FAILED || this == CANCELLED || this == INTERRUPTED || this == UNKNOWN

    companion object {
        /** 与 task-13 `QuizGenerationCoordinator.GenerationProgress.stage` 的线上字符串对齐。 */
        fun fromWire(stage: String?): QuizGenerationStageUi = when (stage) {
            "REGISTERED" -> REGISTERED
            "READING" -> READING
            "PLANNING" -> PLANNING
            "GENERATING" -> GENERATING
            "GENERATING_REPAIR" -> GENERATING_REPAIR
            "VALIDATING" -> VALIDATING
            "PUBLISHING" -> PUBLISHING
            "READY" -> READY
            "FAILED" -> FAILED
            "CANCELLED" -> CANCELLED
            "INTERRUPTED" -> INTERRUPTED
            else -> UNKNOWN
        }

        /** 时间线展示顺序（不含终态与 UNKNOWN）；修复轮次在生成之后。 */
        val TIMELINE: List<QuizGenerationStageUi> = listOf(
            REGISTERED, READING, PLANNING, GENERATING, GENERATING_REPAIR, VALIDATING, PUBLISHING,
        )
    }
}

/**
 * 生成进度（UI）。字段与 `QuizGenerationCoordinator.GenerationProgress` 一一对应：
 * [paperId] 在 REGISTERED 即可能非空（占位已落库）；[ready] 仅 paper.status=READY 为 true；
 * [terminal] 为 true 后停止 collect 并刷新历史。
 */
data class QuizGenerationUi(
    val generationRunId: String? = null,
    val paperId: String? = null,
    val stage: QuizGenerationStageUi = QuizGenerationStageUi.REGISTERED,
    val message: String = "准备生成随堂测",
    val progressPercent: Int? = null,
    val paperStatus: String? = null,
    val questionCount: Int = 0,
    val maxScore: Int = 0,
    val ready: Boolean = false,
    val terminal: Boolean = false,
    val errorCode: String? = null,
    val retryable: Boolean = false,
    val sourceLabel: String? = null,
    val cancelling: Boolean = false,
    val actionError: String? = null,
) {
    val canCancel: Boolean get() = generationRunId != null && !ready && !terminal && !cancelling
}

enum class QuizItemTypeUi { CHOICE, ESSAY }

enum class QuizItemGradeStatusUi { GRADED, PENDING_SELF_REVIEW, NOT_GRADED, UNANSWERED }

enum class QuizAttemptStatusUi { IN_PROGRESS, SUBMITTED_PENDING_REVIEW, SUBMITTED_FINAL }

data class QuizOptionUi(val letter: String, val text: String)

data class QuizQuestionUi(
    val itemId: String,
    val ordinal: Int,
    val type: QuizItemTypeUi,
    val stem: String,
    val options: List<QuizOptionUi> = emptyList(),
    val savedAnswer: String = "",
    val maxScore: Int,
    val score: Int? = null,
    val isCorrect: Boolean? = null,
    val gradeStatus: QuizItemGradeStatusUi = QuizItemGradeStatusUi.UNANSWERED,
    val rubricLines: List<String> = emptyList(),
    val analysis: String = "",
    val knowledgePoint: String = "",
    val sources: List<AgentSourceUi> = emptyList(),
    /** 对应 `questions.id`（写/删 favorites.WRONG 用）；历史卷或没有题目映射时为 null。 */
    val questionId: Long? = null,
    /** 该题当前在错题本（`favorites.source = WRONG`）。改对后不自动删除，只能用户显式移除。 */
    val inWrongBook: Boolean = false,
    val manuallyFavorited: Boolean = false,
    /** 模型复核只作为当前页面建议展示，不自动写入 grade。 */
    val aiReviewSuggestion: AiRubricReviewer.ReviewSuggestion? = null,
) {
    val answered: Boolean get() = savedAnswer.isNotBlank()
}

data class QuizAttemptUi(
    val attemptId: String,
    /**
     * 交卷幂等键：**必须**用 attempt 创建时持久化的值（`QuizRepository.submitKeyFor`）。
     * 服务端要求与 `quiz_attempts.submit_key` 逐字相等，UI 不得自行生成随机 key（FX-17/Q-05）。
     */
    val submitKey: String = "",
    val paperId: String,
    val title: String,
    val courseLabel: String,
    val status: QuizAttemptStatusUi = QuizAttemptStatusUi.IN_PROGRESS,
    val questions: List<QuizQuestionUi> = emptyList(),
    val currentOrdinal: Int = 0,
    val activeUsedMs: Long = 0L,
    val attemptRevision: Long = 0L,
    val gradedScore: Int = 0,
    val maxScore: Int = 100,
    val pendingReviewCount: Int = 0,
) {
    val answeredCount: Int get() = questions.count { it.answered }
    val questionCount: Int get() = questions.size
    val unansweredCount: Int get() = questions.count { !it.answered }
    val currentQuestion: QuizQuestionUi? get() = questions.getOrNull(currentOrdinal)
    val hasPendingReview: Boolean get() = pendingReviewCount > 0
}

data class QuizGradeDraftUi(
    val itemId: String,
    val scoreText: String,
    val note: String = "",
)

data class QuizHistoryItemUi(
    val paperId: String,
    val attemptId: String,
    val title: String,
    val courseLabel: String,
    val statusLabel: String,
    val scoreLabel: String,
    val submittedAtLabel: String,
    val generatedByLabel: String,
    val hasInProgressAttempt: Boolean = false,
)

data class QuizHistoryUi(
    val items: List<QuizHistoryItemUi> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val nextOffset: Int = 0,
)

/** The cursor counts source paper rows, including papers without an attempt. */
data class QuizHistoryPageUi(
    val items: List<QuizHistoryItemUi>,
    val nextOffset: Int,
    val hasMore: Boolean,
)

/** 生成启动结果（与 QuizGenerationCoordinator.StartOutcome.Started 对齐）。 */
data class GenerationStartUi(
    val generationRunId: String,
    val paperId: String?,
    val existing: Boolean = false,
    val sourceLabel: String? = null,
)

/**
 * UI → 数据/编排层端口（真实接线前用 [FakeQuizUiPort]）。
 *
 * 生成进度订阅按 generationRunId（与 task-13 `observeGeneration(userId, runId)` 对齐），
 * 不是按 paperId；paperId 从进度里读（REGISTERED 起可能已非空）。
 */
interface QuizUiPort {
    /** A changed user or epoch invalidates all UI requests from the previous session. */
    fun observeSessionIdentity(): Flow<com.example.myapplication.data.session.SessionIdentity> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun startGeneration(documentId: String, sectionId: String?): GenerationStartUi
    fun observeGeneration(generationRunId: String): Flow<QuizGenerationUi>
    suspend fun cancelGeneration(generationRunId: String)
    suspend fun loadAttempt(attemptId: String): QuizAttemptUi
    suspend fun saveAnswer(attemptId: String, itemId: String, answer: String, editSequence: Long): Long
    /**
     * 续答状态回写（当前题号 + 本次前台有效时长）。
     *
     * 只在 attempt 仍 IN_PROGRESS 时生效；交卷后调用必须是 no-op（不能把已交卷的
     * 题号/计时改回去）。失败不打断作答：调用方只记录，不把本地输入当已保存。
     */
    suspend fun saveResumeState(attemptId: String, currentOrdinal: Int, deltaActiveMs: Long) = Unit
    suspend fun submit(attemptId: String, expectedRevision: Long, submitKey: String): SubmitReplyUi
    suspend fun loadResult(attemptId: String): QuizResultUi
    suspend fun reviewAnswer(
        attemptId: String,
        itemId: String,
    ): AiRubricReviewer.ReviewOutcome =
        AiRubricReviewer.ReviewOutcome.Failed("AI_REVIEW_UNAVAILABLE", "当前入口不支持 AI 复核", retryable = false)
    suspend fun adoptAiReview(
        attemptId: String,
        suggestion: AiRubricReviewer.ReviewSuggestion,
    ): QuizResultUi = throw UnsupportedOperationException("AI 复核采纳不可用")
    suspend fun reviseGrade(attemptId: String, itemId: String, score: Int, note: String, assessmentKey: String): QuizResultUi
    /**
     * 显式移除一条 WRONG 错题记录（`favorites.source = WRONG`），仅在用户确认后调用。
     *
     * 只删除该用户该题的错题本行，不改作答、解析与成绩；没有记录时无副作用。
     * 修分改对**不会**自动调用它（设计 §16/§18：历史错题归属不确定，必须用户手动移除）。
     */
    fun observeManualFavorites(): Flow<Set<Long>> = kotlinx.coroutines.flow.flowOf(emptySet())
    suspend fun setManualFavorite(questionId: Long, selected: Boolean) = Unit
    suspend fun removeWrongFavorite(questionId: Long)
    suspend fun loadHistory(offset: Int, limit: Int): List<QuizHistoryItemUi>
    suspend fun loadHistoryPage(offset: Int, limit: Int): QuizHistoryPageUi {
        val items = loadHistory(offset, limit)
        return QuizHistoryPageUi(items, offset + items.size, items.size >= limit)
    }
    suspend fun retrainPaper(paperId: String): String
    /** 打开 READY paper 的首次作答（指定/新建 proposedAttemptId）。 */
    suspend fun openPaper(paperId: String): String
    /** 用原 paper 的 scope 重新生成一张新卷（新 requestKey；与重练原卷区分）。 */
    suspend fun regenerate(paperId: String): GenerationStartUi
}

data class SubmitReplyUi(
    val attemptId: String,
    val status: String,
    val gradingStatus: String,
    val gradedScore: Int,
    val maxScore: Int,
    val pendingCount: Int,
    val unansweredCount: Int,
    val alreadySubmitted: Boolean = false,
)

data class QuizResultUi(
    val attemptId: String,
    /** 原 paper：'重练原卷' 用同一 paper 新建 attempt；'重新生成' 用原 scope 起新运行。 */
    val paperId: String,
    val title: String,
    val courseLabel: String,
    val status: QuizAttemptStatusUi,
    val gradedScore: Int,
    val maxScore: Int,
    val pendingCount: Int,
    val elapsedMs: Long,
    val questions: List<QuizQuestionUi>,
    val gradeNote: String? = null,
    val aiReviewEnabled: Boolean = false,
) {
    val final: Boolean get() = status == QuizAttemptStatusUi.SUBMITTED_FINAL

    /** 当前仍在错题本（`favorites.source = WRONG`）的题数；0 表示结果页没有可移除项。 */
    val wrongCount: Int get() = questions.count { it.inWrongBook }
}

/**
 * 确定性 fake：生成阶段按真实顺序推进；结果里保留 2 道待自评（score=null），
 * 用于验证“不把 NULL 算 0 宣称终分”。不访问网络、不写库。
 */
class FakeQuizUiPort : QuizUiPort {
    private val attempts = HashMap<String, QuizAttemptUi>()

    /** 冒烟/单测里“用户确认移除”后的错题 id；只在本次 fake 实例内生效，不写库。 */
    private val removedWrongQuestionIds = LinkedHashSet<Long>()

    private var generationCancelled = false

    override suspend fun startGeneration(documentId: String, sectionId: String?): GenerationStartUi {
        generationCancelled = false
        return GenerationStartUi(generationRunId = RUNNING_ID, paperId = PAPER_ID)
    }

    override fun observeGeneration(generationRunId: String): Flow<QuizGenerationUi> = flow {
        val stages = listOf(
            QuizGenerationStageUi.REGISTERED to "已登记生成任务",
            QuizGenerationStageUi.READING to "读取章节与目录",
            QuizGenerationStageUi.PLANNING to "规划覆盖与难度",
            QuizGenerationStageUi.GENERATING to "生成 7 道选择题与 3 道简答题",
            QuizGenerationStageUi.VALIDATING to "校验题数、分数、rubric 与出处",
            QuizGenerationStageUi.PUBLISHING to "发布试卷快照",
        )
        stages.forEachIndexed { index, (stage, message) ->
            delay(80)
            if (generationCancelled) {
                emit(cancelledGeneration(generationRunId))
                return@flow
            }
            emit(
                QuizGenerationUi(
                    generationRunId = generationRunId,
                    paperId = PAPER_ID,
                    stage = stage,
                    message = message,
                    progressPercent = ((index + 1) * 100) / (stages.size + 1),
                ),
            )
        }
        delay(80)
        if (generationCancelled) {
            emit(cancelledGeneration(generationRunId))
            return@flow
        }
        emit(
            QuizGenerationUi(
                generationRunId = generationRunId,
                paperId = PAPER_ID,
                stage = QuizGenerationStageUi.READY,
                message = "试卷已就绪",
                progressPercent = 100,
                paperStatus = "READY",
                questionCount = 10,
                maxScore = 100,
                ready = true,
                terminal = true,
            ),
        )
    }

    override suspend fun cancelGeneration(generationRunId: String) {
        generationCancelled = true
    }

    private fun cancelledGeneration(runId: String) = QuizGenerationUi(
        generationRunId = runId,
        paperId = PAPER_ID,
        stage = QuizGenerationStageUi.CANCELLED,
        message = "已取消生成",
        terminal = true,
    )

    override suspend fun loadAttempt(attemptId: String): QuizAttemptUi {
        attempts[attemptId]?.let { return it }
        val attempt = fakeAttempt(attemptId)
        attempts[attemptId] = attempt
        return attempt
    }

    override suspend fun saveAnswer(attemptId: String, itemId: String, answer: String, editSequence: Long): Long {
        val current = attempts[attemptId] ?: fakeAttempt(attemptId)
        val revision = current.attemptRevision + 1
        attempts[attemptId] = current.copy(
            attemptRevision = revision,
            questions = current.questions.map {
                if (it.itemId == itemId) {
                    val parsed = com.example.myapplication.domain.quiz.QuizRawAnswers.parse(answer)
                    it.copy(savedAnswer = parsed.selectedOptionId ?: parsed.text.orEmpty())
                } else it
            },
        )
        return revision
    }

    override suspend fun submit(attemptId: String, expectedRevision: Long, submitKey: String): SubmitReplyUi {
        val current = attempts[attemptId] ?: fakeAttempt(attemptId)
        attempts[attemptId] = current.copy(status = QuizAttemptStatusUi.SUBMITTED_PENDING_REVIEW, pendingReviewCount = 2)
        return SubmitReplyUi(
            attemptId = attemptId,
            status = "SUBMITTED_PENDING_REVIEW",
            gradingStatus = "PENDING_REVIEW",
            gradedScore = current.questions.sumOf { it.score ?: 0 },
            maxScore = current.maxScore,
            pendingCount = 2,
            unansweredCount = current.unansweredCount,
        )
    }

    override suspend fun loadResult(attemptId: String): QuizResultUi {
        val questions = fakeAttempt(attemptId).questions.mapIndexed { index, question ->
            val graded = when (index) {
                0, 1, 2, 3, 4, 5, 6 -> question.copy(
                    score = if (index < 5) 10 else 0,
                    isCorrect = index < 5,
                    gradeStatus = QuizItemGradeStatusUi.GRADED,
                    analysis = "示例解析：说明正确选项的理由，并指出相近说法的差异。",
                )

                7 -> question.copy(score = 8, gradeStatus = QuizItemGradeStatusUi.GRADED, rubricLines = listOf("要点甲 4/5", "要点乙 4/5"))
                else -> question.copy(score = null, gradeStatus = QuizItemGradeStatusUi.PENDING_SELF_REVIEW, rubricLines = listOf("参考要点 1", "参考要点 2"))
            }
            // 每题都有 question 映射；第 7 题（0 分）模拟“已在错题本”，可被显式移除。
            val questionId = FAKE_QUESTION_ID_BASE + index
            graded.copy(
                questionId = questionId,
                inWrongBook = questionId == SAMPLE_WRONG_QUESTION_ID && questionId !in removedWrongQuestionIds,
            )
        }
        return QuizResultUi(
            attemptId = attemptId,
            paperId = PAPER_ID,
            title = "示例章节随堂测",
            courseLabel = "示例课程（非医学内容）",
            status = QuizAttemptStatusUi.SUBMITTED_PENDING_REVIEW,
            gradedScore = questions.sumOf { it.score ?: 0 },
            maxScore = 100,
            pendingCount = 2,
            elapsedMs = 9 * 60 * 1000L,
            questions = questions,
        )
    }

    override suspend fun reviseGrade(attemptId: String, itemId: String, score: Int, note: String, assessmentKey: String): QuizResultUi {
        val result = loadResult(attemptId)
        val questions = result.questions.map { question ->
            if (question.itemId == itemId) {
                question.copy(score = score.coerceIn(0, question.maxScore), gradeStatus = QuizItemGradeStatusUi.GRADED)
            } else {
                question
            }
        }
        val pending = questions.count { it.score == null }
        return result.copy(
            questions = questions,
            gradedScore = questions.sumOf { it.score ?: 0 },
            pendingCount = pending,
            status = if (pending == 0) QuizAttemptStatusUi.SUBMITTED_FINAL else QuizAttemptStatusUi.SUBMITTED_PENDING_REVIEW,
            gradeNote = "第 ${result.questions.indexOfFirst { it.itemId == itemId } + 1} 题已自评：$score 分",
        )
    }

    override suspend fun removeWrongFavorite(questionId: Long) {
        // fake 只记内存：移除后同一实例的 loadResult 不再标记该题在错题本。
        removedWrongQuestionIds += questionId
    }

    override suspend fun loadHistory(offset: Int, limit: Int): List<QuizHistoryItemUi> =
        if (offset == 0) SAMPLE_HISTORY else emptyList()

    override suspend fun retrainPaper(paperId: String): String = "attempt-retrain-${System.currentTimeMillis() % 1000}"

    override suspend fun openPaper(paperId: String): String = SAMPLE_ATTEMPT_ID

    override suspend fun regenerate(paperId: String): GenerationStartUi =
        GenerationStartUi(generationRunId = RUNNING_ID, paperId = PAPER_ID)

    companion object {
        const val PAPER_ID = "paper-fake-1"
        const val SAMPLE_ATTEMPT_ID = "attempt-fake-1"
        const val RUNNING_ID = "gen-run-fake-1"

        /** 示例错题：第 7 题（index=6，0 分）在错题本里，用于结果页“移除错题”冒烟/单测。 */
        const val SAMPLE_WRONG_QUESTION_ID = 1006L

        /** fake 题目的 questions.id 起点（= 1000 + ordinal）。 */
        private const val FAKE_QUESTION_ID_BASE = 1000L

        fun sampleAttempt(): QuizAttemptUi = fakeAttempt(SAMPLE_ATTEMPT_ID)

        fun sampleResult(): QuizResultUi = runCatching {
            kotlinx.coroutines.runBlocking { FakeQuizUiPort().loadResult(SAMPLE_ATTEMPT_ID) }
        }.getOrElse { error("fake result unavailable: ${it.message}") }

        private fun fakeAttempt(attemptId: String): QuizAttemptUi = QuizAttemptUi(
            attemptId = attemptId,
            paperId = PAPER_ID,
            title = "示例章节随堂测",
            courseLabel = "示例课程（非医学内容）",
            status = QuizAttemptStatusUi.IN_PROGRESS,
            attemptRevision = 3,
            currentOrdinal = 1,
            activeUsedMs = 4 * 60 * 1000L,
            questions = listOf(
                choice(0, "依据示例章节，下列哪一项最符合本节给出的定义？", "B", "定义二的说明", "示例要点：定义二"),
                choice(1, "示例章节把流程分成了几个阶段？", "C", "三个阶段", "示例要点：三阶段"),
                choice(2, "下列哪一项属于本节列出的前置条件？", "A", "前置条件一", "示例要点：前置条件"),
                choice(3, "本节强调的核对方式是什么？", "D", "逐条回查原文", "示例要点：回查原文"),
                choice(4, "关于示例概念，下列说法正确的是？", "B", "说法二", "示例要点：说法二"),
                choice(5, "本节示例中，首先记录的内容是？", "A", "记录一", "示例要点：记录一"),
                choice(6, "下列哪一项不属于本节范围？", "C", "范围外示例", "示例要点：排除项"),
                essay(7, "概括示例章节提出的两个基本要求。", "要求甲；要求乙", listOf("参考要点：要求甲（5分）", "参考要点：要求乙（5分）")),
                essay(8, "说明示例流程中“校验”环节的作用。", "校验环节用于确认输入与输出一致。", listOf("参考要点：确认一致（5分）", "参考要点：记录结果（5分）")),
                essay(9, "举例说明示例概念在真实场景中的限制。", "示例限制：仅适用于给定范围。", listOf("参考要点：范围限制（5分）", "参考要点：举例说明（5分）")),
            ),
        )

        private fun choice(ordinal: Int, stem: String, letter: String, answerText: String, point: String) = QuizQuestionUi(
            itemId = "item-$ordinal",
            ordinal = ordinal,
            type = QuizItemTypeUi.CHOICE,
            stem = stem,
            options = listOf(
                QuizOptionUi("A", "选项甲"),
                QuizOptionUi("B", "选项乙"),
                QuizOptionUi("C", "选项丙"),
                QuizOptionUi("D", "选项丁"),
            ),
            maxScore = 10,
            savedAnswer = if (ordinal < 2) letter else "",
            knowledgePoint = point,
            rubricLines = listOf("正确答案：$letter（$answerText）"),
            sources = listOf(
                AgentSourceUi(
                    ordinal = ordinal + 1,
                    documentId = "doc-sample",
                    documentTitle = "示例文档（非医学内容）",
                    sectionId = "sec-1",
                    sectionTitle = "第一节 示例",
                    quote = "这是用于界面冒烟的示例原文片段，不构成任何事实或医学结论。",
                    locatorJson = "{\"schemaVersion\":1}",
                ),
            ),
        )

        private fun essay(ordinal: Int, stem: String, reference: String, rubric: List<String>) = QuizQuestionUi(
            itemId = "item-$ordinal",
            ordinal = ordinal,
            type = QuizItemTypeUi.ESSAY,
            stem = stem,
            maxScore = 10,
            savedAnswer = if (ordinal == 7) "我的作答示例" else "",
            rubricLines = rubric,
            knowledgePoint = "参考要点：$reference",
            sources = listOf(
                AgentSourceUi(
                    ordinal = ordinal + 1,
                    documentId = "doc-removed",
                    documentTitle = "已删除的示例文档",
                    sectionId = null,
                    sectionTitle = null,
                    quote = "历史 quote 仍应可见，但来源已失效。",
                    locatorJson = null,
                    unavailableReason = "来源已删除，无法精确跳转",
                ),
            ),
        )

        private val SAMPLE_HISTORY = listOf(
            QuizHistoryItemUi(
                paperId = PAPER_ID,
                attemptId = SAMPLE_ATTEMPT_ID,
                title = "示例章节随堂测",
                courseLabel = "示例课程（非医学内容）",
                statusLabel = "待自评 2 题",
                scoreLabel = "已评 78/100（未终分）",
                submittedAtLabel = "今天 15:20",
                generatedByLabel = "AI 生成",
            ),
            QuizHistoryItemUi(
                paperId = "paper-offline-1",
                attemptId = "attempt-offline-1",
                title = "离线练习（6 题）",
                courseLabel = "示例内置章节",
                statusLabel = "已评",
                scoreLabel = "已评 45/60",
                submittedAtLabel = "昨天 20:05",
                generatedByLabel = "离线模板",
            ),
            QuizHistoryItemUi(
                paperId = "paper-fake-2",
                attemptId = "attempt-fake-2",
                title = "上一版示例章节随堂测",
                courseLabel = "示例课程（非医学内容）",
                statusLabel = "已评",
                scoreLabel = "已评 100/100",
                submittedAtLabel = "10-18",
                generatedByLabel = "AI 生成",
            ),
        )
    }
}
