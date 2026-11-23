package com.example.myapplication.data.reader

import com.example.myapplication.domain.reader.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Retains source markup, attaching anchors only after matching the production block model. */
object ReflowDomProjector {
    fun project(html: String, blocks: List<ContentBlock>): String {
        require(blocks.isNotEmpty()) { "empty reflow section" }
        val section = blocks.first().sectionId
        var offset = 0
        blocks.forEachIndexed { index, block ->
            require(block.sectionId == section && block.blockId == "$section#b$index")
            require(block.canonicalStart == offset && block.canonicalEnd == offset + block.text.length)
            require(block.text.isNotBlank() && ReaderLocatorValidator.isWellFormedUtf16(block.text))
            require(block.extractionQuality == ExtractionQuality.VERIFIED_TEXT && block.fixedPageIndex == null)
            offset = Math.addExact(block.canonicalEnd, 1)
        }
        val doc = Jsoup.parse(html)
        doc.outputSettings().prettyPrint(false)
        doc.select("script,base,iframe,object,embed,meta[http-equiv]").remove()
        doc.allElements.forEach { element ->
            element.attributes().asList().map { it.key }.filter {
                it.startsWith("on", true) || it.startsWith("data-reader-", true) || it.equals("srcdoc", true)
            }.forEach(element::removeAttr)
            listOf("href", "src", "xlink:href", "action", "formaction").forEach { attr ->
                val value = element.attr(attr).filterNot(Char::isWhitespace).lowercase()
                if (value.startsWith("javascript:") || value.startsWith("vbscript:") || value.startsWith("data:")) element.removeAttr(attr)
            }
        }
        val candidates = mutableListOf<Pair<Element, Pair<BlockKind, String>>>()
        doc.body().select("h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,figcaption,table,img").forEach { element ->
            val kind = when (element.normalName()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> BlockKind.HEADING
                "li" -> BlockKind.LIST
                "figcaption" -> BlockKind.CAPTION
                "table" -> BlockKind.TABLE
                "img" -> BlockKind.IMAGE
                else -> BlockKind.PARAGRAPH
            }
            val text = when (kind) {
                BlockKind.TABLE -> element.select("tr").let { rows ->
                    if (rows.isEmpty()) element.text() else rows.joinToString("\n") { row -> row.select("th,td").joinToString(" | ") { it.text().trim() } }
                }
                BlockKind.IMAGE -> element.attr("alt").ifBlank { "[image]" }
                else -> element.text()
            }.trim()
            if (text.isNotEmpty()) candidates += element to (kind to text)
        }
        // Unstructured body-text fallback has no stable original element anchor.
        require(candidates.size == blocks.size) { "unsupported or changed reflow DOM" }
        candidates.zip(blocks).forEach { (candidate, block) ->
            require(candidate.second.first == block.kind && candidate.second.second == block.text) { "production text differs from DOM" }
            candidate.first.attr("data-reader-block-id", block.blockId)
        }
        ReflowDomSelectionProjector.annotate(doc, blocks)
        return doc.outerHtml()
    }
}
