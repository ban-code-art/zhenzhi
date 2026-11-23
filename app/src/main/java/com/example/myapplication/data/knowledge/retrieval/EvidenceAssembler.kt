package com.example.myapplication.data.knowledge.retrieval

import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.DocumentAccess
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.PinnedDocument
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.reader.RetrievalHit
import com.example.myapplication.domain.reader.SourceEvidence
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/**
 * 证据装配（设计 §7.4 / FX-14）。
 *
 * 铁律：证据必须来自 DocumentAccess 读取的固定 revision 原文，
 * 不能靠重读 chunk 表自证。流程：
 *  1. 解析候选 locator；
 *  2. DocumentAccess.readRange 用 snapshot 读取原文并逐字核验 quote；
 *  3. quote hash 与 locator/scope/accessEpoch 全部匹配才产出可引用证据；
 *  4. 任何失败丢弃该候选（或由上层补检索），绝不生成可点击假出处；
 *  5. 父段扩展按同一 snapshot 读原 blocks，不猜别的 revision。
 */
class EvidenceAssembler(
    private val documentAccess: DocumentAccess,
) {
    data class Result(
        val evidence: List<SourceEvidence>,
        val dropped: List<DroppedCandidate>,
        /** 因证据预算截断而未被逐字比对整段的行数（按前缀核验通过，属设计行为，不是丢弃）。 */
        val truncatedQuoteCount: Int = 0,
        /** Keeps the verified source associated with the exact candidate, without matching raw text. */
        val evidenceByChunkId: Map<Long, SourceEvidence> = emptyMap(),
    )

    data class DroppedCandidate(val chunkId: Long, val reason: String)

    /**
     * 装配证据。[snapshotIdByDocument] 由调用方在检索开始时 open 得到，
     * 保证读取的是同一 revision。
     */
    suspend fun assemble(
        ctx: AccessContext,
        hits: List<RetrievalHit>,
        snapshotIdByDocument: Map<String, String>,
        maxTokensPerEvidence: Int = 512,
        pinnedDocuments: Map<String, PinnedDocument> = emptyMap(),
    ): Result {
        val evidenceByChunkId = linkedMapOf<Long, SourceEvidence>()
        val accessEpochByChunkId = linkedMapOf<Long, Long>()
        val truncatedChunks = hashSetOf<Long>()
        val dropped = mutableListOf<DroppedCandidate>()
        val pins = pinnedDocuments.toMap()
        val snapshots = snapshotIdByDocument.toMap()
        for (hit in hits.toList()) {
            val pin = pins[hit.documentId]
            if (pins.isNotEmpty() && (pin == null || !matchesPin(hit, pin))) {
                dropped += DroppedCandidate(hit.chunkId, "SCOPE_PIN_INVALID")
                continue
            }
            // Do not cache this decision: a previous read or provider stage may revoke access.
            val opened = currentSnapshot(ctx, hit.documentId)
            if (opened == null) {
                dropped += DroppedCandidate(hit.chunkId, "SOURCE_UNAVAILABLE")
                continue
            }
            if (opened.documentId != hit.documentId || opened.contentRevision != hit.sourceRevision) {
                dropped += DroppedCandidate(hit.chunkId, "REVISION_MISSING")
                continue
            }
            if (pin != null && opened.accessEpoch != pin.accessEpoch) {
                dropped += DroppedCandidate(hit.chunkId, "SCOPE_PIN_INVALID")
                continue
            }
            val snapshotId = snapshots[hit.documentId]
            if (snapshotId == null) {
                dropped += DroppedCandidate(hit.chunkId, "SNAPSHOT_MISSING")
                continue
            }
            val locator = runCatching { ReaderLocatorCodec.decode(hit.locatorJson) }.getOrNull()
            if (locator == null) {
                dropped += DroppedCandidate(hit.chunkId, "LOCATOR_INVALID")
                continue
            }
            if (locator.documentId != hit.documentId || locator.contentRevision != hit.sourceRevision) {
                dropped += DroppedCandidate(hit.chunkId, "LOCATOR_MISMATCH")
                continue
            }
            val read = try {
                documentAccess.readRange(ctx, snapshotId, locator, maxTokensPerEvidence)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                dropped += DroppedCandidate(hit.chunkId, "SOURCE_READ_FAILED")
                continue
            }
            when (val result = read) {
                is OperationResult.Ok -> {
                    val source = result.value
                    var truncatedByBudget = false
                    if (source.documentId != hit.documentId || source.contentRevision != hit.sourceRevision ||
                        source.sectionId != hit.sectionId || source.locator != locator || source.quote.isBlank()) {
                        dropped += DroppedCandidate(hit.chunkId, "SOURCE_IDENTITY_MISMATCH")
                        continue
                    }
                    // 逐字核验：读回的 quote 必须与 locator 的 exactQuote 一致。
                    // 唯一例外是「证据预算截断」：readRange 会把 quote 砍到 maxTokens，
                    // 此时读回值是 locator quote 的前缀，仍然逐字来自原文（hash 也基于该前缀），
                    // 属于设计内的有界证据，不能当成 QUOTE_MISMATCH 丢弃
                    // （否则所有 >512 字的 chunk 都会被静默丢弃：P1-5）。
                    val expected = expectedQuote(locator)
                    if (expected != null && source.quote != expected) {
                        truncatedByBudget = isBudgetTruncatedPrefix(expected, source.quote, maxTokensPerEvidence)
                        if (!truncatedByBudget) {
                            dropped += DroppedCandidate(hit.chunkId, "QUOTE_MISMATCH")
                            continue
                        }
                    }
                    if (!verifyQuoteHash(source)) {
                        dropped += DroppedCandidate(hit.chunkId, "QUOTE_HASH_MISMATCH")
                        continue
                    }
                    val afterRead = currentSnapshot(ctx, hit.documentId)
                    if (afterRead == null || afterRead.documentId != opened.documentId ||
                        afterRead.contentRevision != opened.contentRevision || afterRead.accessEpoch != opened.accessEpoch ||
                        (pin != null && afterRead.accessEpoch != pin.accessEpoch)) {
                        dropped += DroppedCandidate(hit.chunkId, "SCOPE_PIN_INVALID")
                        continue
                    }
                    evidenceByChunkId[hit.chunkId] = source
                    accessEpochByChunkId[hit.chunkId] = afterRead.accessEpoch
                    if (truncatedByBudget) truncatedChunks += hit.chunkId
                }

                is OperationResult.Rejected -> dropped += DroppedCandidate(hit.chunkId, result.code)
                is OperationResult.Failed -> dropped += DroppedCandidate(hit.chunkId, result.code)
                is OperationResult.Conflict -> dropped += DroppedCandidate(hit.chunkId, "CONFLICT")
            }
        }
        // A later candidate's read can revoke a source accepted earlier in this same assembly.
        for ((chunkId, source) in evidenceByChunkId.toMap()) {
            val current = currentSnapshot(ctx, source.documentId)
            val pin = pins[source.documentId]
            if (current == null || current.documentId != source.documentId || current.contentRevision != source.contentRevision ||
                current.accessEpoch != accessEpochByChunkId[chunkId] || (pin != null && current.accessEpoch != pin.accessEpoch)) {
                evidenceByChunkId.remove(chunkId)
                dropped += DroppedCandidate(chunkId, "SCOPE_PIN_INVALID")
            }
        }
        return Result(evidenceByChunkId.values.toList(), dropped,
            evidenceByChunkId.keys.count { it in truncatedChunks }, evidenceByChunkId.toMap())
    }

    private suspend fun currentSnapshot(ctx: AccessContext, documentId: String) = try {
        when (val result = documentAccess.open(ctx, documentId, null)) {
            is OperationResult.Ok -> result.value
            else -> null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun matchesPin(hit: RetrievalHit, pin: PinnedDocument): Boolean =
        hit.documentId == pin.documentId && hit.knowledgeDocumentId == pin.knowledgeDocumentId &&
            hit.sourceRevision == pin.sourceRevision && hit.generation == pin.generation

    /** 父段扩展：按同一 snapshot 读原 blocks，取候选块附近的上下文。 */
    suspend fun expandParent(
        ctx: AccessContext,
        snapshotId: String,
        sectionId: String,
        aroundBlockId: String,
        maxTokens: Int = 1024,
    ): String? {
        var cursor: String? = null
        val blocks = mutableListOf<com.example.myapplication.domain.reader.ContentBlock>()
        do {
            val page = when (val result = documentAccess.readBlocks(ctx, snapshotId, sectionId, cursor)) {
                is OperationResult.Ok -> result.value
                else -> return null
            }
            blocks += page.items
            cursor = page.nextCursor
        } while (cursor != null && blocks.size < MAX_PARENT_BLOCKS)

        val index = blocks.indexOfFirst { it.blockId == aroundBlockId }
        if (index < 0) return null
        // 向前后扩展至 token 预算（CJK 1 token/字近似）
        var start = index
        var end = index
        var tokens = blocks[index].text.length
        while (tokens < maxTokens) {
            val canGrowBefore = start > 0
            val canGrowAfter = end < blocks.size - 1
            if (!canGrowBefore && !canGrowAfter) break
            val beforeSize = if (canGrowBefore) blocks[start - 1].text.length else Int.MAX_VALUE
            val afterSize = if (canGrowAfter) blocks[end + 1].text.length else Int.MAX_VALUE
            if (beforeSize <= afterSize && canGrowBefore) {
                start -= 1
                tokens += beforeSize
            } else if (canGrowAfter) {
                end += 1
                tokens += afterSize
            } else {
                break
            }
        }
        return blocks.subList(start, end + 1).joinToString("\n") { it.text }
    }

    private fun expectedQuote(locator: ReaderLocator): String? = when (locator) {
        is ReaderLocator.Reflow -> locator.exactQuote
        is ReaderLocator.Fixed -> locator.textQuote
        is ReaderLocator.Note -> null
    }

    /**
     * 读回引文是否只是「被证据预算截断」，而不是定位/内容对不上。
     *
     * 条件必须同时成立，避免把真不一致当成截断放行：
     *  1. 期望引文本身超过预算（否则不该被截断）；
     *  2. 读回值不超过预算（正是 readRange 的钳制值）；
     *  3. 读回值确实是期望引文的前缀（逐字来自原文，不是凭空拼接）。
     *
     * 预算钳制必须与 [DocumentAccess.readRange] 的 `coerceIn(16, 8192)` 保持一致。
     */
    private fun isBudgetTruncatedPrefix(
        expected: String,
        actual: String,
        maxTokensPerEvidence: Int,
    ): Boolean {
        val budget = maxTokensPerEvidence.coerceIn(16, 8192)
        return expected.length > budget &&
            actual.isNotEmpty() &&
            actual.length <= budget &&
            expected.startsWith(actual)
    }

    private fun verifyQuoteHash(evidence: SourceEvidence): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(evidence.quote.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return digest == evidence.quoteSha256
    }

    companion object {
        const val MAX_PARENT_BLOCKS = 512
    }
}
