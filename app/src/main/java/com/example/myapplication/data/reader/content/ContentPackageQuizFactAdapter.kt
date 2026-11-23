package com.example.myapplication.data.reader.content

import com.example.myapplication.data.quiz.QuizAuditedFact
import com.example.myapplication.data.quiz.QuizSupportAssessor
import com.example.myapplication.domain.quiz.QuizEvidence
import com.example.myapplication.domain.quiz.QuizEvidenceRef
import com.example.myapplication.domain.reader.ExtractionQuality
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import com.example.myapplication.domain.reader.SourceEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

enum class ContentQuizFactState { REJECTED, REVIEW_REQUIRED, APPROVED }
data class PreparedContentQuizFact(val fact: QuizAuditedFact, val sourceEvidence: List<SourceEvidence>, val evidenceRefs: List<QuizEvidenceRef>)
data class ContentQuizFactReviewRequest(val factKey: String, val sourceRevision: String, val factHash: String,
    val evidenceHashes: List<String>, val documentId: String, val sectionIds: Set<String>)
data class ContentQuizFacts(val state: ContentQuizFactState, val preparedFacts: List<PreparedContentQuizFact>,
    val reviewRequests: List<ContentQuizFactReviewRequest>, val issues: List<ContentPackageIssue>)
data class ContentQuizFinalReview(val recordKey: String, val recordSha256: String, val recordBytes: ByteArray)
data class ContentQuizFinalReviewRequest(val fact: QuizAuditedFact, val factHash: String, val record: ContentQuizFinalReview)
fun interface ContentQuizFinalReviewVerifier { fun isVerified(request: ContentQuizFinalReviewRequest): Boolean }

/** Bridge to existing quiz evidence/fact contracts; no reviewer authority is supplied by this adapter. */
class ContentPackageQuizFactAdapter {
    fun prepareDraft(manifestBytes: ByteArray, resources: Map<String, ByteArray>, chapterKey: String): ContentQuizFacts =
        prepare(manifestBytes, resources, chapterKey).draft

    fun preparePublication(manifestBytes: ByteArray, resources: Map<String, ByteArray>, chapterKey: String,
        professionalReviewVerifier: ContentProfessionalReviewVerifier? = null,
        finalReviews: Map<String, ContentQuizFinalReview> = emptyMap(),
        finalReviewVerifier: ContentQuizFinalReviewVerifier? = null): ContentQuizFacts {
        val prepared = prepare(manifestBytes, resources, chapterKey)
        val draft = prepared.draft
        if (draft.state == ContentQuizFactState.REJECTED) return draft
        if (professionalReviewVerifier == null || finalReviewVerifier == null) {
            val missing = mutableListOf<ContentPackageIssue>()
            if (professionalReviewVerifier == null) missing += ContentPackageIssue("review", "professional review verification is not connected")
            if (finalReviewVerifier == null) missing += ContentPackageIssue("externalReviews", "final review verification is not connected")
            return draft.copy(issues = missing)
        }
        // Read only this chapter's required keys and preflight every size before copying any receipt or calling an authority.
        val requiredRecords = linkedMapOf<String, ContentQuizFinalReview>()
        var totalReceiptBytes = 0L
        for (item in draft.preparedFacts) {
            val key = item.fact.key
            val record = finalReviews[key]
                ?: return draft.copy(issues = listOf(ContentPackageIssue("externalReviews.$key", "final review is missing")))
            totalReceiptBytes += record.recordBytes.size.toLong()
            if (record.recordBytes.size !in 1..MAX_FINAL_REVIEW_BYTES || totalReceiptBytes > MAX_TOTAL_FINAL_REVIEW_BYTES)
                return draft.copy(issues = listOf(ContentPackageIssue("externalReviews.$key", "bounded final review input required")))
            requiredRecords[key] = record
        }
        // Snapshot external receipts before callbacks; neither callback can mutate the bytes later used by the assessor.
        val records = requiredRecords.mapValues { (_, record) -> record.copy(recordBytes = record.recordBytes.copyOf()) }
        var cancelled: CancellationException? = null
        val guardedProfessional = professionalReviewVerifier?.let { verifier -> ContentProfessionalReviewVerifier { request ->
            try { verifier.isVerified(request) } catch (error: CancellationException) { cancelled = error; throw error }
        } }
        val production = ContentPackageValidator().validate(prepared.manifestBytes, prepared.resources,
            ContentValidationMode.PRODUCTION, prepared.canonical, guardedProfessional)
        cancelled?.let { throw it }
        val issues = production.issues.toMutableList()
        if (!production.productionValid) return draft.copy(issues = issues)
        val accepted = mutableListOf<PreparedContentQuizFact>()
        for (item in draft.preparedFacts) {
            val fact = item.fact
            val record = records[fact.key]
            val factHash = QuizSupportAssessor.factHash(fact)
            val recordIsBound = record != null && record.recordKey.isNotBlank() && record.recordKey.length <= 512 &&
                record.recordSha256.matches(Regex("[0-9a-f]{64}")) &&
                record.recordBytes.size in 1..MAX_FINAL_REVIEW_BYTES &&
                ContentPackageCodec.sha256(record.recordBytes) == record.recordSha256 && finalRecordMatches(record.recordBytes, fact, factHash)
            if (!recordIsBound) {
                issues += ContentPackageIssue("externalReviews.${fact.key}", "final review missing or not bound to current source revision/fact/hash")
                continue
            }
            val verified = try {
                finalReviewVerifier.isVerified(ContentQuizFinalReviewRequest(fact, factHash, record!!.copy(recordBytes = record.recordBytes.copyOf())))
            } catch (error: CancellationException) { throw error } catch (_: Exception) { false }
            if (!verified) {
                issues += ContentPackageIssue("externalReviews.${fact.key}", "final review authority not verified")
                continue
            }
            accepted += item.copy(fact = fact.copy(reviewKey = record!!.recordKey, reviewSha256 = record.recordSha256, approved = true))
        }
        // Never expose earlier approved facts if a later receipt failed.
        if (issues.isNotEmpty() || accepted.size != draft.preparedFacts.size) return draft.copy(issues = issues)
        return ContentQuizFacts(ContentQuizFactState.APPROVED, accepted.toList(), emptyList(), emptyList())
    }

