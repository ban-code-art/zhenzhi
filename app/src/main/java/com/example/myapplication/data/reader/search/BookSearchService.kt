package com.example.myapplication.data.reader.search

import com.example.myapplication.data.knowledge.lexical.ChineseTokenizer
import com.example.myapplication.data.knowledge.lexical.LexicalIndex
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.KnowledgeDocumentEntity
import com.example.myapplication.data.local.entity.ReaderDocumentEntity
import com.example.myapplication.data.local.entity.ReaderSectionEntity
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.ContentBlock
import com.example.myapplication.domain.reader.DocumentAccess
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderAccessDecision
import com.example.myapplication.domain.reader.ReaderAccessPolicy
import com.example.myapplication.domain.reader.ReaderDocumentAccessRecord
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.reader.SourceReadBudget
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 书内搜索（第五期 W5-04 / P5-03，设计 §4）。
 *
 * search 保留既有词法/降级兼容；searchExact 独立扫描原文并保留真实续扫位置。
 * 分工（以下描述 legacy search）：
 *  - **按书过滤**：只查一个 documentId，走 [LexicalIndex.search] 的 `documentIds` 过滤，
 *    SQL 层的授权/代次/源 revision/删除条件全部沿用既有实现，不新增查询口径；
 *  - **词/短语**：完全复用 [ChineseTokenizer]。含空白或引号的输入按“短语”处理
 *    （去掉引号与分隔空白后交给同一个 tokenizer，得到一个相邻 bigram phrase），
 *    单字查询就是 tokenizer 的既有单 token phrase，不另造分词/通配规则；
 *  - **未索引节的有界降级**：书没有 knowledge_documents 行、或 lexical_state 不是完成态时，
 *    逐节读正文做有界扫描（总字节预算 + 单节上限 + 节数上限 + 逐块 ensureActive），
 *    结果标 `fromFallback=true` 且 `coverage=PARTIAL`，绝不假冒“全书已搜完”；
 *  - **旧查询丢弃**：请求带 `querySeq`，扫描过程中一旦发现更新的 seq 立即返回
 *    [BookSearchOutcome.Superseded]，调用方据此不覆盖新查询；
 *  - **跳转**：索引命中直接反序列化 chunk 的 `locator_json`（[ReaderLocatorCodec]）；
 *    降级命中用 [ReaderLocator.Reflow] + block/canonical 偏移构造，
 *    **绝不**用 `String.indexOf` 得到的偏移去改 DOM（偏移只在正文片段里用于显示片段与定位）。
 *
 * 本类不含任何 UI/Compose 依赖，除 Room/Asset 访问外全部逻辑可纯 JVM 覆盖
 * （见 BookSearchQueryTest）。
 */
