package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.notes.AnnotationRepository
import com.example.myapplication.data.session.SessionIdentity
import kotlinx.coroutines.CoroutineScope

/** Shared action semantics for the existing reader. The host supplies real epoch and live ownership. */
class LegacyBookmarkController(
    scope: CoroutineScope,
    repository: AnnotationRepository,
    identityNow: suspend () -> SessionIdentity,
    ownsPosition: suspend (BookmarkPositionInput) -> Boolean,
) : AutoCloseable {
    private var input: BookmarkPositionInput? = null
    private var order: BookmarkReadingOrder? = null
    private val controller = BookmarkActionController(scope, BookmarkActionServices(
        identityNow = identityNow,
        ownsPosition = ownsPosition,
        observeBookmarks = repository::observeBookmarks,
        resolvePosition = { document, revision, fragments, viewport, rows ->
            BookmarkPositionResolver.resolve(document, revision, fragments, viewport, rows, order, input?.visibleRanges.orEmpty()).toActionResolution()
        },
        ensureBookmark = repository::ensureBookmark,
        toggleBookmark = repository::toggleBookmark,
    ))
    val state = controller.state
    fun updatePosition(input: BookmarkPositionInput?, readingOrder: BookmarkReadingOrder? = null) {
        this.input = input
        order = readingOrder
        controller.updatePosition(input)
    }
    suspend fun ensureBookmark() = controller.ensureBookmark()
    suspend fun toggleBookmark() = controller.toggleBookmark()
    suspend fun retryWrite() = controller.retryWrite()
    fun retryObservation() = controller.retryObservation()
    fun selectBookmark(bookmarkId: String) = controller.selectBookmark(bookmarkId)
    override fun close() = controller.close()
}

internal fun BookmarkPositionResolver.Result.toActionResolution(): BookmarkActionResolution = when (this) {
    is BookmarkPositionResolver.Result.Resolved -> BookmarkActionResolution.Resolved(
        BookmarkActionResolution.Target(target.locator, target.anchorHash, target.preview), bookmark)
    is BookmarkPositionResolver.Result.Ambiguous -> BookmarkActionResolution.Ambiguous(
        BookmarkActionResolution.Target(target.locator, target.anchorHash, target.preview), bookmarkIds)
    is BookmarkPositionResolver.Result.Unavailable -> BookmarkActionResolution.Unavailable(reason.name)
}
