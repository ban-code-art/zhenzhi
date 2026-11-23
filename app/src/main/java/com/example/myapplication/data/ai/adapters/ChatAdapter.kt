package com.example.myapplication.data.ai.adapters

import com.example.myapplication.data.ai.net.KtorTransport
import com.example.myapplication.data.ai.config.EndpointOperation
import com.example.myapplication.data.ai.config.FinalEndpoint
import com.example.myapplication.data.ai.protocol.AiErrorClassifier
import com.example.myapplication.data.ai.protocol.SseFrameParser
import com.example.myapplication.data.ai.protocol.ToolCallAccumulator
import com.example.myapplication.domain.ai.ChatEvent
import com.example.myapplication.domain.ai.ChatMessage
import com.example.myapplication.domain.ai.ChatRequest
import com.example.myapplication.domain.ai.FinishReason
import com.example.myapplication.domain.ai.MessagePart
import com.example.myapplication.domain.ai.ToolPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 协议适配器统一接口：解析 SSE/非流响应为 ChatEvent，构造请求体。
 * 纯解码逻辑不依赖 Android，可用 fixture 离线测试。
 */
interface ChatAdapter {
    val protocol: com.example.myapplication.domain.ai.WireProtocol

    fun buildChatRequest(request: ChatRequest, apiKey: String?, endpoint: FinalEndpoint): KtorTransport.Request

    /** 解析一个 SSE 帧；返回空列表表示该帧无事件（心跳/未知类型）。 */
    fun decodeStreamFrame(frame: SseFrameParser.Frame, accumulator: ToolCallAccumulator): List<ChatEvent>

    /** 解析非流式响应体。 */
    fun decodeNonStream(body: String): List<ChatEvent>

    /** 模型列表请求。 */
    fun buildModelsRequest(endpoint: FinalEndpoint, apiKey: String?): KtorTransport.Request

    /** 解析模型列表响应；返回模型 id 列表。 */
    fun decodeModels(body: String): List<String>
}

internal val aiJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun FinalEndpoint.chatUrl(stream: Boolean): String {
    require(operation == if (stream) EndpointOperation.CHAT_STREAM else EndpointOperation.CHAT_SINGLE) {
        "Resolved endpoint operation does not match chat request"
    }
    return url
}

internal fun FinalEndpoint.modelsUrl(): String {
    require(operation == EndpointOperation.MODELS) { "Resolved endpoint is not a models resource" }
    return url
}

internal fun invalidPayload() = listOf(ChatEvent.ProviderError("INVALID_PROVIDER_RESPONSE", retryable = false))

/** Expected protocol shape/identity failures are provider errors; no response body enters diagnostics. */
internal inline fun eventsOrInvalid(block: () -> List<ChatEvent>): List<ChatEvent> = try {
    block()
} catch (_: IllegalArgumentException) {
    invalidPayload()
}

internal fun JsonObject.checkedText(name: String, required: Boolean = false, allowNull: Boolean = false): String? {
    val value = this[name]
    if (value == null) {
        require(!required) { "Missing string field" }
        return null
    }
    if (allowNull && value == JsonNull) return null
    require(value is JsonPrimitive && value.isString) { "Invalid string field" }
    return value.content
}

internal fun JsonObject.checkedObject(name: String, required: Boolean = false, allowNull: Boolean = false): JsonObject? {
    val value = this[name]
    if (value == null) {
        require(!required) { "Missing object field" }
        return null
    }
    if (allowNull && value == JsonNull) return null
    require(value is JsonObject) { "Invalid object field" }
    return value
}

internal fun JsonObject.checkedArray(name: String, required: Boolean = false): JsonArray? {
    val value = this[name]
    if (value == null) {
        require(!required) { "Missing array field" }
        return null
    }
    require(value is JsonArray && value.all { it is JsonObject }) { "Invalid object array field" }
    return value
}

internal fun JsonObject.checkedIndex(name: String, required: Boolean = false): Int? {
    val value = this[name]
    if (value == null) {
        require(!required) { "Missing index field" }
        return null
    }
    require(value is JsonPrimitive && !value.isString) { "Invalid index field" }
    return requireNotNull(value.intOrNull).also { require(it >= 0) { "Invalid index field" } }
}

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.arr(name: String): JsonArray? = this[name] as? JsonArray

/** 把 ChatMessage 列表转为各协议的消息数组（由子类实现具体形态）。 */
internal fun buildOpenAiMessages(request: ChatRequest): JsonArray = buildJsonArray {
    request.messages.forEach { message ->
        addJsonObject {
            put("role", message.role)
            val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
            if (message.parts.any { it is MessagePart.Opaque }) {
                // Opaque 片段按协议由子类处理；OpenAI Chat 保留文本视图
                put("content", text)
            } else {
                put("content", text)
            }
            if (message.toolCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    message.toolCalls.forEach { call ->
                        addJsonObject {
                            put("id", call.callId)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.argumentsJson)
                            }
                        }
                    }
                }
            }
            message.toolCallId?.let { put("tool_call_id", it) }
        }
    }
}

internal fun buildOpenAiTools(request: ChatRequest): JsonArray? {
    if (request.tools.isEmpty() || request.toolPolicy == ToolPolicy.NONE) return null
    return buildJsonArray {
        request.tools.forEach { tool ->
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", runCatching { aiJson.parseToJsonElement(tool.parametersJson) }.getOrElse { JsonObject(emptyMap()) })
                }
            }
        }
    }
}

internal fun finishReasonOf(raw: String?): FinishReason = when (raw?.lowercase()) {
    "stop", "end_turn", "stop_sequence" -> FinishReason.STOP
    "tool_calls", "tool_use", "function_call" -> FinishReason.TOOL_CALLS
    "length", "max_tokens" -> FinishReason.LENGTH
    "content_filter", "safety", "recitation", "blocklist", "prohibited_content" -> FinishReason.CONTENT_FILTER
    "refusal" -> FinishReason.REFUSAL
    else -> FinishReason.STOP
}

/** 从 OpenAI 风格 usage 对象提取 token 数。 */
internal fun usageEvent(usage: JsonObject?): ChatEvent? {
    if (usage == null) return null
    val input = usage.int("prompt_tokens") ?: usage.int("input_tokens") ?: return null
    val output = usage.int("completion_tokens") ?: usage.int("output_tokens") ?: 0
    return ChatEvent.Usage(inputTokens = input, outputTokens = output)
}

internal fun errorEvent(status: Int, retryAfter: String?, providerCode: String?): ChatEvent {
    val error = AiErrorClassifier.fromHttp(status, AiErrorClassifier.parseRetryAfter(retryAfter, System.currentTimeMillis()), providerCode)
    return ChatEvent.ProviderError(code = error.code, retryable = error.retryable)
}
