package com.example.myapplication.ui.ai

import java.net.URI
import java.util.Locale
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.jsoup.Jsoup

/** An immutable, Android-independent display model. Message storage keeps the original Markdown. */
data class AgentMarkdownDocument(val blocks: List<AgentMarkdownBlock>) {
    val plainText: String = blocks.joinToString("\n\n", transform = ::readableBlockText).trimEnd()
}

sealed interface AgentMarkdownBlock {
    data class Paragraph(val text: AgentMarkdownText) : AgentMarkdownBlock
    data class Heading(val level: Int, val text: AgentMarkdownText) : AgentMarkdownBlock
    data class Quote(val blocks: List<AgentMarkdownBlock>) : AgentMarkdownBlock
    data class ListBlock(
        val ordered: Boolean,
        val startNumber: Int,
        val tight: Boolean,
        val items: List<List<AgentMarkdownBlock>>,
    ) : AgentMarkdownBlock
    /** Keep the exact decoded code, including its final newline, for the code copy action. */
    data class CodeBlock(val code: String, val language: String?) : AgentMarkdownBlock
    data class Table(
        val header: List<AgentMarkdownText>,
        val rows: List<List<AgentMarkdownText>>,
        val alignments: List<AgentMarkdownAlignment>,
    ) : AgentMarkdownBlock
    data object Divider : AgentMarkdownBlock
}

enum class AgentMarkdownAlignment { LEFT, CENTER, RIGHT }
enum class AgentMarkdownStyle { STRONG, EMPHASIS, CODE }
data class AgentMarkdownText(val text: String, val spans: List<AgentMarkdownSpan> = emptyList())

sealed interface AgentMarkdownSpan {
    val start: Int
    val end: Int
    data class Style(
        override val start: Int,
        override val end: Int,
        val kind: AgentMarkdownStyle,
    ) : AgentMarkdownSpan
    data class Link(
        override val start: Int,
        override val end: Int,
        val destination: String,
    ) : AgentMarkdownSpan
    /** A message-local source label, never a persisted source identity or an external URL. */
    data class Citation(
        override val start: Int,
        override val end: Int,
        val label: String,
    ) : AgentMarkdownSpan
}

/** CommonMark accepts unfinished fences and inline syntax naturally while an answer is streaming. */
object AgentMarkdownParser {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
    private val citationPattern = Regex("\\[S([1-9][0-9]*)]|【S([1-9][0-9]*)】")

    fun parse(markdown: String): AgentMarkdownDocument = AgentMarkdownDocument(blocks(parser.parse(markdown)))

