package com.example.myapplication.data.reader.importer

import com.example.myapplication.data.reader.storage.ManifestFileKind
import com.example.myapplication.data.reader.storage.ManifestSection
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * CBZ / 图片 ZIP 漫画解析器（P5-01 ComicArchiveAdapter）。
 *
 * 设计口径：
 *  - 一页一节（`page-<n>`），`pageStart/pageEnd` 记录页序，天然支持"跳到第 N 页"；
 *  - 图片按**自然排序**（page2 在 page10 之前），不按字典序；
 *  - 页面 HTML 只放一张整宽图片，不含文字 → 能力表声明 `selectableText=false`、
 *    `knowledgeIndexable=false`，UI 不会给出无效的选字/问答/出题入口；
 *  - 非漫画 ZIP（含 EPUB 包描述的）必须明确拒绝，不能"能显示就当漫画读了"。
 *
 * 上限可注入（测试会用很小的值验证边界），默认值即生产值。
 */
class ComicArchiveDocumentParser(
    private val maxPages: Int = MAX_PAGES,
    private val maxImageBytes: Long = ArchiveLimits.MAX_ENTRY_BYTES,
    private val maxTotalBytes: Long = ArchiveLimits.MAX_TOTAL_BYTES,
) : DocumentParser {
    override fun parse(sourceFile: File, outputDirectory: File, titleHint: String?): ParsedDocument {
        if (!sourceFile.looksLikeZip()) {
            throw DocumentParseException("CORRUPT_INPUT", "不是 ZIP 容器")
        }
        val title = ParserIO.titleFromName(titleHint)
        ZipFile(sourceFile).use { zip ->
            val support = ArchiveSupport(maxEntryBytes = maxImageBytes, maxTotalBytes = maxTotalBytes)
            val entries = support.listFiles(zip)
            if (entries.isEmpty()) {
                throw DocumentParseException("UNSUPPORTED_FORMAT", "压缩包内没有任何文件")
            }
            val lowerNames = entries.map { it.name.lowercase(Locale.ROOT) }
            if (lowerNames.any { it == "mimetype" || it.endsWith(".opf") }) {
                throw DocumentParseException("INVALID_ARGUMENT", "该压缩包是 EPUB，不是漫画包")
            }
            val images = entries
                .filter { isImageEntry(it.name) }
                .sortedWith { left, right -> ArchiveSupport.naturalCompare(left.name, right.name) }
            if (images.isEmpty()) {
                throw DocumentParseException("UNSUPPORTED_FORMAT", "压缩包内没有图片页")
            }
            if (images.size > maxPages) {
                throw DocumentParseException("LIMIT_EXCEEDED", "图片页数量 ${images.size} 超过上限 $maxPages")
            }

            val files = mutableListOf<ParsedFile>()
            val sections = mutableListOf<ManifestSection>()
            var totalBytes = 0L
            images.forEachIndexed { index, entry ->
                val bytes = support.readEntry(zip, entry, maxImageBytes)
                totalBytes = support.accumulate(totalBytes, bytes.size)
                val pageNumber = index + 1
                val extension = imageExtension(entry.name)
                val imagePath = "images/page-$pageNumber.$extension"
                ParserIO.writeBytes(outputDirectory, imagePath, bytes)
                files += ParsedFile(imagePath, ManifestFileKind.IMAGE)

                val sectionPath = "pages/page-$pageNumber.html"
                // section 在 pages/ 下，图片在 images/ 下：相对路径必须是 ../images/...
                // （ReaderDocumentRepository.sectionBaseUrl 以 section 所在目录为基准）
                val body = buildString {
                    append("<div class=\"comic-page\">")
                    append("<img src=\"../$imagePath\" alt=\"第 $pageNumber 页\"")
                    append(" style=\"width:100%;height:auto;display:block;margin:0 auto\" />")
                    append("</div>")
                }
                ParserIO.writeText(outputDirectory, sectionPath, ParserIO.htmlDocument("$title 第 $pageNumber 页", body))
                files += ParsedFile(sectionPath, ManifestFileKind.SECTION)
                sections += ManifestSection(
                    id = "page-$pageNumber",
                    orderIndex = index,
                    title = "第 $pageNumber 页",
                    resourceHref = sectionPath,
                    charCount = 0,
                    pageStart = index,
                    pageEnd = index,
                )
            }
            return ParsedDocument(
                title = title,
                author = null,
                format = ReaderFormat.CBZ,
                files = files,
                sections = sections,
                outline = emptyList(),
            )
        }
    }

    private fun isImageEntry(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return IMAGE_EXTENSIONS.any { lower.endsWith(".$it") }
    }

    private fun imageExtension(name: String): String {
        val candidate = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (candidate) {
            "jpg", "jpeg" -> "jpg"
            "png" -> "png"
            "webp" -> "webp"
            "gif" -> "gif"
            "bmp" -> "bmp"
            "avif" -> "avif"
            else -> "jpg"
        }
    }

    companion object {
        const val MAX_PAGES = 2_000
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
    }
}
