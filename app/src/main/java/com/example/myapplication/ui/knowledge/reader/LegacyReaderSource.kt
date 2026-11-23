package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.notes.ReaderSelectionSnapshot
import com.example.myapplication.domain.reader.ReaderLocatorValidator

/** After-body/after-after-body character tokens are processed in the HTML5 in-body mode.
 * Jsoup puts whitespace outside body, and appending it loses order when later content reopens
 * body. Remove only parser-proven closing body/html tokens and parse again in the same mode.
 * Source ranges distinguish real tags from comments, attributes and script/textarea text.
 */
private fun legacyReaderBody(html: String): org.jsoup.nodes.Element {
    var source = html.replace("\r\n", "\n").replace('\r', '\n')
    while (true) {
        val document = org.jsoup.parser.Parser.htmlParser().setTrackPosition(true).parseInput(source, "")
        val endings = listOfNotNull(document.selectFirst("body"), document.selectFirst("html"))
            .map { it.endSourceRange() }.filter { it.isTracked }
            .map { it.start().pos() to it.end().pos() }
            .filter { (start, end) -> start >= 0 && end > start && end <= source.length &&
                Regex("^</(?:body|html)(?=[\\s/>])", RegexOption.IGNORE_CASE).containsMatchIn(source.substring(start, end)) }
            .distinct().sortedByDescending { it.first }
        if (endings.isEmpty()) return document.body()
        // Every pass removes at least one real token; repeated closing tags terminate too.
        for ((start, end) in endings) source = source.removeRange(start, end)
    }
}

/** Matches the existing local WebView text index, not the canonical prepared-engine block index. */
internal fun legacyReaderText(html: String): String {
    val body = legacyReaderBody(html)
    val pending = java.util.ArrayDeque<org.jsoup.nodes.Node>()
    pending.add(body)
    return buildString {
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (node is org.jsoup.nodes.Element && node.normalName() in setOf("script", "style", "noscript")) continue
            if (node is org.jsoup.nodes.TextNode) append(node.wholeText)
            else node.childNodes().asReversed().forEach(pending::addLast)
        }
    }
}

private data class LegacyTextRun(val blockId: String, val start: Int, val end: Int)
private data class LegacyTextIndex(val text: String, val runs: List<LegacyTextRun>)

/** Uses precisely the block selector and generated IDs installed by ReaderWebController. */
private fun legacyTextIndex(html: String, sectionId: String): LegacyTextIndex? {
    if (sectionId.isBlank()) return null
    val body = legacyReaderBody(html)
    body.select("h1,h2,h3,p,li,blockquote,pre,td,th").forEachIndexed { index, element ->
        if (element.attr("data-reader-block").isEmpty()) element.attr("data-reader-block", "block-$index")
    }
    val ids = body.select("[data-reader-block]").map { it.attr("data-reader-block") }
    if (ids.any { it.isBlank() || it == sectionId } || ids.distinct().size != ids.size) return null
    val text = StringBuilder()
    val runs = mutableListOf<LegacyTextRun>()
    val pending = java.util.ArrayDeque<org.jsoup.nodes.Node>()
    pending.add(body)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        if (node is org.jsoup.nodes.Element && (node.normalName() in setOf("script", "style", "noscript") ||
                node.hasAttr("data-reader-annotation-layer"))) continue
        if (node is org.jsoup.nodes.TextNode) {
            var owner = node.parentNode() as? org.jsoup.nodes.Element
            while (owner != null && !owner.hasAttr("data-reader-block")) owner = owner.parent()
            val blockId = owner?.attr("data-reader-block") ?: sectionId
            val start = text.length
            text.append(node.wholeText)
            if (text.length > start) {
                val previous = runs.lastOrNull()
                if (previous?.blockId == blockId && previous.end == start)
                    runs[runs.lastIndex] = previous.copy(end = text.length)
                else runs.add(LegacyTextRun(blockId, start, text.length))
            }
        } else node.childNodes().asReversed().forEach(pending::addLast)
    }
    return LegacyTextIndex(text.toString(), runs)
}

private fun legacyContext(text: String, from: Int, to: Int): String {
    var start = from.coerceAtLeast(0)
    var end = to.coerceAtMost(text.length)
    if (start in 1 until text.length && text[start].isLowSurrogate()) start++
    if (end in 1 until text.length && text[end].isLowSurrogate()) end--
    return text.substring(start, end.coerceAtLeast(start))
}

/** Null means the source cannot prove this complete contiguous selection. */
internal fun legacyReaderSelectionFragments(html: String, sectionId: String, start: Int, end: Int): List<ReaderSelectionSnapshot>? {
    val index = legacyTextIndex(html, sectionId) ?: return null
    if (start < 0 || end <= start || end > index.text.length || end.toLong() - start > 8192) return null
    if (!ReaderLocatorValidator.isWellFormedUtf16(index.text.substring(start, end))) return null
    val result = index.runs.mapNotNull { run ->
        val a = maxOf(start, run.start)
        val b = minOf(end, run.end)
        if (b <= a) return@mapNotNull null
        val quote = index.text.substring(a, b)
        if (quote.isBlank()) return@mapNotNull null
        if (!ReaderLocatorValidator.isWellFormedUtf16(quote)) return null
        ReaderSelectionSnapshot(sectionId, run.blockId, a, b, quote,
            legacyContext(index.text, a - 48, a), legacyContext(index.text, b, b + 48))
    }
    if (result.isEmpty() || result.size > 128) return null
    // One block identity cannot represent two disjoint source positions in BookmarkReadingOrder.
    if (result.map { it.blockId }.distinct().size != result.size) return null
    return result
}

/** Validates every field and source order before providing BookmarkReadingOrder's input. */
internal fun legacyReaderSelectionOrder(html: String, sectionId: String,
    fragments: List<ReaderSelectionSnapshot>): List<Pair<String, String>>? {
    if (fragments.isEmpty() || fragments.size > 128) return null
    val expected = legacyReaderSelectionFragments(html, sectionId,
        fragments.first().canonicalStart, fragments.last().canonicalEnd) ?: return null
    if (expected != fragments) return null
    return expected.map { sectionId to checkNotNull(it.blockId) }
}
