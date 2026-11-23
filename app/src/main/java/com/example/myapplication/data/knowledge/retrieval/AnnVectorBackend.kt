package com.example.myapplication.data.knowledge.retrieval

import com.example.myapplication.data.knowledge.index.VectorCodec
import com.example.myapplication.data.local.dao.KnowledgeEmbeddingDao
import com.example.myapplication.domain.reader.PinnedDocument
import com.example.myapplication.domain.reader.RetrievalBranchStatus
import com.example.myapplication.domain.reader.RetrievalHit
import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException

/**
 * ANN 后端（设计 §7.3 / §13 / W3-09）。
 *
 * 结构：immutable base manifest + 小型 delta overlay + tombstone/epoch 过滤。
 *
 * 本实现不引入 native 依赖（API24/ABI 兼容在引入 artifact 时另行验证），
 * base 用内存线性扫描作为“候选生成器”，但完整遵守 ANN 语义：
 *  - 新块尚未合并 base 时，delta 做有界 exact 并与 base 候选融合；
 *  - 源删除/撤权后 tombstone 即时生效（每批回表复核授权与 pin）；
 *  - manifest 损坏/缺失时返回 null，由调用方回退 exact 并标记降级；
 *  - 结果标 approximate=true，但 approximate 不等于 partial。
 *
 * 大库 native 后端替换时只需实现同一 [AnnVectorBackend.Backend] 接口。
 */