class BookSearchService(
    private val database: AppDatabase,
    private val lexicalIndex: LexicalIndex,
    private val documentAccess: DocumentAccess,
    private val fallbackByteBudget: Long = DEFAULT_FALLBACK_BYTE_BUDGET,
    private val sectionByteLimit: Long = DEFAULT_SECTION_BYTE_LIMIT,
    private val fallbackSectionLimit: Int = DEFAULT_FALLBACK_SECTION_LIMIT,
    private val sourceReadByteBudget: Long = DEFAULT_FALLBACK_BYTE_BUDGET,
) : BookSearcher {

    private val catalog get() = database.readerCatalogDao()
    private val knowledgeDocuments get() = database.knowledgeDocumentDao()

    /** Exact source scan. FTS candidate caps and legacy fallback caps never determine its
     * count. Budgets bound THIS fetch's UTF-8 probe work (including rereads); continuation
     * retains interrupted probes, so a large block or phrase prefix is not silently skipped.
     * Raw source reads and source validation share an independent per-fetch I/O budget before parsing. */
    override suspend fun searchExact(
        userId: Long,
        documentId: String,
        query: String,
        querySeq: Long,
        offset: Int,
        limit: Int,
        latestQuerySeq: () -> Long,
        onProgress: (SearchProgress) -> Unit,
        cursor: ExactMatchCursor?,
        options: ExactMatchOptions,
    ): BookSearchOutcome {
        if (userId <= 0 || documentId.isBlank() || offset != 0) return BookSearchOutcome.Rejected(CODE_INVALID_QUERY)
        val compiled = ExactTextMatcher.compile(query, options) ?: return BookSearchOutcome.Rejected(CODE_INVALID_QUERY)
        if (fallbackByteBudget <= 0 || sectionByteLimit <= 0 || fallbackSectionLimit <= 0 || sourceReadByteBudget !in 1..64L * 1024 * 1024) {
            return BookSearchOutcome.Rejected(CODE_INVALID_EXACT_BUDGET)
        }
        val coroutine = currentCoroutineContext()
        fun checkActive() { coroutine.ensureActive() }
        checkActive()
        fun superseded(): BookSearchOutcome.Superseded? {
            val latest = latestQuerySeq()
            return if (latest > querySeq) BookSearchOutcome.Superseded(querySeq, latest) else null
        }
        superseded()?.let { return it }
        val document = catalog.findDocumentIncludingDeleted(documentId)
            ?: return BookSearchOutcome.Rejected(CODE_NOT_FOUND)
        when (val decision = ReaderAccessPolicy.canRead(userId, document.toAccessRecord())) {
            is ReaderAccessDecision.Denied -> return BookSearchOutcome.Rejected(decision.code)
            else -> Unit
        }
        val revision = document.contentRevision
        if (cursor != null && (cursor.version != 1 || cursor.userId != userId || cursor.documentId != documentId ||
            cursor.contentRevision != revision || cursor.accessEpoch != document.accessEpoch || cursor.query != query ||
            cursor.options != options || cursor.sectionIndex < 0 || cursor.blockIndex < 0 || cursor.utf16Offset < 0 ||
            cursor.totalHitsSoFar < 0 || cursor.blockState?.candidateStart?.let { it != cursor.utf16Offset } == true)) {
            return BookSearchOutcome.Rejected(CODE_INVALID_EXACT_CURSOR)
        }
        val sections = catalog.findSections(documentId, revision).sortedWith(
            compareBy<ReaderSectionEntity> { it.orderIndex }.thenBy { it.id })
        if (sections.isEmpty()) return BookSearchOutcome.Rejected(ReaderAccessPolicy.ERROR_REVISION_MISSING)
        if (cursor != null && (cursor.sectionIndex !in sections.indices || sections[cursor.sectionIndex].id != cursor.sectionId)) {
            return BookSearchOutcome.Rejected(CODE_INVALID_EXACT_CURSOR)
        }
        val ctx = AccessContext(userId, 0, UUID.randomUUID().toString())
        val snapshot = when (val opened = documentAccess.open(ctx, documentId, revision)) {
            is OperationResult.Ok -> opened.value
            is OperationResult.Rejected -> return BookSearchOutcome.Rejected(opened.code)
            is OperationResult.Failed -> return BookSearchOutcome.Rejected(opened.code)
            is OperationResult.Conflict -> return BookSearchOutcome.Rejected(CODE_CONFLICT)
        }
        if (snapshot.documentId != documentId || snapshot.contentRevision != revision || snapshot.accessEpoch != document.accessEpoch) {
            return BookSearchOutcome.Rejected(CODE_INVALID_EXACT_CURSOR)
        }
        // Reader DocumentSnapshot is metadata only, with no close/release API or owned handle.
        // Nothing from it or a loaded source page is retained in the returned cursor.
        var sectionIndex = cursor?.sectionIndex ?: 0
        var pageCursor = cursor?.pageCursor
        var blockIndex = cursor?.blockIndex ?: 0
        var blockId = cursor?.blockId
        var blockState = cursor?.blockState
        var utf16Offset = cursor?.utf16Offset ?: 0
        val previousTotal = cursor?.totalHitsSoFar ?: 0
        val hits = ArrayList<SearchHit>()
        val safeLimit = limit.coerceIn(1, BookSearchQuery.MAX_PAGE_SIZE)
        val touchedSections = LinkedHashSet<Int>()
        val sourceBudget = SourceReadBudget(sourceReadByteBudget, {
            checkActive()
            if (latestQuerySeq() > querySeq) throw ExactQuerySupersededException()
        })
        var sourceBlocked = false
        var scannedBytes = 0L
        var sectionBytes = 0L
        var processedBlocks = 0
        val warnings = LinkedHashSet<String>()

        suspend fun recheck(): String? {
            checkActive()
            val current = catalog.findDocumentIncludingDeleted(documentId) ?: return CODE_NOT_FOUND
            when (val allowed = ReaderAccessPolicy.canRead(userId, current.toAccessRecord())) {
                is ReaderAccessDecision.Denied -> return allowed.code
                else -> Unit
            }
            if (current.contentRevision != revision || current.accessEpoch != document.accessEpoch) return CODE_INVALID_EXACT_CURSOR
            return null
        }
        fun continuation(): ExactMatchCursor? {
            if (sectionIndex >= sections.size) return null
            return ExactMatchCursor(userId, documentId, revision, document.accessEpoch, query, options,
                sectionIndex, sections[sectionIndex].id, pageCursor, blockIndex, blockId, utf16Offset,
                previousTotal + hits.size, blockState = blockState)
        }
        suspend fun finish(): BookSearchOutcome {
            recheck()?.let { return BookSearchOutcome.Rejected(it) }
            superseded()?.let { return it }
            if (previousTotal.toLong() + hits.size > Int.MAX_VALUE) return BookSearchOutcome.Rejected(CODE_EXACT_TOTAL_LIMIT)
            val next = if (sourceBlocked && scannedBytes == 0L) null else continuation()
            if (next != null) warnings += WARN_TRUNCATED
            checkActive()
            return BookSearchOutcome.Ok(BookSearchResult(documentId, revision, querySeq, query, hits.toList(),
                previousTotal + hits.size, groupBySection(hits),
                if (next == null && !sourceBlocked) SearchCoverage.FULL else SearchCoverage.PARTIAL,
                touchedSections.size, next != null || sourceBlocked, warnings.toList(), next))
        }
        fun advanceSection() {
            sectionIndex += 1
            pageCursor = null
            blockIndex = 0
            blockId = null
            blockState = null
            utf16Offset = 0
            sectionBytes = 0
        }
        fun report() = onProgress(SearchProgress(touchedSections.size, sections.size, scannedBytes,
            fallbackByteBudget, previousTotal + hits.size))

        report()
        try {
            while (sectionIndex < sections.size) {
                checkActive()
                superseded()?.let { return it }
                recheck()?.let { return BookSearchOutcome.Rejected(it) }
                if (hits.size >= safeLimit) { warnings += WARN_EXACT_PAGE_LIMIT; return finish() }
                if (scannedBytes >= fallbackByteBudget) { warnings += WARN_BYTE_BUDGET; return finish() }
                if (sectionBytes >= sectionByteLimit) { warnings += WARN_SECTION_BYTES; return finish() }
                if (sectionIndex !in touchedSections && touchedSections.size >= fallbackSectionLimit) {
                    warnings += WARN_SECTION_LIMIT
                    return finish()
                }
                touchedSections += sectionIndex
                val section = sections[sectionIndex]
                val remaining = minOf(fallbackByteBudget - scannedBytes, sectionByteLimit - sectionBytes)
                val requestedBytes = minOf(FALLBACK_BLOCK_PAGE_BYTES.toLong(), remaining).coerceAtLeast(1).toInt()
                val page = when (val read = documentAccess.readBlocksBounded(ctx, snapshot.snapshotId, section.id,
                    pageCursor, requestedBytes, sourceBudget)) {
                    is OperationResult.Ok -> read.value
                    is OperationResult.Rejected -> return BookSearchOutcome.Rejected(read.code)
                    is OperationResult.Conflict -> return BookSearchOutcome.Rejected(CODE_CONFLICT)
                    is OperationResult.Failed -> {
                        sourceBlocked = read.code == "SOURCE_IO_BUDGET_EXCEEDED" || read.code == "BOUNDED_READ_UNSUPPORTED"
                        warnings += "$WARN_EXACT_SOURCE_UNAVAILABLE:${read.code}"
                        return finish()
                    }
                }
                recheck()?.let { return BookSearchOutcome.Rejected(it) }
                // String lengths are metadata, not an unbudgeted UTF-8 pass over the page.
                // This lower bound detects a definite source-page overshoot. The independent source budget already bounds raw reads/validation;
                // requestedBytes here continues to describe the returned block page.
                if (page.items.sumOf { it.text.length.toLong() } > requestedBytes) warnings += WARN_EXACT_SOURCE_PAGE_OVERSIZED
                if (blockIndex > page.items.size || (blockIndex == page.items.size && blockId != null)) {
                    return BookSearchOutcome.Rejected(CODE_INVALID_EXACT_CURSOR)
                }
                while (blockIndex < page.items.size) {
                    checkActive()
                    superseded()?.let { return it }
                    if (processedBlocks >= MAX_EXACT_BLOCKS_PER_FETCH) {
                        warnings += WARN_EXACT_BLOCK_LIMIT
                        report()
                        return finish()
                    }
                    val block = page.items[blockIndex]
                    if (block.sectionId != section.id || (blockId != null && block.blockId != blockId) ||
                        utf16Offset > block.text.length) return BookSearchOutcome.Rejected(CODE_INVALID_EXACT_CURSOR)
                    blockId = block.blockId
                    val budget = minOf(fallbackByteBudget - scannedBytes, sectionByteLimit - sectionBytes)
                    if (budget <= 0 || hits.size >= safeLimit) {
                        warnings += if (hits.size >= safeLimit) WARN_EXACT_PAGE_LIMIT else
                            if (scannedBytes >= fallbackByteBudget) WARN_BYTE_BUDGET else WARN_SECTION_BYTES
                        report()
                        return finish()
                    }
                    val state = blockState ?: ExactTextMatcher.initial(compiled, utf16Offset)
                    processedBlocks += 1
                    val scanned = ExactTextMatcher.scan(block.text, compiled, state, budget, safeLimit - hits.size) {
                        checkActive()
                        // Sequence change must stop CPU probes too, not just the next page read.
                        if (latestQuerySeq() > querySeq) throw ExactQuerySupersededException()
                    }
                    scannedBytes += scanned.scannedBytes
                    sectionBytes += scanned.scannedBytes
                    for (match in scanned.matches) {
                        val hit = exactHit(section, block, documentId, revision, match)
                            ?: return BookSearchOutcome.Rejected(CODE_EXACT_LOCATOR_UNAVAILABLE)
                        hits += hit
                    }
                    blockState = scanned.next
                    if (scanned.next != null) {
                        utf16Offset = scanned.next.candidateStart
                        warnings += if (hits.size >= safeLimit) WARN_EXACT_PAGE_LIMIT else
                            if (scannedBytes >= fallbackByteBudget) WARN_BYTE_BUDGET else WARN_SECTION_BYTES
                        report()
                        return finish()
                    }
                    blockIndex += 1
                    blockId = null
                    utf16Offset = 0
                    blockState = null
                }
                if (page.nextCursor != null) {
                    if (page.items.isEmpty() || page.nextCursor == pageCursor) {
                        return BookSearchOutcome.Rejected(CODE_INVALID_EXACT_SOURCE_PAGE)
                    }
                    pageCursor = page.nextCursor
                    blockIndex = 0
                } else advanceSection()
                report()
            }
            return finish()
        } catch (superseded: ExactQuerySupersededException) {
            return BookSearchOutcome.Superseded(querySeq, latestQuerySeq())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: ExactNormalizationException) {
            return BookSearchOutcome.Rejected(CODE_EXACT_NORMALIZATION_LIMIT)
        }
    }

    private class ExactQuerySupersededException : RuntimeException()

    private fun exactHit(section: ReaderSectionEntity, block: ContentBlock, documentId: String,
        revision: String, match: ExactTextMatch): SearchHit? {
        val start = match.startUtf16
        val end = match.endUtf16
        if (start < 0 || end <= start || end > block.text.length ||
            block.canonicalStart.toLong() + end > block.canonicalEnd) return null
        val quote = block.text.substring(start, end)
        val locator = ReaderLocator.Reflow(documentId, section.id, revision, null, block.nativeLocator,
            quote, prefixOf(block.text, start), suffixOf(block.text, end),
            block.canonicalStart + start, block.canonicalStart + end, block.blockId)
        val encoded = runCatching { ReaderLocatorCodec.encode(locator) }.getOrNull() ?: return null
        val prefix = prefixOf(block.text, start).orEmpty()
        val suffix = suffixOf(block.text, end).orEmpty()
        val leading = if (start > prefix.length) "…" else ""
        val trailing = if (end + suffix.length < block.text.length) "…" else ""
        // Literal source «» are ordinary text. The UI uses these half-open UTF-16 offsets,
        // not injected marker characters, to highlight only this original occurrence.
        val snippet = leading + prefix + quote + suffix + trailing
        val highlightStart = leading.length + prefix.length
        val highlightEnd = highlightStart + quote.length
        return SearchHit(section.id, section.title, section.orderIndex, block.canonicalStart + start,
            block.canonicalStart + end, snippet, encoded, block.fixedPageIndex ?: section.pageStart, true,
            snippetHighlightStart = highlightStart, snippetHighlightEnd = highlightEnd)
    }

    override suspend fun search(
        userId: Long,
        documentId: String,
        query: String,
        querySeq: Long,
        offset: Int,
        limit: Int,
        latestQuerySeq: () -> Long,
        onProgress: (SearchProgress) -> Unit,
    ): BookSearchOutcome {
        val normalized = BookSearchQuery.normalize(query)
        val terms = BookSearchQuery.terms(normalized)
        if (normalized.isEmpty() || terms.isEmpty()) {
            return BookSearchOutcome.Rejected(CODE_INVALID_QUERY)
        }

        // 授权与 revision：与阅读器同一套策略（删除/未 READY/非 owner 一律拒绝）。
        val document = catalog.findDocumentIncludingDeleted(documentId)
            ?: return BookSearchOutcome.Rejected(CODE_NOT_FOUND)
        when (val decision = ReaderAccessPolicy.canRead(userId, document.toAccessRecord())) {
            is ReaderAccessDecision.Denied -> return BookSearchOutcome.Rejected(decision.code)
            else -> Unit
        }
        val revision = document.contentRevision
        val sections = catalog.findSections(documentId, revision).sortedWith(
            compareBy<ReaderSectionEntity> { it.orderIndex }.thenBy { it.id },
        )
        if (sections.isEmpty()) {
            return BookSearchOutcome.Rejected(ReaderAccessPolicy.ERROR_REVISION_MISSING)
        }
        val sectionsById = sections.associateBy { it.id }

        val knowledgeDocument = knowledgeDocuments.findByDocument(userId, documentId)
        val lexicalComplete = isLexicallyComplete(knowledgeDocument, revision)

        var truncated = false
        val warnings = mutableListOf<String>()
        val indexedHits = if (knowledgeDocument == null) {
            emptyList()
        } else {
            val collected = collectIndexedHits(
                userId = userId,
                documentId = documentId,
                revision = revision,
                query = normalized,
                terms = terms,
                sectionsById = sectionsById,
            )
            truncated = truncated || collected.truncated
            collected.hits
        }

        val fallbackHits = ArrayList<SearchHit>()
        var scannedSections = 0
        if (!lexicalComplete) {
            // 发布是原子的（IndexPublisher 一个事务切 active_generation），所以“未完成”
            // 等价于“当前 revision 没有可检索 chunk”：全书的节都算未索引节。
            // 这里仍然先跑一次 FTS（可能命中旧代次/边界竞态），再对全部节做有界降级扫描，
            // 最后按 (sectionId, canonicalStart, canonicalEnd) 去重，索引命中优先。
            val ctx = AccessContext(userId = userId, sessionEpoch = 0, requestId = UUID.randomUUID().toString())
            when (
                val scanned = scanUnindexedSections(
                    ctx = ctx,
                    documentId = documentId,
                    revision = revision,
                    sections = sections,
                    terms = terms,
                    querySeq = querySeq,
                    latestQuerySeq = latestQuerySeq,
                    onProgress = onProgress,
                )
            ) {
                is FallbackScan.Superseded ->
                    return BookSearchOutcome.Superseded(querySeq = querySeq, latestSeq = scanned.latestSeq)

                is FallbackScan.Scanned -> {
                    fallbackHits += scanned.hits
                    scannedSections = scanned.scannedSections
                    truncated = truncated || scanned.truncated
                    warnings += scanned.warnings
                }
            }
            warnings += WARN_FALLBACK_USED
        }

        val merged = LinkedHashMap<String, SearchHit>()
        indexedHits.forEach { hit -> merged.putIfAbsent(hit.identityKey(), hit) }
        fallbackHits.forEach { hit -> merged.putIfAbsent(hit.identityKey(), hit) }
        val allHits = BookSearchQuery.sortHits(merged.values.toList())
        if (truncated) warnings += WARN_TRUNCATED

        val coverage = if (lexicalComplete && fallbackHits.isEmpty() && !truncated) {
            SearchCoverage.FULL
        } else {
            SearchCoverage.PARTIAL
        }
        val result = BookSearchResult(
            documentId = documentId,
            contentRevision = revision,
            querySeq = querySeq,
            query = normalized,
            hits = BookSearchQuery.page(allHits, offset, limit),
            totalHits = allHits.size,
            groups = groupBySection(allHits),
            coverage = coverage,
            fallbackScannedSections = scannedSections,
            truncated = truncated,
            warnings = warnings.distinct(),
        )
        return BookSearchOutcome.Ok(result)
    }

    /**
     * 书是否已经完成词法索引。
     *
     * 现有发布写的是 `lexical_state='READY'`（IndexPublisher §184），设计文档措辞是 COMPLETE；
     * 两者都视为完成，避免因字面差一个词就退化成降级扫描。源 revision 必须是当前 revision，
     * 否则 FTS 的 `k.source_revision = d.content_revision` 条件本来就不会返回任何行。
     */
    private fun isLexicallyComplete(knowledgeDocument: KnowledgeDocumentEntity?, revision: String): Boolean {
        if (knowledgeDocument == null) return false
        if (knowledgeDocument.activeGeneration == null) return false
        if (knowledgeDocument.sourceRevision != revision) return false
        return knowledgeDocument.lexicalState.uppercase() in LEXICAL_COMPLETE_STATES
    }

    private data class IndexedHits(val hits: List<SearchHit>, val truncated: Boolean)

    /** 索引命中：FTS 有界候选 -> 解析 chunk locator -> 书内位置。 */
    private suspend fun collectIndexedHits(
        userId: Long,
        documentId: String,
        revision: String,
        query: String,
        terms: List<String>,
        sectionsById: Map<String, ReaderSectionEntity>,
    ): IndexedHits {
        val candidates = lexicalIndex.search(
            userId = userId,
            query = query,
            documentIds = listOf(documentId),
            candidateLimit = LexicalIndex.MAX_CANDIDATE_LIMIT,
            topK = LexicalIndex.MAX_CANDIDATE_LIMIT,
        )
        val hits = ArrayList<SearchHit>(candidates.size)
        candidates.forEach { candidate ->
            // title_terms 对同一本书的每个 chunk 都相同：只命中书名不是“书内位置”，丢弃。
            if (!BookSearchQuery.isLocationHit(candidate.titleHits, candidate.headingHits, candidate.bodyHits)) {
                return@forEach
            }
            val section = sectionsById[candidate.sectionId] ?: return@forEach
            val locator = ReaderLocatorCodec.decodeOrNull(candidate.locatorJson) ?: return@forEach
            if (locator.sectionId != candidate.sectionId) return@forEach
            if (locator.documentId != documentId) return@forEach
            if (locator.contentRevision != revision) return@forEach
            val canonical = canonicalRangeOf(locator)
            hits += SearchHit(
                sectionId = section.id,
                sectionTitle = section.title,
                sectionOrder = section.orderIndex,
                canonicalStart = canonical.first,
                canonicalEnd = canonical.second,
                snippet = BookSearchQuery.snippet(candidate.rawText, terms),
                locatorJson = candidate.locatorJson,
                pageIndex = section.pageStart,
                fromFallback = false,
            )
        }
        // LexicalIndex 的候选扫描有硬上限（MAX_SCAN_ROWS）：触顶说明命中数只是下界。
        return IndexedHits(hits = hits, truncated = candidates.size >= LexicalIndex.MAX_SCAN_ROWS)
    }

    private sealed interface FallbackScan {
        data class Scanned(
            val hits: List<SearchHit>,
            val scannedSections: Int,
            val truncated: Boolean,
            val warnings: List<String>,
        ) : FallbackScan

        /** 扫描途中发现更新的 querySeq：本次结果必须整体丢弃。 */
        data class Superseded(val latestSeq: Long) : FallbackScan
    }

    /**
     * 未索引节的有限降级扫描。
     *
     * 有界：总字节预算 [fallbackByteBudget]、单节 [sectionByteLimit]、节数 [fallbackSectionLimit]、
     * 单节块数 [MAX_FALLBACK_BLOCKS_PER_SECTION]、命中总数 [MAX_FALLBACK_HITS]；
     * 每个节/块前 `ensureActive()`，可随协程取消；每节结束回报一次进度。
     *
     * 正文通过 [DocumentAccess.readBlocks] 读取：它内部就是
     * `DocumentStorage.revisionDirectory(documentId, revision)` + `resourceHref` +
     * Jsoup 解析（内置书走 asset）。复用同一入口而不是自己再解析一遍 HTML，是为了让
     * canonical 偏移/blockId 与索引、阅读器完全一致——否则降级命中的 Locator
     * 会在 `readRange` 的逐字核验里因块切分规则不同而失配。
     */
    private suspend fun scanUnindexedSections(
        ctx: AccessContext,
        documentId: String,
        revision: String,
        sections: List<ReaderSectionEntity>,
        terms: List<String>,
        querySeq: Long,
        latestQuerySeq: () -> Long,
        onProgress: (SearchProgress) -> Unit,
    ): FallbackScan {
        val opened = documentAccess.open(ctx, documentId, revision)
        val snapshot = when (opened) {
            is OperationResult.Ok -> opened.value
            is OperationResult.Rejected ->
                return FallbackScan.Scanned(emptyList(), 0, false, listOf(WARN_FALLBACK_UNAVAILABLE + opened.code))

            is OperationResult.Failed ->
                return FallbackScan.Scanned(emptyList(), 0, false, listOf(WARN_FALLBACK_UNAVAILABLE + opened.code))

            is OperationResult.Conflict ->
                return FallbackScan.Scanned(emptyList(), 0, false, listOf(WARN_FALLBACK_UNAVAILABLE + CODE_CONFLICT))
        }

        if (sourceReadByteBudget !in 1..64L * 1024 * 1024) {
            return FallbackScan.Scanned(emptyList(), 0, true, listOf(WARN_FALLBACK_UNAVAILABLE + "INVALID_SOURCE_IO_BUDGET"))
        }
        val coroutine = currentCoroutineContext()
        val sourceBudget = SourceReadBudget(sourceReadByteBudget, { coroutine.ensureActive() })
        val target = sections.take(fallbackSectionLimit.coerceAtLeast(1))
        val warnings = mutableListOf<String>()
        var truncated = sections.size > target.size
        if (truncated) warnings += WARN_SECTION_LIMIT
        var scannedBytes = 0L
        var scannedSections = 0
        var hitLimitReached = false
        val hits = ArrayList<SearchHit>()

        onProgress(SearchProgress(0, target.size, 0L, fallbackByteBudget, 0))
        for (section in target) {
            currentCoroutineContext().ensureActive()
            if (latestQuerySeq() > querySeq) return FallbackScan.Superseded(latestQuerySeq())
            if (scannedBytes >= fallbackByteBudget) {
                truncated = true
                warnings += WARN_BYTE_BUDGET
                break
            }
            var cursor: String? = null
            var sectionBytes = 0L
            var sectionBlocks = 0
            var sectionHits = 0
            var unreadable = false
            var sectionCapped = false
            var firstPage = true
            while (firstPage || cursor != null) {
                firstPage = false
                currentCoroutineContext().ensureActive()
                if (latestQuerySeq() > querySeq) return FallbackScan.Superseded(latestQuerySeq())
                val page = documentAccess.readBlocksBounded(
                    ctx = ctx,
                    snapshotId = snapshot.snapshotId,
                    sectionId = section.id,
                    cursor = cursor,
                    maxBytes = FALLBACK_BLOCK_PAGE_BYTES,
                    sourceBudget = sourceBudget,
                )
                if (page !is OperationResult.Ok) {
                    if (page is OperationResult.Failed) warnings += WARN_FALLBACK_UNAVAILABLE + page.code
                    unreadable = true
                    truncated = true
                    break
                }
                for (block in page.value.items) {
                    currentCoroutineContext().ensureActive()
                    sectionBytes += utf8Length(block.text)
                    if (sectionBytes > sectionByteLimit) {
                        truncated = true
                        warnings += WARN_SECTION_BYTES
                        sectionCapped = true
                        break
                    }
                    sectionBlocks += 1
                    if (sectionBlocks > MAX_FALLBACK_BLOCKS_PER_SECTION) {
                        truncated = true
                        warnings += WARN_SECTION_BLOCKS
                        sectionCapped = true
                        break
                    }
                    if (sectionHits >= MAX_HITS_PER_SECTION) {
                        truncated = true
                        warnings += WARN_SECTION_HITS
                        sectionCapped = true
                        break
                    }
                    val blockHits = matchBlock(
                        section = section,
                        block = block,
                        documentId = documentId,
                        revision = revision,
                        terms = terms,
                        budget = MAX_HITS_PER_SECTION - sectionHits,
                    )
                    hits += blockHits
                    sectionHits += blockHits.size
                    if (hits.size >= MAX_FALLBACK_HITS) {
                        hitLimitReached = true
                        sectionCapped = true
                        break
                    }
                }
                if (sectionCapped) break
                cursor = page.value.nextCursor
            }
            if (unreadable) warnings += WARN_SECTION_UNREADABLE
            scannedBytes += sectionBytes
            scannedSections += 1
            onProgress(SearchProgress(scannedSections, target.size, scannedBytes, fallbackByteBudget, hits.size))
            if (hitLimitReached) {
                truncated = true
                warnings += WARN_HIT_LIMIT
                break
            }
        }
        return FallbackScan.Scanned(
            hits = hits,
            scannedSections = scannedSections,
            truncated = truncated,
            warnings = warnings,
        )
    }

    /**
     * 在单个 block 内匹配。
     *
     * 多词查询沿用 FTS 的 AND 口径（每个词都必须出现在同一个 block 里）；
     * 命中按出现次数展开，块内最多 [MAX_HITS_PER_BLOCK] 处、受 [budget] 约束。
     */
    private fun matchBlock(
        section: ReaderSectionEntity,
        block: ContentBlock,
        documentId: String,
        revision: String,
        terms: List<String>,
        budget: Int,
    ): List<SearchHit> {
        if (budget <= 0) return emptyList()
        if (terms.any { BookSearchQuery.indexOfAny(block.text, listOf(it)) < 0 }) return emptyList()
        val hits = ArrayList<SearchHit>(minOf(budget, MAX_HITS_PER_BLOCK))
        var from = 0
        while (hits.size < budget && hits.size < MAX_HITS_PER_BLOCK) {
            val offset = BookSearchQuery.indexOfAny(block.text, terms, from)
            if (offset < 0) break
            val matched = BookSearchQuery.matchAt(block.text, offset, terms) ?: break
            val canonicalStart = block.canonicalStart + offset
            val canonicalEnd = canonicalStart + matched.length
            val quote = block.text.substring(offset, offset + matched.length)
            val locator = ReaderLocator.Reflow(
                documentId = documentId,
                sectionId = block.sectionId,
                contentRevision = revision,
                progression = null,
                nativeLocator = block.nativeLocator,
                exactQuote = quote,
                prefix = prefixOf(block.text, offset),
                suffix = suffixOf(block.text, offset + matched.length),
                canonicalStart = canonicalStart,
                canonicalEnd = canonicalEnd,
                blockId = block.blockId,
            )
            val locatorJson = runCatching { ReaderLocatorCodec.encode(locator) }.getOrNull() ?: break
            hits += SearchHit(
                sectionId = section.id,
                sectionTitle = section.title,
                sectionOrder = section.orderIndex,
                canonicalStart = canonicalStart,
                canonicalEnd = canonicalEnd,
                snippet = BookSearchQuery.snippet(block.text, terms, anchor = offset),
                locatorJson = locatorJson,
                pageIndex = section.pageStart,
                fromFallback = true,
            )
            from = offset + matched.length
        }
        return hits
    }

    private fun groupBySection(hits: List<SearchHit>): List<SearchSectionGroup> =
        hits.groupBy { it.sectionId }
            .map { (sectionId, items) ->
                val first = items.first()
                SearchSectionGroup(
                    sectionId = sectionId,
                    sectionTitle = first.sectionTitle,
                    sectionOrder = first.sectionOrder,
                    hitCount = items.size,
                )
            }
            .sortedWith(compareBy<SearchSectionGroup> { it.sectionOrder }.thenBy { it.sectionId })

    private fun prefixOf(text: String, start: Int, maxChars: Int = BookSearchQuery.SNIPPET_CONTEXT): String? {
        if (start <= 0) return null
        var from = (start - maxChars).coerceAtLeast(0)
        if (from > 0 && Character.isLowSurrogate(text[from])) from += 1
        val value = text.substring(from, start)
        return value.ifEmpty { null }
    }

    private fun suffixOf(text: String, end: Int, maxChars: Int = BookSearchQuery.SNIPPET_CONTEXT): String? {
        if (end >= text.length) return null
        var to = (end + maxChars).coerceAtMost(text.length)
        if (to < text.length && to > end && Character.isHighSurrogate(text[to - 1])) to -= 1
        val value = text.substring(end, to)
        return value.ifEmpty { null }
    }

    private fun ReaderDocumentEntity.toAccessRecord() = ReaderDocumentAccessRecord(
        documentId = id,
        kind = kind,
        ownerUserId = ownerUserId,
        contentRevision = contentRevision,
        accessEpoch = accessEpoch,
        importStatus = importStatus,
        deletedAt = deletedAt,
    )

    companion object {
        const val CODE_NOT_FOUND = ReaderAccessPolicy.ERROR_NOT_FOUND
        const val CODE_INVALID_QUERY = "INVALID_QUERY"
        const val CODE_CONFLICT = "CONFLICT"
        const val CODE_INVALID_EXACT_CURSOR = "INVALID_EXACT_CURSOR"
        const val CODE_INVALID_EXACT_BUDGET = "INVALID_EXACT_BUDGET"
        const val CODE_INVALID_EXACT_SOURCE_PAGE = "INVALID_EXACT_SOURCE_PAGE"
        const val CODE_EXACT_LOCATOR_UNAVAILABLE = "EXACT_LOCATOR_UNAVAILABLE"
        const val CODE_EXACT_TOTAL_LIMIT = "EXACT_TOTAL_LIMIT"
        const val CODE_EXACT_NORMALIZATION_LIMIT = "EXACT_NORMALIZATION_LIMIT"
        const val MAX_EXACT_BLOCKS_PER_FETCH = 1024
        const val WARN_EXACT_BLOCK_LIMIT = "EXACT_BLOCK_LIMIT_REACHED"
        const val WARN_EXACT_PAGE_LIMIT = "EXACT_PAGE_LIMIT_REACHED"
        const val WARN_EXACT_SOURCE_UNAVAILABLE = "EXACT_SOURCE_UNAVAILABLE"
        const val WARN_EXACT_SOURCE_PAGE_OVERSIZED = "EXACT_SOURCE_PAGE_OVERSIZED"

        /** 降级扫描总字节预算（正文 UTF-8 字节）。 */
        const val DEFAULT_FALLBACK_BYTE_BUDGET: Long = 8L * 1024 * 1024

        /** 单节正文上限：一节异常巨大时不把整本书的时间预算吃掉。 */
        const val DEFAULT_SECTION_BYTE_LIMIT: Long = 1024L * 1024

        /** 单次降级扫描最多处理的节数。 */
        const val DEFAULT_FALLBACK_SECTION_LIMIT = 200

        /** 降级命中总数上限。 */
        const val MAX_FALLBACK_HITS = 400

        /** 单节最多产出的降级命中数。 */
        const val MAX_HITS_PER_SECTION = 20

        /** 单块最多产出的降级命中数。 */
        const val MAX_HITS_PER_BLOCK = 3

        /** 单节最多读取的块数。 */
        const val MAX_FALLBACK_BLOCKS_PER_SECTION = 1024

        /** readBlocks 单页字节预算（沿用 DocumentAccess 的既有分页上限口径）。 */
        const val FALLBACK_BLOCK_PAGE_BYTES = 64 * 1024

        /** 现有发布写 READY；设计文档写 COMPLETE。两者都算“词法索引完成”。 */
        val LEXICAL_COMPLETE_STATES = setOf("READY", "COMPLETE")

        const val WARN_FALLBACK_USED = "FALLBACK_USED"
        const val WARN_FALLBACK_UNAVAILABLE = "FALLBACK_UNAVAILABLE:"
        const val WARN_TRUNCATED = "TRUNCATED"
        const val WARN_BYTE_BUDGET = "FALLBACK_BYTE_BUDGET_EXHAUSTED"
        const val WARN_SECTION_BYTES = "FALLBACK_SECTION_BYTES_EXCEEDED"
        const val WARN_SECTION_BLOCKS = "FALLBACK_SECTION_BLOCKS_EXCEEDED"
        const val WARN_SECTION_HITS = "FALLBACK_SECTION_HITS_CAPPED"
        const val WARN_SECTION_UNREADABLE = "FALLBACK_SECTION_UNREADABLE"
        const val WARN_SECTION_LIMIT = "FALLBACK_SECTION_LIMIT_REACHED"
        const val WARN_HIT_LIMIT = "FALLBACK_HIT_LIMIT_REACHED"

        /** 正文 UTF-8 字节数（不分配中间数组，预算核算用）。 */
        fun utf8Length(text: String): Long {
            var bytes = 0L
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                bytes += when {
                    codePoint < 0x80 -> 1
                    codePoint < 0x800 -> 2
                    codePoint < 0x10000 -> 3
                    else -> 4
                }
                index += Character.charCount(codePoint)
            }
            return bytes
        }
    }
}

