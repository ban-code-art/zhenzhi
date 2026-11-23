package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * schema v6：随堂测（quiz_*）与学习活动（learning_activity_*）。
 *
 * 严格对齐《数据库迁移与查询契约》§4 v5→v6 DDL：字段名/默认值/FK/索引一致。
 * 试卷一旦发布即不可变；用户重练新建 attempt，答案按 editSequence 递进，
 * 交卷用 submitKey + attempt.revision CAS 防重复收题。
 */
@Entity(
    tableName = "quiz_papers",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReaderDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = AiRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["generation_run_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["user_id", "created_at", "id"], name = "idx_quiz_paper_recent"),
        Index(value = ["document_id"], name = "idx_quiz_paper_document"),
        Index(value = ["generation_run_id"], unique = true, name = "idx_quiz_paper_generation"),
    ],
)
data class QuizPaperEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "document_id") val documentId: String? = null,
    @ColumnInfo(name = "scope_json") val scopeJson: String,
    @ColumnInfo(name = "source_revision") val sourceRevision: String,
    @ColumnInfo(name = "template_version") val templateVersion: String,
    @ColumnInfo(name = "question_count") val questionCount: Int,
    @ColumnInfo(name = "max_score") val maxScore: Int,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "generation_run_id") val generationRunId: String? = null,
    @ColumnInfo(name = "generation_coverage_json") val generationCoverageJson: String,
    @ColumnInfo(name = "generated_by") val generatedBy: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "quiz_items",
    foreignKeys = [
        ForeignKey(
            entity = QuizPaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paper_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["id"],
            childColumns = ["question_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["paper_id", "ordinal"], unique = true, name = "idx_quiz_item_ordinal"),
        Index(value = ["question_id"], name = "idx_quiz_item_question"),
    ],
)
data class QuizItemEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "paper_id") val paperId: String,
    @ColumnInfo(name = "question_id") val questionId: Long,
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    @ColumnInfo(name = "question_snapshot_json") val questionSnapshotJson: String,
    @ColumnInfo(name = "rubric_json") val rubricJson: String,
    @ColumnInfo(name = "evidence_json") val evidenceJson: String,
)

@Entity(
    tableName = "quiz_attempts",
    foreignKeys = [
        ForeignKey(
            entity = QuizPaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paper_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["submit_key"], unique = true, name = "idx_quiz_attempt_submit"),
        Index(value = ["paper_id", "user_id", "started_at", "id"], name = "idx_quiz_attempt_paper"),
        Index(value = ["user_id", "status", "id"], name = "idx_quiz_attempt_user"),
    ],
)
data class QuizAttemptEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "paper_id") val paperId: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "current_ordinal", defaultValue = "0") val currentOrdinal: Int = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "active_used_ms", defaultValue = "0") val activeUsedMs: Long = 0,
    @ColumnInfo(name = "submitted_at") val submittedAt: Long? = null,
    @ColumnInfo(name = "total_score") val totalScore: Int? = null,
    @ColumnInfo(name = "grading_status") val gradingStatus: String,
    @ColumnInfo(name = "submit_key") val submitKey: String,
    @ColumnInfo(name = "revision", defaultValue = "0") val revision: Long = 0,
    @ColumnInfo(name = "grade_revision", defaultValue = "0") val gradeRevision: Long = 0,
)

@Entity(
    tableName = "quiz_attempt_answers",
    primaryKeys = ["attempt_id", "item_id"],
    foreignKeys = [
        ForeignKey(
            entity = QuizAttemptEntity::class,
            parentColumns = ["id"],
            childColumns = ["attempt_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = QuizItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["item_id"], name = "idx_quiz_answer_item")],
)
data class QuizAttemptAnswerEntity(
    @ColumnInfo(name = "attempt_id") val attemptId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "raw_answer_json") val rawAnswerJson: String? = null,
    @ColumnInfo(name = "edit_sequence", defaultValue = "0") val editSequence: Long = 0,
    @ColumnInfo(name = "score") val score: Int? = null,
    @ColumnInfo(name = "is_correct") val isCorrect: Boolean? = null,
    @ColumnInfo(name = "matched_rubric_json", defaultValue = "'{}'") val matchedRubricJson: String = "{}",
    @ColumnInfo(name = "grading_method", defaultValue = "'NONE'") val gradingMethod: String = "NONE",
    @ColumnInfo(name = "feedback", defaultValue = "''") val feedback: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "quiz_grade_events",
    foreignKeys = [
        ForeignKey(
            entity = QuizAttemptEntity::class,
            parentColumns = ["id"],
            childColumns = ["attempt_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = QuizItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["attempt_id", "assessment_key"],
            unique = true,
            name = "idx_quiz_grade_key",
        ),
        Index(value = ["item_id"], name = "idx_quiz_grade_item"),
    ],
)
data class QuizGradeEventEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "attempt_id") val attemptId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "assessment_key") val assessmentKey: String,
    @ColumnInfo(name = "grade_revision") val gradeRevision: Long,
    @ColumnInfo(name = "old_assessment_json") val oldAssessmentJson: String? = null,
    @ColumnInfo(name = "new_assessment_json") val newAssessmentJson: String,
    @ColumnInfo(name = "reason") val reason: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "learning_activity_events",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["user_id", "dedup_key"], unique = true, name = "idx_learning_event_dedup"),
        Index(value = ["user_id", "local_date"], name = "idx_learning_event_date"),
    ],
)
data class LearningActivityEventEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "dedup_key") val dedupKey: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "local_date") val localDate: String,
    @ColumnInfo(name = "timezone_id") val timezoneId: String,
    @ColumnInfo(name = "reading_delta_ms", defaultValue = "0") val readingDeltaMs: Long = 0,
    @ColumnInfo(name = "quiz_delta", defaultValue = "0") val quizDelta: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "learning_activity_days",
    primaryKeys = ["user_id", "local_date"],
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LearningActivityDayEntity(
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "local_date") val localDate: String,
    @ColumnInfo(name = "timezone_id") val timezoneId: String,
    @ColumnInfo(name = "reading_ms", defaultValue = "0") val readingMs: Long = 0,
    @ColumnInfo(name = "quiz_count", defaultValue = "0") val quizCount: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
