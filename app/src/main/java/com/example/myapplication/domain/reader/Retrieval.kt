package com.example.myapplication.domain.reader

/**
 * 检索请求与产出（接口明细 §7 / 设计 §7）。
 *
 * workBudget 是确定性的最大 eligible 扫描行数；deadline 是真实时间约束。
 * 二者不可混为一谈：固定 workBudget 下 cold/warm/evicted 必须得到相同排序，
 * deadline 截断只影响 partial 覆盖，不得改变已扫描部分的排序。
 */
data class RetrievalRequest(
    val ctx: AccessContext,
    val query: String,
    val scopeSnapshot: ScopeSnapshot,
    val profileFingerprint: String?,
    val limit: Int = 10,
    val workBudget: Int = DEFAULT_WORK_BUDGET,
    val deadlineAtMs: Long? = null,
    val continuation: RetrievalContinuation? = null,
    /** 当前选区问题：加 section 偏置但允许当前书补充。 */
    val preferDocumentId: String? = null,
    val preferSectionId: String? = null,
) {
    init {
        require(query.isNotBlank()) { "query must not be blank" }
        require(limit in 1..MAX_LIMIT) { "limit out of range" }
        require(workBudget > 0) { "workBudget must be positive" }
    }

    companion object {
        const val DEFAULT_WORK_BUDGET = 5_000
        const val MAX_LIMIT = 50
    }
}

/**
 * 固定 scope 快照：每文档 pin 住 (generation, sourceRevision, accessEpoch)。
 * 检索全程按该映射查询，不在每批重新读取 active_generation。
 */
data class ScopeSnapshot(
    val userId: Long,
    val selection: ReadingScope,
    val documents: List<PinnedDocument>,
) {
    val scopeHash: String by lazy {
        val material = buildString {
            append("user:").append(userId).append('\u0000')
            append("selection:").append(selection.name).append('\u0000')
            documents.sortedBy { it.documentId }.forEach { doc ->
                append(doc.documentId).append('|')
                append(doc.knowledgeDocumentId).append('|')
                append(doc.generation).append('|')
                append(doc.sourceRevision).append('|')
                append(doc.accessEpoch).append('\n')
            }
        }
        sha256Hex(material)
    }
}

data class PinnedDocument(
    val documentId: String,
    val knowledgeDocumentId: String,
    val generation: Long,
    val sourceRevision: String,
    val accessEpoch: Long,
)

/** 续检令牌：绑定 user/scopeHash/generation map/profile/queryHash/lastRowId。 */
data class RetrievalContinuation(
    val userId: Long,
    val scopeHash: String,
    val queryHash: String,
    val profileFingerprint: String?,
    val lastChunkId: Long,
    val scannedCount: Int,
    val expiresAtMs: Long,
    val checksum: String,
) {
    fun isValid(nowMs: Long): Boolean = nowMs <= expiresAtMs
}

/** 单条检索结果（融合前/后共用；evidence 由 EvidenceAssembler 生成）。 */
data class RetrievalHit(
    val chunkId: Long,
    val knowledgeDocumentId: String,
    val documentId: String,
    val sectionId: String,
    val sectionOrder: Int,
    val ordinal: Int,
    val rawText: String,
    val locatorJson: String,
    val sourceRevision: String,
    val generation: Long,
    val score: Double,
    val lexicalRank: Int? = null,
    val vectorRank: Int? = null,
    val vectorScore: Double? = null,
)

enum class RetrievalBranchStatus {
    /** 该路完整执行并覆盖全部 eligible 行。 */
    COMPLETE,
    /** 该路因 workBudget/deadline 截断，返回部分覆盖。 */
    PARTIAL,
    /** 该路不可用（未配置 profile / 未授权 / 无向量）。 */
    UNAVAILABLE,
}

data class RetrievalOutcome(
    val requestId: String,
    val results: List<RetrievalHit>,
    val pinnedGenerationMap: Map<String, PinnedDocument>,
    val lexicalStatus: RetrievalBranchStatus,
    val vectorStatus: RetrievalBranchStatus,
    val scannedCount: Int,
    val eligibleCount: Int,
    val partial: Boolean,
    val timedOut: Boolean,
    val degradedReason: String? = null,
    val approximate: Boolean = false,
    val continuation: RetrievalContinuation? = null,
    val stageTimings: Map<String, Long> = emptyMap(),
    val warnings: List<String> = emptyList(),
)

internal fun sha256Hex(text: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
