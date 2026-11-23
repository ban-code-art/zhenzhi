package com.example.myapplication.data.reader.importer

import com.example.myapplication.data.reader.storage.ManifestFileKind
import com.example.myapplication.data.reader.storage.ManifestOutlineNode
import com.example.myapplication.data.reader.storage.ManifestSection
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * FB2（FictionBook 2.0）解析器。
 *
 * 结构口径：
 * - `.fb2` 单文件与 `fb2.zip` 容器（ZIP 内取第一个 `*.fb2`）最终都归一成一份 XML；
 * - `<description><title-info>` 提供书名与作者（first-name / middle-name / last-name 拼接）；
 * - 正文来自 `<body>`：**每一层 `<section>` 都建一节**（不把多级章节拍平），
 *   子节挂在父节的 outline 之下；没有 `<section>` 的平铺 `<body>` 按顶层内容聚成一节；
 * - 每节写一个独立 HTML（`sections/section-N.html`），只包含该节**自己**的内容，
 *   子节内容不会重复渲染（否则正文会出现两遍）；
 * - `<binary>` 里的 base64 图片按需解码落盘到 `images/`，`<image>` 引用改写成相对路径，
 *   解码失败或找不到 binary 的图片直接跳过（不生成断链的 `<img>`）。
 *
 * 安全口径：
 * - 解析前显式拒绝含 `<!ENTITY` 的输入（XXE / 十亿笑声的入口）并抛 CORRUPT_INPUT；
 * - `<!DOCTYPE ... SYSTEM "...">` 在解析前被**剥除**：Jsoup 的 [Parser.xmlParser] 本身不解析 DTD、
 *   不解析外部实体、也不会发起网络请求，剥除是把"不会发生"固化成"结构上不可能发生"；
 * - 写盘全部走 [ParserIO]；ZIP 条目全部经 [ArchiveSupport]（条目数 / 单条目上限 / 压缩比 / 路径穿越）。
 */
class Fb2DocumentParser : DocumentParser {

