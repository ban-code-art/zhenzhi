package com.example.myapplication.ui.mine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.FavoriteDao
import com.example.myapplication.data.local.dao.LearningActivityDao
import com.example.myapplication.data.local.dao.StudyProgressDao
import com.example.myapplication.data.local.dao.TestRecordDao
import com.example.myapplication.data.local.entity.UserEntity
import com.example.myapplication.data.repository.UserRepository
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.data.review.ReviewDao
import com.example.myapplication.data.quiz.SourcePracticeArchiveRepository
import com.example.myapplication.data.learning.learningDayCounts
import com.example.myapplication.LearningApplication
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

data class MineUiState(
    val user: UserEntity? = null,
    val learnedDays: Int = 0,
    val streakDays: Int = 0,
    val completedChapters: Int = 0,
    val favoriteCount: Int = 0,
    val wrongCount: Int = 0,
    /** 新增：来自 learning_activity_days 的阅读/随堂测学习日（与旧随机测试统计合并去重）。 */
    val readingDays: Int = 0,
    val quizDays: Int = 0
)

class MineViewModel(
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager,
    private val testRecordDao: TestRecordDao,
    private val studyProgressDao: StudyProgressDao,
    private val favoriteDao: FavoriteDao,
    private val learningActivityDao: LearningActivityDao,
    private val sourcePracticeArchiveRepository: SourcePracticeArchiveRepository? = null,
    private val reviewDao: ReviewDao? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(MineUiState())
    val state: StateFlow<MineUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessionManager.identity.collectLatest { identity ->
                _state.value = MineUiState()
                val userId = identity.userId ?: return@collectLatest
                coroutineScope {
                    launch {
                        userRepository.observeById(userId).collect { user ->
                            if (sessionManager.identity.first() == identity) _state.update { it.copy(user = user) }
                        }
                    }
                    val learningDays = combine(
                        testRecordDao.observeActiveDays(userId),
                        learningActivityDao.observeDays(userId),
                        reviewDao?.observeReviewedDates(userId) ?: flowOf(emptyList()),
                        flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } },
                    ) { legacy, activity, reviewDates, now ->
                        learningDayCounts(
                            legacyDays = legacy,
                            readingDays = activity.filter { it.readingMs > 0 }.map { it.localDate },
                            quizDays = activity.filter { it.quizCount > 0 }.map { it.localDate },
                            todayWallMs = now,
                            reviewDays = reviewDates,
                        )
                    }
                    val liveStats = combine(
                        learningDays,
                        studyProgressDao.observeByUser(userId),
                        favoriteDao.observeByUser(userId),
                    ) { days, progress, favorites ->
                        MineUiState(
                            learnedDays = days.learnedDays, streakDays = days.streakDays,
                            readingDays = days.readingDays, quizDays = days.quizDays,
                            completedChapters = progress.count { it.completed },
                            favoriteCount = favorites.map { it.questionId }.distinct().size,
                            wrongCount = favorites.filter { it.source == "WRONG" }.map { it.questionId }.distinct().size,
                        )
                    }
                    combine(liveStats, sourcePracticeArchiveRepository?.observeRecords() ?: flowOf(emptyList())) { stats, records ->
                        stats.copy(favoriteCount = stats.favoriteCount + records
                            .filter { it.ownerUserId == userId }.flatMap { it.favoriteQuestionIds }.distinct().size)
                    }.collect { stats ->
                        if (sessionManager.identity.first() == identity) _state.update { stats.copy(user = it.user) }
                    }
                }
            }
        }
    }
}

class MineViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = AppDatabase.getInstance(context)
        val container = (context.applicationContext as LearningApplication).container
        return MineViewModel(
            UserRepository(db.userDao()),
            SessionManager(context),
            db.testRecordDao(),
            db.studyProgressDao(),
            db.favoriteDao(),
            db.learningActivityDao(),
            container.sourcePracticeArchiveRepository,
            db.reviewDao(),
        ) as T
    }
}
