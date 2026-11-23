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
import com.example.myapplication.ui.knowledge.course.*
import com.example.myapplication.ui.theme.MyApplicationTheme

/** Explicit debug-only entry; no production shelf or navigation route is changed. */
class IsolatedCourseLibraryActivity : ComponentActivity() {
    private var library: CourseLibraryViewModel? = null
    override fun onResume() { super.onResume(); library?.reload() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LearningApplication).container
        val allowDraft = intent.getBooleanExtra(EXTRA_ALLOW_DEVELOPMENT_DRAFT, false)
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == CourseLibraryViewModel::class.java)
                return CourseLibraryViewModel(container.sessionManager.identity,
                    { container.database.courseBookDao().observeBooks() },
                    { bookId -> container.database.withTransaction {
                        val dao = container.database.courseBookDao()
                        dao.findBook(bookId)?.let { CourseCatalogSnapshot(it, dao.listAllChapters(bookId)) }
                    } }, { context, bookId, draft -> container.courseReadingSequenceProvider.load(context, bookId, draft) },
                    allowDraft) as T
            }
        })[CourseLibraryViewModel::class.java]
        library = model
        setContent { MyApplicationTheme(paperTheme = true) {
            CourseLibraryScreen(model, { bookId ->
                startActivity(Intent(this, IsolatedCourseBookActivity::class.java)
                    .putExtra(IsolatedCourseBookActivity.EXTRA_BOOK_ID, bookId)
                    .putExtra(IsolatedCourseBookActivity.EXTRA_ALLOW_DEVELOPMENT_DRAFT, allowDraft))
            }, ::finish)
        } }
    }
    companion object { const val EXTRA_ALLOW_DEVELOPMENT_DRAFT = "allowDevelopmentDraft" }
}
