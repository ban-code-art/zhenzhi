package com.example.myapplication.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.LearningApplication
import com.example.myapplication.ui.knowledge.reader.PdfReaderRuntimeScreen
import com.example.myapplication.ui.knowledge.reader.PdfReaderRuntimeViewModel
import com.example.myapplication.ui.knowledge.reader.PdfReaderRuntimeViewModelFactory
import com.example.myapplication.ui.theme.MyApplicationTheme

/** Debug-only explicit entry. It never replaces the production navigation route. */
class IsolatedPdfReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The owner's CreationExtras give the factory a retained SavedStateHandle with disk keys.
        val model = ViewModelProvider(this, PdfReaderRuntimeViewModelFactory(
            application as LearningApplication,
            intent.getStringExtra(EXTRA_DOCUMENT_ID).orEmpty(),
            intent.getStringExtra(EXTRA_LOCATOR_JSON),
        ))[PdfReaderRuntimeViewModel::class.java]
        setContent { MyApplicationTheme(paperTheme = true) {
            PdfReaderRuntimeScreen(model, onExited = ::finish)
        } }
    }

    companion object {
        const val EXTRA_DOCUMENT_ID = "documentId"
        const val EXTRA_LOCATOR_JSON = "locatorJson"
    }
}
