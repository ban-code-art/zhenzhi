package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reader_documents",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["owner_user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["legacy_chapter_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["legacy_chapter_id"], unique = true, name = "idx_reader_document_legacy"),
        Index(value = ["owner_user_id", "file_sha256"], unique = true, name = "idx_reader_document_hash"),
        Index(value = ["owner_user_id", "deleted_at", "updated_at", "id"], name = "idx_reader_document_owner"),
    ],
)
data class ReaderDocumentEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "owner_user_id") val ownerUserId: Long?,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "legacy_chapter_id") val legacyChapterId: Long? = null,
    @ColumnInfo(name = "format") val format: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "author") val author: String? = null,
    @ColumnInfo(name = "blob_path") val blobPath: String? = null,
    @ColumnInfo(name = "file_sha256") val fileSha256: String? = null,
    @ColumnInfo(name = "content_revision") val contentRevision: String,
    @ColumnInfo(name = "extractor_version") val extractorVersion: String,
    @ColumnInfo(name = "metadata_revision", defaultValue = "1") val metadataRevision: Long = 1,
    @ColumnInfo(name = "access_epoch", defaultValue = "1") val accessEpoch: Long = 1,
    @ColumnInfo(name = "import_status", defaultValue = "'PENDING'") val importStatus: String = "PENDING",
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "reader_content_revisions",
    primaryKeys = ["document_id", "revision"],
    foreignKeys = [
        ForeignKey(
            entity = ReaderDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["document_id"], name = "index_reader_content_revisions_document_id")],
)
data class ReaderContentRevisionEntity(
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "revision") val revision: String,
    @ColumnInfo(name = "source_sha256") val sourceSha256: String? = null,
    @ColumnInfo(name = "source_blob_path") val sourceBlobPath: String? = null,
    @ColumnInfo(name = "manifest_path") val manifestPath: String,
    @ColumnInfo(name = "extractor_version") val extractorVersion: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "reader_sections",
    foreignKeys = [
        ForeignKey(
            entity = ReaderContentRevisionEntity::class,
            parentColumns = ["document_id", "revision"],
            childColumns = ["document_id", "content_revision"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReaderSectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["document_id", "content_revision", "resource_href"], unique = true, name = "idx_reader_section_resource"),
        Index(value = ["document_id", "content_revision", "order_index", "id"], name = "idx_reader_section_order"),
        Index(value = ["parent_id"], name = "idx_reader_section_parent"),
        Index(value = ["document_id", "content_revision"], name = "index_reader_sections_document_id_content_revision"),
    ],
)
data class ReaderSectionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "content_revision") val contentRevision: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "depth", defaultValue = "0") val depth: Int = 0,
    @ColumnInfo(name = "resource_href") val resourceHref: String,
    @ColumnInfo(name = "canonical_path") val canonicalPath: String,
    @ColumnInfo(name = "char_count", defaultValue = "0") val charCount: Int = 0,
    @ColumnInfo(name = "page_start") val pageStart: Int? = null,
    @ColumnInfo(name = "page_end") val pageEnd: Int? = null,
)

@Entity(
    tableName = "reader_outline_nodes",
    foreignKeys = [
        ForeignKey(
            entity = ReaderContentRevisionEntity::class,
            parentColumns = ["document_id", "revision"],
            childColumns = ["document_id", "content_revision"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReaderOutlineNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ReaderSectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["target_section_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["document_id", "content_revision", "node_key"], unique = true, name = "idx_reader_outline_key"),
        Index(value = ["document_id", "content_revision", "order_index", "id"], name = "idx_reader_outline_order"),
        Index(value = ["parent_id"], name = "idx_reader_outline_parent"),
        Index(value = ["target_section_id"], name = "idx_reader_outline_section"),
        Index(value = ["document_id", "content_revision"], name = "index_reader_outline_nodes_document_id_content_revision"),
    ],
)
data class ReaderOutlineNodeEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "content_revision") val contentRevision: String,
    @ColumnInfo(name = "node_key") val nodeKey: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "target_section_id") val targetSectionId: String? = null,
    @ColumnInfo(name = "target_locator_json") val targetLocatorJson: String? = null,
)

@Entity(
    tableName = "reader_states",
    primaryKeys = ["user_id", "document_id"],
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
        Index(value = ["user_id", "last_read_at", "document_id"], name = "idx_reader_state_recent"),
        Index(value = ["document_id"], name = "idx_reader_state_document"),
    ],
)
data class ReaderStateEntity(
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "locator_json") val locatorJson: String? = null,
    @ColumnInfo(name = "settings_json") val settingsJson: String? = null,
    @ColumnInfo(name = "is_completed", defaultValue = "0") val isCompleted: Boolean = false,
    @ColumnInfo(name = "is_hidden", defaultValue = "0") val isHidden: Boolean = false,
    @ColumnInfo(name = "display_title_override") val displayTitleOverride: String? = null,
    @ColumnInfo(name = "last_read_at", defaultValue = "0") val lastReadAt: Long = 0,
    @ColumnInfo(name = "writer_epoch", defaultValue = "0") val writerEpoch: Long = 0,
    @ColumnInfo(name = "last_sequence", defaultValue = "0") val lastSequence: Long = 0,
    @ColumnInfo(name = "revision", defaultValue = "0") val revision: Long = 0,
)