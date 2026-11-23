package com.example.myapplication.data.quiz

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.QuizAttemptDao
import com.example.myapplication.data.local.entity.FavoriteEntity
import com.example.myapplication.data.local.entity.QuizGradeEventEntity
import com.example.myapplication.domain.quiz.QuizItemSnapshot
import com.example.myapplication.domain.reader.AccessContext
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 用户对某一题的修分结论（自评/AI 复核共用）。[isCorrect] 缺省时按分值推导。 */
data class GradeAssessment(
    val score: Int,
    val isCorrect: Boolean? = null,
    val gradingMethod: String = GRADING_METHOD_SELF_REVIEW,
    val note: String = "",
) {
    companion object {
        const val GRADING_METHOD_SELF_REVIEW = "SELF_REVIEW"
        const val GRADING_METHOD_AI_REVIEW = "AI_REVIEW"
    }
}

/**
 * 修分结果（FX-18/Q-04 / 设计 §16）。
 *
 * [Applied.alreadyApplied] 为 true 表示同一 assessmentKey 已处理过（幂等回执，
 * grade_revision 不再增长）；[Duplicate] 用于携带原始事件内容；
 * [Conflict] 表示 baseGradeRevision 已过期，调用方需重新读取后再修。
 */
sealed interface ReviseGradeResult {
    data class Applied(
        val attemptId: String,
        val itemId: String,
        val gradeRevision: Long,
        val status: String,
        val gradingStatus: String,
        val totalScore: Int?,
        val gradedScore: Int,
        val pendingCount: Int,
        val alreadyApplied: Boolean,
    ) : ReviseGradeResult

    data class Duplicate(val gradeRevision: Long, val newAssessmentJson: String) : ReviseGradeResult

    data class Conflict(val currentGradeRevision: Long) : ReviseGradeResult

    data class Rejected(val code: String) : ReviseGradeResult

    data class Failed(val code: String, val retryable: Boolean) : ReviseGradeResult
}

/**
 * 修分服务（W4-10 / 设计 §16、契约 §5.5）。
 *
 * 规则：
 *  - 只有已交卷 attempt 能修分；原答案不可再改（saveAnswer 已拒绝非 IN_PROGRESS）；
 *  - `assessmentKey` 唯一幂等：重复同一 key 返回原结果，`grade_revision` 只增一次；
 *  - 用 `baseGradeRevision` CAS 防旧复核覆盖新结果（与 attempt.revision 分离）；
 *  - 同事务写 grade event（old/new）、更新该题评分、重算 total/待评状态；
 *  - 修分**不**消费学习日统计（不写 activity、不改 quiz_count）；
 *  - 改成全对不自动删除既有 WRONG 收藏（可能来自旧随机考试/手动添加）；
 *    改成错可 INSERT OR IGNORE，不重复收录。
 */
