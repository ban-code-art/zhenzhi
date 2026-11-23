package com.example.myapplication.data.agent.notes

import com.example.myapplication.data.reader.notes.ReaderMutationHasher
import com.example.myapplication.domain.agent.AgentTool
import com.example.myapplication.domain.agent.ToolArgumentException
import com.example.myapplication.domain.agent.ToolArgs
import com.example.myapplication.domain.agent.ToolError
import com.example.myapplication.domain.agent.ToolErrorCode
import com.example.myapplication.domain.agent.ToolExecutionContext
import com.example.myapplication.domain.agent.ToolGroup
import com.example.myapplication.domain.agent.ToolResult
import com.example.myapplication.domain.agent.ToolSpec
import com.example.myapplication.domain.agent.boundedObject
import com.example.myapplication.domain.agent.integerSchema
import com.example.myapplication.domain.agent.stringSchema
import com.example.myapplication.domain.agent.stringOrNull
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 笔记提议工具（W4-03 接线 / 详细设计 §3/§6/§13/§20；04-接口 §5/§6/§15）。
 *
 * 本期把“模型提议 → 用户确认”主链路接进生产工具注册表：
 *  - 包装既有 `read_note`：执行后由**应用侧**读取目标当前正文并签发 run 级 read receipt
 *    （receiptId/revision/正文哈希/事件序号全部由应用生成，模型只拿到 receiptId）；
 *  - 新增 `propose_note_patch`：参数 targetKind/targetId/baseRevision/readReceiptId/edits/reason，
 *    只调用 [NoteProposalPort.createProposal] 落 PENDING 提议，**从不写正文**；
 *    previewHash、readReceipt 均由应用侧生成并持久化，模型传入 previewHash/hash/receipt 内容
 *    会被 schema 的未知字段拒绝；
 *  - 工具返回 pending 引用后本轮可以结束：确认由用户在 UI 用**当前** AccessContext 发起，
 *    不自动确认、不自动重放（确认逻辑见 [NoteProposalPort.applyProposal]）。
 *
 * 失败码映射（设计 §13 的 STALE_REVISION/INVALID_ARGUMENT 口径，落到稳定 [ToolErrorCode]）：
 *  - 目标不存在/无权 → NOT_FOUND；
 *  - baseRevision / 回执 revision 与当前不一致、目标已变 → REVISION_MISSING（模型应重新 read_note）；
 *  - receiptId 不是本 run 签发的、参数缺失/类型错/超限/old 非唯一命中/触碰受保护源 → INVALID_ARGUMENT；
 *  - 提议数据完整性异常 → EVIDENCE_UNVERIFIED（不写正文）。
 *
 * 自然语言永远不会被当成写指令：本工具只解析结构化 JSON 参数，参数不合法直接返回结构化错误。
 */
