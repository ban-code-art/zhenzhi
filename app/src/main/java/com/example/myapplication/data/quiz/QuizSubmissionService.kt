package com.example.myapplication.data.quiz

import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.LearningActivityDao
import com.example.myapplication.data.local.entity.FavoriteEntity
import com.example.myapplication.data.local.entity.LearningActivityEventEntity
import com.example.myapplication.data.local.entity.QuizAttemptAnswerEntity
import com.example.myapplication.data.local.entity.StudyProgressEntity
import com.example.myapplication.domain.quiz.QuizGradingStatus
import com.example.myapplication.domain.quiz.QuizItemGrade
import com.example.myapplication.domain.quiz.QuizItemSnapshot
import com.example.myapplication.domain.quiz.QuizPaperSnapshot
import com.example.myapplication.domain.quiz.QuizScoreSummary
import com.example.myapplication.domain.reader.AccessContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 交卷结果（FX-17/Q-05 / 契约 §5.5、设计 §8/§15）。
 *
 * - [Submitted.alreadySubmitted] 为 true 表示同一 attempt+submitKey 之前已提交，
 *   本次只是幂等回执，**没有**重复写评分/错题/学习日；
 * - [Conflict] 表示期间有新作答（attempt.revision 已前进），必须重新读取后重试，
 *   绝不能把旧 revision 的评分提交为结果（FX-23）；
 * - [Failed] 表示事务失败（已回滚），用同一 submitKey 重试即可。
 */
sealed interface SubmitResult {
    data class Submitted(
        val attemptId: String,
        val status: String,
        val gradingStatus: String,
        val totalScore: Int?,
        val gradedScore: Int,
        val maxScore: Int,
        val pendingCount: Int,
        val unansweredCount: Int,
        val wrongItemCount: Int,
        val alreadySubmitted: Boolean,
        /** 本次是否首次消费学习日统计（同一 submitKey 只消费一次）。 */
        val activityConsumed: Boolean,
    ) : SubmitResult

    data class Conflict(val currentRevision: Long) : SubmitResult

    data class Rejected(val code: String) : SubmitResult

    data class Failed(val code: String, val retryable: Boolean) : SubmitResult
}

/**
 * 交卷服务（W4-09 / 契约 §5.5、§8.3；设计 §8/§15/§16）。
 *
 * 顺序严格按契约：
 *  1. 事务外基于 `expectedAttemptRevision` 快照做本地评分（[LocalRubricGrader]）；
 *  2. 进事务后先 CAS 整卷 revision + submit_key；影响 0 行 → CONFLICT/AlreadySubmitted，
 *     不写任何评分；
 *  3. 同一事务内写每题评分、favorites WRONG（去重）、学习日事件（dedup=submitKey）、
 *     study_progress 的 quiz 字段局部更新（保留 completed/id；无 legacy chapter 不写）。
 *
 * [transactionFaultHook] 仅供测试注入“事务内故障”（FX-17 重试场景），生产保持 null。
 */
