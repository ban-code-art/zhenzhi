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
import kotlinx.serialization.json.JsonArray
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
 * Anthropic Messages（/messages）。
 * 独立 system、content block、tool_use/tool_result；x-api-key + anthropic-version。
 */
class AnthropicAdapter(
    private val model: String,
) : ChatAdapter {
    override val protocol = WireProtocol.ANTHROPIC_MESSAGES

    override fun buildChatRequest(request: ChatRequest, apiKey: String?, endpoint: FinalEndpoint): KtorTransport.Request {
        val systemText = request.messages.filter { it.role == "system" }
            .flatMap { it.parts.filterIsInstance<MessagePart.Text>() }
            .joinToString("\n") { it.text }
        val body = buildJsonObject {
            put("model", model)
            put("stream", request.stream)
            put("max_tokens", request.budget.outputReserve.coerceAtLeast(1))
            if (systemText.isNotEmpty()) put("system", systemText)
            put("messages", buildAnthropicMessages(request))
            if (request.tools.isNotEmpty() && request.toolPolicy != ToolPolicy.NONE) {
                putJsonArray("tools") {
                    request.tools.forEach { tool ->
                        addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", runCatching { aiJson.parseToJsonElement(tool.parametersJson) }.getOrElse { JsonObject(emptyMap()) })
                        }
                    }
                }
            }
        }
        return KtorTransport.Request(
            url = endpoint.chatUrl(request.stream),
            headers = buildMap {
                apiKey?.takeIf { it.isNotBlank() }?.let { put("x-api-key", it) }
                put("anthropic-version", "2023-06-01")
            },
            body = body.toString(),
            stream = request.stream,
        )
    }

    private fun buildAnthropicMessages(request: ChatRequest): JsonArray = buildJsonArray {
        request.messages.filter { it.role != "system" }.forEach { message ->
            addJsonObject {
                put("role", if (message.role == "tool") "user" else message.role)
                putJsonArray("content") {
                    val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
                    if (text.isNotEmpty()) {
                        addJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    }
                    message.toolCalls.forEach { call ->
                        addJsonObject {
                            put("type", "tool_use")
                            put("id", call.callId)
                            put("name", call.name)
                            put("input", runCatching { aiJson.parseToJsonElement(call.argumentsJson) }.getOrElse { JsonObject(emptyMap()) })
                        }
                    }
                    message.toolCallId?.let { callId ->
                        addJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", callId)
                            put("content", text)
                        }
                    }
                }
            }
        }
    }

    override fun decodeStreamFrame(frame: SseFrameParser.Frame, accumulator: ToolCallAccumulator): List<ChatEvent> {
        return eventsOrInvalid {
            if (frame.data.isBlank()) return emptyList()
            val root = runCatching { aiJson.parseToJsonElement(frame.data).jsonObject }.getOrNull() ?: return invalidPayload()
            val type = root.checkedText("type") ?: frame.event ?: return emptyList()
            val events = mutableListOf<ChatEvent>()
            when (type) {
                "content_block_delta" -> {
                    val delta = root.checkedObject("delta", required = true) ?: return invalidPayload()
                    when (delta.checkedText("type", required = true)) {
                        "text_delta" -> delta.checkedText("text", required = true)?.takeIf { it.isNotEmpty() }?.let { events.add(ChatEvent.TextDelta(it)) }
                        "input_json_delta" -> {
                            val index = root.checkedIndex("index", required = true) ?: return invalidPayload()
                            val partial = delta.checkedText("partial_json", required = true) ?: return invalidPayload()
                            accumulator.accept(index, callId = null, name = null, argumentsDelta = partial)
                            events.add(ChatEvent.ToolDelta(index = index, argumentsDelta = partial))
                        }
                    }
                }
                "content_block_start" -> {
                    val block = root.checkedObject("content_block", required = true) ?: return invalidPayload()
                    if (block.checkedText("type", required = true) == "tool_use") {
                        val index = root.checkedIndex("index", required = true) ?: return invalidPayload()
                        val callId = block.checkedText("id", required = true)?.takeIf { it.isNotBlank() } ?: return invalidPayload()
                        val name = block.checkedText("name", required = true)?.takeIf { it.isNotBlank() } ?: return invalidPayload()
                        val initialInput = block.checkedObject("input")?.takeIf { it.isNotEmpty() }?.toString()
                        accumulator.accept(index, callId = callId, name = name, argumentsDelta = initialInput)
                        events.add(ChatEvent.ToolDelta(index = index, callId = callId, name = name))
                    }
                }
                "content_block_stop" -> {
                    val index = root.checkedIndex("index", required = true) ?: return invalidPayload()
                    accumulator.markComplete(index)
                }
                "message_delta" -> {
                    root.checkedObject("delta")?.checkedText("stop_reason", allowNull = true)?.let {
                        if (accumulator.finishOnce()) events.add(ChatEvent.Finish(finishReasonOf(it)))
                    }
                    root.obj("usage")?.let { usage ->
                        val totals = accumulator.usage(usage.int("input_tokens"), usage.int("output_tokens"))
                        events.add(ChatEvent.Usage(totals.first, totals.second))
                    }
                }
                "message_start" -> {
                    root.checkedObject("message", required = true)?.checkedObject("usage")?.let { usage ->
                        val totals = accumulator.usage(usage.int("input_tokens"), usage.int("output_tokens"))
                        events.add(ChatEvent.Usage(totals.first, totals.second))
                    }
                }
                "message_stop" -> {
                    accumulator.buildComplete().forEach { call ->
                        events.add(ChatEvent.ToolComplete(call.callId, call.name, call.argumentsJson))
                    }
                }
                "error" -> {
                    val error = root.obj("error")
                    events.add(ChatEvent.ProviderError(code = error?.str("type") ?: "PROVIDER_ERROR", retryable = false))
                }
            }
            return events
        }
    }

    override fun decodeNonStream(body: String): List<ChatEvent> {
        return eventsOrInvalid {
            val root = runCatching { aiJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return invalidPayload()
            root.obj("error")?.let { return listOf(ChatEvent.ProviderError(it.str("type") ?: "PROVIDER_ERROR", false)) }
            val content = root.checkedArray("content", required = true) ?: return invalidPayload()
            val events = mutableListOf<ChatEvent>()
            content.forEach { element ->
                val block = element as? JsonObject ?: return invalidPayload()
                when (block.checkedText("type", required = true)) {
                    "text" -> block.checkedText("text", required = true)?.takeIf { it.isNotEmpty() }?.let { events.add(ChatEvent.TextDelta(it)) }
                    "tool_use" -> {
                        val callId = block.checkedText("id", required = true)?.takeIf { it.isNotBlank() } ?: return invalidPayload()
                        events.add(
                            ChatEvent.ToolComplete(
                                callId = callId,
                                name = block.checkedText("name", required = true)?.takeIf { it.isNotBlank() } ?: return invalidPayload(),
                                argumentsJson = block.checkedObject("input")?.toString() ?: "{}",
                            ),
                        )
                    }
                }
            }
            root.obj("usage")?.let { usage ->
                val input = usage.int("input_tokens")
                if (input != null) events.add(ChatEvent.Usage(input, usage.int("output_tokens") ?: 0))
            }
            root.checkedText("stop_reason", allowNull = true)?.let { events.add(ChatEvent.Finish(finishReasonOf(it))) }
            return events
        }
    }

    override fun buildModelsRequest(endpoint: FinalEndpoint, apiKey: String?): KtorTransport.Request =
        KtorTransport.Request(
            url = endpoint.modelsUrl(),
            method = "GET",
            headers = buildMap {
                apiKey?.takeIf { it.isNotBlank() }?.let { put("x-api-key", it) }
                put("anthropic-version", "2023-06-01")
            },
        )

    override fun decodeModels(body: String): List<String> {
        val root = runCatching { aiJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        return root.arr("data").orEmpty().mapNotNull { it.jsonObject.str("id") }
    }
}
