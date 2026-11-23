package com.example.myapplication.data.agent.evidence

import com.example.myapplication.data.encyclopedia.EncyclopediaEvidenceVerifier
import com.example.myapplication.data.encyclopedia.EncyclopediaFailureReasonKind
import com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot
import com.example.myapplication.data.encyclopedia.EncyclopediaSourceLocator
import com.example.myapplication.data.encyclopedia.EncyclopediaVerificationResult
import com.example.myapplication.data.reader.notes.AnnotationRepository
import com.example.myapplication.data.reader.notes.SourceReferenceStore
import com.example.myapplication.data.reader.notes.SourceReferenceView
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.SourceEvidence

/**
 * 持久来源引用端口（接口明细 §15 / FX-31）：
 * 只接受应用侧已核验的 [SourceEvidence] 注册；lookup 按当前 userId 读持久行。
 * 生产实现 [PersistentSourceReferencePort] 包住二期的 `SourceReferenceStore`。
 */
interface EvidenceReferencePort {
    suspend fun register(context: AccessContext, evidence: SourceEvidence): OperationResult<SourceReferenceView>

    suspend fun registerEncyclopedia(
        context: AccessContext,
        locator: EncyclopediaSourceLocator,
        title: String,
        quote: String,
    ): OperationResult<SourceReferenceView> = OperationResult.Failed("ENCYCLOPEDIA_UNSUPPORTED", retryable = false)

    suspend fun lookup(context: AccessContext, referenceId: String): OperationResult<SourceReferenceView>
}

class PersistentSourceReferencePort(
    private val store: SourceReferenceStore,
) : EvidenceReferencePort {
    override suspend fun register(
        context: AccessContext,
        evidence: SourceEvidence,
    ): OperationResult<SourceReferenceView> = store.register(context, evidence)

    override suspend fun registerEncyclopedia(
        context: AccessContext,
        locator: EncyclopediaSourceLocator,
        title: String,
        quote: String,
    ): OperationResult<SourceReferenceView> = store.registerEncyclopedia(context, locator, title, quote)

    override suspend fun lookup(
        context: AccessContext,
        referenceId: String,
    ): OperationResult<SourceReferenceView> = store.lookup(context, referenceId)
}

/**
 * 来源导航（详细设计 §5/§9，FX-31）：消息来源卡片/[S#] 的长期跳转入口。
 *
 * - 本用户：内存 registry 关闭后仍能按 referenceId 查回持久 quote，并重新核验原文；
 * - 别用户/不存在：`ACCESS_REVOKED`，**不返回 quote**（不泄露他人历史）；
 * - 源被删除：`REVOKED`/`MISSING`，但保留本用户已保存 quote（历史卡片可显示）；
 * - 源被改版：由历史阅读专用 verifier 核验旧 revision；旧版本缺失时返回 `MISSING`。
 */
