package com.example.myapplication.data.knowledge.chunk

import com.example.myapplication.domain.reader.BlockKind
import com.example.myapplication.domain.reader.ContentBlock
import com.example.myapplication.domain.reader.ExtractionQuality
import com.example.myapplication.domain.reader.ReaderLocator
import java.security.MessageDigest

/**
 * 结构化分块器（设计 §5）。
 *
 * 输入是 DocumentAccess 的不可变 block 视图（同一 contentRevision），输出父子块草稿：
 *  - 章节/标题优先分割，父块 1024–2048 token，子块目标 384 / 最小 128 / 最大 768；
 *  - 子块间按整块回带重叠（约 48 token），保证 Locator 区间精确可核验；
 *  - 表格超长按行切并复制表头；段落按句切，切不动再按 token 上限硬切；
 *  - 短定义/表格单行不丢弃：任何输入 block 的文本必须完整落在至少一个子块里；
 *  - 标题上下文只进 embeddingText，不改 rawText；
 *  - OCR 低置信内容带 warning，不静默当高可信文本。
 */
class StructuredChunker(
    private val budget: ChunkBudget = ChunkBudget.DEFAULT,
    val chunkerVersion: String = CHUNKER_VERSION,
) {
    data class ChunkDraft(
        val sectionId: String,
        val blockIds: List<String>,
        val parentKey: String,
        val ordinal: Int,
        val rawText: String,
        val searchText: String,
        val embeddingText: String,
        val locator: ReaderLocator,
        val tokenCount: Int,
        val contentHash: String,
        val extractionQuality: ExtractionQuality,
        val warnings: List<String> = emptyList(),
    )

    private data class Piece(
        val block: ContentBlock,
        val text: String,
        /** 该片段是否是整块（只有整块才参与回带重叠，保证区间精确）。 */
        val wholeBlock: Boolean,
        val tableHeader: String? = null,
    )

    fun chunk(
        documentId: String,
        sectionId: String,
        contentRevision: String,
        blocks: List<ContentBlock>,
        progression: Float? = null,
    ): List<ChunkDraft> {
        val usable = blocks.filter {
            it.sectionId == sectionId &&
                it.text.isNotBlank() &&
                // 无文字层（扫描页提示、纯图片）不得进入索引：宁可没有块，也不造假证据。
                // 待复核 OCR 仍可带 warning 入库，但是否允许出题由上层质量门禁判定；
                // 发布链路本身只发布 VERIFIED_TEXT 的 OCR 页。
                it.extractionQuality != ExtractionQuality.NO_TEXT
        }
        if (usable.isEmpty()) return emptyList()

        val drafts = mutableListOf<ChunkDraft>()
        var ordinal = 0
        groupParents(usable).forEach { parentBlocks ->
            buildChildren(parentBlocks).forEach { child ->
                drafts += buildDraft(
                    documentId = documentId,
                    sectionId = sectionId,
                    contentRevision = contentRevision,
                    progression = progression,
                    ordinal = ordinal++,
                    child = child,
                )
            }
        }
        return drafts
    }

    /** 超长请求被服务端拒绝（如 413）后的缩块：把单个草稿再切成不超过 [maxTokens] 的子块。 */
    fun resplitForLimit(draft: ChunkDraft, maxTokens: Int): List<ChunkDraft> {
        require(maxTokens >= budget.childMin) { "maxTokens must be >= childMin" }
        if (draft.tokenCount <= maxTokens) return listOf(draft)
        val pieces = splitText(draft.rawText, draft.sectionId, draft.blockIds.firstOrNull().orEmpty(), maxTokens)
        var ordinal = draft.ordinal
        return pieces.map { text ->
            draft.copy(
                ordinal = ordinal++,
                rawText = text,
                searchText = text,
                embeddingText = text,
                tokenCount = budget.estimate(text),
                contentHash = contentHashOf(text),
                warnings = draft.warnings + "RESPLIT_FOR_LIMIT",
            )
        }
    }

    // ---- 父块分组：标题边界优先，其次父块 token 预算 ----

    private fun groupParents(blocks: List<ContentBlock>): List<List<ContentBlock>> {
        val parents = mutableListOf<MutableList<ContentBlock>>()
        var current = mutableListOf<ContentBlock>()
        var currentTokens = 0
        blocks.forEach { block ->
            val tokens = budget.estimate(block.text)
            val headingBoundary = block.kind == BlockKind.HEADING && current.isNotEmpty()
            val overBudget = currentTokens + tokens > budget.parentMax
            if (headingBoundary || overBudget) {
                if (current.isNotEmpty()) parents += current
                current = mutableListOf()
                currentTokens = 0
            }
            current += block
            currentTokens += tokens
        }
        if (current.isNotEmpty()) parents += current
        return parents
    }

    // ---- 子块：目标/最小/最大 + 整块回带重叠 ----

    private fun buildChildren(parentBlocks: List<ContentBlock>): List<List<Piece>> {
        val pieces = parentBlocks.flatMap(::splitBlock)
        if (pieces.isEmpty()) return emptyList()
        val children = mutableListOf<MutableList<Piece>>()
        var current = mutableListOf<Piece>()
        var currentTokens = 0

        pieces.forEach { piece ->
            val tokens = budget.estimate(piece.text)
            val wouldExceedMax = currentTokens + tokens > budget.childMax
            val reachedTarget = currentTokens >= budget.childTarget
            if (current.isNotEmpty() && (wouldExceedMax || reachedTarget)) {
                children += current
                current = mutableListOf()
                currentTokens = 0
                val carried = overlapOf(children.last())
                val carriedTokens = carried.sumOf { budget.estimate(it.text) }
                // 回带后不得超过 childMax；会超就放弃这次重叠。
                if (carriedTokens + tokens <= budget.childMax) {
                    carried.forEach { item ->
                        current += item
                        currentTokens += budget.estimate(item.text)
                    }
                }
            }
            current += piece
            currentTokens += tokens
        }
        if (current.isNotEmpty()) children += current
        return children
    }

    /** 从上一个子块尾部整块回带，累计到约 overlap token；部分片段不参与，避免区间不准。 */
    private fun overlapOf(previous: List<Piece>): List<Piece> {
        if (budget.childOverlap <= 0) return emptyList()
        val carried = mutableListOf<Piece>()
        var tokens = 0
        for (piece in previous.asReversed()) {
            if (!piece.wholeBlock) break
            if (tokens >= budget.childOverlap) break
            carried.add(0, piece)
            tokens += budget.estimate(piece.text)
            if (tokens >= budget.childOverlap) break
        }
        // 回带不能吃掉整个子块，否则会无限重复
        return if (carried.size == previous.size) emptyList() else carried
    }

    private fun buildDraft(
        documentId: String,
        sectionId: String,
        contentRevision: String,
        progression: Float?,
        ordinal: Int,
        child: List<Piece>,
    ): ChunkDraft {
        val rawText = child.joinToString("\n") { it.text }
        val headingPath = child.first().block.headingPath
        val headingContext = headingPath.joinToString(" > ")
        val embeddingText = buildString {
            if (headingContext.isNotBlank()) {
                append(headingContext)
                append('\n')
            }
            append(rawText)
        }
        val quality = child.map { it.block.extractionQuality }
            .firstOrNull { it != ExtractionQuality.VERIFIED_TEXT } ?: ExtractionQuality.VERIFIED_TEXT
        val warnings = buildList {
            if (quality == ExtractionQuality.OCR_REVIEW_REQUIRED) add("OCR_REVIEW_REQUIRED")
            if (child.any { it.tableHeader != null }) add("TABLE_HEADER_COPIED")
        }
        return ChunkDraft(
            sectionId = sectionId,
            blockIds = child.map { it.block.blockId }.distinct(),
            parentKey = ParentAnchor.encode(
                contentRevision = contentRevision,
                sectionId = sectionId,
                firstBlockId = child.first().block.blockId,
                lastBlockId = child.last().block.blockId,
            ),
            ordinal = ordinal,
            rawText = rawText,
            searchText = if (headingContext.isBlank()) rawText else "$headingContext\n$rawText",
            embeddingText = embeddingText,
            locator = LocatorMapping.locatorFor(
                documentId = documentId,
                sectionId = sectionId,
                contentRevision = contentRevision,
                progression = progression,
                blocks = child.map { it.block }.distinctBy { it.blockId },
                quote = rawText,
                allowFixedPage = child.all { it.wholeBlock },
            ),
            tokenCount = budget.estimate(rawText),
            contentHash = contentHashOf(rawText),
            extractionQuality = quality,
            warnings = warnings,
        )
    }

    // ---- 块切分：表格按行复制表头；文本按句；最后按 token 上限硬切 ----

    private fun splitBlock(block: ContentBlock): List<Piece> {
        val tokens = budget.estimate(block.text)
        if (tokens <= budget.childMax) return listOf(Piece(block, block.text, wholeBlock = true))
        return when (block.kind) {
            BlockKind.TABLE -> splitTable(block)
            else -> splitText(block.text, block.sectionId, block.blockId, budget.childMax)
                .map { Piece(block, it, wholeBlock = it == block.text) }
        }
    }

    private fun splitTable(block: ContentBlock): List<Piece> {
        val lines = block.text.split('\n')
        if (lines.size <= 1) {
            return splitText(block.text, block.sectionId, block.blockId, budget.childMax)
                .map { Piece(block, it, wholeBlock = it == block.text) }
        }
        val header = lines.first()
        val rows = lines.drop(1)
        val pieces = mutableListOf<Piece>()
        var currentRows = mutableListOf<String>()
        var currentTokens = budget.estimate(header)

        fun flush() {
            if (currentRows.isEmpty()) return
            val text = (listOf(header) + currentRows).joinToString("\n")
            pieces += Piece(block, text, wholeBlock = false, tableHeader = header)
            currentRows = mutableListOf()
            currentTokens = budget.estimate(header)
        }

        rows.forEach { row ->
            val rowTokens = budget.estimate(row)
            if (rowTokens > budget.childMax) {
                flush()
                splitText(row, block.sectionId, block.blockId, budget.childMax - budget.estimate(header))
                    .forEach { part ->
                        pieces += Piece(block, "$header\n$part", wholeBlock = false, tableHeader = header)
                    }
                return@forEach
            }
            if (currentRows.isNotEmpty() && currentTokens + rowTokens > budget.childMax) flush()
            currentRows += row
            currentTokens += rowTokens
        }
        flush()
        return pieces
    }

    /** 按句切；单句仍超限时按 token 上限硬切；拼接结果与原文逐字一致。 */
    private fun splitText(text: String, sectionId: String, blockId: String, maxTokens: Int): List<String> {
        if (budget.estimate(text) <= maxTokens) return listOf(text)
        val units = sentenceSplit(text)
        val result = mutableListOf<String>()
        val buffer = StringBuilder()
        units.forEach { unit ->
            if (buffer.isNotEmpty() && budget.estimate(buffer.toString() + unit) > maxTokens) {
                result += buffer.toString()
                buffer.clear()
            }
            if (budget.estimate(unit) > maxTokens) {
                if (buffer.isNotEmpty()) {
                    result += buffer.toString()
                    buffer.clear()
                }
                result += hardSplit(unit, maxTokens)
            } else {
                buffer.append(unit)
            }
        }
        if (buffer.isNotEmpty()) result += buffer.toString()
        return result.filter { it.isNotEmpty() }
    }

    private fun sentenceSplit(text: String): List<String> {
        val units = mutableListOf<String>()
        val buffer = StringBuilder()
        text.forEach { ch ->
            buffer.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '\n' || ch == '.' || ch == '!' || ch == '?') {
                units += buffer.toString()
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) units += buffer.toString()
        return units
    }

    private fun hardSplit(text: String, maxTokens: Int): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = start
            var lastGood = start
            while (end < text.length) {
                val next = if (Character.isHighSurrogate(text[end]) && end + 1 < text.length) end + 2 else end + 1
                if (budget.estimate(text.substring(start, next)) > maxTokens) break
                lastGood = next
                end = next
            }
            if (lastGood == start) lastGood = if (Character.isHighSurrogate(text[start]) && start + 1 < text.length) start + 2 else start + 1
            parts += text.substring(start, lastGood)
            start = lastGood
        }
        return parts
    }

    companion object {
        const val CHUNKER_VERSION = "structured-v1"

        fun contentHashOf(text: String): String {
            val normalized = text.replace("\r\n", "\n").trim()
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
