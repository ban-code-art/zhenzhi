package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "test_records",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["user_id", "started_at"])]
)
data class TestRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "user_id")
    val userId: Long,
    @ColumnInfo(name = "paper_type")
    val paperType: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "submitted_at")
    val submittedAt: Long? = null,
    @ColumnInfo(name = "duration_limit_seconds")
    val durationLimitSeconds: Int = 2400,
    @ColumnInfo(name = "remaining_seconds")
    val remainingSeconds: Int? = null,
    @ColumnInfo(name = "used_seconds")
    val usedSeconds: Int? = null,
    @ColumnInfo(name = "total_score")
    val totalScore: Int? = null,
    @ColumnInfo(name = "choice_score")
    val choiceScore: Int? = null,
    @ColumnInfo(name = "judge_score")
    val judgeScore: Int? = null,
    @ColumnInfo(name = "fill_score")
    val fillScore: Int? = null,
    @ColumnInfo(name = "essay_score")
    val essayScore: Int? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
