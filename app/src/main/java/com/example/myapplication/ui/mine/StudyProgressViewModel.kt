package com.example.myapplication.ui.mine

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.ChapterDao
import com.example.myapplication.data.local.dao.StudyProgressDao
import com.example.myapplication.data.local.entity.ChapterEntity
import com.example.myapplication.data.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StudyItem(
    val chapter: ChapterEntity,
    val completed: Boolean,
    val quizScore: Int?,
    val quizTotal: Int?
)

/**
 * W4-11：章节 + 学习进度改为 Flow 组合，避免只加载一次（设计 §10）。
 * 章节列表极少变化，用一次性 flow emit；进度用 observe 持续更新
 * （随堂测交卷后 quiz 字段/完成状态会实时反映）。
 */
class StudyProgressViewModel(
    private val chapterDao: ChapterDao,
    private val studyProgressDao: StudyProgressDao,
    sessionManager: SessionManager
) : ViewModel() {

    private val _items = MutableStateFlow<List<StudyItem>>(emptyList())
    val items: StateFlow<List<StudyItem>> = _items.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val reload = MutableStateFlow(0L)

    fun retry() { reload.update { it + 1L } }

    init {
        viewModelScope.launch {
            combine(sessionManager.identity, reload) { identity, _ -> identity }.collectLatest { identity ->
                _items.value = emptyList()
                _error.value = null
                _loading.value = identity.userId != null
                val userId = identity.userId ?: return@collectLatest
                try {
                    flow { emit(chapterDao.getAll()) }
                        .combine(studyProgressDao.observeByUser(userId)) { chapters, progress ->
                            val byChapter = progress.associateBy { it.chapterId }
                            chapters.map { chapter ->
                                val item = byChapter[chapter.id]
                                StudyItem(
                                    chapter = chapter,
                                    completed = item?.completed ?: false,
                                    quizScore = item?.quizScore,
                                    quizTotal = item?.quizTotal,
                                )
                            }
                        }.collect { items ->
                            if (sessionManager.identity.first() == identity) {
                                _items.value = items
                                _loading.value = false
                            }
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (sessionManager.identity.first() == identity) {
                        _loading.value = false
                        _error.value = "学习进度读取失败，请重试"
                    }
                }
            }
        }
    }
}

class StudyProgressViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = AppDatabase.getInstance(context)
        return StudyProgressViewModel(
            db.chapterDao(),
            db.studyProgressDao(),
            SessionManager(context)
        ) as T
    }
}
