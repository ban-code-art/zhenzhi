package com.example.myapplication.ui.ai

import com.example.myapplication.data.agent.ConversationRepository
import com.example.myapplication.data.local.entity.AiMessageEntity
import com.example.myapplication.domain.ai.ChatMessageCodec
import kotlinx.serialization.json.*

/**
 * 历史会话回读的纯映射（P4-03 / 详细设计 §5）：把 `ai_messages` 行转成 UI 消息。
 *
 * 硬约束：
 *  - 按 `order_key` 升序展示（同会话内单调递增，唯一索引防重复）；
 *  - user / assistant 只展示已持久化正文，`streaming=false`（历史行不会继续流式更新）；
 *  - tool/system 行隐藏；工具轨迹仅使用结构化 toolCalls 与对应的工具结果 JSON，
 *    截断或旧版摘要不反推结果，原始 JSON 不作为正文展示；
 *  - sources 只来自 `sources_json` 快照（由调用方用 [AgentSourcesCodec][com.example.myapplication.data.agent.evidence.AgentSourcesCodec]
 *    解码并复核可用性），本映射器不解析正文里的 [S#]，绝不伪造引用。
 */
internal object AgentHistoryMapper {

    /** 单次回读上限：与分页 PAGE_SIZE 同量级，避免异常数据一次撑爆 UI。 */
    const val MAX_HISTORY_MESSAGES = 200

    fun toMessageUi(
        messages: List<AiMessageEntity>,
        sourcesByMessageId: Map<String, List<AgentSourceUi>> = emptyMap(),
    ): List<AgentMessageUi> {
        val ordered = messages
        .sortedBy { it.orderKey }
        .take(MAX_HISTORY_MESSAGES)
        return ordered.mapIndexedNotNull { index, entity ->
            when (entity.role) {
                ROLE_USER -> AgentMessageUi(
                    id = entity.id,
                    role = AgentMessageRole.USER,
                    content = entity.content,
                    streaming = false,
                )

                ROLE_ASSISTANT -> {
                    val calls = runCatching { ChatMessageCodec.decode(entity.messageJson) }
                        .getOrNull()?.toolCalls.orEmpty()
                    AgentMessageUi(
                        id = entity.id,
                        role = AgentMessageRole.ASSISTANT,
                        content = if (calls.isEmpty()) entity.content else "",
                        streaming = false,
                        sources = sourcesByMessageId[entity.id].orEmpty(),
                        toolSteps = calls.distinctBy { it.callId }.map { call ->
                            // Only structured tool rows in this assistant round can complete its calls.
                            val result = ordered.drop(index + 1).takeWhile { it.role != ROLE_USER && it.role != ROLE_ASSISTANT }
                                .firstOrNull { row -> runCatching { ChatMessageCodec.decode(row.messageJson).toolCallId == call.callId }.getOrDefault(false) }
                                ?.content
                            val ok = runCatching { (Json.parseToJsonElement(result.orEmpty()) as? JsonObject)?.get("ok")?.jsonPrimitive?.booleanOrNull }.getOrNull()
                            AgentExploration.step(call, when (ok) { true -> AgentToolStatus.SUCCEEDED; false -> AgentToolStatus.FAILED; null -> AgentToolStatus.RUNNING }, result, false)
                        },
                        statusLabel = statusLabelOf(entity.status),
                    )
                }

                // tool / system：历史里只有摘要或内部记录，隐藏而不伪造。
                else -> null
            }
        }
    }

    /** 历史 assistant 行状态文案：区分正常完成、用户停止、进程中断的未完成内容。 */
    fun statusLabelOf(status: String): String? = when (status) {
        ConversationRepository.STATUS_COMPLETE -> "已完成"
        ConversationRepository.STATUS_INTERRUPTED -> "已中断（未完成）"
        ConversationRepository.STATUS_STREAMING -> "未完成"
        else -> null
    }

    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"
}
