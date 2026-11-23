package com.example.myapplication.data.encyclopedia

import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.EncyclopediaFavoriteEntity
import com.example.myapplication.data.review.ReviewCandidate
import com.example.myapplication.data.review.ReviewRepository
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.session.SessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface EncyclopediaMutationOutcome<out T> {
    data class Success<T>(val value: T) : EncyclopediaMutationOutcome<T>
    data object SessionChanged : EncyclopediaMutationOutcome<Nothing>
    data class InvalidInput(val message: String) : EncyclopediaMutationOutcome<Nothing>
}

enum class EncyclopediaReviewAddStatus {
    ADDED,
    ALREADY_EXISTS,
    INVALID_POINT,
    SESSION_CHANGED,
}

/**
 * 经学百科个人状态仓库：
 * - 按账号隔离穴位收藏（(userId, pointCode) 唯一约束、幂等写入）；
 * - 使用 [SessionManager.withCurrentIdentity] 校验操作前后的当前身份，会话变化或退出后丢弃结果；
 * - 将穴位词条转换为独立来源命名空间 `meridian.acupoint` 交由现有 [ReviewRepository] 排程。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EncyclopediaUserRepository(
    private val database: AppDatabase,
    private val sessions: SessionManager,
    private val reviewRepository: ReviewRepository,
    private val packRepository: EncyclopediaPackRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observeFavoriteCodes(): Flow<Set<String>> =
        sessions.identity.flatMapLatest { id ->
            val userId = id.userId
            if (userId == null || userId <= 0L) {
                flowOf(emptySet())
            } else {
                database.encyclopediaFavoriteDao().observePointCodes(userId).map { it.toSet() }
            }
        }

    fun observeReviewPointCodes(): Flow<Set<String>> =
        sessions.identity.flatMapLatest { id ->
            val userId = id.userId
            if (userId == null || userId <= 0L) {
                flowOf(emptySet())
            } else {
                database.reviewDao().observeSourceRefsByNamespace(userId, REVIEW_NAMESPACE).map { refs ->
                    refs.asSequence()
                        .mapNotNull { raw ->
                            runCatching {
                                (Json.parseToJsonElement(raw) as? JsonObject)
                                    ?.get("id")?.jsonPrimitive?.contentOrNull
                            }.getOrNull()
                        }
                        .filter { it.isNotBlank() }
                        .toSet()
                }
            }
        }

    suspend fun loadFavoriteCodes(identity: SessionIdentity): EncyclopediaMutationOutcome<Set<String>> {
        val userId = identity.userId?.takeIf { it > 0L } ?: return EncyclopediaMutationOutcome.SessionChanged
        val codes = sessions.withCurrentIdentity(identity) {
            database.encyclopediaFavoriteDao().getByUser(userId).map { it.pointCode }.toSet()
        } ?: return EncyclopediaMutationOutcome.SessionChanged
        return EncyclopediaMutationOutcome.Success(codes)
    }

    suspend fun addFavorite(
        identity: SessionIdentity,
        pointCode: String,
        snapshot: EncyclopediaSnapshot? = packRepository.currentSnapshot,
    ): EncyclopediaMutationOutcome<Boolean> {
        val userId = identity.userId?.takeIf { it > 0L } ?: return EncyclopediaMutationOutcome.SessionChanged
        if (snapshot == null || snapshot.pointsByCode[pointCode] == null) {
            return EncyclopediaMutationOutcome.InvalidInput("未找到穴位 $pointCode")
        }
        val now = clock()
        val inserted = sessions.withCurrentIdentity(identity) {
            database.withTransaction {
                database.encyclopediaFavoriteDao().insertIgnore(
                    EncyclopediaFavoriteEntity(userId = userId, pointCode = pointCode, createdAt = now),
                ) > 0L
            }
        } ?: return EncyclopediaMutationOutcome.SessionChanged
        return EncyclopediaMutationOutcome.Success(inserted)
    }

    suspend fun toggleFavorite(
        identity: SessionIdentity,
        pointCode: String,
        snapshot: EncyclopediaSnapshot? = packRepository.currentSnapshot,
    ): EncyclopediaMutationOutcome<Boolean> {
        val userId = identity.userId?.takeIf { it > 0L } ?: return EncyclopediaMutationOutcome.SessionChanged
        if (snapshot == null || snapshot.pointsByCode[pointCode] == null) {
            return EncyclopediaMutationOutcome.InvalidInput("未找到穴位 $pointCode")
        }
        val now = clock()
        val nowFavorited = sessions.withCurrentIdentity(identity) {
            database.withTransaction {
                val dao = database.encyclopediaFavoriteDao()
                if (dao.isFavorite(userId, pointCode)) {
                    dao.delete(userId, pointCode)
                    false
                } else {
                    dao.insertIgnore(
                        EncyclopediaFavoriteEntity(userId = userId, pointCode = pointCode, createdAt = now),
                    )
                    true
                }
            }
        } ?: return EncyclopediaMutationOutcome.SessionChanged
        return EncyclopediaMutationOutcome.Success(nowFavorited)
    }

    suspend fun addAcupointToReview(
        identity: SessionIdentity,
        pointCode: String,
        snapshot: EncyclopediaSnapshot? = packRepository.currentSnapshot,
    ): EncyclopediaReviewAddStatus {
        val userId = identity.userId?.takeIf { it > 0L } ?: return EncyclopediaReviewAddStatus.SESSION_CHANGED
        val resolvedSnapshot = snapshot ?: (packRepository.loadIfNeeded() as? EncyclopediaLoadState.Ready)?.snapshot
            ?: return EncyclopediaReviewAddStatus.INVALID_POINT
        val candidate = buildReviewCandidate(resolvedSnapshot, pointCode)
            ?: return EncyclopediaReviewAddStatus.INVALID_POINT

        val existing = sessions.withCurrentIdentity(identity) {
            database.reviewDao().itemBySource(userId, REVIEW_NAMESPACE, candidate.sourceKey) != null
        } ?: return EncyclopediaReviewAddStatus.SESSION_CHANGED
        if (existing) return EncyclopediaReviewAddStatus.ALREADY_EXISTS

        return try {
            val inserted = reviewRepository.addItems(identity, listOf(candidate))
            if (inserted.isEmpty()) EncyclopediaReviewAddStatus.ALREADY_EXISTS else EncyclopediaReviewAddStatus.ADDED
        } catch (_: Exception) {
            EncyclopediaReviewAddStatus.SESSION_CHANGED
        }
    }

    companion object {
        const val REVIEW_NAMESPACE = "meridian.acupoint"
        const val REVIEW_SOURCE_KIND = "meridian_acupoint"

        fun buildReviewCandidate(
            snapshot: EncyclopediaSnapshot,
            pointCode: String,
        ): ReviewCandidate? {
            val point = snapshot.pointsByCode[pointCode] ?: return null
            val meridian = snapshot.meridiansByCode[point.meridianCode]
            val learning = snapshot.learningForPoint(pointCode)
            val occluded = snapshot.occludedByPointCode[pointCode]
            val regionLabel = occluded?.regionLabel ?: point.region

            val prompt = buildString {
                append("${point.name}（${point.code}）")
                meridian?.let { append(" · ${it.name}") }
                if (point.special.isNotBlank()) append("（${point.special}）")
                append("\n请回忆体表定位与${learning?.displayHeading ?: "主治"}要点。")
            }

            val answer = buildString {
                append("定位（$regionLabel）：${point.location}")
                if (learning != null && learning.groups.isNotEmpty()) {
                    append("\n\n${learning.displayHeading}：\n")
                    learning.groups.take(4).forEachIndexed { idx, group ->
                        if (idx > 0) append('\n')
                        append("• ${group.title}：${group.content}")
                    }
                }
            }.trim().take(7800)

            val refJson = buildJsonObject {
                put("kind", REVIEW_SOURCE_KIND)
                put("id", point.code)
            }.toString()

            return ReviewCandidate(
                sourceNamespace = REVIEW_NAMESPACE,
                sourceKey = "$REVIEW_NAMESPACE:${point.code}",
                sourceRefJson = refJson,
                sourceTags = setOf("MERIDIAN", point.meridianCode),
                prompt = prompt.take(500),
                answerSnapshot = answer,
                sourceRevision = snapshot.manifest.contentRevision,
                bookKey = null,
                sourceTitle = "${point.name} ${point.code} · ${meridian?.shortName ?: point.meridianCode}",
            )
        }
    }
}
