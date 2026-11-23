package com.example.myapplication.data.knowledge.lexical

import com.example.myapplication.data.knowledge.chunk.StructuredChunker
import com.example.myapplication.data.local.dao.KnowledgeChunkDao
import com.example.myapplication.data.local.dao.KnowledgeLexicalDao
import com.example.myapplication.data.local.dao.LexicalCandidateRow

/**
 * 词法索引读写（设计 §7.2）。
 *
 * - 写入：chunk 草稿 -> ChineseTokenizer 双字/术语 -> knowledge_chunks_fts；
 * - 查询：MATCH 表达式由 tokenizer 生成（不拼原始 SQL），候选有界，
 *   scope/授权/代次条件在 DAO 的 SQL 里下推；
 * - 本地词法搜索不读取任何密钥，也不要求 AI enabled。
 */
class LexicalIndex(
    private val chunkDao: KnowledgeChunkDao,
    private val lexicalDao: KnowledgeLexicalDao,
) {
    data class Candidate(
        val chunkId: Long,
        val knowledgeDocumentId: String,
        val generation: Long,
        val documentId: String,
        val sectionId: String,
        val rawText: String,
        val locatorJson: String,
        val sourceRevision: String,
        val score: Double,
        val titleHits: Int,
        val headingHits: Int,
        val bodyHits: Int,
    )

    /** 写入一个 chunk 的 FTS 行；rowid 与 knowledge_chunks.id 对齐。 */
    suspend fun indexChunk(
        chunkId: Long,
        userId: Long,
        title: String,
        headingText: String,
        bodyText: String,
    ) {
        chunkDao.insertFts(
            chunkId = chunkId,
            titleTerms = ChineseTokenizer.toIndexText(title),
            headingTerms = ChineseTokenizer.toIndexText(headingText),
            bodyTerms = ChineseTokenizer.toIndexText(bodyText),
            userScope = userScopeOf(userId),
        )
    }

    suspend fun removeChunk(chunkId: Long) {
        chunkDao.deleteFts(chunkId)
    }

    /**
     * 有界候选检索：按 chunkId 升序**分页**扫描，边取边按字段加权分排序，返回至多 [topK]。
     *
     * 为什么不直接 `ORDER BY c.id LIMIT :candidateLimit`：FTS4 没有 bm25/rank 函数，
     * 打分必须在 Kotlin 侧用 `matchinfo` 完成，因此 SQL 的 LIMIT 是在**打分之前**截断的。
     * 当命中行数远超 limit 时，结果只取决于 chunk 的插入顺序（id 小的赢），
     * 后导入文档的高相关块永远进不了候选（P1-6）。这里改为分页扫描到
     * [MAX_SCAN_ROWS] 行（至多 [SCAN_PAGE_MULTIPLIER] 个 limit 页）后再统一定序：
     * 仍是确定性上界（不会全库扫描），但不再固定截断同一段前缀。
     */
    suspend fun search(
        userId: Long,
        query: String,
        documentIds: List<String>,
        candidateLimit: Int = DEFAULT_CANDIDATE_LIMIT,
        topK: Int = DEFAULT_TOP_K,
    ): List<Candidate> {
        val expression = ChineseTokenizer.toMatchExpression(query) ?: return emptyList()
        val limit = candidateLimit.coerceIn(1, MAX_CANDIDATE_LIMIT)
        val pageSize = limit.coerceAtMost(SCAN_PAGE_ROWS)
        // 扫描上界不能小于 limit：limit 允许到 MAX_CANDIDATE_LIMIT(500)，而扫描硬上限是
        // MAX_SCAN_ROWS(400)。此前写成 coerceIn(limit, MAX_SCAN_ROWS)，一旦调用方要 450~500
        // 个候选就是"空区间"直接抛 IllegalArgumentException（书内搜索传 500 时必崩）。
        val scanCap = (limit * SCAN_PAGE_MULTIPLIER).coerceIn(minOf(limit, MAX_SCAN_ROWS), MAX_SCAN_ROWS)
        val scopeDocumentIds = documentIds.take(MAX_SCOPE_DOCUMENTS)

        val rows = ArrayList<LexicalCandidateRow>(scanCap)
        var afterChunkId = 0L
        while (rows.size < scanCap) {
            val page = if (documentIds.isEmpty()) {
                lexicalDao.searchAllUserDocuments(
                    userId = userId,
                    userScope = userScopeOf(userId),
                    matchExpression = expression,
                    candidateLimit = pageSize,
                    afterChunkId = afterChunkId,
                )
            } else {
                lexicalDao.searchInDocuments(
                    userId = userId,
                    userScope = userScopeOf(userId),
                    matchExpression = expression,
                    documentIds = scopeDocumentIds,
                    candidateLimit = pageSize,
                    afterChunkId = afterChunkId,
                )
            }
            if (page.isEmpty()) break
            rows += page
            afterChunkId = page.last().chunkId
            if (page.size < pageSize) break
        }
        return rows
            .map { row -> row.toCandidate() }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy { it.chunkId },
            )
            .take(topK.coerceAtLeast(1))
    }

    /** user_scope 是分词后的普通列值；仅作过滤优化，权限始终回表 JOIN。 */
    fun userScopeOf(userId: Long): String = "u$userId"

    private fun LexicalCandidateRow.toCandidate(): Candidate {
        val score = FtsRanker.scoreOf(matchInfo)
        return Candidate(
            chunkId = chunkId,
            knowledgeDocumentId = knowledgeDocumentId,
            generation = generation,
            documentId = documentId,
            sectionId = sectionId,
            rawText = rawText,
            locatorJson = locatorJson,
            sourceRevision = sourceRevision,
            score = score.value,
            titleHits = score.titleHits,
            headingHits = score.headingHits,
            bodyHits = score.bodyHits,
        )
    }

    companion object {
        const val DEFAULT_CANDIDATE_LIMIT = 80
        const val MAX_CANDIDATE_LIMIT = 500
        const val DEFAULT_TOP_K = 20
        const val MAX_SCOPE_DOCUMENTS = 64

        /** 单页扫描行数（= 最小 limit，保证至少一次扫描覆盖旧行为）。 */
        const val SCAN_PAGE_ROWS = 80

        /** 最多扫描 limit 的多少倍（P1-6：避免固定截断前 N 行，又不做全库扫描）。 */
        const val SCAN_PAGE_MULTIPLIER = 4

        /** 扫描行数硬上限：库再大也不允许单次检索无界扫描。 */
        const val MAX_SCAN_ROWS = 400

        /** chunk 内容 hash 变化时的重建辅助：先删后写（同一事务内由调用方保证）。 */
        fun contentHashChanged(previous: String, current: String): Boolean = previous != current

        fun chunkerVersion(): String = StructuredChunker.CHUNKER_VERSION
    }
}
