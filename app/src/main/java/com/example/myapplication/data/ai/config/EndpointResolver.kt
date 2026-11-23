package com.example.myapplication.data.ai.config

import com.example.myapplication.domain.ai.ModelProfile
import com.example.myapplication.domain.ai.ProfilePurpose
import com.example.myapplication.domain.ai.RunProfileSnapshot
import com.example.myapplication.domain.ai.WireProtocol
import com.example.myapplication.domain.ai.EndpointNormalizer
import com.example.myapplication.domain.ai.ProfileFingerprint
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The single owner of final URLs. Neither adapters nor restored runs infer paths. */
object EndpointResolver {
    sealed interface Resolution {
        data class Ok(val endpoint: FinalEndpoint) : Resolution
        data class Invalid(val code: String, val reason: String) : Resolution
        data class Unsupported(val operation: EndpointOperation) : Resolution
    }

    sealed interface ConfigurationResult {
        data class Ok(val endpoint: String, val paramsJson: String, val configHash: String) : ConfigurationResult
        data class Invalid(val code: String, val reason: String) : ConfigurationResult
    }

    sealed interface SnapshotResult {
        data class Ok(val snapshot: RunProfileSnapshot) : SnapshotResult
        data class Invalid(val code: String, val reason: String) : SnapshotResult
    }

    fun resolve(profile: ModelProfile, operation: EndpointOperation, model: String = profile.model,
        stream: Boolean = false): Resolution = resolve(profile.protocol, profile.purpose,
        profile.endpoint, profile.paramsJson, operation, model, stream)

    fun resolve(protocol: WireProtocol, purpose: ProfilePurpose, endpoint: String, paramsJson: String,
        operation: EndpointOperation, model: String, stream: Boolean = false): Resolution {
        // Operation is explicit. The historical stream flag cannot turn an EXACT single URL into a stream URL.
        val validated = validate(protocol, purpose, endpoint, paramsJson, model,
            requireModel = operation != EndpointOperation.MODELS)
        if (validated is Validation.Invalid) return invalid(validated.reason)
        return resolveValidated((validated as Validation.Ok).config, protocol, purpose, operation, model)
    }

    fun normalizeConfiguration(protocol: WireProtocol, purpose: ProfilePurpose, endpoint: String,
        paramsJson: String, model: String, requireModel: Boolean = true): ConfigurationResult =
        when (val result = validate(protocol, purpose, endpoint, paramsJson, model, requireModel)) {
            is Validation.Invalid -> ConfigurationResult.Invalid("INVALID_ENDPOINT_CONFIG", result.reason)
            is Validation.Ok -> ConfigurationResult.Ok(result.config.endpoint,
                result.config.params.toString(), result.config.hash)
        }

    fun resolveSnapshot(snapshot: RunProfileSnapshot, purpose: ProfilePurpose,
        operation: EndpointOperation): Resolution {
        val validated = validate(snapshot.protocol, purpose, snapshot.endpoint, snapshot.paramsJson,
            snapshot.model, requireModel = operation != EndpointOperation.MODELS)
        if (validated is Validation.Invalid) return invalid(validated.reason)
        val config = (validated as Validation.Ok).config
        if (snapshot.schemaVersion != 1) return invalid("未知运行快照版本")
        if (!config.params.containsKey(RUNTIME_KEY)) {
            if (config.params.containsKey("_endpointConfig")) {
                return invalid("新配置运行快照缺少冻结操作地址")
            }
            // Genuinely old snapshots have neither reserved field and retain LEGACY_BASE semantics.
            return resolveValidated(config, snapshot.protocol, purpose, operation, snapshot.model)
        }
        val runtime = readRuntime(config, snapshot.protocol, purpose, snapshot.model)
        if (runtime is RuntimeResult.Invalid) return invalid(runtime.reason)
        val urls = (runtime as RuntimeResult.Ok).urls
        return urls[operation]?.let { Resolution.Ok(FinalEndpoint(it, operation, config.hash)) }
            ?: Resolution.Unsupported(operation)
    }

