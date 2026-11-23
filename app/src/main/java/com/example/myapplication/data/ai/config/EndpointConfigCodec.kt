package com.example.myapplication.data.ai.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Persisted endpoint metadata lives in paramsJson; provider parameters exclude reserved fields. */
object EndpointConfigCodec {
    enum class Mode { LEGACY_BASE, BASE, EXACT_ENDPOINT }

    data class Configuration(
        val mode: Mode,
        val operationUrls: Map<EndpointOperation, String> = emptyMap(),
    )

    data class Decoded(val params: JsonObject, val configuration: Configuration)

    sealed interface DecodeResult {
        data class Ok(val decoded: Decoded) : DecodeResult
        data class Invalid(val code: String, val reason: String) : DecodeResult
    }

    fun decode(paramsJson: String): DecodeResult {
        val params = try {
            Json.parseToJsonElement(paramsJson) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return invalid("paramsJson 必须是合法 JSON 对象")
        if (!params.containsKey("_endpointConfig")) {
            return DecodeResult.Ok(Decoded(params, Configuration(Mode.LEGACY_BASE)))
        }
        val config = params["_endpointConfig"] as? JsonObject
            ?: return invalid("_endpointConfig 必须是对象")
        if (config.keys != setOf("version", "mode", "operationUrls")) {
            return invalid("_endpointConfig 字段缺失或未知")
        }
        val version = config["version"] as? JsonPrimitive
        if (version == null || version.isString || version.content != "1") {
            return invalid("不支持的 endpoint 配置版本")
        }
        val modeValue = config["mode"] as? JsonPrimitive
        if (modeValue == null || !modeValue.isString) return invalid("mode 必须是字符串")
        val mode = when (modeValue.content) {
            "BASE" -> Mode.BASE
            "EXACT_ENDPOINT" -> Mode.EXACT_ENDPOINT
            else -> return invalid("未知 endpoint mode")
        }
        val overrides = config["operationUrls"] as? JsonObject
            ?: return invalid("operationUrls 必须是对象")
        val urls = linkedMapOf<EndpointOperation, String>()
        overrides.forEach { (name, value) ->
            val operation = EndpointOperation.entries.firstOrNull { it.name == name }
                ?: return invalid("未知 endpoint 操作")
            val primitive = value as? JsonPrimitive
            if (primitive == null || !primitive.isString) return invalid("操作地址必须是字符串")
            urls[operation] = primitive.content
        }
        return DecodeResult.Ok(Decoded(params, Configuration(mode, urls)))
    }

    fun providerParams(paramsJson: String): JsonObject {
        val decoded = decode(paramsJson)
        require(decoded is DecodeResult.Ok) { "INVALID_ENDPOINT_CONFIG" }
        return providerParams(decoded.decoded.params)
    }

    internal fun providerParams(params: JsonObject): JsonObject = JsonObject(params.filterKeys {
        !it.startsWith("_endpoint") && it != "_runtimeEndpointsV1"
    })

    private fun invalid(reason: String) = DecodeResult.Invalid("INVALID_ENDPOINT_CONFIG", reason)
}
