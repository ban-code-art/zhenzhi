package com.example.myapplication.ui.mine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.FavoriteDao
import com.example.myapplication.data.local.dao.StudyProgressDao
import com.example.myapplication.data.local.dao.TestRecordDao
import com.example.myapplication.data.local.entity.UserEntity
import com.example.myapplication.data.repository.UserRepository
import com.example.myapplication.data.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class MineUiState(
    val user: UserEntity? = null,
    val learnedDays: Int = 0,
    val streakDays: Int = 0,
    val completedChapters: Int = 0,
    val favoriteCount: Int = 0,
    val wrongCount: Int = 0
)

class MineViewModel(
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager,
    private val testRecordDao: TestRecordDao,
    private val studyProgressDao: StudyProgressDao,
    private val favoriteDao: FavoriteDao
) : ViewModel() {

    private val _state = MutableStateFlow(MineUiState())
    val state: StateFlow<MineUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val userId = sessionManager.currentUserId.first() ?: return@launch
            userRepository.observeById(userId).collect { user ->
                _state.update { it.copy(user = user) }
            }
        }
        viewModelScope.launch {
            val userId = sessionManager.currentUserId.first() ?: return@launch
            val activeDays = testRecordDao.getActiveDays(userId)
            _state.update {
                it.copy(
                    learnedDays = activeDays.size,
                    streakDays = computeStreak(activeDays),
                    completedChapters = studyProgressDao.countCompleted(userId),
                    favoriteCount = favoriteDao.countDistinctQuestions(userId),
                    wrongCount = favoriteDao.countDistinctQuestionsBySource(userId, "WRONG")
                )
            }
        }
    }

    private fun computeStreak(activeDays: List<String>): Int {
        if (activeDays.isEmpty()) return 0
        val set = activeDays.toHashSet()
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cursor = Calendar.getInstance()
        if (!set.contains(format.format(cursor.time))) {
            cursor.add(Calendar.DAY_OF_YEAR, -1)
        }
        var streak = 0
        while (set.contains(format.format(cursor.time))) {
            streak++
            cursor.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }
}

class MineViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = AppDatabase.getInstance(context)
        return MineViewModel(
            UserRepository(db.userDao()),
            SessionManager(context),
            db.testRecordDao(),
            db.studyProgressDao(),
            db.favoriteDao()
        ) as T
    }
}
