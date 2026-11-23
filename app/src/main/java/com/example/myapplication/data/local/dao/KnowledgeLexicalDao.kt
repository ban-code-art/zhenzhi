package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * FTS4 词法检索投影。
 *
 * 授权/代次条件全部下推 SQL：user_id、active_generation、source_revision 一致、
 * import_status=READY、deleted_at IS NULL、BUILTIN 或 owner 匹配；
 * user_scope 仅作优化过滤，权限判断以回表 JOIN 为准。
 */
data class LexicalCandidateRow(
    val chunkId: Long,
    val knowledgeDocumentId: String,
    val generation: Long,
    val documentId: String,
    val sectionId: String,
    val rawText: String,
    val locatorJson: String,
    val sourceRevision: String,
    val matchInfo: ByteArray,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

@Dao
interface KnowledgeLexicalDao {
    /**
     * FTS-first 检索（W3-12 基准修复 F1）。
     *
     * 原实现用 `JOIN ... AND k.document_id IN (:documentIds)`，SQLite 会把
     * `knowledge_documents -> knowledge_chunks(idx_knowledge_chunk_scan)` 选为外循环，
     * 先扫描 scope 下**全部** chunk 再逐行探测 FTS；单文档 scope 等价于全库扫描。
     * 实测 10k chunks：词法 p50 从 ~0.15ms 退化到 ~550ms（报告 §8 F1）。
     *
     * 修复：用 `CROSS JOIN` 固定 left-to-right 连接顺序，强制 `knowledge_chunks_fts`
     * 作为驱动表（EXPLAIN QUERY PLAN 首行 `SCAN knowledge_chunks_fts VIRTUAL TABLE INDEX 6:`）。
     * 过滤条件与旧 SQL 完全一致，只改连接顺序；授权/用户隔离/删除/代次语义不变。
     *
     * 旧 SQL（回退对照，勿启用）：
     * `FROM knowledge_chunks_fts JOIN knowledge_chunks c ON c.id = fts.rowid
     *  JOIN knowledge_documents k ON k.id = c.knowledge_document_id
     *  JOIN reader_documents d ON d.id = k.document_id WHERE fts MATCH ? AND ...`
     * ——其余条件与本查询相同，仅 JOIN 写法不同。
     */
    @Query(
        """
        SELECT c.id AS chunkId, c.knowledge_document_id AS knowledgeDocumentId, c.generation AS generation,
               k.document_id AS documentId, c.section_id AS sectionId, c.raw_text AS rawText,
               c.locator_json AS locatorJson, k.source_revision AS sourceRevision,
               matchinfo(knowledge_chunks_fts) AS matchInfo
        FROM knowledge_chunks_fts
        CROSS JOIN knowledge_chunks c
        CROSS JOIN knowledge_documents k
        CROSS JOIN reader_documents d
        WHERE knowledge_chunks_fts MATCH :matchExpression
          AND c.id = knowledge_chunks_fts.rowid
          AND k.id = c.knowledge_document_id
          AND d.id = k.document_id
          AND knowledge_chunks_fts.user_scope = :userScope
          AND k.user_id = :userId
          AND c.generation = k.active_generation
          AND k.source_revision = d.content_revision
          AND d.import_status = 'READY'
          AND d.deleted_at IS NULL
          AND (d.kind = 'BUILTIN' OR d.owner_user_id = :userId)
          AND k.document_id IN (:documentIds)
          AND c.id > :afterChunkId
        ORDER BY c.id ASC
        LIMIT :candidateLimit
        """,
    )
    suspend fun searchInDocuments(
        userId: Long,
        userScope: String,
        matchExpression: String,
        documentIds: List<String>,
        candidateLimit: Int,
        /** 翻页游标：只取 chunkId 大于该值的行（按 id 升序分页，避免固定截断前 N 行）。 */
        afterChunkId: Long = 0L,
    ): List<LexicalCandidateRow>

    @Query(
        """
        SELECT c.id AS chunkId, c.knowledge_document_id AS knowledgeDocumentId, c.generation AS generation,
               k.document_id AS documentId, c.section_id AS sectionId, c.raw_text AS rawText,
               c.locator_json AS locatorJson, k.source_revision AS sourceRevision,
               matchinfo(knowledge_chunks_fts) AS matchInfo
        FROM knowledge_chunks_fts
        JOIN knowledge_chunks c ON c.id = knowledge_chunks_fts.rowid
        JOIN knowledge_documents k ON k.id = c.knowledge_document_id
        JOIN reader_documents d ON d.id = k.document_id
        WHERE knowledge_chunks_fts MATCH :matchExpression
          AND knowledge_chunks_fts.user_scope = :userScope
          AND k.user_id = :userId
          AND c.generation = k.active_generation
          AND k.source_revision = d.content_revision
          AND d.import_status = 'READY'
          AND d.deleted_at IS NULL
          AND (d.kind = 'BUILTIN' OR d.owner_user_id = :userId)
          AND c.id > :afterChunkId
        ORDER BY c.id ASC
        LIMIT :candidateLimit
        """,
    )
    suspend fun searchAllUserDocuments(
        userId: Long,
        userScope: String,
        matchExpression: String,
        candidateLimit: Int,
        /** 翻页游标：只取 chunkId 大于该值的行（按 id 升序分页，避免固定截断前 N 行）。 */
        afterChunkId: Long = 0L,
    ): List<LexicalCandidateRow>

    @Query(
        """
        SELECT COUNT(*) FROM knowledge_chunks_fts
        JOIN knowledge_chunks c ON c.id = knowledge_chunks_fts.rowid
        JOIN knowledge_documents k ON k.id = c.knowledge_document_id
        WHERE k.user_id = :userId AND knowledge_chunks_fts MATCH :matchExpression
        """,
    )
    suspend fun countMatches(userId: Long, matchExpression: String): Int
}
