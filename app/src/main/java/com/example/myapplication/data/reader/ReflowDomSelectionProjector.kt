package com.example.myapplication.data.reader

import com.example.myapplication.domain.reader.BlockKind
import com.example.myapplication.domain.reader.ContentBlock
import com.example.myapplication.domain.reader.ExtractionQuality
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import java.util.ArrayDeque
import java.util.Collections
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

private const val BLOCK_ID_ATTRIBUTE = "data-reader-block-id"
private const val STATUS_ATTRIBUTE = "data-reader-selection-status"
private const val REASON_ATTRIBUTE = "data-reader-selection-reason"
private const val MAP_ATTRIBUTE = "data-reader-selection-map"
private const val MAX_NODE_DEPTH = 128
private const val MAX_TEXT_NODES = 4096
private const val MAX_STRUCTURE_NODES = 16384
private const val UNSUPPORTED_DOM = "UNSUPPORTED_DOM"
private const val NESTED_BLOCK = "NESTED_BLOCK"
private const val SYNTHETIC_TEXT = "SYNTHETIC_TEXT"
private const val TEXT_NORMALIZATION_REQUIRED = "TEXT_NORMALIZATION_REQUIRED"

private val supportedInlineTags = setOf(
    "span", "b", "strong", "em", "i", "u", "s", "del", "mark", "small", "sub", "sup", "code", "a",
)

