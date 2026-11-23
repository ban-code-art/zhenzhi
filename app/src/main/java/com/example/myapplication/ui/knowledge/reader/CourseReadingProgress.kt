package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.local.entity.ReaderStateEntity
import com.example.myapplication.domain.reader.*

enum class CourseReadingStatus { NOT_STARTED, READING, COMPLETED }
data class CourseContinueReading(val target: ReaderTarget, val locator: ReaderLocator?, val resetReason: String? = null)
data class CourseChapterReading(val status: CourseReadingStatus = CourseReadingStatus.NOT_STARTED,
    val locator: ReaderLocator? = null, val resetReason: String? = null)
data class CourseReadingProjection(val chapters: Map<ReaderTarget, CourseChapterReading>, val continueReading: CourseContinueReading?)

/** A projection of the reader's own state, never a second progress store or a quiz completion signal. */
object CourseReadingProgress {
    fun project(userId: Long, sequence: ReadingSequence, states: List<ReaderStateEntity>): CourseReadingProjection {
        val rows = sequence.orderedTargets.associateWith { CourseChapterReading() }.toMutableMap()
        // Reject ambiguous input rather than depend on a caller's list ordering.
        val owned = states.filter { it.userId == userId && it.lastReadAt >= 0 }
            .groupBy { it.documentId }.mapNotNull { (_, entries) -> entries.singleOrNull() }
        val resumes = mutableListOf<Pair<ReaderStateEntity, CourseContinueReading>>()
        for (state in owned) {
            val units = sequence.orderedTargets.filter { it.documentId == state.documentId }
            if (units.isEmpty()) continue
            val locator = state.locatorJson?.let(ReaderLocatorCodec::decodeOrNull)
            val matching = locator?.takeIf { ReaderLocatorValidator.validate(it).isValid }?.let { candidate ->
                units.singleOrNull { target ->
                    candidate.documentId == state.documentId && candidate.contentRevision == target.contentRevision &&
                        target.firstSectionId == target.lastSectionId && candidate.sectionId == target.firstSectionId
                }
            }
            if (matching != null && locator != null) {
                rows[matching] = CourseChapterReading(if (state.isCompleted) CourseReadingStatus.COMPLETED else CourseReadingStatus.READING, locator)
                resumes += state to CourseContinueReading(matching, locator)
            } else if (state.lastReadAt > 0 || state.locatorJson != null || state.isCompleted) {
                val target = units.first()
                val reason = if (locator != null && locator.contentRevision != target.contentRevision) "READING_REVISION_CHANGED" else "READING_POSITION_UNAVAILABLE"
                rows[target] = CourseChapterReading(status = if (state.isCompleted) CourseReadingStatus.COMPLETED else CourseReadingStatus.NOT_STARTED, resetReason = reason)
                resumes += state to CourseContinueReading(target, null, reason)
            }
        }
        // Stable tie-break uses course order; a state from another owner never competes here.
        val order = sequence.orderedTargets.withIndex().associate { it.value to it.index }
        val latest = resumes.sortedWith(compareByDescending<Pair<ReaderStateEntity, CourseContinueReading>> { it.first.lastReadAt }
            .thenBy { order[it.second.target] }).firstOrNull()?.second
        return CourseReadingProjection(rows.toMap(), latest ?: sequence.orderedTargets.firstOrNull()?.let { CourseContinueReading(it, null) })
    }
}

