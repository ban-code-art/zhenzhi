package com.example.myapplication.data.knowledge.retrieval

import com.example.myapplication.domain.reader.RetrievalHit

/**
 * RRF 融合（设计 §7.4 / §12）。
 *
 * RRF(c) = Σ_branch 1/(k + rank_branch(c))，rank 从 1 开始，k=60。
 * 只对已有候选求和：无向量分支时不补伪造 0 分，词法 rank 仍然有效。
 * tie 按 documentId、sectionOrder、canonicalStart（locator 解析）、chunkId 稳定排序。
 */
object RrfFusion {
    const val K = 60

    /** 当前书/当前 section 偏置：仅对已有候选放大，不制造新候选。 */
    const val DOCUMENT_BOOST = 1.10
    const val SECTION_BOOST = 1.05

    data class Branch(
        val hits: List<RetrievalHit>,
        val weight: Double = 1.0,
    )

    fun fuse(
        lexical: List<RetrievalHit>,
        vector: List<RetrievalHit>,
        topK: Int,
        preferDocumentId: String? = null,
        preferSectionId: String? = null,
    ): List<RetrievalHit> {
        val byChunk = LinkedHashMap<Long, MergedHit>()

        lexical.forEachIndexed { index, hit ->
            val merged = byChunk.getOrPut(hit.chunkId) { MergedHit(hit) }
            merged.lexicalRank = index + 1
            merged.addScore(1.0 / (K + index + 1))
        }
        vector.forEachIndexed { index, hit ->
            val merged = byChunk.getOrPut(hit.chunkId) { MergedHit(hit) }
            merged.vectorRank = index + 1
            merged.vectorScore = hit.vectorScore ?: hit.score
            merged.addScore(1.0 / (K + index + 1))
        }

        return byChunk.values
            .map { merged ->
                var score = merged.rrfScore
                if (preferDocumentId != null && merged.hit.documentId == preferDocumentId) {
                    score *= DOCUMENT_BOOST
                }
                if (preferSectionId != null && merged.hit.sectionId == preferSectionId) {
                    score *= SECTION_BOOST
                }
                merged.hit.copy(
                    score = score,
                    lexicalRank = merged.lexicalRank,
                    vectorRank = merged.vectorRank,
                    vectorScore = merged.vectorScore,
                )
            }
            .sortedWith(
                compareByDescending<RetrievalHit> { it.score }
                    .thenBy { it.documentId }
                    .thenBy { it.sectionOrder }
                    .thenBy { canonicalStartOf(it.locatorJson) }
                    .thenBy { it.chunkId },
            )
            .take(topK.coerceAtLeast(1))
    }

    /**
     * MMR 去近重复（设计 §12，lambda=0.75）。
     * 重复度优先 quote 区间重叠（同 section 的 canonical 区间），次选文本相同。
     * 相同文本多出处保留来源别名：返回的重复项折叠为一条，别名列表由调用方展示。
     */
    fun mmr(
        candidates: List<RetrievalHit>,
        topK: Int,
        lambda: Double = 0.75,
    ): List<RetrievalHit> {
        if (candidates.isEmpty()) return emptyList()
        val remaining = candidates.toMutableList()
        val selected = mutableListOf<RetrievalHit>()
        val maxScore = candidates.maxOf { it.score }.takeIf { it > 0 } ?: 1.0
        while (selected.size < topK && remaining.isNotEmpty()) {
            var bestIndex = 0
            var bestValue = Double.NEGATIVE_INFINITY
            remaining.forEachIndexed { index, candidate ->
                val relevance = candidate.score / maxScore
                val redundancy = selected.maxOfOrNull { redundancyOf(it, candidate) } ?: 0.0
                val value = lambda * relevance - (1 - lambda) * redundancy
                if (value > bestValue) {
                    bestValue = value
                    bestIndex = index
                }
            }
            selected += remaining.removeAt(bestIndex)
        }
        return selected
    }

    /** 重复度：同 section 的 canonical 区间 IoU；不同 section 但文本相同记 1.0。 */
    private fun redundancyOf(a: RetrievalHit, b: RetrievalHit): Double {
        if (a.rawText.isNotBlank() && a.rawText == b.rawText) return 1.0
        if (a.sectionId != b.sectionId) return 0.0
        val rangeA = rangeOf(a.locatorJson) ?: return 0.0
        val rangeB = rangeOf(b.locatorJson) ?: return 0.0
        val overlapStart = maxOf(rangeA.first, rangeB.first)
        val overlapEnd = minOf(rangeA.second, rangeB.second)
        if (overlapEnd <= overlapStart) return 0.0
        val unionStart = minOf(rangeA.first, rangeB.first)
        val unionEnd = maxOf(rangeA.second, rangeB.second)
        val union = (unionEnd - unionStart).coerceAtLeast(1)
        return (overlapEnd - overlapStart).toDouble() / union.toDouble()
    }

    private fun rangeOf(locatorJson: String): Pair<Int, Int>? = runCatching {
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(locatorJson) as kotlinx.serialization.json.JsonObject
        val start = (parsed["canonicalStart"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
        val end = (parsed["canonicalEnd"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
        if (start == null || end == null) null else start to end
    }.getOrNull()

    private fun canonicalStartOf(locatorJson: String): Int = rangeOf(locatorJson)?.first ?: Int.MAX_VALUE

    private class MergedHit(val hit: RetrievalHit) {
        var rrfScore: Double = 0.0
        var lexicalRank: Int? = null
        var vectorRank: Int? = null
        var vectorScore: Double? = null

        fun addScore(value: Double) {
            rrfScore += value
        }
    }
}
