package com.example.myapplication.data.ai.adapters

import com.example.myapplication.data.ai.net.KtorTransport
import com.example.myapplication.data.ai.config.EndpointOperation
import com.example.myapplication.data.ai.config.FinalEndpoint
import com.example.myapplication.domain.ai.WireProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Embedding 适配器：OpenAI-compatible /embeddings（必需）+ Gemini embedContent（扩展）。
 * Anthropic 对话端点不能自动当 embedding 端点。
 */
class EmbeddingAdapter(
    private val protocol: WireProtocol,
    private val model: String,
) {
    data class EmbeddingResult(val index: Int, val vector: FloatArray)

    sealed interface Outcome {
        data class Ok(val vectors: List<EmbeddingResult>, val dim: Int) : Outcome
        data class Invalid(val reason: String) : Outcome
    }

    fun buildRequest(inputs: List<String>, apiKey: String?, endpoint: FinalEndpoint): KtorTransport.Request {
        require(endpoint.operation == EndpointOperation.EMBEDDING) { "Resolved endpoint is not an embedding resource" }
        require(protocol in setOf(WireProtocol.OPENAI_CHAT, WireProtocol.OPENAI_RESPONSES, WireProtocol.GEMINI_NATIVE)) {
            "Protocol does not support embedding"
        }
        return when (protocol) {
        WireProtocol.GEMINI_NATIVE -> {
            val body = buildJsonObject {
                putJsonArray("requests") {
                    inputs.forEach { text ->
                        addJsonObject {
                            put("model", "models/${model.removePrefix("models/")}")
                            putJsonObject("content") {
                                putJsonArray("parts") { addJsonObject { put("text", text) } }
                            }
                        }
                    }
                }
            }
            KtorTransport.Request(
                url = endpoint.url,
                headers = buildMap { apiKey?.takeIf { it.isNotBlank() }?.let { put("x-goog-api-key", it) } },
                body = body.toString(),
            )
        }
        else -> {
            val body = buildJsonObject {
                put("model", model)
                putJsonArray("input") { inputs.forEach { add(it) } }
            }
            KtorTransport.Request(
                url = endpoint.url,
                headers = buildMap { apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") } },
                body = body.toString(),
            )
        }
        }
    }

    /**
     * 解析并校验：数量、index 顺序、维度一致、有限值、非零范数。
     * 任何一项不满足都拒收（对应 INVALID_PROVIDER_RESPONSE）。
     */
    fun decode(body: String): Outcome {
        val root = runCatching { aiJson.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return Outcome.Invalid("响应不是 JSON 对象")
        val data: JsonArray = when (protocol) {
            WireProtocol.GEMINI_NATIVE -> root["embeddings"] as? JsonArray ?: return Outcome.Invalid("缺少 embeddings")
            else -> root["data"] as? JsonArray ?: return Outcome.Invalid("缺少 data")
        }
        val vectors = mutableListOf<EmbeddingResult>()
        for ((position, element) in data.withIndex()) {
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return Outcome.Invalid("向量项不是对象")
            val index = (obj["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: position
            val values = obj["embedding"] as? JsonArray ?: obj["values"] as? JsonArray
                ?: return Outcome.Invalid("缺少 embedding 向量")
            val floats = FloatArray(values.size)
            for (i in values.indices) {
                val number = (values[i] as? JsonPrimitive)?.doubleOrNull
                    ?: return Outcome.Invalid("向量含非数值元素")
                floats[i] = number.toFloat()
            }
            if (floats.isEmpty()) return Outcome.Invalid("空向量")
            if (floats.any { !it.isFinite() }) return Outcome.Invalid("向量含 NaN/Inf")
            val norm = kotlin.math.sqrt(floats.sumOf { (it * it).toDouble() })
            if (norm <= 1e-9) return Outcome.Invalid("零范数向量")
            vectors.add(EmbeddingResult(index, floats))
        }
        if (vectors.isEmpty()) return Outcome.Invalid("空结果")
        val dim = vectors.first().vector.size
        if (vectors.any { it.vector.size != dim }) return Outcome.Invalid("维度不一致")
        val indices = vectors.map { it.index }.sorted()
        if (indices != indices.indices.toList()) return Outcome.Invalid("index 顺序非法")
        return Outcome.Ok(vectors.sortedBy { it.index }, dim)
    }
}
