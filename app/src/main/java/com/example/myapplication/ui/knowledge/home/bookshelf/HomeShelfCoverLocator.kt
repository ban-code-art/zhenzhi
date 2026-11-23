package com.example.myapplication.ui.knowledge.home.bookshelf

import com.example.myapplication.domain.reader.SourceReadBudget
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.Locale
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

private const val MaxEpubXmlBytes = 256L * 1024
private const val MaxEpubEntries = 10_000
internal val ShelfRasterMimeTypes = setOf(
    "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/avif",
)

internal fun <T> shelfCoverWindow(entries: List<T>, anchor: Int): List<T> =
    listOfNotNull(entries.getOrNull(anchor), entries.getOrNull(anchor - 1), entries.getOrNull(anchor + 1))

internal fun epubCoverImagePath(zip: ZipFile, checkActive: () -> Unit): String? {
    if (zip.size() > MaxEpubEntries || zip.getEntry("META-INF/encryption.xml") != null) return null
    val container = zip.readCoverXml("META-INF/container.xml", checkActive) ?: return null
    val packagePath = epubResourcePath("", container.coverElements("rootfile").firstOrNull()?.attr("full-path"))
        ?: return null
    val opf = zip.readCoverXml(packagePath, checkActive) ?: return null
    val items = opf.coverElements("manifest").firstOrNull()?.children()?.filter { it.localTagName == "item" }
        ?: return null
    val legacyCoverId = opf.coverElements("metadata").firstOrNull()?.children()
        ?.firstOrNull { it.localTagName == "meta" && it.attr("name").equals("cover", ignoreCase = true) }
        ?.attr("content")?.takeIf { it.isNotBlank() }
    val rasterItems = items.filter { it.attr("media-type").lowercase(Locale.ROOT) in ShelfRasterMimeTypes }
    val item = rasterItems.firstOrNull {
        it.attr("properties").splitToSequence(' ', '\t', '\r', '\n').any { property -> property == "cover-image" }
    } ?: rasterItems.firstOrNull { legacyCoverId != null && it.attr("id") == legacyCoverId }
    return epubResourcePath(packagePath.substringBeforeLast('/', ""), item?.attr("href"))
}

private fun ZipFile.readCoverXml(path: String, checkActive: () -> Unit): Element? {
    val entry = getEntry(path) ?: return null
    if (entry.isDirectory || entry.size !in 1..MaxEpubXmlBytes) return null
    val bytes = getInputStream(entry).use { input ->
        SourceReadBudget(MaxEpubXmlBytes, checkActive).readAll(input, entry.size).takeIf { input.read() == -1 }
    } ?: return null
    return ByteArrayInputStream(bytes).use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
}

private val Element.localTagName: String get() = tagName().substringAfter(':')

private fun Element.coverElements(name: String): Sequence<Element> =
    allElements.asSequence().filter { it.localTagName == name }

private fun epubResourcePath(directory: String, href: String?): String? {
    val encoded = href?.substringBefore('#')?.takeIf { it.isNotBlank() && it.length <= 512 } ?: return null
    val path = try {
        URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (path.startsWith('/') || path.any { it == '\\' || it == ':' || it == '?' || it.isISOControl() }) return null
    val segments = ArrayDeque(directory.split('/').filter { it.isNotEmpty() })
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.removeLastOrNull() == null) return null
            else -> segments.addLast(segment)
        }
    }
    return segments.joinToString("/").takeIf { it.isNotBlank() && it.length <= 512 }
}
