package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.myapplication.data.local.entity.KnowledgeAnnManifestEntity
import com.example.myapplication.data.local.entity.KnowledgeChunkEntity
import com.example.myapplication.data.local.entity.KnowledgeDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDocumentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(document: KnowledgeDocumentEntity)

    @Query("SELECT * FROM knowledge_documents WHERE id = :id LIMIT 1")
    suspend fun find(id: String): KnowledgeDocumentEntity?

    @Query("SELECT * FROM knowledge_documents WHERE user_id = :userId AND document_id = :documentId LIMIT 1")
    suspend fun findByDocument(userId: Long, documentId: String): KnowledgeDocumentEntity?

    @Query("SELECT * FROM knowledge_documents WHERE user_id = :userId ORDER BY id ASC")
    fun observeDocuments(userId: Long): Flow<List<KnowledgeDocumentEntity>>

    @Query("SELECT * FROM knowledge_documents WHERE user_id = :userId ORDER BY id ASC")
    suspend fun findDocuments(userId: Long): List<KnowledgeDocumentEntity>

    @Query("SELECT COUNT(*) FROM knowledge_documents WHERE user_id = :userId")
    suspend fun countDocuments(userId: Long): Int

    /** 切换 active generation 前必须核对源 revision 与 access epoch 仍匹配。 */
    @Query(
        "UPDATE knowledge_documents SET active_generation = :generation, source_revision = :sourceRevision, " +
            "lexical_state = :lexicalState, vector_state = :vectorState, access_epoch = :accessEpoch, " +
            "error_code = NULL " +
            "WHERE id = :id AND access_epoch = :expectedAccessEpoch",
    )
    suspend fun publishGeneration(
        id: String,
        generation: Long,
        sourceRevision: String,
        lexicalState: String,
        vectorState: String,
        accessEpoch: Long,
        expectedAccessEpoch: Long,
    ): Int

    @Query("UPDATE knowledge_documents SET requested_generation = :generation WHERE id = :id")
    suspend fun updateRequestedGeneration(id: String, generation: Long): Int

    @Query("UPDATE knowledge_documents SET access_epoch = access_epoch + 1, error_code = :errorCode WHERE id = :id")
    suspend fun bumpAccessEpoch(id: String, errorCode: String? = null): Int

    @Query("UPDATE knowledge_documents SET access_epoch = :accessEpoch WHERE id = :id")
    suspend fun setAccessEpoch(id: String, accessEpoch: Long): Int

    @Query("UPDATE knowledge_documents SET lexical_state = :state, error_code = :errorCode WHERE id = :id")
    suspend fun updateLexicalState(id: String, state: String, errorCode: String? = null): Int

    @Query("UPDATE knowledge_documents SET vector_state = :state, error_code = :errorCode WHERE id = :id")
    suspend fun updateVectorState(id: String, state: String, errorCode: String? = null): Int

    @Query("DELETE FROM knowledge_documents WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface KnowledgeChunkDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(chunk: KnowledgeChunkEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(chunks: List<KnowledgeChunkEntity>): List<Long>

    /**
     * FTS4 外部内容表由触发器维护，这里提供显式维护入口以支持迁移/重建。
     * user_scope 用于词法过滤优化，权限仍以关系 JOIN 为准。
     */
    @Query(
        "INSERT INTO knowledge_chunks_fts(rowid, title_terms, heading_terms, body_terms, user_scope) " +
            "VALUES(:chunkId, :titleTerms, :headingTerms, :bodyTerms, :userScope)",
    )
    suspend fun insertFts(chunkId: Long, titleTerms: String, headingTerms: String, bodyTerms: String, userScope: String)

    @Query("DELETE FROM knowledge_chunks_fts WHERE rowid = :chunkId")
    suspend fun deleteFts(chunkId: Long)

    @Query("SELECT * FROM knowledge_chunks WHERE id = :chunkId LIMIT 1")
    suspend fun find(chunkId: Long): KnowledgeChunkEntity?

    @Query(
        "SELECT * FROM knowledge_chunks WHERE knowledge_document_id = :knowledgeDocumentId " +
            "AND generation = :generation ORDER BY ordinal ASC",
    )
    suspend fun findGeneration(knowledgeDocumentId: String, generation: Long): List<KnowledgeChunkEntity>

    @Query(
        "SELECT * FROM knowledge_chunks WHERE knowledge_document_id = :knowledgeDocumentId " +
            "AND generation = :generation AND ordinal >= :fromOrdinal ORDER BY ordinal ASC LIMIT :limit",
    )
    suspend fun scanGeneration(knowledgeDocumentId: String, generation: Long, fromOrdinal: Int, limit: Int): List<KnowledgeChunkEntity>

    @Query("SELECT COUNT(*) FROM knowledge_chunks WHERE knowledge_document_id = :knowledgeDocumentId AND generation = :generation")
    suspend fun countGeneration(knowledgeDocumentId: String, generation: Long): Int

    /**
     * 只取一行生成口径（G-01 版本重建判定）：解析器版本 + 分块器版本。
     * 不读正文，避免在唤醒路径上把大段 raw_text 捞进内存。
     */
    @Query(
        "SELECT extractor_version AS extractorVersion, chunker_version AS chunkerVersion " +
            "FROM knowledge_chunks WHERE knowledge_document_id = :knowledgeDocumentId " +
            "AND generation = :generation LIMIT 1",
    )
    suspend fun findGenerationVersions(knowledgeDocumentId: String, generation: Long): GenerationVersions?

    /**
     * 取该代次中**尚无该 profile 向量**的块（按 ordinal 稳定排序）。
     * 重试同一批不会重复计入：已写入的块自然从结果里消失。
     */
    @Query(
        "SELECT c.* FROM knowledge_chunks c WHERE c.knowledge_document_id = :knowledgeDocumentId " +
            "AND c.generation = :generation AND NOT EXISTS (" +
            "SELECT 1 FROM knowledge_embeddings e WHERE e.chunk_id = c.id AND e.profile_fingerprint = :profileFingerprint" +
            ") ORDER BY c.ordinal ASC LIMIT :limit",
    )
    suspend fun findMissingEmbeddings(
        knowledgeDocumentId: String,
        generation: Long,
        profileFingerprint: String,
        limit: Int,
    ): List<KnowledgeChunkEntity>

    @Query("SELECT COUNT(*) FROM knowledge_chunks kd JOIN knowledge_documents d ON d.id = kd.knowledge_document_id WHERE d.user_id = :userId AND kd.generation = d.active_generation")
    suspend fun countActiveChunks(userId: Long): Int

    @Query(
        "SELECT kd.id FROM knowledge_chunks kd JOIN knowledge_documents d ON d.id = kd.knowledge_document_id " +
            "WHERE d.user_id = :userId AND kd.generation = d.active_generation",
    )
    suspend fun findActiveChunkIds(userId: Long): List<Long>

    @Query("DELETE FROM knowledge_chunks WHERE knowledge_document_id = :knowledgeDocumentId AND generation = :generation")
    suspend fun deleteGeneration(knowledgeDocumentId: String, generation: Long): Int
}

