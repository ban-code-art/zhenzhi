package com.example.myapplication.data.agent.evidence

import com.example.myapplication.domain.agent.AgentHashing
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.DocumentAccess
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.SourceEvidence

/** 证据校验失败的稳定分类（对应 ToolErrorCode / SourceNavigator 状态）。 */
enum class EvidenceFailureCode {
    ACCESS_REVOKED,
    NOT_FOUND,
    REVISION_MISSING,
    EVIDENCE_UNVERIFIED,
}

sealed interface VerificationOutcome {
    data class Verified(val evidence: SourceEvidence) : VerificationOutcome

    data class Rejected(
        val code: EvidenceFailureCode,
        val reason: String,
        val retryable: Boolean = false,
        /** 历史 quote 快照：仅在本用户自己的来源上保留（别用户不返回 quote）。 */
        val preservedQuote: String? = null,
    ) : VerificationOutcome
}

/**
 * 证据校验端口（FX-14）：任何进入 [EvidenceRegistry] 或来源卡片的证据都必须
 * 经固定 revision 的 [DocumentAccess] 原文逐字核验，不能只重读 chunk 表。
 */
interface EvidenceVerifier {
    suspend fun verify(context: AccessContext, evidence: SourceEvidence): VerificationOutcome

    suspend fun verifyLocator(
        context: AccessContext,
        documentId: String,
        contentRevision: String,
        locator: ReaderLocator,
        expectedQuote: String,
    ): VerificationOutcome
}

/**
 * 生产实现：固定 (documentId, revision) 打开 snapshot → readRange 读原文 →
 * 逐字比对 quote 与 sha256。撤权/改版/删除分别返回稳定错误，不伪造“可点击出处”。
 */
