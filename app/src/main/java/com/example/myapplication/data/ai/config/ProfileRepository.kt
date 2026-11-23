package com.example.myapplication.data.ai.config

import com.example.myapplication.data.local.dao.AiProfileDao
import com.example.myapplication.data.local.entity.AiProfileEntity
import com.example.myapplication.domain.ai.CapabilityProfile
import com.example.myapplication.domain.ai.ModelProfile
import com.example.myapplication.domain.ai.ProfilePurpose
import com.example.myapplication.domain.ai.WireProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 配置仓库：草稿、测试与保存分离。
 *
 * 保存顺序（跨两种存储的分阶段引用切换，不是假原子）：
 *  1. 写新 secretVersion
 *  2. Room CAS configRevision + secret_ref
 *  3. 失败回收新未引用 secret；成功延迟 GC 旧 secret
 *
 * 测试只用草稿快照，不改变当前在用 profile。
 */
class ProfileRepository(
    private val profileDao: AiProfileDao,
    private val secretStore: SecretStore,
) {
    data class Draft(
        val id: String?,
        val purpose: ProfilePurpose,
        val protocol: WireProtocol,
        val endpoint: String,
        val model: String,
        val apiKey: String?,
        val paramsJson: String = "{}",
        val capabilities: CapabilityProfile = CapabilityProfile(),
        val enabled: Boolean = true,
        val baseRevision: Long = 0,
        /** null/空 apiKey + false 为 Keep；null + true 为 Clear；非空 + true 无效。 */
        val clearApiKey: Boolean = false,
    )

    sealed interface SaveResult {
        data class Ok(val profileId: String, val configRevision: Long) : SaveResult
        data class Invalid(val reason: String) : SaveResult
        data class Conflict(val currentRevision: Long) : SaveResult
        data class Failed(val reason: String) : SaveResult
    }

    fun observeProfiles(userId: Long): Flow<List<ModelProfile>> =
        profileDao.observeProfiles(userId).map { list -> list.map { it.toDomain() } }

    suspend fun profiles(userId: Long, purpose: ProfilePurpose): List<ModelProfile> =
        profileDao.findProfiles(userId, purpose.name).map { it.toDomain() }

    suspend fun find(userId: Long, profileId: String): ModelProfile? =
        profileDao.findProfile(userId, profileId)?.toDomain()

    /** 保存草稿：endpoint 规范化 → secret 落位 → Room CAS。 */
    suspend fun save(userId: Long, draft: Draft, now: Long): SaveResult {
        val configuration = when (val result = EndpointResolver.normalizeConfiguration(
            draft.protocol, draft.purpose, draft.endpoint, draft.paramsJson, draft.model,
        )) {
            is EndpointResolver.ConfigurationResult.Invalid -> return SaveResult.Invalid(result.reason)
            is EndpointResolver.ConfigurationResult.Ok -> result
        }
        val newHost = EndpointResolver.endpointHost(configuration.endpoint)
            ?: return SaveResult.Invalid("endpoint 地址无效")
        // Runtime URL sets belong to individual runs, never to an editable saved profile.
        val persistedParams = kotlinx.serialization.json.JsonObject(
            kotlinx.serialization.json.Json.parseToJsonElement(configuration.paramsJson)
                .let { it as kotlinx.serialization.json.JsonObject }.filterKeys { it != "_runtimeEndpointsV1" },
        ).toString()
        if (draft.clearApiKey && !draft.apiKey.isNullOrEmpty()) {
            return SaveResult.Invalid("清除密钥不能同时提供新密钥")
        }

        val profileId = draft.id ?: "profile-${java.util.UUID.randomUUID()}"
        // 失败仅清理本次新版本；Keep 选择的旧引用绝不能误删。
        var allocatedSecretRef: String? = null
        var databaseWriteStarted = false
        try {
            val existing = draft.id?.let { profileDao.findProfile(userId, it) }
            if (draft.id != null && existing == null && draft.baseRevision > 0) {
                return SaveResult.Conflict(0)
            }
            if (existing != null && existing.purpose != draft.purpose.name) {
                return SaveResult.Invalid("已保存配置不能更改用途")
            }
            val oldSecretRef = existing?.secretRef
            val oldHost = existing?.endpoint?.let(EndpointResolver::endpointHost)
            val keepKey = !draft.clearApiKey && draft.apiKey.isNullOrEmpty()
            if (keepKey && oldSecretRef != null && oldHost != newHost) {
                return SaveResult.Invalid("更换 host 需要输入新密钥或明确清除密钥")
            }

            val newSecretRef = when {
                draft.clearApiKey -> null
                keepKey -> oldSecretRef
                else -> when (val stored = secretStore.store(userId, draft.purpose.name, profileId, draft.apiKey!!)) {
                    is SecretStore.Result.Ok -> stored.ref.also { allocatedSecretRef = it }
                    is SecretStore.Result.Failure -> return SaveResult.Failed(stored.reason)
                }
            }
            val capabilitiesJson = encodeCapabilities(draft.capabilities)
            val capabilityHash = hashCapabilities(capabilitiesJson)
            currentCoroutineContext().ensureActive()

            if (existing == null) {
                databaseWriteStarted = true
                profileDao.insertProfile(
                    AiProfileEntity(
                        id = profileId,
                        userId = userId,
                        purpose = draft.purpose.name,
                        protocol = draft.protocol.name,
                        endpoint = configuration.endpoint,
                        model = draft.model,
                        secretRef = newSecretRef,
                        paramsJson = persistedParams,
                        capabilitiesJson = capabilitiesJson,
                        capabilityProfileHash = capabilityHash,
                        enabled = draft.enabled,
                        configRevision = 1,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                return SaveResult.Ok(profileId, 1)
            }

            databaseWriteStarted = true
            val affected = profileDao.updateProfile(
                userId = userId,
                profileId = profileId,
                expectedRevision = draft.baseRevision,
                protocol = draft.protocol.name,
                endpoint = configuration.endpoint,
                model = draft.model,
                secretRef = newSecretRef,
                paramsJson = persistedParams,
                capabilitiesJson = capabilitiesJson,
                capabilityProfileHash = capabilityHash,
                enabled = draft.enabled,
                now = now,
            )
            if (affected == 0) {
                discardUnreferencedNewSecret(allocatedSecretRef)
                allocatedSecretRef = null
                val current = profileDao.findProfile(userId, profileId)
                return SaveResult.Conflict(current?.configRevision ?: 0)
            }
            // Replace/Clear 成功都不在这里删除旧版本：它可能仍被活跃 run 引用。
            // ProfileRuntime.convergeSecrets() 按 profile + 活跃 run 引用延迟收敛。
            return SaveResult.Ok(profileId, draft.baseRevision + 1)
        } catch (cancelled: CancellationException) {
            if (!databaseWriteStarted) discardUnreferencedNewSecret(allocatedSecretRef)
            throw cancelled
        } catch (failure: Exception) {
            if (!databaseWriteStarted) discardUnreferencedNewSecret(allocatedSecretRef)
            return SaveResult.Failed("配置写入失败：${failure.javaClass.simpleName}")
        }
    }

    // DAO 调用后的异常/取消无法判定提交：新版本可能已进入活跃 run 快照，
    // 仅 allSecretRefs 不足以排除引用，因此留给 ProfileRuntime 的全引用 GC。

    /** 仅明确 CAS 未更新或尚未开始写入时清新版本，在取消之外核实 profile 引用。 */
    private suspend fun discardUnreferencedNewSecret(ref: String?) {
        if (ref == null) return
        withContext(NonCancellable) {
            try {
                if (ref !in profileDao.allSecretRefs()) secretStore.delete(ref)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 无法核实提交结果/立即删除时保留版本，交引用扫描 GC 收敛。
            }
        }
    }

    /**
     * 显式激活某个已保存 profile 作为该用途的当前配置。
     * 只改 active 指针，不改 profile 内容、不删旧向量；settings 行缺失时补建。
     */
    suspend fun activate(userId: Long, purpose: ProfilePurpose, profileId: String): Boolean {
        val profile = profileDao.findProfile(userId, profileId) ?: return false
        if (profile.purpose != purpose.name || !profile.enabled) return false
        profileDao.insertSettings(
            com.example.myapplication.data.local.entity.AiUserSettingsEntity(userId = userId),
        )
        val changed = when (purpose) {
            ProfilePurpose.CHAT -> profileDao.setActiveChatProfile(userId, profileId)
            ProfilePurpose.EMBEDDING -> profileDao.setActiveEmbeddingProfile(userId, profileId)
            ProfilePurpose.RERANK -> profileDao.setActiveRerankProfile(userId, profileId)
            ProfilePurpose.SEARCH -> profileDao.setActiveSearchProfile(userId, profileId)
        }
        return changed > 0
    }

    suspend fun saveCapabilities(userId: Long, profileId: String, capabilities: CapabilityProfile, now: Long): Boolean {
        val json = encodeCapabilities(capabilities)
        return profileDao.updateCapabilities(userId, profileId, json, hashCapabilities(json), now) > 0
    }

    suspend fun delete(userId: Long, profileId: String): Boolean {
        val affected = profileDao.deleteProfile(userId, profileId)
        // 密文由引用扫描回收：删除 profile 不得立即断掉
        // 仍在使用该 secretVersionRef 的在途 run。
        return affected > 0
    }

    /** 读取密钥明文：仅调用方（网络层）在请求构建时使用，不写日志/不持久化。 */
    fun resolveSecret(secretRef: String?): String? = secretRef?.let { secretStore.load(it) }

    private fun AiProfileEntity.toDomain(): ModelProfile = ModelProfile(
        id = id,
        userId = userId,
        purpose = ProfilePurpose.fromStorage(purpose) ?: ProfilePurpose.CHAT,
        protocol = WireProtocol.fromStorage(protocol) ?: WireProtocol.OPENAI_CHAT,
        endpoint = endpoint,
        model = model,
        secretRef = secretRef,
        paramsJson = paramsJson,
        capabilities = decodeCapabilities(capabilitiesJson),
        capabilityProfileHash = capabilityProfileHash,
        enabled = enabled,
        configRevision = configRevision,
    )

    private fun encodeCapabilities(capabilities: CapabilityProfile): String = buildString {
        append('{')
        append("\"schemaVersion\":").append(capabilities.schemaVersion).append(',')
        append("\"generation\":\"").append(capabilities.generation.name).append("\",")
        append("\"streaming\":\"").append(capabilities.streaming.name).append("\",")
        append("\"toolCalling\":\"").append(capabilities.toolCalling.name).append("\",")
        append("\"structuredOutput\":\"").append(capabilities.structuredOutput.name).append("\",")
        append("\"embedding\":\"").append(capabilities.embedding.name).append("\",")
        append("\"rerank\":\"").append(capabilities.rerank.name).append("\",")
        append("\"search\":\"").append(capabilities.search.name).append("\",")
        append("\"supportsTemperature\":").append(capabilities.supportsTemperature).append(',')
        append("\"supportsMaxTokens\":").append(capabilities.supportsMaxTokens).append(',')
        append("\"contextWindowTokens\":").append(capabilities.contextWindowTokens ?: "null").append(',')
        append("\"embeddingDim\":").append(capabilities.embeddingDim ?: "null").append(',')
        append("\"notes\":[")
        capabilities.notes.forEachIndexed { index, note ->
            if (index > 0) append(',')
            append('"').append(note.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
        }
        append("]}")
    }

    private fun decodeCapabilities(json: String): CapabilityProfile {
        if (json.isBlank() || json == "{}") return CapabilityProfile()
        return runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(json).let { it as kotlinx.serialization.json.JsonObject }
            fun status(name: String): CapabilityProfile.CapabilityStatus {
                val raw = (root[name] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return CapabilityProfile.CapabilityStatus.UNTESTED
                return CapabilityProfile.CapabilityStatus.entries.firstOrNull { it.name == raw }
                    ?: CapabilityProfile.CapabilityStatus.UNTESTED
            }
            CapabilityProfile(
                schemaVersion = (root["schemaVersion"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 1,
                generation = status("generation"),
                streaming = status("streaming"),
                toolCalling = status("toolCalling"),
                structuredOutput = status("structuredOutput"),
                embedding = status("embedding"),
                rerank = status("rerank"),
                search = status("search"),
                supportsTemperature = (root["supportsTemperature"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBoolean() ?: true,
                supportsMaxTokens = (root["supportsMaxTokens"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBoolean() ?: true,
                contextWindowTokens = (root["contextWindowTokens"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull(),
                embeddingDim = (root["embeddingDim"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull(),
                // notes 由 probe 写入，必须能读回：否则“能力画像 + 诊断说明”只有写没有读。
                notes = (root["notes"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    .orEmpty(),
            )
        }.getOrElse { CapabilityProfile() }
    }

    private fun hashCapabilities(json: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
