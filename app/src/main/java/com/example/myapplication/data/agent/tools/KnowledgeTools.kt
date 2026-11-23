package com.example.myapplication.data.agent.tools

import com.example.myapplication.data.knowledge.retrieval.HybridRetriever
import com.example.myapplication.data.local.dao.ReaderCatalogDao
import com.example.myapplication.domain.agent.AgentTool
import com.example.myapplication.domain.agent.ToolArgumentException
import com.example.myapplication.domain.agent.ToolArgs
import com.example.myapplication.domain.agent.ToolError
import com.example.myapplication.domain.agent.ToolErrorCode
import com.example.myapplication.domain.agent.ToolExecutionContext
import com.example.myapplication.domain.agent.ToolGroup
import com.example.myapplication.domain.agent.ToolResult
import com.example.myapplication.domain.agent.ToolSpec
import com.example.myapplication.domain.agent.boundedObject
import com.example.myapplication.domain.agent.integerSchema
import com.example.myapplication.domain.agent.stringArraySchema
import com.example.myapplication.domain.agent.stringSchema
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.ContentBlock
import com.example.myapplication.domain.reader.DocumentAccess
import com.example.myapplication.domain.reader.DocumentSnapshot
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.PinnedDocument
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.reader.RetrievalRequest
import com.example.myapplication.domain.reader.ScopeSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 只读内容工具（W4-02 / 详细设计 §3 工具权限表 / 接口明细 §6/§8/§15）。
 *
 * 共同约束：
 *  - 全部经 [DocumentAccess] / [HybridRetriever]；固定 snapshot/revision，不重读当前内容冒充旧版；
 *  - [frozenScope] 是运行开始时由应用侧解析的 scope，模型只能在其中**缩小**（search_knowledge
 *    的 documentIds），不能扩大；本作用域外的 documentId 直接 ACCESS_REVOKED；
 *  - 参数有界：query 1–4000、topK 1–20、read_source ≤64KiB 且不超 token 剩余；
 *  - 不接受模型传入 userId/路径（注册表未知字段 + 本类显式校验双重拒绝）；
 *  - 返回的 [com.example.myapplication.domain.reader.SourceEvidence] 均由 DocumentAccess
 *    固定 revision 原文核验；S 编号由回答级 EvidenceRegistry 统一分配，工具不自行编号。
 */
