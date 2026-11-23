package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.myapplication.data.local.entity.QuizAttemptAnswerEntity
import com.example.myapplication.data.local.entity.QuizAttemptEntity
import com.example.myapplication.data.local.entity.QuizGradeEventEntity
import kotlinx.coroutines.flow.Flow

/** Read-only attempt/paper projection for a course directory; neither row is rewritten. */
data class CourseQuizAttemptSummary(
    val attemptId: String,
    val userId: Long,
    val paperUserId: Long,
    val paperId: String,
    val documentId: String?,
    val scopeJson: String,
    val sourceRevision: String,
    val paperStatus: String,
    val maxScore: Int,
    val status: String,
    val gradingStatus: String,
    val startedAt: Long,
    val submittedAt: Long?,
    val totalScore: Int?,
    val gradeRevision: Long,
    val currentDocumentRevision: String? = sourceRevision,
)

/** 幂等开卷结果：同 proposedAttemptId 重试返回既有 attempt，不暗建多份。 */
sealed interface OpenAttemptOutcome {
    data class Opened(val attempt: QuizAttemptEntity) : OpenAttemptOutcome
    data class Reused(val attempt: QuizAttemptEntity) : OpenAttemptOutcome
    data class Rejected(val reason: String) : OpenAttemptOutcome
}

/**
 * 单题作答保存结果（第四期 §8 / 契约 §8.3）：
 *  - 只有 editSequence 高于已存值才接受并推进 `quiz_attempts.revision`；
 *  - 旧/重复 editSequence 返回当前 revision 与已存序号（Ack，不覆盖新答案）；
 *  - attempt 不属于当前 user、已交卷或 item 不属于本卷时拒绝。
 */
sealed interface SaveAnswerOutcome {
    data class Accepted(val attemptRevision: Long, val editSequence: Long) : SaveAnswerOutcome
    data class StaleEdit(val attemptRevision: Long, val editSequence: Long) : SaveAnswerOutcome
    data object Rejected : SaveAnswerOutcome
}

/** 交卷 CAS 结果：RevisionConflict 供调用方重新读取后重试同一 submitKey。 */
sealed interface SubmitOutcome {
    data object Submitted : SubmitOutcome
    data object AlreadySubmitted : SubmitOutcome
    data object RevisionConflict : SubmitOutcome
    data object Rejected : SubmitOutcome
}

/**
 * 随堂测作答（schema v6 / 第四期 §8、§15，契约 §5.5、§8.3）。
 *
 * 不变式：
 *  - 答案写入与 attempt.revision 自增同事务，只有新 editSequence 才增版本；
 *  - 交卷用 (user, status, expectedRevision, submitKey) CAS，重试/双点不会重复收题；
 *  - 修分只推进 grade_revision，不修改原答案、不重跑首次交卷统计。
 */