/** 书内搜索入口：ViewModel 只依赖这个接口，便于替换/伪造。 */
interface BookSearcher {
    /** Exact pages contain only new positions; totalHits is cumulative, exact only at FULL.
     * Nonzero offset is unsupported: continue with nextCursor instead.
     * Default delegation keeps legacy test doubles source compatible.
     */
    suspend fun searchExact(
        userId: Long,
        documentId: String,
        query: String,
        querySeq: Long,
        offset: Int = 0,
        limit: Int = BookSearchQuery.DEFAULT_PAGE_SIZE,
        latestQuerySeq: () -> Long = { querySeq },
        onProgress: (SearchProgress) -> Unit = {},
        cursor: ExactMatchCursor? = null,
        options: ExactMatchOptions = ExactMatchOptions(),
    ): BookSearchOutcome = search(userId, documentId, query, querySeq, offset, limit, latestQuerySeq, onProgress)

    suspend fun search(
        userId: Long,
        documentId: String,
        query: String,
        querySeq: Long,
        offset: Int = 0,
        limit: Int = BookSearchQuery.DEFAULT_PAGE_SIZE,
        latestQuerySeq: () -> Long = { querySeq },
        onProgress: (SearchProgress) -> Unit = {},
    ): BookSearchOutcome
}

/** 单条命中：UI 只需展示 + 用 [locatorJson] 跳转，不自己算偏移。 */
data class SearchHit(
    val sectionId: String,
    val sectionTitle: String,
    val sectionOrder: Int,
    val canonicalStart: Int,
    val canonicalEnd: Int,
    val snippet: String,
    val locatorJson: String,
    val pageIndex: Int?,
    val fromFallback: Boolean,
    /** Half-open original UTF-16 range in snippet, including any displayed ellipsis prefix.
     * Both null means legacy marker-formatted snippet; exact snippets use explicit offsets. */
    val snippetHighlightStart: Int? = null,
    val snippetHighlightEnd: Int? = null,
) {
    /** 去重键：同一位置被索引命中与降级命中重复命中时只保留一条。 */
    fun identityKey(): String = "$sectionId|$canonicalStart|$canonicalEnd"
}

