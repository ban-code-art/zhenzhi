package com.example.myapplication.data.review

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.myapplication.data.local.entity.UserEntity

@Entity(
    tableName = "review_items",
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["userId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["userId", "sourceNamespace", "sourceKey"], unique = true),
        Index(value = ["userId", "portableItemId"], unique = true),
        Index(value = ["userId", "creationOperationId"], unique = true),
        Index(value = ["userId", "sourceNamespace", "excerptFingerprint"]),
        Index(value = ["userId", "availability", "paused", "dueAtUtc"]),
        Index(value = ["userId", "bookKey", "availability", "paused", "dueAtUtc"]),
    ],
)
data class ReviewItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val portableItemId: String,
    val userId: Long,
    val sourceNamespace: String,
    val sourceKey: String,
    val sourceRefJson: String,
    val sourceTags: String,
    val sourceTitle: String,
    val bookKey: String?,
    val priority: Int,
    val prompt: String,
    val answerSnapshot: String,
    val snapshotHash: String,
    val sourceRevision: String,
    val itemRevision: Long,
    val creationOperationId: String?,
    val excerptFingerprint: String?,
    val availability: String,
    val paused: Boolean,
    val stage: Int,
    val dueAtUtc: Long,
    val lastReviewedAtUtc: Long?,
    val policyVersion: String,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
)

@Entity(
    tableName = "review_sessions",
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["userId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["userId", "portableSessionId"], unique = true),
        Index(value = ["userId", "startOperationId"], unique = true),
        Index(value = ["activeUserId"], unique = true),
    ],
)
data class ReviewSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val portableSessionId: String,
    val userId: Long,
    val activeUserId: Long?,
    val startOperationId: String?,
    val bookKey: String?,
    val status: String,
    val startedAtUtc: Long,
    val finishedAtUtc: Long?,
    val completedCount: Int = 0,
    val skippedCount: Int = 0,
    val unavailableCount: Int = 0,
)

@Entity(
    tableName = "review_session_items",
    primaryKeys = ["sessionId", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = ReviewSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ReviewItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["sessionId", "cardIdentity"], unique = true),
        Index(value = ["itemId"]),
    ],
)
data class ReviewSessionItemEntity(
    val sessionId: Long,
    val ordinal: Int,
    val itemId: Long?,
    val cardIdentity: String,
    val snapshotRevision: Long,
    val sourceRevision: String?,
    val sourceRefJson: String?,
    val sourceTitle: String?,
    val prompt: String?,
    val answerSnapshot: String?,
    val status: String,
    val draftAnswer: String? = null,
    val revealed: Boolean = false,
    val submittedEventId: Long? = null,
)

@Entity(
    tableName = "review_events",
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["userId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ReviewSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = ReviewItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["userId", "operationId"], unique = true),
        Index(value = ["userId", "portableEventId"], unique = true),
        Index(value = ["userId", "localDate", "cardIdentity"]),
        Index(value = ["sessionId"]),
        Index(value = ["itemId"]),
    ],
)
data class ReviewEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val portableEventId: String,
    val userId: Long,
    val sessionId: Long?,
    val itemId: Long?,
    val portableSessionId: String,
    val cardIdentity: String,
    val ordinal: Int?,
    val operationId: String,
    val rating: String,
    val beforeStage: Int,
    val afterStage: Int,
    val beforeDueAtUtc: Long,
    val afterDueAtUtc: Long,
    val snapshotRevision: Long,
    val occurredAtUtc: Long,
    val localDate: String,
    val timezoneId: String,
    val activeMs: Long,
)