class KnowledgeTools(
    private val documentAccess: DocumentAccess,
    private val catalog: ReaderCatalogDao,
    private val retriever: HybridRetriever,
    /** 运行开始时固定的 scope（含每文档 generation/sourceRevision/accessEpoch pin）。 */
    private val frozenScope: ScopeSnapshot,
    /** 当前选中的 embedding profile；null 表示只走词法（不伪造向量）。 */
    private val profileFingerprint: String?,
    /** 检索确定性扫描上限（workBudget），测试可注入小值以验证 INDEX_PARTIAL。 */
    private val retrievalWorkBudget: Int = DEFAULT_RETRIEVAL_WORK_BUDGET,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(frozenScope.documents.isNotEmpty()) { "frozen scope must not be empty" }
        require(frozenScope.userId > 0) { "frozen scope userId must be positive" }
        require(retrievalWorkBudget > 0) { "retrievalWorkBudget must be positive" }
    }

    fun tools(): List<AgentTool> = listOf(
        ListSourcesTool(),
        ReadOutlineTool(),
        ReadSourceTool(),
        SearchKnowledgeTool(),
    )

    // ---- 共享 ----

    private fun scopeDocumentIds(): Set<String> = frozenScope.documents.map { it.documentId }.toSet()

    private fun requireInScope(documentId: String) {
        if (documentId !in scopeDocumentIds()) {
            throw ScopeViolationException("document is outside the pinned scope")
        }
    }

    private class ScopeViolationException(message: String) : Exception(message)

    private sealed interface SnapshotResult {
        data class Opened(val snapshot: DocumentSnapshot) : SnapshotResult
        data class Failed(val error: ToolError) : SnapshotResult
    }

    private suspend fun openSnapshot(
        context: AccessContext,
        documentId: String,
        requestedRevision: String?,
    ): SnapshotResult {
        val pin = frozenScope.documents.firstOrNull { it.documentId == documentId }
        if (context.userId != frozenScope.userId || pin == null) {
            return SnapshotResult.Failed(ToolError(ToolErrorCode.ACCESS_REVOKED, "document is outside the pinned scope"))
        }
        if (requestedRevision != null && requestedRevision != pin.sourceRevision) {
            return SnapshotResult.Failed(ToolError(ToolErrorCode.REVISION_MISSING, "revision differs from the pinned scope"))
        }
        return when (val opened = documentAccess.open(context, documentId, pin.sourceRevision)) {
            is OperationResult.Ok -> when {
                opened.value.accessEpoch != pin.accessEpoch -> SnapshotResult.Failed(
                    ToolError(ToolErrorCode.ACCESS_REVOKED, "pinned access epoch has changed"))
                opened.value.contentRevision != pin.sourceRevision -> SnapshotResult.Failed(
                    ToolError(ToolErrorCode.REVISION_MISSING, "pinned revision is not available"))
                else -> SnapshotResult.Opened(opened.value)
            }
            is OperationResult.Rejected -> SnapshotResult.Failed(
                when (opened.code) {
                    "ACCESS_REVOKED" -> ToolError(ToolErrorCode.ACCESS_REVOKED, "access revoked")
                    "REVISION_MISSING" -> ToolError(ToolErrorCode.REVISION_MISSING, "revision is not available")
                    else -> ToolError(ToolErrorCode.NOT_FOUND, "document not found")
                },
            )
            is OperationResult.Failed -> SnapshotResult.Failed(
                ToolError(ToolErrorCode.NOT_FOUND, "document open failed: ${opened.code}", retryable = opened.retryable),
            )
            is OperationResult.Conflict -> SnapshotResult.Failed(
                ToolError(ToolErrorCode.NOT_FOUND, "document open conflict", retryable = true),
            )
        }
    }

    private fun failure(callId: String, toolName: String, error: ToolError): ToolResult =
        ToolResult.failure(callId, toolName, error)

    private fun argumentFailure(toolName: String, error: ToolArgumentException): ToolResult =
        ToolResult.failure(
            callId = "",
            toolName = toolName,
            error = ToolError(
                ToolErrorCode.INVALID_ARGUMENT,
                error.message.orEmpty(),
                retryable = true,
                field = error.field,
            ),
        )

    // ---- list_sources ----

    private inner class ListSourcesTool : AgentTool {
        override val spec: ToolSpec = ToolSpec(
            name = "list_sources",
            group = ToolGroup.READ_ONLY_CONTENT,
            description = "列出当前用户在当前 scope 内可读的文档（只给 id/标题/格式/可用性，不给任意磁盘树）。",
            parameters = boundedObject(
                required = emptyList(),
                properties = mapOf(
                    "query" to stringSchema(1, MAX_LIST_QUERY_CHARS),
                    "cursor" to stringSchema(1, 256),
                    "limit" to integerSchema(1, MAX_LIST_LIMIT),
                ),
            ),
        )

        override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult {
            return try {
                val query = ToolArgs.optionalString(arguments, "query", MAX_LIST_QUERY_CHARS)?.trim()?.lowercase()
                val cursor = ToolArgs.optionalString(arguments, "cursor", 256)
                val limit = ToolArgs.optionalInt(arguments, "limit", 1, MAX_LIST_LIMIT) ?: DEFAULT_LIST_LIMIT
                val inScope = scopeDocumentIds()
                val candidates = catalog.findIndexableDocuments(context.userId)
                    .asSequence()
                    .filter { it.id in inScope }
                    .filter { query.isNullOrBlank() || it.title.lowercase().contains(query) }
                    .sortedBy { it.id }
                    .toList()
                val startIndex = if (cursor == null) {
                    0
                } else {
                    candidates.indexOfFirst { it.id > cursor }.takeIf { it >= 0 } ?: candidates.size
                }
                val page = candidates.drop(startIndex).take(limit)
                val nextCursor = if (startIndex + page.size < candidates.size) page.lastOrNull()?.id else null
                val payload = buildJsonObject {
                    put("scope", frozenScope.selection.name)
                    put("pinnedDocumentCount", frozenScope.documents.size)
                    put("count", page.size)
                    if (nextCursor != null) put("nextCursor", nextCursor)
                    putJsonArray("sources") {
                        page.forEach { document ->
                            addJsonObject {
                                put("documentId", document.id)
                                put("title", document.title)
                                put("format", document.format)
                                put("availability", if (document.importStatus == "READY") "READY" else document.importStatus)
                                put("revision", document.contentRevision)
                            }
                        }
                    }
                }
                ToolResult.success(callId = "", toolName = spec.name, payload = payload)
            } catch (denied: ScopeViolationException) {
                failure("", spec.name, ToolError(ToolErrorCode.ACCESS_REVOKED, denied.message.orEmpty()))
            } catch (error: ToolArgumentException) {
                argumentFailure(spec.name, error)
            }
        }
    }

    // ---- read_outline ----

    private inner class ReadOutlineTool : AgentTool {
        override val spec: ToolSpec = ToolSpec(
            name = "read_outline",
            group = ToolGroup.READ_ONLY_CONTENT,
            description = "按固定 revision 读取文档目录（章节层级与可读范围），不返回正文全文。",
            parameters = boundedObject(
                required = listOf("documentId"),
                properties = mapOf(
                    "documentId" to stringSchema(1, MAX_DOCUMENT_ID_CHARS),
                    "contentRevision" to stringSchema(1, MAX_REVISION_CHARS),
                ),
            ),
        )

        override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult {
            return try {
                val documentId = ToolArgs.requireString(arguments, "documentId", 1, MAX_DOCUMENT_ID_CHARS)
                val requestedRevision = ToolArgs.optionalString(arguments, "contentRevision", MAX_REVISION_CHARS)
                requireInScope(documentId)
                when (val opened = openSnapshot(context.accessContext(), documentId, requestedRevision)) {
                    is SnapshotResult.Failed -> failure("", spec.name, opened.error)
                    is SnapshotResult.Opened -> {
                        val snapshot = opened.snapshot
                        val sections = snapshot.sections.take(MAX_OUTLINE_SECTIONS)
                        val outline = catalog.findOutline(documentId, snapshot.contentRevision).take(MAX_OUTLINE_NODES)
                        val document = catalog.findDocumentIncludingDeleted(documentId)
                        val payload = buildJsonObject {
                            put("documentId", documentId)
                            put("title", document?.title.orEmpty())
                            put("contentRevision", snapshot.contentRevision)
                            put("accessEpoch", snapshot.accessEpoch)
                            put("sectionCount", sections.size)
                            putJsonArray("sections") {
                                sections.forEach { section ->
                                    addJsonObject {
                                        put("sectionId", section.id)
                                        put("title", section.title)
                                        put("orderIndex", section.orderIndex)
                                        put("charCount", section.charCount)
                                        section.pageStart?.let { put("pageStart", it) }
                                        section.pageEnd?.let { put("pageEnd", it) }
                                    }
                                }
                            }
                            putJsonArray("outline") {
                                outline.forEach { node ->
                                    addJsonObject {
                                        put("nodeKey", node.nodeKey)
                                        put("title", node.title)
                                        put("orderIndex", node.orderIndex)
                                        node.targetSectionId?.let { put("targetSectionId", it) }
                                    }
                                }
                            }
                        }
                        ToolResult.success(callId = "", toolName = spec.name, payload = payload)
                    }
                }
            } catch (denied: ScopeViolationException) {
                failure("", spec.name, ToolError(ToolErrorCode.ACCESS_REVOKED, denied.message.orEmpty()))
            } catch (error: ToolArgumentException) {
                argumentFailure(spec.name, error)
            }
        }
    }

    // ---- read_source ----

    private inner class ReadSourceTool : AgentTool {
        override val spec: ToolSpec = ToolSpec(
            name = "read_source",
            group = ToolGroup.READ_ONLY_CONTENT,
            description = "按固定 revision 读取指定 section 的原文（≤64KiB 且不超 token 剩余），" +
                "返回可核验证据。不接受模型自填 user/path。",
            parameters = boundedObject(
                required = listOf("documentId", "sectionId"),
                properties = mapOf(
                    "documentId" to stringSchema(1, MAX_DOCUMENT_ID_CHARS),
                    "sectionId" to stringSchema(1, MAX_DOCUMENT_ID_CHARS),
                    "contentRevision" to stringSchema(1, MAX_REVISION_CHARS),
                    "startOffset" to integerSchema(0, Int.MAX_VALUE),
                    "length" to integerSchema(1, MAX_SOURCE_BYTES),
                    "maxBytes" to integerSchema(MIN_SOURCE_BYTES, MAX_SOURCE_BYTES),
                ),
            ),
            maxArgumentBytes = 4 * 1024,
        )

        override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult {
            return try {
                val documentId = ToolArgs.requireString(arguments, "documentId", 1, MAX_DOCUMENT_ID_CHARS)
                val sectionId = ToolArgs.requireString(arguments, "sectionId", 1, MAX_DOCUMENT_ID_CHARS)
                val requestedRevision = ToolArgs.optionalString(arguments, "contentRevision", MAX_REVISION_CHARS)
                val requestedStart = ToolArgs.optionalInt(arguments, "startOffset", 0, Int.MAX_VALUE)
                val requestedLength = ToolArgs.optionalInt(arguments, "length", 1, MAX_SOURCE_BYTES)
                val maxBytes = ToolArgs.optionalInt(arguments, "maxBytes", MIN_SOURCE_BYTES, MAX_SOURCE_BYTES)
                    ?: DEFAULT_SOURCE_BYTES
                requireInScope(documentId)
                if (context.remainingTokens <= 0) {
                    throw ToolArgumentException("remainingTokens", "no token budget remaining for read_source")
                }
                when (val opened = openSnapshot(context.accessContext(), documentId, requestedRevision)) {
                    is SnapshotResult.Failed -> failure("", spec.name, opened.error)
                    is SnapshotResult.Opened -> read(context, opened.snapshot, sectionId, requestedStart, requestedLength, maxBytes)
                }
            } catch (denied: ScopeViolationException) {
                failure("", spec.name, ToolError(ToolErrorCode.ACCESS_REVOKED, denied.message.orEmpty()))
            } catch (error: ToolArgumentException) {
                argumentFailure(spec.name, error)
            }
        }

        private suspend fun read(
            context: ToolExecutionContext,
            snapshot: DocumentSnapshot,
            sectionId: String,
            requestedStart: Int?,
            requestedLength: Int?,
            maxBytes: Int,
        ): ToolResult {
            val accessContext = context.accessContext()
            val blocks = collectBlocks(accessContext, snapshot.snapshotId, sectionId, maxBytes)
            if (blocks.isEmpty()) {
                return failure(
                    "",
                    spec.name,
                    ToolError(ToolErrorCode.NOT_FOUND, "section has no readable blocks"),
                )
            }
            val sectionStart = blocks.first().canonicalStart
            val sectionEnd = blocks.last().canonicalEnd
            val start = (requestedStart ?: sectionStart).coerceAtLeast(sectionStart)
            if (start >= sectionEnd) {
                throw ToolArgumentException("startOffset", "startOffset is outside the readable section")
            }
            val charCap = minOf(context.remainingTokens, MAX_EVIDENCE_CHARS)
            val requestedEnd = start + (requestedLength ?: charCap)
            var end = minOf(requestedEnd, sectionEnd, start + charCap).coerceAtLeast(start + 1)
            var quote = reconstruct(blocks, start, end)
            if (quote.isBlank()) {
                return failure("", spec.name, ToolError(ToolErrorCode.NOT_FOUND, "requested range is empty"))
            }
            val fitted = fitUtf8(quote, maxBytes, context.remainingTokens)
            if (fitted < quote.length) {
                end = start + fitted
                quote = reconstruct(blocks, start, end)
            }
            if (quote.isBlank()) {
                return failure("", spec.name, ToolError(ToolErrorCode.NOT_FOUND, "requested range is empty"))
            }
            val firstBlock = blocks.first { it.canonicalEnd > start }
            val locator = ReaderLocator.Reflow(
                documentId = snapshot.documentId,
                sectionId = sectionId,
                contentRevision = snapshot.contentRevision,
                progression = null,
                nativeLocator = firstBlock.nativeLocator,
                exactQuote = quote,
                canonicalStart = start,
                canonicalEnd = end,
                blockId = firstBlock.blockId,
            )
            val maxTokens = quote.length.coerceIn(16, MAX_EVIDENCE_CHARS)
            val evidence = when (val result = documentAccess.readRange(accessContext, snapshot.snapshotId, locator, maxTokens)) {
                is OperationResult.Ok -> result.value
                is OperationResult.Rejected -> return failure(
                    "",
                    spec.name,
                    ToolError(
                        when (result.code) {
                            "ACCESS_REVOKED" -> ToolErrorCode.ACCESS_REVOKED
                            "REVISION_MISSING" -> ToolErrorCode.REVISION_MISSING
                            "NOT_FOUND" -> ToolErrorCode.NOT_FOUND
                            else -> ToolErrorCode.EVIDENCE_UNVERIFIED
                        },
                        "source read rejected: ${result.code}",
                    ),
                )

                is OperationResult.Failed -> return failure(
                    "",
                    spec.name,
                    ToolError(ToolErrorCode.NOT_FOUND, "source read failed: ${result.code}", retryable = result.retryable),
                )

                is OperationResult.Conflict -> return failure(
                    "",
                    spec.name,
                    ToolError(ToolErrorCode.NOT_FOUND, "source read conflict", retryable = true),
                )
            }
            if (evidence.quote != quote) {
                return failure(
                    "",
                    spec.name,
                    ToolError(ToolErrorCode.EVIDENCE_UNVERIFIED, "quote failed immutable-revision verification"),
                )
            }
            val truncated = end < sectionEnd
            val document = catalog.findDocumentIncludingDeleted(snapshot.documentId)
            val payload = buildJsonObject {
                put("documentId", snapshot.documentId)
                put("title", document?.title.orEmpty())
                put("sectionId", sectionId)
                put("contentRevision", snapshot.contentRevision)
                put("startOffset", start)
                put("endOffset", end)
                put("byteCount", quote.toByteArray(Charsets.UTF_8).size)
                put("truncated", truncated)
                if (truncated) put("nextStartOffset", end)
                put("quote", quote)
            }
            return ToolResult.success(callId = "", toolName = spec.name, payload = payload, evidence = listOf(evidence))
        }

        private suspend fun collectBlocks(
            context: AccessContext,
            snapshotId: String,
            sectionId: String,
            maxBytes: Int,
        ): List<ContentBlock> {
            val blocks = mutableListOf<ContentBlock>()
            var cursor: String? = null
            var collectedBytes = 0
            do {
                val pageBytes = maxBytes.coerceIn(MIN_SOURCE_BYTES, MAX_SOURCE_BYTES)
                val page = when (val result = documentAccess.readBlocks(context, snapshotId, sectionId, cursor, pageBytes)) {
                    is OperationResult.Ok -> result.value
                    is OperationResult.Rejected -> return emptyList()
                    is OperationResult.Failed -> return emptyList()
                    is OperationResult.Conflict -> return emptyList()
                }
                blocks += page.items
                page.items.forEach { collectedBytes += it.text.toByteArray(Charsets.UTF_8).size }
                cursor = page.nextCursor
            } while (cursor != null && collectedBytes < maxBytes)
            return blocks
        }

        private fun reconstruct(blocks: List<ContentBlock>, start: Int, end: Int): String {
            val builder = StringBuilder()
            for (block in blocks) {
                if (block.canonicalEnd <= start) continue
                if (block.canonicalStart >= end) break
                val localStart = maxOf(start, block.canonicalStart) - block.canonicalStart
                val localEnd = minOf(end, block.canonicalEnd) - block.canonicalStart
                if (localEnd <= localStart) continue
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(block.text, localStart, localEnd)
            }
            return builder.toString()
        }

        /** 截断到 UTF-8 字节上限（保留完整代理对），返回保留的 UTF-16 char 数。 */
        private fun fitUtf8(text: String, maxBytes: Int, remainingTokens: Int): Int {
            var length = minOf(text.length, remainingTokens.coerceAtLeast(1))
            while (length > 0) {
                if (length < text.length && Character.isHighSurrogate(text[length - 1])) {
                    length -= 1
                    continue
                }
                if (text.substring(0, length).toByteArray(Charsets.UTF_8).size <= maxBytes) return length
                length -= 1
            }
            return 0
        }
    }

    // ---- search_knowledge ----

    private inner class SearchKnowledgeTool : AgentTool {
        override val spec: ToolSpec = ToolSpec(
            name = "search_knowledge",
            group = ToolGroup.READ_ONLY_CONTENT,
            description = "在当前固定 scope 内做词法+向量混合检索（只能缩小 scope），返回候选与可核验证据；" +
                "覆盖不完整时显式标记 INDEX_PARTIAL。",
            parameters = boundedObject(
                required = listOf("query"),
                properties = mapOf(
                    "query" to stringSchema(1, MAX_SEARCH_QUERY_CHARS),
                    "topK" to integerSchema(1, MAX_SEARCH_TOP_K),
                    "documentIds" to stringArraySchema(MAX_SCOPE_DOCUMENTS, MAX_DOCUMENT_ID_CHARS),
                ),
            ),
            maxArgumentBytes = 4 * 1024,
        )

        override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult {
            return try {
                val query = ToolArgs.requireString(arguments, "query", 1, MAX_SEARCH_QUERY_CHARS)
                val topK = ToolArgs.optionalInt(arguments, "topK", 1, MAX_SEARCH_TOP_K) ?: DEFAULT_SEARCH_TOP_K
                val requestedDocuments = ToolArgs.optionalStringList(
                    arguments,
                    "documentIds",
                    MAX_SCOPE_DOCUMENTS,
                    MAX_DOCUMENT_ID_CHARS,
                )
                val pinned = narrowScope(requestedDocuments)
                val scope = ScopeSnapshot(
                    userId = frozenScope.userId,
                    selection = frozenScope.selection,
                    documents = pinned,
                )
                val request = RetrievalRequest(
                    ctx = context.accessContext(),
                    query = query,
                    scopeSnapshot = scope,
                    profileFingerprint = profileFingerprint,
                    limit = topK,
                    workBudget = retrievalWorkBudget,
                    deadlineAtMs = context.deadlineAtMs,
                )
                val result = retriever.retrieve(request)
                val warnings = buildList {
                    if (result.outcome.partial) add(ToolErrorCode.INDEX_PARTIAL.name)
                    if (result.outcome.timedOut) add("DEADLINE_EXCEEDED")
                    result.outcome.degradedReason?.let { add("VECTOR_DEGRADED:$it") }
                    if (result.droppedCandidates.isNotEmpty()) {
                        add("EVIDENCE_DROPPED_${result.droppedCandidates.size}")
                    }
                }
                val payload = buildJsonObject {
                    put("query", query)
                    put("partial", result.outcome.partial)
                    put("timedOut", result.outcome.timedOut)
                    put("degradedReason", result.outcome.degradedReason)
                    put("lexicalStatus", result.outcome.lexicalStatus.name)
                    put("vectorStatus", result.outcome.vectorStatus.name)
                    put("scannedCount", result.outcome.scannedCount)
                    put("eligibleCount", result.outcome.eligibleCount)
                    put("droppedCandidates", result.droppedCandidates.size)
                    putJsonArray("results") {
                        result.evidence.forEachIndexed { index, evidence ->
                            addJsonObject {
                                put("evidenceIndex", index)
                                put("documentId", evidence.documentId)
                                put("sectionId", evidence.sectionId)
                                put("contentRevision", evidence.contentRevision)
                                put("quote", evidence.quote)
                                put("locatorJson", ReaderLocatorCodec.encode(evidence.locator))
                            }
                        }
                    }
                }
                ToolResult.success(callId = "", toolName = spec.name, payload = payload, evidence = result.evidence, warnings = warnings)
            } catch (denied: ScopeViolationException) {
                failure("", spec.name, ToolError(ToolErrorCode.ACCESS_REVOKED, denied.message.orEmpty()))
            } catch (error: ToolArgumentException) {
                argumentFailure(spec.name, error)
            }
        }

        private fun narrowScope(requested: List<String>?): List<PinnedDocument> {
            if (requested == null) return frozenScope.documents
            if (requested.isEmpty()) {
                throw ToolArgumentException("documentIds", "documentIds must not be empty")
            }
            val pinnedById = frozenScope.documents.associateBy { it.documentId }
            return requested.distinct().map { id ->
                pinnedById[id]
                    ?: throw ToolArgumentException("documentIds", "scope can only be narrowed: $id is not pinned")
            }
        }
    }

    companion object {
        const val DEFAULT_RETRIEVAL_WORK_BUDGET = 5_000
        const val MAX_LIST_QUERY_CHARS = 200
        const val MAX_LIST_LIMIT = 50
        const val DEFAULT_LIST_LIMIT = 20
        const val MAX_SEARCH_QUERY_CHARS = 4_000
        const val MAX_SEARCH_TOP_K = 20
        const val DEFAULT_SEARCH_TOP_K = 8
        const val MAX_SCOPE_DOCUMENTS = 64
        const val MAX_DOCUMENT_ID_CHARS = 256
        const val MAX_REVISION_CHARS = 128
        const val MAX_SOURCE_BYTES = 64 * 1024
        const val MIN_SOURCE_BYTES = 1_024
        const val DEFAULT_SOURCE_BYTES = 16 * 1024
        const val MAX_EVIDENCE_CHARS = 8_192
        const val MAX_OUTLINE_SECTIONS = 64
        const val MAX_OUTLINE_NODES = 400
    }
}

