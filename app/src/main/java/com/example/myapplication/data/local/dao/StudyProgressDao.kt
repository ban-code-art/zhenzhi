package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.StudyProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: StudyProgressEntity): Long

    /**
     * 内置进度局部更新用：不存在时插入，存在时保持原 id/completed，
     * 避免 REPLACE 把用户已完成状态清掉（契约 §5.5）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(progress: StudyProgressEntity): Long

    /** 只更新 quiz_score/quiz_total/updated_at；completed 与原 id 不动。 */
    @Query(
        "UPDATE study_progress SET quiz_score = :score, quiz_total = :total, updated_at = :now " +
            "WHERE user_id = :userId AND chapter_id = :chapterId",
    )
    suspend fun updateQuizFields(
        userId: Long,
        chapterId: Long,
        score: Int?,
        total: Int?,
        now: Long,
    ): Int

    @Query("SELECT * FROM study_progress WHERE user_id = :userId")
    fun observeByUser(userId: Long): Flow<List<StudyProgressEntity>>

    @Query("SELECT * FROM study_progress WHERE user_id = :userId AND chapter_id = :chapterId LIMIT 1")
    suspend fun find(userId: Long, chapterId: Long): StudyProgressEntity?

    @Query("SELECT COUNT(*) FROM study_progress WHERE user_id = :userId AND completed = 1")
    suspend fun countCompleted(userId: Long): Int
}
