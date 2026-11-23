package com.example.myapplication.data.agent.notes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * W4-05：笔记修改提议的领域模型（纯 Kotlin，便于 JVM 单测）。
 *
 * 契约来源：第四期详细设计 §6（笔记修改提议与确认）、§12（WAITING_CONFIRMATION 与工具幂等）、
 * §13（propose_note_patch 输入校验：≤20 段 / 32KiB 新增文本 / old 须唯一命中）、
 * §20（read_receipt_json 由应用记录、确认用当前 ctx 再授权）、04-接口模型 §5。
 *
 * 只负责数据结构与序列化；校验在 [NotePatchValidator]，事务与持久化在 [NoteProposalService]。
 */
enum class NoteProposalTargetKind(val wire: String) {
    NOTEBOOK("NOTEBOOK"),
    ANNOTATION_NOTE("ANNOTATION_NOTE"),
    ;

    companion object {
        fun fromWire(value: String?): NoteProposalTargetKind? =
            values().firstOrNull { it.wire == value }
    }
}

/** 单段精确替换：old 必须唯一命中，仅支持 UNIQUE 策略。 */
data class NotePatchEdit(
    val oldText: String,
    val newText: String,
    val occurrencePolicy: String = OCCURRENCE_UNIQUE,
) {
    companion object {
        const val OCCURRENCE_UNIQUE = "UNIQUE"
    }
}

/** 提议目标的当前快照：由服务在当前 AccessContext 下读取，不接受模型传入正文。 */
data class NoteProposalTarget(
    val kind: NoteProposalTargetKind,
    val targetId: String,
    val documentId: String,
    val revision: Long,
    val body: String,
    /** 只读源数据：高亮 quote 等，任何 edit 不得触碰。 */
    val protectedTexts: List<String> = emptyList(),
)

/**
 * read_note 回执：应用从工具结果登记，模型不能自行编造。
 * 绑定 run / 目标 / revision / 正文哈希 / 证据事件序号。
 */
data class NoteReadReceipt(
    val receiptId: String,
    val runId: String,
    val targetKind: NoteProposalTargetKind,
    val targetId: String,
    val revision: Long,
    val contentHash: String,
    val eventSeq: Long,
    val issuedAt: Long,
) {
    fun toJson(): String = buildJsonObject {
        put("receiptId", receiptId)
        put("runId", runId)
        put("targetKind", targetKind.wire)
        put("targetId", targetId)
        put("revision", revision)
        put("contentHash", contentHash)
        put("eventSeq", eventSeq)
        put("issuedAt", issuedAt)
    }.toString()

    companion object {
        fun parse(json: String?): NoteReadReceipt? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val root = Json.parseToJsonElement(json).jsonObject
                NoteReadReceipt(
                    receiptId = root.string("receiptId") ?: return null,
                    runId = root.string("runId") ?: return null,
                    targetKind = NoteProposalTargetKind.fromWire(root.string("targetKind")) ?: return null,
                    targetId = root.string("targetId") ?: return null,
                    revision = root.long("revision") ?: return null,
                    contentHash = root.string("contentHash") ?: return null,
                    eventSeq = root.long("eventSeq") ?: return null,
                    issuedAt = root.long("issuedAt") ?: return null,
                )
            }.getOrNull()
        }
    }
}

object NoteProposalStatus {
    const val PENDING = "PENDING"
    const val APPROVED = "APPROVED"
    const val REJECTED = "REJECTED"
    const val STALE = "STALE"
}

/** 持久化提议的可读模型（不含 Room 类型）。 */
data class NoteProposalRecord(
    val id: String,
    val runId: String,
    val userId: Long,
    val targetKind: NoteProposalTargetKind,
    val targetId: String,
    val documentId: String,
    val baseRevision: Long,
    val readReceiptId: String,
    val readReceipt: NoteReadReceipt?,
    val edits: List<NotePatchEdit>,
    val reason: String,
    val previewHash: String,
    val status: String,
) {
    val isPending: Boolean get() = status == NoteProposalStatus.PENDING
}

sealed interface NotePatchValidation {
    data class Valid(val newBody: String, val previewHash: String) : NotePatchValidation
    data class Invalid(val code: String) : NotePatchValidation
}

sealed interface ProposalCreateOutcome {
    data class Created(val proposal: NoteProposalRecord) : ProposalCreateOutcome
    data class Rejected(val code: String) : ProposalCreateOutcome
}

enum class NoteProposalStaleReason {
    /** 目标 revision 已变（用户或其它写入者先保存）。 */
    REVISION_CHANGED,

    /** 存在用户本地草稿，不能丢弃；提议转 STALE，草稿保留。 */
    DIRTY_DRAFT,

    /** 持久化 receipt 与提议字段/目标当前状态不符。 */
    RECEIPT_MISMATCH,

    /** 持久化 payload 与 previewHash 不符（被篡改或损坏）。 */
    PAYLOAD_MISMATCH,
}

sealed interface ProposalApplyOutcome {
    data class Applied(
        val proposalId: String,
        val targetKind: NoteProposalTargetKind,
        val targetId: String,
        val revision: Long,
        val bodyHash: String,
    ) : ProposalApplyOutcome

    data class Stale(
        val proposalId: String,
        val reason: NoteProposalStaleReason,
        val currentRevision: Long,
        val draftBody: String? = null,
    ) : ProposalApplyOutcome

    data class Rejected(val code: String) : ProposalApplyOutcome

    data class Failed(val code: String, val retryable: Boolean) : ProposalApplyOutcome
}

sealed interface ProposalRejectOutcome {
    data class Rejected(val proposalId: String) : ProposalRejectOutcome
    data class AlreadyResolved(val status: String) : ProposalRejectOutcome
    data object NotFound : ProposalRejectOutcome
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.array(key: String): JsonArray? =
    this[key] as? JsonArray