    private data class Prepared(val manifestBytes: ByteArray, val resources: Map<String, ByteArray>,
        val canonical: Map<String, ContentCanonicalChapter>, val draft: ContentQuizFacts)

    private fun prepare(manifestBytes: ByteArray, resources: Map<String, ByteArray>, chapterKey: String): Prepared {
        fun rejected(issues: List<ContentPackageIssue>) = Prepared(ByteArray(0), emptyMap(), emptyMap(),
            ContentQuizFacts(ContentQuizFactState.REJECTED, emptyList(), emptyList(), issues))
        if (manifestBytes.size > 1024 * 1024 || resources.size > 10_000 ||
            resources.values.any { it.size > 8 * 1024 * 1024 } || resources.values.sumOf { it.size.toLong() } + manifestBytes.size > 256L * 1024 * 1024)
            return rejected(listOf(ContentPackageIssue("root", "bounded package input required")))
        val raw = manifestBytes.copyOf()
        val snapshot = resources.mapValues { (_, bytes) -> bytes.copyOf() }
        val format = ContentPackageValidator().validate(raw, snapshot)
        if (!format.formatValid) return rejected(format.issues)
        return try {
            val manifest = ContentPackageCodec.decode(raw)
            val chapters = manifest.objects("chapters")
            val chapter = chapters.singleOrNull { it.text("chapterKey") == chapterKey }
                ?: return rejected(listOf(ContentPackageIssue("root.chapters", "requested chapter is missing")))
            val canonical = chapters.associate { it.text("chapterKey") to ContentPackageCanonical.parse(it, snapshot.getValue(it.text("bodyPath"))) }
            val canonicalCheck = ContentPackageValidator().validate(raw, snapshot, ContentValidationMode.CANONICAL, canonical)
            if (canonicalCheck.canonicalValid != true) return rejected(canonicalCheck.issues)
            val index = canonical.getValue(chapterKey)
            val revision = chapter.text("contentRevision")
            val documentId = requireNotNull(index.documentId) { "real canonical document identity missing" }
            val sectionId = requireNotNull(index.sectionId) { "real canonical section identity missing" }
            val parserVersion = requireNotNull(index.parserVersion) { "real parser version missing" }
            require(index.contentRevision == revision && index.bodySha256 == chapter.text("bodySha256")) { "canonical identity mismatch" }
            val evidenceRegistry = linkedMapOf<List<String>, SourceEvidence>()
            val facts = mutableListOf<PreparedContentQuizFact>()
            for (point in chapter.objects("knowledgePoints")) for (wireFact in point.objects("facts")) {
                val references = wireFact.objects("evidence")
                require(references.isNotEmpty()) { "fact requires real evidence" }
                val sources = references.map { evidence ->
                    val start = evidence.number("canonicalStart");val end = evidence.number("canonicalEnd");val quote = evidence.text("quote")
                    val block = index.blocks.single { it.blockId == evidence.text("blockId") }
                    val runtime = index.runtimeBlocks.single { it.blockId == block.runtimeBlockId }
                    require(runtime.sectionId == sectionId && runtime.extractionQuality == ExtractionQuality.VERIFIED_TEXT &&
                        start >= runtime.canonicalStart && end <= runtime.canonicalEnd && end > start &&
                        contentUtf16Boundary(index.canonicalText, start) && contentUtf16Boundary(index.canonicalText, end) &&
                        index.canonicalText.substring(start, end) == quote) { "fact evidence differs from actual reader block" }
                    val identity = listOf(evidence.text("sourceKey"), runtime.blockId, start.toString(), end.toString(), quote)
                    evidenceRegistry.getOrPut(identity) {
                        require(evidenceRegistry.size < 9999) { "evidence registry exceeds supported S IDs" }
                        val locator = ReaderLocator.Reflow(documentId, sectionId, revision, null, runtime.nativeLocator,
                            quote, canonicalStart = start, canonicalEnd = end, blockId = runtime.blockId)
                        require(ReaderLocatorValidator.validateForDocument(locator, documentId, revision).isValid) { "invalid reader locator" }
                        SourceEvidence("S${evidenceRegistry.size + 1}", documentId, sectionId, revision, locator, quote,
                            QuizEvidence.quoteSha256(quote), chapter.text("title"), parserVersion)
                    }
                }
                require(sources.map { it.evidenceId }.distinct().size == sources.size) { "duplicate fact evidence" }
                val refs = sources.map(QuizEvidence::fromSource)
                val fact = QuizAuditedFact(wireFact.text("factKey"), wireFact.text("kind"), wireFact.text("prompt"),
                    wireFact.strings("answerParts"), wireFact.strings("distractors"), wireFact.text("analysis"),
                    point.text("knowledgePointKey"), revision, refs.map { it.quoteSha256 }.distinct().sorted(),
                    reviewKey = "", reviewSha256 = "", approved = false, sourceDocumentId = documentId, sourceSectionIds = setOf(sectionId))
                facts += PreparedContentQuizFact(fact, sources.toList(), refs)
            }
            require(facts.isNotEmpty()) { "chapter has no fact draft" }
            val requests = facts.map { ContentQuizFactReviewRequest(it.fact.key, revision, QuizSupportAssessor.factHash(it.fact),
                it.fact.evidenceHashes, documentId, it.fact.sourceSectionIds) }
            Prepared(raw, snapshot, canonical, ContentQuizFacts(ContentQuizFactState.REVIEW_REQUIRED, facts.toList(), requests,
                listOf(ContentPackageIssue("review", "REVIEW_REQUIRED: external final-revision fact review must be supplied by a trusted reviewer"))))
        } catch (error: CancellationException) { throw error } catch (error: Exception) {
            rejected(listOf(ContentPackageIssue("root.canonical", error.message ?: "actual canonical binding rejected")))
        }
    }

    private fun finalRecordMatches(bytes: ByteArray, fact: QuizAuditedFact, factHash: String): Boolean = try {
        val root = Json.parseToJsonElement(ContentPackageCodec.utf8(bytes)).jsonObject
        val version = root["schemaVersion"] as? JsonPrimitive
        fun text(name: String) = (root[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        version != null && !version.isString && version.intOrNull == 1 && text("status") == "APPROVED" &&
            text("factKey") == fact.key && text("sourceRevision") == fact.sourceRevision && text("factHash") == factHash
    } catch (_: Exception) { false }

    private fun JsonObject.objects(key: String) = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun JsonObject.number(key: String) = text(key).toInt()

    private companion object {
        const val MAX_FINAL_REVIEW_BYTES = 1024 * 1024
        const val MAX_TOTAL_FINAL_REVIEW_BYTES = 16L * 1024 * 1024
    }
}
