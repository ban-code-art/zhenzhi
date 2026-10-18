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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class StudyItem(
    val chapter: ChapterEntity,
    val completed: Boolean,
    val quizScore: Int?,
    val quizTotal: Int?
)

class StudyProgressViewModel(
    private val chapterDao: ChapterDao,
    private val studyProgressDao: StudyProgressDao,
    sessionManager: SessionManager
) : ViewModel() {

    private val _items = MutableStateFlow<List<StudyItem>>(emptyList())
    val items: StateFlow<List<StudyItem>> = _items.asStateFlow()

    init {
        viewModelScope.launch {
            val userId = sessionManager.currentUserId.first() ?: return@launch
            val chapters = chapterDao.getAll()
            val progress = studyProgressDao.observeByUser(userId).first()
            val byChapter = progress.associateBy { it.chapterId }
            _items.value = chapters.map { chapter ->
                val item = byChapter[chapter.id]
                StudyItem(
                    chapter = chapter,
                    completed = item?.completed ?: false,
                    quizScore = item?.quizScore,
                    quizTotal = item?.quizTotal
                )
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
