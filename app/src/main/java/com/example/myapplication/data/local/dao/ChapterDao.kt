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
}
