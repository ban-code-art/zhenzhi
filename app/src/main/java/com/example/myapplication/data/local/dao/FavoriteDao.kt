package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("INSERT OR IGNORE INTO favorites(user_id,question_id,source,created_at) SELECT :userId,id,'MANUAL',:createdAt FROM questions WHERE id=:questionId AND (owner_user_id IS NULL OR owner_user_id=:userId)")
    suspend fun insertManualOwned(userId: Long, questionId: Long, createdAt: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteEntity): Long

    @Query("SELECT * FROM favorites WHERE user_id = :userId ORDER BY created_at DESC")
    fun observeByUser(userId: Long): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE user_id = :userId AND source = :source ORDER BY created_at DESC")
    suspend fun findBySource(userId: Long, source: String): List<FavoriteEntity>

    /**
     * 只读：某用户某来源（WRONG/MANUAL）的题目 id 集合。
     *
     * 结果页用它判断“这道题是否已在错题本（favorites.source=WRONG）”，只读不写；
     * 移除仍走既有 [delete]（按 user_id + question_id + source 精确删除）。
     */
    @Query("SELECT question_id FROM favorites WHERE user_id = :userId AND source = :source")
    suspend fun findQuestionIdsBySource(userId: Long, source: String): List<Long>

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "DELETE FROM favorites WHERE user_id = :userId AND question_id = :questionId AND source = :source"
    )
    suspend fun delete(userId: Long, questionId: Long, source: String)

    @Query("SELECT COUNT(DISTINCT question_id) FROM favorites WHERE user_id = :userId")
    suspend fun countDistinctQuestions(userId: Long): Int

    @Query(
        "SELECT COUNT(DISTINCT question_id) FROM favorites " +
            "WHERE user_id = :userId AND source = :source"
    )
    suspend fun countDistinctQuestionsBySource(userId: Long, source: String): Int
}
