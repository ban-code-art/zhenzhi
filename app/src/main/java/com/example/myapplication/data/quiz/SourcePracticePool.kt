package com.example.myapplication.data.quiz

/** A new local group; the source window can continue even after its current candidates run out. */
data class SourcePracticeBatch(
    val practice: SourcePractice,
    val number: Int,
    val hasMore: Boolean,
)

/**
 * Keeps only a bounded set of extracted questions, not every previously read source window.
 * A prepared fork is committed only after source authorization and cancellation checks succeed.
 */
internal class SourcePracticePool(val title: String, val sourceRevision: String) {
    private val pending = linkedMapOf<String, SourcePracticeQuestion>()
    private val shownIds = mutableSetOf<String>()
    private val shownSources = mutableSetOf<String>()
    private val shownStems = mutableSetOf<String>()
    private val sectionIds = mutableSetOf<String>()
    private var sourceHasMore = false
    private var completeCoverage = true
    var batchNumber: Int = 0
        private set

    val hasPending: Boolean get() = pending.isNotEmpty()
    val limitReached: Boolean get() = shownIds.size >= MAX_SESSION_QUESTIONS
    val hasMore: Boolean get() = !limitReached && (hasPending || sourceHasMore)
    val pendingCount: Int get() = pending.size

    fun append(sections: List<SourcePracticeSection>, complete: Boolean, canReadMore: Boolean) {
        sectionIds += sections.map { it.sectionId }
        completeCoverage = complete
        sourceHasMore = canReadMore
        if (limitReached) return
        SourcePracticeFactory.sourceCandidates(sourceRevision, sections).forEach { question ->
            val source = SourcePracticeFactory.sourceFingerprint(question)
            val stem = normalizedStem(question)
            if (pending.size < MAX_PENDING && question.id !in shownIds && source !in shownSources &&
                stem !in shownStems && source !in pending) {
                pending[source] = question
            }
        }
    }

    fun nextBatch(maxQuestions: Int = QUESTIONS_PER_BATCH): SourcePracticeBatch? {
        require(maxQuestions in 1..QUESTIONS_PER_BATCH)
        val selected = SourcePracticeFactory.selectSourceQuestions(pending.values.toList(),
            minOf(maxQuestions, MAX_SESSION_QUESTIONS - shownIds.size))
        if (selected.isEmpty()) return null
        selected.forEach { question ->
            val source = SourcePracticeFactory.sourceFingerprint(question)
            pending.remove(source)
            shownSources += source
            shownIds += question.id
            shownStems += normalizedStem(question)
        }
        // The same stem can arise from separate reads of an overlapping source fragment.
        pending.entries.removeAll { (_, question) -> normalizedStem(question) in shownStems }
        if (limitReached) pending.clear()
        batchNumber++
        return SourcePracticeBatch(
            SourcePracticeFactory.sourceBatch(title, sourceRevision, selected, sectionIds.size, completeCoverage),
            batchNumber,
            hasMore,
        )
    }

    fun emptyPractice(): SourcePractice = SourcePracticeFactory.sourceBatch(
        title, sourceRevision, emptyList(), sectionIds.size, completeCoverage,
    )

    fun fork(): SourcePracticePool = SourcePracticePool(title, sourceRevision).also { copy ->
        copy.pending.putAll(pending)
        copy.shownIds.addAll(shownIds)
        copy.shownSources.addAll(shownSources)
        copy.shownStems.addAll(shownStems)
        copy.sectionIds.addAll(sectionIds)
        copy.sourceHasMore = sourceHasMore
        copy.completeCoverage = completeCoverage
        copy.batchNumber = batchNumber
    }

    private fun normalizedStem(question: SourcePracticeQuestion): String = SourcePracticeFactory.stemFingerprint(question)

    companion object {
        const val QUESTIONS_PER_BATCH = 12
        const val MAX_PENDING = 512
        const val MAX_SESSION_QUESTIONS = 8_192
    }
}
