package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["id"],
            childColumns = ["question_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["user_id", "question_id", "source"], unique = true),
        Index(value = ["user_id", "source"]),
        Index(value = ["question_id"])
    ]
)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "user_id")
    val userId: Long,
    @ColumnInfo(name = "question_id")
    val questionId: Long,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
