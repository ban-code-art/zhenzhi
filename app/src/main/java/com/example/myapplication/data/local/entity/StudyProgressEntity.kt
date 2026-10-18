package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "study_progress",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapter_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["user_id", "chapter_id"], unique = true),
        Index(value = ["chapter_id"])
    ]
)
data class StudyProgressEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "user_id")
    val userId: Long,
    @ColumnInfo(name = "chapter_id")
    val chapterId: Long,
    @ColumnInfo(name = "completed")
    val completed: Boolean = false,
    @ColumnInfo(name = "quiz_score")
    val quizScore: Int? = null,
    @ColumnInfo(name = "quiz_total")
    val quizTotal: Int? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