/**
 * 已发布代次的生成口径（解析器版本 + 分块器版本），供 [com.example.myapplication.data.knowledge.index.IndexVersioning] 判定是否重建。
 */
data class GenerationVersions(
    val extractorVersion: String,
    val chunkerVersion: String,
)

/**
 * exact 扫描行：一次取齐排序与原文核验所需字段。
 * 授权/代次/pin 条件全部在 SQL 里下推（设计 §5.4）。
 */
data class ExactVectorRow(
    val chunkId: Long,
    val knowledgeDocumentId: String,
    val documentId: String,
    val generation: Long,
    val sectionId: String,
    val sectionOrder: Int,
    val ordinal: Int,
    val rawText: String,
    val locatorJson: String,
    val sourceRevision: String,
    val extractorVersion: String,
    val dim: Int,
    val encoding: String,
    val vectorBlob: ByteArray,
    val norm: Double,
    val quantizationParams: String? = null,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

data class PinnedHitMetadataRow(
    val chunkId: Long,
    val knowledgeDocumentId: String,
    val documentId: String,
    val generation: Long,
    val sectionId: String,
    val sectionOrder: Int,
    val ordinal: Int,
    val rawText: String,
    val locatorJson: String,
    val sourceRevision: String,
)

@Dao
interface KnowledgeEmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(embedding: com.example.myapplication.data.local.entity.KnowledgeEmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(embeddings: List<com.example.myapplication.data.local.entity.KnowledgeEmbeddingEntity>)

    @Query("SELECT * FROM knowledge_embeddings WHERE chunk_id = :chunkId AND profile_fingerprint = :profileFingerprint LIMIT 1")
    suspend fun find(chunkId: Long, profileFingerprint: String): com.example.myapplication.data.local.entity.KnowledgeEmbeddingEntity?

    @Query(
        "SELECT e.* FROM knowledge_embeddings e JOIN knowledge_chunks k ON k.id = e.chunk_id " +
            "WHERE k.knowledge_document_id = :knowledgeDocumentId AND k.generation = :generation " +
            "AND e.profile_fingerprint = :profileFingerprint ORDER BY k.ordinal ASC",
    )
    suspend fun findForGeneration(
        knowledgeDocumentId: String,
        generation: Long,
        profileFingerprint: String,
    ): List<com.example.myapplication.data.local.entity.KnowledgeEmbeddingEntity>

    /** Bounded scan used to validate a staged encoding before its active pointer is switched. */
    @Query(
        "SELECT e.* FROM knowledge_embeddings e JOIN knowledge_chunks k ON k.id = e.chunk_id " +
            "WHERE k.knowledge_document_id = :knowledgeDocumentId AND k.generation = :generation " +
            "AND e.profile_fingerprint = :profileFingerprint AND e.chunk_id > :afterChunkId " +
            "ORDER BY e.chunk_id ASC LIMIT :limit",
    )
    suspend fun scanGenerationRows(
        knowledgeDocumentId: String,
        generation: Long,
        profileFingerprint: String,
        afterChunkId: Long,
        limit: Int,
    ): List<com.example.myapplication.data.local.entity.KnowledgeEmbeddingEntity>

    @Query(
        "SELECT e.* FROM knowledge_embeddings e JOIN knowledge_chunks k ON k.id = e.chunk_id " +
            "JOIN knowledge_documents d ON d.id = k.knowledge_document_id " +
            "WHERE d.user_id = :userId AND k.generation = d.active_generation " +
            "AND e.profile_fingerprint = :profileFingerprint ORDER BY k.knowledge_document_id ASC, k.ordinal ASC",
    )
    suspend fun findActiveForUser(
        userId: Long,
        profileFingerprint: String,
    ): List<com.example.myapplication.data.local.entity.KnowledgeEmbeddingEntity>

    @Query(
        "SELECT e.* FROM knowledge_embeddings e JOIN knowledge_chunks k ON k.id = e.chunk_id " +
            "JOIN knowledge_documents d ON d.id = k.knowledge_document_id " +
            "WHERE d.user_id = :userId AND k.generation = d.active_generation " +
            "AND e.profile_fingerprint = :profileFingerprint AND d.document_id = :documentId " +
            "ORDER BY k.ordinal ASC",
    )
    suspend fun findActiveForDocument(
        userId: Long,
        documentId: String,
        profileFingerprint: String,
    ): List<com.example.myapplication.data.local.entity.KnowledgeEmbeddingEntity>

    @Query("SELECT COUNT(*) FROM knowledge_embeddings WHERE chunk_id = :chunkId AND profile_fingerprint = :profileFingerprint")
    suspend fun exists(chunkId: Long, profileFingerprint: String): Int

    /** 当前用户 active generation 下该 profile 的向量覆盖（统计页用）。 */
    @Query(
        "SELECT COUNT(*) FROM knowledge_embeddings e JOIN knowledge_chunks k ON k.id = e.chunk_id " +
            "JOIN knowledge_documents d ON d.id = k.knowledge_document_id " +
            "WHERE d.user_id = :userId AND k.generation = d.active_generation " +
            "AND e.profile_fingerprint = :profileFingerprint",
    )
    suspend fun countActiveForUser(userId: Long, profileFingerprint: String): Int

    @Query(
        "SELECT COUNT(*) FROM knowledge_embeddings e JOIN knowledge_chunks k ON k.id = e.chunk_id " +
            "WHERE k.knowledge_document_id = :knowledgeDocumentId AND k.generation = :generation " +
            "AND e.profile_fingerprint = :profileFingerprint",
    )
    suspend fun countForGeneration(knowledgeDocumentId: String, generation: Long, profileFingerprint: String): Int

    @Query("DELETE FROM knowledge_embeddings WHERE chunk_id = :chunkId AND profile_fingerprint = :profileFingerprint")
    suspend fun delete(chunkId: Long, profileFingerprint: String): Int

    @Query(
        "DELETE FROM knowledge_embeddings WHERE profile_fingerprint = :profileFingerprint AND chunk_id IN " +
            "(SELECT id FROM knowledge_chunks WHERE knowledge_document_id = :knowledgeDocumentId AND generation = :generation)",
    )
    suspend fun deleteForGeneration(knowledgeDocumentId: String, generation: Long, profileFingerprint: String): Int

    /**
     * exact 扫描（设计 §5.4）：固定 (kdId, generation, sourceRevision, accessEpoch) pin，
     * 每批按 chunk id 递增游标；授权与源一致性条件全部下推。
     * 不在每批重新读取 active_generation，避免混入新代次。
     */
    @Query(
        """
        SELECT c.id AS chunkId, c.knowledge_document_id AS knowledgeDocumentId, k.document_id AS documentId,
               c.generation AS generation, c.section_id AS sectionId, s.order_index AS sectionOrder,
               c.ordinal AS ordinal, c.raw_text AS rawText, c.locator_json AS locatorJson,
               k.source_revision AS sourceRevision, c.extractor_version AS extractorVersion,
               e.dim AS dim, e.encoding AS encoding, e.vector_blob AS vectorBlob, e.norm AS norm,
               e.quantization_params AS quantizationParams
        FROM knowledge_chunks c
        JOIN knowledge_documents k ON k.id = c.knowledge_document_id
        JOIN reader_documents d ON d.id = k.document_id
        JOIN reader_sections s ON s.id = c.section_id
        JOIN knowledge_embeddings e ON e.chunk_id = c.id AND e.profile_fingerprint = :profileFingerprint
        WHERE k.user_id = :userId
          AND k.id = :knowledgeDocumentId
          AND c.generation = :pinnedGeneration
          AND k.source_revision = :pinnedSourceRevision
          AND d.content_revision = :pinnedSourceRevision
          AND d.access_epoch = :pinnedAccessEpoch
          AND d.import_status = 'READY'
          AND d.deleted_at IS NULL
          AND (d.kind = 'BUILTIN' OR d.owner_user_id = :userId)
          AND c.id > :lastChunkId
        ORDER BY c.id ASC
        LIMIT :batchSize
        """,
    )
    suspend fun scanPinned(
        userId: Long,
        knowledgeDocumentId: String,
        pinnedGeneration: Long,
        pinnedSourceRevision: String,
        pinnedAccessEpoch: Long,
        profileFingerprint: String,
        lastChunkId: Long,
        batchSize: Int,
    ): List<ExactVectorRow>

    /**
     * 按 chunk id 取回授权行（ANN 候选只给 id，元数据必须回表取，且同样过 pin/授权）。
     */
    @Query(
        """
        SELECT c.id AS chunkId, c.knowledge_document_id AS knowledgeDocumentId, k.document_id AS documentId,
               c.generation AS generation, c.section_id AS sectionId, s.order_index AS sectionOrder,
               c.ordinal AS ordinal, c.raw_text AS rawText, c.locator_json AS locatorJson,
               k.source_revision AS sourceRevision, c.extractor_version AS extractorVersion,
               e.dim AS dim, e.encoding AS encoding, e.vector_blob AS vectorBlob, e.norm AS norm,
               e.quantization_params AS quantizationParams
        FROM knowledge_chunks c
        JOIN knowledge_documents k ON k.id = c.knowledge_document_id
        JOIN reader_documents d ON d.id = k.document_id
        JOIN reader_sections s ON s.id = c.section_id
        JOIN knowledge_embeddings e ON e.chunk_id = c.id AND e.profile_fingerprint = :profileFingerprint
        WHERE k.user_id = :userId
          AND k.id = :knowledgeDocumentId
          AND c.generation = :pinnedGeneration
          AND k.source_revision = :pinnedSourceRevision
          AND d.content_revision = :pinnedSourceRevision
          AND d.access_epoch = :pinnedAccessEpoch
          AND d.import_status = 'READY'
          AND d.deleted_at IS NULL
          AND (d.kind = 'BUILTIN' OR d.owner_user_id = :userId)
          AND c.id IN (:chunkIds)
        """,
    )
    suspend fun findPinnedByIds(
        userId: Long,
        knowledgeDocumentId: String,
        pinnedGeneration: Long,
        pinnedSourceRevision: String,
        pinnedAccessEpoch: Long,
        profileFingerprint: String,
        chunkIds: List<Long>,
    ): List<ExactVectorRow>

    /** Fetch only displayed hit fields after ANN ranking; never selects the vector BLOB. */
    @Query(
        """
        SELECT c.id AS chunkId, c.knowledge_document_id AS knowledgeDocumentId, k.document_id AS documentId,
               c.generation AS generation, c.section_id AS sectionId, s.order_index AS sectionOrder,
               c.ordinal AS ordinal, c.raw_text AS rawText, c.locator_json AS locatorJson,
               k.source_revision AS sourceRevision
        FROM knowledge_chunks c
        JOIN knowledge_documents k ON k.id = c.knowledge_document_id
        JOIN reader_documents d ON d.id = k.document_id
        JOIN reader_sections s ON s.id = c.section_id
        JOIN knowledge_embeddings e ON e.chunk_id = c.id AND e.profile_fingerprint = :profileFingerprint
        WHERE k.user_id = :userId
          AND k.id = :knowledgeDocumentId
          AND c.generation = :pinnedGeneration
          AND k.source_revision = :pinnedSourceRevision
          AND d.content_revision = :pinnedSourceRevision
          AND d.access_epoch = :pinnedAccessEpoch
          AND d.import_status = 'READY'
          AND d.deleted_at IS NULL
          AND (d.kind = 'BUILTIN' OR d.owner_user_id = :userId)
          AND c.id IN (:chunkIds)
        """,
    )
    suspend fun findPinnedHitMetadataByIds(
        userId: Long,
        knowledgeDocumentId: String,
        pinnedGeneration: Long,
        pinnedSourceRevision: String,
        pinnedAccessEpoch: Long,
        profileFingerprint: String,
        chunkIds: List<Long>,
    ): List<PinnedHitMetadataRow>

    /** Lightweight current-pin allow-list used while scanning a file-backed ANN manifest. */
    @Query(
        """
        SELECT c.id AS chunkId
        FROM knowledge_chunks c
        JOIN knowledge_documents k ON k.id = c.knowledge_document_id
        JOIN reader_documents d ON d.id = k.document_id
        JOIN knowledge_embeddings e ON e.chunk_id = c.id AND e.profile_fingerprint = :profileFingerprint
        WHERE k.user_id = :userId
          AND k.id = :knowledgeDocumentId
          AND c.generation = :pinnedGeneration
          AND k.source_revision = :pinnedSourceRevision
          AND d.content_revision = :pinnedSourceRevision
          AND d.access_epoch = :pinnedAccessEpoch
          AND d.import_status = 'READY'
          AND d.deleted_at IS NULL
          AND (d.kind = 'BUILTIN' OR d.owner_user_id = :userId)
        ORDER BY c.id ASC
        """,
    )
    suspend fun listPinnedChunkIds(
        userId: Long,
        knowledgeDocumentId: String,
        pinnedGeneration: Long,
        pinnedSourceRevision: String,
        pinnedAccessEpoch: Long,
        profileFingerprint: String,
    ): List<Long>

    /** 该 pin 下的合法行数；用于 eligible 统计与 continuation 判断。 */
    @Query(
        """
        SELECT COUNT(*) FROM knowledge_chunks c
        JOIN knowledge_documents k ON k.id = c.knowledge_document_id
        JOIN reader_documents d ON d.id = k.document_id
        JOIN knowledge_embeddings e ON e.chunk_id = c.id AND e.profile_fingerprint = :profileFingerprint
        WHERE k.user_id = :userId
          AND k.id = :knowledgeDocumentId
          AND c.generation = :pinnedGeneration
          AND k.source_revision = :pinnedSourceRevision
          AND d.content_revision = :pinnedSourceRevision
          AND d.access_epoch = :pinnedAccessEpoch
          AND d.import_status = 'READY'
          AND d.deleted_at IS NULL
          AND (d.kind = 'BUILTIN' OR d.owner_user_id = :userId)
        """,
    )
    suspend fun countPinned(
        userId: Long,
        knowledgeDocumentId: String,
        pinnedGeneration: Long,
        pinnedSourceRevision: String,
        pinnedAccessEpoch: Long,
        profileFingerprint: String,
    ): Int
}

@Dao
interface KnowledgeVectorStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: com.example.myapplication.data.local.entity.KnowledgeVectorStateEntity)

    @Query(
        "SELECT * FROM knowledge_vector_states WHERE knowledge_document_id = :knowledgeDocumentId " +
            "AND generation = :generation AND profile_fingerprint = :profileFingerprint LIMIT 1",
    )
    suspend fun find(
        knowledgeDocumentId: String,
        generation: Long,
        profileFingerprint: String,
    ): com.example.myapplication.data.local.entity.KnowledgeVectorStateEntity?

    @Query(
        "SELECT * FROM knowledge_vector_states WHERE knowledge_document_id = :knowledgeDocumentId " +
            "AND generation = :generation ORDER BY profile_fingerprint ASC",
    )
    suspend fun findForGeneration(
        knowledgeDocumentId: String,
        generation: Long,
    ): List<com.example.myapplication.data.local.entity.KnowledgeVectorStateEntity>

    @Query(
        "SELECT * FROM knowledge_vector_states WHERE knowledge_document_id = :knowledgeDocumentId " +
            "AND profile_fingerprint = :profileFingerprint ORDER BY generation DESC",
    )
    suspend fun findForProfile(
        knowledgeDocumentId: String,
        profileFingerprint: String,
    ): List<com.example.myapplication.data.local.entity.KnowledgeVectorStateEntity>

    @Query("DELETE FROM knowledge_vector_states WHERE knowledge_document_id = :knowledgeDocumentId AND generation = :generation")
    suspend fun deleteForGeneration(knowledgeDocumentId: String, generation: Long): Int
}

@Dao
interface KnowledgeAnnManifestDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(manifest: KnowledgeAnnManifestEntity)

    @Query("SELECT * FROM knowledge_ann_manifests WHERE id = :id LIMIT 1")
    suspend fun find(id: String): KnowledgeAnnManifestEntity?

    @Query(
        "SELECT * FROM knowledge_ann_manifests WHERE user_id = :userId AND profile_fingerprint = :profileFingerprint " +
            "AND status = 'READY' ORDER BY created_at DESC, id DESC LIMIT 1",
    )
    suspend fun findLatestReady(userId: Long, profileFingerprint: String): KnowledgeAnnManifestEntity?

    @Query("UPDATE knowledge_ann_manifests SET status = :status WHERE id = :id AND status = :expectedStatus")
    suspend fun updateStatus(id: String, expectedStatus: String, status: String): Int

    @Query("DELETE FROM knowledge_ann_manifests WHERE id = :id")
    suspend fun delete(id: String): Int
}
