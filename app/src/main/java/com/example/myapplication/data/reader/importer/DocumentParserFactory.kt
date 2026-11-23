package com.example.myapplication.data.reader.importer

/**
 * 格式 → 解析器映射。
 *
 * 第五期新增的容器/XML 格式各自有独立适配器（见 P5-01）；未接线前显式抛
 * UNSUPPORTED_FORMAT，不允许"识别成某格式但解析器不存在"这种半吊子状态。
 */
object DocumentParserFactory {
    fun create(format: ReaderFormat): DocumentParser = when (format) {
        ReaderFormat.EPUB -> EpubDocumentParser()
        ReaderFormat.PDF -> PdfDocumentParser()
        ReaderFormat.TXT -> PlainTextDocumentParser()
        ReaderFormat.MARKDOWN -> MarkdownDocumentParser()
        ReaderFormat.HTML -> HtmlArchiveDocumentParser()
        ReaderFormat.MHTML -> HtmlArchiveDocumentParser()
        ReaderFormat.FB2 -> Fb2DocumentParser()
        ReaderFormat.MOBI, ReaderFormat.AZW, ReaderFormat.AZW3 -> LegacyEbookDocumentParser()
        ReaderFormat.CBZ -> ComicArchiveDocumentParser()
        ReaderFormat.UNKNOWN -> throw DocumentParseException("UNSUPPORTED_FORMAT", "不支持的书籍格式")
    }
}