    fun freezeSnapshot(snapshot: RunProfileSnapshot, purpose: ProfilePurpose,
        operations: Set<EndpointOperation>): SnapshotResult {
        if (operations.isEmpty() || snapshot.schemaVersion != 1) {
            return SnapshotResult.Invalid("INVALID_ENDPOINT_CONFIG", "运行快照必须包含所需操作")
        }
        val validated = validate(snapshot.protocol, purpose, snapshot.endpoint, snapshot.paramsJson,
            snapshot.model, requireModel = operations.any { it != EndpointOperation.MODELS })
        if (validated is Validation.Invalid) return SnapshotResult.Invalid("INVALID_ENDPOINT_CONFIG", validated.reason)
        val config = (validated as Validation.Ok).config
        val urls = linkedMapOf<EndpointOperation, String>()
        for (operation in operations.sortedBy { it.name }) {
            val resolved = if (config.params.containsKey(RUNTIME_KEY)) resolveSnapshot(snapshot, purpose, operation)
                else resolveValidated(config, snapshot.protocol, purpose, operation, snapshot.model)
            when (resolved) {
                is Resolution.Ok -> urls[operation] = resolved.endpoint.url
                is Resolution.Invalid -> return SnapshotResult.Invalid(resolved.code, resolved.reason)
                is Resolution.Unsupported -> return SnapshotResult.Invalid("UNSUPPORTED_OPERATION", "所需操作未配置或不支持")
            }
        }
        val runtime = buildJsonObject {
            put("version", 1)
            put("configHash", config.hash)
            put("urls", buildJsonObject { urls.forEach { (operation, url) -> put(operation.name, url) } })
        }
        return SnapshotResult.Ok(snapshot.copy(paramsJson = JsonObject(config.params + (RUNTIME_KEY to runtime)).toString()))
    }

    fun embeddingFingerprint(userId: Long, protocol: WireProtocol, endpoint: String, model: String,
        paramsJson: String, dimensionPolicy: String = "default", preprocessingVersion: String = "v1"): String? {
        val resolved = resolve(protocol, ProfilePurpose.EMBEDDING, endpoint, paramsJson,
            EndpointOperation.EMBEDDING, model) as? Resolution.Ok ?: return null
        val legacyBase = (EndpointNormalizer.normalize(endpoint) as? EndpointNormalizer.Result.Ok)?.normalized
        val finalUrl = resolved.endpoint.url
        val candidates = linkedSetOf<String>()
        legacyBase?.let(candidates::add)
        // This reverse lookup serves fingerprint compatibility only. EXACT request resolution
        // never infers a base, a models URL or another operation from the configured resource.
        val legacySuffix = baseUrl(protocol, "", EndpointOperation.EMBEDDING, model)
        if (legacySuffix != null && finalUrl.endsWith(legacySuffix)) {
            val candidate = EndpointNormalizer.normalize(finalUrl.removeSuffix(legacySuffix))
            if (candidate is EndpointNormalizer.Result.Ok) candidates.add(candidate.normalized)
        }
        val equivalentLegacyBase = candidates.firstOrNull {
            baseUrl(protocol, it, EndpointOperation.EMBEDDING, model) == finalUrl
        }
        // Both legacy BASE and an equivalent EXACT resource use the existing partition identity.
        val identity = equivalentLegacyBase ?: "resolved-embedding:$finalUrl"
        return ProfileFingerprint.of(userId, identity, protocol, model, dimensionPolicy, preprocessingVersion)
    }

    /** Endpoint-only callers retain legacy normalization; params-aware callers preserve exact bytes. */
    internal fun authorizationIdentity(endpoint: String, paramsJson: String, purpose: ProfilePurpose): String? {
        val decoded = EndpointConfigCodec.decode(paramsJson) as? EndpointConfigCodec.DecodeResult.Ok ?: return null
        val exact = decoded.decoded.configuration.mode == EndpointConfigCodec.Mode.EXACT_ENDPOINT
        val identity = if (exact) {
            if (parseUrl(endpoint, allowQuery = true) != null) endpoint else null
        } else {
            if (hasControls(endpoint)) null else (EndpointNormalizer.normalize(endpoint) as? EndpointNormalizer.Result.Ok)?.normalized
        } ?: return null
        val origin = parseUrl(identity, allowQuery = exact) ?: return null
        for ((operation, url) in decoded.decoded.configuration.operationUrls) {
            val override = parseUrl(url, allowQuery = true) ?: return null
            if (!sameOrigin(origin, override) || exact && operation == primary(purpose) && url != identity) return null
        }
        return identity
    }

    internal fun endpointHost(endpoint: String): String? = parseUrl(endpoint, allowQuery = true)?.host?.lowercase(Locale.ROOT)

    private data class ValidatedConfig(val endpoint: String, val params: JsonObject,
        val metadata: EndpointConfigCodec.Configuration, val hash: String)
    private sealed interface Validation {
        data class Ok(val config: ValidatedConfig) : Validation
        data class Invalid(val reason: String) : Validation
    }