/** 按章节分组（标题 + 命中数），供 UI 展示小节头。 */
data class SearchSectionGroup(
    val sectionId: String,
    val sectionTitle: String,
    val sectionOrder: Int,
    val hitCount: Int,
)

/** FULL 表示当前查询的覆盖范围已完整搜索；精确入口只有原文耗尽才 FULL。 */
enum class SearchCoverage { FULL, PARTIAL }

/** 原文扫描进度；精确入口计入实际 UTF-8 探查工作及重复前缀/TERMS 验证。 */
data class SearchProgress(
    val scannedSections: Int,
    val totalSections: Int,
    val scannedBytes: Long,
    val byteBudget: Long,
    val hits: Int,
) {
    val fraction: Float
        get() = if (totalSections <= 0) 0f else (scannedSections.toFloat() / totalSections).coerceIn(0f, 1f)
}

data class BookSearchResult(
    val documentId: String,
    val contentRevision: String,
    val querySeq: Long,
    val query: String,
    /** 当前页结果（已按 sectionOrder、canonicalStart 排序）。 */
    val hits: List<SearchHit>,
    /** 本次查询在**当前覆盖范围内**的命中总数（[truncated] 时只是下界）。 */
    val totalHits: Int,
    val groups: List<SearchSectionGroup>,
    val coverage: SearchCoverage,
    /** 本批尝试原文扫描的节数；旧 search 的 0 表示结果完全来自索引。 */
    val fallbackScannedSections: Int,
    /** 命中数被上限截断（候选扫描触顶 / 字节预算 / 节数上限）。 */
    val truncated: Boolean,
    val warnings: List<String> = emptyList(),
    /** Next unscanned position. Null only when exhausted or continuation is unavailable. */
    val nextCursor: ExactMatchCursor? = null,
)

