package com.example.myapplication.data.quiz

import com.example.myapplication.domain.quiz.QuizItemSnapshot
import com.example.myapplication.domain.quiz.QuizItemType
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.*

data class QuizPersonalApproval(
    val reviewerUserId: Long,
    val reviewedAtUtc: String,
    val draftHash: String,
    val evidenceHashes: List<String>,
    val checkedAssertions: Set<String>,
    val decision: String = "ACCEPT_FOR_PERSONAL_PRACTICE",
)

/** A personal receipt binds every actual claim; it never promotes uncertain content to SUPPORTED. */
object QuizPersonalReview {
    private val hashPattern = Regex("[a-fA-F0-9]{64}")
    private val allowedReasons = setOf("NEEDS_HUMAN_REVIEW", "REVIEW_UNVERIFIED", "FACT_NOT_APPLICABLE", "USER_EDIT_REQUIRES_REVIEW")

    fun isReviewable(item: QuizItemSnapshot, assessment: QuizSupportAssessment): Boolean {
        val hashes = assessment.evidenceHashes.map { it.lowercase(Locale.ROOT) }
        val claims = assessment.assertionChecks
        return assessment.schemaVersion == 1 && assessment.sourceRevision.isNotBlank() &&
            assessment.state == QuizSupportState.UNCERTAIN && assessment.reasonCodes.isNotEmpty() &&
            assessment.reasonCodes.all { it in allowedReasons } &&
            assessment.itemDraftHash == QuizSupportAssessor.itemHash(item) &&
            hashes.isNotEmpty() && hashes.all(hashPattern::matches) && hashes.distinct().size == hashes.size &&
            claims.isNotEmpty() && claims.map { it.assertionKey }.distinct().size == claims.size &&
            claims.all { it.assertionKey.isNotBlank() && it.claimText.isNotBlank() &&
                it.judgement in setOf("UNCERTAIN", "USER_CONFIRMED") &&
                it.evidenceHashes.map { h -> h.lowercase(Locale.ROOT) }.toSet() == hashes.toSet() } &&
            claims.map { it.role to it.claimText }.groupingBy { it }.eachCount() ==
                expectedClaims(item).groupingBy { it }.eachCount()
    }

    fun approve(item: QuizItemSnapshot, assessment: QuizSupportAssessment,
        approval: QuizPersonalApproval, userId: Long): QuizSupportAssessment? {
        if (userId <= 0 || approval.reviewerUserId != userId ||
            approval.decision != "ACCEPT_FOR_PERSONAL_PRACTICE" || !hashPattern.matches(approval.draftHash) ||
            !validUtc(approval.reviewedAtUtc) || !isReviewable(item, assessment) ||
            approval.evidenceHashes.map { it.lowercase(Locale.ROOT) }.toSet() !=
                assessment.evidenceHashes.map { it.lowercase(Locale.ROOT) }.toSet() ||
            approval.evidenceHashes.map { it.lowercase(Locale.ROOT) }.distinct().size != approval.evidenceHashes.size ||
            assessment.assertionChecks.map { it.assertionKey }.toSet() != approval.checkedAssertions) return null
        return assessment.copy(authority = "USER_REVIEW", approval = approval,
            assertionChecks = assessment.assertionChecks.map { it.copy(judgement = "USER_CONFIRMED") })
    }

    /** Mirrors the frozen support matrix: a choice letter itself is not the proposition being reviewed. */
    fun expectedClaims(item: QuizItemSnapshot): List<Pair<String, String>> = buildList {
        add("STEM" to item.stem)
        if (item.type == QuizItemType.CHOICE) {
            val index = item.answer.singleOrNull()?.singleOrNull()?.let { it - 'A' }
            add("CORRECT_ANSWER" to (index?.takeIf { it in item.options.indices }?.let { item.options[it] } ?: "INVALID_CHOICE_ANSWER"))
            item.options.forEachIndexed { i, text -> if (('A'.code + i).toChar().toString() !in item.answer) add("DISTRACTOR" to text) }
        } else item.answer.forEach { add("CORRECT_ANSWER" to it) }
        item.rubric.forEach { add("RUBRIC" to it.accepted.joinToString("；")) }
        add("ANALYSIS" to item.analysis)
    }

    fun decodeAssessment(o: JsonObject): QuizSupportAssessment {
        fun strings(key: String) = (o[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        val checks = (o["assertionChecks"] as? JsonArray)?.map { raw ->
            val c = raw.jsonObject
            QuizAssertionCheck(c.getValue("assertionKey").jsonPrimitive.content,c.getValue("role").jsonPrimitive.content,
                c.getValue("claimText").jsonPrimitive.content,c.getValue("evidenceHashes").jsonArray.map { it.jsonPrimitive.content },
                c.getValue("judgement").jsonPrimitive.content,c.getValue("reason").jsonPrimitive.content)
        }.orEmpty()
        return QuizSupportAssessment(o.getValue("itemDraftHash").jsonPrimitive.content,o.getValue("sourceRevision").jsonPrimitive.content,
            QuizSupportState.valueOf(o.getValue("state").jsonPrimitive.content),strings("reasonCodes"),o.getValue("authority").jsonPrimitive.content,
            strings("evidenceHashes"),o["factKey"]?.jsonPrimitive?.content,o["factReviewSha256"]?.jsonPrimitive?.content,checks,
            schemaVersion=o.getValue("schemaVersion").jsonPrimitive.int)
    }

    private fun validUtc(value: String): Boolean {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,3})?Z").matches(value)) return false
        val normalized = if ('.' in value) value.substringBefore('.') + "." + value.substringAfter('.').removeSuffix("Z").padEnd(3, '0') + "Z" else value
        val formatter = SimpleDateFormat(if ('.' in normalized) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        formatter.isLenient = false
        val position = ParsePosition(0)
        return formatter.parse(normalized, position) != null && position.index == normalized.length
    }
}