    private fun validate(protocol: WireProtocol, purpose: ProfilePurpose, endpoint: String,
        paramsJson: String, model: String, requireModel: Boolean): Validation {
        val decoded = when (val result = EndpointConfigCodec.decode(paramsJson)) {
            is EndpointConfigCodec.DecodeResult.Invalid -> return Validation.Invalid(result.reason)
            is EndpointConfigCodec.DecodeResult.Ok -> result.decoded
        }
        if (requireModel && model.isBlank()) return Validation.Invalid("model 不能为空")
        if (protocol == WireProtocol.GEMINI_NATIVE && (requireModel || model.isNotEmpty()) && !validModel(model)) {
            return Validation.Invalid("Gemini 模型必须是未编码的单段 ID")
        }
        val exact = decoded.configuration.mode == EndpointConfigCodec.Mode.EXACT_ENDPOINT
        if (hasControls(endpoint)) return Validation.Invalid("endpoint 不允许控制字符")
        val normalized = if (exact) endpoint else {
            (EndpointNormalizer.normalize(endpoint) as? EndpointNormalizer.Result.Ok)?.normalized
                ?: return Validation.Invalid("BASE 地址无效")
        }
        val root = parseUrl(normalized, allowQuery = exact) ?: return Validation.Invalid("endpoint 地址无效")
        for ((operation, url) in decoded.configuration.operationUrls) {
            val override = parseUrl(url, allowQuery = true) ?: return Validation.Invalid("操作覆盖地址无效")
            if (!sameOrigin(root, override)) return Validation.Invalid("操作覆盖地址必须同 origin")
            if (exact && operation == primary(purpose) && url != normalized) {
                return Validation.Invalid("主操作覆盖地址必须与 endpoint 完全相同")
            }
        }
        val hashParams = buildJsonObject {
            put("protocol", protocol.name); put("purpose", purpose.name); put("endpoint", normalized); put("model", model)
            put("mode", if (exact) "EXACT_ENDPOINT" else "BASE")
            put("operationUrls", buildJsonObject {
                decoded.configuration.operationUrls.forEach { (operation, url) -> put(operation.name, url) }
            })
            put("params", EndpointConfigCodec.providerParams(decoded.params))
        }
        return Validation.Ok(ValidatedConfig(normalized, decoded.params, decoded.configuration, sha256(canonical(hashParams))))
    }

    private fun resolveValidated(config: ValidatedConfig, protocol: WireProtocol, purpose: ProfilePurpose,
        operation: EndpointOperation, model: String): Resolution {
        if (!supported(protocol, purpose, operation)) return Resolution.Unsupported(operation)
        val url = config.metadata.operationUrls[operation] ?: if (config.metadata.mode == EndpointConfigCodec.Mode.EXACT_ENDPOINT) {
            if (operation == primary(purpose)) config.endpoint else null
        } else baseUrl(protocol, config.endpoint, operation, model)
        if (url == null) return Resolution.Unsupported(operation)
        if (parseUrl(url, allowQuery = true) == null) return invalid("最终操作地址无效")
        return Resolution.Ok(FinalEndpoint(url, operation, config.hash))
    }

    private fun primary(purpose: ProfilePurpose): EndpointOperation = when (purpose) {
        ProfilePurpose.CHAT -> EndpointOperation.CHAT_SINGLE
        ProfilePurpose.EMBEDDING -> EndpointOperation.EMBEDDING
        ProfilePurpose.RERANK -> EndpointOperation.RERANK
        ProfilePurpose.SEARCH -> EndpointOperation.SEARCH
    }

    private fun supported(protocol: WireProtocol, purpose: ProfilePurpose, operation: EndpointOperation): Boolean {
        if (purpose == ProfilePurpose.RERANK || purpose == ProfilePurpose.SEARCH) return false
        if (operation == EndpointOperation.RERANK || operation == EndpointOperation.SEARCH) return false
        if (purpose == ProfilePurpose.EMBEDDING && operation != EndpointOperation.EMBEDDING && operation != EndpointOperation.MODELS) return false
        if (purpose == ProfilePurpose.CHAT && operation == EndpointOperation.EMBEDDING) return false
        return operation != EndpointOperation.EMBEDDING || protocol != WireProtocol.ANTHROPIC_MESSAGES
    }

