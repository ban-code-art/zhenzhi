package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.QuestionEntity

@Dao
interface QuestionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(questions: List<QuestionEntity>)

    @Query("SELECT COUNT(*) FROM questions WHERE type = :type AND enabled = 1")
    suspend fun countByType(type: String): Int

    @Query("SELECT * FROM questions WHERE type = :type AND enabled = 1 ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomByType(type: String, limit: Int): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): QuestionEntity?

    @Query("SELECT * FROM questions WHERE chapter_id = :chapterId AND enabled = 1 ORDER BY id ASC")
    suspend fun findByChapter(chapterId: Long): List<QuestionEntity>
}
