package com.example.myapplication.data.reader.importer

import com.example.myapplication.data.reader.storage.ManifestFileKind
import com.example.myapplication.data.reader.storage.ManifestOutlineNode
import com.example.myapplication.data.reader.storage.ManifestSection
import java.io.File
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * HTML / HTM / MHTML / MHT 解析器（P5-01 HtmlArchiveAdapter）。
 *
 * 设计口径（详细设计 §2）：
 *  - 本地资源净化：删除 script/iframe/object/embed 与 on* 事件属性、javascript: 链接；
 *  - **禁止远程请求**：远程图片一律移除，不"先加载再说"；
 *  - MHTML 用 MIME 分段解析（boundary / Content-Type / Content-Transfer-Encoding /
 *    Content-ID / Content-Location），cid 引用改写成落盘相对路径；
 *  - 按 h1–h3 切节，无标题则整篇一节；
 *  - 所有资源写入 revision 目录，section 在 `sections/`、资源在 `images/`，
 *    因此引用必须写成 `../images/...`（阅读器以 section 所在目录为 baseUrl）。
 *
 * 上限可注入（测试用极小值验证边界），默认值即生产值。
 */
class HtmlArchiveDocumentParser(
    private val maxSourceBytes: Long = 32L * 1024 * 1024,
    private val maxSections: Int = 500,
    private val maxResources: Int = 500,
    private val maxResourceBytes: Long = 32L * 1024 * 1024,
) : DocumentParser {
    override fun parse(sourceFile: File, outputDirectory: File, titleHint: String?): ParsedDocument {
        if (!sourceFile.isFile) throw DocumentParseException("CORRUPT_INPUT", "源文件不存在")
        if (sourceFile.length() > maxSourceBytes) {
            throw DocumentParseException("LIMIT_EXCEEDED", "HTML/MHTML 源文件超过上限 $maxSourceBytes 字节")
        }
        val bytes = sourceFile.readBytes()
        val raw = String(bytes, Charsets.ISO_8859_1)
        val title = ParserIO.titleFromName(titleHint)
        val isMhtml = looksLikeMhtml(raw)
        val files = mutableListOf<ParsedFile>()
        val document: Document
        if (isMhtml) {
            val (merged, mhtmlResources) = parseMhtml(raw, outputDirectory)
            document = merged
            files += mhtmlResources
        } else {
            document = parseSingle(bytes)
        }
        // MHTML 的分段图片已在 parseMhtml 落盘，这里负责把 cid 引用改写成相对路径
        // （sourceDirectory 传 null：MHTML 不允许再去读源目录里的相对文件）。
        files += collectImages(document, if (isMhtml) null else sourceFile.parentFile, outputDirectory)
        val (sectionFiles, sections, outline) = buildSections(document, title, outputDirectory)
        files += sectionFiles
        return ParsedDocument(
            title = document.title().takeIf { it.isNotBlank() } ?: title,
            author = document.selectFirst("meta[name=author]")?.attr("content")?.takeIf { it.isNotBlank() },
            format = if (isMhtml) ReaderFormat.MHTML else ReaderFormat.HTML,
            files = files,
            sections = sections,
            outline = outline,
        )
    }

    // ---- 单文件 HTML ----

    private fun parseSingle(bytes: ByteArray): Document {
        val html = ParserIO.decodeText(bytes)
        val document = Jsoup.parse(html, "", Parser.htmlParser())
        sanitize(document)
        return document
    }

    // ---- MHTML ----

    private fun parseMhtml(raw: String, outputDirectory: File): Pair<Document, List<ParsedFile>> {
        val boundary = extractBoundary(raw)
            ?: throw DocumentParseException("CORRUPT_INPUT", "MHTML 缺少 multipart boundary")
        val parts = splitParts(raw, boundary)
        if (parts.isEmpty()) throw DocumentParseException("CORRUPT_INPUT", "MHTML 没有可用分段")

        val resources = mutableMapOf<String, String>()
        val resourceFiles = mutableListOf<ParsedFile>()
        val htmlParts = mutableListOf<MimePart>()
        var resourceIndex = 0
        parts.forEach { part ->
            val contentType = part.header("content-type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
            when {
                contentType.startsWith("text/html") -> htmlParts += part
                contentType.startsWith("image/") -> {
                    if (resourceIndex >= maxResources) {
                        throw DocumentParseException("LIMIT_EXCEEDED", "MHTML 图片资源超过上限 $maxResources")
                    }
                    val body = part.decodedBody()
                    if (body.size > maxResourceBytes) {
                        throw DocumentParseException("LIMIT_EXCEEDED", "MHTML 图片资源超过单资源上限")
                    }
                    resourceIndex += 1
                    val extension = extensionForMime(contentType)
                    val relative = "images/resource-$resourceIndex.$extension"
                    ParserIO.writeBytes(outputDirectory, relative, body)
                    resourceFiles += ParsedFile(relative, ManifestFileKind.IMAGE)
                    val href = "../$relative"
                    part.header("content-id")?.let { cid ->
                        resources[cid.trim('<', '>').lowercase(Locale.ROOT)] = href
                        resources["cid:${cid.trim('<', '>').lowercase(Locale.ROOT)}"] = href
                    }
                    part.header("content-location")?.let { location ->
                        resources[location.lowercase(Locale.ROOT)] = href
                        resources[location.substringAfterLast('/').lowercase(Locale.ROOT)] = href
                    }
                }
            }
        }
        if (htmlParts.isEmpty()) {
            throw DocumentParseException("CORRUPT_INPUT", "MHTML 中没有 HTML 正文分段")
        }
        val merged = Jsoup.parse("<html><head></head><body></body></html>", "", Parser.htmlParser())
        htmlParts.forEach { part ->
            val charset = part.header("content-type")?.let { contentTypeCharset(it) }
            val html = if (charset != null) {
                String(part.decodedBody(), charset)
            } else {
                ParserIO.decodeText(part.decodedBody())
            }
            val parsed = Jsoup.parse(html, "", Parser.htmlParser())
            parsed.body().children().forEach { child -> merged.body().appendChild(child.clone()) }
            parsed.title().takeIf { it.isNotBlank() }?.let { if (merged.title().isBlank()) merged.title(it) }
        }
        // 解析器的输出文件名会被 sanitize 改写成 images/，因此把 cid 映射交给 rewriteImages
        merged.head().appendElement("meta").attr("name", "mhtml-cid-map").attr("content", encodeMap(resources))
        sanitize(merged)
        return merged to resourceFiles
    }

    private fun extractBoundary(raw: String): String? {
        val headerEnd = headerBodyBoundary(raw) ?: return null
        // 注意：不能先把头部小写化再取值——MIME boundary 是大小写敏感的，
        // 小写化会让 "--BOUND" 与小写 delimiter 匹配失败，整包被当成一个 part。
        val headers = raw.substring(0, headerEnd)
        val match = Regex("boundary\\s*=\\s*\"?([^\";\\r\\n]+)\"?", RegexOption.IGNORE_CASE).find(headers)
            ?: return null
        return match.groupValues[1].trim()
    }

    private fun splitParts(raw: String, boundary: String): List<MimePart> {
        val headerEnd = headerBodyBoundary(raw) ?: return emptyList()
        val body = raw.substring(headerEnd).trimStart('\r', '\n')
        val delimiter = "--$boundary"
        val parts = mutableListOf<MimePart>()
        body.split(delimiter).forEach { chunk ->
            val trimmed = chunk.trimStart('\r', '\n')
            if (trimmed.isEmpty() || trimmed.startsWith("--")) return@forEach
            val separator = headerBodyBoundary(trimmed)
            val headers = if (separator == null) {
                parseHeaders(trimmed)
            } else {
                parseHeaders(trimmed.substring(0, separator))
            }
            val payload = if (separator == null) "" else trimmed.substring(separator)
            parts += MimePart(headers, payload.toByteArray(Charsets.ISO_8859_1))
        }
        return parts
    }

    private fun headerBodyBoundary(text: String): Int? {
        val crlf = text.indexOf("\r\n\r\n")
        val lf = text.indexOf("\n\n")
        return when {
            crlf >= 0 && lf >= 0 -> if (crlf < lf) crlf + 4 else lf + 2
            crlf >= 0 -> crlf + 4
            lf >= 0 -> lf + 2
            else -> null
        }
    }

    private fun parseHeaders(block: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        block.lines().forEach { line ->
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase(Locale.ROOT)] = line.substring(index + 1).trim()
            }
        }
        return headers
    }

    private data class MimePart(val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]

        fun decodedBody(): ByteArray = when (header("content-transfer-encoding")?.lowercase(Locale.ROOT)?.trim()) {
            "base64" -> MimeBase64.decode(String(body, Charsets.ISO_8859_1))
            "quoted-printable" -> QuotedPrintable.decode(body)
            else -> body
        }
    }

    // ---- 净化与资源 ----

    /** 净化：移除可执行/可远程加载的元素与属性（不依赖外部白名单库）。 */
    private fun sanitize(document: Document) {
        // style/link 一并移除：外部 CSS 可能外发请求，内联样式会覆盖阅读器排版口径。
        document.select("script, iframe, object, embed, frame, frameset, applet, link, style").remove()
        document.allElements.forEach { element ->
            val attributes = element.attributes()
            attributes.asList().forEach { attribute ->
                val key = attribute.key.lowercase(Locale.ROOT)
                val value = attribute.value.lowercase(Locale.ROOT).trim()
                val dangerous = key.startsWith("on") ||
                    ((key == "href" || key == "src" || key == "action") && value.startsWith("javascript:"))
                if (dangerous) element.removeAttr(attribute.key)
            }
        }
    }

    /**
     * 收集并改写图片资源：data URI 解码落盘，相对路径从源目录复制，
     * 远程地址一律移除（设计明确禁止自动请求）。
     */
    private fun collectImages(document: Document, sourceDirectory: File?, outputDirectory: File): List<ParsedFile> {
        val files = mutableListOf<ParsedFile>()
        val cidMap = decodeMap(document.selectFirst("meta[name=mhtml-cid-map]")?.attr("content"))
        var index = 0
        document.select("img").forEach { image ->
            val source = image.attr("src").trim()
            if (source.isEmpty()) {
                image.remove()
                return@forEach
            }
            // 1) MHTML 分段资源：按 Content-ID / Content-Location / 文件名三种键匹配，
            //    命中就直接改写成本地相对路径（资源已落盘，不再复制）。
            val cidKey = source.removePrefix("cid:").lowercase(Locale.ROOT)
            val mapped = cidMap[source.lowercase(Locale.ROOT)]
                ?: cidMap[cidKey]
                ?: cidMap[source.substringAfterLast('/').lowercase(Locale.ROOT)]
            if (mapped != null) {
                image.attr("src", mapped)
                return@forEach
            }
            // 2) 内嵌 data URI：解码落盘；3) 远程地址：直接移除，绝不发请求；
            //    4) 相对路径：从源文件同目录复制（越界/缺失都移除）。
            val resolved: Pair<ByteArray, String>? = when {
                source.startsWith("data:", ignoreCase = true) -> decodeDataUri(source)
                isRemoteReference(source) -> null
                else -> readLocalResource(source, sourceDirectory)
            }
            if (resolved == null) {
                image.remove()
                return@forEach
            }
            if (index >= maxResources) {
                throw DocumentParseException("LIMIT_EXCEEDED", "内嵌图片数量超过上限 $maxResources")
            }
            index += 1
            val (bytes, extension) = resolved
            if (bytes.size > maxResourceBytes) {
                throw DocumentParseException("LIMIT_EXCEEDED", "单张图片超过上限 $maxResourceBytes 字节")
            }
            val relative = "images/image-$index.$extension"
            ParserIO.writeBytes(outputDirectory, relative, bytes)
            files += ParsedFile(relative, ManifestFileKind.IMAGE)
            // section 在 sections/ 下，资源在 images/ 下
            image.attr("src", "../$relative")
        }
        return files
    }

    private fun decodeDataUri(value: String): Pair<ByteArray, String>? {
        val comma = value.indexOf(',')
        if (comma < 0) return null
        val meta = value.substring(5, comma)
        if (!meta.contains("base64", ignoreCase = true)) return null
        val mime = meta.substringBefore(';').ifBlank { "image/png" }
        val bytes = runCatching { MimeBase64.decode(value.substring(comma + 1)) }.getOrNull() ?: return null
        return bytes to extensionForMime(mime)
    }

    private fun readLocalResource(reference: String, sourceDirectory: File?): Pair<ByteArray, String>? {
        if (sourceDirectory == null) return null
        val cleaned = reference.substringBefore('?').substringBefore('#')
        if (cleaned.isEmpty() || cleaned.startsWith("//")) return null
        val root = sourceDirectory.canonicalFile
        val target = File(root, cleaned.replace('/', File.separatorChar)).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) return null
        if (!target.isFile || target.length() > maxResourceBytes) return null
        val extension = target.name.substringAfterLast('.', "png").lowercase(Locale.ROOT)
        return target.readBytes() to normalizeImageExtension(extension)
    }

    private fun isRemoteReference(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("//") || lower.startsWith("ftp:")
    }

    // ---- 分节 ----

    private fun buildSections(
        document: Document,
        fallbackTitle: String,
        outputDirectory: File,
    ): Triple<List<ParsedFile>, List<ManifestSection>, List<ManifestOutlineNode>> {
        val buckets = mutableListOf<Pair<String?, MutableList<String>>>()
        var current: Pair<String?, MutableList<String>>? = null
        document.body().children().forEach { child ->
            if (child.tagName().lowercase(Locale.ROOT) in HEADING_TAGS) {
                current?.let { buckets += it }
                if (buckets.size >= maxSections) {
                    throw DocumentParseException("LIMIT_EXCEEDED", "章节数超过上限 $maxSections")
                }
                current = child.text().takeIf { it.isNotBlank() } to mutableListOf(child.outerHtml())
            } else {
                val holder = current
                if (holder == null) {
                    val preamble = null to mutableListOf(child.outerHtml())
                    current = preamble
                } else {
                    holder.second += child.outerHtml()
                }
            }
        }
        current?.let { buckets += it }
        if (buckets.isEmpty()) {
            buckets += (null to mutableListOf(document.body().html()))
        }

        val files = mutableListOf<ParsedFile>()
        val sections = mutableListOf<ManifestSection>()
        val outline = mutableListOf<ManifestOutlineNode>()
        buckets.forEachIndexed { index, (headingTitle, bodyParts) ->
            val sectionId = "section-${index + 1}"
            val relative = "sections/$sectionId.html"
            // 第一个标题之前的散段落叫"前言"，比拿书名当节名更符合阅读预期。
            val sectionTitle = headingTitle?.take(120)
                ?: if (index == 0 && buckets.size > 1) "前言" else fallbackTitle
            val bodyHtml = bodyParts.joinToString("\n")
            ParserIO.writeText(outputDirectory, relative, ParserIO.htmlDocument(sectionTitle, bodyHtml))
            files += ParsedFile(relative, ManifestFileKind.SECTION)
            sections += ManifestSection(
                id = sectionId,
                orderIndex = index,
                title = sectionTitle,
                resourceHref = relative,
                charCount = Jsoup.parse(bodyHtml).text().length,
            )
            if (headingTitle != null) {
                outline += ManifestOutlineNode(
                    nodeKey = "outline-${index + 1}",
                    parentKey = null,
                    orderIndex = index,
                    title = sectionTitle,
                    targetSectionId = sectionId,
                )
            }
        }
        return Triple(files, sections, outline)
    }

    private fun looksLikeMhtml(raw: String): Boolean {
        val head = raw.take(4096).lowercase(Locale.ROOT)
        return head.contains("mime-version:") &&
            (head.contains("multipart/related") || head.contains("content-location:"))
    }

    private fun extensionForMime(mime: String): String = when (mime.substringBefore(';').trim().lowercase(Locale.ROOT)) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        "image/avif" -> "avif"
        "image/svg+xml" -> "svg"
        else -> "png"
    }

    private fun normalizeImageExtension(extension: String): String = when (extension) {
        "jpeg", "jpg" -> "jpg"
        "png", "gif", "webp", "bmp", "avif", "svg" -> extension
        else -> "png"
    }

    private fun contentTypeCharset(contentType: String): java.nio.charset.Charset? {
        val match = Regex("charset\\s*=\\s*\"?([^\"';]+)\"?", RegexOption.IGNORE_CASE).find(contentType)
            ?: return null
        return runCatching { java.nio.charset.Charset.forName(match.groupValues[1].trim()) }.getOrNull()
    }

    private fun encodeMap(map: Map<String, String>): String =
        map.entries.joinToString("\u0001") { "${it.key}\u0002${it.value}" }

    private fun decodeMap(value: String?): Map<String, String> {
        if (value.isNullOrBlank()) return emptyMap()
        return value.split('\u0001').mapNotNull { entry ->
            val index = entry.indexOf('\u0002')
            if (index <= 0) null else entry.substring(0, index) to entry.substring(index + 1)
        }.toMap()
    }

    companion object {
        private val HEADING_TAGS = setOf("h1", "h2", "h3")
    }
}
