package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import com.example.myapplication.domain.reader.ReaderSelectionFragment
import java.util.Collections

/** Source-owned order, built from the prepared section/block manifest, never screen coordinates. */
class BookmarkReadingOrder(
    val documentId: String,
    val contentRevision: String,
    blocks: List<Pair<String, String>>,
    fixedPages: List<Pair<String, Int>> = emptyList(),
) {
    private val positions = Collections.unmodifiableMap(blocks.withIndex().associate { it.value to it.index })
    private val valid = blocks.isNotEmpty() && positions.size == blocks.size &&
        blocks.all { it.first.isNotBlank() && it.second.isNotBlank() }
    private val pages = Collections.unmodifiableMap(fixedPages.toMap())
    private val validPages = blocks.isEmpty() && fixedPages.isNotEmpty() && pages.size == fixedPages.size &&
        fixedPages.map { it.second }.distinct().size == fixedPages.size &&
        fixedPages.all { it.first.isNotBlank() && it.second >= 0 } &&
        fixedPages.zipWithNext().all { (a, b) -> a.second < b.second }

    fun normalize(fragments: List<ReaderSelectionFragment>): List<ReaderSelectionFragment>? {
        if (fragments.isEmpty() || fragments.size > 128) return null
        if (fragments.any { it.locator is ReaderLocator.Fixed }) return normalizePages(fragments)
        if (!valid) return null
        val located = fragments.map { fragment ->
            val locator = fragment.locator as? ReaderLocator.Reflow ?: return null
            if (locator.documentId != documentId || locator.contentRevision != contentRevision) return null
            val position = positions[locator.sectionId to locator.blockId] ?: return null
            Triple(position, locator.canonicalStart, fragment)
        }.sortedWith(compareBy<Triple<Int, Int, ReaderSelectionFragment>> { it.first }.thenBy { it.second })
        for (i in 1 until located.size) {
            val previous = located[i - 1]
            val next = located[i]
            if (previous.first == next.first &&
                (previous.third.locator as ReaderLocator.Reflow).canonicalEnd > next.second) return null
        }
        return Collections.unmodifiableList(located.map { it.third })
    }

    /** Page numbers are trusted only after matching the source-owned section/page directory. */
    private fun normalizePages(fragments: List<ReaderSelectionFragment>): List<ReaderSelectionFragment>? {
        if (!validPages) return null
        val located = fragments.map { fragment ->
            val locator = fragment.locator as? ReaderLocator.Fixed ?: return null
            if (locator.documentId != documentId || locator.contentRevision != contentRevision ||
                pages[locator.sectionId] != locator.pageIndex || locator.rects.isEmpty() ||
                fragment.quote.isBlank() || fragment.quote != locator.textQuote ||
                !ReaderLocatorValidator.validate(locator).isValid) return null
            fragment.copy(locator = locator.copy(rects = Collections.unmodifiableList(locator.rects.toList())))
        }.sortedBy { (it.locator as ReaderLocator.Fixed).pageIndex }
        // The PDF selection surface emits one complete source range per page. Disjoint ranges
        // within a page need a separate proven text order; rectangle order cannot supply it.
        if (located.map { (it.locator as ReaderLocator.Fixed).pageIndex }.distinct().size != located.size) return null
        return Collections.unmodifiableList(located)
    }
}
