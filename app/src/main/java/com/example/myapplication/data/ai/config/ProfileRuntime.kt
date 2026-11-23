package com.example.myapplication.data.ai.config

import com.example.myapplication.data.local.dao.AiProfileDao
import com.example.myapplication.data.local.dao.AiRunDao
import com.example.myapplication.data.local.entity.AiConsentEntity
import com.example.myapplication.domain.ai.EndpointHash
import com.example.myapplication.domain.ai.ProfilePurpose
import com.example.myapplication.domain.ai.RunProfileSnapshot
import com.example.myapplication.domain.ai.RunProfileSnapshotCodec

/**
 * W3-03：运行期 profile 解析与授权门禁。
 *
 * 规则（公共契约 §6 / FX-24）：
 *  - 保存新 profile 不改 active 设置，在途 run 继续用原 snapshot；
 *  - 每次请求前按“当前授权 + secret 版本”复核，撤权/禁用立即阻断，不再外发；
 *  - secret GC 的引用集合 = 所有 profile.secret_ref + 活跃 run 快照中的 secretVersionRef，
 *    活跃引用先于清理被保留（崩溃后引用扫描收敛）。
 */
class ProfileRuntime(
    private val profileDao: AiProfileDao,
    private val runDao: AiRunDao,
    private val secretStore: SecretStore,
    private val profileRepository: ProfileRepository,
) {
    sealed interface SecretResolution {
        /** [apiKey] 为 null 表示该端点明确允许无 key（本地模型）。 */
        data class Ok(val apiKey: String?) : SecretResolution
        /** 该端点未授权或已撤权：调用方不得发起请求。 */
        data object Revoked : SecretResolution
        /** 授权仍在但密钥密文缺失（恢复/换机）：要求重新输入，不静默降级。 */
        data object SecretMissing : SecretResolution
    }

    /** 保存草稿并登记该端点的外发授权（保存与授权同一用户动作）。 */
    suspend fun saveAuthorized(
        userId: Long,
        draft: ProfileRepository.Draft,
        now: Long,
        scopeJson: String = "{\"scope\":\"SAVED_PROFILE\"}",
        policyRevision: Long = 1,
    ): ProfileRepository.SaveResult {
        val result = profileRepository.save(userId, draft, now)
        if (result is ProfileRepository.SaveResult.Ok) {
            // Read the actual CAS result: an EXACT URL remains byte-stable and a BASE URL is normalized.
            val saved = profileRepository.find(userId, result.profileId)
            val identity = saved?.let { EndpointResolver.authorizationIdentity(it.endpoint, it.paramsJson, it.purpose) }
            if (saved != null && saved.configRevision == result.configRevision && identity != null) {
                profileDao.upsertConsent(
                    AiConsentEntity(
                        userId = userId,
                        purpose = draft.purpose.name,
                        endpointHash = EndpointHash.of(identity),
                        allowed = saved.enabled,
                        scopeJson = scopeJson,
                        policyRevision = policyRevision,
                        updatedAt = now,
                    ),
                )
            }
        }
        return result
    }

    /** 撤权：立即阻断后续请求；已发往服务商的内容不在本地可控范围。 */
    suspend fun revoke(userId: Long, purpose: ProfilePurpose, endpoint: String, paramsJson: String = "{}"): Boolean {
        val identity = EndpointResolver.authorizationIdentity(endpoint, paramsJson, purpose) ?: return false
        return profileDao.deleteConsent(userId, purpose.name, EndpointHash.of(identity)) > 0
    }

    suspend fun isAuthorized(userId: Long, purpose: ProfilePurpose, endpoint: String, paramsJson: String = "{}"): Boolean {
        val identity = EndpointResolver.authorizationIdentity(endpoint, paramsJson, purpose) ?: return false
        val consent = profileDao.findConsent(userId, purpose.name, EndpointHash.of(identity))
        return consent?.allowed == true
    }

    /**
     * 每轮调用前解析运行密钥。撤权、禁用或密文缺失都返回非 Ok，
     * 调用方据此取消运行，不得继续请求。
     */
    suspend fun resolveRunSecret(
        userId: Long,
        purpose: ProfilePurpose,
        snapshot: RunProfileSnapshot,
    ): SecretResolution {
        val primary = when (purpose) {
            ProfilePurpose.CHAT -> EndpointOperation.CHAT_SINGLE
            ProfilePurpose.EMBEDDING -> EndpointOperation.EMBEDDING
            ProfilePurpose.RERANK -> EndpointOperation.RERANK
            ProfilePurpose.SEARCH -> EndpointOperation.SEARCH
        }
        // Invalid frozen metadata blocks credentials even when the endpoint's consent still exists.
        if (EndpointResolver.resolveSnapshot(snapshot, purpose, primary) is EndpointResolver.Resolution.Invalid ||
            !isAuthorized(userId, purpose, snapshot.endpoint, snapshot.paramsJson)) return SecretResolution.Revoked
        val ref = snapshot.secretVersionRef ?: return SecretResolution.Ok(null)
        val plaintext = secretStore.load(ref) ?: return SecretResolution.SecretMissing
        return SecretResolution.Ok(plaintext)
    }

    /**
     * 崩溃/失败后的引用扫描收敛：保留所有 profile 引用与活跃 run 快照引用，
     * 回收其余 secret 文件。返回被回收的引用。
     */
    suspend fun convergeSecrets(): List<String> {
        val referenced = profileDao.allSecretRefs().toMutableSet()
        runDao.activeSnapshotJsons().forEach { json ->
            val snapshot = runCatching { RunProfileSnapshotCodec.decode(json) }.getOrNull()
            snapshot?.secretVersionRef?.let(referenced::add)
        }
        return secretStore.gc(referenced)
    }
}
