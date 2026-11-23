package com.example.myapplication.ui.knowledge.reader

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.LearningApplication
import com.example.myapplication.domain.reader.ReaderOpenRequest
import com.example.myapplication.ui.ai.AgentLaunchContext
import com.example.myapplication.ui.knowledge.course.CourseReaderNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Same Main navigation entry; explicit native annotation preview does not replace the default engine. */
@Composable
fun ReaderScreen(
    documentId: String,
    initialLocatorJson: String? = null,
    onBack: () -> Unit,
    isSourceReader: Boolean = false,
    onOpenNotebook: () -> Unit = {},
    onAskAi: (AgentLaunchContext) -> Unit = {},
    onGenerateQuiz: (String, String?) -> Unit = { _, _ -> },
    courseRequest: ReaderOpenRequest? = null,
    courseNavigation: CourseReaderNavigation? = null,
    onPositionReady: (String) -> Unit = {},
    courseQuizAvailable: Boolean = false,
) = key(documentId, initialLocatorJson) {
    val application = LocalContext.current.applicationContext as LearningApplication
    val scope = rememberCoroutineScope()
    val parent = checkNotNull(LocalViewModelStoreOwner.current)
    // A new caller-supplied location starts a reader session instead of restoring the old mode.
    var native by rememberSaveable { mutableStateOf(false) }
    var nativeInstance by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var locator by rememberSaveable { mutableStateOf(initialLocatorJson) }
    // Clearing this child store closes the outgoing mode. Returning recreates a fresh legacy VM
    // against the persisted committed point instead of retaining stale page/annotation state.
    val store = remember(documentId, native) { ViewModelStore() }
    val defaults = parent as? HasDefaultViewModelProviderFactory
    val owner = remember(parent, store) { object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
        override val viewModelStore = store
        override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
            defaults?.defaultViewModelProviderFactory ?: ViewModelProvider.NewInstanceFactory()
        override val defaultViewModelCreationExtras: CreationExtras = defaults?.defaultViewModelCreationExtras ?: CreationExtras.Empty
    } }
    DisposableEffect(store) { onDispose { store.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        if (native) {
            val factory = remember(documentId, locator) { PdfReaderRuntimeViewModelFactory(application, documentId, locator) }
            val model: PdfReaderRuntimeViewModel = viewModel(key = "pdf-native-$nativeInstance", factory = factory)
            var returning by remember(model) { mutableStateOf(false) }
            PdfReaderRuntimeScreen(model) {
                if (!returning) {
                    returning = true
                    scope.launch {
                        // onExited is delivered only after save/settings/renderer cleanup completed.
                        val identity = application.container.sessionManager.identity.first()
                        val saved = withContext(Dispatchers.IO) { identity.userId?.let {
                            application.container.database.readerStateDao().find(it, documentId)?.locatorJson
                        } }
                        if (application.container.sessionManager.identity.first() == identity) locator = saved ?: locator
                        native = false
                    }
                }
            }
        } else {
            LegacyReaderScreen(documentId, locator, onBack, isSourceReader, onOpenNotebook, onAskAi,
                onGenerateQuiz, courseRequest, courseNavigation, onPositionReady, courseQuizAvailable,
                // Course navigation requires its own finite binding and is not an imported PDF preview.
                onOpenNativePdf = if (courseRequest != null) null else { point -> locator = point ?: locator; nativeInstance = java.util.UUID.randomUUID().toString(); native = true })
        }
    }
}
