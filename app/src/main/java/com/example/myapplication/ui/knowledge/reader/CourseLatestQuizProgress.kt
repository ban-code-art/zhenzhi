package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.local.dao.CourseQuizAttemptSummary
import com.example.myapplication.data.reader.CourseCatalogSnapshot
import com.example.myapplication.domain.reader.ReaderTarget
import com.example.myapplication.domain.reader.ReadingSequence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import java.security.MessageDigest

data class CourseCombinedQuizSummary(
    val documentId: String,
    val sourceRevision: String,
    val sectionIds: List<String>,
    val chapterKeys: List<String>,
    val latestQuiz: CourseLatestQuizProgress.Attempt,
) {
    val stableKey: String get() = MessageDigest.getInstance("SHA-256").digest(buildJsonArray {
        add(documentId); add(sourceRevision); sectionIds.sorted().forEach { add(it) }
    }.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

data class CourseLatestQuizGrade(
    val attemptId: String,
    val score: Int,
    val total: Int,
    val submittedAt: Long,
    val gradeRevision: Long,
)

enum class CourseLatestQuizStatus { IN_PROGRESS, PENDING_REVIEW, GRADED }

sealed interface CourseLatestQuizProgress {
    data object Unavailable : CourseLatestQuizProgress
    data object NoAttempt : CourseLatestQuizProgress
    data class Attempt(
        val attemptId: String,
        val status: CourseLatestQuizStatus,
        val startedAt: Long,
        val submittedAt: Long?,
        val score: Int?,
        val total: Int,
        val gradeRevision: Long,
        val lastGraded: CourseLatestQuizGrade? = null,
    ) : CourseLatestQuizProgress
}

/** Latest attempt state and complete grade stay separate from reader completion and legacy scores. */
object CourseLatestQuizProjector {
    fun project(
        userId: Long,
        catalog: CourseCatalogSnapshot,
        sequence: ReadingSequence,
        attemptRows: List<CourseQuizAttemptSummary>,
    ): Map<ReaderTarget, CourseLatestQuizProgress> {
        val chaptersByKey = catalog.chapters.groupBy { it.chapterKey }
        val targetsByKey = sequence.orderedTargets.groupBy { it.chapterKey }
        val targetsByDocument = sequence.orderedTargets.groupBy { it.documentId }
        val owned = attemptRows.filter { it.userId == userId && it.paperUserId == userId }
            .groupBy { it.documentId }
        return sequence.orderedTargets.associateWith { target ->
            val chapter = target.chapterKey?.let { chaptersByKey[it]?.singleOrNull() }
            if (chapter?.sectionId != null) {
                return@associateWith projectSection(userId, catalog, sequence, target, owned[target.documentId].orEmpty())
            }
            // A null catalog section is a whole-document mapping validated by the sequence provider.
            // One course target alone never promotes an explicitly mapped section to a document.
            val validMapping = userId > 0 && target.courseBookId == catalog.book.bookId &&
                chapter != null && chapter.bookId == catalog.book.bookId && chapter.enabled &&
                chapter.documentId == target.documentId && chapter.sectionId == null &&
                targetsByKey[target.chapterKey]?.size == 1 && targetsByDocument[target.documentId]?.size == 1 &&
                target.startLocator == null && target.endLocator == null
            if (!validMapping) return@associateWith CourseLatestQuizProgress.Unavailable

            val documentRows = owned[target.documentId].orEmpty()
            if (documentRows.any { it.currentDocumentRevision != target.contentRevision }) {
                return@associateWith CourseLatestQuizProgress.Unavailable
            }
            val candidates = documentRows.filter { it.sourceRevision == target.contentRevision }
                .map { row -> row to (scopeMatch(row, target, chapter!!.legacyChapterId) == ScopeMatch.MATCH) }
            if (candidates.isEmpty()) return@associateWith CourseLatestQuizProgress.NoAttempt
            if (candidates.groupBy { it.first.attemptId }.any { it.value.size != 1 }) {
                return@associateWith CourseLatestQuizProgress.Unavailable
            }
            val latest = candidates.maxWithOrNull(compareBy<Pair<CourseQuizAttemptSummary, Boolean>> {
                it.first.submittedAt ?: it.first.startedAt
            }.thenBy { it.first.attemptId })!!
            val row = latest.first
            val status = if (latest.second) validStatus(row) else null
            if (status == null) return@associateWith CourseLatestQuizProgress.Unavailable

            val graded = candidates.asSequence().filter { it.second && validStatus(it.first) == CourseLatestQuizStatus.GRADED }
                .map { it.first }.maxWithOrNull(compareBy<CourseQuizAttemptSummary> { it.submittedAt!! }.thenBy { it.attemptId })
            CourseLatestQuizProgress.Attempt(
                row.attemptId, status, row.startedAt, row.submittedAt,
                if (status == CourseLatestQuizStatus.GRADED) row.totalScore else null,
                row.maxScore, row.gradeRevision,
                graded?.let { CourseLatestQuizGrade(it.attemptId, it.totalScore!!, it.maxScore, it.submittedAt!!, it.gradeRevision) },
            )
        }
    }

    /** A composite score belongs to its exact section set, never to its constituent chapters. */
    fun projectCombined(
        userId: Long,
        catalog: CourseCatalogSnapshot,
        sequence: ReadingSequence,
        attemptRows: List<CourseQuizAttemptSummary>,
    ): List<CourseCombinedQuizSummary> {
        if (userId <= 0) return emptyList()
        val owned = attemptRows.filter { it.userId == userId && it.paperUserId == userId }
        return owned.groupBy { it.documentId }.flatMap { (document, rows) ->
            val targets = sequence.orderedTargets.filter { it.documentId == document }
            val revision = targets.map { it.contentRevision }.distinct().singleOrNull()
                ?: return@flatMap emptyList()
            if (rows.any { it.currentDocumentRevision != revision }) return@flatMap emptyList()
            val current = rows.filter { it.sourceRevision == revision }
            val mapped = current.map { row -> row to sectionScope(row, catalog, sequence) }
            // An unclassifiable current row may be a newer result for any section set.
            if (mapped.any { it.second == null }) return@flatMap emptyList()
            mapped.filter { it.second != null && it.second!!.size > 1 }
                .groupBy { it.second!! }.mapNotNull { (sections, entries) ->
                    val latest = summarize(entries.map { it.first }) as? CourseLatestQuizProgress.Attempt
                        ?: return@mapNotNull null
                    val mapping = sectionMapping(catalog, sequence, document!!, revision)
                    CourseCombinedQuizSummary(document, revision, sections, sections.map { mapping.getValue(it).chapterKey!! }, latest)
                }
        }.sortedWith(compareBy<CourseCombinedQuizSummary> { it.documentId }.thenBy { it.stableKey })
    }

    private fun projectSection(userId: Long, catalog: CourseCatalogSnapshot, sequence: ReadingSequence,
        target: ReaderTarget, rows: List<CourseQuizAttemptSummary>): CourseLatestQuizProgress {
        if (userId <= 0) return CourseLatestQuizProgress.Unavailable
        val mapping = sectionMapping(catalog, sequence, target.documentId, target.contentRevision)
        if (mapping[target.firstSectionId] != target) return CourseLatestQuizProgress.Unavailable
        if (rows.any { it.currentDocumentRevision != target.contentRevision }) return CourseLatestQuizProgress.Unavailable
        val current = rows.filter { it.sourceRevision == target.contentRevision }
        val mapped = current.map { it to sectionScope(it, catalog, sequence) }
        if (mapped.any { it.second == null }) return CourseLatestQuizProgress.Unavailable
        return summarize(mapped.filter { it.second == listOf(target.firstSectionId) }.map { it.first })
    }

    private fun sectionMapping(catalog: CourseCatalogSnapshot, sequence: ReadingSequence, document: String,
        revision: String): Map<String, ReaderTarget> {
        val chapters = catalog.chapters.filter { it.enabled && it.documentId == document }
        val targets = sequence.orderedTargets.filter { it.documentId == document }
        if (chapters.any { it.sectionId == null }) return emptyMap()
        return chapters.mapNotNull { chapter ->
            val section = chapter.sectionId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val target = targets.singleOrNull { it.chapterKey == chapter.chapterKey } ?: return@mapNotNull null
            if (chapter.bookId != catalog.book.bookId || catalog.chapters.count { it.chapterKey == chapter.chapterKey } != 1 ||
                sequence.orderedTargets.count { it.chapterKey == chapter.chapterKey } != 1 ||
                chapters.count { it.sectionId == section } != 1 || target.courseBookId != catalog.book.bookId ||
                target.contentRevision != revision || target.firstSectionId != section || target.lastSectionId != section ||
                target.startLocator != null || target.endLocator != null) return@mapNotNull null
            section to target
        }.toMap()
    }

    private fun sectionScope(row: CourseQuizAttemptSummary, catalog: CourseCatalogSnapshot,
        sequence: ReadingSequence): List<String>? {
        val scope = runCatching { Json.parseToJsonElement(row.scopeJson) as? JsonObject }.getOrNull() ?: return null
        fun string(key: String) = (scope[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val document = row.documentId ?: return null
        if (string("documentId") != document || string("readingScope") != "CURRENT_SECTION") return null
        for (key in listOf("scope", "selection")) if (key in scope && string(key) != "CURRENT_SECTION") return null
        if ("sourceRevision" in scope && string("sourceRevision") != row.sourceRevision) return null
        if ("courseBookId" in scope && string("courseBookId") != catalog.book.bookId) return null
        val raw = scope["sectionIds"] as? JsonArray ?: return null
        val sections = raw.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return null }
        if (sections.isEmpty() || sections.size != sections.distinct().size) return null
        val mapping = sectionMapping(catalog, sequence, document, row.sourceRevision)
        if (sections.any { it !in mapping }) return null
        for (key in listOf("sectionId", "sections", "chapterKeys", "chapterIds", "courseBookIds", "startLocator", "endLocator")) {
            if (key in scope && scope[key] != JsonNull) return null
        }
        if ("documentIds" in scope && scope["documentIds"] != JsonArray(listOf(JsonPrimitive(document)))) return null
        if ("chapterKey" in scope && (sections.size != 1 || string("chapterKey") != mapping.getValue(sections.single()).chapterKey)) return null
        for (key in listOf("chapterId", "legacyChapterId")) if (key in scope) {
            if (sections.size != 1) return null
            val legacy = catalog.chapters.single { it.chapterKey == mapping.getValue(sections.single()).chapterKey }.legacyChapterId ?: return null
            if ((scope[key] as? JsonPrimitive)?.longOrNull != legacy) return null
        }
        return sections.sorted()
    }

    private fun summarize(rows: List<CourseQuizAttemptSummary>): CourseLatestQuizProgress {
        if (rows.isEmpty()) return CourseLatestQuizProgress.NoAttempt
        if (rows.groupBy { it.attemptId }.any { it.value.size != 1 }) return CourseLatestQuizProgress.Unavailable
        val row = rows.maxWithOrNull(compareBy<CourseQuizAttemptSummary> { it.submittedAt ?: it.startedAt }.thenBy { it.attemptId })!!
        val status = validStatus(row) ?: return CourseLatestQuizProgress.Unavailable
        val graded = rows.filter { validStatus(it) == CourseLatestQuizStatus.GRADED }
            .maxWithOrNull(compareBy<CourseQuizAttemptSummary> { it.submittedAt!! }.thenBy { it.attemptId })
        return CourseLatestQuizProgress.Attempt(row.attemptId, status, row.startedAt, row.submittedAt,
            if (status == CourseLatestQuizStatus.GRADED) row.totalScore else null, row.maxScore, row.gradeRevision,
            graded?.let { CourseLatestQuizGrade(it.attemptId, it.totalScore!!, it.maxScore, it.submittedAt!!, it.gradeRevision) })
    }

    private enum class ScopeMatch { MATCH, INVALID }

    private fun scopeMatch(row: CourseQuizAttemptSummary, target: ReaderTarget, legacyChapterId: Long?): ScopeMatch {
        val scope = runCatching { Json.parseToJsonElement(row.scopeJson) as? JsonObject }.getOrNull()
            ?: return ScopeMatch.INVALID
        fun string(key: String): String? = (scope[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val document = string("documentId")?.takeIf { it.isNotBlank() } ?: return ScopeMatch.INVALID
        if (document != row.documentId || document != target.documentId) return ScopeMatch.INVALID
        val readingScope = string("readingScope") ?: string("scope") ?: return ScopeMatch.INVALID
        if (readingScope != "CURRENT_DOCUMENT") return ScopeMatch.INVALID
        for (key in listOf("readingScope", "scope", "selection")) {
            if (key in scope && string(key) != readingScope) return ScopeMatch.INVALID
        }
        for ((key, value) in listOf("sourceRevision" to row.sourceRevision,
            "courseBookId" to target.courseBookId, "chapterKey" to target.chapterKey)) {
            if (key in scope && string(key) != value) return ScopeMatch.INVALID
        }
        // The current producer emits [] for a whole-document scope. It is not a
        // selected-section scope; nonempty or malformed selectors remain unavailable.
        if ("sectionIds" in scope && scope["sectionIds"] != JsonNull) {
            val sections = scope["sectionIds"] as? JsonArray ?: return ScopeMatch.INVALID
            if (sections.isNotEmpty()) return ScopeMatch.INVALID
        }
        for (key in listOf("sectionId", "sections", "chapterKeys", "chapterIds",
            "courseBookIds", "startLocator", "endLocator")) {
            if (key in scope && scope[key] != JsonNull) return ScopeMatch.INVALID
        }
        if ("documentIds" in scope) {
            val documents = scope["documentIds"] as? JsonArray ?: return ScopeMatch.INVALID
            if (documents.size != 1 || (documents.single() as? JsonPrimitive)?.takeIf { it.isString }?.content != document) {
                return ScopeMatch.INVALID
            }
        }
        for (key in listOf("chapterId", "legacyChapterId")) {
            if (key in scope && ((scope[key] as? JsonPrimitive)?.longOrNull != legacyChapterId || legacyChapterId == null)) {
                return ScopeMatch.INVALID
            }
        }
        return ScopeMatch.MATCH
    }

    private fun validStatus(row: CourseQuizAttemptSummary): CourseLatestQuizStatus? {
        if (row.attemptId.isBlank() || row.paperId.isBlank() || row.paperStatus != "READY" ||
            row.maxScore <= 0 || row.startedAt < 0 || row.gradeRevision < 0 ||
            (row.totalScore != null && row.totalScore !in 0..row.maxScore)) return null
        return when (row.status) {
            "IN_PROGRESS" -> if (row.gradingStatus == "NOT_GRADED" && row.submittedAt == null && row.totalScore == null) {
                CourseLatestQuizStatus.IN_PROGRESS
            } else null
            "SUBMITTED_PENDING_REVIEW" -> if (row.gradingStatus == "PENDING_REVIEW" &&
                row.submittedAt != null && row.submittedAt >= row.startedAt) CourseLatestQuizStatus.PENDING_REVIEW else null
            "SUBMITTED_FINAL" -> if (row.gradingStatus == "FINAL" && row.totalScore != null &&
                row.submittedAt != null && row.submittedAt >= row.startedAt) CourseLatestQuizStatus.GRADED else null
            else -> null
        }
    }
}
