package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE enabled = 1 ORDER BY order_index ASC")
    fun observeEnabled(): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters ORDER BY order_index ASC")
    suspend fun getAll(): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE content_key = :contentKey LIMIT 1")
    suspend fun findByContentKey(contentKey: String): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(chapter: ChapterEntity): Long

    @Query("UPDATE chapters SET order_index = :orderIndex, title = :title, summary = :summary, enabled = :enabled WHERE id = :id")
    suspend fun updateMetadata(id: Long, orderIndex: Int, title: String, summary: String?, enabled: Boolean): Int
}