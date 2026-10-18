package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteEntity): Long

    @Query("SELECT * FROM favorites WHERE user_id = :userId ORDER BY created_at DESC")
    fun observeByUser(userId: Long): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE user_id = :userId AND source = :source ORDER BY created_at DESC")
    suspend fun findBySource(userId: Long, source: String): List<FavoriteEntity>

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
