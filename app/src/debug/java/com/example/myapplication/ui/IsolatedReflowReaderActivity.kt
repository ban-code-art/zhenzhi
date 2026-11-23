package com.example.myapplication.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.LearningApplication
import com.example.myapplication.ui.knowledge.reader.*
import com.example.myapplication.ui.theme.MyApplicationTheme

/** Debug-only opt-in route. Host and reader share the Activity's retained ViewModelStore. */
class IsolatedReflowReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val application = application as LearningApplication
        val hostOwner = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == HostOwner::class.java)
                return HostOwner(WebViewReflowRuntimeHost(application.applicationContext)) as T
            }
        })[HostOwner::class.java]
        val model = ViewModelProvider(this, ReflowReaderRuntimeViewModelFactory(application,
            intent.getStringExtra(EXTRA_DOCUMENT_ID).orEmpty(), hostOwner.host,
            intent.getStringExtra(EXTRA_LOCATOR_JSON), intent.getStringExtra(EXTRA_COURSE_BOOK_ID),
            intent.getStringExtra(EXTRA_COURSE_CHAPTER_KEY), intent.getBooleanExtra(EXTRA_ALLOW_DEVELOPMENT_DRAFT, false)))[ReflowReaderRuntimeViewModel::class.java]
        setContent { MyApplicationTheme(paperTheme = true) {
            ReflowReaderRuntimeScreen(model, hostOwner.host, ::finish)
        } }
    }

    private class HostOwner(val host: WebViewReflowRuntimeHost) : ViewModel()

    companion object {
        const val EXTRA_ALLOW_DEVELOPMENT_DRAFT = "allowDevelopmentDraft"
        const val EXTRA_COURSE_BOOK_ID = "courseBookId"
        const val EXTRA_COURSE_CHAPTER_KEY = "courseChapterKey"
        const val EXTRA_DOCUMENT_ID = "documentId"
        const val EXTRA_LOCATOR_JSON = "locatorJson"
    }
}

