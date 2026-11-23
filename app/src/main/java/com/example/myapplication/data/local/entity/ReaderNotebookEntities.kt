package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reader_notebooks",
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
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["user_id", "document_id"], unique = true, name = "idx_reader_notebook_owner"),
        Index(value = ["document_id"], name = "idx_reader_notebook_document"),
    ],
)
data class ReaderNotebookEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "body_markdown", defaultValue = "''") val bodyMarkdown: String = "",
    @ColumnInfo(name = "revision", defaultValue = "0") val revision: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "reader_note_versions",
    foreignKeys = [
        ForeignKey(
            entity = ReaderNotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebook_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["notebook_id", "revision"], unique = true, name = "idx_reader_note_version"),
        Index(value = ["notebook_id", "operation_id"], unique = true, name = "idx_reader_note_operation"),
    ],
)
data class ReaderNoteVersionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "notebook_id") val notebookId: String,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "body_markdown") val bodyMarkdown: String,
    @ColumnInfo(name = "actor") val actor: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "reader_mutation_receipts",
    primaryKeys = ["user_id", "operation_id"],
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["user_id", "target_kind", "target_id"], name = "idx_reader_mutation_target")],
)
data class ReaderMutationReceiptEntity(
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "target_kind") val targetKind: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "request_hash") val requestHash: String,
    @ColumnInfo(name = "before_revision") val beforeRevision: Long,
    @ColumnInfo(name = "after_revision") val afterRevision: Long,
    @ColumnInfo(name = "result_json") val resultJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "reader_drafts",
    primaryKeys = ["user_id", "notebook_id", "editor_id"],
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReaderNotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebook_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["notebook_id"], name = "idx_reader_draft_notebook")],
)
data class ReaderDraftEntity(
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "notebook_id") val notebookId: String,
    @ColumnInfo(name = "editor_id") val editorId: String,
    @ColumnInfo(name = "editor_sequence", defaultValue = "0") val editorSequence: Long = 0,
    @ColumnInfo(name = "base_revision") val baseRevision: Long,
    @ColumnInfo(name = "body_markdown") val bodyMarkdown: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)