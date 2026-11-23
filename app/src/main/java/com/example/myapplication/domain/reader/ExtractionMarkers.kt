package com.example.myapplication.domain.reader

/**
 * 解析期写入 HTML、读取期用来判定抽取质量的标记。
 *
 * 背景：扫描 PDF 页没有文字层，解析器只写一句“此页为扫描图像”的提示。
 * 这句提示**不是原文**——若被当成正文入库，会被分块、检索、出题引用，
 * 形成“假装书里有这句话”的证据。因此提示必须与正文分开标记：
 *  - 解析器在页容器写 `data-extraction="no-text"`；
 *  - DocumentAccess 读到祖先标记时，该页所有块记为 [ExtractionQuality.NO_TEXT]；
 *  - 分块器跳过 NO_TEXT 块；随堂测按 NO_TEXT 判 EXTRACTION_BLOCKED。
 */
object ExtractionMarkers {
    const val ATTR = "data-extraction"

    /** 有真实文字层。 */
    const val TEXT = "text"

    /** 无文字层（扫描页/纯图片），提示文案不得当正文。 */
    const val NO_TEXT = "no-text"

    fun isNoText(value: String?): Boolean = value == NO_TEXT
}
