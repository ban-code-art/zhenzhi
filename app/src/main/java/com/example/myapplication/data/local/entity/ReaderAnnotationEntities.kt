package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reader_annotations",
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
        ForeignKey(
            entity = ReaderSectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["section_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["user_id", "document_id", "deleted_at", "kind"], name = "idx_reader_annotation_scope"),
        Index(value = ["document_id"], name = "idx_reader_annotation_document"),
        Index(value = ["section_id"], name = "idx_reader_annotation_section"),
    ],
)
data class ReaderAnnotationEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "section_id") val sectionId: String? = null,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "locator_json") val locatorJson: String,
    @ColumnInfo(name = "quote_snapshot") val quoteSnapshot: String? = null,
    @ColumnInfo(name = "note_text") val noteText: String? = null,
    @ColumnInfo(name = "style_json", defaultValue = "'{}'") val styleJson: String = "{}",
    @ColumnInfo(name = "ink_blob_path") val inkBlobPath: String? = null,
    @ColumnInfo(name = "revision", defaultValue = "1") val revision: Long = 1,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "reader_bookmarks",
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
        Index(value = ["user_id", "document_id", "anchor_hash"], unique = true, name = "idx_reader_bookmark_anchor"),
        Index(value = ["document_id"], name = "idx_reader_bookmark_document"),
    ],
)
data class ReaderBookmarkEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "locator_json") val locatorJson: String,
    @ColumnInfo(name = "anchor_hash") val anchorHash: String,
    @ColumnInfo(name = "preview", defaultValue = "''") val preview: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "reader_source_references",
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
            childColumns = ["source_document_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ReaderSectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["section_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["user_id", "reference_key"], unique = true, name = "idx_reader_source_reference_key"),
        Index(value = ["source_document_id"], name = "idx_reader_source_reference_document"),
        Index(value = ["section_id"], name = "idx_reader_source_reference_section"),
    ],
)
data class ReaderSourceReferenceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "reference_key") val referenceKey: String,
    @ColumnInfo(name = "source_document_id") val sourceDocumentId: String? = null,
    @ColumnInfo(name = "section_id") val sectionId: String? = null,
    @ColumnInfo(name = "source_revision") val sourceRevision: String,
    @ColumnInfo(name = "locator_json") val locatorJson: String,
    @ColumnInfo(name = "quote_snapshot") val quoteSnapshot: String,
    @ColumnInfo(name = "quote_sha256") val quoteSha256: String,
    @ColumnInfo(name = "bibliographic_json", defaultValue = "'{}'") val bibliographicJson: String = "{}",
    @ColumnInfo(name = "evidence_kind") val evidenceKind: String,
    @ColumnInfo(name = "extraction_quality") val extractionQuality: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)