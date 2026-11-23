package com.example.myapplication.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.myapplication.ui.knowledge.reader.ReaderScreen
import com.example.myapplication.ui.knowledge.ShelfScreen
import androidx.compose.runtime.*

/** Real production ReaderScreen, hosted without application navigation for isolated M04 evidence. */
class MarkdownReaderProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(android.os.Process.myUid() / 100000 == 10) { "M04 requires isolated Android user10" }
        val documentId = intent.getStringExtra("documentId")
        val locator = intent.getStringExtra("locator")
        setContent {
            var opened by remember { mutableStateOf(documentId) }
            if (intent.hasExtra("route")) {
                com.example.myapplication.ui.navigation.AppNavHost(
                    intent.getStringExtra("route")!!, androidx.navigation.compose.rememberNavController())
            } else if (opened == null && intent.getBooleanExtra("shelf", false)) {
                ShelfScreen(onOpenReader = { opened = it })
            } else ReaderScreen(documentId = requireNotNull(opened), initialLocatorJson = locator, onBack = { finish() })
        }
    }
}