    private fun baseUrl(protocol: WireProtocol, endpoint: String, operation: EndpointOperation, model: String): String? {
        if (operation == EndpointOperation.RERANK || operation == EndpointOperation.SEARCH) return null
        val suffix = when (operation) {
            EndpointOperation.MODELS -> if (protocol == WireProtocol.GEMINI_NATIVE) "/models?pageSize=200" else "/models"
            EndpointOperation.EMBEDDING -> when (protocol) {
                WireProtocol.ANTHROPIC_MESSAGES -> return null
                WireProtocol.GEMINI_NATIVE -> "/models/${encodeModel(model)}:batchEmbedContents"
                else -> "/embeddings"
            }
            EndpointOperation.CHAT_SINGLE, EndpointOperation.CHAT_STREAM -> when (protocol) {
                WireProtocol.OPENAI_CHAT -> "/chat/completions"
                WireProtocol.OPENAI_RESPONSES -> "/responses"
                WireProtocol.ANTHROPIC_MESSAGES -> "/messages"
                WireProtocol.GEMINI_NATIVE -> "/models/${encodeModel(model)}:" +
                    if (operation == EndpointOperation.CHAT_STREAM) "streamGenerateContent?alt=sse" else "generateContent"
            }
            else -> return null
        }
        return endpoint + suffix
    }

    private sealed interface RuntimeResult {
        data class Ok(val urls: Map<EndpointOperation, String>) : RuntimeResult
        data class Invalid(val reason: String) : RuntimeResult
    }

    private fun readRuntime(config: ValidatedConfig, protocol: WireProtocol, purpose: ProfilePurpose, model: String): RuntimeResult {
        val root = config.params[RUNTIME_KEY] as? JsonObject ?: return RuntimeResult.Invalid("运行地址元数据必须是对象")
        if (root.keys != setOf("version", "configHash", "urls") || root["version"] != JsonPrimitive(1) ||
            root["configHash"] != JsonPrimitive(config.hash)) return RuntimeResult.Invalid("运行地址版本或配置 hash 不匹配")
        val stored = root["urls"] as? JsonObject ?: return RuntimeResult.Invalid("运行地址集合必须是对象")
        if (stored.isEmpty()) return RuntimeResult.Invalid("运行地址集合为空")
        val urls = linkedMapOf<EndpointOperation, String>()
        for ((name, value) in stored) {
            val operation = EndpointOperation.entries.firstOrNull { it.name == name }
                ?: return RuntimeResult.Invalid("未知运行地址操作")
            val primitive = value as? JsonPrimitive
            if (primitive == null || !primitive.isString) return RuntimeResult.Invalid("运行地址必须是字符串")
            val expected = resolveValidated(config, protocol, purpose, operation, model) as? Resolution.Ok
                ?: return RuntimeResult.Invalid("快照包含不支持的操作")
            if (primitive.content != expected.endpoint.url) return RuntimeResult.Invalid("冻结地址与配置不匹配")
            urls[operation] = primitive.content
        }
        return RuntimeResult.Ok(urls)
    }

    private fun parseUrl(raw: String, allowQuery: Boolean): URI? {
        if (raw.isEmpty() || hasControls(raw)) return null
        val uri = try { URI(raw).parseServerAuthority() } catch (_: URISyntaxException) { return null }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null ||
            uri.rawFragment != null || uri.port > 65535 || (!allowQuery && uri.rawQuery != null)) return null
        // Encoded controls are also unsafe, especially when proxies decode paths before routing.
        if (Regex("%0[0-9a-f]|%1[0-9a-f]|%7f", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return null
        for (part in uri.rawQuery.orEmpty().split('&', ';')) {
            val key = try { URLDecoder.decode(part.substringBefore('='), "UTF-8") } catch (_: IllegalArgumentException) { return null }
            val compact = key.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
            if (compact in SECRET_KEYS || hasControls(key)) return null
        }
        return uri
    }

    private fun sameOrigin(a: URI, b: URI): Boolean = a.scheme.equals(b.scheme, ignoreCase = true) &&
        a.host.equals(b.host, ignoreCase = true) && effectivePort(a) == effectivePort(b)
    private fun effectivePort(uri: URI): Int = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    private fun hasControls(value: String): Boolean = value.any { it.code < 32 || it.code == 127 }
    private fun validModel(model: String): Boolean = model.isNotBlank() && !hasControls(model) &&
        '/' !in model && '\\' !in model && '%' !in model && model != "." && model != ".."
    private fun encodeModel(model: String): String = URLEncoder.encode(model, "UTF-8").replace("+", "%20").replace("%7E", "~")
    private fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.keys.sorted().joinToString(",", "{", "}") { JsonPrimitive(it).toString() + ":" + canonical(element.getValue(it)) }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        else -> element.toString()
    }
    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun invalid(reason: String) = Resolution.Invalid("INVALID_ENDPOINT_CONFIG", reason)
    private const val RUNTIME_KEY = "_runtimeEndpointsV1"
    private val SECRET_KEYS = setOf("key", "apikey", "xapikey", "xgoogapikey", "token", "accesstoken",
        "refreshtoken", "authtoken", "oauthtoken", "authorization", "secret", "apisecret", "password", "credential")
}
