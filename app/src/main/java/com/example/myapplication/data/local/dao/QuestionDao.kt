package com.example.myapplication.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.ContentQuestionBindingEntity
import com.example.myapplication.data.local.entity.QuestionEntity
import com.example.myapplication.data.local.entity.TestAnswerEntity
import com.example.myapplication.data.local.entity.TestRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {

    data class BoundQuestionRow(
        @ColumnInfo(name = "question_key") val questionKey: String,
        @ColumnInfo(name = "question_id") val questionId: Long,
    )

    data class SubmittedAnswerLocation(
        @ColumnInfo(name = "test_id") val testId: Long,
        @ColumnInfo(name = "order_index") val orderIndex: Int,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(questions: List<QuestionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertQuestion(question: QuestionEntity): Long

    @Query(
        "UPDATE questions SET type = :type, chapter_id = :chapterId, stem = :stem, options = :options, " +
            "answer = :answer, synonyms = :synonyms, knowledge_point = :knowledgePoint, analysis = :analysis, " +
            "score = :score, enabled = :enabled, owner_user_id = NULL, origin = :origin, " +
            "source_document_id = :sourceDocumentId, source_locator_json = :sourceLocatorJson, " +
            "source_excerpt = :sourceExcerpt, source_revision = :sourceRevision WHERE id = :id",
    )
    suspend fun updateQuestionInPlace(
        id: Long,
        type: String,
        chapterId: Long?,
        stem: String,
        options: String?,
        answer: String,
        synonyms: String?,
        knowledgePoint: String?,
        analysis: String,
        score: Int,
        enabled: Boolean,
        origin: String,
        sourceDocumentId: String?,
        sourceLocatorJson: String?,
        sourceExcerpt: String?,
        sourceRevision: String?,
    ): Int

    @Query("SELECT COUNT(*) FROM questions WHERE type = :type AND enabled = 1")
    suspend fun countByType(type: String): Int

    @Query("SELECT * FROM questions WHERE type = :type AND enabled = 1 ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomByType(type: String, limit: Int): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): QuestionEntity?

    @Query("SELECT * FROM questions WHERE chapter_id = :chapterId AND enabled = 1 ORDER BY id ASC")
    suspend fun findByChapter(chapterId: Long): List<QuestionEntity>

    @Query("SELECT question_key, question_id FROM content_question_bindings WHERE package_id = :packageId")
    suspend fun findBindingsByPackage(packageId: String): List<BoundQuestionRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBinding(binding: ContentQuestionBindingEntity)

    @Query("UPDATE content_question_bindings SET question_id = :questionId WHERE package_id = :packageId AND question_key = :questionKey")
    suspend fun updateBinding(packageId: String, questionKey: String, questionId: Long): Int

    @Query(
        "SELECT COUNT(*) FROM questions q " +
            "INNER JOIN content_question_bindings b ON b.question_id = q.id " +
            "WHERE b.package_id = :packageId AND q.enabled = 1 AND q.owner_user_id IS NULL",
    )
    suspend fun countEnabledBoundQuestions(packageId: String): Int

    @Query(
        "SELECT q.* FROM questions q " +
            "INNER JOIN content_question_bindings b ON b.question_id = q.id " +
            "WHERE b.package_id = :packageId AND q.enabled = 1 AND q.owner_user_id IS NULL " +
            "ORDER BY q.id ASC",
    )
    suspend fun listBoundQuestions(packageId: String): List<QuestionEntity>

    @Query(
        "SELECT * FROM questions " +
            "WHERE enabled = 1 AND owner_user_id IS NULL AND origin IN ('BUILTIN', 'CONTENT_PACKAGE') " +
            "ORDER BY id ASC",
    )
    suspend fun listBuiltInQuestions(): List<QuestionEntity>

    @Query("SELECT COUNT(*) FROM test_records WHERE user_id = :userId AND status = 'SUBMITTED'")
    suspend fun countSubmittedTests(userId: Long): Int

    @Query("SELECT COUNT(*) FROM test_records WHERE user_id = :userId AND status = 'SUBMITTED'")
    fun observeSubmittedTestCount(userId: Long): Flow<Int>

    @Query(
        "SELECT * FROM test_records WHERE user_id = :userId AND status = 'IN_PROGRESS' " +
            "ORDER BY started_at DESC, id DESC LIMIT 1",
    )
    suspend fun findInProgressRecord(userId: Long): TestRecordEntity?

    @Query("SELECT * FROM test_answers WHERE test_id = :testId ORDER BY order_index ASC")
    suspend fun listAnswersByTest(testId: Long): List<TestAnswerEntity>

    @Query(
        "SELECT ta.test_id, ta.order_index FROM test_answers ta " +
            "INNER JOIN test_records tr ON tr.id = ta.test_id " +
            "WHERE tr.user_id = :userId AND tr.status = 'SUBMITTED' AND ta.question_id = :questionId " +
            "ORDER BY tr.submitted_at DESC, tr.id DESC LIMIT 1",
    )
    suspend fun findLatestSubmittedAnswerLocation(userId: Long, questionId: Long): SubmittedAnswerLocation?
}