class GradeAssessmentService(
    private val db: AppDatabase,
    private val repository: QuizRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val attemptDao get() = db.quizAttemptDao()
    private val favoriteDao get() = db.favoriteDao()

    suspend fun reviseGrade(
        ctx: AccessContext,
        attemptId: String,
        itemId: String,
        baseGradeRevision: Long,
        newAssessment: GradeAssessment,
        reason: String,
        assessmentKey: String,
        now: Long = clock(),
    ): ReviseGradeResult {
        if (assessmentKey.isBlank()) return ReviseGradeResult.Rejected(REJECT_INVALID_ASSESSMENT_KEY)
        if (reason.isBlank()) return ReviseGradeResult.Rejected(REJECT_REASON_REQUIRED)

        val snapshot = repository.attemptSnapshot(ctx, attemptId)
            ?: return ReviseGradeResult.Rejected(REJECT_ATTEMPT_NOT_FOUND)
        val attempt = snapshot.attempt
        if (!attempt.isSubmitted) return ReviseGradeResult.Rejected(REJECT_ATTEMPT_NOT_SUBMITTED)

        val item = snapshot.paper.items.firstOrNull { it.itemId == itemId }
            ?: return ReviseGradeResult.Rejected(REJECT_ITEM_NOT_IN_PAPER)
        if (newAssessment.score !in 0..item.score) {
            return ReviseGradeResult.Rejected(REJECT_SCORE_OUT_OF_RANGE)
        }
        val derivedCorrect = when {
            newAssessment.score == item.score -> true
            newAssessment.score == 0 -> false
            else -> null
        }
        if (newAssessment.isCorrect != null && newAssessment.isCorrect != derivedCorrect) {
            return ReviseGradeResult.Rejected(REJECT_INCONSISTENT_ASSESSMENT)
        }
        val previousEvent = attemptDao.findGradeEvent(attemptId, assessmentKey)
        if (previousEvent != null) {
            return ReviseGradeResult.Duplicate(previousEvent.gradeRevision, previousEvent.newAssessmentJson)
        }
        if (attempt.gradeRevision != baseGradeRevision) {
            return ReviseGradeResult.Conflict(attempt.gradeRevision)
        }

        val newAssessmentJson = encodeAssessmentJson(
            score = newAssessment.score,
            isCorrect = derivedCorrect,
            gradingMethod = newAssessment.gradingMethod,
            note = newAssessment.note,
            reason = reason,
        )
        val previousAnswer = snapshot.answerFor(itemId)
        val oldAssessmentJson = previousAnswer?.let { answer ->
            // 旧值保持原样：旧分可能是 NULL（待评），审计 JSON 里必须是 null 而不是 0。
            encodeAssessmentJson(
                score = answer.score,
                isCorrect = answer.isCorrect,
                gradingMethod = answer.gradingMethod,
                note = answer.feedback,
                reason = answer.feedback,
            )
        }

        val txOutcome = try {
            db.withTransaction {
                // 并发下唯一索引兜底：同 assessmentKey 只允许一条事件。
                val raced = attemptDao.findGradeEvent(attemptId, assessmentKey)
                if (raced != null) return@withTransaction TxOutcome.Duplicate(raced)
                attemptDao.insertGradeEvent(
                    QuizGradeEventEntity(
                        id = UUID.randomUUID().toString(),
                        attemptId = attemptId,
                        itemId = itemId,
                        assessmentKey = assessmentKey,
                        gradeRevision = baseGradeRevision + 1,
                        oldAssessmentJson = oldAssessmentJson,
                        newAssessmentJson = newAssessmentJson,
                        reason = reason,
                        createdAt = now,
                    ),
                )
                attemptDao.updateAnswerGrade(
                    attemptId = attemptId,
                    itemId = itemId,
                    score = newAssessment.score,
                    isCorrect = derivedCorrect,
                    matchedRubricJson = previousAnswer?.matchedRubricJson ?: "{}",
                    gradingMethod = newAssessment.gradingMethod,
                    feedback = reason,
                    now = now,
                )
                val pendingAfter = pendingCountAfterRegrade(attemptId, snapshot.paper.items)
                val totals = totalsAfterRegrade(attemptId, snapshot.paper.items, pendingAfter)
                val newStatus = attemptStatusFor(pendingAfter)
                val newGradingStatus = gradingStatusFor(pendingAfter)
                val changed = attemptDao.updateAttemptGrading(
                    attemptId = attemptId,
                    userId = ctx.userId,
                    expectedGradeRevision = baseGradeRevision,
                    totalScore = totals,
                    gradingStatus = newGradingStatus,
                    status = newStatus,
                )
                if (changed == 0) return@withTransaction TxOutcome.Conflict
                if (newAssessment.score < item.score) {
                    // 改错可 INSERT OR IGNORE；改对不删旧 WRONG（FX-18）。
                    favoriteDao.insert(
                        FavoriteEntity(
                            userId = ctx.userId,
                            questionId = snapshot.itemQuestionIds.getValue(itemId),
                            source = QuizRepository.FAVORITE_SOURCE_WRONG,
                            createdAt = now,
                        ),
                    )
                }
                TxOutcome.Applied(
                    gradeRevision = baseGradeRevision + 1,
                    status = newStatus,
                    gradingStatus = newGradingStatus,
                    totalScore = totals,
                    gradedScore = totals ?: gradedScoreAfterRegrade(attemptId, snapshot.paper.items),
                    pendingCount = pendingAfter,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (duplicate: SQLiteConstraintException) {
            val raced = attemptDao.findGradeEvent(attemptId, assessmentKey)
            if (raced != null) {
                TxOutcome.Duplicate(raced)
            } else {
                TxOutcome.Failed
            }
        } catch (error: Throwable) {
            TxOutcome.Failed
        }

        return when (val outcome = txOutcome) {
            is TxOutcome.Applied -> ReviseGradeResult.Applied(
                attemptId = attemptId,
                itemId = itemId,
                gradeRevision = outcome.gradeRevision,
                status = outcome.status,
                gradingStatus = outcome.gradingStatus,
                totalScore = outcome.totalScore,
                gradedScore = outcome.gradedScore,
                pendingCount = outcome.pendingCount,
                alreadyApplied = false,
            )

            is TxOutcome.Duplicate -> ReviseGradeResult.Duplicate(outcome.event.gradeRevision, outcome.event.newAssessmentJson)

            TxOutcome.Conflict -> ReviseGradeResult.Conflict(
                currentGradeRevision = attemptDao.findAttemptForUser(ctx.userId, attemptId)?.gradeRevision ?: attempt.gradeRevision,
            )

            TxOutcome.Failed -> ReviseGradeResult.Failed(code = "REGRAVE_FAILED", retryable = true)
        }
    }

    /** 修分历史（结果页/AI 复核审计用）。 */
    suspend fun gradeEvents(ctx: AccessContext, attemptId: String): List<QuizGradeEventView> {
        attemptDao.findAttemptForUser(ctx.userId, attemptId) ?: return emptyList()
        return attemptDao.findGradeEvents(attemptId).map { event ->
            QuizGradeEventView(
                id = event.id,
                itemId = event.itemId,
                assessmentKey = event.assessmentKey,
                gradeRevision = event.gradeRevision,
                oldAssessmentJson = event.oldAssessmentJson,
                newAssessmentJson = event.newAssessmentJson,
                reason = event.reason,
                createdAt = event.createdAt,
            )
        }
    }

    // ---- 重算 ----

    private suspend fun pendingCountAfterRegrade(attemptId: String, items: List<QuizItemSnapshot>): Int {
        val answers = attemptDao.findAnswers(attemptId).associateBy { it.itemId }
        return items.count { answers[it.itemId]?.score == null }
    }

    private suspend fun totalsAfterRegrade(
        attemptId: String,
        items: List<QuizItemSnapshot>,
        pendingCount: Int,
    ): Int? {
        if (pendingCount > 0) return null
        val answers = attemptDao.findAnswers(attemptId).associateBy { it.itemId }
        return items.sumOf { answers[it.itemId]?.score ?: 0 }
    }

    private suspend fun gradedScoreAfterRegrade(attemptId: String, items: List<QuizItemSnapshot>): Int {
        val answers = attemptDao.findAnswers(attemptId).associateBy { it.itemId }
        return items.sumOf { answers[it.itemId]?.score ?: 0 }
    }

    private fun encodeAssessmentJson(
        score: Int?,
        isCorrect: Boolean?,
        gradingMethod: String,
        note: String,
        reason: String,
    ): String = buildJsonObject {
        // 旧分可能是 NULL（待评）：审计 JSON 保留 null，不能写成 0。
        if (score != null) {
            put("score", score)
        } else {
            put("score", kotlinx.serialization.json.JsonNull)
        }
        if (isCorrect != null) {
            put("isCorrect", JsonPrimitive(isCorrect))
        } else {
            put("isCorrect", kotlinx.serialization.json.JsonNull)
        }
        put("gradingMethod", gradingMethod)
        put("note", note)
        put("reason", reason)
    }.toString()

    private sealed interface TxOutcome {
        data class Applied(
            val gradeRevision: Long,
            val status: String,
            val gradingStatus: String,
            val totalScore: Int?,
            val gradedScore: Int,
            val pendingCount: Int,
        ) : TxOutcome

        data class Duplicate(val event: QuizGradeEventEntity) : TxOutcome
        data object Conflict : TxOutcome
        data object Failed : TxOutcome
    }

    companion object {
        const val REJECT_ATTEMPT_NOT_FOUND = "ATTEMPT_NOT_FOUND"
        const val REJECT_ATTEMPT_NOT_SUBMITTED = "ATTEMPT_NOT_SUBMITTED"
        const val REJECT_ITEM_NOT_IN_PAPER = "ITEM_NOT_IN_PAPER"
        const val REJECT_SCORE_OUT_OF_RANGE = "SCORE_OUT_OF_RANGE"
        const val REJECT_INCONSISTENT_ASSESSMENT = "INCONSISTENT_ASSESSMENT"
        const val REJECT_INVALID_ASSESSMENT_KEY = "INVALID_ASSESSMENT_KEY"
        const val REJECT_REASON_REQUIRED = "REASON_REQUIRED"
    }
}

/** 修分事件只读视图。 */
data class QuizGradeEventView(
    val id: String,
    val itemId: String,
    val assessmentKey: String,
    val gradeRevision: Long,
    val oldAssessmentJson: String?,
    val newAssessmentJson: String,
    val reason: String,
    val createdAt: Long,
)

