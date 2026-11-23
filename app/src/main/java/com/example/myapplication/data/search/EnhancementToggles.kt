package com.example.myapplication.data.search

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * AI 增强开关（W5-10）：联网检索与 AI 评分复核都**默认关闭**，逐用户存、可撤回。
 *
 * 放在 DataStore 而不是 schema v6：两个开关是设备级偏好，不参与同步/统计，
 * 也不需要事务；为它加表会把 schema 版本推进一格，违反本期"不动 schema"的约束。
 */
interface EnhancementToggles {
    fun observeWebSearch(userId: Long): Flow<Boolean>

    suspend fun isWebSearchEnabled(userId: Long): Boolean

    suspend fun setWebSearchEnabled(userId: Long, enabled: Boolean)

    fun observeAiReview(userId: Long): Flow<Boolean>

    suspend fun isAiReviewEnabled(userId: Long): Boolean

    suspend fun setAiReviewEnabled(userId: Long, enabled: Boolean)

    /** 撤回全部增强能力（退出登录/清空数据时调用）。 */
    suspend fun revokeAll()
}

/** DataStore 实现：键按 userId 分桶，缺省 false——"没配过"就是"没授权"。 */
class DataStoreEnhancementToggles(
    private val context: Context,
) : EnhancementToggles {

    override fun observeWebSearch(userId: Long): Flow<Boolean> =
        context.aiEnhancementsDataStore.data.map { prefs -> prefs[webSearchKey(userId)] ?: false }

    override suspend fun isWebSearchEnabled(userId: Long): Boolean =
        context.aiEnhancementsDataStore.data.first()[webSearchKey(userId)] ?: false

    override suspend fun setWebSearchEnabled(userId: Long, enabled: Boolean) {
        context.aiEnhancementsDataStore.edit { prefs ->
            if (enabled) prefs[webSearchKey(userId)] = true else prefs.remove(webSearchKey(userId))
        }
    }

    override fun observeAiReview(userId: Long): Flow<Boolean> =
        context.aiEnhancementsDataStore.data.map { prefs -> prefs[aiReviewKey(userId)] ?: false }

    override suspend fun isAiReviewEnabled(userId: Long): Boolean =
        context.aiEnhancementsDataStore.data.first()[aiReviewKey(userId)] ?: false

    override suspend fun setAiReviewEnabled(userId: Long, enabled: Boolean) {
        context.aiEnhancementsDataStore.edit { prefs ->
            if (enabled) prefs[aiReviewKey(userId)] = true else prefs.remove(aiReviewKey(userId))
        }
    }

    override suspend fun revokeAll() {
        context.aiEnhancementsDataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(WEB_SEARCH_PREFIX) || it.name.startsWith(AI_REVIEW_PREFIX) }
                .forEach { prefs.remove(it) }
        }
    }

    private fun webSearchKey(userId: Long) = booleanPreferencesKey("$WEB_SEARCH_PREFIX$userId")

    private fun aiReviewKey(userId: Long) = booleanPreferencesKey("$AI_REVIEW_PREFIX$userId")

    private companion object {
        const val WEB_SEARCH_PREFIX = "web_search_enabled_"
        const val AI_REVIEW_PREFIX = "ai_review_enabled_"
    }
}

private val Context.aiEnhancementsDataStore by preferencesDataStore(name = "ai_enhancements")

/** 测试/预览用内存实现：语义与 DataStore 版一致（缺省 false、撤销即删键）。 */
class InMemoryEnhancementToggles(
    private val webSearch: MutableSet<Long> = mutableSetOf(),
    private val aiReview: MutableSet<Long> = mutableSetOf(),
) : EnhancementToggles {
    override fun observeWebSearch(userId: Long): Flow<Boolean> =
        kotlinx.coroutines.flow.flowOf(userId in webSearch)

    override suspend fun isWebSearchEnabled(userId: Long): Boolean = userId in webSearch

    override suspend fun setWebSearchEnabled(userId: Long, enabled: Boolean) {
        if (enabled) webSearch += userId else webSearch -= userId
    }

    override fun observeAiReview(userId: Long): Flow<Boolean> =
        kotlinx.coroutines.flow.flowOf(userId in aiReview)

    override suspend fun isAiReviewEnabled(userId: Long): Boolean = userId in aiReview

    override suspend fun setAiReviewEnabled(userId: Long, enabled: Boolean) {
        if (enabled) aiReview += userId else aiReview -= userId
    }

    override suspend fun revokeAll() {
        webSearch.clear()
        aiReview.clear()
    }
}