class SourceNavigator(
    private val references: EvidenceReferencePort,
    private val verifier: EvidenceVerifier,
    private val encyclopediaSnapshot: (suspend () -> EncyclopediaSnapshot?)? = null,
) {
    sealed interface Outcome {
        data class Verified(
            val referenceId: String,
            val documentId: String,
            val sectionId: String,
            val contentRevision: String,
            val quote: String,
            val locator: ReaderLocator,
            val title: String,
        ) : Outcome

        data class EncyclopediaVerified(
            val referenceId: String,
            val locator: EncyclopediaSourceLocator,
            val quote: String,
            val title: String,
        ) : Outcome

        /** 撤权/别用户：quote 只为本人保留；他人一律 null。 */
        data class Revoked(
            val referenceId: String,
            val code: EvidenceFailureCode,
            val reason: String,
            val quote: String?,
        ) : Outcome

        /** 来源不可用（删除/改版/无法核验）：保留本人 quote 与旧 locator。 */
        data class Missing(
            val referenceId: String,
            val code: EvidenceFailureCode,
            val reason: String,
            val quote: String?,
            val locator: ReaderLocator?,
        ) : Outcome
    }

    suspend fun open(context: AccessContext, referenceId: String): Outcome {
        if (referenceId.isBlank() || referenceId.length > MAX_REFERENCE_ID_CHARS) {
            return Outcome.Missing(
                referenceId = referenceId,
                code = EvidenceFailureCode.NOT_FOUND,
                reason = "invalid reference id",
                quote = null,
                locator = null,
            )
        }
        val lookedUp = references.lookup(context, referenceId)
        val view = when (lookedUp) {
            is OperationResult.Ok -> lookedUp.value
            is OperationResult.Rejected -> return Outcome.Revoked(
                referenceId = referenceId,
                code = EvidenceFailureCode.ACCESS_REVOKED,
                reason = "reference not accessible: ${lookedUp.code}",
                quote = null,
            )

            is OperationResult.Failed -> return Outcome.Missing(
                referenceId = referenceId,
                code = EvidenceFailureCode.EVIDENCE_UNVERIFIED,
                reason = "reference lookup failed: ${lookedUp.code}",
                quote = null,
                locator = null,
            )

            is OperationResult.Conflict -> return Outcome.Missing(
                referenceId = referenceId,
                code = EvidenceFailureCode.EVIDENCE_UNVERIFIED,
                reason = "reference lookup conflict",
                quote = null,
                locator = null,
            )
        }
        if (view.evidenceKind == AnnotationRepository.EVIDENCE_KIND_ENCYCLOPEDIA) {
            val encLocator = EncyclopediaSourceLocator.decode(view.rawLocatorJson)
                ?: return Outcome.Missing(
                    referenceId = referenceId,
                    code = EvidenceFailureCode.EVIDENCE_UNVERIFIED,
                    reason = "stored encyclopedia locator is invalid",
                    quote = view.quote,
                    locator = null,
                )
            val snapshot = encyclopediaSnapshot?.invoke()
            return when (val checked = EncyclopediaEvidenceVerifier.verify(snapshot, encLocator, view.contentRevision, view.quote)) {
                is EncyclopediaVerificationResult.Verified -> Outcome.EncyclopediaVerified(
                    referenceId = referenceId,
                    locator = checked.extract.locator,
                    quote = view.quote,
                    title = checked.extract.title.ifBlank { view.sourceTitle },
                )
                is EncyclopediaVerificationResult.Rejected -> Outcome.Missing(
                    referenceId = referenceId,
                    code = when (checked.failureKind) {
                        EncyclopediaFailureReasonKind.REVISION_MISSING -> EvidenceFailureCode.REVISION_MISSING
                        EncyclopediaFailureReasonKind.NOT_FOUND -> EvidenceFailureCode.NOT_FOUND
                        EncyclopediaFailureReasonKind.EVIDENCE_UNVERIFIED -> EvidenceFailureCode.EVIDENCE_UNVERIFIED
                    },
                    reason = checked.reason,
                    quote = view.quote,
                    locator = null,
                )
            }
        }
        // 源文档被永久删除时 FK SET NULL：仍保留 quote 快照，但不能再跳原文。
        if (view.documentId == null) {
            return Outcome.Missing(
                referenceId = referenceId,
                code = EvidenceFailureCode.NOT_FOUND,
                reason = "source document no longer exists",
                quote = view.quote,
                locator = view.locator,
            )
        }
        val locator = view.locator
        if (locator == null) {
            return Outcome.Missing(
                referenceId = referenceId,
                code = EvidenceFailureCode.EVIDENCE_UNVERIFIED,
                reason = "stored locator is invalid",
                quote = view.quote,
                locator = null,
            )
        }
        return when (
            val verified = verifier.verifyLocator(
                context = context,
                documentId = view.documentId,
                contentRevision = view.contentRevision,
                locator = locator,
                expectedQuote = view.quote,
            )
        ) {
            is VerificationOutcome.Verified -> Outcome.Verified(
                referenceId = referenceId,
                documentId = view.documentId,
                sectionId = verified.evidence.sectionId,
                contentRevision = verified.evidence.contentRevision,
                quote = verified.evidence.quote,
                locator = verified.evidence.locator,
                title = verified.evidence.sourceTitleSnapshot.ifBlank { view.sourceTitle },
            )

            is VerificationOutcome.Rejected -> when (verified.code) {
                EvidenceFailureCode.REVISION_MISSING -> Outcome.Missing(
                    referenceId = referenceId,
                    code = verified.code,
                    reason = verified.reason,
                    quote = view.quote,
                    locator = locator,
                )

                EvidenceFailureCode.ACCESS_REVOKED,
                EvidenceFailureCode.NOT_FOUND,
                -> Outcome.Revoked(
                    referenceId = referenceId,
                    code = verified.code,
                    reason = verified.reason,
                    quote = view.quote,
                )

                EvidenceFailureCode.EVIDENCE_UNVERIFIED -> Outcome.Missing(
                    referenceId = referenceId,
                    code = verified.code,
                    reason = verified.reason,
                    quote = view.quote,
                    locator = locator,
                )
            }
        }
    }

    /** 应用内深链（`aculearn://source/<referenceId>`，接口明细 §15）。 */
    fun deepLink(referenceId: String): String = "$DEEP_LINK_PREFIX$referenceId"

    fun referenceIdOf(deepLink: String): String? =
        deepLink.removePrefix(DEEP_LINK_PREFIX).takeIf { it != deepLink && it.isNotBlank() }

    companion object {
        const val DEEP_LINK_PREFIX = "aculearn://source/"
        const val MAX_REFERENCE_ID_CHARS = 256
    }
}
