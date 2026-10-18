package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "questions",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapter_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["type"]),
        Index(value = ["chapter_id"]),
        Index(value = ["type", "enabled"])
    ]
)
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "chapter_id")
    val chapterId: Long? = null,
    @ColumnInfo(name = "stem")
    val stem: String,
    @ColumnInfo(name = "options")
    val options: String? = null,
    @ColumnInfo(name = "answer")
    val answer: String,
    @ColumnInfo(name = "synonyms")
    val synonyms: String? = null,
    @ColumnInfo(name = "knowledge_point")
    val knowledgePoint: String? = null,
    @ColumnInfo(name = "analysis")
    val analysis: String,
    @ColumnInfo(name = "score")
    val score: Int = 2,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