class AnnVectorBackend(
    private val embeddingDao: KnowledgeEmbeddingDao,
    private val manifestStore: AnnManifestStore,
    private val exactFallback: ExactVectorBackend,
) {
    data class Result(
        val hits: List<RetrievalHit>,
        val status: RetrievalBranchStatus,
        val scannedCount: Int,
        val eligibleCount: Int,
        val approximate: Boolean,
        val usedFallback: Boolean,
        val warnings: List<String>,
        /**
         * exact fallback 是否因 deadline 截断（manifest/delta 路径本身是内存操作，
         * 没有 IO 超时语义，恒为 false）。编排层据此如实标记 outcome.timedOut。
         */
        val timedOut: Boolean = false,
    )

    /**
     * 检索：优先用 manifest 候选；无 manifest 或损坏时回退 exact。
     * [deltaChunkIds] 是尚未合并进 base 的块（本次调用由调用方从 ready_count
     * 与 base 覆盖差集算出；为空时只查 base）。
     */
    suspend fun search(
        userId: Long,
        documents: List<PinnedDocument>,
        profileFingerprint: String,
        queryVector: FloatArray,
        topK: Int,
        workBudget: Int,
        deadlineAtMs: Long?,
        deltaChunkIds: Set<Long> = emptySet(),
    ): Result {
        val warnings = linkedSetOf<String>()
        val stored = manifestStore.read(userId, profileFingerprint)
        if (stored == null) {
            warnings += "ANN_MANIFEST_UNAVAILABLE"
            val fallback = exactFallback.search(
                userId = userId,
                documents = documents,
                profileFingerprint = profileFingerprint,
                queryVector = queryVector,
                topK = topK,
                workBudget = workBudget,
                deadlineAtMs = deadlineAtMs,
            )
            return Result(
                hits = fallback.hits.map { it.copy(vectorRank = null) },
                status = fallback.status,
                scannedCount = fallback.scannedCount,
                eligibleCount = fallback.eligibleCount,
                approximate = false,
                usedFallback = true,
                warnings = (warnings + fallback.warnings).toList(),
                timedOut = fallback.timedOut,
            )
        }

        val manifest = stored.manifest
        if (manifest.dim != queryVector.size) {
            warnings += "ANN_DIM_MISMATCH"
            val fallback = exactFallback.search(
                userId = userId,
                documents = documents,
                profileFingerprint = profileFingerprint,
                queryVector = queryVector,
                topK = topK,
                workBudget = workBudget,
                deadlineAtMs = deadlineAtMs,
            )
            return Result(
                hits = fallback.hits.map { it.copy(vectorRank = null) },
                status = fallback.status,
                scannedCount = fallback.scannedCount,
                eligibleCount = fallback.eligibleCount,
                approximate = false,
                usedFallback = true,
                warnings = (warnings + fallback.warnings).toList(),
                timedOut = fallback.timedOut,
            )
        }

        // 授权过滤：manifest 可能含旧 docId；只保留当前 pin 且仍授权的行。
        // 候选先按向量分排序，再对 top 候选回表取元数据（元数据不取自 manifest）。
        val allowedIds = allowedChunkIds(userId, documents, profileFingerprint)
        val effectiveTopK = topK.coerceIn(1, MAX_TOP_K)
        val worstFirst = compareBy<AnnCandidate> { it.score }.thenByDescending { it.chunkId }
        val candidates = PriorityQueue<AnnCandidate>(effectiveTopK, worstFirst)
        var scanned = 0
        var eligible = 0
        stored.forEachEntry { entry ->
            scanned += 1
            if (entry.chunkId !in allowedIds) return@forEachEntry
            eligible += 1
            val score = VectorCodec.cosine(queryVector, entry.vector) ?: return@forEachEntry
            val candidate = AnnCandidate(entry.chunkId, score)
            if (candidates.size < effectiveTopK) {
                candidates += candidate
            } else if (worstFirst.compare(candidate, candidates.peek()) > 0) {
                candidates.poll()
                candidates += candidate
            }
        }
        val ranked = candidates.sortedWith(
            compareByDescending<AnnCandidate> { it.score }.thenBy { it.chunkId },
        )
        val metadata = loadPinnedHitMetadata(
            userId = userId,
            documents = documents,
            profileFingerprint = profileFingerprint,
            chunkIds = ranked.map { it.chunkId },
        )

        // delta：尚未合并 base 的新块做有界 exact，与 base 候选融合
        var deltaStatus = RetrievalBranchStatus.COMPLETE
        if (deltaChunkIds.isNotEmpty()) {
            val deltaHits = scanDelta(
                userId = userId,
                documents = documents,
                profileFingerprint = profileFingerprint,
                queryVector = queryVector,
                deltaChunkIds = deltaChunkIds,
                limit = minOf(deltaChunkIds.size, DELTA_SCAN_LIMIT),
            )
            if (deltaHits.size >= DELTA_SCAN_LIMIT) {
                deltaStatus = RetrievalBranchStatus.PARTIAL
                warnings += "ANN_DELTA_BUDGET_EXHAUSTED"
            }
            val merged = LinkedHashMap<Long, Pair<RetrievalHit, Double>>()
            ranked.forEach { candidate ->
                val row = metadata[candidate.chunkId] ?: return@forEach
                merged[candidate.chunkId] = row.toHit(candidate.score) to candidate.score
            }
            deltaHits.forEach { hit -> merged[hit.chunkId] = hit to (hit.vectorScore ?: 0.0) }
            val ordered = merged.values.sortedWith(
                compareByDescending<Pair<RetrievalHit, Double>> { it.second }
                    .thenBy { it.first.chunkId },
            ).take(effectiveTopK)
            return Result(
                hits = ordered.mapIndexed { index, pair -> pair.first.copy(vectorRank = index + 1) },
                status = deltaStatus,
                scannedCount = scanned + deltaHits.size,
                eligibleCount = eligible + deltaHits.size,
                approximate = true,
                usedFallback = false,
                warnings = warnings.toList(),
            )
        }

        return Result(
            hits = ranked.mapIndexedNotNull { index, candidate ->
                val row = metadata[candidate.chunkId] ?: return@mapIndexedNotNull null
                row.toHit(candidate.score).copy(vectorRank = index + 1)
            },
            status = RetrievalBranchStatus.COMPLETE,
            scannedCount = scanned,
            eligibleCount = eligible,
            approximate = true,
            usedFallback = false,
            warnings = warnings.toList(),
        )
    }

    /**
     * 当前 pin 下仍授权的行（manifest 是派生物，授权与元数据都以回表为准）。
     * 返回 chunkId -> 行，供候选排序后取元数据。
     */
    private suspend fun allowedChunkIds(
        userId: Long,
        documents: List<PinnedDocument>,
        profileFingerprint: String,
    ): Set<Long> {
        val allowed = HashSet<Long>()
        for (document in documents) {
            allowed += embeddingDao.listPinnedChunkIds(
                    userId = userId,
                    knowledgeDocumentId = document.knowledgeDocumentId,
                    pinnedGeneration = document.generation,
                    pinnedSourceRevision = document.sourceRevision,
                    pinnedAccessEpoch = document.accessEpoch,
                    profileFingerprint = profileFingerprint,
                )
        }
        return allowed
    }

    private suspend fun loadPinnedHitMetadata(
        userId: Long,
        documents: List<PinnedDocument>,
        profileFingerprint: String,
        chunkIds: List<Long>,
    ): Map<Long, com.example.myapplication.data.local.dao.PinnedHitMetadataRow> {
        if (chunkIds.isEmpty()) return emptyMap()
        val rows = HashMap<Long, com.example.myapplication.data.local.dao.PinnedHitMetadataRow>(chunkIds.size)
        for (document in documents) {
            embeddingDao.findPinnedHitMetadataByIds(
                userId = userId,
                knowledgeDocumentId = document.knowledgeDocumentId,
                pinnedGeneration = document.generation,
                pinnedSourceRevision = document.sourceRevision,
                pinnedAccessEpoch = document.accessEpoch,
                profileFingerprint = profileFingerprint,
                chunkIds = chunkIds,
            ).forEach { rows[it.chunkId] = it }
        }
        return rows
    }

    private fun com.example.myapplication.data.local.dao.PinnedHitMetadataRow.toHit(score: Double) = RetrievalHit(
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

    private suspend fun scanDelta(
        userId: Long,
        documents: List<PinnedDocument>,
        profileFingerprint: String,
        queryVector: FloatArray,
        deltaChunkIds: Set<Long>,
        limit: Int,
    ): List<RetrievalHit> {
        val hits = mutableListOf<RetrievalHit>()
        for (document in documents) {
            if (hits.size >= limit) break
            var cursor = 0L
            while (hits.size < limit) {
                val batch = embeddingDao.scanPinned(
                    userId = userId,
                    knowledgeDocumentId = document.knowledgeDocumentId,
                    pinnedGeneration = document.generation,
                    pinnedSourceRevision = document.sourceRevision,
                    pinnedAccessEpoch = document.accessEpoch,
                    profileFingerprint = profileFingerprint,
                    lastChunkId = cursor,
                    batchSize = DELTA_BATCH_SIZE,
                )
                if (batch.isEmpty()) break
                for (row in batch) {
                    cursor = row.chunkId
                    if (row.chunkId !in deltaChunkIds) continue
                    val decoded = VectorCodec.decode(
                        blob = row.vectorBlob,
                        dim = row.dim,
                        encoding = row.encoding,
                        quantizationParams = row.quantizationParams,
                    )
                    if (decoded !is VectorCodec.Decoded.Ok) continue
                    val score = VectorCodec.cosine(queryVector, decoded.vector) ?: continue
                    hits += RetrievalHit(
                        chunkId = row.chunkId,
                        knowledgeDocumentId = row.knowledgeDocumentId,
                        documentId = row.documentId,
                        sectionId = row.sectionId,
                        sectionOrder = row.sectionOrder,
                        ordinal = row.ordinal,
                        rawText = row.rawText,
                        locatorJson = row.locatorJson,
                        sourceRevision = row.sourceRevision,
                        generation = row.generation,
                        score = score,
                        vectorScore = score,
                    )
                    if (hits.size >= limit) break
                }
                if (batch.size < DELTA_BATCH_SIZE) break
            }
        }
        return hits.sortedWith(compareByDescending<RetrievalHit> { it.score }.thenBy { it.chunkId })
    }

    companion object {
        const val DELTA_BATCH_SIZE = 128
        const val DELTA_SCAN_LIMIT = 256
        const val MAX_TOP_K = 512
    }

    private data class AnnCandidate(val chunkId: Long, val score: Double)
}
