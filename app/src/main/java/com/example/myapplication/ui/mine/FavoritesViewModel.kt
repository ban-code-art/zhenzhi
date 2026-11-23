package com.example.myapplication.ui.mine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.FavoriteDao
import com.example.myapplication.data.local.dao.QuestionDao
import com.example.myapplication.data.local.dao.ReaderCatalogDao
import com.example.myapplication.data.local.entity.QuestionEntity
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch

data class FavoriteItem(
    val question: QuestionEntity,
    val createdAt: Long,
    val source: com.example.myapplication.ui.ai.AgentSourceUi? = null,
)

/**
 * W4-11：收藏/错题改为 Flow 组合，避免只加载一次（设计 §10/§9）。
 *
 * `favoriteDao.observeByUser` 是唯一数据源：交卷写 WRONG、手动收藏/移除都会
 * 触发重算；题目正文按需要回表读取（不缓存整库）。
 */
class FavoritesViewModel(
    private val database: AppDatabase,
    private val favoriteDao: FavoriteDao,
    private val questionDao: QuestionDao,
    private val sessionManager: SessionManager,
    private val source: String,
    private val readerCatalogDao: ReaderCatalogDao? = null,
) : ViewModel() {

    private val _items = MutableStateFlow<List<FavoriteItem>>(emptyList())
    val items: StateFlow<List<FavoriteItem>> = _items.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _removingQuestionId = MutableStateFlow<Long?>(null)
    val removingQuestionId: StateFlow<Long?> = _removingQuestionId.asStateFlow()
    private val _removeError = MutableStateFlow<String?>(null)
    val removeError: StateFlow<String?> = _removeError.asStateFlow()

    fun clearRemoveError() {
        _removeError.value = null
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observe() {
        viewModelScope.launch {
            sessionManager.identity.flatMapLatest { identity ->
                _items.value = emptyList()
                _error.value = null
                val userId = identity.userId
                if (userId == null) flowOf(emptyList()) else favoriteDao.observeByUser(userId).map { favorites ->
                    val projected = ArrayList<FavoriteItem>(favorites.size)
                    favorites.forEach { favorite ->
                        if (favorite.source != source) return@forEach
                        questionDao.findById(favorite.questionId)?.let { question ->
                            if (question.ownerUserId != null && question.ownerUserId != userId) return@let
                            val document = question.sourceDocumentId?.let { readerCatalogDao?.findDocument(it) }
                            val unavailable = when {
                                question.sourceLocatorJson.isNullOrBlank() -> "来源不可定位，保留历史摘录"
                                readerCatalogDao == null -> null
                                document == null -> "来源已删除，保留历史摘录"
                                document.kind != "BUILTIN" && document.ownerUserId != userId -> "当前账号无法访问来源，保留历史摘录"
                                question.sourceRevision != null && document.contentRevision != question.sourceRevision -> "来源已改版，保留历史摘录"
                                else -> null
                            }
                            val sourceUi = question.sourceExcerpt?.takeIf { it.isNotBlank() }?.let { quote ->
                                com.example.myapplication.ui.ai.AgentSourceUi(1, question.sourceDocumentId.orEmpty(),
                                    document?.takeIf { it.kind == "BUILTIN" || it.ownerUserId == userId }?.title ?: "来源文档", null, null, quote, question.sourceLocatorJson,
                                    unavailableReason = unavailable)
                            }
                            projected += FavoriteItem(question, favorite.createdAt, sourceUi)
                        }
                    }
                    if (sessionManager.identity.first() == identity) projected.distinctBy { it.question.id } else emptyList()
                }.catch { error ->
                    if (error is CancellationException) throw error
                    if (sessionManager.identity.first() == identity) _error.value = "加载失败：${error.message}"
                    emit(emptyList())
                }
            }
                .collect { items -> _items.value = items }
        }
    }
    init { observe() }
    fun remove(item: FavoriteItem, expectedIdentity: SessionIdentity, onRemoved: () -> Unit) {
        val userId = expectedIdentity.userId ?: return
        val questionId = item.question.id
        if (_items.value.none { it.question.id == questionId }) return
        if (!_removingQuestionId.compareAndSet(null, questionId)) return
        clearRemoveError()
        viewModelScope.launch {
            try {
                if (sessionManager.identity.first() != expectedIdentity) return@launch
                database.withTransaction {
                    // A queued writer must recheck the session after acquiring the transaction.
                    check(sessionManager.identity.first() == expectedIdentity) { "当前账号已变更，请重新操作" }
                    favoriteDao.delete(userId, questionId, source)
                    // Throwing here also rolls back a delete when the session changes during the write.
                    check(sessionManager.identity.first() == expectedIdentity) { "当前账号已变更，请重新操作" }
                }
                if (sessionManager.identity.first() == expectedIdentity) onRemoved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (sessionManager.identity.first() == expectedIdentity) {
                    _removeError.value = "移除失败：${error.message ?: "请重试"}"
                }
            } finally {
                _removingQuestionId.value = null
            }
        }
    }

}

class FavoritesViewModelFactory(
    private val context: Context,
    private val source: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = AppDatabase.getInstance(context)
        return FavoritesViewModel(
            db,
            db.favoriteDao(),
            db.questionDao(),
            SessionManager(context),
            source,
            db.readerCatalogDao(),
        ) as T
    }
}
