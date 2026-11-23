package com.example.myapplication.domain.ocr

/**
 * PDF 页级 OCR 规划（W5-03）：只回答“哪些页缺文本、整本处于什么可用性”，
 * 不做识别、不碰数据库，便于离线单测。
 *
 * 判定口径：
 *  - 一页字符数低于 [MIN_TEXT_CHARS] 视为“实际无文字层”（零星字符常是页码/水印）；
 *  - 全部页有文本 → [OcrAvailability.TEXT_LAYER]；
 *  - 任意页缺文本 → [OcrAvailability.OCR_PENDING]（混排也按待 OCR 处理，文字页仍可检索）；
 *  - 无页 → [OcrAvailability.NONE]。
 *
 * OCR_READY / OCR_REVIEW_REQUIRED 由识别结果发布后另行设置，本规划器不臆测。
 */
data class PdfPageText(
    val pageIndex: Int,
    val sectionId: String,
    val charCount: Int,
) {
    init {
        require(pageIndex >= 0) { "pageIndex must be >= 0" }
        require(charCount >= 0) { "charCount must be >= 0" }
    }
}

data class OcrPlan(
    val availability: OcrAvailability,
    val pagesNeedingOcr: List<Int>,
    val textPages: Int,
    val emptyPages: Int,
) {
    val totalPages: Int get() = textPages + emptyPages
}

object PdfOcrPlanner {
    /** 少于此字符数的页按“无文字层”处理。 */
    const val MIN_TEXT_CHARS = 8

    fun plan(pages: List<PdfPageText>): OcrPlan {
        if (pages.isEmpty()) {
            return OcrPlan(OcrAvailability.NONE, emptyList(), textPages = 0, emptyPages = 0)
        }
        val needing = pages.filter { it.charCount < MIN_TEXT_CHARS }
        val textPages = pages.size - needing.size
        val availability = if (needing.isEmpty()) OcrAvailability.TEXT_LAYER else OcrAvailability.OCR_PENDING
        return OcrPlan(
            availability = availability,
            pagesNeedingOcr = needing.map { it.pageIndex }.sorted(),
            textPages = textPages,
            emptyPages = needing.size,
        )
    }
}
