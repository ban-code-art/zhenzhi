package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.myapplication.data.local.entity.QuizItemEntity
import com.example.myapplication.data.local.entity.QuizPaperEntity

/**
 * 随堂测试卷与固定题目（schema v6 / 第四期 §7.2）。
 *
 * 试卷发布是不可变快照：paper 与 items 同事务写入，item 的 `ordinal` 在卷内唯一；
 * 题干/选项/rubric/证据全部存 `question_snapshot_json` / `rubric_json` / `evidence_json`，
 * 历史题目停用后不回写旧卷。按用户分页用 `created_at + id` 稳定排序。
 */
@Dao
interface QuizPaperDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPaper(paper: QuizPaperEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<QuizItemEntity>)

    /** 发布事务：paper 与固定 items 要么一起可见，要么都不写（不允许半卷 READY）。 */
    @Transaction
    suspend fun insertPaperWithItems(paper: QuizPaperEntity, items: List<QuizItemEntity>) {
        insertPaper(paper)
        insertItems(items)
    }

    @Query("SELECT * FROM quiz_papers WHERE id = :paperId LIMIT 1")
    suspend fun findPaper(paperId: String): QuizPaperEntity?

    @Query("SELECT * FROM quiz_papers WHERE id = :paperId AND user_id = :userId LIMIT 1")
    suspend fun findPaperForUser(userId: Long, paperId: String): QuizPaperEntity?

    @Query("SELECT * FROM quiz_papers WHERE user_id = :userId ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun listPapers(userId: Long, limit: Int, offset: Int): List<QuizPaperEntity>

    @Query(
        "SELECT * FROM quiz_papers WHERE user_id = :userId AND document_id = :documentId " +
            "ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun listPapersForDocument(
        userId: Long,
        documentId: String,
        limit: Int,
        offset: Int,
    ): List<QuizPaperEntity>

    @Query("SELECT * FROM quiz_items WHERE paper_id = :paperId ORDER BY ordinal ASC")
    suspend fun findItems(paperId: String): List<QuizItemEntity>

    @Query("SELECT * FROM quiz_items WHERE id = :itemId LIMIT 1")
    suspend fun findItem(itemId: String): QuizItemEntity?

    @Query("SELECT COUNT(*) FROM quiz_items WHERE paper_id = :paperId")
    suspend fun countItems(paperId: String): Int
}