    override fun parse(sourceFile: File, outputDirectory: File, titleHint: String?): ParsedDocument {
        if (!sourceFile.isFile) {
            throw DocumentParseException("INVALID_ARGUMENT", "FB2 源文件不存在：${sourceFile.name}")
        }
        val raw = try {
            readSource(sourceFile)
        } catch (error: DocumentParseException) {
            throw error
        } catch (error: ZipException) {
            throw DocumentParseException("CORRUPT_INPUT", "fb2.zip 容器损坏：${error.message}", error)
        } catch (error: IOException) {
            throw DocumentParseException("CORRUPT_INPUT", "读取 FB2 失败：${error.message}", error)
        }

        val xml = decodeFb2Bytes(raw).removePrefix("\uFEFF")
        if (xml.isBlank()) {
            throw DocumentParseException("CORRUPT_INPUT", "FB2 内容为空")
        }
        // 顺序很重要：先拒绝实体声明（含 internal subset 里的），再剥 DOCTYPE。
        // 反过来先剥 DOCTYPE 会把内联实体声明一起剥掉，XXE 检查就形同虚设。
        rejectEntityDeclarations(xml)
        val sanitizedXml = stripDoctypeDeclarations(xml)

        val document = Jsoup.parse(sanitizedXml, "", Parser.xmlParser())
        document.outputSettings().prettyPrint(false)
        val root = document.children().firstOrNull { localName(it) == "fictionbook" }
            ?: throw DocumentParseException("CORRUPT_INPUT", "不是 FB2 文档：缺少 <FictionBook> 根元素")

        val titleInfo = root.children()
            .firstOrNull { localName(it) == "description" }
            ?.children()
            ?.firstOrNull { localName(it) == "title-info" }
        val bookTitle = titleInfo?.children()
            ?.firstOrNull { localName(it) == "book-title" }
            ?.let { cleanText(it.text()) }
            ?.takeIf { it.isNotEmpty() }
        val authors = titleInfo?.children()
            ?.filter { localName(it) == "author" }
            ?.mapNotNull { author -> authorName(author) }
            ?.distinct()
            ?.joinToString(", ")
            ?.takeIf { it.isNotEmpty() }
        val title = bookTitle
            ?: titleHint?.let { cleanText(it) }?.takeIf { it.isNotEmpty() }
            ?: ParserIO.titleFromName(sourceFile.name)

        val images = Fb2ImageRegistry(outputDirectory, collectBinaries(root))
        val bodies = root.children().filter { localName(it) == "body" }
        if (bodies.isEmpty()) {
            throw DocumentParseException("CORRUPT_INPUT", "FB2 缺少 <body> 正文")
        }
        val drafts = mutableListOf<Fb2SectionDraft>()
        bodies.forEach { body -> collectBodySections(body, images, drafts, depth = 0) }
        if (drafts.isEmpty()) {
            throw DocumentParseException("CORRUPT_INPUT", "FB2 没有可读正文")
        }

        // 封面图在 <description><coverpage> 里，不在任何 section 中，需要单独解析一次。
        // 复用同一个 registry：若正文也引用了同一个 binary，不会重复落盘。
        val coverpageImage = root.children()
            .firstOrNull { localName(it) == "description" }
            ?.children()
            ?.firstOrNull { localName(it) == "coverpage" }
            ?.children()
            ?.firstOrNull { localName(it) == "image" }
        if (coverpageImage != null) {
            images.resolve(attrLocal(coverpageImage, "href"))
        }

        val files = mutableListOf<ParsedFile>()
        val sections = mutableListOf<ManifestSection>()
        val outline = mutableListOf<ManifestOutlineNode>()
        val nodeKeys = mutableListOf<String>()
        drafts.forEachIndexed { index, draft ->
            val sectionId = "section-${index + 1}"
            val relativePath = "sections/$sectionId.html"
            ParserIO.writeText(
                outputDirectory,
                relativePath,
                ParserIO.htmlDocument(draft.title, headingHtml(draft.title, draft.depth) + draft.html),
            )
            files += ParsedFile(relativePath, ManifestFileKind.SECTION)
            sections += ManifestSection(
                id = sectionId,
                orderIndex = index,
                title = draft.title,
                resourceHref = relativePath,
                charCount = draft.textLength,
            )
            val nodeKey = "toc-${index + 1}"
            nodeKeys += nodeKey
            outline += ManifestOutlineNode(
                nodeKey = nodeKey,
                // 父节一定在当前节之前入表（DFS 先序），所以这里能直接取到父 nodeKey。
                parentKey = draft.parentIndex?.let { nodeKeys.getOrNull(it) },
                orderIndex = index,
                title = draft.title,
                targetSectionId = sectionId,
            )
        }
        // 图片是在渲染各节时按需落盘的，所以要在 section 之后合并，保证 files 里两个来源都在。
        files += images.files

        return ParsedDocument(
            title = title,
            author = authors,
            format = ReaderFormat.FB2,
            files = files,
            sections = sections,
            outline = outline,
        )
    }

    // ---------------------------------------------------------------- 输入与安全

    /**
     * 读取 FB2 源：ZIP 容器（fb2.zip）只取一个 `*.fb2` 条目，单文件直接读。
     * ZIP 条目的上限、压缩比、路径穿越由 [ArchiveSupport] 统一把关。
     */
    private fun readSource(sourceFile: File): ByteArray {
        if (sourceFile.looksLikeZip()) {
            ZipFile(sourceFile).use { zip ->
                val support = ArchiveSupport()
                val entries = support.listFiles(zip)
                val candidate = entries
                    .filter { it.name.replace('\\', '/').lowercase(Locale.ROOT).endsWith(".fb2") }
                    .minWithOrNull(Comparator { left, right ->
                        ArchiveSupport.naturalCompare(left.name, right.name)
                    })
                    ?: throw DocumentParseException("CORRUPT_INPUT", "fb2.zip 内没有 .fb2 条目")
                return support.readEntry(zip, candidate)
            }
        }
        if (sourceFile.length() > ParserIO.MAX_TEXT_BYTES) {
            throw DocumentParseException("LIMIT_EXCEEDED", "FB2 文件超过 64 MiB 上限")
        }
        return sourceFile.readBytes()
    }

