package com.example.myapplication.data.reader.export

/**
 * Markdown 导出器（W5-05）。
 *
 * 目标（详细设计 §5.1）：
 * - 头部含书名/作者/格式/章节/真实页或百分比；
 * - 用户笔记正文与原文 quote 分块，原文统一用 `> ` 引用块；
 * - 每个条目带 `<!-- locator: {...} -->`，离开应用也能看懂出处；
 * - 来源有可读 fallback（书名 + 章节 + 页码/百分比）。
 *
 * 本文件是纯函数（不碰磁盘、不碰数据库），便于 JVM 单测直接断言分块与 locator 注释。
 *
 * 注意：locator 的 JSON 原样写入 HTML 注释；为防止 quote 里的 `<!--`/`-->` 提前结束注释，
 * 序列化时把 `<`/`>` 写成 `\u003c`/`\u003e`（JSON 语义完全等价，解析后仍是原字符）。
 */
data class MarkdownEntry(
    /** 文档内序号（从 1 开始）。 */
    val index: Int,
    /** 展示类型：高亮/笔记/手写。 */
    val typeLabel: String,
    /** 颜色中文名（可空）。 */
    val colorLabel: String?,
    /** 可读来源：书名 · 章节 · 页码/百分比。 */
    val sourceLine: String,
    /** 该条目的 locator JSON（包内命名空间）。 */
    val locatorJson: String,
    val quote: String?,
    val noteText: String?,
)

/** 按章节分组的标注块。 */
data class MarkdownSectionBlock(
    val title: String?,
    val locatorJson: String?,
    val entries: List<MarkdownEntry>,
)

/** 单文档 Markdown 渲染输入。 */
data class MarkdownDocumentInput(
    val title: String,
    val author: String?,
    val format: String,
    val sourceLine: String,
    val documentLocatorJson: String?,
    val sections: List<MarkdownSectionBlock>,
    val noteBody: String,
    val exportId: String,
    val exportedAt: Long,
)

object MarkdownExporter {
    /** 笔记正文起始标记；标记行之后的全部内容 = 笔记本正文原文。 */
    const val NOTE_BODY_MARKER: String = "<!-- aculearn:note-body -->"

    private const val ENTRY_SEPARATOR = "---"

    fun render(document: MarkdownDocumentInput): String {
        val out = StringBuilder()
        out.append("# ").append(document.title).append('\n').append('\n')
        out.append("> 作者：").append(document.author?.takeIf { it.isNotBlank() } ?: "未注明").append('\n')
        out.append("> 格式：").append(document.format).append('\n')
        out.append("> 来源：").append(document.sourceLine).append('\n')
        out.append("> 导出：").append(document.exportId).append(" · ").append(document.exportedAt).append('\n')
        document.documentLocatorJson?.let { locator ->
            out.append('\n').append(locatorComment(locator)).append('\n')
        }
        out.append('\n')

        document.sections.forEach { section ->
            out.append("## ").append(section.title?.takeIf { it.isNotBlank() } ?: "标注").append('\n').append('\n')
            section.locatorJson?.let { locator ->
                out.append(locatorComment(locator)).append('\n').append('\n')
            }
            section.entries.forEach { entry ->
                out.append("### 第 ").append(entry.index).append(" 条（").append(entry.typeLabel)
                entry.colorLabel?.takeIf { it.isNotBlank() }?.let { out.append(" · ").append(it) }
                out.append("）").append('\n').append('\n')
                out.append("**来源**：").append(entry.sourceLine).append('\n').append('\n')
                out.append(locatorComment(entry.locatorJson)).append('\n').append('\n')
                entry.quote?.let { quote ->
                    val block = quoteBlock(quote)
                    if (block.isNotEmpty()) {
                        out.append(block).append('\n').append('\n')
                    }
                }
                entry.noteText?.takeIf { it.isNotBlank() }?.let { note ->
                    out.append(note).append('\n').append('\n')
                }
                out.append(ENTRY_SEPARATOR).append('\n').append('\n')
            }
        }

        out.append("## 笔记正文\n\n")
        document.documentLocatorJson?.let { locator ->
            out.append(locatorComment(locator)).append('\n').append('\n')
        }
        out.append(NOTE_BODY_MARKER).append('\n')
        // 正文原样追加，不再补换行：parseNoteBody 才能逐字符还原。
        out.append(document.noteBody)
        return out.toString()
    }

    /** 原文引用块：每行前缀 `> `，空行写成 `>`。 */
    fun quoteBlock(quote: String): String {
        val normalized = quote.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isBlank()) return ""
        return normalized.split('\n').joinToString("\n") { line ->
            if (line.isEmpty()) ">" else "> $line"
        }
    }

    fun locatorComment(locatorJson: String): String =
        "<!-- locator: ${sanitizeJsonForComment(locatorJson)} -->"

    /** 把 `<`/`>` 转成 JSON 的 unicode 转义，语义不变但不会破坏 HTML 注释。 */
    fun sanitizeJsonForComment(locatorJson: String): String =
        locatorJson.replace("<", "\\u003c").replace(">", "\\u003e")

    /**
     * 可读来源 fallback：书名 · 章节 · 页码/百分比。
     * 没有章节/页码时给出明确说明，而不是留空。
     */
    fun readableSource(
        title: String,
        sectionTitle: String?,
        pageIndex: Int?,
        progression: Float?,
    ): String = buildString {
        append(title)
        sectionTitle?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        append(" · ").append(pageLabel(pageIndex, progression))
    }

    /** 人类页号 = pageIndex + 1；无页码则给百分比；都没有则明确“位置未记录”。 */
    fun pageLabel(pageIndex: Int?, progression: Float?): String {
        if (pageIndex != null && pageIndex >= 0) return "第 ${pageIndex + 1} 页"
        if (progression != null && progression.isFinite()) {
            val percent = (progression.coerceIn(0f, 1f) * 100f)
            // 固定 Locale.US：导出文本不随系统区域变化（同一文档同一份 Markdown）。
            return "位置 " + String.format(java.util.Locale.US, "%.1f", percent) + "%"
        }
        return "位置未记录"
    }

    /**
     * 从导出 Markdown 还原笔记本正文（恢复用）。
     * 标记行之后的全部内容原样返回；没有标记返回 null（视为损坏/非本应用导出）。
     */
    fun parseNoteBody(markdown: String): String? {
        val index = markdown.indexOf(NOTE_BODY_MARKER)
        if (index < 0) return null
        var rest = markdown.substring(index + NOTE_BODY_MARKER.length)
        if (rest.startsWith("\r\n")) {
            rest = rest.substring(2)
        } else if (rest.startsWith("\n")) {
            rest = rest.substring(1)
        }
        return rest
    }

    /** 标注类型展示名。 */
    fun typeLabel(type: String): String = when (type.uppercase()) {
        "HIGHLIGHT" -> "高亮"
        "NOTE" -> "笔记"
        "INK" -> "手写"
        else -> type
    }
}
