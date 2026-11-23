package com.example.myapplication.ui.knowledge.course

import androidx.compose.ui.semantics.SemanticsPropertyKey
import com.example.myapplication.data.local.entity.ReaderSectionEntity
import com.example.myapplication.data.reader.CourseCatalogSnapshot
import com.example.myapplication.domain.reader.*
import com.example.myapplication.ui.knowledge.reader.TurnBoundary
import com.example.myapplication.ui.knowledge.reader.TurnPageCoordinator
import kotlinx.serialization.json.*

val CourseReaderDocumentId = SemanticsPropertyKey<String>("CourseReaderDocumentId")
val CourseReaderSectionId = SemanticsPropertyKey<String>("CourseReaderSectionId")
val CourseReaderChapterKey = SemanticsPropertyKey<String>("CourseReaderChapterKey")

data class CourseReaderNavigation(
    val previousTitle: String?,
    val nextTitle: String?,
    val busy: Boolean = false,
    val onNavigate: (ReaderTurnDirection) -> Unit,
)

data class CourseReaderBinding(
    val bookId: String,
    val chapterKey: String,
    val sequenceVersion: String,
    val request: ReaderOpenRequest,
    val previous: ReaderTarget?,
    val next: ReaderTarget?,
)

data class CourseLegacyPlacement(val sectionId: String, val progression: Float, val pageIndex: Int?)
data class CourseDirectoryPosition(val bookId: String, val chapterKey: String?, val index: Int, val offset: Int)

sealed interface CourseConsumedPosition {
    data object Initial : CourseConsumedPosition
    data object LegacyWithoutLocator : CourseConsumedPosition
    data class Located(val locator: ReaderLocator) : CourseConsumedPosition
    data object Reset : CourseConsumedPosition
}

/** Saved renderer positions outrank delayed DAO progress and old Agent return locators. */
object CourseConsumedPositionCodec {
    fun encode(userId: Long, epoch: Long, book: String, chapter: String, locatorJson: String): String {
        require(userId > 0 && epoch >= 0 && book.isNotBlank() && chapter.isNotBlank())
        require(ReaderLocatorCodec.decodeOrNull(locatorJson) != null)
        return buildJsonObject {
            put("userId", userId); put("epoch", epoch); put("bookId", book); put("chapterKey", chapter); put("locator", locatorJson)
        }.toString()
    }

    fun restore(raw: String?, userId: Long, epoch: Long, book: String, chapter: String,
        target: ReaderTarget, sections: List<ReaderSectionEntity>): CourseConsumedPosition {
        if (raw == null) return CourseConsumedPosition.Initial
        return runCatching<CourseConsumedPosition> {
            val obj = Json.parseToJsonElement(raw).jsonObject
            if (obj["userId"]?.jsonPrimitive?.longOrNull != userId || obj["epoch"]?.jsonPrimitive?.longOrNull != epoch ||
                obj["bookId"]?.jsonPrimitive?.contentOrNull != book || obj["chapterKey"]?.jsonPrimitive?.contentOrNull != chapter ||
                target.courseBookId != book || target.chapterKey != chapter) return CourseConsumedPosition.Reset
            // Only the identified old marker format, with the field absent, may consult the DAO.
            if ("locator" !in obj) return CourseConsumedPosition.LegacyWithoutLocator
            val value = obj["locator"]?.jsonPrimitive ?: return CourseConsumedPosition.Reset
            if (!value.isString) return CourseConsumedPosition.Reset
            val locator = ReaderLocatorCodec.decodeOrNull(value.content) ?: return CourseConsumedPosition.Reset
            if (CourseReaderNavigationPolicy.placement(ReaderOpenRequest(target, ReaderPlacement.AT_LOCATOR, locator),
                    target.documentId, target.contentRevision, sections) !is OperationResult.Ok) return CourseConsumedPosition.Reset
            CourseConsumedPosition.Located(locator)
        }.getOrDefault(CourseConsumedPosition.Reset)
    }
}

