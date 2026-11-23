package com.example.myapplication.data.knowledge.index

import androidx.room.withTransaction
import com.example.myapplication.data.ai.adapters.EmbeddingAdapter
import com.example.myapplication.data.ai.config.ProfileRepository
import com.example.myapplication.data.ai.config.ProfileRuntime
import com.example.myapplication.data.ai.config.EndpointOperation
import com.example.myapplication.data.ai.config.EndpointResolver
import com.example.myapplication.data.ai.config.FinalEndpoint
import com.example.myapplication.data.ai.net.HttpExecutor
import com.example.myapplication.data.ai.protocol.AiErrorClassifier
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.KnowledgeEmbeddingEntity
import com.example.myapplication.data.local.entity.KnowledgeVectorStateEntity
import com.example.myapplication.domain.ai.ProfilePurpose
import com.example.myapplication.domain.ai.WireProtocol
import kotlinx.coroutines.CancellationException

/**
 * VECTOR 增强（设计 §6/§13 / FX-24）。
 *
 * 流程（每批）：
 *  1. claim 租约（CAS）
 *  2. 取该代次尚无该 profile 向量的块（batch 16，按 ordinal 稳定）
 *  3. 复核授权（consent + secret）+ 解析密钥；未授权直接 BLOCKED，不外发
 *  4. 网络调用（不持 DB 写锁）
 *  5. 返回后复核 source/generation/profile/consent/lease；任一变则整批丢弃
 *  6. 短事务写 embeddings + 重算 vector_states 计数（不盲目 += 批量）
 *
 * ready_count 由 SQL 重新统计，重试同一批不会重复累计。
 */
