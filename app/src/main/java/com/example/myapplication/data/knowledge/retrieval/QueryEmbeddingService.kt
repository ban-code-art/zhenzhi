package com.example.myapplication.data.knowledge.retrieval

import com.example.myapplication.data.ai.adapters.EmbeddingAdapter
import com.example.myapplication.data.ai.config.ProfileRepository
import com.example.myapplication.data.ai.config.ProfileRuntime
import com.example.myapplication.data.ai.config.EndpointOperation
import com.example.myapplication.data.ai.config.EndpointResolver
import com.example.myapplication.data.ai.config.FinalEndpoint
import com.example.myapplication.data.ai.net.HttpExecutor
import com.example.myapplication.data.knowledge.index.VectorCodec
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.domain.ai.ProfilePurpose
import com.example.myapplication.domain.ai.WireProtocol
import com.example.myapplication.domain.reader.sha256Hex
import kotlinx.coroutines.CancellationException

/**
 * 查询向量提供者（设计 §7 / §14）。
 *
 * 规则：
 *  - 只使用用户当前授权且启用的 embedding profile；未授权/密文缺失返回 null（降级为词法），
 *    绝不伪造向量，也不把失败包装成“没找到”；
 *  - 查询向量走 5min TTL 缓存，key 含 user/profile/preprocessing；
 *  - 取消原样传播，不折为普通失败。
 */
class QueryEmbeddingService(
    private val database: AppDatabase,
    private val profileRuntime: ProfileRuntime,
    private val profileRepository: ProfileRepository,
    private val transport: HttpExecutor,
    private val cache: QueryVectorCache = QueryVectorCache(),
    private val clock: () -> Long = System::currentTimeMillis,
) : HybridRetriever.QueryEmbeddingProvider {

    override suspend fun embed(userId: Long, query: String, profileFingerprint: String): FloatArray? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        if (profileFingerprint.isBlank()) return null

        val key = CacheNamespace.queryVector(
            userId = userId,
            queryHash = sha256Hex(trimmed),
            profileFingerprint = profileFingerprint,
            preprocessingVersion = PREPROCESSING_VERSION,
        )
        cache.get(key)?.let { return it }

        val profile = resolveProfile(userId, profileFingerprint) ?: return null
        if (!profileRuntime.isAuthorized(userId, ProfilePurpose.EMBEDDING, profile.endpoint, profile.paramsJson)) return null
        val apiKey = profileRepository.resolveSecret(profile.secretRef)
        if (profile.secretRef != null && apiKey == null) return null

        val adapter = EmbeddingAdapter(profile.protocol, profile.model)
        val response = try {
            transport.execute(adapter.buildRequest(listOf(trimmed), apiKey, profile.finalEndpoint))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return null
        }
        if (response.status !in 200..299) return null
        if (!profileRuntime.isAuthorized(userId, ProfilePurpose.EMBEDDING, profile.endpoint, profile.paramsJson)) return null
        val decoded = adapter.decode(response.body)
        if (decoded !is EmbeddingAdapter.Outcome.Ok) return null
        val vector = decoded.vectors.firstOrNull()?.vector ?: return null
        if (vector.size != decoded.dim || vector.isEmpty()) return null
        if (vector.any { !it.isFinite() }) return null
        val norm = VectorCodec.normOf(vector)
        if (norm <= 0.0) return null

        cache.put(key, vector)
        return vector
    }

    /** 缓存驱逐入口（登出/配置切换由调用方触发）。 */
    fun evictUser(userId: Long) = cache.evictUser(userId)

    private suspend fun resolveProfile(userId: Long, fingerprint: String): Resolved? {
        val candidates = database.aiProfileDao().findProfiles(userId, ProfilePurpose.EMBEDDING.name)
        for (entity in candidates) {
            if (!entity.enabled) continue
            val protocol = WireProtocol.fromStorage(entity.protocol) ?: continue
            val computed = EndpointResolver.embeddingFingerprint(
                userId = userId,
                endpoint = entity.endpoint,
                protocol = protocol,
                model = entity.model,
                paramsJson = entity.paramsJson,
            ) ?: continue
            if (computed == fingerprint) {
                val resolved = EndpointResolver.resolve(protocol, ProfilePurpose.EMBEDDING, entity.endpoint,
                    entity.paramsJson, EndpointOperation.EMBEDDING, entity.model)
                if (resolved !is EndpointResolver.Resolution.Ok) continue
                return Resolved(protocol, entity.model, entity.endpoint, entity.secretRef, entity.paramsJson, resolved.endpoint)
            }
        }
        return null
    }

    private data class Resolved(
        val protocol: WireProtocol,
        val model: String,
        val endpoint: String,
        val secretRef: String?,
        val paramsJson: String,
        val finalEndpoint: FinalEndpoint,
    )

    companion object {
        const val PREPROCESSING_VERSION = "v1"
    }
}
