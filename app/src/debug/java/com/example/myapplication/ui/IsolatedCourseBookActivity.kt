package com.example.myapplication.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.reader.CourseCatalogSnapshot
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.ui.knowledge.reader.*
import com.example.myapplication.ui.theme.MyApplicationTheme

/** Debug-only directory/reader pair; Activity return keeps the directory's list state. */
class IsolatedCourseBookActivity : ComponentActivity() {
    private var directory: CourseBookViewModel? = null
    override fun onResume() { super.onResume(); directory?.reload() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LearningApplication).container
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        val allowDraft = intent.getBooleanExtra(EXTRA_ALLOW_DEVELOPMENT_DRAFT, false)
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == CourseBookViewModel::class.java)
                return CourseBookViewModel(bookId, container.sessionManager.identity, { id ->
                    container.database.withTransaction {
                        val dao = container.database.courseBookDao()
                        dao.findBook(id)?.let { CourseCatalogSnapshot(it, dao.listAllChapters(id)) }
                    }
                }, { context, id -> container.courseReadingSequenceProvider.load(context, id, allowDraft) }, { user, target ->
                    when (val session = container.readerRepository.openSession(user, target.documentId, target.contentRevision)) {
                        is OperationResult.Ok -> session.value.document.title
                        else -> target.chapterKey.orEmpty()
                    }
                }, readState = { user, document -> container.database.readerStateDao().find(user, document) },
                    studyProgress = { user -> container.database.studyProgressDao().observeByUser(user) },
                    quizAttempts = { user, documents -> container.database.quizAttemptDao().observeCourseAttempts(user, documents) }) as T
            }
        })[CourseBookViewModel::class.java]
        directory = model
        setContent { MyApplicationTheme(paperTheme = true) {
            CourseBookScreen(model, { target ->
                startActivity(Intent(this, IsolatedReflowReaderActivity::class.java)
                    .putExtra(IsolatedReflowReaderActivity.EXTRA_ALLOW_DEVELOPMENT_DRAFT, allowDraft)
                    .putExtra(IsolatedReflowReaderActivity.EXTRA_COURSE_BOOK_ID, bookId)
                    .putExtra(IsolatedReflowReaderActivity.EXTRA_COURSE_CHAPTER_KEY, target.chapterKey))
            }, ::finish, onContinue = { next ->
                startActivity(Intent(this, IsolatedReflowReaderActivity::class.java)
                    .putExtra(IsolatedReflowReaderActivity.EXTRA_ALLOW_DEVELOPMENT_DRAFT, allowDraft)
                    .putExtra(IsolatedReflowReaderActivity.EXTRA_COURSE_BOOK_ID, bookId)
                    .putExtra(IsolatedReflowReaderActivity.EXTRA_COURSE_CHAPTER_KEY, next.target.chapterKey)
                    .putExtra(IsolatedReflowReaderActivity.EXTRA_LOCATOR_JSON, next.locator?.let(ReaderLocatorCodec::encode)))
            })
        } }
    }
    companion object {
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_ALLOW_DEVELOPMENT_DRAFT = "allowDevelopmentDraft"
    }
}


