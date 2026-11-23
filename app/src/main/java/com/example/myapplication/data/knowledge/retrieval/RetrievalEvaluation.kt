package com.example.myapplication.data.knowledge.retrieval

import java.security.MessageDigest
import kotlin.math.ln
import kotlinx.coroutines.CancellationException

/** Independent human-labelled corpus contract. Synthetic fixtures only validate arithmetic. */
object RetrievalEvaluation {
    enum class Kind { EXACT, SEMANTIC, NO_ANSWER, COMPARISON }
    data class Case(val id: String, val kind: Kind, val query: String,
        val relevantEvidence: Set<String>, val sourceDocument: String,
        val sourceRevision: String, val annotator: String, val annotationMethod: String = "HUMAN_SOURCE_REVIEW",
        val labelledSources: Map<String, EvidenceSource> = emptyMap())
    /** Human-reviewed gold identity and hash, recorded independently of predictions. */
    data class EvidenceSource(val documentId: String, val revision: String, val quoteSha256: String)
    data class Citation(val evidenceId: String, val documentId: String, val revision: String, val quote: String)
    data class SourceReadback(val documentId: String, val revision: String, val quote: String)
    /** Reads independent source material, never a retrieved chunk or a claimed verified flag. */
    fun interface SourceVerifier { fun read(citation: Citation): SourceReadback? }
    data class Observation(val caseId: String, val rankedEvidence: List<String>, val rejected: Boolean,
        val degradedReason: String? = null, val citations: List<Citation> = emptyList())
    data class Report(val status: String, val errors: List<String>, val evaluatedCount: Int,
        val recallAt20: Double?, val ndcgAt20: Double?, val noAnswerRejectionRate: Double?,
        val citationCorrectnessRate: Double? = null, val degradedCount: Int = 0,
        val sourceValidationMissingCount: Int = 0, val duplicatePredictionCount: Int = 0,
        val citationCount: Int = 0, val sourceValidatedCitationCount: Int = 0,
        val kindCounts: Map<Kind, Int> = emptyMap(), val duplicateCitationCount: Int = 0)