    /**
     * 按 XML 声明里的 encoding 解码；声明缺失或不可用时退回 [ParserIO.decodeText]（BOM / UTF-8 / GB18030）。
     * 不用 `String(bytes, Charset)` 之前先看声明，是因为俄文 FB2 常见 windows-1251，按 UTF-8 解会整篇乱码。
     */
    private fun decodeFb2Bytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) {
            throw DocumentParseException("CORRUPT_INPUT", "FB2 内容为空")
        }
        val head = String(bytes, 0, minOf(bytes.size, XML_DECLARATION_PROBE_BYTES), StandardCharsets.ISO_8859_1)
        val declared = XML_ENCODING.find(head)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
        if (declared != null && declared !in UTF_FAMILY_ENCODINGS) {
            val charset = runCatching { Charset.forName(declared) }.getOrNull()
            if (charset != null) {
                return String(bytes, charset)
            }
        }
        return ParserIO.decodeText(bytes)
    }

    /** XXE 闸门：任何实体声明都拒绝（外部实体、参数实体、十亿笑声都从 `<!ENTITY` 开始）。 */
    private fun rejectEntityDeclarations(xml: String) {
        if (xml.contains("<!ENTITY", ignoreCase = true)) {
            throw DocumentParseException(
                "CORRUPT_INPUT",
                "FB2 含实体声明（<!ENTITY），已拒绝解析以防止 XML 外部实体注入",
            )
        }
    }

    /**
     * 剥掉 `<!DOCTYPE ...>`（含 internal subset）。JSoup 本来就不解析 DTD，
     * 这里再剥一次是为了让"外部 DTD 不会被读取"成为代码结构上可断言的事实，
     * 而不是依赖第三方库的内部行为。
     */
    private fun stripDoctypeDeclarations(xml: String): String {
        val builder = StringBuilder(xml.length)
        var cursor = 0
        while (true) {
            val start = xml.indexOf("<!DOCTYPE", cursor, ignoreCase = true)
            if (start < 0) {
                builder.append(xml, cursor, xml.length)
                return builder.toString()
            }
            builder.append(xml, cursor, start)
            val end = findMarkupDeclarationEnd(xml, start)
            if (end < 0) {
                throw DocumentParseException("CORRUPT_INPUT", "FB2 的 DOCTYPE 声明未闭合")
            }
            cursor = end + 1
        }
    }

    /** 从 `<!` 之后扫到配对的 `>`；internal subset 的 `[...]` 与引号内的 `>` 不算结束。 */
    private fun findMarkupDeclarationEnd(text: String, start: Int): Int {
        var index = start + 2
        var bracketDepth = 0
        var quote: Char? = null
        while (index < text.length) {
            val ch = text[index]
            if (quote != null) {
                if (ch == quote) quote = null
            } else {
                when (ch) {
                    '"', '\'' -> quote = ch
                    '[' -> bracketDepth += 1
                    ']' -> if (bracketDepth > 0) bracketDepth -= 1
                    '>' -> if (bracketDepth == 0) return index
                }
            }
            index += 1
        }
        return -1
    }

    // ---------------------------------------------------------------- 元数据

    private fun authorName(author: Element): String? {
        val parts = AUTHOR_NAME_PARTS.mapNotNull { tag ->
            author.children()
                .firstOrNull { localName(it) == tag }
                ?.let { cleanText(it.text()) }
                ?.takeIf { it.isNotEmpty() }
        }
        if (parts.isNotEmpty()) return parts.joinToString(" ")
        return author.children()
            .firstOrNull { localName(it) == "nickname" }
            ?.let { cleanText(it.text()) }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun collectBinaries(root: Element): Map<String, Fb2Binary> {
        // 用 getAllElements 而不是 getElementsByTag：FB2 允许带命名空间前缀（<fb:binary>），
        // 按 localName 匹配才不会漏。
        val result = mutableMapOf<String, Fb2Binary>()
        root.getAllElements().forEach { element ->
            if (localName(element) != "binary") return@forEach
            val id = attrLocal(element, "id")?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            if (!result.containsKey(id)) {
                result[id] = Fb2Binary(
                    contentType = attrLocal(element, "content-type"),
                    base64 = element.text(),
                )
            }
        }
        return result
    }

    // ---------------------------------------------------------------- 结构与渲染

    /**
     * 一个 `<body>`：有 `<section>` 就按层级逐节展开，否则把顶层内容聚成一节
     * （"平铺 body" 常见于短篇/单章 FB2）。
     */
    private fun collectBodySections(
        body: Element,
        images: Fb2ImageRegistry,
        drafts: MutableList<Fb2SectionDraft>,
        depth: Int,
    ) {
        val childSections = body.children().filter { localName(it) == "section" }
        if (childSections.isNotEmpty()) {
            childSections.forEach { section ->
                walkSection(section, depth = 1, parentIndex = null, images = images, drafts = drafts)
            }
            return
        }
        val rendered = renderBlocks(body, images, depth)
        if (rendered.html.isBlank()) return
        val title = body.children()
            .firstOrNull { localName(it) == "title" }
            ?.let { cleanText(titleText(it)) }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_FLAT_TITLE
        drafts += Fb2SectionDraft(
            title = title,
            depth = 1,
            html = rendered.html,
            textLength = title.length + rendered.text.length,
            parentIndex = null,
        )
    }

    private fun walkSection(
        section: Element,
        depth: Int,
        parentIndex: Int?,
        images: Fb2ImageRegistry,
        drafts: MutableList<Fb2SectionDraft>,
    ) {
        if (depth > MAX_SECTION_DEPTH) {
            throw DocumentParseException("CORRUPT_INPUT", "FB2 section 层级超过 $MAX_SECTION_DEPTH 层")
        }
        val heading = section.children()
            .firstOrNull { localName(it) == "title" }
            ?.let { cleanText(titleText(it)) }
            ?.takeIf { it.isNotEmpty() }
        val ownContent = renderBlocks(section, images, depth)
        val index = drafts.size
        val title = heading ?: "第 ${index + 1} 节"
        drafts += Fb2SectionDraft(
            title = title,
            depth = depth,
            html = ownContent.html,
            textLength = title.length + ownContent.text.length,
            parentIndex = parentIndex,
        )
        // 先把自己放进 drafts 再递归子节：DFS 先序保证父节 index 永远小于子节。
        section.children().filter { localName(it) == "section" }.forEach { child ->
            walkSection(child, depth + 1, index, images, drafts)
        }
    }

    /**
     * 渲染一个节点下的块级内容。`title` / `section` 由上层单独处理（标题进 <h1..h3>、
     * 子节递归建节），这里跳过，避免同一段文字在父子两节里各出现一次。
     */
    private fun renderBlocks(parent: Element, images: Fb2ImageRegistry, depth: Int): Rendered {
        if (depth > MAX_RENDER_DEPTH) return Rendered.EMPTY
        val html = StringBuilder()
        val text = StringBuilder()
        parent.childNodes().forEach { node ->
            val rendered = when (node) {
                is TextNode -> {
                    val value = node.text().trim()
                    if (value.isEmpty()) Rendered.EMPTY
                    else Rendered("<p>${ParserIO.escapeHtml(value)}</p>", value)
                }
                is Element -> renderBlockElement(node, images, depth + 1)
                else -> Rendered.EMPTY
            }
            html.append(rendered.html)
            if (rendered.text.isNotEmpty()) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(rendered.text)
            }
        }
        return Rendered(html.toString(), text.toString())
    }

    private fun renderBlockElement(element: Element, images: Fb2ImageRegistry, depth: Int): Rendered {
        if (depth > MAX_RENDER_DEPTH) return Rendered.EMPTY
        return when (localName(element)) {
            "title", "section" -> Rendered.EMPTY
            "p" -> Rendered(
                html = "<p>${renderInline(element, images, depth + 1)}</p>",
                text = element.text().trim(),
            )
            "empty-line" -> Rendered(
                // 空行不需要正文文字，但必须占一个段落高度，否则诗意排版会被压没。
                html = "<p class=\"empty-line\"><br></p>",
                text = "",
            )
            "image" -> renderImage(element, images)
            "subtitle", "v", "text-author", "date" -> {
                val inline = renderInline(element, images, depth + 1)
                val value = element.text().trim()
                if (value.isEmpty() && inline.isBlank()) Rendered.EMPTY
                else Rendered("<p class=\"${localName(element)}\">$inline</p>", value)
            }
            "epigraph", "cite", "annotation" -> renderWrapped("blockquote", element, images, depth)
            "poem", "stanza", "table" -> renderWrapped("div class=\"${localName(element)}\"", element, images, depth)
            else -> {
                val hasBlockChild = element.children().any { localName(it) in BLOCK_TAGS }
                if (hasBlockChild) {
                    val inner = renderBlocks(element, images, depth + 1)
                    Rendered(
                        html = "<div class=\"fb2-${localName(element)}\">${inner.html}</div>",
                        text = inner.text,
                    )
                } else {
                    val inline = renderInline(element, images, depth + 1)
                    val value = element.text().trim()
                    // 有些容器标签只装图不装字：不能因为 text 为空就把图片一起丢掉。
                    if (value.isEmpty() && inline.isBlank()) Rendered.EMPTY else Rendered("<p>$inline</p>", value)
                }
            }
        }
    }

    private fun renderWrapped(tag: String, element: Element, images: Fb2ImageRegistry, depth: Int): Rendered {
        val inner = renderBlocks(element, images, depth + 1)
        if (inner.html.isBlank()) return Rendered.EMPTY
        return Rendered("<$tag>${inner.html}</$tag>", inner.text)
    }

    /** 行内渲染：只保留"语义"标签，`<a>` 丢链接保文字，其余未知标签退化成纯文本。 */
    private fun renderInline(element: Element, images: Fb2ImageRegistry, depth: Int): String {
        if (depth > MAX_RENDER_DEPTH) return ParserIO.escapeHtml(element.text())
        val builder = StringBuilder()
        element.childNodes().forEach { node ->
            when (node) {
                is TextNode -> builder.append(ParserIO.escapeHtml(node.text()))
                is Element -> builder.append(renderInlineElement(node, images, depth + 1))
                else -> Unit
            }
        }
        return builder.toString()
    }

    private fun renderInlineElement(element: Element, images: Fb2ImageRegistry, depth: Int): String {
        if (depth > MAX_RENDER_DEPTH) return ParserIO.escapeHtml(element.text())
        val inner = { renderInline(element, images, depth + 1) }
        return when (localName(element)) {
            "image" -> renderImage(element, images).html
            // <a> 的内链目标在本期不落 locator，先保留文字（脚注编号不丢）。
            "a" -> inner()
            "strong" -> "<strong>${inner()}</strong>"
            "emphasis" -> "<em>${inner()}</em>"
            "strikethrough" -> "<s>${inner()}</s>"
            "sub" -> "<sub>${inner()}</sub>"
            "sup" -> "<sup>${inner()}</sup>"
            "code" -> "<code>${inner()}</code>"
            else -> inner()
        }
    }

    private fun renderImage(element: Element, images: Fb2ImageRegistry): Rendered {
        val href = attrLocal(element, "href")
        val relativePath = images.resolve(href) ?: return Rendered.EMPTY
        val alt = attrLocal(element, "alt").orEmpty()
        // section HTML 在 sections/ 下，图片在 images/ 下，所以固定退一级。
        return Rendered(
            html = "<img src=\"../${ParserIO.escapeHtml(relativePath)}\" " +
                "alt=\"${ParserIO.escapeHtml(alt)}\">",
            text = "",
        )
    }

    private fun headingHtml(title: String, depth: Int): String {
        val level = depth.coerceIn(1, 3)
        return "<h$level>${ParserIO.escapeHtml(title)}</h$level>"
    }

    private fun titleText(title: Element): String {
        // XML 解析器不会在相邻 <p> 之间插空格（Element.text() 会直接拼起来），
        // FB2 的 <title><p>上篇</p><p>第一章</p></title> 必须手动分隔，否则标题粘成一串。
        val parts = title.children().mapNotNull { child ->
            cleanText(child.text()).takeIf { it.isNotEmpty() }
        }
        return if (parts.isNotEmpty()) parts.joinToString(" ") else cleanText(title.text())
    }

    // ---------------------------------------------------------------- 小工具

    private fun localName(element: Element): String =
        element.tagName().substringAfter(':').lowercase(Locale.ROOT)

    private fun attrLocal(element: Element, name: String): String? {
        element.attributes().forEach { attribute ->
            if (attribute.key.substringAfter(':').equals(name, ignoreCase = true)) {
                return attribute.value
            }
        }
        return null
    }

    private fun cleanText(value: String, maxLength: Int = 120): String {
        val builder = StringBuilder(value.length)
        value.forEach { ch ->
            if (ch.isISOControl()) {
                if (ch == '\n' || ch == '\t' || ch == '\r') builder.append(' ')
            } else {
                builder.append(ch)
            }
        }
        return builder.toString().replace(WHITESPACE, " ").trim().take(maxLength)
    }

    private data class Rendered(val html: String, val text: String) {
        companion object {
            val EMPTY = Rendered("", "")
        }
    }

    private data class Fb2SectionDraft(
        val title: String,
        val depth: Int,
        val html: String,
        val textLength: Int,
        val parentIndex: Int?,
    )

    private data class Fb2Binary(val contentType: String?, val base64: String)

    /**
     * 内嵌图片登记处：按需解码 + 落盘 + 去重。
     *
     * 之所以"按需"而不是解析前一次性解码：一本带几十张插图的 FB2 可能有几十 MB base64，
     * 只有正文真正引用到的二进制才值得解；没被引用的 `<binary>` 不落盘、也不进 manifest。
     */
    private class Fb2ImageRegistry(
        private val outputDirectory: File,
        private val binaries: Map<String, Fb2Binary>,
    ) {
        val files = mutableListOf<ParsedFile>()
        private val resolved = mutableMapOf<String, String>()
        private val failed = mutableSetOf<String>()
        private val usedNames = mutableSetOf<String>()

        /** 返回相对输出根目录的路径（如 `images/pic1.png`），解析失败返回 null。 */
        fun resolve(href: String?): String? {
            val id = href?.trim()?.removePrefix("#")?.trim().orEmpty()
            if (id.isEmpty()) return null
            resolved[id]?.let { return it }
            if (id in failed) return null
            val binary = binaries[id]
            if (binary == null) {
                failed += id
                return null
            }
            val bytes = try {
                MimeBase64.decode(binary.base64)
            } catch (_: IllegalArgumentException) {
                // 坏 base64 不值得让整本书导入失败，跳过这张图即可。
                failed += id
                return null
            }
            if (bytes.isEmpty()) {
                failed += id
                return null
            }
            val extension = imageExtension(binary.contentType, id)
            val relativePath = "images/${uniqueFileName(id, extension)}"
            ParserIO.writeBytes(outputDirectory, relativePath, bytes)
            files += ParsedFile(relativePath, ManifestFileKind.IMAGE)
            resolved[id] = relativePath
            return relativePath
        }

        private fun uniqueFileName(id: String, extension: String): String {
            val base = safeBaseName(id)
            val lowerBase = base.lowercase(Locale.ROOT)
            val stem = if (lowerBase.endsWith(".$extension")) {
                base.dropLast(extension.length + 1)
            } else {
                base
            }.ifBlank { "image" }
            var candidate = "$stem.$extension"
            var counter = 2
            // 大小写不敏感去重：Android 常见 ext4 区分大小写，但 FAT/exFAT SD 卡不区分，
            // 同一本书在两种存储上落盘结果必须一致，否则 manifest 会随设备变化。
            while (!usedNames.add(candidate.lowercase(Locale.ROOT))) {
                candidate = "$stem-$counter.$extension"
                counter += 1
            }
            return candidate
        }

        private fun safeBaseName(id: String): String {
            val builder = StringBuilder(id.length)
            id.forEach { ch ->
                builder.append(if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '-')
            }
            return builder.toString().trim('.', '-', '_').take(80).ifBlank { "image" }
        }

        private fun imageExtension(contentType: String?, id: String): String {
            val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            val fromType = when (type) {
                "image/png" -> "png"
                "image/jpeg", "image/jpg" -> "jpg"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                "image/bmp" -> "bmp"
                "image/svg+xml" -> "svg"
                "image/tiff" -> "tiff"
                else -> null
            }
            if (fromType != null) return fromType
            // content-type 缺失时从 id 后缀猜（FB2 常见 id="cover.jpg"）。
            val fromId = id.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return if (fromId in KNOWN_IMAGE_EXTENSIONS) fromId else "bin"
        }
    }

    private companion object {
        const val XML_DECLARATION_PROBE_BYTES = 512
        const val MAX_SECTION_DEPTH = 64
        const val MAX_RENDER_DEPTH = 64
        const val DEFAULT_FLAT_TITLE = "正文"

        val XML_ENCODING = Regex(
            """encoding\s*=\s*["']([A-Za-z0-9._:-]+)["']""",
            RegexOption.IGNORE_CASE,
        )
        val WHITESPACE = Regex("\\s+")
        val UTF_FAMILY_ENCODINGS = setOf("utf-8", "utf8", "utf-16", "utf-16le", "utf-16be", "utf-32")
        val AUTHOR_NAME_PARTS = listOf("first-name", "middle-name", "last-name")
        val KNOWN_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "tiff")
        val BLOCK_TAGS = setOf(
            "p", "empty-line", "image", "section", "title", "subtitle", "epigraph", "cite",
            "annotation", "poem", "stanza", "v", "table", "text-author", "date",
        )
    }
}
