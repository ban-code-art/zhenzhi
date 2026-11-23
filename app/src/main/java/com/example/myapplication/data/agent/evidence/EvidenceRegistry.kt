package com.example.myapplication.data.agent.evidence

import com.example.myapplication.data.encyclopedia.EncyclopediaEvidenceVerifier
import com.example.myapplication.data.encyclopedia.EncyclopediaFailureReasonKind
import com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot
import com.example.myapplication.data.encyclopedia.EncyclopediaSourceLocator
import com.example.myapplication.data.encyclopedia.EncyclopediaVerificationResult
import com.example.myapplication.domain.agent.AgentHashing
import com.example.myapplication.domain.agent.ToolErrorCode
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.SourceEvidence
import java.util.Locale

/**
 * 回答级证据注册表（详细设计 §4/§13，G-03 / FX-14 / FX-31）。
 *
 * 规则：
 *  - 只有通过 [EvidenceVerifier] 固定 revision 原文核验的证据才能注册；
 *  - 编号形如 `[S1]`，同一来源区间（document/revision/locator 区间 + quote）**复用**同一编号；
 *  - 本轮新来源单调递增（S1、S2…），同一 registry（一次回答/会话）内编号全局唯一，
 *    不会把同一 S 编号分配给不同来源；
 *  - 重复注册同区间但换了 progression 等展示字段仍然复用（身份只看内容区间与 quote）；
 *  - 注册同时写持久 [EvidenceReferencePort]（FX-31），内存 registry 关闭后
 *    仍可按 referenceId 由本用户查回 quote，别的用户拒绝。
 */
