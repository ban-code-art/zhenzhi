package com.example.myapplication.data.ai.adapters

import com.example.myapplication.data.ai.net.KtorTransport
import com.example.myapplication.data.ai.config.FinalEndpoint
import com.example.myapplication.data.ai.protocol.SseFrameParser
import com.example.myapplication.data.ai.protocol.ToolCallAccumulator
import com.example.myapplication.domain.ai.ChatEvent
import com.example.myapplication.domain.ai.ChatRequest
import com.example.myapplication.domain.ai.FinishReason
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
 * OpenAI Chat Completions（/chat/completions）。
 * message/delta/tool_calls；Bearer 认证，本地端点可留空 key。
 *
 * model 来自快照；每次请求显式接收已冻结的最终地址。
 */
class OpenAiChatAdapter(
    private val model: String,
) : ChatAdapter {
    override val protocol = WireProtocol.OPENAI_CHAT

    override fun buildChatRequest(request: ChatRequest, apiKey: String?, endpoint: FinalEndpoint): KtorTransport.Request {
        val body = buildJsonObject {
            put("model", model)
            put("stream", request.stream)
            put("messages", buildOpenAiMessages(request))
            buildOpenAiTools(request)?.let { put("tools", it) }
            request.responseSchema?.let { schema ->
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", "response")
                        put("schema", runCatching { aiJson.parseToJsonElement(schema) }.getOrElse { JsonObject(emptyMap()) })
                    }
                }
            }
            request.budget.outputReserve.takeIf { it > 0 }?.let { put("max_tokens", it) }
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

    override fun decodeStreamFrame(frame: SseFrameParser.Frame, accumulator: ToolCallAccumulator): List<ChatEvent> {
        return eventsOrInvalid {
            if (frame.data.isBlank()) return emptyList()
            if (frame.data.trim() == "[DONE]") return if (accumulator.finishOnce()) {
                listOf(ChatEvent.Finish(if (accumulator.hasToolCalls()) FinishReason.TOOL_CALLS else FinishReason.STOP))
            } else emptyList()
            val root = runCatching { aiJson.parseToJsonElement(frame.data).jsonObject }.getOrNull() ?: return invalidPayload()
            root.obj("error")?.let { error ->
                val code = error.str("code") ?: error.str("type")
                return listOf(ChatEvent.ProviderError(code = code ?: "PROVIDER_ERROR", retryable = false))
            }
            val events = mutableListOf<ChatEvent>()
            val choice = root.checkedArray("choices")?.firstOrNull() as? JsonObject
            if (choice == null) {
                root.obj("usage")?.let { usage ->
                    val totals = accumulator.usage(usage.int("prompt_tokens"), usage.int("completion_tokens"))
                    events.add(ChatEvent.Usage(totals.first, totals.second))
                }
                return events
            }
            val delta = choice.checkedObject("delta")
            delta?.checkedText("content", allowNull = true)?.takeIf { it.isNotEmpty() }?.let { events.add(ChatEvent.TextDelta(it)) }
            delta?.checkedArray("tool_calls")?.forEach { element ->
                val call = element as? JsonObject ?: return invalidPayload()
                val index = call.checkedIndex("index") ?: 0
                val function = call.checkedObject("function")
                accumulator.accept(
                    index = index,
                    callId = call.checkedText("id"),
                    name = function?.checkedText("name"),
                    argumentsDelta = function?.checkedText("arguments"),
                )
                events.add(
                    ChatEvent.ToolDelta(
                        index = index,
                        callId = call.str("id"),
                        name = function?.str("name"),
                        argumentsDelta = function?.str("arguments"),
                    ),
                )
            }
            choice.checkedText("finish_reason", allowNull = true)?.let { reason ->
                if (reason == "tool_calls") {
                    // finish_reason 表示服务端已发完所有参数分片：先标记完成再组装
                    accumulator.incompleteIndices().forEach { accumulator.markComplete(it) }
                    accumulator.buildComplete().forEach { call ->
                        events.add(ChatEvent.ToolComplete(call.callId, call.name, call.argumentsJson))
                    }
                }
                if (accumulator.finishOnce()) events.add(ChatEvent.Finish(finishReasonOf(reason)))
            }
            root.obj("usage")?.let { usage ->
                val totals = accumulator.usage(usage.int("prompt_tokens"), usage.int("completion_tokens"))
                events.add(ChatEvent.Usage(totals.first, totals.second))
            }
            return events
        }
    }

    override fun decodeNonStream(body: String): List<ChatEvent> {
        return eventsOrInvalid {
            val root = runCatching { aiJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return invalidPayload()
            root.obj("error")?.let { return listOf(ChatEvent.ProviderError(it.str("code") ?: it.str("type") ?: "PROVIDER_ERROR", false)) }
            val events = mutableListOf<ChatEvent>()
            val choice = root.checkedArray("choices", required = true)?.firstOrNull() as? JsonObject ?: return invalidPayload()
            val message = choice.checkedObject("message", required = true)
            message?.checkedText("content", allowNull = true)?.takeIf { it.isNotEmpty() }?.let { events.add(ChatEvent.TextDelta(it)) }
            message?.checkedArray("tool_calls")?.forEach { element ->
                val call = element as? JsonObject ?: return invalidPayload()
                val function = call.checkedObject("function", required = true)
                val callId = call.checkedText("id", required = true)?.takeIf { it.isNotBlank() } ?: return invalidPayload()
                events.add(
                    ChatEvent.ToolComplete(
                        callId = callId,
                        name = function?.checkedText("name", required = true)?.takeIf { it.isNotBlank() } ?: return invalidPayload(),
                        argumentsJson = function?.checkedText("arguments", required = true) ?: return invalidPayload(),
                    ),
                )
            }
            choice.checkedText("finish_reason", allowNull = true)?.let { events.add(ChatEvent.Finish(finishReasonOf(it))) }
            usageEvent(root.obj("usage"))?.let { events.add(it) }
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