private fun <T> immutableCopy(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

internal data class ReflowDomTextEndpoint(val nodePath: List<Int>, val utf16Offset: Int)

internal class ReflowDomTextRun(
    nodePath: List<Int>,
    val rawText: String,
    val canonicalStart: Int,
    val canonicalEnd: Int,
) {
    val nodePath: List<Int> = immutableCopy(nodePath)
}

private enum class DomNodeKind { ELEMENT, TEXT, COMMENT }

private data class DomNodeSnapshot(
    val nodePath: List<Int>,
    val kind: DomNodeKind,
    val tagName: String? = null,
)

private data class RawDomText(val nodePath: List<Int>, val rawText: String)

private data class LeafScan(
    val textNodes: List<RawDomText>,
    val structure: List<DomNodeSnapshot>,
    val unavailableReason: String? = null,
)

/** Transient traversal state only; it never enters a frozen selection map. */
private class DomWalkFrame(val node: Node, val nodePath: List<Int>, var nextChild: Int = -1)

/**
 * A static value proof for one production block. No DOM object, document handle,
 * native selection identity, or token is retained or inferred from this map.
 */
internal class ReflowDomBlockSelectionMap private constructor(
    block: ContentBlock,
    private val rootTag: String,
    nodes: List<ReflowDomTextRun>,
    structure: List<DomNodeSnapshot>,
    val unavailableReason: String?,
) {
    val blockId: String = block.blockId
    val sectionId: String = block.sectionId
    val canonicalStart: Int = block.canonicalStart
    val canonicalEnd: Int = block.canonicalEnd
    val nodes: List<ReflowDomTextRun> = immutableCopy(nodes)
    private val structure: List<DomNodeSnapshot> = immutableCopy(structure)
    val isMappable: Boolean get() = unavailableReason == null

    fun canonicalOffset(currentRoot: Element, endpoint: ReflowDomTextEndpoint): Int? {
        if (!isMappable || currentRoot.normalName() != rootTag || currentRoot.attr(BLOCK_ID_ATTRIBUTE) != blockId) return null
        if (endpoint.nodePath.isEmpty() || endpoint.nodePath.size > MAX_NODE_DEPTH || endpoint.nodePath.any { it < 0 }) return null
        val run = nodes.firstOrNull { it.nodePath == endpoint.nodePath } ?: return null
        val offset = endpoint.utf16Offset
        if (offset < 0 || offset > run.rawText.length) return null
        if (offset > 0 && offset < run.rawText.length &&
            Character.isHighSurrogate(run.rawText[offset - 1]) && Character.isLowSurrogate(run.rawText[offset])) return null

        // Status/map attributes carry no authority. Recheck every text value and
        // every element/comment path, including empty inline elements.
        val current = scanLeaf(currentRoot)
        if (current.unavailableReason != null || current.structure != structure || current.textNodes.size != nodes.size) return null
        if (current.textNodes.indices.any { index ->
                current.textNodes[index].nodePath != nodes[index].nodePath || current.textNodes[index].rawText != nodes[index].rawText
            }) return null
        return run.canonicalStart + offset
    }

    internal companion object {
        fun fromVerifiedRoot(block: ContentBlock, root: Element): ReflowDomBlockSelectionMap {
            val tag = root.normalName()
            if (block.kind == BlockKind.TABLE || block.kind == BlockKind.IMAGE) {
                return ReflowDomBlockSelectionMap(block, tag, emptyList(), emptyList(), SYNTHETIC_TEXT)
            }
            val scanned = scanLeaf(root)
            if (scanned.unavailableReason != null) {
                return ReflowDomBlockSelectionMap(block, tag, emptyList(), emptyList(), scanned.unavailableReason)
            }
            // This is the exact original TextNode concatenation: no trimming,
            // normalization, inferred separator, quote search, or offset repair.
            if (scanned.textNodes.joinToString("") { it.rawText } != block.text) {
                return ReflowDomBlockSelectionMap(block, tag, emptyList(), emptyList(), TEXT_NORMALIZATION_REQUIRED)
            }
            var offset = block.canonicalStart
            val runs = scanned.textNodes.map { text ->
                val end = Math.addExact(offset, text.rawText.length)
                ReflowDomTextRun(text.nodePath, text.rawText, offset, end).also { offset = end }
            }
            return ReflowDomBlockSelectionMap(block, tag, runs, scanned.structure, null)
        }
    }
}

internal object ReflowDomSelectionProjector {
    fun annotate(document: Document, blocks: List<ContentBlock>): List<ReflowDomBlockSelectionMap> {
        val source = ArrayList(blocks)
        require(source.isNotEmpty()) { "empty reflow section" }
        val section = source.first().sectionId
        var expectedStart = 0L
        source.forEachIndexed { index, block ->
            require(block.sectionId == section && block.blockId == "$section#b$index") { "invalid production block identity" }
            require(block.canonicalStart.toLong() == expectedStart &&
                block.canonicalEnd.toLong() == expectedStart + block.text.length.toLong()) { "invalid production canonical range" }
            require(block.text.isNotBlank() && ReaderLocatorValidator.isWellFormedUtf16(block.text)) { "invalid production canonical text" }
            require(block.extractionQuality == ExtractionQuality.VERIFIED_TEXT && block.fixedPageIndex == null) { "invalid reflow text quality" }
            expectedStart = block.canonicalEnd.toLong() + 1L
            require(expectedStart <= Int.MAX_VALUE) { "production canonical range overflow" }
        }

        // Validate the entire source before writing any exact metadata. The
        // sanitized production projector emits precisely these anchors in order.
        val roots = document.getElementsByAttribute(BLOCK_ID_ATTRIBUTE).toList()
        require(roots.size == source.size) { "missing, duplicate, or unknown production block anchor" }
        roots.zip(source).forEach { (root, block) ->
            require(root.attr(BLOCK_ID_ATTRIBUTE) == block.blockId && isWithinBody(root, document.body())) { "invalid production block anchor" }
            require(productionKind(root) == block.kind) { "production block kind differs from DOM" }
            require(productionText(root, block.kind) == block.text) { "production text differs from DOM" }
        }
        val maps = immutableCopy(roots.zip(source).map { (root, block) ->
            ReflowDomBlockSelectionMap.fromVerifiedRoot(block, root)
        })
        document.outputSettings().prettyPrint(false)
        roots.zip(maps).forEach { (root, map) ->
            root.removeAttr(REASON_ATTRIBUTE)
            root.removeAttr(MAP_ATTRIBUTE)
            if (map.isMappable) {
                root.attr(STATUS_ATTRIBUTE, "exact")
                root.attr(MAP_ATTRIBUTE, mapJson(map))
            } else {
                root.attr(STATUS_ATTRIBUTE, "unavailable")
                root.attr(REASON_ATTRIBUTE, requireNotNull(map.unavailableReason))
            }
        }
        return maps
    }

    /** The actual WebView preparation handoff, preserving the existing CSP. */
    fun prepareForWebView(html: String): String {
        val document = Jsoup.parse(html)
        document.outputSettings().prettyPrint(false)
        document.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy")
            .attr("content", "default-src 'none'; script-src 'none'; style-src 'self' 'unsafe-inline'; img-src 'self'; font-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'none'")
        return document.outerHtml()
    }
}

private fun isWithinBody(root: Element, body: Element): Boolean {
    var node: Node? = root
    while (node != null) {
        if (node === body) return true
        node = node.parentNode()
    }
    return false
}

private fun productionKind(root: Element): BlockKind? = when (root.normalName()) {
    "h1", "h2", "h3", "h4", "h5", "h6" -> BlockKind.HEADING
    "p", "blockquote", "pre" -> BlockKind.PARAGRAPH
    "li" -> BlockKind.LIST
    "figcaption" -> BlockKind.CAPTION
    "table" -> BlockKind.TABLE
    "img" -> BlockKind.IMAGE
    else -> null
}

/** Only the source precheck follows the existing production canonical rules. */
private fun productionText(root: Element, kind: BlockKind): String = when (kind) {
    BlockKind.TABLE -> root.select("tr").let { rows ->
        if (rows.isEmpty()) root.text() else rows.joinToString("\n") { row ->
            row.select("th,td").joinToString(" | ") { it.text().trim() }
        }
    }
    BlockKind.IMAGE -> root.attr("alt").ifBlank { "[image]" }
    else -> root.text()
}.trim()

/** Depth-first document order, with at most one transient frame per depth. */
private fun scanLeaf(root: Element): LeafScan {
    val frames = ArrayDeque<DomWalkFrame>()
    val texts = ArrayList<RawDomText>()
    val structure = ArrayList<DomNodeSnapshot>()
    var nestedBlock = false
    var unsupported = false
    var visitedNodes = 0
    frames.addLast(DomWalkFrame(root, emptyList()))
    while (frames.isNotEmpty()) {
        val frame = requireNotNull(frames.peekLast())
        if (frame.nextChild == -1) {
            visitedNodes++
            if (visitedNodes > MAX_STRUCTURE_NODES || frame.nodePath.size > MAX_NODE_DEPTH) {
                return LeafScan(emptyList(), emptyList(), UNSUPPORTED_DOM)
            }
            val path = immutableCopy(frame.nodePath)
            when (val node = frame.node) {
                is Element -> {
                    if (node !== root) {
                        if (node.hasAttr(BLOCK_ID_ATTRIBUTE)) nestedBlock = true
                        if (node.normalName() !in supportedInlineTags) unsupported = true
                    }
                    structure += DomNodeSnapshot(path, DomNodeKind.ELEMENT, node.normalName())
                }
                is TextNode -> {
                    if (texts.size >= MAX_TEXT_NODES || !ReaderLocatorValidator.isWellFormedUtf16(node.wholeText)) {
                        return LeafScan(emptyList(), emptyList(), UNSUPPORTED_DOM)
                    }
                    texts += RawDomText(path, node.wholeText)
                    structure += DomNodeSnapshot(path, DomNodeKind.TEXT)
                }
                is Comment -> structure += DomNodeSnapshot(path, DomNodeKind.COMMENT)
                else -> unsupported = true
            }
            frame.nextChild = 0
        }
        if (frame.nextChild < frame.node.childNodeSize()) {
            val index = frame.nextChild++
            frames.addLast(DomWalkFrame(frame.node.childNode(index), frame.nodePath + index))
        } else {
            frames.removeLast()
        }
    }
    val reason = when {
        nestedBlock -> NESTED_BLOCK
        unsupported -> UNSUPPORTED_DOM
        else -> null
    }
    return LeafScan(immutableCopy(texts), immutableCopy(structure), reason)
}

private fun mapJson(map: ReflowDomBlockSelectionMap): String = buildJsonObject {
    put("version", 1)
    put("blockId", map.blockId)
    put("sectionId", map.sectionId)
    put("canonicalStart", map.canonicalStart)
    put("canonicalEnd", map.canonicalEnd)
    put("nodes", buildJsonArray {
        map.nodes.forEach { run ->
            add(buildJsonObject {
                put("path", buildJsonArray { run.nodePath.forEach { add(it) } })
                put("text", run.rawText)
                put("canonicalStart", run.canonicalStart)
                put("canonicalEnd", run.canonicalEnd)
            })
        }
    })
}.toString()