class VectorIndexer(
    private val database: AppDatabase,
    private val lease: IndexLease,
    private val profileRuntime: ProfileRuntime,
    private val profileRepository: ProfileRepository,
    private val transport: HttpExecutor,
    private val maxBatchesPerRun: Int = MAX_BATCHES_PER_RUN,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Outcome {
        data class Done(val readyCount: Int, val expectedCount: Int) : Outcome
        data class Progress(val readyCount: Int, val expectedCount: Int) : Outcome
        data class StaleClaim(val reason: String) : Outcome
        data class Retryable(val errorCode: String, val retryAt: Long) : Outcome
        data class Blocked(val errorCode: String) : Outcome
        data class Cancelled(val reason: String) : Outcome
    }

    suspend fun processVectorJob(jobId: String, userId: Long): Outcome {
        val claimed = lease.tryClaim(jobId) ?: return Outcome.StaleClaim("CLAIM_FAILED")
        return try {
            runVector(claimed, userId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Outcome.Retryable("IO_FAILURE", clock() + RETRY_DELAY_MILLIS)
        }
    }

    private suspend fun runVector(claimed: IndexLease.Claimed, userId: Long): Outcome {
        val job = claimed.job
        if (job.userId != userId) return Outcome.StaleClaim("OWNER_MISMATCH")
        if (job.phase != IndexOutbox.PHASE_VECTOR) return Outcome.StaleClaim("WRONG_PHASE")
        val profileFingerprint = job.profileFingerprint
        if (profileFingerprint.isBlank()) return Outcome.Blocked("MISSING_PROFILE")

        val knowledgeDoc = database.knowledgeDocumentDao().find(job.knowledgeDocumentId)
            ?: return Outcome.StaleClaim("KNOWLEDGE_DOC_MISSING")
        if (knowledgeDoc.userId != userId) return Outcome.StaleClaim("OWNER_MISMATCH")
        // VECTOR 只对已发布的 active generation 写；staging 代次无向量
        if (knowledgeDoc.activeGeneration != job.requestedGeneration) {
            return Outcome.StaleClaim("NOT_ACTIVE_GENERATION")
        }
        if (knowledgeDoc.sourceRevision != job.targetRevision) {
            return Outcome.StaleClaim("SOURCE_CHANGED")
        }
        lease.markRunning(claimed)

        // 解析 profile：fingerprint 必须能反查到当前用户的一个 embedding 配置
        val indexIdentity = VectorIndexProfile.parse(profileFingerprint)
            ?: return Outcome.Blocked("INVALID_INDEX_PROFILE")
        val profile = resolveProfile(userId, indexIdentity.providerFingerprint)
            ?: return Outcome.Blocked("PROFILE_MISSING")
        if (!profileRuntime.isAuthorized(userId, ProfilePurpose.EMBEDDING, profile.endpoint, profile.paramsJson)) {
            return Outcome.Blocked("CONSENT_REVOKED")
        }
        // 密文缺失（换机/恢复）要求重新填写，不带着空 key 请求后误报鉴权失败
        if (profile.secretRef != null && profileRepository.resolveSecret(profile.secretRef) == null) {
            return Outcome.Blocked("SECRET_MISSING")
        }
        val apiKey = profileRepository.resolveSecret(profile.secretRef)

        val chunkDao = database.knowledgeChunkDao()
        val embeddingDao = database.knowledgeEmbeddingDao()
        val expectedCount = chunkDao.countGeneration(job.knowledgeDocumentId, job.requestedGeneration)
        if (expectedCount == 0) return Outcome.Blocked("NO_CHUNKS")
        // Encoding is part of the queued index-profile identity. A setting change during an
        // in-flight batch cannot silently mix FLOAT32 and quantized rows in the same namespace.
        val selectedEncoding = indexIdentity.encoding

        var processedAny = false
        var batches = 0
        while (batches < maxBatchesPerRun) {
            val missing = chunkDao.findMissingEmbeddings(
                knowledgeDocumentId = job.knowledgeDocumentId,
                generation = job.requestedGeneration,
                profileFingerprint = profileFingerprint,
                limit = BATCH_SIZE,
            )
            if (missing.isEmpty()) break

            // 每批前复核租约与授权：撤权立即停止，不再外发
            if (lease.checkPublishable(claimed) !is IndexLease.PublishCheck.Ok) {
                return Outcome.StaleClaim("STALE_CLAIM")
            }
            if (!profileRuntime.isAuthorized(userId, ProfilePurpose.EMBEDDING, profile.endpoint, profile.paramsJson)) {
                return Outcome.Blocked("CONSENT_REVOKED")
            }

            val request = EmbeddingAdapter(profile.protocol, profile.model)
                .buildRequest(missing.map { it.embeddingText }, apiKey, profile.finalEndpoint)
            val response = transport.execute(request)
            if (response.status !in 200..299) {
                val error = AiErrorClassifier.fromHttp(
                    status = response.status,
                    retryAfterMs = AiErrorClassifier.parseRetryAfter(response.headers["Retry-After"], clock()),
                )
                return when (error.kind) {
                    com.example.myapplication.domain.ai.AiErrorKind.AUTH ->
                        Outcome.Blocked("AUTH_BLOCKED")
                    com.example.myapplication.domain.ai.AiErrorKind.RATE_LIMIT ->
                        Outcome.Retryable("RATE_LIMITED", error.retryAtMs ?: clock() + RETRY_DELAY_MILLIS)
                    com.example.myapplication.domain.ai.AiErrorKind.TRANSIENT ->
                        Outcome.Retryable("TRANSIENT", error.retryAtMs ?: clock() + RETRY_DELAY_MILLIS)
                    else -> Outcome.Blocked("INVALID_REQUEST")
                }
            }

            val decoded = when (val outcome = EmbeddingAdapter(profile.protocol, profile.model).decode(response.body)) {
                is EmbeddingAdapter.Outcome.Invalid -> return Outcome.Blocked("INVALID_PROVIDER_RESPONSE")
                is EmbeddingAdapter.Outcome.Ok -> outcome
            }
            // 数量与顺序必须与请求一一对应；乱序/缺项整批拒收
            if (decoded.vectors.size != missing.size) return Outcome.Blocked("VECTOR_COUNT_MISMATCH")
            if (decoded.vectors.map { it.index } != missing.indices.toList()) {
                return Outcome.Blocked("VECTOR_ORDER_MISMATCH")
            }
            val capabilityDim = profile.capabilities.embeddingDim
            if (capabilityDim != null && capabilityDim != decoded.dim) {
                return Outcome.Blocked("DIMENSION_CHANGED")
            }

            // 返回后复核：源、代次、profile、consent、lease 任一变化都不得入库
            val current = database.knowledgeDocumentDao().find(job.knowledgeDocumentId)
                ?: return Outcome.StaleClaim("KNOWLEDGE_DOC_MISSING")
            if (current.activeGeneration != job.requestedGeneration) return Outcome.StaleClaim("GENERATION_CHANGED")
            if (current.sourceRevision != job.targetRevision) return Outcome.StaleClaim("SOURCE_CHANGED")
            if (lease.checkPublishable(claimed) !is IndexLease.PublishCheck.Ok) {
                return Outcome.StaleClaim("STALE_CLAIM")
            }
            if (!profileRuntime.isAuthorized(userId, ProfilePurpose.EMBEDDING, profile.endpoint, profile.paramsJson)) {
                return Outcome.Blocked("CONSENT_REVOKED")
            }

            val now = clock()
            val rows = missing.mapIndexedNotNull { position, chunk ->
                val vector = decoded.vectors[position].vector
                val encoded = VectorCodec.encodeResult(vector, selectedEncoding) ?: return@mapIndexedNotNull null
                KnowledgeEmbeddingEntity(
                    chunkId = chunk.id,
                    profileFingerprint = profileFingerprint,
                    dim = decoded.dim,
                    encoding = encoded.encoding,
                    vectorBlob = encoded.vectorBlob,
                    norm = encoded.norm,
                    quantizationParams = encoded.quantizationParams,
                    createdAt = now,
                )
            }
            if (rows.isEmpty()) return Outcome.Blocked("NO_VALID_VECTOR")

            val readyCount = database.withTransaction {
                embeddingDao.upsertAll(rows)
                val ready = embeddingDao.countForGeneration(
                    job.knowledgeDocumentId,
                    job.requestedGeneration,
                    profileFingerprint,
                )
                val state = if (ready >= expectedCount) "READY" else "PENDING"
                database.knowledgeVectorStateDao().upsert(
                    KnowledgeVectorStateEntity(
                        knowledgeDocumentId = job.knowledgeDocumentId,
                        generation = job.requestedGeneration,
                        profileFingerprint = profileFingerprint,
                        expectedCount = expectedCount,
                        readyCount = ready,
                        state = state,
                        updatedAt = now,
                    ),
                )
                // vector_state 只是当前选中 profile 的 UI 汇总，不替代 vector_states 权威
                database.knowledgeDocumentDao().updateVectorState(job.knowledgeDocumentId, state, null)
                ready
            }
            processedAny = true
            batches += 1
            if (readyCount >= expectedCount) {
                lease.finish(claimed, status = "DONE", progressJson = progressJson(readyCount, expectedCount))
                return Outcome.Done(readyCount, expectedCount)
            }
        }

        val readyCount = embeddingDao.countForGeneration(
            job.knowledgeDocumentId,
            job.requestedGeneration,
            profileFingerprint,
        )
        if (readyCount >= expectedCount) {
            lease.finish(claimed, status = "DONE", progressJson = progressJson(readyCount, expectedCount))
            return Outcome.Done(readyCount, expectedCount)
        }
        // 还有缺口：交回队列下一轮继续（不标成功，也不丢进度）
        lease.retryLater(
            claimed,
            retryAt = clock() + RETRY_DELAY_MILLIS,
            errorCode = if (processedAny) "PARTIAL_BATCH_BUDGET" else "NO_PROGRESS",
            progressJson = progressJson(readyCount, expectedCount),
        )
        return Outcome.Progress(readyCount, expectedCount)
    }

    private suspend fun resolveProfile(userId: Long, fingerprint: String): ResolvedEmbeddingProfile? {
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
                return ResolvedEmbeddingProfile(
                    protocol = protocol,
                    model = entity.model,
                    endpoint = entity.endpoint,
                    secretRef = entity.secretRef,
                    capabilities = decodeCapabilities(entity.capabilitiesJson),
                    paramsJson = entity.paramsJson,
                    finalEndpoint = resolved.endpoint,
                )
            }
        }
        return null
    }

    private fun decodeCapabilities(json: String): com.example.myapplication.domain.ai.CapabilityProfile {
        if (json.isBlank() || json == "{}") return com.example.myapplication.domain.ai.CapabilityProfile()
        return runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
            fun status(name: String): com.example.myapplication.domain.ai.CapabilityProfile.CapabilityStatus {
                val raw = (root[name] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: return com.example.myapplication.domain.ai.CapabilityProfile.CapabilityStatus.UNTESTED
                return com.example.myapplication.domain.ai.CapabilityProfile.CapabilityStatus.entries
                    .firstOrNull { it.name == raw }
                    ?: com.example.myapplication.domain.ai.CapabilityProfile.CapabilityStatus.UNTESTED
            }
            com.example.myapplication.domain.ai.CapabilityProfile(
                generation = status("generation"),
                streaming = status("streaming"),
                toolCalling = status("toolCalling"),
                structuredOutput = status("structuredOutput"),
                embedding = status("embedding"),
                rerank = status("rerank"),
                search = status("search"),
                embeddingDim = (root["embeddingDim"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull(),
            )
        }.getOrElse { com.example.myapplication.domain.ai.CapabilityProfile() }
    }

    private fun progressJson(ready: Int, expected: Int): String =
        """{"ready":$ready,"expected":$expected}"""

    private data class ResolvedEmbeddingProfile(
        val protocol: WireProtocol,
        val model: String,
        val endpoint: String,
        val secretRef: String?,
        val capabilities: com.example.myapplication.domain.ai.CapabilityProfile,
        val paramsJson: String,
        val finalEndpoint: FinalEndpoint,
    )

    companion object {
        const val BATCH_SIZE = 16
        const val MAX_BATCHES_PER_RUN = 64
        const val RETRY_DELAY_MILLIS = 60_000L
    }
}
