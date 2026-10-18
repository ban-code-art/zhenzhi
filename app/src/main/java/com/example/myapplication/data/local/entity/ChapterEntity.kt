package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chapters",
    indices = [Index(value = ["order_index"], unique = true)]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "summary")
    val summary: String? = null,
    @ColumnInfo(name = "content_key")
    val contentKey: String,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
