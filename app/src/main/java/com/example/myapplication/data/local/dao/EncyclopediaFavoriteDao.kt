package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.EncyclopediaFavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EncyclopediaFavoriteDao {
    @Query("SELECT point_code FROM encyclopedia_favorites WHERE user_id = :userId ORDER BY created_at DESC, id DESC")
    fun observePointCodes(userId: Long): Flow<List<String>>

    @Query("SELECT * FROM encyclopedia_favorites WHERE user_id = :userId ORDER BY created_at ASC, id ASC")
    suspend fun getByUser(userId: Long): List<EncyclopediaFavoriteEntity>

    @Query("SELECT COUNT(*) FROM encyclopedia_favorites WHERE user_id = :userId")
    suspend fun countByUser(userId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM encyclopedia_favorites WHERE user_id = :userId AND point_code = :pointCode)")
    suspend fun isFavorite(userId: Long, pointCode: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: EncyclopediaFavoriteEntity): Long

    @Query("DELETE FROM encyclopedia_favorites WHERE user_id = :userId AND point_code = :pointCode")
    suspend fun delete(userId: Long, pointCode: String): Int
}
