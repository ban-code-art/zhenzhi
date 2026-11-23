package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.NormalizedRect
import com.example.myapplication.domain.reader.ReaderLocatorValidator

/** Bounds are PDF text-position cells in the rotated, cropped rendered page, normalized to 0..1. */
data class PdfTextGlyph(val text: String, val start: Int, val end: Int, val rect: NormalizedRect)
data class PdfTextSelection(val pageIndex: Int, val text: String, val start: Int, val end: Int,
    val rects: List<NormalizedRect>)
data class PdfPageTextRange(val page: PdfTextPage, val start: Int, val end: Int)

data class PdfTextPage(val pageIndex: Int, val width: Int, val height: Int, val rotationDegrees: Int,
    val glyphs: List<PdfTextGlyph>, val unavailableReason: String? = null) {
    val text: String = glyphs.joinToString("") { it.text }
    val selectable: Boolean get() = unavailableReason == null && pageIndex >= 0 && width > 0 && height > 0 &&
        rotationDegrees in setOf(0,90,180,270) && glyphs.isNotEmpty() &&
        glyphs.joinToString("") { it.text } == text &&
        glyphs.first().start == 0 && glyphs.last().end == text.length &&
        glyphs.all { it.start >= 0 && it.end > it.start && it.end.toLong()-it.start == it.text.length.toLong() &&
            it.text.isNotEmpty() && ReaderLocatorValidator.isWellFormedUtf16(it.text) && PdfTextGeometry.valid(it.rect) } &&
        glyphs.zipWithNext().all { (a,b) -> a.end == b.start }

    /** Never split a Unicode glyph/ligature or invent fractional character boxes. */
    fun select(start: Int, end: Int): PdfTextSelection? {
        if (!selectable || start < 0 || end <= start || end > text.length) return null
        val selected = glyphs.filter { it.end > start && it.start < end }
        if (selected.isEmpty() || selected.first().start != start || selected.last().end != end) return null
        if (selected.zipWithNext().any { (a,b) -> a.end != b.start }) return null
        val quote = selected.joinToString("") { it.text }
        if (quote.isBlank() || !ReaderLocatorValidator.isWellFormedUtf16(quote) ||
            quote.codePointCount(0,quote.length) > 8192) return null
        // Keep actual cells, including line/column breaks; never union an entire selection box.
        return PdfTextSelection(pageIndex, quote, start, end,
            java.util.Collections.unmodifiableList(selected.map { it.rect }))
    }

    fun glyphAt(x: Float, y: Float): PdfTextGlyph? = if (!selectable || !x.isFinite() || !y.isFinite()) null
        else glyphs.firstOrNull { x >= it.rect.left && x <= it.rect.right && y >= it.rect.top && y <= it.rect.bottom }
}

fun selectAcrossPdfPages(ranges: List<PdfPageTextRange>): List<PdfTextSelection>? {
    if (ranges.isEmpty() || ranges.size > 2) return null
    val ordered = ranges.sortedBy { it.page.pageIndex }
    if (ordered.zipWithNext().any { (a,b) -> b.page.pageIndex != a.page.pageIndex + 1 }) return null
    return ordered.map { it.page.select(it.start,it.end) ?: return null }
}

object PdfTextGeometry {
    fun valid(rect: NormalizedRect): Boolean = listOf(rect.left,rect.top,rect.right,rect.bottom).all { it.isFinite() } &&
        rect.left >= 0f && rect.top >= 0f && rect.right <= 1f && rect.bottom <= 1f && rect.width > .0001f && rect.height > .0001f

    /** Clockwise /Rotate transformation after CropBox translation, before viewport scale/pan. */
    fun rotate(rect: NormalizedRect, degrees: Int): NormalizedRect = when (degrees) {
        0 -> rect
        90 -> NormalizedRect(1f-rect.bottom,rect.left,1f-rect.top,rect.right)
        180 -> NormalizedRect(1f-rect.right,1f-rect.bottom,1f-rect.left,1f-rect.top)
        270 -> NormalizedRect(rect.top,1f-rect.right,rect.bottom,1f-rect.left)
        else -> throw IllegalArgumentException("unsupported PDF rotation")
    }
}