class SourceVerifier(
    private val documentAccess: DocumentAccess,
) : EvidenceVerifier {

    override suspend fun verify(context: AccessContext, evidence: SourceEvidence): VerificationOutcome =
        verifyLocator(
            context = context,
            documentId = evidence.documentId,
            contentRevision = evidence.contentRevision,
            locator = evidence.locator,
            expectedQuote = evidence.quote,
        )

    override suspend fun verifyLocator(
        context: AccessContext,
        documentId: String,
        contentRevision: String,
        locator: ReaderLocator,
        expectedQuote: String,
    ): VerificationOutcome {
        if (expectedQuote.isBlank()) {
            return VerificationOutcome.Rejected(EvidenceFailureCode.EVIDENCE_UNVERIFIED, "evidence quote is blank")
        }
        if (expectedQuote.codePointCount(0, expectedQuote.length) > MAX_QUOTE_CODE_POINTS) {
            return VerificationOutcome.Rejected(EvidenceFailureCode.EVIDENCE_UNVERIFIED, "evidence quote is too long")
        }
        if (locator.documentId != documentId || locator.contentRevision != contentRevision) {
            return VerificationOutcome.Rejected(
                EvidenceFailureCode.EVIDENCE_UNVERIFIED,
                "locator does not belong to the requested document/revision",
                preservedQuote = expectedQuote,
            )
        }
        // locator 自带的 quote 必须与待核验 quote 逐字一致：否则 readRange 可能按 locator
        // 返回另一段文本（甚至被 maxTokens 截断后恰好相等），造成“假核验通过”。
        val locatorQuote = when (locator) {
            is ReaderLocator.Reflow -> locator.exactQuote
            is ReaderLocator.Fixed -> locator.textQuote
            is ReaderLocator.Note -> null
        }
        if (locatorQuote != null && locatorQuote != expectedQuote) {
            return VerificationOutcome.Rejected(
                EvidenceFailureCode.EVIDENCE_UNVERIFIED,
                "locator quote does not match the evidence quote",
                preservedQuote = expectedQuote,
            )
        }
        return when (val opened = documentAccess.open(context, documentId, contentRevision)) {
            is OperationResult.Ok -> {
                val snapshot = opened.value
                if (snapshot.documentId != documentId || snapshot.contentRevision != contentRevision) {
                    return VerificationOutcome.Rejected(
                        EvidenceFailureCode.REVISION_MISSING,
                        "opened snapshot does not match the pinned revision",
                        preservedQuote = expectedQuote,
                    )
                }
                val maxTokens = expectedQuote.length.coerceIn(16, MAX_QUOTE_CODE_POINTS)
                when (val read = documentAccess.readRange(context, snapshot.snapshotId, locator, maxTokens)) {
                    is OperationResult.Ok -> {
                        val evidence = read.value
                        val hash = AgentHashing.sha256(expectedQuote)
                        val verified = evidence.documentId == documentId &&
                            evidence.contentRevision == contentRevision &&
                            evidence.quote == expectedQuote &&
                            evidence.quoteSha256 == hash
                        if (verified) {
                            VerificationOutcome.Verified(evidence)
                        } else {
                            VerificationOutcome.Rejected(
                                EvidenceFailureCode.EVIDENCE_UNVERIFIED,
                                "quote does not match the immutable revision",
                                preservedQuote = expectedQuote,
                            )
                        }
                    }

                    is OperationResult.Rejected -> when (read.code) {
                        "ACCESS_REVOKED" -> VerificationOutcome.Rejected(
                            EvidenceFailureCode.ACCESS_REVOKED,
                            "access revoked",
                            preservedQuote = expectedQuote,
                        )

                        "REVISION_MISSING" -> VerificationOutcome.Rejected(
                            EvidenceFailureCode.REVISION_MISSING,
                            "revision no longer available",
                            preservedQuote = expectedQuote,
                        )

                        "NOT_FOUND" -> VerificationOutcome.Rejected(
                            EvidenceFailureCode.NOT_FOUND,
                            "source not found",
                            preservedQuote = expectedQuote,
                        )

                        else -> VerificationOutcome.Rejected(
                            EvidenceFailureCode.EVIDENCE_UNVERIFIED,
                            "quote/locator rejected: ${read.code}",
                            preservedQuote = expectedQuote,
                        )
                    }

                    is OperationResult.Failed -> VerificationOutcome.Rejected(
                        EvidenceFailureCode.EVIDENCE_UNVERIFIED,
                        "source read failed: ${read.code}",
                        retryable = read.retryable,
                        preservedQuote = expectedQuote,
                    )

                    is OperationResult.Conflict -> VerificationOutcome.Rejected(
                        EvidenceFailureCode.EVIDENCE_UNVERIFIED,
                        "source conflict",
                        retryable = true,
                        preservedQuote = expectedQuote,
                    )
                }
            }

            is OperationResult.Rejected -> when (opened.code) {
                "ACCESS_REVOKED" -> VerificationOutcome.Rejected(
                    EvidenceFailureCode.ACCESS_REVOKED,
                    "access revoked",
                    preservedQuote = expectedQuote,
                )

                "NOT_FOUND" -> VerificationOutcome.Rejected(
                    EvidenceFailureCode.NOT_FOUND,
                    "source not found",
                    preservedQuote = expectedQuote,
                )

                "REVISION_MISSING" -> VerificationOutcome.Rejected(
                    EvidenceFailureCode.REVISION_MISSING,
                    "revision no longer available",
                    preservedQuote = expectedQuote,
                )

                else -> VerificationOutcome.Rejected(
                    EvidenceFailureCode.EVIDENCE_UNVERIFIED,
                    "open rejected: ${opened.code}",
                    preservedQuote = expectedQuote,
                )
            }

            is OperationResult.Failed -> VerificationOutcome.Rejected(
                EvidenceFailureCode.EVIDENCE_UNVERIFIED,
                "source open failed: ${opened.code}",
                retryable = opened.retryable,
                preservedQuote = expectedQuote,
            )

            is OperationResult.Conflict -> VerificationOutcome.Rejected(
                EvidenceFailureCode.EVIDENCE_UNVERIFIED,
                "source conflict",
                retryable = true,
                preservedQuote = expectedQuote,
            )
        }
    }

    companion object {
        const val MAX_QUOTE_CODE_POINTS = 8_192
    }
}
