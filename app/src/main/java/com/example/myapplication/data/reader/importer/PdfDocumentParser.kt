package com.example.myapplication.data.reader.importer

import com.example.myapplication.data.reader.storage.ManifestFileKind
import com.example.myapplication.data.reader.storage.ManifestOutlineNode
import com.example.myapplication.data.reader.storage.ManifestSection
import com.example.myapplication.domain.reader.ExtractionMarkers
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException

class PdfDocumentParser : DocumentParser {
    override fun parse(sourceFile: File, outputDirectory: File, titleHint: String?): ParsedDocument {
        val document = try {
            PDDocument.load(sourceFile)
        } catch (error: InvalidPasswordException) {
            throw DocumentParseException("DRM_PROTECTED", "PDF 需要密码或受保护", error)
        } catch (error: IOException) {
            throw DocumentParseException("CORRUPT_INPUT", "PDF 文件损坏或不可读", error)
        }
        try {
            val pageCount = document.numberOfPages
            if (pageCount <= 0) throw DocumentParseException("CORRUPT_INPUT", "PDF 没有页面")
            val info = document.documentInformation
            val title = info?.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: titleHint?.trim()?.takeIf { it.isNotEmpty() }
                ?: ParserIO.titleFromName(sourceFile.name)
            val author = info?.author?.trim()?.takeIf { it.isNotEmpty() }
            val files = mutableListOf<ParsedFile>()
            val sections = mutableListOf<ManifestSection>()
            val outline = mutableListOf<ManifestOutlineNode>()

            val sourceTarget = File(outputDirectory, "source").apply { mkdirs() }
            sourceFile.copyTo(File(sourceTarget, "source.pdf"), overwrite = true)
            files += ParsedFile("source/source.pdf", ManifestFileKind.SOURCE)

            for (pageIndex in 0 until pageCount) {
                val pageText = extractPageText(document, pageIndex + 1)
                val pageNumber = pageIndex + 1
                // 扫描页只写提示，且整页标 no-text：提示文案不得被分块/检索/出题当正文。
                val html = ParserIO.htmlDocument(
                    "第 $pageNumber 页",
                    PdfPageMarkup.body(pageNumber, pageText),
                )
                val path = "content/page-${pageIndex + 1}.html"
                ParserIO.writeText(outputDirectory, path, html)
                files += ParsedFile(path, ManifestFileKind.SECTION)
                val sectionId = "page-${pageIndex + 1}"
                sections += ManifestSection(
                    id = sectionId,
                    orderIndex = pageIndex,
                    title = "第 ${pageIndex + 1} 页",
                    resourceHref = path,
                    charCount = pageText.length,
                    pageStart = pageIndex,
                    pageEnd = pageIndex,
                )
                outline += ManifestOutlineNode(
                    nodeKey = "page-${pageIndex + 1}",
                    orderIndex = pageIndex,
                    title = "第 ${pageIndex + 1} 页",
                    targetSectionId = sectionId,
                )
            }
            return ParsedDocument(
                title = title,
                author = author,
                format = ReaderFormat.PDF,
                files = files,
                sections = sections,
                outline = outline,
            )
        } finally {
            document.close()
        }
    }

    private fun extractPageText(document: PDDocument, pageNumber: Int): String {
        return try {
            val stripper = PDFTextStripper()
            stripper.startPage = pageNumber
            stripper.endPage = pageNumber
            stripper.getText(document).trim()
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        /** 扫描页提示；只用于显示，不进入正文块（见 [ExtractionMarkers]）。 */
        const val SCANNED_PAGE_NOTICE = "此页为扫描图像，暂无可提取文本。"
    }
}