    private fun blocks(parent: Node): List<AgentMarkdownBlock> = buildList {
        for (node in children(parent)) {
            when (node) {
                is Paragraph -> add(AgentMarkdownBlock.Paragraph(inline(node)))
                is Heading -> add(AgentMarkdownBlock.Heading(node.level, inline(node)))
                is BlockQuote -> add(AgentMarkdownBlock.Quote(blocks(node)))
                is BulletList -> add(AgentMarkdownBlock.ListBlock(
                    ordered = false, startNumber = 1, tight = node.isTight,
                    items = children(node).filterIsInstance<ListItem>().map(::blocks).toList(),
                ))
                is OrderedList -> add(AgentMarkdownBlock.ListBlock(
                    ordered = true, startNumber = node.markerStartNumber ?: 1, tight = node.isTight,
                    items = children(node).filterIsInstance<ListItem>().map(::blocks).toList(),
                ))
                is FencedCodeBlock -> add(AgentMarkdownBlock.CodeBlock(
                    node.literal, node.info.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() },
                ))
                is IndentedCodeBlock -> add(AgentMarkdownBlock.CodeBlock(node.literal, null))
                is TableBlock -> add(table(node))
                is ThematicBreak -> add(AgentMarkdownBlock.Divider)
                is HtmlBlock -> {
                    // HTML is displayed as native text. No WebView, script or resource loading is involved.
                    val html = Jsoup.parseBodyFragment(node.literal)
                    html.select("script,style").remove()
                    val text = html.body().wholeText().trim()
                    if (text.isNotBlank()) add(AgentMarkdownBlock.Paragraph(AgentMarkdownText(text)))
                }
                else -> addAll(blocks(node))
            }
        }
    }

    private fun table(table: TableBlock): AgentMarkdownBlock.Table {
        var header: List<AgentMarkdownText> = emptyList()
        var alignments: List<AgentMarkdownAlignment> = emptyList()
        val rows = mutableListOf<List<AgentMarkdownText>>()
        for (part in children(table)) {
            for (row in children(part).filterIsInstance<TableRow>()) {
                val cells = children(row).filterIsInstance<TableCell>().toList()
                if (part is TableHead) {
                    header = cells.map(::inline)
                    alignments = cells.map { cell ->
                        when (cell.alignment) {
                            TableCell.Alignment.CENTER -> AgentMarkdownAlignment.CENTER
                            TableCell.Alignment.RIGHT -> AgentMarkdownAlignment.RIGHT
                            else -> AgentMarkdownAlignment.LEFT
                        }
                    }
                } else if (part is TableBody) {
                    rows += cells.map(::inline)
                }
            }
        }
        return AgentMarkdownBlock.Table(header, rows, alignments)
    }

    private fun inline(parent: Node): AgentMarkdownText {
        val text = StringBuilder()
        val spans = mutableListOf<AgentMarkdownSpan>()
        fun visit(node: Node) {
            val start = text.length
            when (node) {
                is Text -> text.append(node.literal)
                is Code -> {
                    text.append(node.literal)
                    if (text.length > start) spans += AgentMarkdownSpan.Style(start, text.length, AgentMarkdownStyle.CODE)
                }
                is SoftLineBreak -> text.append(' ')
                is HardLineBreak -> text.append('\n')
                is HtmlInline -> {
                    if (node.literal.matches(Regex("(?i)<br\\s*/?>"))) text.append('\n')
                }
                is Image -> {
                    children(node).forEach(::visit)
                    if (text.length == start) text.append("图片")
                }
                else -> {
                    children(node).forEach(::visit)
                    if (text.length > start) {
                        when (node) {
                            is StrongEmphasis -> spans += AgentMarkdownSpan.Style(start, text.length, AgentMarkdownStyle.STRONG)
                            is Emphasis -> spans += AgentMarkdownSpan.Style(start, text.length, AgentMarkdownStyle.EMPHASIS)
                            is Link -> supportedLink(node.destination)?.let {
                                spans += AgentMarkdownSpan.Link(start, text.length, it)
                            }
                        }
                    }
                }
            }
        }
        children(parent).forEach(::visit)
        val literal = text.toString()
        for (match in citationPattern.findAll(literal)) {
            val number = (match.groups[1] ?: match.groups[2])?.value?.toIntOrNull() ?: continue
            val start = match.range.first
            val end = match.range.last + 1
            val protected = spans.any {
                it.start < end && it.end > start &&
                    (it is AgentMarkdownSpan.Link || it is AgentMarkdownSpan.Style && it.kind == AgentMarkdownStyle.CODE)
            }
            if (!protected) spans += AgentMarkdownSpan.Citation(start, end, "S$number")
        }
        return AgentMarkdownText(text.toString(), spans.toList())
    }

    private fun supportedLink(destination: String): String? {
        val uri = runCatching { URI(destination) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> destination.takeIf { !uri.rawAuthority.isNullOrBlank() }
            "mailto" -> destination.takeIf { !uri.rawSchemeSpecificPart.isNullOrBlank() }
            else -> null
        }
    }

    private fun children(parent: Node): Sequence<Node> = sequence {
        var child = parent.firstChild
        while (child != null) {
            yield(child)
            child = child.next
        }
    }
}

private fun readableBlockText(block: AgentMarkdownBlock): String = when (block) {
    is AgentMarkdownBlock.Paragraph -> block.text.text
    is AgentMarkdownBlock.Heading -> block.text.text
    is AgentMarkdownBlock.Quote -> block.blocks.joinToString("\n\n", transform = ::readableBlockText)
    is AgentMarkdownBlock.CodeBlock -> block.code.removeSuffix("\n")
    is AgentMarkdownBlock.Table -> (listOf(block.header) + block.rows)
        .joinToString("\n") { row -> row.joinToString("\t") { it.text } }
    is AgentMarkdownBlock.ListBlock -> block.items.mapIndexed { index, item ->
        val marker = if (block.ordered) (block.startNumber.toLong() + index).toString() + ". " else "• "
        val lines = item.joinToString("\n", transform = ::readableBlockText).lines()
        marker + lines.firstOrNull().orEmpty() + lines.drop(1).joinToString("") { "\n" + " ".repeat(marker.length) + it }
    }.joinToString("\n")
    AgentMarkdownBlock.Divider -> "────────"
}
