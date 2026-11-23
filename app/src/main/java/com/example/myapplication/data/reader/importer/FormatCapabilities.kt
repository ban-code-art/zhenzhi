package com.example.myapplication.data.reader.importer

/**
 * 格式能力声明（P5-01 验收口径）。
 *
 * 设计 §2：「导入器通过 capabilities 声明可阅读、可选字、可检索、需OCR；无文字 CBZ 不显示
 * 无效的文本选择按钮」。因此能力必须由**格式**决定、由 UI 读取，而不是让每个页面自己猜。
 *
 * 注意：能力是"这一格式通常能提供什么"，不是"这本书解析出来一定有文字"。
 * 单本书的实际可用性仍以解析结果（section 是否为空、charCount 是否为 0）为准。
 */
data class FormatCapabilities(
    /** 能否进入阅读器（否则只能展示不支持的说明）。 */
    val readable: Boolean = true,
    /** 是否有目录/大纲。 */
    val outline: Boolean = true,
    /** 是否可选字（决定是否显示选字/高亮/批注入口）。 */
    val selectableText: Boolean = true,
    /** 是否参与书内搜索。 */
    val searchable: Boolean = true,
    /** 是否进入知识索引（RAG / 随堂测出处）。 */
    val knowledgeIndexable: Boolean = true,
    /** 需要 OCR 才有可检索文本（扫描 PDF / 图片漫画）。 */
    val requiresOcr: Boolean = false,
    /** 是否含图片资源。 */
    val images: Boolean = true,
    /** 固定版式（按页/按图），不做重排。 */
    val fixedLayout: Boolean = false,
    /** 面向用户的一句话说明；UI 在书架/阅读页可展示，null 表示无需说明。 */
    val note: String? = null,
)

object FormatRegistry {
    fun capabilitiesOf(format: ReaderFormat): FormatCapabilities = when (format) {
        ReaderFormat.EPUB -> FormatCapabilities()
        ReaderFormat.PDF -> FormatCapabilities(
            // 一期 PDF 提供文本层，可选中；版式固定不做重排。
            fixedLayout = true,
        )
        ReaderFormat.TXT -> FormatCapabilities(images = false)
        ReaderFormat.MARKDOWN -> FormatCapabilities(images = false)
        ReaderFormat.HTML -> FormatCapabilities(outline = false)
        ReaderFormat.MHTML -> FormatCapabilities(images = false, outline = false)
        ReaderFormat.FB2 -> FormatCapabilities()
        ReaderFormat.MOBI -> FormatCapabilities()
        ReaderFormat.AZW -> FormatCapabilities()
        ReaderFormat.AZW3 -> FormatCapabilities()
        ReaderFormat.CBZ -> FormatCapabilities(
            outline = false,
            selectableText = false,
            searchable = false,
            knowledgeIndexable = false,
            requiresOcr = true,
            fixedLayout = true,
            note = "图片漫画：无文字层，暂不参与检索与出题",
        )
        ReaderFormat.UNKNOWN -> FormatCapabilities(
            readable = false,
            outline = false,
            selectableText = false,
            searchable = false,
            knowledgeIndexable = false,
            images = false,
            note = "不支持的格式",
        )
    }

    /** 导入落盘时的源文件扩展名（source/source.<ext>）。 */
    fun extensionOf(format: ReaderFormat): String = when (format) {
        ReaderFormat.EPUB -> "epub"
        ReaderFormat.PDF -> "pdf"
        ReaderFormat.TXT -> "txt"
        ReaderFormat.MARKDOWN -> "md"
        ReaderFormat.HTML -> "html"
        ReaderFormat.MHTML -> "mht"
        ReaderFormat.FB2 -> "fb2"
        ReaderFormat.MOBI -> "mobi"
        ReaderFormat.AZW -> "azw"
        ReaderFormat.AZW3 -> "azw3"
        ReaderFormat.CBZ -> "cbz"
        ReaderFormat.UNKNOWN -> "bin"
    }

    /** 书架徽章文案；与 [ShelfScreen] 的展示口径一致。 */
    fun badgeOf(format: ReaderFormat): String = when (format) {
        ReaderFormat.EPUB -> "EPUB"
        ReaderFormat.PDF -> "PDF"
        ReaderFormat.TXT -> "TXT"
        ReaderFormat.MARKDOWN -> "MD"
        ReaderFormat.HTML -> "HTML"
        ReaderFormat.MHTML -> "MHT"
        ReaderFormat.FB2 -> "FB2"
        ReaderFormat.MOBI -> "MOBI"
        ReaderFormat.AZW -> "AZW"
        ReaderFormat.AZW3 -> "AZW3"
        ReaderFormat.CBZ -> "CBZ"
        ReaderFormat.UNKNOWN -> "BOOK"
    }
}
