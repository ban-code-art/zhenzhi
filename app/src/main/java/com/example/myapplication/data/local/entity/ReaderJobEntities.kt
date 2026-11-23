package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reader_jobs",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReaderDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["user_id", "request_key"], unique = true, name = "idx_reader_job_request"),
        Index(value = ["status", "next_retry_at", "id"], name = "idx_reader_job_due"),
        Index(value = ["document_id"], name = "idx_reader_job_document"),
    ],
)
data class ReaderJobEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "document_id") val documentId: String? = null,
    @ColumnInfo(name = "request_key") val requestKey: String,
    @ColumnInfo(name = "stage") val stage: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "staged_path") val stagedPath: String? = null,
    @ColumnInfo(name = "target_revision") val targetRevision: String? = null,
    @ColumnInfo(name = "lease_token") val leaseToken: String? = null,
    @ColumnInfo(name = "lease_until") val leaseUntil: Long? = null,
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "next_retry_at") val nextRetryAt: Long? = null,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    @ColumnInfo(name = "progress_json", defaultValue = "'{}'") val progressJson: String = "{}",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "content_packages")
data class ContentPackageEntity(
    @PrimaryKey @ColumnInfo(name = "package_id") val packageId: String,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "manifest_hash") val manifestHash: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "applied_at") val appliedAt: Long? = null,
)

@Entity(
    tableName = "content_question_bindings",
    primaryKeys = ["package_id", "question_key"],
    foreignKeys = [
        ForeignKey(
            entity = ContentPackageEntity::class,
            parentColumns = ["package_id"],
            childColumns = ["package_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["id"],
            childColumns = ["question_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["question_id"], name = "idx_content_question_id")],
)
data class ContentQuestionBindingEntity(
    @ColumnInfo(name = "package_id") val packageId: String,
    @ColumnInfo(name = "question_key") val questionKey: String,
    @ColumnInfo(name = "question_id") val questionId: Long,
)