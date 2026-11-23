package com.example.myapplication.data.knowledge

import android.content.res.AssetManager
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.ReaderDocumentEntity
import com.example.myapplication.data.local.entity.ReaderSectionEntity
import com.example.myapplication.data.reader.verifiedBuiltInProjection
import com.example.myapplication.data.reader.ReaderHtmlBlockParser
import com.example.myapplication.data.reader.storage.DocumentStorage
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.ContentBlock
import com.example.myapplication.domain.reader.DocumentAccess
import com.example.myapplication.domain.reader.DocumentSnapshot
import com.example.myapplication.domain.reader.ExtractionQuality
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.NormalizedRect
import com.example.myapplication.domain.reader.ReaderAccessDecision
import com.example.myapplication.domain.reader.ReaderAccessPolicy
import com.example.myapplication.domain.reader.ReaderDocumentAccessRecord
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderPage
import com.example.myapplication.domain.reader.SectionDescriptor
import com.example.myapplication.domain.reader.SourceEvidence
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import com.example.myapplication.domain.reader.SourceReadBudget
import com.example.myapplication.domain.reader.SourceReadLimitExceeded
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

/**
 * DocumentAccess 的 Room 实现（设计 §5.1 / §11）。
 *
 * - open 固定 (documentId, contentRevision, accessEpoch)，之后 readBlocks/readRange
 *   只在该 revision 内读取；文档更新后旧 snapshot 不再返回当前内容。
 * - block 由该 revision 的 section HTML 现场解析（一期未持久化 block 表），
 *   canonical 区间是 section 内累计字符偏移，半开区间。
 * - 每次调用都复核 owner/kind/importStatus/deleted_at 与 revision/epoch，
 *   SELECT 成功本身不是授权结论。
 */
