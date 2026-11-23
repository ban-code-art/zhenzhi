package com.example.myapplication.data.quiz

import java.io.IOException

/** Exact readable source identity. A display title never substitutes for a revision. */
data class SourcePracticeDocumentBinding(
    val documentId: String,
    val sourceRevision: String,
    val sectionIds: List<String> = emptyList(),
    val ownerUserId: Long? = null,
    val accessEpoch: Long? = null,
    val metadataRevision: Long? = null,
    val extractorVersion: String? = null,
)

data class SourcePracticeScope(
    val documentId: String? = null,
    val sectionIds: List<String> = emptyList(),
    val bundledBookKey: String? = null,
    val bundledChapterKey: String? = null,
    val title: String = "",
    val documentOwnerUserId: Long? = null,
    val accessEpoch: Long? = null,
    val metadataRevision: Long? = null,
    val extractorVersion: String? = null,
    val relatedSources: List<SourcePracticeDocumentBinding> = emptyList(),
    val sourceRevision: String = "",
) {
    internal fun normalized(practice: SourcePractice): SourcePracticeScope {
        if (sourceRevision.isNotBlank() && sourceRevision != practice.sourceRevision)
            throw SourcePracticeArchiveException("练习来源版本与题目不一致。")
        if (documentId.isNullOrBlank() && relatedSources.isEmpty() &&
            (bundledBookKey.isNullOrBlank() || bundledChapterKey.isNullOrBlank()))
            throw SourcePracticeArchiveException("练习缺少可恢复的章节来源。")
        if ((bundledBookKey == null) != (bundledChapterKey == null) ||
            listOfNotNull(documentId, bundledBookKey, bundledChapterKey).any { it.isBlank() || it.length > 1024 } ||
            relatedSources.size > 128 || relatedSources.any { it.documentId.isBlank() || it.sourceRevision.isBlank() })
            throw SourcePracticeArchiveException("练习来源信息不完整。")
        return copy(sectionIds = sectionIds.filter(String::isNotBlank).distinct().sorted(),
            relatedSources = relatedSources.map { it.copy(sectionIds = it.sectionIds.filter(String::isNotBlank).distinct().sorted()) }
                .distinct().sortedWith(compareBy({ it.documentId }, { it.sourceRevision })),
            sourceRevision = practice.sourceRevision)
    }
}

/** Every field is actual user state; normalization never fills in an answer. */
data class SourcePracticeProgress(
    val responses: List<String> = emptyList(),
    val submitted: List<Boolean> = emptyList(),
    val selfReviews: List<Int> = emptyList(),
    val index: Int = 0,
    val summary: Boolean = false,
) {
    fun normalized(practice: SourcePractice): SourcePracticeProgress {
        val answers = practice.questions.indices.map { responses.getOrElse(it) { "" }.take(MAX_RESPONSE_CHARS) }
        val checked = practice.questions.indices.map { submitted.getOrElse(it) { false } && answers[it].isNotBlank() }
        val reviews = practice.questions.indices.map {
            if (checked[it] && practice.questions[it].kind == SourcePracticeKind.RECALL)
                selfReviews.getOrElse(it) { 0 }.coerceIn(0, 2) else 0
        }
        return copy(responses = answers, submitted = checked, selfReviews = reviews,
            index = if (practice.questions.isEmpty()) 0 else index.coerceIn(0, practice.questions.lastIndex))
    }

    companion object { const val MAX_RESPONSE_CHARS = 4000 }
}

data class SourcePracticeRecord(
    val id: String,
    val ownerUserId: Long,
    val practice: SourcePractice,
    val scope: SourcePracticeScope,
    val progress: SourcePracticeProgress = SourcePracticeProgress(),
    val favoriteQuestionIds: Set<String> = emptySet(),
    val createdAt: Long,
    val updatedAt: Long,
    val batchNumber: Int = 1,
) {
    val title: String get() = practice.title
    val sourceRevision: String get() = practice.sourceRevision
    val questionCount: Int get() = practice.questions.size
    val answeredCount: Int get() = progress.responses.count(String::isNotBlank)
    val submittedCount: Int get() = progress.submitted.count { it }
    val favoriteQuestions: List<SourcePracticeQuestion> get() = practice.questions.filter { it.id in favoriteQuestionIds }
    val completed: Boolean get() = questionCount > 0 && submittedCount == questionCount
}

class SourcePracticeArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)
