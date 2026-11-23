package com.example.myapplication.domain.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * ChatMessage 是 role + typed parts + toolCalls/toolCallId 的结构化消息，
 * 不是仅 role/content 两个字符串；各协议保留对话的工具调用组。
 *
 * message_json 持久化该结构（v2.2：content 只作可读预览，typed 结构才是上下文权威）。
 */
data class ChatMessage(
    val role: String,
    val parts: List<MessagePart> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    /** 可读预览；不用于重建协议输入。 */
    val contentPreview: String = "",
) {
    init {
        require(role in setOf("system", "user", "assistant", "tool")) { "invalid role: $role" }
    }

    companion object {
        fun user(text: String) = ChatMessage(role = "user", parts = listOf(MessagePart.Text(text)), contentPreview = text)

        fun assistant(text: String) = ChatMessage(role = "assistant", parts = listOf(MessagePart.Text(text)), contentPreview = text)

        fun system(text: String) = ChatMessage(role = "system", parts = listOf(MessagePart.Text(text)), contentPreview = text)

        fun toolResult(callId: String, text: String) = ChatMessage(
            role = "tool",
            parts = listOf(MessagePart.Text(text)),
            toolCallId = callId,
            contentPreview = text,
        )
    }
}

sealed interface MessagePart {
    data class Text(val text: String) : MessagePart

    /** provider-required opaque 片段（例如 Responses item id、Gemini thoughtSignature）。 */
    data class Opaque(val provider: String, val kind: String, val payloadJson: String) : MessagePart
}

data class ToolCall(
    val callId: String,
    val name: String,
    val argumentsJson: String,
)

data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
)

enum class ToolPolicy { AUTO, NONE, REQUIRED }

data class ChatBudget(
    val inputLimit: Int,
    val outputReserve: Int,
    val deadlineMs: Long,
)

/** 统一流事件（公共契约 §7）。 */
sealed interface ChatEvent {
    data class TextDelta(val text: String) : ChatEvent
    data class ToolDelta(
        val index: Int,
        val callId: String? = null,
        val name: String? = null,
        val argumentsDelta: String? = null,
    ) : ChatEvent

    data class ToolComplete(val callId: String, val name: String, val argumentsJson: String) : ChatEvent
    data class Usage(val inputTokens: Int, val outputTokens: Int) : ChatEvent
    data class Finish(val reason: FinishReason) : ChatEvent
    data class ProviderError(val code: String, val retryable: Boolean) : ChatEvent
}

enum class FinishReason { STOP, TOOL_CALLS, LENGTH, REFUSAL, CONTENT_FILTER, ERROR }

data class ChatRequest(
    val runId: String,
    val profileId: String,
    val configRevision: Long,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val toolPolicy: ToolPolicy = ToolPolicy.AUTO,
    val responseSchema: String? = null,
    val stream: Boolean = true,
    val budget: ChatBudget,
)

/** 三类错误分类：鉴权（需用户修复）/ 限流（可退避重试）/ 其他（按 retryable）。 */
enum class AiErrorKind { AUTH, RATE_LIMIT, TRANSIENT, INVALID_REQUEST, MODEL, UNKNOWN }

data class AiError(
    val kind: AiErrorKind,
    val code: String,
    val retryable: Boolean,
    val retryAtMs: Long? = null,
    val message: String,
)

/**
 * typed 消息的 JSON codec：message_json 的权威结构与 content 预览分离。
 * 空对象/缺 schemaVersion/未知版本都拒绝，不能当可执行历史。
 */
object ChatMessageCodec {
    const val SCHEMA_VERSION = 1

    private val json = Json { ignoreUnknownKeys = false }

    fun encode(message: ChatMessage): String = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("role", message.role)
        put("parts", buildJsonArray {
            message.parts.forEach { part ->
                add(
                    when (part) {
                        is MessagePart.Text -> buildJsonObject {
                            put("type", "TEXT")
                            put("text", part.text)
                        }
                        is MessagePart.Opaque -> buildJsonObject {
                            put("type", "OPAQUE")
                            put("provider", part.provider)
                            put("kind", part.kind)
                            put("payload", part.payloadJson)
                        }
                    },
                )
            }
        })
        put("toolCalls", buildJsonArray {
            message.toolCalls.forEach { call ->
                add(
                    buildJsonObject {
                        put("callId", call.callId)
                        put("name", call.name)
                        put("arguments", call.argumentsJson)
                    },
                )
            }
        })
        put("toolCallId", message.toolCallId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("contentPreview", message.contentPreview)
    }.toString()

