package com.example.myapplication.data.reader.importer

import com.example.myapplication.domain.reader.ExtractionMarkers

/**
 * PDF 页 HTML 生成（纯函数，便于离线回归）。
 *
 * 关键：扫描页的“暂无可提取文本”只是提示，必须整页标 `no-text`，
 * 让 DocumentAccess/分块器把它排除在正文之外。
 */
internal object PdfPageMarkup {
    fun hasTextLayer(pageText: String): Boolean = pageText.isNotBlank()

    fun body(pageNumber: Int, pageText: String): String {
        val hasText = hasTextLayer(pageText)
        return buildString {
            append("<section ").append(ExtractionMarkers.ATTR).append("=\"")
            append(if (hasText) ExtractionMarkers.TEXT else ExtractionMarkers.NO_TEXT)
            append("\">")
            append("<h1>第 ").append(pageNumber).append(" 页</h1>")
            if (hasText) {
                append("<p>").append(ParserIO.escapeHtml(pageText).replace("\n", "<br>")).append("</p>")
            } else {
                append("<div class=\"scan-notice\" style=\"user-select:none;color:#5f6f68\">")
                append(PdfDocumentParser.SCANNED_PAGE_NOTICE)
                append("</div>")
            }
            append("</section>")
        }
    }
}