class EvidenceRegistry(
    private val verifier: EvidenceVerifier,
    private val references: EvidenceReferencePort,
    private val encyclopediaSnapshot: (suspend () -> EncyclopediaSnapshot?)? = null,
) {
    data class RegisteredSource(
        val citation: String,
        val ordinal: Int,
        val referenceId: String,
        /** 已核验的固定 revision 书库证据（百科词条引用时为 null）。 */
        val evidence: SourceEvidence?,
        val quoteSha256: String,
        /** 首次注册时的轮次；复用时首次轮次保留。 */
        val originTurnId: String,
        val rawDocumentId: String = evidence?.documentId.orEmpty(),
        val rawSectionId: String = evidence?.sectionId.orEmpty(),
        val rawContentRevision: String = evidence?.contentRevision.orEmpty(),
        val rawQuote: String = evidence?.quote.orEmpty(),
        val rawSourceTitle: String = evidence?.sourceTitleSnapshot.orEmpty(),
        val rawLocatorJson: String? = null,
    ) {
        val documentId: String get() = evidence?.documentId ?: rawDocumentId
        val sectionId: String get() = evidence?.sectionId ?: rawSectionId
        val contentRevision: String get() = evidence?.contentRevision ?: rawContentRevision
        val quote: String get() = evidence?.quote ?: rawQuote
        val sourceTitle: String get() = evidence?.sourceTitleSnapshot ?: rawSourceTitle
    }

    sealed interface RegisterOutcome {
        data class Accepted(val source: RegisteredSource) : RegisterOutcome
        data class Rejected(val code: ToolErrorCode, val reason: String) : RegisterOutcome
    }

    data class CitationCheck(
        val cited: List<String>,
        val unknown: List<String>,
    ) {
        val isValid: Boolean get() = unknown.isEmpty()
    }

    private val byIdentity = LinkedHashMap<String, RegisteredSource>()
    private val byCitation = LinkedHashMap<String, RegisteredSource>()
    private val byTurn = LinkedHashMap<String, MutableList<String>>()
    private val byReference = LinkedHashMap<String, RegisteredSource>()
    private var nextOrdinal = 1

    suspend fun register(
        context: AccessContext,
        turnId: String,
        evidence: SourceEvidence,
    ): RegisterOutcome {
        if (turnId.isBlank() || turnId.length > MAX_TURN_ID_CHARS) {
            return RegisterOutcome.Rejected(ToolErrorCode.INVALID_ARGUMENT, "turnId is invalid")
        }
        if (evidence.quote.isBlank()) {
            return RegisterOutcome.Rejected(ToolErrorCode.EVIDENCE_UNVERIFIED, "evidence quote is blank")
        }
        when (val verified = verifier.verify(context, evidence)) {
            is VerificationOutcome.Rejected -> return RegisterOutcome.Rejected(
                verified.code.toToolErrorCode(),
                verified.reason,
            )

            is VerificationOutcome.Verified -> {
                val confirmed = verified.evidence
                val identity = identityOf(confirmed)
                byIdentity[identity]?.let { existing ->
                    rememberInTurn(turnId, existing.citation)
                    return RegisterOutcome.Accepted(existing)
                }
                val persisted = when (val registered = references.register(context, confirmed)) {
                    is OperationResult.Ok -> registered.value
                    is OperationResult.Rejected -> return RegisterOutcome.Rejected(
                        registered.code.toToolErrorCode(),
                        "persistent reference rejected: ${registered.code}",
                    )

                    is OperationResult.Failed -> return RegisterOutcome.Rejected(
                        ToolErrorCode.EVIDENCE_UNVERIFIED,
                        "persistent reference failed: ${registered.code}",
                    )

                    is OperationResult.Conflict -> return RegisterOutcome.Rejected(
                        ToolErrorCode.EVIDENCE_UNVERIFIED,
                        "persistent reference conflict",
                    )
                }
                val ordinal = nextOrdinal++
                val source = RegisteredSource(
                    citation = "S$ordinal",
                    ordinal = ordinal,
                    referenceId = persisted.id,
                    evidence = confirmed,
                    quoteSha256 = AgentHashing.sha256(confirmed.quote),
                    originTurnId = turnId,
                )
                byIdentity[identity] = source
                byCitation[source.citation] = source
                byReference[source.referenceId] = source
                rememberInTurn(turnId, source.citation)
                return RegisterOutcome.Accepted(source)
            }
        }
    }

    suspend fun registerEncyclopedia(
        context: AccessContext,
        turnId: String,
        locator: EncyclopediaSourceLocator,
        title: String,
        quote: String,
    ): RegisterOutcome {
        if (turnId.isBlank() || turnId.length > MAX_TURN_ID_CHARS) {
            return RegisterOutcome.Rejected(ToolErrorCode.INVALID_ARGUMENT, "turnId is invalid")
        }
        val snapshot = encyclopediaSnapshot?.invoke()
        val verified = when (val checked = EncyclopediaEvidenceVerifier.verify(snapshot, locator, locator.contentRevision, quote)) {
            is EncyclopediaVerificationResult.Verified -> checked.extract
            is EncyclopediaVerificationResult.Rejected -> {
                val code = when (checked.failureKind) {
                    EncyclopediaFailureReasonKind.REVISION_MISSING -> ToolErrorCode.REVISION_MISSING
                    EncyclopediaFailureReasonKind.NOT_FOUND -> ToolErrorCode.NOT_FOUND
                    EncyclopediaFailureReasonKind.EVIDENCE_UNVERIFIED -> ToolErrorCode.EVIDENCE_UNVERIFIED
                }
                return RegisterOutcome.Rejected(code, checked.reason)
            }
        }
        val encIdentity = AgentHashing.sha256(
            listOf(
                "ENCYCLOPEDIA",
                verified.locator.kind.wire,
                verified.locator.entryId,
                verified.locator.sectionId,
                verified.locator.contentRevision,
                verified.locator.openClues.joinToString(","),
                AgentHashing.sha256(quote),
            ).joinToString("\u0000"),
        )
        byIdentity[encIdentity]?.let { existing ->
            rememberInTurn(turnId, existing.citation)
            return RegisterOutcome.Accepted(existing)
        }
        val persisted = when (val registered = references.registerEncyclopedia(context, verified.locator, title.ifBlank { verified.title }, quote)) {
            is OperationResult.Ok -> registered.value
            is OperationResult.Rejected -> return RegisterOutcome.Rejected(
                registered.code.toToolErrorCode(),
                "persistent encyclopedia reference rejected: ${registered.code}",
            )
            is OperationResult.Failed -> return RegisterOutcome.Rejected(
                ToolErrorCode.EVIDENCE_UNVERIFIED,
                "persistent encyclopedia reference failed: ${registered.code}",
            )
            is OperationResult.Conflict -> return RegisterOutcome.Rejected(
                ToolErrorCode.EVIDENCE_UNVERIFIED,
                "persistent encyclopedia reference conflict",
            )
        }
        val ordinal = nextOrdinal++
        val encodedLocator = EncyclopediaSourceLocator.encode(verified.locator)
        val source = RegisteredSource(
            citation = "S$ordinal",
            ordinal = ordinal,
            referenceId = persisted.id,
            evidence = null,
            quoteSha256 = AgentHashing.sha256(quote),
            originTurnId = turnId,
            rawDocumentId = verified.locator.namespaceDocumentKey,
            rawSectionId = verified.locator.sectionId,
            rawContentRevision = verified.locator.contentRevision,
            rawQuote = quote,
            rawSourceTitle = title.ifBlank { verified.title },
            rawLocatorJson = encodedLocator,
        )
        byIdentity[encIdentity] = source
        byCitation[source.citation] = source
        byReference[source.referenceId] = source
        rememberInTurn(turnId, source.citation)
        return RegisterOutcome.Accepted(source)
    }

    /** 本轮（turn）已注册的来源，按首次/复用出现顺序去重。 */
    fun sourcesForTurn(turnId: String): List<RegisteredSource> =
        byTurn[turnId].orEmpty().mapNotNull(byCitation::get)

    fun resolve(citation: String): RegisteredSource? = byCitation[citation]

    fun resolveReference(referenceId: String): RegisteredSource? = byReference[referenceId]

    fun allSources(): List<RegisteredSource> = byCitation.values.toList()

    fun citationForReference(referenceId: String): String? = byReference[referenceId]?.citation

    /** 回答级引用校验：`[S99]` 不在 registry 即视为伪造引用（G-03）。 */
    fun validateCitations(answer: String): CitationCheck {
        val cited = CITATION_PATTERN.findAll(answer)
            .map { it.groupValues[1] }
            .map { "S$it" }
            .distinct()
            .toList()
        val unknown = cited.filterNot { byCitation.containsKey(it) }
        return CitationCheck(cited = cited, unknown = unknown)
    }

    private fun rememberInTurn(turnId: String, citation: String) {
        val citations = byTurn.getOrPut(turnId) { mutableListOf() }
        if (citation !in citations) citations += citation
    }

    /** 证据身份：只包含内容区间与 quote hash，不包含 progression/展示字段。 */
    private fun identityOf(evidence: SourceEvidence): String {
        val locator = evidence.locator
        val locatorIdentity = when (locator) {
            is ReaderLocator.Reflow -> listOf(
                "REFLOW",
                locator.sectionId,
                locator.blockId,
                locator.canonicalStart.toString(),
                locator.canonicalEnd.toString(),
            )

            is ReaderLocator.Fixed -> listOf(
                "FIXED",
                locator.sectionId,
                locator.pageIndex.toString(),
                locator.rotationDegrees.toString(),
                locator.rects.joinToString(";") {
                    listOf(it.left, it.top, it.right, it.bottom).joinToString(",") { value ->
                        String.format(Locale.ROOT, "%.6f", value)
                    }
                },
            )

            is ReaderLocator.Note -> listOf(
                "NOTE",
                locator.sectionId,
                locator.blockId.orEmpty(),
                locator.canonicalStart?.toString().orEmpty(),
                locator.canonicalEnd?.toString().orEmpty(),
                locator.notebookId,
                locator.noteRevision.toString(),
            )
        }
        return AgentHashing.sha256(
            listOf(
                evidence.documentId,
                evidence.contentRevision,
                locatorIdentity.joinToString("|"),
                AgentHashing.sha256(evidence.quote),
            ).joinToString("\u0000"),
        )
    }

    companion object {
        const val MAX_TURN_ID_CHARS = 128
        private val CITATION_PATTERN = Regex("\\[[Ss](\\d{1,4})\\]")
    }
}

private fun String.toToolErrorCode(): ToolErrorCode = when (this) {
    "ACCESS_REVOKED" -> ToolErrorCode.ACCESS_REVOKED
    "NOT_FOUND" -> ToolErrorCode.NOT_FOUND
    "REVISION_MISSING" -> ToolErrorCode.REVISION_MISSING
    else -> ToolErrorCode.EVIDENCE_UNVERIFIED
}

internal fun EvidenceFailureCode.toToolErrorCode(): ToolErrorCode = when (this) {
    EvidenceFailureCode.ACCESS_REVOKED -> ToolErrorCode.ACCESS_REVOKED
    EvidenceFailureCode.NOT_FOUND -> ToolErrorCode.NOT_FOUND
    EvidenceFailureCode.REVISION_MISSING -> ToolErrorCode.REVISION_MISSING
    EvidenceFailureCode.EVIDENCE_UNVERIFIED -> ToolErrorCode.EVIDENCE_UNVERIFIED
}