@Dao
interface QuizAttemptDao {
    /** Both owners and the caller's course documents bound this read; source changes invalidate it. */
    @Query(
        "SELECT a.id AS attemptId, a.user_id AS userId, p.user_id AS paperUserId, " +
            "a.paper_id AS paperId, p.document_id AS documentId, p.scope_json AS scopeJson, " +
            "p.source_revision AS sourceRevision, p.status AS paperStatus, p.max_score AS maxScore, " +
            "a.status AS status, a.grading_status AS gradingStatus, a.started_at AS startedAt, " +
            "a.submitted_at AS submittedAt, a.total_score AS totalScore, a.grade_revision AS gradeRevision, " +
            "CASE WHEN d.deleted_at IS NULL THEN d.content_revision ELSE NULL END AS currentDocumentRevision " +
            "FROM quiz_attempts a JOIN quiz_papers p ON p.id = a.paper_id " +
            "LEFT JOIN reader_documents d ON d.id = p.document_id " +
            "WHERE a.user_id = :userId AND p.user_id = :userId AND p.document_id IN (:documentIds) " +
            "ORDER BY COALESCE(a.submitted_at, a.started_at) DESC, a.id DESC",
    )
    fun observeCourseAttempts(userId: Long, documentIds: List<String>): Flow<List<CourseQuizAttemptSummary>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAttemptIfAbsent(attempt: QuizAttemptEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAnswerIfAbsent(answer: QuizAttemptAnswerEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGradeEvent(event: QuizGradeEventEntity)

    /**
     * openAttempt：同 proposedAttemptId 幂等复用既有 attempt；同 submitKey 已属其他
     * attempt 时拒绝（唯一索引兜底，不能悄悄改写成另一份卷）。
     */
    @Transaction
    suspend fun openAttempt(attempt: QuizAttemptEntity): OpenAttemptOutcome {
        val existingById = findAttempt(attempt.id)
        if (existingById != null) {
            return if (existingById.userId == attempt.userId && existingById.paperId == attempt.paperId) {
                OpenAttemptOutcome.Reused(existingById)
            } else {
                OpenAttemptOutcome.Rejected("ATTEMPT_ID_CONFLICT")
            }
        }
        if (findAttemptBySubmitKey(attempt.submitKey) != null) {
            return OpenAttemptOutcome.Rejected("SUBMIT_KEY_CONFLICT")
        }
        insertAttemptIfAbsent(attempt)
        val stored = findAttempt(attempt.id)
            ?: return OpenAttemptOutcome.Rejected("ATTEMPT_INSERT_FAILED")
        return OpenAttemptOutcome.Opened(stored)
    }

    /**
     * saveAnswer：单题作答保存（事务内）。接受条件全部在事务内核验：
     * user、IN_PROGRESS、item 属于本卷 paper、editSequence 严格递增。
     */
    @Transaction
    suspend fun saveAnswer(
        userId: Long,
        attemptId: String,
        itemId: String,
        rawAnswerJson: String?,
        editSequence: Long,
        now: Long,
    ): SaveAnswerOutcome {
        val attempt = findAttemptForUser(userId, attemptId) ?: return SaveAnswerOutcome.Rejected
        if (attempt.status != STATUS_IN_PROGRESS) return SaveAnswerOutcome.Rejected
        if (itemBelongsToPaper(itemId, attempt.paperId) == 0) return SaveAnswerOutcome.Rejected

        val existing = findAnswer(attemptId, itemId)
        if (existing != null && existing.editSequence >= editSequence) {
            val revision = readRevision(attemptId, userId) ?: attempt.revision
            return SaveAnswerOutcome.StaleEdit(revision, existing.editSequence)
        }
        val written = if (existing == null) {
            insertAnswerIfAbsent(
                QuizAttemptAnswerEntity(
                    attemptId = attemptId,
                    itemId = itemId,
                    rawAnswerJson = rawAnswerJson,
                    editSequence = editSequence,
                    updatedAt = now,
                ),
            ) != -1L
        } else {
            updateAnswerIfNewer(attemptId, itemId, rawAnswerJson, editSequence, now) == 1
        }
        if (!written) {
            val current = findAnswer(attemptId, itemId)
            val revision = readRevision(attemptId, userId) ?: attempt.revision
            return SaveAnswerOutcome.StaleEdit(revision, current?.editSequence ?: editSequence)
        }
        if (incrementAttemptRevision(attemptId, userId) != 1) {
            // 同事务串行下不应发生；防御性回滚答案写，禁止 revision 与答案不一致。
            if (existing == null) {
                deleteAnswer(attemptId, itemId)
            } else {
                restoreAnswer(attemptId, itemId, existing.rawAnswerJson, existing.editSequence, existing.updatedAt)
            }
            return SaveAnswerOutcome.Rejected
        }
        return SaveAnswerOutcome.Accepted(attempt.revision + 1, editSequence)
    }

    /**
     * submitAttempt：交卷 CAS。expectedRevision 由调用方在事务外判分前读取；
     * 期间任何新作答使 revision 前进，CAS 影响 0 行 → RevisionConflict，绝不提交旧分。
     * 已终态返回 AlreadySubmitted（同一 submitKey 幂等）。
     */
    @Transaction
    suspend fun submitAttempt(
        userId: Long,
        attemptId: String,
        expectedRevision: Long,
        submitKey: String,
        status: String,
        gradingStatus: String,
        totalScore: Int?,
        now: Long,
    ): SubmitOutcome {
        val attempt = findAttemptForUser(userId, attemptId) ?: return SubmitOutcome.Rejected
        if (attempt.submitKey != submitKey) return SubmitOutcome.Rejected
        if (attempt.status != STATUS_IN_PROGRESS) return SubmitOutcome.AlreadySubmitted
        val changed = submitCas(
            attemptId = attemptId,
            userId = userId,
            expectedRevision = expectedRevision,
            submitKey = submitKey,
            status = status,
            gradingStatus = gradingStatus,
            totalScore = totalScore,
            now = now,
        )
        return if (changed == 1) SubmitOutcome.Submitted else SubmitOutcome.RevisionConflict
    }

    @Query("SELECT * FROM quiz_attempts WHERE id = :attemptId LIMIT 1")
    suspend fun findAttempt(attemptId: String): QuizAttemptEntity?

    @Query("SELECT * FROM quiz_attempts WHERE id = :attemptId AND user_id = :userId LIMIT 1")
    suspend fun findAttemptForUser(userId: Long, attemptId: String): QuizAttemptEntity?

    @Query("SELECT * FROM quiz_attempts WHERE submit_key = :submitKey LIMIT 1")
    suspend fun findAttemptBySubmitKey(submitKey: String): QuizAttemptEntity?

    @Query(
        "SELECT * FROM quiz_attempts WHERE paper_id = :paperId AND user_id = :userId " +
            "ORDER BY started_at DESC, id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun listAttempts(paperId: String, userId: Long, limit: Int, offset: Int): List<QuizAttemptEntity>

    @Query("SELECT COUNT(*) FROM quiz_attempts WHERE paper_id = :paperId AND user_id = :userId")
    suspend fun countAttempts(paperId: String, userId: Long): Int

    /** 读取整卷作答 revision；调用方判分前固定该值，交卷时作为 CAS 期望值。 */
    @Query("SELECT revision FROM quiz_attempts WHERE id = :attemptId AND user_id = :userId LIMIT 1")
    suspend fun readRevision(attemptId: String, userId: Long): Long?

    @Query("SELECT COUNT(*) FROM quiz_items WHERE id = :itemId AND paper_id = :paperId")
    suspend fun itemBelongsToPaper(itemId: String, paperId: String): Int

    @Query("SELECT * FROM quiz_attempt_answers WHERE attempt_id = :attemptId AND item_id = :itemId LIMIT 1")
    suspend fun findAnswer(attemptId: String, itemId: String): QuizAttemptAnswerEntity?

    @Query(
        "SELECT a.* FROM quiz_attempt_answers a JOIN quiz_items i ON i.id = a.item_id " +
            "WHERE a.attempt_id = :attemptId ORDER BY i.ordinal ASC",
    )
    suspend fun findAnswers(attemptId: String): List<QuizAttemptAnswerEntity>

    @Query(
        "UPDATE quiz_attempt_answers SET raw_answer_json = :rawAnswerJson, edit_sequence = :editSequence, " +
            "updated_at = :now WHERE attempt_id = :attemptId AND item_id = :itemId AND edit_sequence < :editSequence",
    )
    suspend fun updateAnswerIfNewer(
        attemptId: String,
        itemId: String,
        rawAnswerJson: String?,
        editSequence: Long,
        now: Long,
    ): Int

    @Query("DELETE FROM quiz_attempt_answers WHERE attempt_id = :attemptId AND item_id = :itemId")
    suspend fun deleteAnswer(attemptId: String, itemId: String): Int

    @Query(
        "UPDATE quiz_attempt_answers SET raw_answer_json = :rawAnswerJson, edit_sequence = :editSequence, " +
            "updated_at = :updatedAt WHERE attempt_id = :attemptId AND item_id = :itemId",
    )
    suspend fun restoreAnswer(
        attemptId: String,
        itemId: String,
        rawAnswerJson: String?,
        editSequence: Long,
        updatedAt: Long,
    ): Int

    /** 作答接受后同事务推进整卷 revision（只对当前 user 的 IN_PROGRESS attempt）。 */
    @Query(
        "UPDATE quiz_attempts SET revision = revision + 1 " +
            "WHERE id = :attemptId AND user_id = :userId AND status = 'IN_PROGRESS'",
    )
    suspend fun incrementAttemptRevision(attemptId: String, userId: Long): Int

    @Query(
        "UPDATE quiz_attempts SET status = :status, grading_status = :gradingStatus, " +
            "total_score = :totalScore, submitted_at = :now, revision = revision + 1 " +
            "WHERE id = :attemptId AND user_id = :userId AND status = 'IN_PROGRESS' " +
            "AND revision = :expectedRevision AND submit_key = :submitKey",
    )
    suspend fun submitCas(
        attemptId: String,
        userId: Long,
        expectedRevision: Long,
        submitKey: String,
        status: String,
        gradingStatus: String,
        totalScore: Int?,
        now: Long,
    ): Int

    /** 修分：只推进 grade_revision（不混用 attempt.revision），同 key 重试由唯一索引兜底。 */
    @Query(
        "UPDATE quiz_attempts SET grade_revision = grade_revision + 1 " +
            "WHERE id = :attemptId AND user_id = :userId AND grade_revision = :expectedGradeRevision",
    )
    suspend fun bumpGradeRevision(attemptId: String, userId: Long, expectedGradeRevision: Long): Int

    /**
     * 写单题评分（判分/自评/AI 复核落库）：score 为 NULL 表示待评或部分，
     * is_correct 为 NULL 表示部分正确/未定；不修改 raw_answer_json 与 edit_sequence。
     */
    @Query(
        "UPDATE quiz_attempt_answers SET score = :score, is_correct = :isCorrect, " +
            "matched_rubric_json = :matchedRubricJson, grading_method = :gradingMethod, " +
            "feedback = :feedback, updated_at = :now WHERE attempt_id = :attemptId AND item_id = :itemId",
    )
    suspend fun updateAnswerGrade(
        attemptId: String,
        itemId: String,
        score: Int?,
        isCorrect: Boolean?,
        matchedRubricJson: String,
        gradingMethod: String,
        feedback: String,
        now: Long,
    ): Int

    /**
     * 修分后的原子重算：同一 UPDATE 里 grade_revision+1、写 total_score/grading_status，
     * 并把 status 从 SUBMITTED_PENDING_REVIEW 迁到 SUBMITTED_FINAL（待评判完即终态，
     * 契约 §16/§18：待评与终态是两回事）。expectedGradeRevision CAS 防旧复核覆盖新结果。
     */
    @Query(
        "UPDATE quiz_attempts SET grade_revision = grade_revision + 1, total_score = :totalScore, " +
            "grading_status = :gradingStatus, status = :status WHERE id = :attemptId AND user_id = :userId " +
            "AND grade_revision = :expectedGradeRevision",
    )
    suspend fun updateAttemptGrading(
        attemptId: String,
        userId: Long,
        expectedGradeRevision: Long,
        totalScore: Int?,
        gradingStatus: String,
        status: String,
    ): Int

    /**
     * 续答恢复：回写当前题号并按单调时钟持久累计有效时长（deltaMs<0 拒绝，防倒扣）。
     * 只对当前 user 的 IN_PROGRESS attempt 生效；交卷后不再改题号/时长。
     */
    @Query(
        "UPDATE quiz_attempts SET current_ordinal = :currentOrdinal, active_used_ms = active_used_ms + :deltaMs " +
            "WHERE id = :attemptId AND user_id = :userId AND status = 'IN_PROGRESS' AND :deltaMs >= 0",
    )
    suspend fun updateResumeState(
        attemptId: String,
        userId: Long,
        currentOrdinal: Int,
        deltaMs: Long,
    ): Int

    @Query(
        "SELECT * FROM quiz_grade_events WHERE attempt_id = :attemptId AND assessment_key = :assessmentKey LIMIT 1",
    )
    suspend fun findGradeEvent(attemptId: String, assessmentKey: String): QuizGradeEventEntity?

    @Query("SELECT * FROM quiz_grade_events WHERE attempt_id = :attemptId ORDER BY created_at ASC, id ASC")
    suspend fun findGradeEvents(attemptId: String): List<QuizGradeEventEntity>

    companion object {
        const val STATUS_IN_PROGRESS = "IN_PROGRESS"
        const val STATUS_SUBMITTED_FINAL = "SUBMITTED_FINAL"
        const val STATUS_SUBMITTED_PENDING_REVIEW = "SUBMITTED_PENDING_REVIEW"

        /** grading_status 字面量：进行中未判分 / 全部已评 / 存在待自评或待复核项。 */
        const val GRADING_NOT_GRADED = "NOT_GRADED"
        const val GRADING_FINAL = "FINAL"
        const val GRADING_PENDING_REVIEW = "PENDING_REVIEW"
    }
}
