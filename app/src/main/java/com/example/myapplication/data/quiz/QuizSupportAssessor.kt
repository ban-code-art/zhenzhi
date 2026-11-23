package com.example.myapplication.data.quiz

import com.example.myapplication.domain.quiz.*
import kotlinx.serialization.json.*
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import java.security.MessageDigest
import java.util.Random
import kotlinx.coroutines.CancellationException

/** A bound quote establishes provenance; only an independently reviewed immutable template establishes support. */
class QuizSupportAssessor(private val reviewReader: (String) -> ByteArray? = { null }) {
    fun assess(item: QuizItemSnapshot, refs: List<QuizEvidenceRef>, sourceRevision: String,
        fact: QuizAuditedFact? = null, seed: Long = 0, structureValid: Boolean = true,
        coverageComplete: Boolean = true): QuizSupportAssessment {
        val hashes = refs.map { it.quoteSha256.lowercase() }.distinct().sorted()
        fun result(state: QuizSupportState, reason: String, authority: String = "NONE") = QuizSupportAssessment(
            itemHash(item), sourceRevision, state, listOf(reason), authority, hashes,
            fact?.key, fact?.reviewSha256, assertions(item, hashes, state, reason))
        fun reject(reason: String) = result(QuizSupportState.UNSUPPORTED, reason, "DETERMINISTIC_REJECTION")
        if (!structureValid) return reject("STRUCTURE_INVALID")
        if (sourceRevision.isBlank() || refs.any { it.contentRevision != sourceRevision ||
                it.locator.contentRevision != sourceRevision }) return reject("SOURCE_CHANGED")
        if (refs.isEmpty() || item.evidenceIds.isEmpty() || refs.map { it.evidenceId }.toSet() != item.evidenceIds.toSet() ||
            refs.map { it.evidenceId }.distinct().size != refs.size ||
            refs.any { !QuizEvidence.isValidEvidenceId(it.evidenceId) || !QuizEvidence.isSelfConsistent(it) ||
                it.documentId != it.locator.documentId || it.sectionId != it.locator.sectionId }) return reject("EVIDENCE_INVALID")
        if (!coverageComplete) return result(QuizSupportState.UNCERTAIN,"COVERAGE_INCOMPLETE")
        if (fact == null) return result(QuizSupportState.UNCERTAIN,"NEEDS_HUMAN_REVIEW")
        if (fact.sourceRevision != sourceRevision) return reject("SOURCE_CHANGED")
        if (!fact.approved || fact.kind !in setOf("SEQUENCE","DEFINITION","ENUMERATION"))
            return result(QuizSupportState.UNCERTAIN,"NEEDS_HUMAN_REVIEW")
        if (fact.evidenceHashes.map { it.lowercase() }.distinct().sorted() != hashes)
            return result(QuizSupportState.UNCERTAIN,"UNRELATED_EVIDENCE")
        val review = try { reviewReader(fact.reviewKey) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        if (review == null || sha256(review) != fact.reviewSha256.lowercase())
            return result(QuizSupportState.UNCERTAIN,"REVIEW_UNVERIFIED")
        val verifiedReview = try {
            val record = Json.parseToJsonElement(review.toString(Charsets.UTF_8)).jsonObject
            val version = record["schemaVersion"] as? JsonPrimitive
            fun reviewedString(key: String): String? = (record[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            version != null && !version.isString && version.intOrNull == 1 &&
                reviewedString("status") == "APPROVED" &&
                reviewedString("factKey") == fact.key &&
                reviewedString("sourceRevision") == sourceRevision &&
                reviewedString("factHash") == factHash(fact)
        } catch (_: Exception) { false }
        if (!verifiedReview) return result(QuizSupportState.UNCERTAIN,"REVIEW_UNVERIFIED")
        if (fact.sourceDocumentId.isBlank() || fact.sourceSectionIds.isEmpty() ||
            refs.any { it.documentId != fact.sourceDocumentId } || refs.map { it.sectionId }.toSet() != fact.sourceSectionIds)
            return result(QuizSupportState.UNCERTAIN,"UNRELATED_EVIDENCE")
        val expected = try { renderFactV1(fact,item.type,refs,item.ordinal,seed,item.templateVersion).copy(difficulty=item.difficulty) }
            catch (_: IllegalArgumentException) { return result(QuizSupportState.UNCERTAIN,"FACT_NOT_APPLICABLE") }
        // A contradictory answer is rejected only when the same reviewed question is being asserted.
        if (item.stem == expected.stem && item.options == expected.options && item.answer != expected.answer)
            return reject("CONTRADICTS_FACT")
        if (itemHash(item) != itemHash(expected)) return result(QuizSupportState.UNCERTAIN,"NEEDS_HUMAN_REVIEW")
        return result(QuizSupportState.SUPPORTED,"AUDITED_TEMPLATE_MATCH","AUDITED_TEMPLATE")
    }

    fun renderFactV1(fact: QuizAuditedFact, type: QuizItemType, refs: List<QuizEvidenceRef>,
        ordinal: Int = 1, seed: Long = 0, templateVersion: String = "fact-v2"): QuizItemSnapshot {
        require(ordinal > 0) { "fact rendering requires a positive ordinal" }
        require(fact.key.isNotBlank() && fact.prompt.isNotBlank() && fact.analysis.isNotBlank() && fact.knowledgePoint.isNotBlank())
        require(fact.answerParts.isNotEmpty() && fact.answerParts.all { it.isNotBlank() } && fact.answerParts.distinct().size==fact.answerParts.size)
        require(refs.isNotEmpty() && refs.all { QuizEvidence.isSelfConsistent(it) && it.contentRevision == fact.sourceRevision })
        val correct = fact.answerParts.joinToString("；")
        val options = mutableListOf<String>()
        val answer: List<String>
        val rubric: List<RubricPoint>
        if (type == QuizItemType.CHOICE) {
            require(fact.distractors.size >= 3)
            options += correct; options += fact.distractors.take(3)
            require(options.all { it.isNotBlank() } && options.map { it.trim() }.distinct().size == 4)
            // Version the permutation: persisted v1 snapshots retain their original option order.
            // Reusing a paper seed alone fixes every answer to one position; bind each new item too.
            val itemSeed = if (templateVersion == "fact-v2") {
                val material = "$seed\n$ordinal\n${fact.key}".toByteArray(Charsets.UTF_8)
                java.nio.ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(material)).long
            } else seed
            val random = Random(itemSeed)
            for (i in options.lastIndex downTo 1) { val j=random.nextInt(i+1); val value=options[i];options[i]=options[j];options[j]=value }
            answer=listOf(('A'.code+options.indexOf(correct)).toChar().toString());rubric=emptyList()
        } else {
            require(fact.answerParts.size in 3..5)
            answer=fact.answerParts.toList()
            rubric=fact.answerParts.mapIndexed { index, value -> RubricPoint("${fact.key}:point:${index+1}",
                10/fact.answerParts.size + if(index < 10%fact.answerParts.size) 1 else 0, listOf(value)) }
        }
        return QuizItemSnapshot(ordinal,type,fact.prompt,options.toList(),answer,10,
            fact.analysis+"依据："+refs.joinToString("\n") { it.quote },fact.knowledgePoint,
            rubric=rubric,evidenceIds=refs.map { it.evidenceId },templateVersion=templateVersion)
    }

    private fun assertions(item: QuizItemSnapshot, hashes: List<String>, state: QuizSupportState, reason: String): List<QuizAssertionCheck> {
        val claims=mutableListOf("STEM" to item.stem)
        if (item.type == QuizItemType.CHOICE) {
            // The letter is a selector, not the proposition reviewed beside the original source.
            val letter = item.answer.singleOrNull()?.singleOrNull()
            val answerIndex = letter?.let { it - 'A' }
            val text = answerIndex?.takeIf { it in item.options.indices }?.let { item.options[it] }
            claims += "CORRECT_ANSWER" to (text ?: "INVALID_CHOICE_ANSWER")
        } else item.answer.forEach { claims += "CORRECT_ANSWER" to it }
        item.options.forEachIndexed { index, text -> if(('A'.code+index).toChar().toString() !in item.answer) claims += "DISTRACTOR" to text }
        item.rubric.forEach { claims += "RUBRIC" to it.accepted.joinToString("；") }
        claims += "ANALYSIS" to item.analysis
        return claims.mapIndexed { index, claim -> QuizAssertionCheck("${item.ordinal}:$index",claim.first,claim.second,hashes,
            when(state){QuizSupportState.SUPPORTED->"SUPPORTED";QuizSupportState.UNSUPPORTED->"CONTRADICTED";else->"UNCERTAIN"},reason) }
    }

    companion object {
        private fun canonical(e: JsonElement): String = when(e) {
            is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",","{","}") {
                require(ReaderLocatorValidator.isWellFormedUtf16(it.key)) { "Malformed UTF16 JSON key" }
                JsonPrimitive(it.key).toString()+":"+canonical(it.value)
            }
            is JsonArray -> e.joinToString(",","[","]") { canonical(it) }
            is JsonPrimitive -> {
                require(!e.isString || ReaderLocatorValidator.isWellFormedUtf16(e.content)) { "Malformed UTF16 JSON value" }
                e.toString()
            }
        }
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun itemHash(item: QuizItemSnapshot): String = sha256(canonical(Json.parseToJsonElement(QuizCodecs.encodeItemSnapshot(item))).toByteArray(Charsets.UTF_8))
        fun factHash(f: QuizAuditedFact): String = sha256(canonical(buildJsonObject {
            put("key",f.key);put("kind",f.kind);put("prompt",f.prompt);put("answerParts",JsonArray(f.answerParts.map(::JsonPrimitive)))
            put("distractors",JsonArray(f.distractors.map(::JsonPrimitive)));put("analysis",f.analysis);put("knowledgePoint",f.knowledgePoint)
            put("sourceDocumentId",f.sourceDocumentId);put("sourceSectionIds",JsonArray(f.sourceSectionIds.sorted().map(::JsonPrimitive)))
            put("sourceRevision",f.sourceRevision);put("evidenceHashes",JsonArray(f.evidenceHashes.map(::JsonPrimitive)))
        }).toByteArray(Charsets.UTF_8))
        fun encodeAssessment(a: QuizSupportAssessment): JsonObject = buildJsonObject {
            a.approval?.let { p -> put("approval",buildJsonObject {
                put("reviewerUserId",p.reviewerUserId);put("reviewedAtUtc",p.reviewedAtUtc);put("draftHash",p.draftHash)
                put("evidenceHashes",JsonArray(p.evidenceHashes.map(::JsonPrimitive)))
                put("checkedAssertions",JsonArray(p.checkedAssertions.sorted().map(::JsonPrimitive)));put("decision",p.decision)
            }) }
            put("schemaVersion",a.schemaVersion);put("itemDraftHash",a.itemDraftHash);put("sourceRevision",a.sourceRevision)
            put("state",a.state.name);put("reasonCodes",JsonArray(a.reasonCodes.map(::JsonPrimitive)));put("authority",a.authority)
            a.factKey?.let { put("factKey",it) }; a.factReviewSha256?.let { put("factReviewSha256",it) }
            put("evidenceHashes",JsonArray(a.evidenceHashes.map(::JsonPrimitive)))
            put("assertionChecks",JsonArray(a.assertionChecks.map { c -> buildJsonObject {
                put("assertionKey",c.assertionKey);put("role",c.role);put("claimText",c.claimText)
                put("evidenceHashes",JsonArray(c.evidenceHashes.map(::JsonPrimitive)));put("judgement",c.judgement);put("reason",c.reason)
            } }))
        }
    }
}
