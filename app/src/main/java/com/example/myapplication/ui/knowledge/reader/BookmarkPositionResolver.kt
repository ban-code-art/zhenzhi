package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.notes.ReaderBookmarkView
import com.example.myapplication.data.reader.notes.ReaderMutationHasher
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import com.example.myapplication.domain.reader.ReaderSelectionFragment
import java.util.Collections

/**
 * Resolves an exact book position using the existing bookmark hash identity.
 * Callers must supply bookmarks from the current user's isolated repository and check session tokens.
 */
object BookmarkPositionResolver {
    data class Target(val locator: ReaderLocator, val anchorHash: String, val preview: String)

    enum class UnavailableReason {
        INVALID_IDENTITY,
        NO_POSITION,
        INVALID_SELECTION,
        UNPROVEN_READING_ORDER,
        INVALID_POSITION,
    }

    sealed interface Result {
        data class Resolved(val target: Target, val bookmark: ReaderBookmarkView?) : Result
        data class Ambiguous(val target: Target, val bookmarkIds: List<String>) : Result
        data class Unavailable(val reason: UnavailableReason) : Result
    }

    fun resolve(
        documentId: String,
        contentRevision: String,
        selection: List<ReaderSelectionFragment>? = null,
        viewport: ReaderLocator? = null,
        bookmarks: List<ReaderBookmarkView> = emptyList(),
        readingOrder: BookmarkReadingOrder? = null,
        visibleRanges: List<ReaderLocator> = emptyList(),
    ): Result {
        if (!validIdentity(documentId) || !validIdentity(contentRevision)) {
            return Result.Unavailable(UnavailableReason.INVALID_IDENTITY)
        }

        var selectionPreview: String? = null
        val locator = if (selection != null) {
            val fragments = if (selection.size > 1) {
                if (readingOrder?.documentId != documentId || readingOrder.contentRevision != contentRevision)
                    return Result.Unavailable(UnavailableReason.UNPROVEN_READING_ORDER)
                readingOrder.normalize(selection) ?: return Result.Unavailable(UnavailableReason.UNPROVEN_READING_ORDER)
            } else selection.toList()
            if (fragments.isEmpty()) return Result.Unavailable(UnavailableReason.INVALID_SELECTION)
            if (fragments.sumOf { it.quote.length.toLong() } > 8192) return Result.Unavailable(UnavailableReason.INVALID_SELECTION)
            for (fragment in fragments) {
            val selected = freezeLocator(fragment.locator)
            if (!validPosition(selected, documentId, contentRevision) ||
                fragment.quote.isEmpty() || fragment.quote != quote(selected) ||
                (selected is ReaderLocator.Reflow &&
                    (selected.canonicalEnd <= selected.canonicalStart ||
                        selected.canonicalEnd - selected.canonicalStart != fragment.quote.length))
            ) {
                return Result.Unavailable(UnavailableReason.INVALID_SELECTION)
            }
            }
            selectionPreview = preview(fragments.joinToString("\n") { it.quote })
            freezeLocator(fragments.first().locator)
        } else {
            val position = viewport ?: return Result.Unavailable(UnavailableReason.NO_POSITION)
            val frozen = freezeLocator(position)
            if (!validPosition(frozen, documentId, contentRevision)) {
                return Result.Unavailable(UnavailableReason.INVALID_POSITION)
            }
            frozen
        }

        val target = Target(locator, ReaderMutationHasher.bookmarkHash(locator), selectionPreview ?: preview(quote(locator)))
        val matches = bookmarks.mapNotNull { bookmark ->
            if (bookmark.documentId != documentId) return@mapNotNull null
            val candidateLocator = bookmark.locator?.let(::freezeLocator) ?: return@mapNotNull null
            if (!validPosition(candidateLocator, documentId, contentRevision) ||
                ReaderMutationHasher.bookmarkHash(candidateLocator) != bookmark.anchorHash ||
                !(bookmark.anchorHash == target.anchorHash || (selection == null && (samePosition(locator, candidateLocator) ||
                    visibleRanges.any { range -> validPosition(range, documentId, contentRevision) && visibleStart(range, candidateLocator) })))
            ) {
                return@mapNotNull null
            }
            bookmark.copy(locator = candidateLocator)
        }
        return when (matches.size) {
            0 -> Result.Resolved(target, null)
            1 -> {
                val match = matches.single()
                val saved = checkNotNull(match.locator)
                Result.Resolved(if (match.anchorHash == target.anchorHash) target else
                    Target(saved, match.anchorHash, preview(match.preview)), match)
            }
            else -> Result.Ambiguous(target, immutableList(matches.map { it.id }.sorted()))
        }
    }

    private fun visibleStart(range: ReaderLocator, saved: ReaderLocator): Boolean =
        range is ReaderLocator.Reflow && saved is ReaderLocator.Reflow && range.sectionId == saved.sectionId &&
            range.blockId == saved.blockId && saved.canonicalStart >= range.canonicalStart && saved.canonicalStart < range.canonicalEnd

    private fun samePosition(position: ReaderLocator, saved: ReaderLocator): Boolean = when {
        position is ReaderLocator.Reflow && saved is ReaderLocator.Reflow ->
            // Called only for a viewport (never a selection). Its source-backed sample can
            // have a different end/quote than a saved selection at the same reading point.
            position.sectionId == saved.sectionId && position.blockId == saved.blockId &&
                position.canonicalStart == saved.canonicalStart
        position is ReaderLocator.Fixed && saved is ReaderLocator.Fixed ->
            position.rects.isEmpty() && position.textQuote.isNullOrEmpty() &&
                position.sectionId == saved.sectionId && position.pageIndex == saved.pageIndex
        else -> false
    }

    private fun validIdentity(value: String): Boolean =
        value.isNotBlank() && value.length <= 512 && value.none(Char::isISOControl) &&
            ReaderLocatorValidator.isWellFormedUtf16(value)

    private fun validPosition(locator: ReaderLocator, documentId: String, contentRevision: String): Boolean {
        if (locator is ReaderLocator.Note ||
            !ReaderLocatorValidator.validateForDocument(locator, documentId, contentRevision).isValid ||
            !validIdentity(locator.documentId) || !validIdentity(locator.contentRevision) ||
            !validIdentity(locator.sectionId)
        ) {
            return false
        }
        return when (locator) {
            is ReaderLocator.Reflow -> validIdentity(locator.nativeLocator) && validIdentity(locator.blockId)
            is ReaderLocator.Fixed -> true
            is ReaderLocator.Note -> false
        }
    }

    private fun quote(locator: ReaderLocator): String = when (locator) {
        is ReaderLocator.Reflow -> locator.exactQuote
        is ReaderLocator.Fixed -> locator.textQuote.orEmpty()
        is ReaderLocator.Note -> ""
    }

    private fun preview(quote: String): String {
        var end = minOf(160, quote.length)
        if (end > 0 && end < quote.length &&
            Character.isHighSurrogate(quote[end - 1]) && Character.isLowSurrogate(quote[end])
        ) {
            end -= 1
        }
        return quote.substring(0, end)
    }

    private fun freezeLocator(locator: ReaderLocator): ReaderLocator = when (locator) {
        is ReaderLocator.Fixed -> locator.copy(rects = immutableList(locator.rects))
        is ReaderLocator.Reflow -> locator
        is ReaderLocator.Note -> locator
    }

    private fun <T> immutableList(values: List<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))
}