    fun decode(text: String): ChatMessage {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw AiCodecException("message_json root must be object")
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw AiCodecException("message_json missing schemaVersion")
        if (version != SCHEMA_VERSION) throw AiCodecException("unsupported message_json schemaVersion=$version")
        val role = (root["role"] as? JsonPrimitive)?.contentOrNull
            ?: throw AiCodecException("message_json missing role")
        val parts = (root["parts"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "TEXT" -> MessagePart.Text((obj["text"] as? JsonPrimitive)?.contentOrNull ?: "")
                "OPAQUE" -> MessagePart.Opaque(
                    provider = (obj["provider"] as? JsonPrimitive)?.contentOrNull ?: "",
                    kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull ?: "",
                    payloadJson = (obj["payload"] as? JsonPrimitive)?.contentOrNull ?: "{}",
                )
                else -> null
            }
        }
        val toolCalls = (root["toolCalls"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val callId = (obj["callId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            ToolCall(
                callId = callId,
                name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: "",
                argumentsJson = (obj["arguments"] as? JsonPrimitive)?.contentOrNull ?: "{}",
            )
        }
        return ChatMessage(
            role = role,
            parts = parts,
            toolCalls = toolCalls,
            toolCallId = (root["toolCallId"] as? JsonPrimitive)?.contentOrNull,
            contentPreview = (root["contentPreview"] as? JsonPrimitive)?.contentOrNull ?: "",
        )
    }
}

class AiCodecException(message: String) : IllegalArgumentException(message)

/** RunProfileSnapshot 的 JSON codec（schemaVersion 校验，空对象拒绝）。 */
object RunProfileSnapshotCodec {
    const val SCHEMA_VERSION = 1

    private val json = Json { ignoreUnknownKeys = false }

    fun encode(snapshot: RunProfileSnapshot): String = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("profileId", snapshot.profileId)
        put("configRevision", snapshot.configRevision)
        put("protocol", snapshot.protocol.name)
        put("endpointHash", snapshot.endpointHash)
        put("endpoint", snapshot.endpoint)
        put("model", snapshot.model)
        put("paramsJson", snapshot.paramsJson)
        put("capabilitiesHash", snapshot.capabilitiesHash)
        put("secretVersionRef", snapshot.secretVersionRef?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()

    fun decode(text: String): RunProfileSnapshot {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw AiCodecException("profile_snapshot_json root must be object")
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw AiCodecException("profile_snapshot_json missing schemaVersion")
        if (version != SCHEMA_VERSION) throw AiCodecException("unsupported profile_snapshot schemaVersion=$version")
        val protocolName = (root["protocol"] as? JsonPrimitive)?.contentOrNull
            ?: throw AiCodecException("profile_snapshot missing protocol")
        val protocol = WireProtocol.fromStorage(protocolName)
            ?: throw AiCodecException("unknown protocol $protocolName")
        return RunProfileSnapshot(
            schemaVersion = version,
            profileId = (root["profileId"] as? JsonPrimitive)?.contentOrNull
                ?: throw AiCodecException("profile_snapshot missing profileId"),
            configRevision = (root["configRevision"] as? JsonPrimitive)?.longOrNull
                ?: throw AiCodecException("profile_snapshot missing configRevision"),
            protocol = protocol,
            endpointHash = (root["endpointHash"] as? JsonPrimitive)?.contentOrNull ?: "",
            endpoint = (root["endpoint"] as? JsonPrimitive)?.contentOrNull ?: "",
            model = (root["model"] as? JsonPrimitive)?.contentOrNull ?: "",
            paramsJson = (root["paramsJson"] as? JsonPrimitive)?.contentOrNull ?: "{}",
            capabilitiesHash = (root["capabilitiesHash"] as? JsonPrimitive)?.contentOrNull ?: "",
            secretVersionRef = (root["secretVersionRef"] as? JsonPrimitive)?.contentOrNull,
        )
    }
}
