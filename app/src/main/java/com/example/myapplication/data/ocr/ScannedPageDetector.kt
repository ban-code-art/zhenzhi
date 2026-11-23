package com.example.myapplication.data.ocr

/**
 * 判断一页当前是否缺文字层。
 *
 * 只看当前代次 HTML 的抽取标记，不靠“文本短”猜测：有文字层的页即使只有页码，
 * 也不该被 OCR 覆盖；扫描页必须由解析器显式标 `data-extraction="no-text"`。
 */
object ScannedPageDetector {
    private const val NO_TEXT_MARKER = "data-extraction=\"no-text\""

    fun needsOcr(pageHtml: String): Boolean = pageHtml.contains(NO_TEXT_MARKER)
}
