package com.example.myapplication.data.reader.importer

import com.example.myapplication.data.reader.storage.ManifestFileKind
import com.example.myapplication.data.reader.storage.ManifestOutlineNode
import com.example.myapplication.data.reader.storage.ManifestSection
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.parser.Parser

class EpubDocumentParser(
    /**
     * 单个 ZIP 条目的解压上限（字节），默认 32 MiB。
     *
     * 做成可注入参数是为了让 JVM 单测不必真写 32 MiB 就能证明「XML/HTML 读取同样受限」：
     * 历史实现里 [readXml]/[readHtml] 直接读 entry，绕过了 [readEntry] 的上限，
     * 一个高压缩比的 container.xml/OPF/nav 就能把整份内容解压进内存（ZIP 炸弹通道）。
     */
    private val maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
) : DocumentParser {
    override fun parse(sourceFile: File, outputDirectory: File, titleHint: String?): ParsedDocument {
        ZipFile(sourceFile).use { zip ->
            validateArchive(zip)
            if (zip.getEntry("META-INF/encryption.xml") != null) {
                throw DocumentParseException("DRM_PROTECTED", "EPUB 含有加密/DRM 内容")
            }
            val container = readXml(zip, "META-INF/container.xml")
                ?: throw DocumentParseException("CORRUPT_INPUT", "EPUB 缺少 container.xml")
            val rootFilePath = container.getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
                ?.takeIf { it.isNotBlank() }
                ?: throw DocumentParseException("CORRUPT_INPUT", "EPUB 未声明 OPF")
            val opf = readXml(zip, rootFilePath)
                ?: throw DocumentParseException("CORRUPT_INPUT", "EPUB OPF 不可读")
            val opfDirectory = rootFilePath.substringBeforeLast('/', "")
            val metadata = opf.getElementsByTag("metadata").firstOrNull()
            val title = metadataText(metadata, "title")
                ?.takeIf { it.isNotBlank() }
                ?: titleHint?.trim()?.takeIf { it.isNotBlank() }
                ?: ParserIO.titleFromName(sourceFile.name)
            val author = metadataText(metadata, "creator")?.takeIf { it.isNotBlank() }

            val manifestItems = opf.getElementsByTag("item").mapNotNull { item ->
                val id = item.attr("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val href = resolvePath(opfDirectory, item.attr("href")) ?: return@mapNotNull null
                id to EpubItem(
                    id = id,
                    href = href,
                    mediaType = item.attr("media-type"),
                    properties = item.attr("properties"),
                )
            }.toMap()
            if (manifestItems.isEmpty()) throw DocumentParseException("CORRUPT_INPUT", "EPUB manifest 为空")

            val files = mutableListOf<ParsedFile>()
            val copied = mutableSetOf<String>()
            var totalBytes = 0L
            manifestItems.values.forEach { item ->
                val entry = zip.getEntry(item.href) ?: return@forEach
                if (!copied.add(item.href)) return@forEach
                val bytes = readEntry(zip, entry)
                totalBytes += bytes.size
                if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                    throw DocumentParseException("LIMIT_EXCEEDED", "EPUB 解压内容超过限制")
                }
                if (item.isHtml()) {
                    val sanitized = sanitizeHtml(bytes, item.href.endsWith(".xhtml", ignoreCase = true)).toByteArray(StandardCharsets.UTF_8)
                    ParserIO.writeBytes(outputDirectory, item.href, sanitized)
                    files += ParsedFile(item.href, ManifestFileKind.SECTION)
                } else {
                    ParserIO.writeBytes(outputDirectory, item.href, bytes)
                    files += ParsedFile(item.href, item.kind())
                }
            }

            val spineItems = opf.getElementsByTag("itemref").mapNotNull { ref ->
                manifestItems[ref.attr("idref")]
            }
            if (spineItems.isEmpty()) {
                throw DocumentParseException("CORRUPT_INPUT", "EPUB spine 为空")
            }

            val sections = mutableListOf<ManifestSection>()
            val sectionByPath = mutableMapOf<String, String>()
            spineItems.forEachIndexed { index, item ->
                val entry = zip.getEntry(item.href)
                    ?: throw DocumentParseException("CORRUPT_INPUT", "EPUB spine 文件缺失: ${item.href}")
                val html = sanitizeHtml(readEntry(zip, entry), item.href.endsWith(".xhtml", ignoreCase = true))
                val parsed = Jsoup.parse(html, "", Parser.htmlParser())
                val sectionTitle = parsed.getElementsByTag("h1").firstOrNull()?.text()
                    ?: parsed.getElementsByTag("h2").firstOrNull()?.text()
                    ?: parsed.title().takeIf { it.isNotBlank() }
                    ?: "第 ${index + 1} 节"
                val sectionId = "section-${index + 1}"
                sectionByPath[item.href] = sectionId
                sections += ManifestSection(
                    id = sectionId,
                    orderIndex = index,
                    title = sectionTitle.take(120),
                    resourceHref = item.href,
                    charCount = parsed.body()?.text()?.length ?: 0,
                )
            }

            val outline = buildOutline(zip, manifestItems, sectionByPath, sections)
            return ParsedDocument(
                title = title,
                author = author,
                format = ReaderFormat.EPUB,
                files = files,
                sections = sections,
                outline = outline,
            )
        }
    }

    private fun validateArchive(zip: ZipFile) {
        val entries = zip.entries()
        var count = 0
        while (entries.hasMoreElements()) {
            count += 1
            if (count > MAX_ENTRIES) throw DocumentParseException("LIMIT_EXCEEDED", "EPUB 条目过多")
            val entry = entries.nextElement()
            val name = entry.name.replace('\\', '/')
            if (name.startsWith('/') || name.split('/').any { it == ".." }) {
                throw DocumentParseException("CORRUPT_INPUT", "EPUB 含路径穿越条目")
            }
        }
    }

    private fun buildOutline(
        zip: ZipFile,
        manifestItems: Map<String, EpubItem>,
        sectionByPath: Map<String, String>,
        sections: List<ManifestSection>,
    ): List<ManifestOutlineNode> {
        val navItem = manifestItems.values.firstOrNull { it.properties.split(' ').contains("nav") }
        if (navItem != null) {
            val navHtml = readXml(zip, navItem.href) ?: readHtml(zip, navItem.href)
            if (navHtml != null) {
                val nav = navHtml.getElementsByTag("nav").firstOrNull { element ->
                    element.attr("epub:type").contains("toc") || element.attr("type").contains("toc")
                } ?: navHtml.getElementsByTag("nav").firstOrNull()
                val links = nav?.getElementsByTag("a").orEmpty()
                val nodes = links.mapIndexedNotNull { index, link ->
                    val targetPath = resolvePath(navItem.href.substringBeforeLast('/', ""), link.attr("href"))
                        ?: return@mapIndexedNotNull null
                    val sectionId = sectionByPath[targetPath] ?: return@mapIndexedNotNull null
                    ManifestOutlineNode(
                        nodeKey = "toc-${index + 1}",
                        orderIndex = index,
                        title = link.text().trim().take(120).ifBlank { "目录 ${index + 1}" },
                        targetSectionId = sectionId,
                    )
                }
                if (nodes.isNotEmpty()) return nodes
            }
        }
        val ncxItem = manifestItems.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        if (ncxItem != null) {
            val ncx = readXml(zip, ncxItem.href)
            if (ncx != null) {
                val nodes = mutableListOf<ManifestOutlineNode>()
                val navMap = ncx.getElementsByTag("navMap").firstOrNull()
                parseNavPoints(navMap, ncxItem.href.substringBeforeLast('/', ""), sectionByPath, nodes, null)
                if (nodes.isNotEmpty()) return nodes
            }
        }
        return sections.map { section ->
            ManifestOutlineNode(
                nodeKey = "toc-${section.orderIndex + 1}",
                orderIndex = section.orderIndex,
                title = section.title,
                targetSectionId = section.id,
            )
        }
    }

    private fun parseNavPoints(
        parent: Element?,
        baseDirectory: String,
        sectionByPath: Map<String, String>,
        output: MutableList<ManifestOutlineNode>,
        parentKey: String?,
    ) {
        parent?.children()?.filter { it.tagName().substringAfter(':') == "navPoint" }?.forEach { navPoint ->
            val title = navPoint.getElementsByTag("text").firstOrNull()?.text()?.trim()
                ?.take(120)?.takeIf { it.isNotBlank() } ?: "目录 ${output.size + 1}"
            val src = navPoint.getElementsByTag("content").firstOrNull()?.attr("src")
            val target = resolvePath(baseDirectory, src)
            val nodeKey = "toc-${output.size + 1}"
            val sectionId = target?.let(sectionByPath::get)
            if (sectionId != null) {
                output += ManifestOutlineNode(
                    nodeKey = nodeKey,
                    parentKey = parentKey,
                    orderIndex = output.size,
                    title = title,
                    targetSectionId = sectionId,
                )
            }
            parseNavPoints(navPoint, baseDirectory, sectionByPath, output, nodeKey)
        }
    }

    private fun readXml(zip: ZipFile, path: String): Document? {
        val entry = zip.getEntry(path) ?: return null
        // 与 manifest 资源的读取共用同一字节上限：直接读 entry 会绕过 readEntry 的
        // 32 MiB 限制，把 XML 变成 ZIP 炸弹入口（container.xml / OPF / nav 都不受 manifest 保护）。
        return ByteArrayInputStream(readEntry(zip, entry)).use { input ->
            Jsoup.parse(input, "UTF-8", "", Parser.xmlParser())
        }
    }

    private fun readHtml(zip: ZipFile, path: String): Document? {
        val entry = zip.getEntry(path) ?: return null
        // 同上：目录型的 HTML 也走字节上限，保证所有 entry 读取路径只有一处限制。
        return ByteArrayInputStream(readEntry(zip, entry)).use { input ->
            Jsoup.parse(input, "UTF-8", "", Parser.htmlParser())
        }
    }

    private fun readEntry(zip: ZipFile, entry: ZipEntry): ByteArray {
        val limit = maxEntryBytes
        // 报错带上条目名与上限，导入失败时能直接定位是哪个文件撑爆了限制。
        if (entry.size > limit) {
            throw DocumentParseException("LIMIT_EXCEEDED", "EPUB 单文件过大: ${entry.name}（上限 $limit 字节）")
        }
        return zip.getInputStream(entry).use { input -> readLimited(input, limit, entry.name) }
    }

    private fun readLimited(input: InputStream, limit: Int, source: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            // 声明大小可能缺失或被伪造（entry.size = -1 / 偏小），因此以实际解压字节数为准，
            // 一旦超限立刻中断，绝不把超限内容读进内存。
            if (total > limit) {
                throw DocumentParseException("LIMIT_EXCEEDED", "EPUB 单文件超过限制: $source（上限 $limit 字节）")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun sanitizeHtml(bytes: ByteArray, xhtml: Boolean): String {
        val document = Jsoup.parse(bytes.toString(StandardCharsets.UTF_8), "", Parser.htmlParser())
        document.select("script, iframe, object, embed, form, input, button").remove()
        document.select("meta[http-equiv=refresh]").remove()
        document.getAllElements().forEach { element ->
            element.attributes().asList()
                .filter { it.key.startsWith("on", ignoreCase = true) }
                .forEach { element.removeAttr(it.key) }
            val href = element.attr("href")
            val src = element.attr("src")
            if (href.startsWith("javascript:", ignoreCase = true)) element.removeAttr("href")
            if (src.startsWith("javascript:", ignoreCase = true)) element.removeAttr("src")
        }
        document.outputSettings().prettyPrint(false)
        if (xhtml) {
            // WebView serves .xhtml as XML. HTML serialization leaves void tags unclosed
            // and named entities such as &nbsp; undefined, making the whole page a parse error.
            document.outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml)
            document.selectFirst("html")?.attr("xmlns", "http://www.w3.org/1999/xhtml")
        }
        return document.outerHtml()
    }

    private fun metadataText(metadata: Element?, localName: String): String? =
        metadata?.children()?.firstOrNull { it.tagName().substringAfter(':') == localName }?.text()

    private fun resolvePath(baseDirectory: String, href: String?): String? {
        if (href.isNullOrBlank()) return null
        val cleanHref = href.substringBefore('#')
        if (cleanHref.isBlank()) return null
        val decoded = runCatching {
            URLDecoder.decode(cleanHref.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrElse { cleanHref }
        if (decoded.startsWith('/') || decoded.startsWith('\\')) return null
        val result = mutableListOf<String>()
        (baseDirectory.split('/').filter { it.isNotEmpty() } + decoded.split('/')).forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (result.isNotEmpty()) result.removeAt(result.lastIndex) else return null
                else -> result += segment
            }
        }
        return result.joinToString("/").takeIf { it.isNotBlank() }
    }

    private data class EpubItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    ) {
        fun isHtml(): Boolean = mediaType.contains("html") || href.endsWith(".xhtml") || href.endsWith(".html")

        fun kind(): ManifestFileKind = when {
            mediaType.startsWith("image/") -> ManifestFileKind.IMAGE
            mediaType.contains("css") -> ManifestFileKind.STYLE
            mediaType.contains("font") || href.endsWith(".woff") || href.endsWith(".ttf") -> ManifestFileKind.FONT
            else -> ManifestFileKind.OTHER
        }
    }

    companion object {
        private const val MAX_ENTRIES = 10_000
        private const val DEFAULT_MAX_ENTRY_BYTES = 32 * 1024 * 1024
        private const val MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
    }
}
