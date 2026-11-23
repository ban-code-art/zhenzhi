package com.example.myapplication.data.ocr

import com.example.myapplication.data.reader.importer.ParserIO
import com.example.myapplication.domain.ocr.OcrPageResult
import java.util.Locale

/**
 * 把一页 OCR 结果写成可发布的 section HTML。
 *
 * 事实约束：
 *  - 页容器标 `data-extraction="text"`，表示这页现在有可引用文本；
 *  - 每行一个 `<p>`，带归一化 `data-rect`，供原页定位/高亮；
 *  - 标 `data-ocr="1"` 与模型版本，引用时可显示“OCR 抽取”而不是假装原生文字层；
 *  - 只接受 VERIFIED_TEXT；需复核的结果不在这里发布。
 */
object OcrPageHtml {
    const val ATTR_OCR = "data-ocr"
    const val ATTR_RECT = "data-rect"
    const val ATTR_MODEL = "data-ocr-model"
    const val ATTR_HUMAN_REVIEWED = "data-ocr-human-reviewed"

    fun body(
        pageIndex: Int,
        result: OcrPageResult,
        modelVersion: String,
        humanReviewed: Boolean = false,
    ): String {
        require(pageIndex >= 0) { "pageIndex must be >= 0" }
        require(result.boxes.isNotEmpty() || result.text.isNotBlank()) { "OCR result is empty" }
        val pageNumber = pageIndex + 1
        val paragraphs = buildString {
            val boxes = result.boxes.sortedBy { it.readingOrder }
            if (boxes.isEmpty()) {
                append("<p ").append(ATTR_OCR).append("=\"1\" data-ocr-line=\"0\">")
                append(ParserIO.escapeHtml(result.text.trim()))
                append("</p>")
            } else {
                boxes.forEachIndexed { index, box ->
                    val text = box.text.trim()
                    if (text.isEmpty()) return@forEachIndexed
                    append("<p ").append(ATTR_OCR).append("=\"1\" data-ocr-line=\"")
                    append(index).append("\" ").append(ATTR_RECT).append("=\"")
                    append(formatRect(box.rect.left, box.rect.top, box.rect.right, box.rect.bottom))
                    append("\">").append(ParserIO.escapeHtml(text)).append("</p>")
                }
            }
        }
        return buildString {
            append("<section data-extraction=\"text\" ")
            append(ATTR_OCR).append("=\"1\" data-ocr-page=\"").append(pageIndex).append("\" ")
            append(ATTR_MODEL).append("=\"").append(ParserIO.escapeHtml(modelVersion)).append("\" ")
            append(ATTR_HUMAN_REVIEWED).append("=\"").append(if (humanReviewed) "true" else "false").append("\">")
            append("<h1>第 ").append(pageNumber).append(" 页</h1>")
            append(paragraphs)
            append("</section>")
        }
    }

    /** 固定 5 位小数，避免不同 Locale 产生逗号小数点。 */
    fun formatRect(left: Float, top: Float, right: Float, bottom: Float): String =
        String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.5f", left, top, right, bottom)
}
