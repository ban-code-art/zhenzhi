package com.example.myapplication.data.ai.adapters

import com.example.myapplication.data.ai.net.KtorTransport
import com.example.myapplication.data.ai.config.FinalEndpoint
import com.example.myapplication.data.ai.protocol.SseFrameParser
import com.example.myapplication.data.ai.protocol.ToolCallAccumulator
import com.example.myapplication.domain.ai.ChatEvent
import com.example.myapplication.domain.ai.ChatRequest
import com.example.myapplication.domain.ai.FinishReason
import com.example.myapplication.domain.ai.MessagePart
import com.example.myapplication.domain.ai.ToolPolicy
import com.example.myapplication.domain.ai.WireProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI Responses API（/responses）。
 *
 * 与 Chat 的关键差异：
 *  - 输入用 input[] item 结构；assistant 的 function_call 与 function_call_output 是独立 item
 *  - 流事件为 response.output_text.delta / response.function_call_arguments.delta / response.completed
 *  - 工具调用以 call_id 关联，continuation 通过 previous_response_id
 *
 * 本适配器实现完整 function-call 往返：请求侧把 assistant toolCalls 还原为 function_call item，
 * tool 消息还原为 function_call_output item；响应侧按 call_id 组装。
 */
class OpenAiResponsesAdapter(
    private val model: String,
) : ChatAdapter {
    override val protocol = WireProtocol.OPENAI_RESPONSES

    override fun buildChatRequest(request: ChatRequest, apiKey: String?, endpoint: FinalEndpoint): KtorTransport.Request {
        val body = buildJsonObject {
            put("model", model)
            put("stream", request.stream)
            put("input", buildInput(request))
            if (request.tools.isNotEmpty() && request.toolPolicy != ToolPolicy.NONE) {
                putJsonArray("tools") {
                    request.tools.forEach { tool ->
                        addJsonObject {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", runCatching { aiJson.parseToJsonElement(tool.parametersJson) }.getOrElse { JsonObject(emptyMap()) })
                        }
                    }
                }
            }
            request.responseSchema?.let { schema ->
                putJsonObject("text") {
                    putJsonObject("format") {
                        put("type", "json_schema")
                        put("name", "response")
                        put("schema", runCatching { aiJson.parseToJsonElement(schema) }.getOrElse { JsonObject(emptyMap()) })
                        put("strict", true)
                    }
                }
            }
            request.budget.outputReserve.takeIf { it > 0 }?.let { put("max_output_tokens", it) }
        }
        return KtorTransport.Request(
            url = endpoint.chatUrl(request.stream),
            headers = buildMap {
                apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
            },
            body = body.toString(),
            stream = request.stream,
        )
    }

    /** 把 typed 历史还原为 Responses input items，保留 function_call / function_call_output 组。 */
    private fun buildInput(request: ChatRequest) = buildJsonArray {
        request.messages.forEach { message ->
            val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
            when (message.role) {
                "system" -> addJsonObject {
                    put("role", "system")
                    put("content", text)
                }
                "user" -> addJsonObject {
                    put("role", "user")
                    put("content", text)
                }
                "assistant" -> {
                    if (text.isNotEmpty()) {
                        addJsonObject {
                            put("role", "assistant")
                            put("content", text)
                        }
                    }
                    message.toolCalls.forEach { call ->
                        addJsonObject {
                            put("type", "function_call")
                            put("call_id", call.callId)
                            put("name", call.name)
                            put("arguments", call.argumentsJson)
                        }
                    }
                }
                "tool" -> addJsonObject {
                    put("type", "function_call_output")
                    put("call_id", message.toolCallId ?: "")
                    put("output", text)
                }
            }
            // provider-required opaque parts（例如 reasoning item id）按原样回传
            message.parts.filterIsInstance<MessagePart.Opaque>().forEach { opaque ->
                if (opaque.provider == "openai_responses") {
                    runCatching { aiJson.parseToJsonElement(opaque.payloadJson) }.getOrNull()?.let { add(it) }
                }
            }
        }
    }

    override fun decodeStreamFrame(frame: SseFrameParser.Frame, accumulator: ToolCallAccumulator): List<ChatEvent> {
        return eventsOrInvalid {
            if (frame.data.isBlank()) return emptyList()
            if (frame.data.trim() == "[DONE]") return if (accumulator.finishOnce()) {
                listOf(ChatEvent.Finish(if (accumulator.hasToolCalls()) FinishReason.TOOL_CALLS else FinishReason.STOP))
            } else emptyList()
            val root = runCatching { aiJson.parseToJsonElement(frame.data).jsonObject }.getOrNull() ?: return invalidPayload()
            root.obj("error")?.let { error ->
                return listOf(ChatEvent.ProviderError(error.str("code") ?: "PROVIDER_ERROR", false))
            }
            val type = root.checkedText("type") ?: frame.event ?: return emptyList()
            val events = mutableListOf<ChatEvent>()
            when (type) {
                "response.output_text.delta" -> root.checkedText("delta", required = true)?.takeIf { it.isNotEmpty() }?.let { events.add(ChatEvent.TextDelta(it)) }
                "response.output_item.added", "response.output_item.done" -> {
                    val item = root.checkedObject("item", required = true) ?: return invalidPayload()
                    if (item.checkedText("type", required = true) == "function_call") {
                        val callId = item.checkedText("call_id", required = true)?.takeIf { it.isNotBlank() } ?: return invalidPayload()
                        val itemId = item.checkedText("id")
                        val name = item.checkedText("name", required = true)?.takeIf { it.isNotBlank() } ?: return invalidPayload()
                        val args = item.checkedText("arguments")
                        val authoritativeArgs = if (type == "response.output_item.done") args else args?.takeIf { it.isNotEmpty() }
                        val index = toolIndex(accumulator, callId, itemId, root.checkedIndex("output_index"), name, authoritativeArgs) ?: return invalidPayload()
                        accumulator.accept(index, callId, name, null)
                        if (type == "response.output_item.done") {
                            accumulator.completeArguments(index, args)
                        } else {
                            args?.takeIf { it.isNotEmpty() }?.let { accumulator.accept(index, null, null, it) }
                        }
                        accumulator.buildComplete().forEach { events.add(ChatEvent.ToolComplete(it.callId, it.name, it.argumentsJson)) }
                    }
                }
                "response.function_call_arguments.delta" -> {
                    val callId = root.checkedText("call_id")
                    val delta = root.checkedText("delta", required = true) ?: return invalidPayload()
                    val name = root.checkedText("name")
                    val index = toolIndex(accumulator, callId, root.checkedText("item_id"), root.checkedIndex("output_index"), name) ?: return invalidPayload()
                    accumulator.accept(index = index, callId = callId, name = name, argumentsDelta = delta)
                    events.add(ChatEvent.ToolDelta(index = index, callId = callId, name = name, argumentsDelta = delta))
                }
                "response.function_call_arguments.done" -> {
                    val callId = root.checkedText("call_id")
                    val name = root.checkedText("name")
                    val args = root.checkedText("arguments")
                    val index = toolIndex(accumulator, callId, root.checkedText("item_id"), root.checkedIndex("output_index"), name, args) ?: return invalidPayload()
                    accumulator.accept(index, callId, name, null)
                    accumulator.completeArguments(index, args)
                    accumulator.buildComplete().forEach { events.add(ChatEvent.ToolComplete(it.callId, it.name, it.argumentsJson)) }
                }
                "response.completed" -> {
                    val response = root.checkedObject("response", required = true)
                    response?.obj("usage")?.let { usage ->
                        val totals = accumulator.usage(usage.int("input_tokens"), usage.int("output_tokens"))
                        events.add(ChatEvent.Usage(totals.first, totals.second))
                    }
                    accumulator.buildComplete().forEach { call ->
                        events.add(ChatEvent.ToolComplete(call.callId, call.name, call.argumentsJson))
                    }
                    if (accumulator.finishOnce()) events.add(ChatEvent.Finish(if (accumulator.hasToolCalls()) FinishReason.TOOL_CALLS else FinishReason.STOP))
                }
                "response.failed", "response.error", "error" -> {
                    val code = root.obj("error")?.str("code") ?: root.obj("response")?.obj("error")?.str("code")
                    events.add(ChatEvent.ProviderError(code = code ?: "PROVIDER_ERROR", retryable = false))
                }
                "response.incomplete" -> if (accumulator.finishOnce()) events.add(ChatEvent.Finish(FinishReason.LENGTH))
            }
            return events
        }
    }

    private fun toolIndex(accumulator: ToolCallAccumulator, callId: String?, itemId: String?, outputIndex: Int?, name: String? = null, argumentsJson: String? = null): Int? {
        if (callId.isNullOrBlank() && itemId.isNullOrBlank() && outputIndex == null) return null
        return accumulator.identityIndex(callId, itemId, outputIndex, name, argumentsJson)
    }

    override fun decodeNonStream(body: String): List<ChatEvent> {
        return eventsOrInvalid {
            val root = runCatching { aiJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return invalidPayload()
            root.obj("error")?.let { return listOf(ChatEvent.ProviderError(it.str("code") ?: "PROVIDER_ERROR", false)) }
            val output = root.checkedArray("output", required = true) ?: return invalidPayload()
            val events = mutableListOf<ChatEvent>()
            output.forEach { element ->
                val item = element as? JsonObject ?: return invalidPayload()
                when (item.checkedText("type", required = true)) {
                    "message" -> item.checkedArray("content", required = true)?.forEach { part ->
                        val content = part as? JsonObject ?: return invalidPayload()
                        if (content.checkedText("type", required = true) == "output_text") {
                            content.checkedText("text", required = true)?.takeIf { it.isNotEmpty() }?.let { events.add(ChatEvent.TextDelta(it)) }
                        }
                    }
                    "function_call" -> {
                        val callId = item.checkedText("call_id", required = true)?.takeIf { it.isNotBlank() } ?: return invalidPayload()
                        events.add(
                            ChatEvent.ToolComplete(
                                callId = callId,
                                name = item.checkedText("name", required = true)?.takeIf { it.isNotBlank() } ?: return invalidPayload(),
                                argumentsJson = item.checkedText("arguments", required = true) ?: return invalidPayload(),
                            ),
                        )
                    }
                }
            }
            root.obj("usage")?.let { usage ->
                val input = usage.int("input_tokens")
                if (input != null) events.add(ChatEvent.Usage(input, usage.int("output_tokens") ?: 0))
            }
            val hasTools = events.any { it is ChatEvent.ToolComplete }
            root.checkedText("status")?.let {
                events.add(ChatEvent.Finish(if (hasTools) FinishReason.TOOL_CALLS else FinishReason.STOP))
            }
            return events
        }
    }

    override fun buildModelsRequest(endpoint: FinalEndpoint, apiKey: String?): KtorTransport.Request =
        KtorTransport.Request(
            url = endpoint.modelsUrl(),
            method = "GET",
            headers = buildMap {
                apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
            },
        )

    override fun decodeModels(body: String): List<String> {
        val root = runCatching { aiJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        return root.arr("data").orEmpty().mapNotNull { it.jsonObject.str("id") }
    }
}