class RoomDocumentAccess private constructor(
    private val database: AppDatabase,
    private val storage: DocumentStorage,
    private val assets: AssetManager,
    private val historicalReading: Boolean,
) : DocumentAccess {
    constructor(database: AppDatabase, storage: DocumentStorage, assets: AssetManager) :
        this(database, storage, assets, false)


    private val catalog get() = database.readerCatalogDao()

    override suspend fun open(
        ctx: AccessContext,
        documentId: String,
        requestedRevision: String?,
    ): OperationResult<DocumentSnapshot> {
        val document = catalog.findDocumentIncludingDeleted(documentId)
            ?: return OperationResult.Rejected("NOT_FOUND")
        when (val decision = ReaderAccessPolicy.canRead(ctx.userId, document.toAccessRecord())) {
            is ReaderAccessDecision.Denied -> return OperationResult.Rejected(decision.code)
            else -> Unit
        }
        val revision = requestedRevision ?: document.contentRevision
        when (val decision = if (historicalReading) ReaderAccessDecision.Allowed else ReaderAccessPolicy.checkRevision(document.toAccessRecord(), revision)) {
            is ReaderAccessDecision.Denied -> return OperationResult.Rejected(decision.code)
            else -> Unit
        }
        val revisionRow = catalog.findContentRevision(documentId, revision)
            ?: return OperationResult.Rejected("REVISION_MISSING")
        val sections = catalog.findSections(documentId, revision)
        if (sections.isEmpty()) return OperationResult.Failed("REVISION_MISSING", retryable = false)
        authorizationError(ctx, SnapshotId(ctx.userId, documentId, revision, document.accessEpoch))?.let { return OperationResult.Rejected(it) }
        return OperationResult.Ok(
            DocumentSnapshot(
                snapshotId = snapshotIdOf(documentId, revision, ctx.userId, document.accessEpoch),
                documentId = documentId,
                ownerUserId = document.ownerUserId,
                contentRevision = revision,
                metadataRevision = document.metadataRevision,
                accessEpoch = document.accessEpoch,
                extractorVersion = revisionRow.extractorVersion,
                sections = sections.take(MAX_SNAPSHOT_SECTIONS).map { it.toDescriptor(documentId, revision) },
                sectionsNextCursor = if (sections.size > MAX_SNAPSHOT_SECTIONS) MAX_SNAPSHOT_SECTIONS.toString() else null,
            ),
            operationId = UUID.randomUUID().toString(),
        )
    }

    override suspend fun readSectionDescriptors(
        ctx: AccessContext,
        snapshotId: String,
        cursor: String?,
        limit: Int,
    ): OperationResult<ReaderPage<SectionDescriptor>> {
        val parsed = parseSnapshotId(snapshotId) ?: return OperationResult.Rejected("INVALID_SNAPSHOT")
        if (parsed.userId != ctx.userId) return OperationResult.Rejected("NOT_FOUND")
        val document = catalog.findDocumentIncludingDeleted(parsed.documentId)
            ?: return OperationResult.Rejected("NOT_FOUND")
        when (val decision = ReaderAccessPolicy.canRead(ctx.userId, document.toAccessRecord())) {
            is ReaderAccessDecision.Denied -> return OperationResult.Rejected(decision.code)
            else -> Unit
        }
        if (document.accessEpoch != parsed.accessEpoch) return OperationResult.Rejected("ACCESS_EPOCH_MISMATCH")
        if (!historicalReading && document.contentRevision != parsed.revision) {
            return OperationResult.Rejected("REVISION_MISSING")
        }
        if (catalog.findContentRevision(parsed.documentId, parsed.revision) == null) {
            return OperationResult.Rejected("REVISION_MISSING")
        }
        val sections = catalog.findSections(parsed.documentId, parsed.revision)
        if (sections.isEmpty()) return OperationResult.Rejected("REVISION_MISSING")
        val fromIndex = parseCursor(cursor) ?: return OperationResult.Rejected("INVALID_CURSOR")
        if (fromIndex > sections.size) return OperationResult.Rejected("INVALID_CURSOR")
        val pageSize = limit.coerceIn(1, 64)
        val page = sections.drop(fromIndex).take(pageSize)
        val next = if (fromIndex + page.size < sections.size) (fromIndex + page.size).toString() else null
        authorizationError(ctx, parsed)?.let { return OperationResult.Rejected(it) }
        return OperationResult.Ok(
            ReaderPage(
                items = page.map { it.toDescriptor(parsed.documentId, parsed.revision) },
                nextCursor = next,
            ),
            operationId = UUID.randomUUID().toString(),
        )
    }

    override suspend fun readBlocks(
        ctx: AccessContext, snapshotId: String, sectionId: String, cursor: String?, maxBytes: Int,
    ): OperationResult<ReaderPage<ContentBlock>> =
        readBlocksInternal(ctx, snapshotId, sectionId, cursor, maxBytes, defaultSourceBudget())

    override suspend fun readBlocksBounded(
        ctx: AccessContext, snapshotId: String, sectionId: String, cursor: String?, maxBytes: Int,
        sourceBudget: SourceReadBudget,
    ): OperationResult<ReaderPage<ContentBlock>> =
        readBlocksInternal(ctx, snapshotId, sectionId, cursor, maxBytes, sourceBudget)

    private suspend fun defaultSourceBudget(): SourceReadBudget {
        val context = currentCoroutineContext()
        return SourceReadBudget(8L * 1024 * 1024, { context.ensureActive() })
    }

    private suspend fun readBlocksInternal(
        ctx: AccessContext,
        snapshotId: String,
        sectionId: String,
        cursor: String?,
        maxBytes: Int,
        sourceBudget: SourceReadBudget,
    ): OperationResult<ReaderPage<ContentBlock>> {
        val parsed = parseSnapshotId(snapshotId) ?: return OperationResult.Rejected("INVALID_SNAPSHOT")
        if (parsed.userId != ctx.userId) return OperationResult.Rejected("NOT_FOUND")
        val document = catalog.findDocumentIncludingDeleted(parsed.documentId)
            ?: return OperationResult.Rejected("NOT_FOUND")
        when (val decision = ReaderAccessPolicy.canRead(ctx.userId, document.toAccessRecord())) {
            is ReaderAccessDecision.Denied -> return OperationResult.Rejected(decision.code)
            else -> Unit
        }
        if (document.accessEpoch != parsed.accessEpoch) return OperationResult.Rejected("ACCESS_EPOCH_MISMATCH")
        if (!historicalReading && document.contentRevision != parsed.revision) {
            // 旧 snapshot 只允许历史阅读；当前内容已换 revision 时不得混用
            return OperationResult.Rejected("REVISION_MISSING")
        }
        if (catalog.findContentRevision(parsed.documentId, parsed.revision) == null) {
            return OperationResult.Rejected("REVISION_MISSING")
        }
        val fromIndex = parseCursor(cursor) ?: return OperationResult.Rejected("INVALID_CURSOR")
        val section = catalog.findSections(parsed.documentId, parsed.revision).firstOrNull { it.id == sectionId }
            ?: return OperationResult.Rejected("NOT_FOUND")
        val blocks = try {
            parseBlocks(document, parsed.revision, section, sourceBudget)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (limit: SourceReadLimitExceeded) {
            return OperationResult.Failed("SOURCE_IO_BUDGET_EXCEEDED", retryable = false)
        } catch (error: Exception) {
            return OperationResult.Failed("STORAGE_FAILURE", retryable = true)
        }
        authorizationError(ctx, parsed)?.let { return OperationResult.Rejected(it) }
        if (fromIndex > blocks.size) return OperationResult.Rejected("INVALID_CURSOR")
        val byteBudget = maxBytes.coerceIn(1024, 512 * 1024)
        val page = mutableListOf<ContentBlock>()
        var used = 0
        var index = fromIndex
        while (index < blocks.size && page.size < 64) {
            val block = blocks[index]
            val size = block.text.toByteArray(StandardCharsets.UTF_8).size
            if (page.isNotEmpty() && used + size > byteBudget) break
            page += block
            used += size
            index += 1
        }
        val next = if (index < blocks.size) index.toString() else null
        return OperationResult.Ok(
            ReaderPage(items = page, nextCursor = next),
            operationId = UUID.randomUUID().toString(),
        )
    }

    override suspend fun readRange(
        ctx: AccessContext,
        snapshotId: String,
        locator: ReaderLocator,
        maxTokens: Int,
    ): OperationResult<SourceEvidence> {
        val parsed = parseSnapshotId(snapshotId) ?: return OperationResult.Rejected("INVALID_SNAPSHOT")
        if (parsed.userId != ctx.userId) return OperationResult.Rejected("NOT_FOUND")
        if (locator.documentId != parsed.documentId || locator.contentRevision != parsed.revision) {
            return OperationResult.Rejected("REVISION_MISSING")
        }
        val document = catalog.findDocumentIncludingDeleted(parsed.documentId)
            ?: return OperationResult.Rejected("NOT_FOUND")
        when (val decision = ReaderAccessPolicy.canRead(ctx.userId, document.toAccessRecord())) {
            is ReaderAccessDecision.Denied -> return OperationResult.Rejected(decision.code)
            else -> Unit
        }
        if (document.accessEpoch != parsed.accessEpoch) return OperationResult.Rejected("ACCESS_EPOCH_MISMATCH")
        if (!historicalReading && document.contentRevision != parsed.revision)
            return OperationResult.Rejected("REVISION_MISSING")
        val revisionRow = catalog.findContentRevision(parsed.documentId, parsed.revision)
            ?: return OperationResult.Rejected("REVISION_MISSING")
        val section = catalog.findSections(parsed.documentId, parsed.revision).firstOrNull { it.id == locator.sectionId }
            ?: return OperationResult.Rejected("NOT_FOUND")
        val blocks = try {
            parseBlocks(document, parsed.revision, section, defaultSourceBudget())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (limit: SourceReadLimitExceeded) {
            return OperationResult.Failed("SOURCE_IO_BUDGET_EXCEEDED", retryable = false)
        } catch (error: Exception) {
            return OperationResult.Failed("STORAGE_FAILURE", retryable = true)
        }
        authorizationError(ctx, parsed)?.let { return OperationResult.Rejected(it) }
        val quote = when (locator) {
            is ReaderLocator.Reflow -> locateQuote(blocks, locator)
            is ReaderLocator.Fixed -> locateFixedQuote(blocks, section, locator)
            is ReaderLocator.Note -> locateQuote(blocks, locator)
        }.orEmpty()
        if (quote.isBlank()) return OperationResult.Rejected("QUOTE_MISMATCH")
        val budget = maxTokens.coerceIn(16, 8192)
        val trimmed = trimToBudget(quote, budget)
        authorizationError(ctx, parsed)?.let { return OperationResult.Rejected(it) }
        return OperationResult.Ok(
            SourceEvidence(
                evidenceId = UUID.randomUUID().toString(),
                documentId = parsed.documentId,
                sectionId = locator.sectionId,
                contentRevision = parsed.revision,
                locator = locator,
                quote = trimmed,
                quoteSha256 = sha256Of(trimmed),
                sourceTitleSnapshot = document.title,
                extractorVersion = revisionRow.extractorVersion,
            ),
            operationId = UUID.randomUUID().toString(),
        )
    }

    /** Fixed-page evidence must be read back from the pinned page and, for OCR, its exact boxes. */
    private fun locateFixedQuote(
        blocks: List<ContentBlock>,
        section: ReaderSectionEntity,
        locator: ReaderLocator.Fixed,
    ): String? {
        val expected = locator.textQuote?.takeIf { it.isNotBlank() } ?: return null
        if (section.pageStart != locator.pageIndex) return null
        if (locator.rects.isEmpty()) {
            val pageText = blocks.joinToString("\n") { it.text }
            return expected.takeIf { pageText.contains(it) }
        }
        val pageBlocks = blocks.filter { it.fixedPageIndex == locator.pageIndex && it.fixedRect != null }
        val selected = locator.rects.map { rect ->
            pageBlocks.firstOrNull { block ->
                val actual = block.fixedRect ?: return@firstOrNull false
                closeRect(actual, rect)
            } ?: return null
        }
        if (selected.size != locator.rects.size) return null
        val actualQuote = selected.joinToString("\n") { it.text }
        return actualQuote.takeIf { it == expected }
    }

    private fun closeRect(left: NormalizedRect, right: NormalizedRect): Boolean =
        kotlin.math.abs(left.left - right.left) <= RECT_EPSILON &&
            kotlin.math.abs(left.top - right.top) <= RECT_EPSILON &&
            kotlin.math.abs(left.right - right.right) <= RECT_EPSILON &&
            kotlin.math.abs(left.bottom - right.bottom) <= RECT_EPSILON

    // ---- block 解析 ----

    private suspend fun parseBlocks(
        document: ReaderDocumentEntity,
        revision: String,
        section: ReaderSectionEntity,
        sourceBudget: SourceReadBudget,
    ): List<ContentBlock> {
        sourceBudget.checkpoint()
        val blocks = ReaderHtmlBlockParser.parse(
            html = sectionHtml(document, revision, section, sourceBudget),
            sectionId = section.id,
            pageStart = section.pageStart,
        ).runtimeBlocks
        sourceBudget.checkpoint()
        return blocks
    }

    private suspend fun sectionHtml(
        document: ReaderDocumentEntity,
        revision: String,
        section: ReaderSectionEntity,
        sourceBudget: SourceReadBudget,
    ): String {
        val row = requireNotNull(catalog.findContentRevision(document.id, revision))
        if (document.kind == "BUILTIN" && row.manifestPath.startsWith("asset:") && section.resourceHref.startsWith("asset:")) {
            val assetPath = section.resourceHref.removePrefix("asset:")
            return assets.open(assetPath).use { input -> sourceBudget.readAll(input).toString(StandardCharsets.UTF_8) }
        }
        if (document.kind == "BUILTIN") {
            verifiedBuiltInProjection(storage, document.id, row, catalog.findSections(document.id, revision), sourceBudget)
        }
        val file = storage.resolveFile(document.id, revision, section.resourceHref)
        return sourceBudget.readFile(file).toString(StandardCharsets.UTF_8)
    }

    private fun locateQuote(blocks: List<ContentBlock>, locator: ReaderLocator.Reflow): String {
        val exact = locator.exactQuote
        if (exact.isBlank()) return ""
        val byBlock = blocks.firstOrNull { it.blockId == locator.blockId }
        if (byBlock != null && byBlock.text.contains(exact)) return exact
        // 区间核验：canonical 区间必须落在块内且逐字匹配
        val inRange = blocks.firstOrNull { block ->
            block.canonicalStart <= locator.canonicalStart && block.canonicalEnd >= locator.canonicalEnd
        }
        if (inRange != null) {
            val start = locator.canonicalStart - inRange.canonicalStart
            val end = locator.canonicalEnd - inRange.canonicalStart
            if (start >= 0 && end <= inRange.text.length && end >= start) {
                val slice = inRange.text.substring(start, end)
                if (slice == exact) return exact
            }
        }
        return locateCrossBlockQuote(blocks, locator, exact)
    }

    /**
     * 跨块区间核验（LocatorMapping 对多块子块给出 first.canonicalStart..last.canonicalEnd）。
     *
     * parseBlocks 的 canonical 偏移按块间 "\n" 累计：每个块 offset += clean.length + 1，
     * 与 StructuredChunker 用 "\n" 拼接子块 rawText 的规则一致。因此这里按块顺序重建
     * 与 canonical 偏移一一对应的文本，再截取区间逐字比对 exactQuote。
     *
     * 只有在块区间连续（无空洞/重叠）、区间落在范围内且逐字相等时才通过；
     * 任何越界或不连续都返回空，保持“不匹配即拒绝”的核验语义。
     */
    private fun locateCrossBlockQuote(
        blocks: List<ContentBlock>,
        locator: ReaderLocator.Reflow,
        exact: String,
    ): String {
        if (blocks.isEmpty()) return ""
        val base = blocks.first().canonicalStart
        val start = locator.canonicalStart - base
        val end = locator.canonicalEnd - base
        if (start < 0 || end < start) return ""
        val joined = StringBuilder()
        var expectedStart = base
        blocks.forEachIndexed { index, block ->
            if (block.canonicalStart != expectedStart) return ""
            if (index > 0) joined.append('\n')
            joined.append(block.text)
            expectedStart = block.canonicalEnd + 1
        }
        if (end > joined.length) return ""
        return if (joined.substring(start, end) == exact) exact else ""
    }

    private fun locateQuote(blocks: List<ContentBlock>, locator: ReaderLocator.Note): String {
        val start = locator.canonicalStart ?: return ""
        val end = locator.canonicalEnd ?: return ""
        val block = blocks.firstOrNull { it.blockId == locator.blockId } ?: return ""
        val localStart = start - block.canonicalStart
        val localEnd = end - block.canonicalStart
        if (localStart < 0 || localEnd > block.text.length || localEnd < localStart) return ""
        return block.text.substring(localStart, localEnd)
    }

    private fun trimToBudget(text: String, maxTokens: Int): String {
        if (text.length <= maxTokens) return text
        var end = maxTokens
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end -= 1
        return text.substring(0, end)
    }

    private fun ReaderSectionEntity.toDescriptor(documentId: String, revision: String) = SectionDescriptor(
        id = id,
        documentId = documentId,
        contentRevision = revision,
        orderIndex = orderIndex,
        title = title,
        resourceHref = resourceHref,
        charCount = charCount,
        pageStart = pageStart,
        pageEnd = pageEnd,
    )

    private fun ReaderDocumentEntity.toAccessRecord() = ReaderDocumentAccessRecord(
        documentId = id,
        kind = kind,
        ownerUserId = ownerUserId,
        contentRevision = contentRevision,
        accessEpoch = accessEpoch,
        importStatus = importStatus,
        deletedAt = deletedAt,
    )

    /** Malformed offsets must never silently restart a paginated read. */
    private fun parseCursor(cursor: String?): Int? {
        if (cursor == null) return 0
        if (cursor.isEmpty() || cursor.any { it !in '0'..'9' }) return null
        return cursor.toIntOrNull()
    }

    private suspend fun authorizationError(ctx: AccessContext, snapshot: SnapshotId): String? {
        val current = catalog.findDocumentIncludingDeleted(snapshot.documentId) ?: return "NOT_FOUND"
        val allowed = ReaderAccessPolicy.canRead(ctx.userId, current.toAccessRecord())
        if (allowed is ReaderAccessDecision.Denied) return allowed.code
        if (current.accessEpoch != snapshot.accessEpoch) return "ACCESS_EPOCH_MISMATCH"
        if (!historicalReading && current.contentRevision != snapshot.revision) return "REVISION_MISSING"
        if (catalog.findContentRevision(snapshot.documentId, snapshot.revision) == null) return "REVISION_MISSING"
        return null
    }

    private fun snapshotIdOf(documentId: String, revision: String, userId: Long, accessEpoch: Long): String =
        "snap|$userId|$documentId|$revision|$accessEpoch"

    private fun parseSnapshotId(value: String): SnapshotId? {
        val parts = value.split("|")
        if (parts.size != 5 || parts[0] != "snap") return null
        val userId = parts[1].toLongOrNull() ?: return null
        if (parts[2].isBlank() || parts[3].isBlank()) return null
        val epoch = parts[4].toLongOrNull()?.takeIf { it >= 0 && it.toString() == parts[4] } ?: return null
        return SnapshotId(userId, parts[2], parts[3], epoch)
    }

    private fun sha256Of(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class SnapshotId(val userId: Long, val documentId: String, val revision: String, val accessEpoch: Long)

    companion object {
        /** Only source navigation uses this reader; never inject it into retrieval or registration. */
        fun forHistoricalReading(database: AppDatabase, storage: DocumentStorage, assets: AssetManager) =
            RoomDocumentAccess(database, storage, assets, true)
        const val MAX_SNAPSHOT_SECTIONS = 64
        private const val RECT_EPSILON = 0.00001f
    }
}
