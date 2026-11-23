package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.AiConsentEntity
import com.example.myapplication.data.local.entity.AiProfileEntity
import com.example.myapplication.data.local.entity.AiUserSettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiProfileDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProfile(profile: AiProfileEntity)

    @Query("SELECT * FROM ai_profiles WHERE id = :profileId AND user_id = :userId LIMIT 1")
    suspend fun findProfile(userId: Long, profileId: String): AiProfileEntity?

    @Query("SELECT * FROM ai_profiles WHERE user_id = :userId AND purpose = :purpose ORDER BY updated_at DESC, id ASC")
    suspend fun findProfiles(userId: Long, purpose: String): List<AiProfileEntity>

    @Query("SELECT * FROM ai_profiles WHERE user_id = :userId ORDER BY purpose ASC, updated_at DESC, id ASC")
    fun observeProfiles(userId: Long): Flow<List<AiProfileEntity>>

    /**
     * 保存配置使用 CAS：只有当前 config_revision 匹配才更新，返回受影响行数。
     * 0 行表示并发修改，调用方返回 CONFLICT。
     */
    @Query(
        "UPDATE ai_profiles SET protocol = :protocol, endpoint = :endpoint, model = :model, " +
            "secret_ref = :secretRef, params_json = :paramsJson, capabilities_json = :capabilitiesJson, " +
            "capability_profile_hash = :capabilityProfileHash, enabled = :enabled, " +
            "config_revision = config_revision + 1, updated_at = :now " +
            "WHERE id = :profileId AND user_id = :userId AND config_revision = :expectedRevision",
    )
    suspend fun updateProfile(
        userId: Long,
        profileId: String,
        expectedRevision: Long,
        protocol: String,
        endpoint: String,
        model: String,
        secretRef: String?,
        paramsJson: String,
        capabilitiesJson: String,
        capabilityProfileHash: String,
        enabled: Boolean,
        now: Long,
    ): Int

    @Query("UPDATE ai_profiles SET capabilities_json = :capabilitiesJson, capability_profile_hash = :hash, updated_at = :now WHERE id = :profileId AND user_id = :userId")
    suspend fun updateCapabilities(userId: Long, profileId: String, capabilitiesJson: String, hash: String, now: Long): Int

    @Query("DELETE FROM ai_profiles WHERE id = :profileId AND user_id = :userId")
    suspend fun deleteProfile(userId: Long, profileId: String): Int

    /** 所有仍被 profile 引用的 secret_ref；secret GC 的引用扫描输入之一。 */
    @Query("SELECT secret_ref FROM ai_profiles WHERE secret_ref IS NOT NULL")
    suspend fun allSecretRefs(): List<String>

    @Query("SELECT * FROM ai_user_settings WHERE user_id = :userId LIMIT 1")
    suspend fun findSettings(userId: Long): AiUserSettingsEntity?

    @Query("SELECT * FROM ai_user_settings WHERE user_id = :userId LIMIT 1")
    fun observeSettings(userId: Long): Flow<AiUserSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSettings(settings: AiUserSettingsEntity): Long

    @Query(
        "UPDATE ai_user_settings SET active_chat_profile_id = :chatProfileId, " +
            "active_embedding_profile_id = :embeddingProfileId, active_rerank_profile_id = :rerankProfileId, " +
            "active_search_profile_id = :searchProfileId, network_search_enabled = :networkSearchEnabled, " +
            "budget_json = :budgetJson, settings_revision = settings_revision + 1 " +
            "WHERE user_id = :userId AND settings_revision = :expectedRevision",
    )
    suspend fun updateSettings(
        userId: Long,
        expectedRevision: Long,
        chatProfileId: String?,
        embeddingProfileId: String?,
        rerankProfileId: String?,
        searchProfileId: String?,
        networkSearchEnabled: Boolean,
        budgetJson: String,
    ): Int

    /**
     * 显式激活某个 profile（复用端点必须用户显式选择，不自动切换）。
     * 返回 0 行表示 settings 行缺失，调用方先 [insertSettings] 再重试。
     */
    @Query("UPDATE ai_user_settings SET active_chat_profile_id = :profileId, settings_revision = settings_revision + 1 WHERE user_id = :userId")
    suspend fun setActiveChatProfile(userId: Long, profileId: String?): Int

    @Query("UPDATE ai_user_settings SET active_embedding_profile_id = :profileId, settings_revision = settings_revision + 1 WHERE user_id = :userId")
    suspend fun setActiveEmbeddingProfile(userId: Long, profileId: String?): Int

    @Query("UPDATE ai_user_settings SET active_rerank_profile_id = :profileId, settings_revision = settings_revision + 1 WHERE user_id = :userId")
    suspend fun setActiveRerankProfile(userId: Long, profileId: String?): Int

    @Query("UPDATE ai_user_settings SET active_search_profile_id = :profileId, settings_revision = settings_revision + 1 WHERE user_id = :userId")
    suspend fun setActiveSearchProfile(userId: Long, profileId: String?): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConsent(consent: AiConsentEntity)

    @Query("SELECT * FROM ai_consents WHERE user_id = :userId AND purpose = :purpose AND endpoint_hash = :endpointHash LIMIT 1")
    suspend fun findConsent(userId: Long, purpose: String, endpointHash: String): AiConsentEntity?

    @Query("SELECT * FROM ai_consents WHERE user_id = :userId ORDER BY purpose ASC, endpoint_hash ASC")
    suspend fun findConsents(userId: Long): List<AiConsentEntity>

    @Query("DELETE FROM ai_consents WHERE user_id = :userId AND purpose = :purpose AND endpoint_hash = :endpointHash")
    suspend fun deleteConsent(userId: Long, purpose: String, endpointHash: String): Int
}
