package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "test_answers",
    foreignKeys = [
        ForeignKey(
            entity = TestRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["test_id"],
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
        Index(value = ["test_id", "question_id"], unique = true),
        Index(value = ["test_id"]),
        Index(value = ["question_id"])
    ]
)
data class TestAnswerEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "test_id")
    val testId: Long,
    @ColumnInfo(name = "question_id")
    val questionId: Long,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    @ColumnInfo(name = "user_answer")
    val userAnswer: String? = null,
    @ColumnInfo(name = "score")
    val score: Int = 0,
    @ColumnInfo(name = "is_correct")
    val isCorrect: Int? = null
)