    /**
     * Scores an independently labelled corpus. Small fixtures can test arithmetic, but cannot
     * claim the required 40/30/20/10 quality evaluation. Quality acceptance is an external gate;
     * this reporter never grants ACCEPTED merely because measurements exist.
     *
     * [sourceVerifier] must read original source or separately collected DocumentAccess readback
     * records. Neither prediction text nor a caller-supplied "verified" flag is source authority.
     */
    fun evaluate(cases: List<Case>, observations: List<Observation>, sourceVerifier: SourceVerifier? = null): Report {
        val gold = cases.map { it.copy(relevantEvidence = it.relevantEvidence.toSet(), labelledSources = it.labelledSources.toMap()) }
        val predictions = observations.map { it.copy(rankedEvidence = it.rankedEvidence.toList(), citations = it.citations.toList()) }
        val errors = linkedSetOf<String>()
        val kindCounts = Kind.entries.associateWith { kind -> gold.count { it.kind == kind } }
        fun invalid(): Report = Report("INVALID", errors.toList(), 0, null, null, null, kindCounts = kindCounts)

        if (gold.isEmpty()) {
            errors += "HUMAN_CORPUS_MISSING"
            if (predictions.isNotEmpty()) {
                errors += "UNKNOWN_OBSERVATION"
                return invalid()
            }
            return Report("NOT_EVALUATED", errors.toList(), 0, null, null, null, kindCounts = kindCounts)
        }
        gold.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.forEach { errors += "DUPLICATE_CASE:$it" }
        predictions.groupingBy { it.caseId }.eachCount().filterValues { it > 1 }.keys.forEach { errors += "DUPLICATE_OBSERVATION:$it" }
        for (case in gold) {
            if (case.id.isBlank() || case.query.isBlank()) errors += "CASE_ID_OR_QUERY_MISSING:${case.id}"
            if (case.annotator.isBlank() || case.annotationMethod != "HUMAN_SOURCE_REVIEW") errors += "ANNOTATION_INVALID:${case.id}"
            if (case.sourceDocument.isBlank() || case.sourceRevision.isBlank()) errors += "SOURCE_PROVENANCE_MISSING:${case.id}"
            if (case.relevantEvidence.any { it.isBlank() } ||
                (case.kind == Kind.NO_ANSWER && case.relevantEvidence.isNotEmpty()) ||
                (case.kind != Kind.NO_ANSWER && case.relevantEvidence.isEmpty())) errors += "GOLD_INVALID:${case.id}"
            for ((id, source) in case.labelledSources) {
                if (id !in case.relevantEvidence || source.documentId.isBlank() || source.revision.isBlank() ||
                    !source.quoteSha256.matches(Regex("[0-9a-fA-F]{64}"))) errors += "GOLD_SOURCE_INVALID:${case.id}:$id"
            }
        }
        val known = gold.map { it.id }.toSet()
        val observed = predictions.map { it.caseId }.toSet()
        (known - observed).forEach { errors += "OBSERVATION_MISSING:$it" }
        (observed - known).forEach { errors += "UNKNOWN_OBSERVATION:$it" }
        for (observation in predictions) {
            if (observation.rankedEvidence.any { it.isBlank() }) errors += "PREDICTION_ID_MISSING:${observation.caseId}"
            if (observation.citations.any { it.evidenceId.isBlank() || it.documentId.isBlank() ||
                    it.revision.isBlank() || it.quote.isBlank() }) errors += "CITATION_INVALID:${observation.caseId}"
        }
        // Missing or duplicate observations invalidate the denominator instead of shrinking it.
        if (errors.isNotEmpty()) return invalid()

        val byId = predictions.associateBy { it.caseId }
        var recallSum = 0.0
        var ndcgSum = 0.0
        var answered = 0
        var noAnswer = 0
        var rejectedNoAnswer = 0
        var duplicatePredictions = 0
        var degraded = 0
        var citations = 0
        var correctCitations = 0
        var sourceChecks = 0
        var missingSourceChecks = 0
        var duplicateCitations = 0

        for (case in gold) {
            val observation = byId.getValue(case.id)
            duplicatePredictions += observation.rankedEvidence.size - observation.rankedEvidence.toSet().size
            if (!observation.degradedReason.isNullOrBlank()) degraded++
            if (case.kind == Kind.NO_ANSWER) {
                noAnswer++
                if (observation.rejected) rejectedNoAnswer++
            } else {
                answered++
                val top = observation.rankedEvidence.take(20)
                recallSum += top.toSet().count { it in case.relevantEvidence }.toDouble() / case.relevantEvidence.size
                val seen = hashSetOf<String>()
                val dcg = top.mapIndexed { rank, id ->
                    if (seen.add(id) && id in case.relevantEvidence) discount(rank + 1) else 0.0
                }.sum()
                val ideal = (1..minOf(20, case.relevantEvidence.size)).sumOf { discount(it) }
                ndcgSum += dcg / ideal
            }

            val cited = hashSetOf<String>()
            for (citation in observation.citations) {
                citations++
                val duplicate = !cited.add(citation.evidenceId)
                if (duplicate) {
                    duplicateCitations++
                    errors += "DUPLICATE_CITATION:${case.id}:${citation.evidenceId}"
                }
                val labelledSource = case.labelledSources[citation.evidenceId]
                // A relevant id alone cannot authenticate a quote without a human source hash.
                if (citation.evidenceId in case.relevantEvidence && labelledSource == null) {
                    missingSourceChecks++
                    errors += "GOLD_SOURCE_MISSING:${case.id}:${citation.evidenceId}"
                    continue
                }
                val readback = try {
                    sourceVerifier?.read(citation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    errors += "SOURCE_VERIFICATION_FAILED:${case.id}:${citation.evidenceId}"
                    null
                }
                if (readback == null) {
                    missingSourceChecks++
                    errors += "SOURCE_VERIFICATION_MISSING:${case.id}:${citation.evidenceId}"
                    continue
                }
                sourceChecks++
                val correct = !duplicate && case.kind != Kind.NO_ANSWER && labelledSource != null &&
                    citation.documentId == labelledSource.documentId && citation.revision == labelledSource.revision &&
                    sha256(citation.quote).equals(labelledSource.quoteSha256, ignoreCase = true) &&
                    readback.documentId == citation.documentId && readback.revision == citation.revision &&
                    readback.quote == citation.quote
                if (correct) correctCitations++ else errors += "CITATION_INCORRECT:${case.id}:${citation.evidenceId}"
            }
        }
        if (duplicatePredictions > 0) errors += "DUPLICATE_PREDICTION:$duplicatePredictions"
        val expected = mapOf(Kind.EXACT to 40, Kind.SEMANTIC to 30, Kind.NO_ANSWER to 20, Kind.COMPARISON to 10)
        val fullCorpus = gold.size == 100 && kindCounts == expected
        if (!fullCorpus) errors += "HUMAN_CORPUS_SHAPE_INCOMPLETE"
        val fixtures = gold.any { it.annotator.uppercase().contains("FIXTURE") }
        if (fixtures) errors += "FIXTURE_CORPUS_NOT_ACCEPTABLE"
        val status = when {
            missingSourceChecks > 0 -> "INVALID"
            !fullCorpus || fixtures -> "PARTIAL_CORPUS"
            else -> "EVALUATED"
        }
        return Report(
            status = status, errors = errors.toList(), evaluatedCount = gold.size,
            recallAt20 = if (answered > 0) recallSum / answered else null,
            ndcgAt20 = if (answered > 0) ndcgSum / answered else null,
            noAnswerRejectionRate = if (noAnswer > 0) rejectedNoAnswer.toDouble() / noAnswer else null,
            citationCorrectnessRate = if (citations > 0 && missingSourceChecks == 0) correctCitations.toDouble() / citations else null,
            degradedCount = degraded, sourceValidationMissingCount = missingSourceChecks,
            duplicatePredictionCount = duplicatePredictions, citationCount = citations,
            sourceValidatedCitationCount = sourceChecks, kindCounts = kindCounts, duplicateCitationCount = duplicateCitations,
        )
    }

    private fun discount(rank: Int): Double = 1.0 / (ln(rank + 1.0) / ln(2.0))
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
