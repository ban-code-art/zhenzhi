package com.example.myapplication.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * schema v5：知识库索引、向量与 ANN manifest。
 *
 * knowledge_chunks_fts 是 FTS4 虚拟表，不参与 Room 实体映射；
 * 由 Migration4To5 / 新装回调创建，正文维护通过 KnowledgeChunkDao 的显式 SQL 完成。
 */
@Entity(
    tableName = "knowledge_documents",
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
        Index(value = ["user_id", "document_id"], unique = true, name = "idx_knowledge_document_owner"),
        Index(value = ["document_id"], name = "idx_knowledge_document_source"),
        Index(value = ["user_id", "lexical_state", "id"], name = "idx_knowledge_document_state"),
    ],
)
data class KnowledgeDocumentEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "source_revision") val sourceRevision: String,
    @ColumnInfo(name = "requested_generation", defaultValue = "1") val requestedGeneration: Long = 1,
    @ColumnInfo(name = "active_generation") val activeGeneration: Long? = null,
    @ColumnInfo(name = "lexical_state", defaultValue = "'PENDING'") val lexicalState: String = "PENDING",
    @ColumnInfo(name = "vector_state", defaultValue = "'PENDING'") val vectorState: String = "PENDING",
    @ColumnInfo(name = "access_epoch", defaultValue = "1") val accessEpoch: Long = 1,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
)

@Entity(
    tableName = "knowledge_chunks",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledge_document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ReaderSectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["section_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["knowledge_document_id", "generation", "ordinal"], unique = true, name = "idx_knowledge_chunk_ordinal"),
        Index(value = ["knowledge_document_id", "generation", "id"], name = "idx_knowledge_chunk_scan"),
        Index(value = ["section_id"], name = "idx_knowledge_chunk_section"),
    ],
)
data class KnowledgeChunkEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "knowledge_document_id") val knowledgeDocumentId: String,
    @ColumnInfo(name = "generation") val generation: Long,
    @ColumnInfo(name = "section_id") val sectionId: String,
    @ColumnInfo(name = "block_ids_json") val blockIdsJson: String,
    @ColumnInfo(name = "parent_key") val parentKey: String,
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    @ColumnInfo(name = "raw_text") val rawText: String,
    @ColumnInfo(name = "search_text") val searchText: String,
    @ColumnInfo(name = "embedding_text") val embeddingText: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "locator_json") val locatorJson: String,
    @ColumnInfo(name = "token_count") val tokenCount: Int,
    @ColumnInfo(name = "extractor_version") val extractorVersion: String,
    @ColumnInfo(name = "chunker_version") val chunkerVersion: String,
)

@Entity(
    tableName = "knowledge_embeddings",
    primaryKeys = ["chunk_id", "profile_fingerprint"],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profile_fingerprint", "chunk_id"], name = "idx_knowledge_embedding_profile")],
)
data class KnowledgeEmbeddingEntity(
    @ColumnInfo(name = "chunk_id") val chunkId: Long,
    @ColumnInfo(name = "profile_fingerprint") val profileFingerprint: String,
    @ColumnInfo(name = "dim") val dim: Int,
    @ColumnInfo(name = "encoding", defaultValue = "'FLOAT32'") val encoding: String = "FLOAT32",
    @ColumnInfo(name = "vector_blob") val vectorBlob: ByteArray,
    @ColumnInfo(name = "norm") val norm: Double,
    @ColumnInfo(name = "quantization_params") val quantizationParams: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KnowledgeEmbeddingEntity) return false
        return chunkId == other.chunkId &&
            profileFingerprint == other.profileFingerprint &&
            dim == other.dim &&
            encoding == other.encoding &&
            vectorBlob.contentEquals(other.vectorBlob) &&
            norm == other.norm &&
            quantizationParams == other.quantizationParams &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = chunkId.hashCode()
        result = 31 * result + profileFingerprint.hashCode()
        result = 31 * result + dim
        result = 31 * result + encoding.hashCode()
        result = 31 * result + vectorBlob.contentHashCode()
        result = 31 * result + norm.hashCode()
        result = 31 * result + (quantizationParams?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

@Entity(
    tableName = "knowledge_vector_states",
    primaryKeys = ["knowledge_document_id", "generation", "profile_fingerprint"],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledge_document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class KnowledgeVectorStateEntity(
    @ColumnInfo(name = "knowledge_document_id") val knowledgeDocumentId: String,
    @ColumnInfo(name = "generation") val generation: Long,
    @ColumnInfo(name = "profile_fingerprint") val profileFingerprint: String,
    @ColumnInfo(name = "expected_count", defaultValue = "0") val expectedCount: Int = 0,
    @ColumnInfo(name = "ready_count", defaultValue = "0") val readyCount: Int = 0,
    @ColumnInfo(name = "state", defaultValue = "'PENDING'") val state: String = "PENDING",
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "knowledge_index_jobs",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = KnowledgeDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledge_document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["knowledge_document_id", "requested_generation", "phase", "profile_fingerprint"],
            unique = true,
            name = "idx_knowledge_job_identity",
        ),
        Index(value = ["user_id", "status", "id"], name = "idx_knowledge_job_owner"),
        Index(value = ["status", "next_retry_at", "id"], name = "idx_knowledge_job_due"),
    ],
)
data class KnowledgeIndexJobEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "knowledge_document_id") val knowledgeDocumentId: String,
    @ColumnInfo(name = "phase") val phase: String,
    @ColumnInfo(name = "target_revision") val targetRevision: String,
    @ColumnInfo(name = "requested_generation") val requestedGeneration: Long,
    @ColumnInfo(name = "profile_fingerprint", defaultValue = "''") val profileFingerprint: String = "",
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "lease_token") val leaseToken: String? = null,
    @ColumnInfo(name = "lease_until") val leaseUntil: Long? = null,
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "next_retry_at") val nextRetryAt: Long? = null,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String? = null,
    @ColumnInfo(name = "progress_json", defaultValue = "'{}'") val progressJson: String = "{}",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "knowledge_ann_manifests",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["user_id", "profile_fingerprint", "status"], name = "idx_ann_manifest_profile")],
)
data class KnowledgeAnnManifestEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "profile_fingerprint") val profileFingerprint: String,
    @ColumnInfo(name = "dim") val dim: Int,
    @ColumnInfo(name = "source_generation_hash") val sourceGenerationHash: String,
    @ColumnInfo(name = "blob_path") val blobPath: String,
    @ColumnInfo(name = "checksum") val checksum: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * FTS4 全文索引表，rowid 与 knowledge_chunks.id 对齐。
 *
 * 说明：设计 DDL 使用 unicode61 分词器；中文双字切分与术语扩展在写入
 * title_terms/heading_terms/body_terms 前完成（见 ChineseTokenizer）。
 * user_scope 仅用于优化过滤，权限判断始终回表 JOIN。
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "knowledge_chunks_fts")
data class KnowledgeChunkFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    @ColumnInfo(name = "title_terms") val titleTerms: String,
    @ColumnInfo(name = "heading_terms") val headingTerms: String,
    @ColumnInfo(name = "body_terms") val bodyTerms: String,
    @ColumnInfo(name = "user_scope") val userScope: String,
)