sealed interface BookSearchOutcome {
    data class Ok(val result: BookSearchResult) : BookSearchOutcome

    /** 已有更新的 querySeq：本次结果必须丢弃，不得覆盖新查询。 */
    data class Superseded(val querySeq: Long, val latestSeq: Long) : BookSearchOutcome

    data class Rejected(val code: String) : BookSearchOutcome
}

/**
 * 查询归一、片段与分页的纯逻辑（无 Android/Room 依赖，JVM 单测直接覆盖）。
 *
 * 分词口径全部转交 [ChineseTokenizer]，本类只决定“整串当短语”还是“多词 AND”。
 */
object BookSearchQuery {
    /** snippet 命中词前后各保留的字数。 */
    const val SNIPPET_CONTEXT = 20

    const val DEFAULT_PAGE_SIZE = 20
    const val MAX_PAGE_SIZE = 100

    private const val MAX_SNIPPET_RADIUS = 200

    /** 视为“引号”的字符：含中文引号/直角引号。 */
    private val QUOTE_CHARS = charArrayOf('"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』')

    /**
     * 查询归一：NFKC（全角拉丁/数字/全角逗号 -> 半角）后，把所有空白
     * （含 U+3000 全角空格、U+00A0 NBSP）折叠成一个半角空格并去掉首尾。
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val builder = StringBuilder(nfkc.length)
        var pendingSpace = false
        nfkc.forEach { ch ->
            if (isSpace(ch)) {
                if (builder.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    builder.append(' ')
                    pendingSpace = false
                }
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    /** 含空白或引号的输入按短语处理。 */
    fun isPhrase(raw: String): Boolean {
        val normalized = normalize(raw)
        if (normalized.any { isSpace(it) }) return true
        return normalized.any { ch -> QUOTE_CHARS.contains(ch) }
    }

