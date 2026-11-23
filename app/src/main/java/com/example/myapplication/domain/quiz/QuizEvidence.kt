package com.example.myapplication.domain.quiz

import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.SourceEvidence
import java.security.MessageDigest

/**
 * 绑定后的题目出处（quiz_items.evidence_json 的元素）。
 *
 * 只由应用侧 [SourceEvidence] 生成：Locator/quote/revision 都来自固定 revision 的
 * 原文核验结果，模型提供的 chunkId/书名/坐标一律不进入本结构（设计 §7/§14）。
 */
data class QuizEvidenceRef(
    val evidenceId: String,
    val documentId: String,
    val sectionId: String,
    val contentRevision: String,
    val locator: ReaderLocator,
    val quote: String,
    val quoteSha256: String,
    val sourceTitleSnapshot: String = "",
) {
    fun matchesPinnedRevision(pinnedSourceRevision: String?): Boolean =
        pinnedSourceRevision == null || pinnedSourceRevision == contentRevision
}

object QuizEvidence {
    /** evidenceId 的合法形态：应用序号 S1、S2……；chunkId/书名等模型自填值直接拒绝。 */
    val ID_PATTERN: Regex = Regex("^S[1-9][0-9]{0,3}$")

    fun isValidEvidenceId(raw: String?): Boolean = raw != null && ID_PATTERN.matches(raw.trim())

    fun quoteSha256(quote: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(quote.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** 从原文核验结果构造绑定出处（应用侧唯一合法来源）。 */
    fun fromSource(evidence: SourceEvidence): QuizEvidenceRef = QuizEvidenceRef(
        evidenceId = evidence.evidenceId,
        documentId = evidence.documentId,
        sectionId = evidence.sectionId,
        contentRevision = evidence.contentRevision,
        locator = evidence.locator,
        quote = evidence.quote,
        quoteSha256 = evidence.quoteSha256,
        sourceTitleSnapshot = evidence.sourceTitleSnapshot,
    )

    /** 出处自身完整性：quote 与 sha256 必须一致（改 1 字符而未改 hash 必须失败）。 */
    fun isSelfConsistent(evidence: QuizEvidenceRef): Boolean =
        evidence.quote.isNotBlank() && evidence.quoteSha256 == quoteSha256(evidence.quote)
}
