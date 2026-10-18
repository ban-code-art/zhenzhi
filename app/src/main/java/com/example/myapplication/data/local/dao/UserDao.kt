package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: UserEntity): Long

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun findByUsername(username: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<UserEntity?>

    @Query("SELECT COUNT(*) FROM users WHERE username = :username")
    suspend fun countByUsername(username: String): Int

    @Query("UPDATE users SET nickname = :nickname, updated_at = :updatedAt WHERE id = :userId")
    suspend fun updateProfile(userId: Long, nickname: String?, updatedAt: Long)

    @Query(
        "UPDATE users SET password_hash = :passwordHash, salt = :salt, updated_at = :updatedAt " +
            "WHERE id = :userId"
    )
    suspend fun updatePassword(userId: Long, passwordHash: String, salt: String, updatedAt: Long)

    @Query(
        "UPDATE users SET avatar_id = :avatarId, avatar_path = :avatarPath, " +
            "updated_at = :updatedAt WHERE id = :userId"
    )
    suspend fun updateAvatar(
        userId: Long,
        avatarId: Int,
        avatarPath: String?,
        updatedAt: Long
    )
}
