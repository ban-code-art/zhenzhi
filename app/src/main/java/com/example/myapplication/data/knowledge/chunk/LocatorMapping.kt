package com.example.myapplication.data.knowledge.chunk

import com.example.myapplication.domain.reader.ContentBlock
import com.example.myapplication.domain.reader.ReaderLocator

/**
 * 块 → Locator 映射（设计 §5.1/§11）。
 *
 * - Reflow locator 固定在 (documentId, sectionId, contentRevision) 与首块 blockId；
 * - canonical 区间取块组首块 start 到末块 end，半开区间；
 * - parentKey 是 DocumentAccess 可解析的 (revision, sectionId, block 范围) 锚点，
 *   不是另一张 parent 表的外键。
 */
object LocatorMapping {
    fun locatorFor(
        documentId: String,
        sectionId: String,
        contentRevision: String,
        progression: Float?,
        blocks: List<ContentBlock>,
        quote: String,
        allowFixedPage: Boolean,
    ): ReaderLocator {
        require(blocks.isNotEmpty()) { "locator requires at least one block" }
        val pageIndexes = blocks.map { it.fixedPageIndex }
        val pageIndex = pageIndexes.firstOrNull()
        val rects = blocks.mapNotNull { it.fixedRect }
        if (allowFixedPage && pageIndex != null && pageIndexes.all { it == pageIndex } && rects.size == blocks.size) {
            return ReaderLocator.Fixed(
                documentId = documentId,
                sectionId = sectionId,
                contentRevision = contentRevision,
                progression = progression,
                pageIndex = pageIndex,
                pageBox = com.example.myapplication.domain.reader.NormalizedRect(0f, 0f, 1f, 1f),
                rotationDegrees = 0,
                rects = rects.distinct(),
                textQuote = quote,
            )
        }
        return reflowLocator(documentId, sectionId, contentRevision, progression, blocks, quote)
    }

    fun reflowLocator(
        documentId: String,
        sectionId: String,
        contentRevision: String,
        progression: Float?,
        blocks: List<ContentBlock>,
        quote: String,
    ): ReaderLocator.Reflow {
        require(blocks.isNotEmpty()) { "locator requires at least one block" }
        val first = blocks.first()
        val last = blocks.last()
        return ReaderLocator.Reflow(
            documentId = documentId,
            sectionId = sectionId,
            contentRevision = contentRevision,
            progression = progression,
            nativeLocator = first.nativeLocator,
            exactQuote = quote,
            prefix = null,
            suffix = null,
            canonicalStart = first.canonicalStart,
            canonicalEnd = last.canonicalEnd,
            blockId = first.blockId,
        )
    }
}

/** parentKey 编解码：`revision|sectionId|firstBlockId|lastBlockId`。 */
object ParentAnchor {
    private const val SEPARATOR = "|"

    fun encode(contentRevision: String, sectionId: String, firstBlockId: String, lastBlockId: String): String =
        listOf(contentRevision, sectionId, firstBlockId, lastBlockId).joinToString(SEPARATOR)

    data class Decoded(
        val contentRevision: String,
        val sectionId: String,
        val firstBlockId: String,
        val lastBlockId: String,
    )

    fun decode(value: String): Decoded? {
        val parts = value.split(SEPARATOR)
        if (parts.size != 4) return null
        if (parts.any { it.isBlank() }) return null
        return Decoded(parts[0], parts[1], parts[2], parts[3])
    }
}
