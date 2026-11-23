package com.example.myapplication.ui.ai

import com.example.myapplication.data.agent.ConversationRepository
import com.example.myapplication.data.local.entity.AiMessageEntity
import com.example.myapplication.data.local.entity.AiRunEventEntity
import com.example.myapplication.domain.ai.ChatMessageCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One immutable run view, keyed only by persisted message associations. */
internal data class AgentRunProjectionSnapshot(
    val seq: Long,
    val messages: List<AgentMessageUi>,
    val phase: AgentRunPhase,
    val precise: Boolean,
)

internal object AgentRunProjection {
    private val TERMINAL_EVENTS = setOf("RUN_COMPLETED", "RUN_CANCELLED", "RUN_INTERRUPTED", "RUN_ERROR")
    fun messageIds(runId: String, events: List<AiRunEventEntity>): Set<String> = events.asSequence()
        .filter { it.runId == runId && it.type == "MESSAGE_UPDATED" }
        .mapNotNull { event ->
            val payload = runCatching { Json.parseToJsonElement(event.payloadJson) as? JsonObject }.getOrNull()
            (payload?.get("messageId") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }.toSet()

    fun project(
        runId: String,
        phase: AgentRunPhase,
        events: List<AiRunEventEntity>,
        messages: List<AiMessageEntity>,
        sourcesByMessageId: Map<String, List<AgentSourceUi>> = emptyMap(),
    ): AgentRunProjectionSnapshot {
        val orderedEvents = events.filter { it.runId == runId }.sortedBy { it.seq }
        val ids = messageIds(runId, orderedEvents)
        // A terminal transaction can commit after the caller read the run row.
        // Its durable event is authoritative for this exact cursor.
        val effectivePhase = when (orderedEvents.lastOrNull { it.type in TERMINAL_EVENTS }?.type) {
            "RUN_COMPLETED" -> AgentRunPhase.COMPLETED
            "RUN_CANCELLED" -> AgentRunPhase.CANCELLED
            "RUN_INTERRUPTED" -> AgentRunPhase.INTERRUPTED
            "RUN_ERROR" -> AgentRunPhase.ERROR
            else -> phase
        }
        val active = effectivePhase in setOf(AgentRunPhase.PREPARING, AgentRunPhase.GENERATING, AgentRunPhase.TOOL_RUNNING)
        val terminalLabel = when (effectivePhase) {
            AgentRunPhase.CANCELLED -> "已停止"
            AgentRunPhase.INTERRUPTED -> "已中断（未完成）"
            AgentRunPhase.ERROR -> "失败"
            AgentRunPhase.COMPLETED -> "已完成"
            else -> null
        }
        val toolStates = orderedEvents.asSequence()
            .filter { it.toolCallId != null && it.type in setOf("TOOL_CALL", "TOOL_RESULT", "TOOL_ERROR") }
            .associateBy { it.toolCallId!! }
        val projected = messages.asSequence().filter { it.id in ids }
            .distinctBy { it.id }.sortedBy { it.orderKey }.mapNotNull { message ->
                when (message.role) {
                    "user" -> AgentMessageUi(message.id, AgentMessageRole.USER, message.content)
                    "assistant" -> {
                        val decoded = runCatching { ChatMessageCodec.decode(message.messageJson) }.getOrNull()
                        val steps = decoded?.toolCalls.orEmpty().distinctBy { it.callId }.map { call ->
                            val status = when (toolStates[call.callId]?.type) {
                                "TOOL_RESULT" -> AgentToolStatus.SUCCEEDED
                                "TOOL_ERROR" -> AgentToolStatus.FAILED
                                else -> AgentToolStatus.RUNNING
                            }
                            AgentExploration.step(call, status, toolStates[call.callId]?.payloadJson, active)
                        }
                        AgentMessageUi(
                            id = message.id,
                            role = AgentMessageRole.ASSISTANT,
                            content = if (steps.isEmpty()) message.content else "",
                            streaming = active && message.status == ConversationRepository.STATUS_STREAMING,
                            toolSteps = steps,
                            sources = sourcesByMessageId[message.id].orEmpty(),
                            statusLabel = terminalLabel ?: AgentHistoryMapper.statusLabelOf(message.status),
                        )
                    }
                    else -> null
                }
            }.toList()
        return AgentRunProjectionSnapshot(orderedEvents.lastOrNull()?.seq ?: 0L, projected, effectivePhase, ids.isNotEmpty())
    }
}
