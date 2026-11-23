package com.example.myapplication.data.reader.importer

import com.example.myapplication.data.reader.storage.ManifestFileKind
import com.example.myapplication.data.reader.storage.ManifestOutlineNode
import com.example.myapplication.data.reader.storage.ManifestSection
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.CustomNode
import org.commonmark.node.Document
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup

/**
 * [attachments] 为 null 时保持既有行为：图片引用原样渲染（R15 手工放置资源的 revision 依赖这一点）。
 * 提供附件来源时（导入 Worker / 重新解析），每个图片引用要么指向 revision 内按内容寻址的资源，
 * 要么替换为带原因的可见占位，并写出 [ATTACHMENT_INDEX_PATH] 附件索引。
 */
class MarkdownDocumentParser(
    private val attachments: MarkdownAttachmentProvider? = null,
    private val limits: MarkdownAttachmentLimits = MarkdownAttachmentLimits(),
) : DocumentParser {
    override fun parse(sourceFile: File, outputDirectory: File, titleHint: String?): ParsedDocument =
        parse(sourceFile, outputDirectory, titleHint, attachments)

    fun parse(
        sourceFile: File,
        outputDirectory: File,
        titleHint: String?,
        attachments: MarkdownAttachmentProvider?,
    ): ParsedDocument {
        if (sourceFile.length() > ParserIO.MAX_TEXT_BYTES) {
            throw DocumentParseException("LIMIT_EXCEEDED", "Markdown 文件超过 64 MiB 限制")
        }
        val markdown = readMarkdown(sourceFile)
        if (markdown.isBlank()) throw DocumentParseException("CORRUPT_INPUT", "Markdown 文件没有可读正文")

        val document = parser.parse(markdown)
        val files = mutableListOf<ParsedFile>()
        if (attachments != null) {
            files += AttachmentMaterializer(attachments, limits, outputDirectory).apply(document)
        }
        val fallbackTitle = ParserIO.titleFromName(sourceFile.name)
        val firstChapter = generateSequence(document.firstChild) { it.next }
            .filterIsInstance<Heading>().firstOrNull { it.level == 1 }
        val chapterTitle = firstChapter?.let(::headingText)
        val chunks = splitByHeadings(document, fallbackTitle)
        val title = titleHint?.trim()?.takeIf { it.isNotEmpty() }
            ?: chapterTitle
            ?: fallbackTitle
        val sectionFiles = mutableListOf<ParsedFile>()
        val sections = mutableListOf<ManifestSection>()
        val outline = mutableListOf<ManifestOutlineNode>()

        chunks.forEachIndexed { index, chunk ->
            val sectionId = "section-${index + 1}"
            val path = "$SECTION_DIRECTORY/${sectionId}-${ParserIO.slug(chunk.title)}.html"
            val body = renderer.render(chunk.document)
            val extraCss = if (body.contains(PLACEHOLDER_CLASS_ATTRIBUTE)) PLACEHOLDER_CSS else ""
            val html = ParserIO.htmlDocument(chunk.title, body, extraCss)
            ParserIO.writeText(outputDirectory, path, html)
            sectionFiles += ParsedFile(path, ManifestFileKind.SECTION)
            sections += ManifestSection(
                id = sectionId,
                orderIndex = index,
                title = chunk.title,
                resourceHref = path,
                charCount = Jsoup.parse(body).body().text().length,
            )
            val rootHeading = (chunk.document.firstChild as? Heading)?.takeIf { it.level == 1 }
            val rootKey = "toc-${outline.size + 1}"
            outline += ManifestOutlineNode(
                nodeKey = rootKey,
                orderIndex = outline.size,
                title = chunk.title,
                targetSectionId = sectionId,
            )
            val parents = mutableListOf((rootHeading?.level ?: 0) to rootKey)
            generateSequence(chunk.document.firstChild) { it.next }.filterIsInstance<Heading>()
                .filter { it !== rootHeading }.forEach { heading ->
                    while (parents.size > 1 && parents.last().first >= heading.level) parents.removeAt(parents.lastIndex)
                    val key = "toc-${outline.size + 1}"
                    outline += ManifestOutlineNode(
                        nodeKey = key,
                        parentKey = parents.last().second,
                        orderIndex = outline.size,
                        title = headingText(heading),
                        targetSectionId = sectionId,
                    )
                    parents += heading.level to key
                }
        }

        return ParsedDocument(
            title = title,
            author = null,
            format = ReaderFormat.MARKDOWN,
            // 章节在前，保持无附件时的文件顺序与旧版本完全一致。
            files = sectionFiles + files,
            sections = sections,
            outline = outline,
        )
    }

    /** Split only top-level H1 AST nodes. Code fences, Setext and nested blocks keep CommonMark semantics. */
    private fun splitByHeadings(document: Node, fallbackTitle: String): List<Chunk> {
        val hasChapter = generateSequence(document.firstChild) { it.next }
            .any { it is Heading && it.level == 1 }
        val chunks = mutableListOf<Chunk>()
        var chunk = Chunk(if (hasChapter) "前言" else fallbackTitle, Document())
        var node = document.firstChild
        while (node != null) {
            // appendChild detaches the node from its old parent, so retain its successor first.
            val next = node.next
            if (node is Heading && node.level == 1) {
                if (chunk.document.firstChild != null) chunks += chunk
                chunk = Chunk(headingText(node), Document())
            }
            chunk.document.appendChild(node)
            node = next
        }
        if (chunk.document.firstChild != null) chunks += chunk
        return chunks
    }

    private fun headingText(node: Heading): String = plainText(node).trim().take(80).ifBlank { "正文" }

    private data class Chunk(val title: String, val document: Document)

    /** 把 AST 中的图片引用落地为 revision 资源或占位节点；返回需写入 manifest 的附加文件。 */
    private class AttachmentMaterializer(
        private val provider: MarkdownAttachmentProvider,
        private val limits: MarkdownAttachmentLimits,
        private val outputDirectory: File,
    ) {
        private val records = linkedMapOf<String, MarkdownAttachmentRecord>()
        private val resourcesBySha = linkedMapOf<String, String>()
        private var totalBytes = 0L

        fun apply(document: Node): List<ParsedFile> {
            val images = mutableListOf<Image>()
            document.accept(object : AbstractVisitor() {
                override fun visit(image: Image) {
                    images += image
                    visitChildren(image)
                }
            })
            images.forEach(::rewrite)
            if (records.isEmpty()) return emptyList()
            val files = resourcesBySha.values.map { ParsedFile(it, ManifestFileKind.IMAGE) }.toMutableList()
            ParserIO.writeText(outputDirectory, ATTACHMENT_INDEX_PATH, MarkdownAttachmentIndexCodec.encode(records.values.toList()))
            files += ParsedFile(ATTACHMENT_INDEX_PATH, ManifestFileKind.OTHER)
            return files
        }

        private fun rewrite(image: Image) {
            val raw = image.destination.orEmpty()
            val status = when (val target = MarkdownResourceReference.classify(raw)) {
                MarkdownResourceTarget.Ignored -> return
                MarkdownResourceTarget.Remote -> MarkdownAttachmentStatus.REMOTE_NOT_LOADED
                is MarkdownResourceTarget.Rejected -> target.status
                is MarkdownResourceTarget.Local -> {
                    val record = records.getOrPut(target.path) { materialize(target.path) }
                    if (record.status == MarkdownAttachmentStatus.COLLECTED) {
                        image.destination = "../${record.resource}"
                        return
                    }
                    record.status
                }
            }
            val placeholder = MissingAttachmentNode(status, raw, plainText(image).trim())
            image.insertBefore(placeholder)
            image.unlink()
        }

        private fun materialize(path: String): MarkdownAttachmentRecord {
            fun failed(status: MarkdownAttachmentStatus) = MarkdownAttachmentRecord(path, status)
            val available = when (val resolution = provider.resolve(path)) {
                is MarkdownAttachmentResolution.Unavailable -> return failed(resolution.status)
                is MarkdownAttachmentResolution.Available -> resolution
            }
            val file = available.file
            if (!file.isFile) return failed(MarkdownAttachmentStatus.READ_FAILED)
            val bytes = file.length()
            if (bytes > limits.maxBytes) return failed(MarkdownAttachmentStatus.LIMIT_EXCEEDED)
            val sha = try {
                sha256(file)
            } catch (_: IOException) {
                return failed(MarkdownAttachmentStatus.READ_FAILED)
            }
            // 私有副本被篡改/截断：不信任，不发布。
            if (available.expectedSha256 != null && available.expectedSha256 != sha) {
                return failed(MarkdownAttachmentStatus.READ_FAILED)
            }
            val extension = try {
                MarkdownImageSniffer.extensionOf(file)
            } catch (_: IOException) {
                return failed(MarkdownAttachmentStatus.READ_FAILED)
            } ?: return failed(MarkdownAttachmentStatus.UNSUPPORTED_TYPE)
            val resource = resourcesBySha[sha] ?: run {
                if (resourcesBySha.size >= limits.maxCount || totalBytes + bytes > limits.maxTotalBytes) {
                    return failed(MarkdownAttachmentStatus.LIMIT_EXCEEDED)
                }
                val relative = "$RESOURCE_DIRECTORY/md-$sha.$extension"
                try {
                    ParserIO.copyFile(outputDirectory, relative, file)
                } catch (error: IOException) {
                    throw DocumentParseException("STORAGE_FAILURE", "无法写入 Markdown 图片资源", error)
                }
                totalBytes += bytes
                resourcesBySha[sha] = relative
                relative
            }
            return MarkdownAttachmentRecord(path, MarkdownAttachmentStatus.COLLECTED, sha, bytes, extension, resource)
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /** 未能导入的图片：渲染为带状态与原因的可见占位，不保留指向不存在文件的 `<img>`。 */
    private class MissingAttachmentNode(
        val status: MarkdownAttachmentStatus,
        val reference: String,
        val alt: String,
    ) : CustomNode()

    private class MissingAttachmentRenderer(private val context: HtmlNodeRendererContext) : NodeRenderer {
        override fun getNodeTypes(): Set<Class<out Node>> = setOf(MissingAttachmentNode::class.java)

        override fun render(node: Node) {
            val missing = node as MissingAttachmentNode
            val label = buildString {
                append("图片未导入（").append(missing.status.label).append("）")
                if (missing.alt.isNotEmpty()) append("：").append(missing.alt)
            }
            val writer = context.writer
            writer.tag(
                "span",
                linkedMapOf(
                    "class" to PLACEHOLDER_CLASS,
                    "role" to "img",
                    "data-attachment-status" to missing.status.name,
                    "data-attachment-ref" to missing.reference,
                    "aria-label" to label,
                ),
            )
            writer.tag("/span")
        }
    }

    companion object {
        const val SECTION_DIRECTORY = "content"
        const val RESOURCE_DIRECTORY = "resources"
        const val ATTACHMENT_INDEX_PATH = "resources/markdown-attachments.json"
        const val PLACEHOLDER_CLASS = "md-attachment-missing"
        private const val PLACEHOLDER_CLASS_ATTRIBUTE = "class=\"$PLACEHOLDER_CLASS\""
        private const val PLACEHOLDER_CSS =
            " .md-attachment-missing { display: inline-block; margin: .3em 0; padding: 4px 8px;" +
                " border: 1px dashed #b9875a; border-radius: 4px; color: #8a5a2b; font-size: .85em; line-height: 1.5; }" +
                " .md-attachment-missing::before { content: attr(aria-label); }"

        private val extensions = listOf(TablesExtension.create())
        private val parser: Parser = Parser.builder().extensions(extensions).build()
        private val renderer: HtmlRenderer = HtmlRenderer.builder()
            .extensions(extensions)
            .escapeHtml(true)
            .nodeRendererFactory { context -> MissingAttachmentRenderer(context) }
            .build()

        /** 与解析同一套解码与换行规范化，供导入入口在授权有效期内提取图片引用。 */
        fun readMarkdown(file: File): String = ParserIO.decodeText(file.readBytes())
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        /** 按 CommonMark 语义提取图片目标（不含代码块中的文本），顺序为文档顺序。 */
        fun imageDestinations(markdown: String): List<String> {
            val destinations = mutableListOf<String>()
            parser.parse(markdown).accept(object : AbstractVisitor() {
                override fun visit(image: Image) {
                    image.destination?.let(destinations::add)
                    visitChildren(image)
                }
            })
            return destinations
        }

        private fun plainText(node: Node): String = when (node) {
            is Text -> node.literal
            is Code -> node.literal
            is SoftLineBreak, is HardLineBreak -> " "
            is MissingAttachmentNode -> node.alt
            else -> generateSequence(node.firstChild) { it.next }.joinToString("") { plainText(it) }
        }
    }
}
