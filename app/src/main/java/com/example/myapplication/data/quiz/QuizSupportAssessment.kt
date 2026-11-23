package com.example.myapplication.data.quiz

enum class QuizSupportState { SUPPORTED, UNSUPPORTED, UNCERTAIN }
data class QuizAssertionCheck(val assertionKey: String, val role: String, val claimText: String,
    val evidenceHashes: List<String>, val judgement: String, val reason: String)
data class QuizSupportAssessment(val itemDraftHash: String, val sourceRevision: String,
    val state: QuizSupportState, val reasonCodes: List<String>, val authority: String,
    val evidenceHashes: List<String>, val factKey: String? = null, val factReviewSha256: String? = null,
    val assertionChecks: List<QuizAssertionCheck> = emptyList(), val schemaVersion: Int = 1,
    val approval: QuizPersonalApproval? = null)
data class QuizAuditedFact(val key: String, val kind: String, val prompt: String,
    val answerParts: List<String>, val distractors: List<String>, val analysis: String,
    val knowledgePoint: String, val sourceRevision: String, val evidenceHashes: List<String>,
    val reviewKey: String, val reviewSha256: String, val approved: Boolean = false,
    val sourceDocumentId: String = "", val sourceSectionIds: Set<String> = emptySet())
