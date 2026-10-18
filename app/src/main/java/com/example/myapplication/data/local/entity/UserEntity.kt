package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "users",
    indices = [Index(value = ["username"], unique = true)]
)
data class UserEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "username")
    val username: String,
    @ColumnInfo(name = "password_hash")
    val passwordHash: String,
    @ColumnInfo(name = "salt")
    val salt: String,
    @ColumnInfo(name = "nickname")
    val nickname: String? = null,
    @ColumnInfo(name = "avatar_id", defaultValue = "0")
    val avatarId: Int = 0,
    @ColumnInfo(name = "avatar_path")
    val avatarPath: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
