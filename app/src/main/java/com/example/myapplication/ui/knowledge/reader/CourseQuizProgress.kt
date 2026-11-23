package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.local.entity.StudyProgressEntity
import com.example.myapplication.data.reader.CourseCatalogSnapshot
import com.example.myapplication.domain.reader.ReaderTarget
import com.example.myapplication.domain.reader.ReadingSequence

sealed interface CourseQuizProgress {
    data object Unavailable : CourseQuizProgress
    data object NoGradedResult : CourseQuizProgress
    data class Graded(val score: Int, val total: Int) : CourseQuizProgress
}

/** A legacy chapter's graded summary, with no reader-completion or attempt-time inference. */
object CourseQuizProgressProjector {
    fun project(
        userId: Long,
        catalog: CourseCatalogSnapshot,
        sequence: ReadingSequence,
        progressRows: List<StudyProgressEntity>,
    ): Map<ReaderTarget, CourseQuizProgress> {
        val chaptersByKey = catalog.chapters.groupBy { it.chapterKey }
        val targetsByKey = sequence.orderedTargets.groupBy { it.chapterKey }
        val targetsByDocument = sequence.orderedTargets.groupBy { it.documentId }
        val enabledLegacyMappings = catalog.chapters.filter { it.enabled }
            .groupBy { it.legacyChapterId }
        val ownedProgress = progressRows.filter { it.userId == userId }.groupBy { it.chapterId }

        return sequence.orderedTargets.associateWith { target ->
            val chapter = target.chapterKey?.let { chaptersByKey[it]?.singleOrNull() }
            val legacyId = chapter?.legacyChapterId
            val validMapping = target.courseBookId == catalog.book.bookId &&
                chapter != null && chapter.bookId == catalog.book.bookId && chapter.enabled &&
                targetsByKey[target.chapterKey]?.size == 1 &&
                target.documentId == chapter.documentId && legacyId != null && legacyId > 0 &&
                enabledLegacyMappings[legacyId]?.size == 1 &&
                if (chapter.sectionId == null) {
                    targetsByDocument[target.documentId]?.size == 1
                } else {
                    target.firstSectionId == chapter.sectionId && target.lastSectionId == chapter.sectionId
                }
            if (!validMapping) return@associateWith CourseQuizProgress.Unavailable

            val entries = ownedProgress[legacyId]
            if (entries == null) return@associateWith CourseQuizProgress.NoGradedResult
            val progress = entries.singleOrNull() ?: return@associateWith CourseQuizProgress.Unavailable
            val score = progress.quizScore
            val total = progress.quizTotal
            when {
                score == null && total == null -> CourseQuizProgress.NoGradedResult
                score == null || total == null || total <= 0 || score < 0 || score > total -> CourseQuizProgress.Unavailable
                else -> CourseQuizProgress.Graded(score, total)
            }
        }
    }
}