class QuizSubmissionService(
    private val db: AppDatabase,
    private val repository: QuizRepository,
    private val grader: LocalRubricGrader = LocalRubricGrader(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val transactionFaultHook: (() -> Unit)? = null,
) {
    private val attemptDao get() = db.quizAttemptDao()
    private val favoriteDao get() = db.favoriteDao()
    private val activityDao get() = db.learningActivityDao()
    private val studyProgressDao get() = db.studyProgressDao()
    private val questionDao get() = db.questionDao()

    suspend fun submit(
        ctx: AccessContext,
        attemptId: String,
        expectedAttemptRevision: Long,
        submitKey: String,
        now: Long = clock(),
        timezoneId: String = TimeZone.getDefault().id,
    ): SubmitResult {
        val snapshot = repository.attemptSnapshot(ctx, attemptId)
            ?: return SubmitResult.Rejected(REJECT_ATTEMPT_NOT_FOUND)
        if (snapshot.attempt.submitKey != submitKey) {
            return SubmitResult.Rejected(REJECT_SUBMIT_KEY_MISMATCH)
        }
        if (snapshot.attempt.isSubmitted) {
            // 已交卷：同 key 幂等回执（不同 key 已被上面的 submitKey 校验拒绝）。
            return alreadySubmittedResult(snapshot)
        }
        if (snapshot.attempt.revision != expectedAttemptRevision) {
            // FX-23：期间有新作答，旧 revision 不能提交旧分；此时未做任何写入。
            return SubmitResult.Conflict(snapshot.attempt.revision)
        }

        val grades = gradeSnapshot(snapshot)
        val summary = QuizScoreSummary.of(snapshot.paper, grades)
        val status = attemptStatusFor(summary.pendingCount)
        val gradingStatus = gradingStatusFor(summary.pendingCount)

        val txOutcome = try {
            db.withTransaction {
                transactionFaultHook?.invoke()
                when (
                    val outcome = attemptDao.submitAttempt(
                        userId = ctx.userId,
                        attemptId = attemptId,
                        expectedRevision = expectedAttemptRevision,
                        submitKey = submitKey,
                        status = status,
                        gradingStatus = gradingStatus,
                        totalScore = summary.totalScore,
                        now = now,
                    )
                ) {
                    com.example.myapplication.data.local.dao.SubmitOutcome.Submitted -> {
                        writeGrades(attemptId, snapshot, grades, now)
                        writeWrongFavorites(ctx.userId, snapshot, grades, now)
                        recordSubmissionActivity(ctx.userId, submitKey, now, timezoneId)
                        updateCourseProgress(ctx.userId, snapshot, summary, now)
                        TxOutcome.Submitted
                    }

                    com.example.myapplication.data.local.dao.SubmitOutcome.AlreadySubmitted -> TxOutcome.Already
                    com.example.myapplication.data.local.dao.SubmitOutcome.RevisionConflict -> TxOutcome.Conflict
                    com.example.myapplication.data.local.dao.SubmitOutcome.Rejected -> TxOutcome.Rejected
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // 事务已回滚：attemp 仍 IN_PROGRESS、没有任何评分/错题/统计写入，可同 key 重试。
            return SubmitResult.Failed(code = "TRANSACTION_FAILED", retryable = true)
        }

        return when (txOutcome) {
            TxOutcome.Submitted -> SubmitResult.Submitted(
                attemptId = attemptId,
                status = status,
                gradingStatus = gradingStatus,
                totalScore = summary.totalScore,
                gradedScore = summary.gradedScore,
                maxScore = snapshot.paper.maxScore,
                pendingCount = summary.pendingCount,
                unansweredCount = summary.unansweredCount,
                wrongItemCount = grades.count { isWrongForFavorite(snapshot.paper, it) },
                alreadySubmitted = false,
                activityConsumed = true,
            )

            TxOutcome.Already -> {
                val reread = repository.attemptSnapshot(ctx, attemptId)
                if (reread == null) SubmitResult.Rejected(REJECT_ATTEMPT_NOT_FOUND) else alreadySubmittedResult(reread)
            }

            TxOutcome.Conflict -> SubmitResult.Conflict(
                currentRevision = attemptDao.readRevision(attemptId, ctx.userId) ?: snapshot.attempt.revision,
            )

            TxOutcome.Rejected -> SubmitResult.Rejected(REJECT_SUBMIT_REJECTED)
        }
    }

    // ---- 事务外评分 ----

    private fun gradeSnapshot(snapshot: QuizAttemptSnapshot): List<QuizItemGrade> =
        snapshot.paper.items.map { item ->
            val rawAnswer = snapshot.answers[item.itemId]?.rawAnswerJson
            grader.grade(item, rawAnswer)
        }

    private suspend fun alreadySubmittedResult(snapshot: QuizAttemptSnapshot): SubmitResult.Submitted {
        val grades = snapshot.paper.items.map { item ->
            val answer = snapshot.answers[item.itemId]
            QuizItemGrade(
                itemOrdinal = item.ordinal,
                itemType = item.type,
                normalizedAnswer = answer?.rawAnswerJson.orEmpty(),
                score = answer?.score,
                isCorrect = answer?.isCorrect,
                status = when {
                    answer?.score == null -> QuizGradingStatus.PENDING_SELF_REVIEW
                    answer.rawAnswerJson.isNullOrBlank() -> QuizGradingStatus.UNANSWERED
                    else -> QuizGradingStatus.GRADED
                },
                gradingMethod = com.example.myapplication.domain.quiz.QuizGradingMethod.entries
                    .firstOrNull { it.name == answer?.gradingMethod }
                    ?: com.example.myapplication.domain.quiz.QuizGradingMethod.NONE,
                feedback = answer?.feedback.orEmpty(),
            )
        }
        val summary = QuizScoreSummary.of(snapshot.paper, grades)
        return SubmitResult.Submitted(
            attemptId = snapshot.attempt.attemptId,
            status = snapshot.attempt.status,
            gradingStatus = snapshot.attempt.gradingStatus,
            totalScore = snapshot.attempt.totalScore,
            gradedScore = summary.gradedScore,
            maxScore = snapshot.paper.maxScore,
            pendingCount = grades.count { it.score == null },
            unansweredCount = summary.unansweredCount,
            wrongItemCount = grades.count { isWrongForFavorite(snapshot.paper, it) },
            alreadySubmitted = true,
            activityConsumed = false,
        )
    }

    // ---- 事务内落库 ----

    private suspend fun writeGrades(
        attemptId: String,
        snapshot: QuizAttemptSnapshot,
        grades: List<QuizItemGrade>,
        now: Long,
    ) {
        grades.forEachIndexed { index, grade ->
            val item = snapshot.paper.items[index]
            val itemId = item.itemId ?: return@forEachIndexed
            val matchedRubricJson = encodeMatchedRubric(grade)
            val existing = attemptDao.findAnswer(attemptId, itemId)
            if (existing == null) {
                // 未作答题也要落一行：score=0（未作答）或 NULL（待评）必须在结果页可区分。
                attemptDao.insertAnswerIfAbsent(
                    QuizAttemptAnswerEntity(
                        attemptId = attemptId,
                        itemId = itemId,
                        rawAnswerJson = null,
                        editSequence = 0,
                        score = grade.score,
                        isCorrect = grade.isCorrect,
                        matchedRubricJson = matchedRubricJson,
                        gradingMethod = grade.gradingMethod.name,
                        feedback = grade.feedback,
                        updatedAt = now,
                    ),
                )
            } else {
                attemptDao.updateAnswerGrade(
                    attemptId = attemptId,
                    itemId = itemId,
                    score = grade.score,
                    isCorrect = grade.isCorrect,
                    matchedRubricJson = matchedRubricJson,
                    gradingMethod = grade.gradingMethod.name,
                    feedback = grade.feedback,
                    now = now,
                )
            }
        }
    }

    private suspend fun writeWrongFavorites(
        userId: Long,
        snapshot: QuizAttemptSnapshot,
        grades: List<QuizItemGrade>,
        now: Long,
    ) {
        grades.forEachIndexed { index, grade ->
            val item = snapshot.paper.items[index]
            if (!isWrongForFavorite(snapshot.paper, grade)) return@forEachIndexed
            val itemId = item.itemId ?: return@forEachIndexed
            val questionId = snapshot.itemQuestionIds[itemId] ?: return@forEachIndexed
            favoriteDao.insert(
                FavoriteEntity(
                    userId = userId,
                    questionId = questionId,
                    source = QuizRepository.FAVORITE_SOURCE_WRONG,
                    createdAt = now,
                ),
            )
        }
    }

    private suspend fun recordSubmissionActivity(userId: Long, submitKey: String, now: Long, timezoneId: String) {
        activityDao.recordEvent(
            LearningActivityEventEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                // dedup 用 submitKey：双点/重试/事务失败后重试都只消费一次学习日。
                dedupKey = QuizRepository.ACTIVITY_DEDUP_PREFIX + submitKey,
                kind = QuizRepository.ACTIVITY_KIND_QUIZ_SUBMIT,
                localDate = localDateOf(now, timezoneId),
                timezoneId = timezoneId,
                readingDeltaMs = 0,
                quizDelta = 1,
                createdAt = now,
            ),
        )
    }

    /**
     * study_progress 只写 quiz 字段：保留原 id 与 completed；没有真实 legacy chapter 的
     * 导入题（chapter_id 为空）不写该表（契约 §5.5 / 设计 §16）。待评时不覆盖上一份完整成绩。
     */
    private suspend fun updateCourseProgress(
        userId: Long,
        snapshot: QuizAttemptSnapshot,
        summary: QuizScoreSummary,
        now: Long,
    ) {
        if (summary.pendingCount > 0) return
        val chapterId = snapshot.paper.items.firstNotNullOfOrNull { item ->
            val itemId = item.itemId ?: return@firstNotNullOfOrNull null
            val questionId = snapshot.itemQuestionIds[itemId] ?: return@firstNotNullOfOrNull null
            questionDao.findById(questionId)?.chapterId
        } ?: return
        val updated = studyProgressDao.updateQuizFields(
            userId = userId,
            chapterId = chapterId,
            score = summary.gradedScore,
            total = snapshot.paper.maxScore,
            now = now,
        )
        if (updated == 0) {
            studyProgressDao.insertIfAbsent(
                StudyProgressEntity(
                    userId = userId,
                    chapterId = chapterId,
                    completed = false,
                    quizScore = summary.gradedScore,
                    quizTotal = snapshot.paper.maxScore,
                    updatedAt = now,
                ),
            )
        }
    }

    private sealed interface TxOutcome {
        data object Submitted : TxOutcome
        data object Already : TxOutcome
        data object Conflict : TxOutcome
        data object Rejected : TxOutcome
    }

    companion object {
        const val REJECT_ATTEMPT_NOT_FOUND = "ATTEMPT_NOT_FOUND"
        const val REJECT_SUBMIT_KEY_MISMATCH = "SUBMIT_KEY_MISMATCH"
        const val REJECT_SUBMIT_REJECTED = "SUBMIT_REJECTED"
    }
}

/** 全部已评 → FINAL，否则 PENDING_REVIEW（NULL 不当 0，§15）。 */
internal fun attemptStatusFor(pendingCount: Int): String =
    if (pendingCount == 0) {
        com.example.myapplication.data.local.dao.QuizAttemptDao.STATUS_SUBMITTED_FINAL
    } else {
        com.example.myapplication.data.local.dao.QuizAttemptDao.STATUS_SUBMITTED_PENDING_REVIEW
    }

internal fun gradingStatusFor(pendingCount: Int): String =
    if (pendingCount == 0) QuizRepository.GRADING_FINAL else QuizRepository.GRADING_PENDING_REVIEW

/** 错题判定：已评且得分 < 满分（含部分分）；待评（score NULL）不自动入错题本。 */
internal fun isWrongForFavorite(paper: QuizPaperSnapshot, grade: QuizItemGrade): Boolean {
    val score = grade.score ?: return false
    val maxScore = paper.items.firstOrNull { it.ordinal == grade.itemOrdinal }?.score ?: return false
    return score < maxScore
}

/** matched_rubric_json：命中/缺失/冲突要点，结果页与修分历史共用。 */
internal fun encodeMatchedRubric(grade: QuizItemGrade): String = buildJsonObject {
    put(
        "matched",
        kotlinx.serialization.json.buildJsonArray { grade.matchedPoints.forEach { add(it) } },
    )
    put(
        "missed",
        kotlinx.serialization.json.buildJsonArray { grade.missedPoints.forEach { add(it) } },
    )
    put(
        "conflicted",
        kotlinx.serialization.json.buildJsonArray { grade.conflictedPoints.forEach { add(it) } },
    )
    put("status", grade.status.name)
    put("rubricVersion", grade.rubricVersion)
}.toString()

/** 学习日按事件发生时的设备时区取日期；minSdk 24 不支持 java.time，用 SimpleDateFormat。 */
internal fun localDateOf(now: Long, timezoneId: String): String {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    format.timeZone = TimeZone.getTimeZone(timezoneId)
    return format.format(Date(now))
}