    /**
     * 真正交给 tokenizer 的查询串：
     *  - 短语：去掉引号与所有分隔空白，得到连续文本（CJK 短语不拆字）；
     *  - 其他：归一后的原串（多词由 tokenizer 切成 AND 分支）。
     */
    fun effectiveQuery(raw: String): String {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return ""
        if (!isPhrase(normalized)) return normalized
        val builder = StringBuilder(normalized.length)
        normalized.forEach { ch ->
            if (!isSpace(ch) && !QUOTE_CHARS.contains(ch)) builder.append(ch)
        }
        return builder.toString()
    }

    /** 片段/降级匹配用的词表：去重，长词优先（同一位置优先取最长命中）。 */
    fun terms(raw: String): List<String> {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return emptyList()
        val candidates = if (isPhrase(normalized)) {
            listOf(stripSeparators(normalized))
        } else {
            ChineseTokenizer.splitQueryWords(normalized)
        }
        return candidates
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareByDescending<String> { it.length }.thenBy { it })
    }

    /** FTS MATCH 表达式（与 LexicalIndex 内部一致；测试用来断言口径）。 */
    fun matchExpression(raw: String): String? =
        ChineseTokenizer.toMatchExpression(effectiveQuery(raw))

    /**
     * 书内搜索的位置过滤：只命中书名（title 列）不是“位置”，因为同一本书的每个
     * chunk 都带相同的 title_terms，放行会让整本书的块都被算成命中。
     */
    fun isLocationHit(titleHits: Int, headingHits: Int, bodyHits: Int): Boolean =
        headingHits > 0 || bodyHits > 0

    /**
     * 命中片段：命中词前后各 [radius] 个字符，命中词用 «» 包裹（纯文本，不含 HTML）。
     *
     * [anchor] 已知命中起点时按它居中（同一 block 的多处命中各自截图自己的窗口），
     * 否则取词表在文本中的第一处命中。没有命中时返回头部 [radius]*2 个字符，不加标记。
     */
    fun snippet(
        text: String,
        terms: List<String>,
        anchor: Int = -1,
        radius: Int = SNIPPET_CONTEXT,
    ): String {
        if (text.isEmpty()) return ""
        val flat = flatten(text)
        val ordered = orderTerms(terms)
        val safeRadius = radius.coerceIn(1, MAX_SNIPPET_RADIUS)
        if (flat.isEmpty() || ordered.isEmpty()) return clipHead(flat, safeRadius)

        var matchStart = -1
        var matched = ""
        if (anchor in 0 until flat.length) {
            val atAnchor = matchAtOrdered(flat, anchor, ordered)
            if (atAnchor != null) {
                matchStart = anchor
                matched = atAnchor
            }
        }
        if (matchStart < 0) {
            val first = indexOfAnyOrdered(flat, ordered, 0)
            if (first >= 0) {
                matchStart = first
                matched = matchAtOrdered(flat, first, ordered) ?: ""
            }
        }
        if (matchStart < 0 || matched.isEmpty()) return clipHead(flat, safeRadius)

        var start = (matchStart - safeRadius).coerceAtLeast(0)
        var end = (matchStart + matched.length + safeRadius).coerceAtMost(flat.length)
        if (start > 0 && Character.isLowSurrogate(flat[start])) start -= 1
        if (end < flat.length && end > 0 && Character.isHighSurrogate(flat[end - 1])) end += 1
        val window = flat.substring(start, end)
        val body = wrapOccurrences(window, ordered)
        return buildString(body.length + 2) {
            if (start > 0) append('…')
            append(body)
            if (end < flat.length) append('…')
        }
    }

    /** 词表在文本中的下一处命中起点（从 [from] 起，忽略大小写），没有则 -1。 */
    fun indexOfAny(text: String, terms: List<String>, from: Int = 0): Int =
        indexOfAnyOrdered(text, orderTerms(terms), from)

    /** 文本在 [index] 处是否命中某个词；命中返回该词，否则 null（忽略大小写）。 */
    fun matchAt(text: String, index: Int, terms: List<String>): String? =
        matchAtOrdered(text, index, orderTerms(terms))

    /** 排序键：(sectionOrder, canonicalStart, canonicalEnd, sectionId)，稳定可复现。 */
    fun sortHits(hits: List<SearchHit>): List<SearchHit> = hits.sortedWith(
        compareBy<SearchHit> { it.sectionOrder }
            .thenBy { it.canonicalStart }
            .thenBy { it.canonicalEnd }
            .thenBy { it.sectionId },
    )

    /**
     * offset/limit 分页：offset 负数归零、越界返回空；limit 收敛到 1..[MAX_PAGE_SIZE]。
     * 不做“limit 超大就返回全量”的隐式行为。
     */
    fun <T> page(items: List<T>, offset: Int, limit: Int): List<T> {
        val safeOffset = offset.coerceAtLeast(0)
        if (safeOffset >= items.size) return emptyList()
        val size = limit.coerceIn(1, MAX_PAGE_SIZE)
        return items.subList(safeOffset, minOf(items.size, safeOffset + size)).toList()
    }

    // ---- 内部实现 ----

    private fun stripSeparators(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            if (!isSpace(ch) && !QUOTE_CHARS.contains(ch)) append(ch)
        }
    }

    private fun isSpace(ch: Char): Boolean =
        ch == '\u00A0' || ch == '\u3000' || ch.isWhitespace()

    /** 换行/制表/特殊空格替换为半角空格（等长，偏移不漂移）。 */
    private fun flatten(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            append(if (ch == '\r' || ch == '\n' || ch == '\t' || ch == '\u00A0' || ch == '\u3000') ' ' else ch)
        }
    }

    private fun orderTerms(terms: List<String>): List<String> = terms
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedWith(compareByDescending<String> { it.length }.thenBy { it })

    private fun clipHead(flat: String, radius: Int): String {
        val limit = radius * 2
        if (flat.length <= limit) return flat
        var end = limit
        if (Character.isHighSurrogate(flat[end - 1])) end -= 1
        return flat.substring(0, end) + "…"
    }

    private fun indexOfAnyOrdered(text: String, ordered: List<String>, from: Int): Int {
        if (ordered.isEmpty()) return -1
        var index = from.coerceAtLeast(0)
        while (index < text.length) {
            if (matchAtOrdered(text, index, ordered) != null) return index
            index += 1
        }
        return -1
    }

    private fun matchAtOrdered(text: String, index: Int, ordered: List<String>): String? {
        if (index < 0 || index >= text.length) return null
        for (term in ordered) {
            if (term.isEmpty() || index + term.length > text.length) continue
            if (text.regionMatches(index, term, 0, term.length, true)) return term
        }
        return null
    }

    private fun wrapOccurrences(window: String, ordered: List<String>): String {
        if (ordered.isEmpty()) return window
        val out = StringBuilder(window.length + 8)
        var index = 0
        while (index < window.length) {
            val matched = matchAtOrdered(window, index, ordered)
            if (matched != null) {
                out.append('«').append(window, index, index + matched.length).append('»')
                index += matched.length
            } else {
                out.append(window[index])
                index += 1
            }
        }
        return out.toString()
    }
}

