package com.example.myapplication.ui.mine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.FavoriteDao
import com.example.myapplication.data.local.dao.QuestionDao
import com.example.myapplication.data.local.entity.QuestionEntity
import com.example.myapplication.data.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class FavoriteItem(
    val question: QuestionEntity,
    val createdAt: Long
)

class FavoritesViewModel(
    private val favoriteDao: FavoriteDao,
    private val questionDao: QuestionDao,
    sessionManager: SessionManager,
    private val source: String
) : ViewModel() {

    private val _items = MutableStateFlow<List<FavoriteItem>>(emptyList())
    val items: StateFlow<List<FavoriteItem>> = _items.asStateFlow()

    init {
        viewModelScope.launch {
            val userId = sessionManager.currentUserId.first() ?: return@launch
            val favorites = favoriteDao.findBySource(userId, source)
            _items.value = favorites.mapNotNull { favorite ->
                questionDao.findById(favorite.questionId)?.let { question ->
                    FavoriteItem(question, favorite.createdAt)
                }
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
            db.favoriteDao(),
            db.questionDao(),
            SessionManager(context),
            source
        ) as T
    }
}
