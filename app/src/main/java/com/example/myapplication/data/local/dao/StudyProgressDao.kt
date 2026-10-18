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

    @Query("SELECT * FROM study_progress WHERE user_id = :userId")
    fun observeByUser(userId: Long): Flow<List<StudyProgressEntity>>

    @Query("SELECT * FROM study_progress WHERE user_id = :userId AND chapter_id = :chapterId LIMIT 1")
    suspend fun find(userId: Long, chapterId: Long): StudyProgressEntity?

    @Query("SELECT COUNT(*) FROM study_progress WHERE user_id = :userId AND completed = 1")
    suspend fun countCompleted(userId: Long): Int
}