/** Locator 的 canonical 区间（非 Reflow 退化为 0..0，只用于排序/展示，不用于跳转）。 */
private fun canonicalRangeOf(locator: ReaderLocator): Pair<Int, Int> = when (locator) {
    is ReaderLocator.Reflow -> {
        val start = locator.canonicalStart.coerceAtLeast(0)
        start to locator.canonicalEnd.coerceAtLeast(start)
    }

    is ReaderLocator.Note -> {
        val start = locator.canonicalStart?.coerceAtLeast(0) ?: 0
        start to (locator.canonicalEnd ?: start).coerceAtLeast(start)
    }

    is ReaderLocator.Fixed -> 0 to 0
}

/**
 * querySeq 闸门（书内搜索的“旧结果不得覆盖新查询”）。
 *
 * 每次请求取一个递增序号；只有序号仍是最新的结果才允许落状态。
 * 服务端在降级扫描过程中用 [isSuperseded] 立即放弃，避免白扫整本书。
 */
class SearchSequenceGate {
    private val sequence = AtomicLong(0L)

    fun next(): Long = sequence.incrementAndGet()

    fun latest(): Long = sequence.get()

    /** 结果是否仍是最新一次请求（旧序号一律丢弃）。 */
    fun accept(candidate: Long): Boolean = sequence.get() == candidate

    /** 是否已经出现更新的请求（扫描中途放弃用）。 */
    fun isSuperseded(candidate: Long): Boolean = sequence.get() > candidate
}
