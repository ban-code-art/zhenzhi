package com.example.myapplication.ui.knowledge

import com.example.myapplication.data.local.dao.ReaderShelfRow
import com.example.myapplication.data.local.entity.ReaderSectionEntity
import com.example.myapplication.data.reader.importer.ReaderFormat
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.ui.knowledge.course.BundledCourseCatalog

/** A read-only book preview; completion and resume state come from the current owner's saved state. */
data class KnowledgeHomeBook(
    val documentId: String,
    val title: String,
    val author: String? = null,
    val format: ReaderFormat = ReaderFormat.UNKNOWN,
    val chapterCount: Int = 0,
    val firstChapterTitle: String? = null,
    val resumeChapterTitle: String? = null,
    val canResume: Boolean = false,
    val isCompleted: Boolean = false,
    val lastReadAt: Long = 0L,
) {
    val canOpen: Boolean get() = chapterCount > 0
    val readingAction: String get() = when {
        !canOpen -> "暂无可读章节"
        isCompleted -> "重新阅读"
        canResume -> "继续阅读"
        else -> "开始阅读"
    }
    val formatLabel: String get() = when (format) {
        ReaderFormat.MARKDOWN -> "Markdown"
        ReaderFormat.UNKNOWN -> "图书"
        else -> format.name
    }
    val chapterUnit: String get() = if (format == ReaderFormat.PDF || format == ReaderFormat.CBZ) "页" else "章"
}

/** Independent course and library states keep a failed source from hiding the other content. */
data class KnowledgeHomeUiState(
    val identity: SessionIdentity = SessionIdentity(null, -1),
    val coursesLoading: Boolean = true,
    val catalog: BundledCourseCatalog? = null,
    val courseError: String? = null,
    val libraryLoading: Boolean = true,
    val importedBooks: List<KnowledgeHomeBook> = emptyList(),
    val importedBookCount: Int = 0,
    val libraryError: String? = null,
    val shelfBooks: List<KnowledgeHomeBook> = importedBooks,
) {
    val signedIn: Boolean get() = identity.userId?.let { it > 0 } == true && identity.epoch >= 0
}

internal object KnowledgeHomeProjection {
    const val PREVIEW_BOOKS = 3
    const val SHELF_BOOKS = 24

    fun visibleImports(userId: Long, rows: List<ReaderShelfRow>): List<ReaderShelfRow> = rows.filter { row ->
        row.document.kind == "IMPORTED" && row.document.ownerUserId == userId &&
            row.document.importStatus == "READY" && row.document.deletedAt == null && row.isHidden != true
    }

    fun book(row: ReaderShelfRow, sections: List<ReaderSectionEntity>): KnowledgeHomeBook {
        val document = row.document
        require(sections.all { it.documentId == document.id && it.contentRevision == document.contentRevision })
        val ordered = sections.sortedWith(compareBy<ReaderSectionEntity> { it.orderIndex }.thenBy { it.id })
        val locator = row.locatorJson?.let(ReaderLocatorCodec::decodeOrNull)?.takeIf {
            it.documentId == document.id && it.contentRevision == document.contentRevision
        }
        val resumed = locator?.sectionId?.let { id -> ordered.firstOrNull { it.id == id } }
            ?.takeIf { (row.lastReadAt ?: 0L) > 0L }
        return KnowledgeHomeBook(
            documentId = document.id,
            title = row.displayTitleOverride?.takeIf(String::isNotBlank) ?: document.title,
            author = document.author?.takeIf(String::isNotBlank),
            format = ReaderFormat.entries.firstOrNull { it.name == document.format } ?: ReaderFormat.UNKNOWN,
            chapterCount = ordered.size,
            firstChapterTitle = ordered.firstOrNull()?.title,
            resumeChapterTitle = resumed?.title,
            canResume = resumed != null,
            isCompleted = row.isCompleted == true,
            lastReadAt = row.lastReadAt ?: 0L,
        )
    }
}
