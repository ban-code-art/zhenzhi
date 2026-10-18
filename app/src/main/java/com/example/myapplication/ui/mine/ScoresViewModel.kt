package com.example.myapplication.ui.mine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.QuestionDao
import com.example.myapplication.data.local.dao.TestAnswerDao
import com.example.myapplication.data.local.dao.TestRecordDao
import com.example.myapplication.data.local.entity.TestRecordEntity
import com.example.myapplication.data.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ScoreAnswerItem(
    val orderIndex: Int,
    val type: String,
    val stem: String,
    val userAnswer: String?,
    val correctAnswer: String,
    val analysis: String,
    val knowledgePoint: String?,
    val score: Int,
    val maxScore: Int,
    val isCorrect: Int?
)

data class ScoreDetail(
    val record: TestRecordEntity,
    val items: List<ScoreAnswerItem>
)

class ScoresViewModel(
    private val testRecordDao: TestRecordDao,
    private val testAnswerDao: TestAnswerDao,
    private val questionDao: QuestionDao,
    sessionManager: SessionManager
) : ViewModel() {

    private val _records = MutableStateFlow<List<TestRecordEntity>>(emptyList())
    val records: StateFlow<List<TestRecordEntity>> = _records.asStateFlow()

    private val _detail = MutableStateFlow<ScoreDetail?>(null)
    val detail: StateFlow<ScoreDetail?> = _detail.asStateFlow()

    init {
        viewModelScope.launch {
            val userId = sessionManager.currentUserId.first() ?: return@launch
            testRecordDao.observeSubmittedByUser(userId).collect { list ->
                _records.value = list
            }
        }
    }

    fun loadDetail(testId: Long) {
        viewModelScope.launch {
            val record = testRecordDao.findById(testId) ?: return@launch
            val answers = testAnswerDao.findByTest(testId)
            val items = answers.map { answer ->
                val question = questionDao.findById(answer.questionId)
                ScoreAnswerItem(
                    orderIndex = answer.orderIndex,
                    type = question?.type ?: "",
                    stem = question?.stem ?: "(题目已删除)",
                    userAnswer = answer.userAnswer,
                    correctAnswer = question?.answer ?: "",
                    analysis = question?.analysis ?: "",
                    knowledgePoint = question?.knowledgePoint,
                    score = answer.score,
                    maxScore = question?.score ?: 0,
                    isCorrect = answer.isCorrect
                )
            }
            _detail.value = ScoreDetail(record, items)
        }
    }
}

class ScoresViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = AppDatabase.getInstance(context)
        return ScoresViewModel(
            db.testRecordDao(),
            db.testAnswerDao(),
            db.questionDao(),
            SessionManager(context)
        ) as T
    }
}