class NoteProposalTools(
    private val port: NoteProposalPort,
    private val receipts: NoteReadReceiptRegistry = NoteReadReceiptRegistry(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * 返回本 run 的笔记工具体：[read_note 包装] + [propose_note_patch]。
     * [readNote] 必须是既有只读 read_note 工具（spec 不变，只在成功后补发应用侧回执）。
     */
    fun tools(readNote: AgentTool): List<AgentTool> = listOf(
        ReadNoteWithReceiptTool(readNote),
        ProposeNotePatchTool(),
    )

    // ------------------------------------------------------------ read_note

    /**
     * read_note 回执包装：委托既有只读工具完成后，用当前 AccessContext 读取真实目标正文，
     * 计算正文哈希并登记 receipt；在结果 payload 里回填 receiptId 供模型后续引用。
     *
     * 不使用模型可见的（可能被截断的）payload 正文做哈希，避免“读到的 hash 与确认时重算不一致”。
     */
    private inner class ReadNoteWithReceiptTool(
        private val delegate: AgentTool,
    ) : AgentTool {
        override val spec: ToolSpec get() = delegate.spec

        override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult {
            val result = delegate.execute(context, arguments)
            if (!result.ok) return result
            val payload = result.payload
            val kind = when (payload.stringOrNull("targetKind")) {
                "NOTEBOOK" -> NoteProposalTargetKind.NOTEBOOK
                "ANNOTATION", "ANNOTATION_NOTE" -> NoteProposalTargetKind.ANNOTATION_NOTE
                else -> return result
            }
            val targetId = payload.stringOrNull("targetId") ?: return result
            // 以应用侧读取到的当前正文/版本签发回执；读取失败时不签发（提议会被拒绝，不会写正文）。
            val target = port.readTarget(context.accessContext(), kind, targetId) ?: return result
            val receipt = NoteReadReceipt(
                receiptId = "receipt-${UUID.randomUUID()}",
                runId = context.runId,
                targetKind = kind,
                targetId = target.targetId,
                revision = target.revision,
                contentHash = ReaderMutationHasher.sha256(target.body),
                eventSeq = context.eventSeq,
                issuedAt = clock(),
            )
            receipts.issue(receipt)
            val augmented = buildJsonObject {
                payload.forEach { (key, value) -> put(key, value) }
                // 模型只能拿到 receiptId；hash/revision 由应用侧在创建提议时复核。
                put("readReceiptId", receipt.receiptId)
                putJsonObject("readReceipt") {
                    put("receiptId", receipt.receiptId)
                    put("runId", receipt.runId)
                    put("targetKind", receipt.targetKind.wire)
                    put("targetId", receipt.targetId)
                    put("revision", receipt.revision)
                    put("contentHash", receipt.contentHash)
                    put("eventSeq", receipt.eventSeq)
                    put("issuedAt", receipt.issuedAt)
                }
            }
            return ToolResult.success(
                callId = result.callId,
                toolName = result.toolName,
                payload = augmented,
                evidence = result.evidence,
                warnings = result.warnings + WARNING_RECEIPT_ISSUED,
            )
        }
    }

    // ---------------------------------------------------- propose_note_patch

    private inner class ProposeNotePatchTool : AgentTool {
        override val spec: ToolSpec = ToolSpec(
            name = TOOL_NAME,
            group = ToolGroup.NOTE_PROPOSAL,
            description = "对当前用户的笔记提出精确 old→new 修改建议，生成待用户确认的提议；" +
                "不直接修改正文，必须先在本 run 内 read_note 取得 readReceiptId。",
            readOnly = false,
            parameters = boundedObject(
                required = listOf("targetKind", "targetId", "baseRevision", "readReceiptId", "edits", "reason"),
                properties = mapOf(
                    "targetKind" to kotlinx.serialization.json.buildJsonObject {
                        put("type", "string")
                        put("minLength", 1)
                        put("maxLength", 32)
                        putJsonArray("enum") {
                            add(NoteProposalTargetKind.NOTEBOOK.wire)
                            add(NoteProposalTargetKind.ANNOTATION_NOTE.wire)
                        }
                    },
                    "targetId" to stringSchema(1, MAX_TARGET_ID_CHARS),
                    "baseRevision" to integerSchema(0, Int.MAX_VALUE),
                    "readReceiptId" to stringSchema(1, MAX_RECEIPT_ID_CHARS),
                    "edits" to kotlinx.serialization.json.buildJsonObject {
                        put("type", "array")
                        put("maxItems", NotePatchValidator.MAX_EDITS)
                        putJsonObject("items") { put("type", "object") }
                    },
                    "reason" to stringSchema(1, MAX_REASON_CHARS),
                ),
            ),
            maxArgumentBytes = MAX_ARGUMENT_BYTES,
        )

        override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult {
            return try {
                val kindRaw = ToolArgs.requireString(arguments, "targetKind", 1, 32)
                val kind = NoteProposalTargetKind.fromWire(kindRaw)
                    ?: throw ToolArgumentException("targetKind", "unknown targetKind: $kindRaw")
                val targetId = ToolArgs.requireString(arguments, "targetId", 1, MAX_TARGET_ID_CHARS)
                val baseRevision = ToolArgs.optionalInt(arguments, "baseRevision", 0, Int.MAX_VALUE)
                    ?: throw ToolArgumentException("baseRevision", "baseRevision is required")
                val readReceiptId = ToolArgs.requireString(arguments, "readReceiptId", 1, MAX_RECEIPT_ID_CHARS)
                val reason = ToolArgs.requireString(arguments, "reason", 1, MAX_REASON_CHARS)
                val edits = parseEdits(arguments["edits"])

                // 回执必须由本 run 的 read_note 签发；模型自填 baseRevision 不算读过。
                val receipt = receipts.find(context.runId, readReceiptId)
                    ?: return proposeFailure(
                        context,
                        ToolError(
                            ToolErrorCode.INVALID_ARGUMENT,
                            "readReceiptId 不是本 run 由 read_note 签发的回执，请先 read_note",
                            retryable = true,
                            field = "readReceiptId",
                        ),
                    )
                if (receipt.targetKind != kind || receipt.targetId != targetId) {
                    return proposeFailure(
                        context,
                        ToolError(
                            ToolErrorCode.INVALID_ARGUMENT,
                            "readReceiptId 与 targetKind/targetId 不匹配，请对该目标重新 read_note",
                            retryable = true,
                            field = "readReceiptId",
                        ),
                    )
                }
                if (receipt.revision != baseRevision.toLong()) {
                    return proposeFailure(
                        context,
                        ToolError(
                            ToolErrorCode.REVISION_MISSING,
                            "baseRevision 与回执 revision 不一致，请重新 read_note",
                            retryable = true,
                            field = "baseRevision",
                        ),
                    )
                }

                when (
                    val outcome = port.createProposal(
                        context = context.accessContext(),
                        runId = context.runId,
                        targetKind = kind,
                        targetId = targetId,
                        baseRevision = baseRevision.toLong(),
                        readReceipt = receipt,
                        edits = edits,
                        reason = reason,
                    )
                ) {
                    is ProposalCreateOutcome.Created -> {
                        val proposal = outcome.proposal
                        val payload = buildJsonObject {
                            put("proposalId", proposal.id)
                            put("status", proposal.status)
                            put("targetKind", proposal.targetKind.wire)
                            put("targetId", proposal.targetId)
                            put("documentId", proposal.documentId)
                            put("baseRevision", proposal.baseRevision)
                            // previewHash 由应用侧计算并持久化，回传只供用户界面/日志对照，模型不得改写。
                            put("previewHash", proposal.previewHash)
                            put("readReceiptId", proposal.readReceiptId)
                            put("editCount", proposal.edits.size)
                            put("reason", proposal.reason)
                            put("requiresUserConfirmation", true)
                            put(
                                "next",
                                "已生成待确认提议：必须由用户在会话里点击确认后才会写入；" +
                                    "不要重复调用本工具，也不要声称已经修改笔记",
                            )
                        }
                        ToolResult.success("", spec.name, payload)
                    }

                    is ProposalCreateOutcome.Rejected -> proposeFailure(context, mapRejection(outcome.code))
                }
            } catch (error: ToolArgumentException) {
                proposeFailure(
                    context,
                    ToolError(
                        ToolErrorCode.INVALID_ARGUMENT,
                        error.message.orEmpty(),
                        retryable = true,
                        field = error.field,
                    ),
                )
            }
        }

        private fun proposeFailure(context: ToolExecutionContext, error: ToolError): ToolResult =
            ToolResult.failure("", spec.name, error)
    }

    // --------------------------------------------------------------- helpers

    /** 解析并预先约束结构化 edits；更细的正文规则（唯一命中/预算/受保护源）由 NotePatchValidator 负责。 */
    private fun parseEdits(element: kotlinx.serialization.json.JsonElement?): List<NotePatchEdit> {
        val array = element as? JsonArray
            ?: throw ToolArgumentException("edits", "edits must be an array")
        if (array.isEmpty()) throw ToolArgumentException("edits", "edits must not be empty")
        if (array.size > NotePatchValidator.MAX_EDITS) {
            throw ToolArgumentException("edits", "edits allows at most ${NotePatchValidator.MAX_EDITS} items")
        }
        var addedBytes = 0L
        val edits = array.mapIndexed { index, item ->
            val obj = item as? JsonObject
                ?: throw ToolArgumentException("edits[$index]", "edit must be an object")
            val unknown = obj.keys - ALLOWED_EDIT_FIELDS
            if (unknown.isNotEmpty()) {
                // 模型试图自填 previewHash / hash / revision 等字段：直接拒绝（应用侧生成）。
                throw ToolArgumentException(
                    "edits[$index].${unknown.first()}",
                    "unknown edit field: ${unknown.first()}",
                )
            }
            val oldText = (obj["oldText"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: throw ToolArgumentException("edits[$index].oldText", "oldText is required")
            val newText = (obj["newText"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: throw ToolArgumentException("edits[$index].newText", "newText is required")
            val policy = (obj["occurrencePolicy"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.contentOrNull
                ?: NotePatchEdit.OCCURRENCE_UNIQUE
            if (policy != NotePatchEdit.OCCURRENCE_UNIQUE) {
                throw ToolArgumentException(
                    "edits[$index].occurrencePolicy",
                    "only ${NotePatchEdit.OCCURRENCE_UNIQUE} is supported",
                )
            }
            if (oldText.isEmpty()) {
                throw ToolArgumentException("edits[$index].oldText", "oldText must not be empty")
            }
            addedBytes += newText.toByteArray(Charsets.UTF_8).size
            if (addedBytes > NotePatchValidator.MAX_NEW_TEXT_BYTES) {
                throw ToolArgumentException(
                    "edits",
                    "new text exceeds ${NotePatchValidator.MAX_NEW_TEXT_BYTES} bytes",
                )
            }
            NotePatchEdit(oldText = oldText, newText = newText, occurrencePolicy = policy)
        }
        return edits
    }

    /** 服务拒绝码 → 稳定 ToolError（失败码口径见类注释）。 */
    private fun mapRejection(code: String): ToolError = when (code) {
        NoteProposalService.CODE_NOT_FOUND -> ToolError(
            ToolErrorCode.NOT_FOUND,
            "笔记目标不存在或无权访问",
            retryable = false,
            field = "targetId",
        )

        NoteProposalService.CODE_STALE_REVISION -> ToolError(
            ToolErrorCode.REVISION_MISSING,
            "目标正文已变化（STALE_REVISION），请重新 read_note 后重试",
            retryable = true,
            field = "baseRevision",
        )

        NoteProposalService.CODE_INVALID_RECEIPT -> ToolError(
            ToolErrorCode.REVISION_MISSING,
            "read receipt 与目标/版本不一致（INVALID_RECEIPT），请重新 read_note",
            retryable = true,
            field = "readReceiptId",
        )

        NoteProposalService.CODE_PROPOSAL_REJECTED -> ToolError(
            ToolErrorCode.INVALID_ARGUMENT,
            "该提议已被拒绝，不能复用原提议",
            retryable = false,
        )

        NoteProposalService.CODE_CORRUPT -> ToolError(
            ToolErrorCode.EVIDENCE_UNVERIFIED,
            "提议数据完整性校验失败（PROPOSAL_CORRUPT）",
            retryable = false,
        )

        NoteProposalService.CODE_STATE_RACE -> ToolError(
            ToolErrorCode.REVISION_MISSING,
            "并发状态变化，请重新读取目标后重试",
            retryable = true,
        )

        NotePatchValidator.CODE_PROTECTED_SOURCE -> ToolError(
            ToolErrorCode.INVALID_ARGUMENT,
            "不允许修改高亮引文/墨迹等受保护源数据",
            retryable = false,
            field = "edits",
        )

        NotePatchValidator.CODE_WHOLE_BODY_REPLACE -> ToolError(
            ToolErrorCode.INVALID_ARGUMENT,
            "不允许整篇 REPLACE，请给出精确 old→new 片段",
            retryable = true,
            field = "edits",
        )

        NotePatchValidator.CODE_TOO_MANY_EDITS,
        NotePatchValidator.CODE_TEXT_TOO_LARGE,
        -> ToolError(
            ToolErrorCode.INVALID_ARGUMENT,
            "修改量超出预算（≤${NotePatchValidator.MAX_EDITS} 段 / ${NotePatchValidator.MAX_NEW_TEXT_BYTES} 字节新增）",
            retryable = true,
            field = "edits",
        )

        else -> ToolError(
            ToolErrorCode.INVALID_ARGUMENT,
            "提议被拒绝：$code",
            retryable = true,
            field = "edits",
        )
    }

    companion object {
        const val TOOL_NAME = "propose_note_patch"
        const val MAX_TARGET_ID_CHARS = 256
        const val MAX_RECEIPT_ID_CHARS = 128
        const val MAX_REASON_CHARS = 2_000

        /**
         * 单次参数上限：设计 §13 的“≤20 段 / 32KiB 新增文本”在 JSON 转义后仍需放得下，
         * 因此本工具显式声明 36KiB（其它工具默认仍 8KiB，见 ToolSpec.MAX_ARGUMENT_BYTES）。
         */
        const val MAX_ARGUMENT_BYTES = 36 * 1024

        const val WARNING_RECEIPT_ISSUED =
            "READ_RECEIPT_ISSUED: 回执由应用签发，仅本 run 有效；propose_note_patch 时传 readReceiptId"

        private val ALLOWED_EDIT_FIELDS = setOf("oldText", "newText", "occurrencePolicy")
    }
}