/** Production scope resolution. Physical resources are checked again by the legacy reader. */
object CourseReaderNavigationPolicy {
    fun resolve(bookId: String, chapterKey: String, catalog: CourseCatalogSnapshot, sequence: ReadingSequence,
        placement: ReaderPlacement = ReaderPlacement.START, locator: ReaderLocator? = null): OperationResult<CourseReaderBinding> {
        if (bookId.isBlank() || catalog.book.bookId != bookId || catalog.book.reviewStatus != "APPROVED")
            return OperationResult.Rejected("COURSE_NOT_APPROVED")
        val rows = catalog.chapters
        val enabled = rows.filter { it.enabled }.sortedBy { it.orderIndex }
        if (rows.any { it.bookId != bookId || it.chapterKey.isBlank() || it.documentId.isBlank() || it.orderIndex < 0 } ||
            rows.map { it.chapterKey }.distinct().size != rows.size ||
            enabled.map { it.orderIndex }.distinct().size != enabled.size || enabled.isEmpty() ||
            enabled.size != sequence.orderedTargets.size) return OperationResult.Rejected("COURSE_CATALOG_INVALID")
        if (enabled.zip(sequence.orderedTargets).any { (row, target) ->
                target.courseBookId != bookId || target.chapterKey != row.chapterKey || target.documentId != row.documentId ||
                    (row.sectionId != null && (target.firstSectionId != row.sectionId || target.lastSectionId != row.sectionId))
            }) return OperationResult.Rejected("COURSE_SEQUENCE_INVALID")
        val index = enabled.indexOfFirst { it.chapterKey == chapterKey }
        if (index < 0) return OperationResult.Rejected("COURSE_CHAPTER_NOT_FOUND")
        val target = sequence.orderedTargets[index]
        if ((placement == ReaderPlacement.AT_LOCATOR) != (locator != null)) return OperationResult.Rejected("INVALID_LOCATOR")
        if (locator != null && (!ReaderLocatorValidator.validateForDocument(locator, target.documentId, target.contentRevision).isValid ||
                (target.firstSectionId == target.lastSectionId && locator.sectionId != target.firstSectionId)))
            return OperationResult.Rejected("INVALID_LOCATOR")
        return OperationResult.Ok(CourseReaderBinding(bookId, chapterKey, sequence.version,
            ReaderOpenRequest(target, placement, locator), sequence.orderedTargets.getOrNull(index - 1),
            sequence.orderedTargets.getOrNull(index + 1)), sequence.version)
    }

    fun turn(binding: CourseReaderBinding, sequence: ReadingSequence, direction: ReaderTurnDirection): TurnBoundary {
        val index = sequence.indexOf(binding.request.target)
        if (sequence.version != binding.sequenceVersion || index < 0 || sequence.orderedTargets[index] != binding.request.target ||
            sequence.orderedTargets.getOrNull(index - 1) != binding.previous || sequence.orderedTargets.getOrNull(index + 1) != binding.next)
            return TurnBoundary.Invalidated
        return TurnPageCoordinator().resolve(sequence, binding.request.target, direction)
    }

