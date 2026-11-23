package com.example.myapplication.domain.ai

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * 四种 wire protocol（公共契约 §7）。
 * "OpenAI 兼容"是端点预设，映射到前两者之一，不是独立第五种协议。
 */
enum class WireProtocol {
    OPENAI_CHAT,
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES,
    GEMINI_NATIVE,
    ;

    companion object {
        fun fromStorage(value: String): WireProtocol? = entries.firstOrNull { it.name == value }
    }
}

enum class ProfilePurpose {
    CHAT,
    EMBEDDING,
    RERANK,
    SEARCH,
    ;

    companion object {
        fun fromStorage(value: String): ProfilePurpose? = entries.firstOrNull { it.name == value }
    }
}

/**
 * 端点规范化：用于 fingerprint 与请求构造的同一实现。
 * 规则：trim、去尾部重复 slash、保留编码 path、拒绝非 HTTP(S)/userinfo/query 碎片/空 host。
 */
object EndpointNormalizer {
    sealed interface Result {
        data class Ok(val normalized: String, val host: String) : Result
        data class Invalid(val reason: String) : Result
    }

    fun normalize(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Invalid("endpoint 为空")
        if (trimmed.any { it.code < 32 || it.code == 127 }) {
            return Result.Invalid("endpoint 不允许控制字符")
        }
        val uri = try {
            URI(trimmed).parseServerAuthority()
        } catch (_: URISyntaxException) {
            return Result.Invalid("endpoint 地址格式无效")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return Result.Invalid("仅支持 http/https 绝对地址")
        if (uri.rawUserInfo != null) return Result.Invalid("不允许 userinfo")
        if (uri.rawQuery != null || uri.rawFragment != null) return Result.Invalid("endpoint 不允许 query/fragment")
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return Result.Invalid("缺少 host")
        if (uri.port > 65535) return Result.Invalid("endpoint 端口无效")
        val authority = uri.rawAuthority ?: return Result.Invalid("缺少 host")
        // URI 仅负责校验/分离 host；不调用 normalize() 或重建 URI，避免改写
        // 合法历史 BASE 的编码、dot segments、内部斜线和显式端口。
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return Result.Ok("$scheme://${authority.lowercase(Locale.ROOT)}$path", host.lowercase(Locale.ROOT))
    }
}

/**
 * capability fingerprint：用户 namespace + 规范 endpoint + protocol + model + 维度策略 + 预处理版本。
 * 不含密钥明文；换 key 失效 capability cache 由调用方另行处理。
 */
object ProfileFingerprint {
    fun of(
        userId: Long,
        normalizedEndpoint: String,
        protocol: WireProtocol,
        model: String,
        dimensionPolicy: String = "default",
        preprocessingVersion: String = "v1",
    ): String {
        val material = listOf(
            "user:$userId",
            "endpoint:$normalizedEndpoint",
            "protocol:${protocol.name}",
            "model:$model",
            "dim:$dimensionPolicy",
            "preprocess:$preprocessingVersion",
        ).joinToString("\u0000")
        return Sha256.hex(material)
    }
}

/**
 * 授权记录（ai_consents.endpoint_hash）使用的 endpoint 身份。
 * 只绑定规范化 endpoint，不绑定 key/model，换 key 不丢授权、换 host 必须重新授权。
 */
object EndpointHash {
    fun of(normalizedEndpoint: String): String = Sha256.hex("endpoint\u0000$normalizedEndpoint")
}

internal object Sha256 {
    fun hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 能力画像：由实际 probe 产生，不用名称猜。
 * 每项独立记录 UNTESTED/PASS/FAIL/UNSUPPORTED。
 */
data class CapabilityProfile(
    val schemaVersion: Int = 1,
    val generation: CapabilityStatus = CapabilityStatus.UNTESTED,
    val streaming: CapabilityStatus = CapabilityStatus.UNTESTED,
    val toolCalling: CapabilityStatus = CapabilityStatus.UNTESTED,
    val structuredOutput: CapabilityStatus = CapabilityStatus.UNTESTED,
    val embedding: CapabilityStatus = CapabilityStatus.UNTESTED,
    val rerank: CapabilityStatus = CapabilityStatus.UNTESTED,
    val search: CapabilityStatus = CapabilityStatus.UNTESTED,
    val supportsTemperature: Boolean = true,
    val supportsMaxTokens: Boolean = true,
    val contextWindowTokens: Int? = null,
    val embeddingDim: Int? = null,
    val notes: List<String> = emptyList(),
) {
    enum class CapabilityStatus { UNTESTED, PASS, FAIL, UNSUPPORTED }
}

/** 已保存的模型配置（domain 视图，不含密钥明文）。 */
data class ModelProfile(
    val id: String,
    val userId: Long,
    val purpose: ProfilePurpose,
    val protocol: WireProtocol,
    val endpoint: String,
    val model: String,
    val secretRef: String?,
    val paramsJson: String,
    val capabilities: CapabilityProfile,
    val capabilityProfileHash: String,
    val enabled: Boolean,
    val configRevision: Long,
)

/**
 * 运行快照：冻结本次运行的端点/模型/wire/参数与 secret 版本引用，不存 key。
 * 默认模型变化不修改在途 snapshot；撤权/secret 失效仍中断新调用。
 */
data class RunProfileSnapshot(
    val schemaVersion: Int = 1,
    val profileId: String,
    val configRevision: Long,
    val protocol: WireProtocol,
    val endpointHash: String,
    val endpoint: String,
    val model: String,
    val paramsJson: String,
    val capabilitiesHash: String,
    val secretVersionRef: String?,
)
