package com.example.myapplication.data.knowledge.retrieval

import com.example.myapplication.data.knowledge.index.VectorCodec
import com.example.myapplication.data.local.dao.ExactVectorRow
import com.example.myapplication.data.local.dao.KnowledgeEmbeddingDao
import com.example.myapplication.domain.reader.PinnedDocument
import com.example.myapplication.domain.reader.RetrievalBranchStatus
import com.example.myapplication.domain.reader.RetrievalHit
import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException

/**
 * exact 向量后端（设计 §7.3 / §14 / FX-09 / FX-10）。
 *
 * - 按固定 pin（kdId, generation, sourceRevision, accessEpoch）分批扫描；
 * - batch 128–256，查询范数只算一次；
 * - 每行解码校验 dim/encoding/NaN/Inf/零范数，坏行跳过并计入 warning，
 *   绝不用 0 向量冒充“相似度 0”参与排序；
 * - workBudget 是确定性扫描行数上限：达到即 partial，并给出可续检游标；
 * - deadline 只影响 partial 覆盖，不改变已扫描部分的排序；
 * - 缓存只省 IO：miss 回源，cold/warm/evicted 输出一致。
 */
class ExactVectorBackend(
    private val embeddingDao: KnowledgeEmbeddingDao,
    private val cache: VectorBlobCache = VectorBlobCache(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Result(
        val hits: List<RetrievalHit>,
        val status: RetrievalBranchStatus,
        val scannedCount: Int,
        val eligibleCount: Int,
        val lastChunkId: Long,
        val timedOut: Boolean,
        val warnings: List<String>,
        val elapsedMillis: Long,
    )

    /**
     * 扫描 pin 文档集合的向量，返回按 cosine 降序（并列按 chunkId 升序）的候选。
     */
    suspend fun search(
        userId: Long,
        documents: List<PinnedDocument>,
        profileFingerprint: String,
        queryVector: FloatArray,
        topK: Int,
        workBudget: Int,
        deadlineAtMs: Long?,
        startAfterChunkId: Long = 0,
    ): Result {
        val started = clock()
        val queryNorm = VectorCodec.normOf(queryVector)
        if (queryNorm <= VectorCodec.NORM_EPSILON) {
            return Result(
                hits = emptyList(),
                status = RetrievalBranchStatus.UNAVAILABLE,
                scannedCount = 0,
                eligibleCount = 0,
                lastChunkId = startAfterChunkId,
                timedOut = false,
                warnings = listOf("QUERY_VECTOR_DEGENERATE"),
                elapsedMillis = clock() - started,
            )
        }

        val boundedTopK = topK.coerceIn(1, MAX_TOP_K)
        val worstFirst = compareBy<ScoredHit> { it.score }.thenByDescending { it.hit.chunkId }
        val scored = PriorityQueue<ScoredHit>(boundedTopK, worstFirst)
        val warnings = linkedSetOf<String>()
        if (topK != boundedTopK) warnings += "TOP_K_CLAMPED"
        var scanned = 0
        var eligible = 0
        var lastChunkId = startAfterChunkId
        var timedOut = false
        var budgetExhausted = false

        outer@ for (document in documents.sortedBy { it.documentId }) {
            var cursor = startAfterChunkId
            while (true) {
                if (deadlineAtMs != null && clock() >= deadlineAtMs) {
                    timedOut = true
                    break@outer
                }
                val remaining = workBudget - scanned
                if (remaining <= 0) {
                    budgetExhausted = true
                    break@outer
                }
                val requested = minOf(BATCH_SIZE, remaining)
                val batch = embeddingDao.scanPinned(
                    userId = userId,
                    knowledgeDocumentId = document.knowledgeDocumentId,
                    pinnedGeneration = document.generation,
                    pinnedSourceRevision = document.sourceRevision,
                    pinnedAccessEpoch = document.accessEpoch,
                    profileFingerprint = profileFingerprint,
                    lastChunkId = cursor,
                    batchSize = requested,
                )
                if (batch.isEmpty()) break
                for (row in batch) {
                    cursor = row.chunkId
                    lastChunkId = maxOf(lastChunkId, row.chunkId)
                    scanned += 1
                    when (val decoded = decodeRow(row)) {
                        is DecodedRow.Ok -> {
                            eligible += 1
                            val score = VectorCodec.cosine(queryVector, decoded.vector)
                            if (score == null) {
                                warnings += "ZERO_NORM_ROW_SKIPPED"
                                continue
                            }
                            val candidate = ScoredHit(row.toHit(score), score)
                            if (scored.size < boundedTopK) {
                                scored += candidate
                            } else if (worstFirst.compare(candidate, scored.peek()) > 0) {
                                scored.poll()
                                scored += candidate
                            }
                        }

                        is DecodedRow.Invalid -> warnings += decoded.reason
                    }
                }
                // 返回行数少于请求数 => 该文档已扫完
                if (batch.size < requested) break
                // 正好用满预算且仍有后续行 => partial，交回续检游标
                if (scanned >= workBudget) {
                    budgetExhausted = true
                    break@outer
                }
            }
        }

        val ranked = scored.sortedWith(
            compareByDescending<ScoredHit> { it.score }.thenBy { it.hit.chunkId },
        )

        val status = when {
            timedOut || budgetExhausted -> RetrievalBranchStatus.PARTIAL
            else -> RetrievalBranchStatus.COMPLETE
        }
        return Result(
            hits = ranked.map { it.hit },
            status = status,
            scannedCount = scanned,
            eligibleCount = eligible,
            lastChunkId = lastChunkId,
            timedOut = timedOut,
            warnings = warnings.toList(),
            elapsedMillis = clock() - started,
        )
    }

    private sealed interface DecodedRow {
        data class Ok(val vector: FloatArray) : DecodedRow
        data class Invalid(val reason: String) : DecodedRow
    }

    private fun decodeRow(row: ExactVectorRow): DecodedRow {
        val key = cacheKeyOf(row)
        cache.get(key)?.let { return DecodedRow.Ok(it) }
        return when (val decoded = VectorCodec.decode(
            blob = row.vectorBlob,
            dim = row.dim,
            encoding = row.encoding,
            quantizationParams = row.quantizationParams,
        )) {
            is VectorCodec.Decoded.Ok -> {
                cache.put(key, decoded.vector)
                DecodedRow.Ok(decoded.vector)
            }

            is VectorCodec.Decoded.Invalid -> DecodedRow.Invalid(decoded.reason.uppercase().replace(' ', '_'))
        }
    }

    /**
     * 缓存 key 含 chunkId/dim/encoding/revision：源或编码变化自然 miss。
     * 用户隔离由 pin 查询保证（行本身已属于该用户 scope），缓存只存已授权行。
     */
    private fun cacheKeyOf(row: ExactVectorRow): String =
        "row|${row.chunkId}|${row.dim}|${row.encoding}|${row.sourceRevision}"

    private data class ScoredHit(val hit: RetrievalHit, val score: Double)

    private fun ExactVectorRow.toHit(score: Double) = RetrievalHit(
            chunkId = chunkId,
            knowledgeDocumentId = knowledgeDocumentId,
            documentId = documentId,
            sectionId = sectionId,
            sectionOrder = sectionOrder,
            ordinal = ordinal,
            rawText = rawText,
            locatorJson = locatorJson,
            sourceRevision = sourceRevision,
            generation = generation,
            score = score,
            vectorScore = score,
        )

    companion object {
        const val BATCH_SIZE = 128
        const val MAX_TOP_K = 512
    }
}

/** 已解码向量的内存缓存：只省解码/IO，不参与排序决策。 */
class VectorBlobCache(
    maxBytes: Long = 32L * 1024 * 1024,
) {
    private val lru = ByteBoundedLru<String, FloatArray>(maxBytes) { it.size.toLong() * 4 }

    fun get(key: String): FloatArray? = lru.get(key)?.copyOf()

    fun put(key: String, vector: FloatArray) = lru.put(key, vector.copyOf())

    fun evictAll() = lru.evictAll()

    fun size(): Int = lru.size()
}