    fun placement(request: ReaderOpenRequest, documentId: String, currentRevision: String,
        sections: List<ReaderSectionEntity>): OperationResult<CourseLegacyPlacement> {
        val target = request.target
        if (target.documentId != documentId || target.contentRevision != currentRevision || sections.isEmpty() ||
            sections.any { it.documentId != documentId || it.contentRevision != currentRevision || it.orderIndex < 0 ||
                (it.pageStart == null && it.pageEnd != null) ||
                (it.pageStart != null && (it.pageStart < 0 || (it.pageEnd != null && it.pageEnd < it.pageStart))) } ||
            sections.map { it.id }.distinct().size != sections.size || sections.map { it.orderIndex }.distinct().size != sections.size)
            return OperationResult.Rejected("COURSE_SCOPE_INVALID")
        val ordered = sections.sortedBy { it.orderIndex }
        val first = ordered.indexOfFirst { it.id == target.firstSectionId }
        val last = ordered.indexOfFirst { it.id == target.lastSectionId }
        if (first < 0 || last < first) return OperationResult.Rejected("COURSE_SCOPE_INVALID")
        if ((request.placement == ReaderPlacement.AT_LOCATOR) != (request.locator != null))
            return OperationResult.Rejected("INVALID_LOCATOR")
        val position = when (request.placement) {
            ReaderPlacement.START -> CourseLegacyPlacement(ordered[first].id, 0f, ordered[first].pageStart)
            ReaderPlacement.END -> CourseLegacyPlacement(ordered[last].id, 1f, ordered[last].pageEnd ?: ordered[last].pageStart)
            ReaderPlacement.AT_LOCATOR -> {
                val locator = request.locator ?: return OperationResult.Rejected("INVALID_LOCATOR")
                if (!ReaderLocatorValidator.validateForDocument(locator, documentId, currentRevision).isValid)
                    return OperationResult.Rejected("INVALID_LOCATOR")
                val section = ordered.subList(first, last + 1).singleOrNull { it.id == locator.sectionId }
                    ?: return OperationResult.Rejected("COURSE_SCOPE_INVALID")
                val page = (locator as? ReaderLocator.Fixed)?.pageIndex
                if (page != null && (section.pageStart == null || page < section.pageStart || page > (section.pageEnd ?: section.pageStart)))
                    return OperationResult.Rejected("COURSE_SCOPE_INVALID")
                CourseLegacyPlacement(section.id, locator.progression ?: 0f, page ?: section.pageStart)
            }
        }
        return OperationResult.Ok(position, "course-placement")
    }
}

object CourseDirectoryPositionCodec {
    fun encode(position: CourseDirectoryPosition): String {
        require(position.bookId.isNotBlank() && position.index >= 0 && position.offset >= 0)
        return buildJsonObject {
            put("bookId", position.bookId); position.chapterKey?.let { put("chapterKey", it) }
            put("index", position.index); put("offset", position.offset)
        }.toString()
    }
    fun decodeOrNull(json: String, bookId: String): CourseDirectoryPosition? = runCatching {
        val obj = Json.parseToJsonElement(json).jsonObject
        val owner = obj["bookId"]?.jsonPrimitive?.content ?: return null
        val index = obj["index"]?.jsonPrimitive?.intOrNull ?: return null
        val offset = obj["offset"]?.jsonPrimitive?.intOrNull ?: return null
        if (owner != bookId || owner.isBlank() || index < 0 || offset < 0) return null
        CourseDirectoryPosition(owner, obj["chapterKey"]?.jsonPrimitive?.contentOrNull, index, offset)
    }.getOrNull()
}

/** A directory's visual items are not the chapter array; overview/quiz items have no chapter key. */
internal object CourseDirectoryPositionProjector {
    fun layout(chapterKeys: List<String>, combinedCount: Int): List<String?> {
        require(combinedCount >= 0 && chapterKeys.all(String::isNotBlank) &&
            chapterKeys.distinct().size == chapterKeys.size)
        return listOf<String?>(null, null) + chapterKeys +
            if (combinedCount == 0) emptyList() else List(combinedCount + 1) { null }
    }

    fun capture(bookId: String, layout: List<String?>, listIndex: Int, offset: Int): CourseDirectoryPosition {
        require(bookId.isNotBlank() && layout.isNotEmpty() && listIndex >= 0 && offset >= 0)
        val boundedIndex = listIndex.coerceAtMost(layout.lastIndex)
        return CourseDirectoryPosition(bookId, layout[boundedIndex], boundedIndex,
            if (boundedIndex == listIndex) offset else 0)
    }

    fun restoreListIndex(position: CourseDirectoryPosition, layout: List<String?>): Int? =
        if (position.chapterKey != null) layout.indexOf(position.chapterKey).takeIf { it >= 0 }
        else position.index.takeIf { it in layout.indices }
}
