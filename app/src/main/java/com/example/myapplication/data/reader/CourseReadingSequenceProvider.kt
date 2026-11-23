package com.example.myapplication.data.reader

import com.example.myapplication.data.local.entity.CourseBookChapterEntity
import com.example.myapplication.data.local.entity.CourseBookEntity
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReadingSequence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Catalog rows must be read atomically by the production binding. */
data class CourseCatalogSnapshot(val book: CourseBookEntity, val chapters: List<CourseBookChapterEntity>)

/**
 * Resolves actual document units without opening a renderer or acquiring a progress writer.
 * Returned scope is a versioned snapshot, not permission to skip resource/access checks at open.
 */
class CourseReadingSequenceProvider(
    private val readCatalog: suspend (String) -> CourseCatalogSnapshot?,
    private val readDocument: suspend (AccessContext, String) -> OperationResult<ReadingSequence>,
    private val identity: suspend () -> SessionIdentity,
) {
    suspend fun load(
        context: AccessContext,
        bookId: String,
        allowDevelopmentDraft: Boolean = false,
    ): OperationResult<ReadingSequence> {
        currentCoroutineContext().ensureActive()
        if (bookId.isBlank()) return OperationResult.Rejected("INVALID_ARGUMENT")
        try {
            if (!matches(context)) return OperationResult.Rejected("SESSION_CHANGED")
            val catalog = readCatalog(bookId)
            currentCoroutineContext().ensureActive()
            val baseline = catalog?.snapshot()
                ?: return OperationResult.Rejected("COURSE_NOT_FOUND")
            if (!matches(context)) return OperationResult.Rejected("SESSION_CHANGED")
            if (baseline.book.bookId != bookId) return OperationResult.Rejected("COURSE_CATALOG_INVALID")
            when (baseline.book.reviewStatus) {
                "APPROVED" -> Unit
                "DRAFT" -> if (!allowDevelopmentDraft) return OperationResult.Rejected("COURSE_NOT_APPROVED")
                "REVIEWED" -> return OperationResult.Rejected("COURSE_NOT_APPROVED")
                else -> return OperationResult.Rejected("COURSE_REVIEW_STATUS_UNSUPPORTED")
            }
            val enabled = baseline.chapters.filter { it.enabled }.sortedBy { it.orderIndex }
            // Reject invalid catalog identities before granting any document reads.
            if (enabled.isEmpty() || baseline.chapters.any {
                    it.bookId != bookId || it.chapterKey.isBlank() || it.documentId.isBlank() || it.orderIndex < 0 ||
                        (it.sectionId != null && it.sectionId.isBlank())
                } || baseline.chapters.map { it.chapterKey }.distinct().size != baseline.chapters.size ||
                enabled.map { it.orderIndex }.distinct().size != enabled.size) {
                return OperationResult.Rejected("COURSE_CATALOG_INVALID")
            }
            val documents = linkedMapOf<String, ReadingSequence>()
            for (documentId in enabled.map { it.documentId }.distinct()) {
                if (!matches(context)) return OperationResult.Rejected("SESSION_CHANGED")
                val source = readDocument(context, documentId)
                currentCoroutineContext().ensureActive()
                when (source) {
                    is OperationResult.Ok -> documents[documentId] = source.value
                    else -> return source.failure()
                }
                if (!matches(context)) return OperationResult.Rejected("SESSION_CHANGED")
            }
            val sequence = try {
                CourseReadingSequenceMapper.map(baseline.book, baseline.chapters, documents)
            } catch (_: IllegalArgumentException) {
                return OperationResult.Rejected("COURSE_CATALOG_INVALID")
            }
            // Publication, mapping and source access can change while document preparation suspends.
            for ((documentId, baselineSource) in documents) {
                val current = readDocument(context, documentId)
                currentCoroutineContext().ensureActive()
                when (current) {
                    is OperationResult.Ok -> if (current.value.version != baselineSource.version ||
                        current.value.orderedTargets != baselineSource.orderedTargets) {
                        return OperationResult.Rejected("COURSE_SOURCE_CHANGED")
                    }
                    else -> return current.failure()
                }
                if (!matches(context)) return OperationResult.Rejected("SESSION_CHANGED")
            }
            val latestCatalog = readCatalog(bookId)
            currentCoroutineContext().ensureActive()
            if (latestCatalog?.snapshot() != baseline) return OperationResult.Rejected("COURSE_CHANGED")
            if (!matches(context)) return OperationResult.Rejected("SESSION_CHANGED")
            return OperationResult.Ok(sequence, context.requestId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Storage may throw an ordinary exception after its caller was cancelled.
            currentCoroutineContext().ensureActive()
            return OperationResult.Failed("COURSE_SEQUENCE_LOAD_FAILED", retryable = true)
        }
    }

    private suspend fun matches(context: AccessContext): Boolean {
        currentCoroutineContext().ensureActive()
        val current = identity()
        currentCoroutineContext().ensureActive()
        return current.userId == context.userId && current.epoch == context.sessionEpoch
    }

    private fun CourseCatalogSnapshot.snapshot() = copy(chapters = chapters.toList())

    private fun OperationResult<*>.failure(): OperationResult<Nothing> = when (this) {
        is OperationResult.Ok -> error("expected failure")
        is OperationResult.Rejected -> this
        is OperationResult.Failed -> this
        is OperationResult.Conflict -> this
    }
}
