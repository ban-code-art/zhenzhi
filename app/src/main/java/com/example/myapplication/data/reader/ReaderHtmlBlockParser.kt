package com.example.myapplication.data.reader

import com.example.myapplication.domain.reader.BlockKind
import com.example.myapplication.domain.reader.ContentBlock
import com.example.myapplication.domain.reader.ExtractionMarkers
import com.example.myapplication.domain.reader.ExtractionQuality
import com.example.myapplication.domain.reader.NormalizedRect
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class ReaderHtmlBlockParseResult(
    val runtimeBlocks: List<ContentBlock>,
    val emittedRootIds: Map<String, String>,
    val duplicateDomIds: Set<String>,
    val canonicalText: String,
)

/** Single Room/package HTML algorithm; aliases describe emitted DOM roots without changing blocks. */
object ReaderHtmlBlockParser {
    const val VERSION = "reader-html-v1"

    fun parse(html: String, sectionId: String, pageStart: Int? = null): ReaderHtmlBlockParseResult {
        val doc = Jsoup.parse(html)
        val headingPath = mutableListOf<String>()
        val blocks = mutableListOf<ContentBlock>()
        val rootIds = linkedMapOf<String, String>()
        val duplicateIds = doc.allElements.map { it.id() }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        var offset = 0
        var index = 0

        fun emit(
            element: Element,
            kind: BlockKind,
            text: String,
            quality: ExtractionQuality = ExtractionQuality.VERIFIED_TEXT,
            rect: String = "",
        ) {
            val clean = text.trim()
            if (clean.isEmpty()) return
            val blockId = "${sectionId}#b${index}"
            blocks += ContentBlock(
                blockId = blockId,
                sectionId = sectionId,
                kind = kind,
                canonicalStart = offset,
                canonicalEnd = offset + clean.length,
                text = clean,
                headingPath = headingPath.toList(),
                // OCR 行把归一化矩形带进 nativeLocator，供原页高亮/引用；原生文字层保持原格式。
                nativeLocator = buildString {
                    append("section:").append(sectionId).append(":block:").append(index)
                    if (rect.isNotBlank()) append(":rect:").append(rect)
                },
                extractionQuality = quality,
                fixedPageIndex = pageStart.takeIf { rect.isNotBlank() },
                fixedRect = parseNormalizedRect(rect),
            )
            element.id().takeIf { it.isNotBlank() }?.let { rootIds[it] = blockId }
            offset += clean.length + 1 // 块间以换行分隔
            index += 1
        }

        val body = doc.body()
        body.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, figcaption, table, img").forEach { element ->
            val ocrPage = element.parents().any { it.attr("data-ocr") == "1" }
            val noTextPage = element.parents().any { parent ->
                ExtractionMarkers.isNoText(parent.attr(ExtractionMarkers.ATTR))
            }
            val quality = if (noTextPage) ExtractionQuality.NO_TEXT else ExtractionQuality.VERIFIED_TEXT
            when (element.tagName().lowercase()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    if (ocrPage) return@forEach // OCR 页标题由 section/页序提供，不作为无矩形出处证据。
                    val level = element.tagName()[1].digitToInt()
                    while (headingPath.size >= level) headingPath.removeAt(headingPath.size - 1)
                    headingPath += element.text()
                    emit(element, BlockKind.HEADING, element.text(), quality)
                }
                "p", "blockquote", "pre" -> emit(
                    element,
                    BlockKind.PARAGRAPH,
                    element.text(),
                    quality,
                    rect = element.attr("data-rect"),
                )
                "li" -> emit(element, BlockKind.LIST, element.text(), quality)
                "figcaption" -> emit(element, BlockKind.CAPTION, element.text(), quality)
                "table" -> emit(element, BlockKind.TABLE, tableText(element), quality)
                "img" -> emit(element, BlockKind.IMAGE, element.attr("alt").ifBlank { "[image]" }, quality)
                else -> Unit
            }
        }
        // 扫描页的提示文案不是原文：即使没有任何正文块，也不做 body.text() 兜底。
        val hasNoTextMarker = body.select("[${ExtractionMarkers.ATTR}=${ExtractionMarkers.NO_TEXT}]").isNotEmpty()
        if (blocks.isEmpty() && !hasNoTextMarker) {
            val fallback = body.text().trim()
            if (fallback.isNotEmpty()) emit(body, BlockKind.PARAGRAPH, fallback)
        }
        return ReaderHtmlBlockParseResult(blocks.toList(), rootIds.toMap(), duplicateIds.toSet(), blocks.joinToString("\n") { it.text })
    }

    private fun parseNormalizedRect(raw: String): NormalizedRect? {
        if (raw.isBlank()) return null
        val values = raw.split(',').map { it.trim().toFloatOrNull() ?: return null }
        if (values.size != 4 || values.any { !it.isFinite() || it !in 0f..1f }) return null
        val (left, top, right, bottom) = values
        if (right <= left || bottom <= top) return null
        return NormalizedRect(left, top, right, bottom)
    }

    /** 表格转制表符文本：首行为表头，行内单元格以 " | " 连接。 */
    private fun tableText(table: Element): String {
        val rows = table.select("tr")
        if (rows.isEmpty()) return table.text()
        return rows.joinToString("\n") { row ->
            row.select("th, td").joinToString(" | ") { cell -> cell.text().trim() }
        }
    }

}
