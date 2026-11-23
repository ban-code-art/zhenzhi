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
 * Gemini Native（generateContent / streamGenerateContent）。
 * parts、functionCall/functionResponse；优先 x-goog-api-key。
 */
class GeminiAdapter(
    private val model: String,
) : ChatAdapter {
    override val protocol = WireProtocol.GEMINI_NATIVE

    override fun buildChatRequest(request: ChatRequest, apiKey: String?, endpoint: FinalEndpoint): KtorTransport.Request {
        val url = endpoint.chatUrl(request.stream)
        val body = buildJsonObject {
            put("contents", buildGeminiContents(request))
            request.messages.filter { it.role == "system" }
                .takeIf { it.isNotEmpty() }
                ?.let { systemMessages ->
                    putJsonObject("systemInstruction") {
                        putJsonArray("parts") {
                            systemMessages.flatMap { it.parts.filterIsInstance<MessagePart.Text>() }.forEach { part ->
                                addJsonObject { put("text", part.text) }
                            }
                        }
                    }
                }
            if (request.tools.isNotEmpty() && request.toolPolicy != ToolPolicy.NONE) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            request.tools.forEach { tool ->
                                addJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", runCatching { aiJson.parseToJsonElement(tool.parametersJson) }.getOrElse { JsonObject(emptyMap()) })
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                request.budget.outputReserve.takeIf { it > 0 }?.let { put("maxOutputTokens", it) }
                request.responseSchema?.let { schema ->
                    put("responseMimeType", "application/json")
                    put("responseSchema", runCatching { aiJson.parseToJsonElement(schema) }.getOrElse { JsonObject(emptyMap()) })
                }
            }
        }
        return KtorTransport.Request(
            url = url,
            headers = buildMap {
                apiKey?.takeIf { it.isNotBlank() }?.let { put("x-goog-api-key", it) }
            },
            body = body.toString(),
            stream = request.stream,
        )
    }

    private fun buildGeminiContents(request: ChatRequest) = buildJsonArray {
        val functionNames = mutableMapOf<String, String>()
        request.messages.filter { it.role != "system" }.forEach { message ->
            addJsonObject {
                put("role", if (message.role == "assistant") "model" else "user")
                putJsonArray("parts") {
                    val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
                    if (text.isNotEmpty() && message.toolCallId == null) {
                        addJsonObject { put("text", text) }
                    }
                    message.toolCalls.forEach { call ->
                        functionNames[call.callId] = call.name
                        addJsonObject {
                            putJsonObject("functionCall") {
                                put("name", call.name)
                                put("args", runCatching { aiJson.parseToJsonElement(call.argumentsJson) }.getOrElse { JsonObject(emptyMap()) })
                            }
                        }
                    }
                    message.toolCallId?.let { callId ->
                        addJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", requireNotNull(functionNames[callId]) { "Gemini tool result has no matching function call" })
                                putJsonObject("response") { put("content", text) }
                            }
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
            val events = mutableListOf<ChatEvent>()
            root.obj("error")?.let { error ->
                return listOf(ChatEvent.ProviderError(code = error.str("status") ?: "PROVIDER_ERROR", retryable = false))
            }
            val candidate = root.checkedArray("candidates")?.firstOrNull() as? JsonObject
            candidate?.checkedObject("content")?.checkedArray("parts", required = true)?.forEachIndexed { index, element ->
                val part = element as? JsonObject ?: return invalidPayload()
                part.checkedText("text")?.takeIf { it.isNotEmpty() }?.let { events.add(ChatEvent.TextDelta(it)) }
                part.checkedObject("functionCall")?.let { call ->
                    val name = call.checkedText("name", required = true)?.takeIf { it.isNotBlank() } ?: return invalidPayload()
                    val args = call.checkedObject("args")?.toString() ?: "{}"
                    val explicitId = call.checkedText("id")
                    val toolIndex = accumulator.identityIndex(explicitId, "gemini:$index:$name:$args", null, name, args)
                    val callId = explicitId ?: "gemini-$name-$toolIndex"
                    accumulator.accept(toolIndex, callId = callId, name = name, argumentsDelta = null)
                    accumulator.completeArguments(toolIndex, args)
                    accumulator.buildComplete().forEach { events.add(ChatEvent.ToolComplete(it.callId, it.name, it.argumentsJson)) }
                }
            }
            root.obj("usageMetadata")?.let { usage ->
                val totals = accumulator.usage(usage.int("promptTokenCount"), usage.int("candidatesTokenCount"))
                events.add(ChatEvent.Usage(totals.first, totals.second))
            }
            candidate?.checkedText("finishReason")?.let { if (accumulator.finishOnce()) events.add(ChatEvent.Finish(finishReasonOf(it))) }
            return events
        }
    }

    override fun decodeNonStream(body: String): List<ChatEvent> {
        return eventsOrInvalid {
            val root = runCatching { aiJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return invalidPayload()
            root.obj("error")?.let { return listOf(ChatEvent.ProviderError(it.str("status") ?: "PROVIDER_ERROR", false)) }
            val events = mutableListOf<ChatEvent>()
            val candidate = root.checkedArray("candidates", required = true)?.firstOrNull() as? JsonObject ?: return invalidPayload()
            candidate.checkedObject("content")?.checkedArray("parts", required = true)?.forEachIndexed { index, element ->
                val part = element as? JsonObject ?: return invalidPayload()
                part.checkedText("text")?.takeIf { it.isNotEmpty() }?.let { events.add(ChatEvent.TextDelta(it)) }
                part.checkedObject("functionCall")?.let { call ->
                    val name = call.checkedText("name", required = true)?.takeIf { it.isNotBlank() } ?: return invalidPayload()
                    val args = call.checkedObject("args")?.toString() ?: "{}"
                    events.add(ChatEvent.ToolComplete("gemini-$name-$index", name, args))
                }
            }
            root.obj("usageMetadata")?.let { usage ->
                val input = usage.int("promptTokenCount")
                if (input != null) events.add(ChatEvent.Usage(input, usage.int("candidatesTokenCount") ?: 0))
            }
            candidate.checkedText("finishReason")?.let { events.add(ChatEvent.Finish(finishReasonOf(it))) }
            return events
        }
    }

    override fun buildModelsRequest(endpoint: FinalEndpoint, apiKey: String?): KtorTransport.Request =
        KtorTransport.Request(
            url = endpoint.modelsUrl(),
            method = "GET",
            headers = buildMap {
                apiKey?.takeIf { it.isNotBlank() }?.let { put("x-goog-api-key", it) }
            },
        )

    override fun decodeModels(body: String): List<String> {
        val root = runCatching { aiJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        return root.arr("models").orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj.str("name")?.removePrefix("models/") ?: return@mapNotNull null
            // 按 supportedGenerationMethods 过滤（生成能力）
            val methods = obj.arr("supportedGenerationMethods")?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList()
            if (methods.isEmpty() || methods.any { it == "generateContent" || it == "streamGenerateContent" }) name else null
        }
    }
}
