package com.example.myapplication.ui.knowledge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.ui.components.LocalMainDockBottomInset
import com.example.myapplication.ui.knowledge.home.KnowledgeHomeDeckContent
import com.example.myapplication.ui.knowledge.review.KnowledgeReviewHeader
import kotlinx.coroutines.launch

@Composable
fun KnowledgeScreen(
    onOpenReader: (String) -> Unit = {},
    onOpenCourseBook: ((String) -> Unit)? = null,
    onOpenCourseLibrary: (() -> Unit)? = null,
    onOpenImportedLibrary: (() -> Unit)? = null,
    onOpenBundledCourse: ((String) -> Unit)? = null,
    onOpenReview: (() -> Unit)? = null,
    homeActive: Boolean = true,
) {
    if (onOpenCourseLibrary != null && onOpenImportedLibrary != null) {
        val application = LocalContext.current.applicationContext as LearningApplication
        val model: KnowledgeHomeViewModel = viewModel(factory = remember(application) {
            KnowledgeHomeViewModel.factory(application)
        })
        val state by model.state.collectAsState()
        val identity by application.container.sessionManager.identity.collectAsState(SessionIdentity(null, -1))
        // Do not paint the previous owner's private books while the VM consumes an identity change.
        val visibleState = if (state.identity == identity) state else state.copy(identity = identity,
            libraryLoading = identity.userId?.let { it > 0L && identity.epoch >= 0L } == true,
            importedBooks = emptyList(), shelfBooks = emptyList(), importedBookCount = 0, libraryError = null)
        val scope = rememberCoroutineScope()
        val sessionManager = application.container.sessionManager
        KnowledgeHomeContent(
            state = visibleState,
            onOpenReader = { documentId ->
                val owner = identity.userId
                if (homeActive && owner != null) scope.launch {
                    sessionManager.withCurrentIdentity(identity) {
                        val database = application.container.database
                        val document = database.readerCatalogDao().findDocument(documentId)
                        if (document != null && document.ownerUserId == owner && document.importStatus == "READY" &&
                            database.readerStateDao().find(owner, documentId)?.isHidden != true
                        ) onOpenReader(documentId)
                        true
                    }
                }
            },
            onOpenBundledCourse = { bookKey ->
                if (homeActive) scope.launch {
                    sessionManager.withCurrentIdentity(identity) {
                        if (visibleState.catalog?.books?.any { it.key == bookKey && it.chapters.isNotEmpty() } == true) {
                            onOpenBundledCourse?.invoke(bookKey) ?: onOpenCourseLibrary()
                        }
                        true
                    }
                }
            },
            onOpenCourseLibrary = onOpenCourseLibrary,
            onOpenImportedLibrary = onOpenImportedLibrary,
            onReloadCourses = model::reloadCourses,
            onReloadLibrary = model::reloadLibrary,
            homeActive = homeActive,
            reviewHeader = {
                if (onOpenReview != null && visibleState.signedIn) {
                    KnowledgeReviewHeader(application.container.reviewRepository, identity) { if (homeActive) onOpenReview() }
                }
            },
        )
        return
    }
    val context = LocalContext.current
    ShelfScreen(
        onOpenReader = onOpenReader,
        onOpenCourseBook = onOpenCourseBook ?: defaultCourseBookLauncher(context),
        onOpenCourseLibrary = onOpenCourseLibrary ?: defaultCourseLibraryLauncher(context),
    )
}

/** Pure content surface so layout checks can supply real-shaped states without a database or navigation host. */
@Composable
fun KnowledgeHomeContent(
    state: KnowledgeHomeUiState,
    onOpenReader: (String) -> Unit = {},
    onOpenBundledCourse: (String) -> Unit = {},
    onOpenCourseLibrary: () -> Unit = {},
    onOpenImportedLibrary: () -> Unit = {},
    onReloadCourses: () -> Unit = {},
    onReloadLibrary: () -> Unit = {},
    modifier: Modifier = Modifier,
    homeActive: Boolean = true,
    reviewHeader: @Composable () -> Unit = {},
) {
    KnowledgeHomeDeckContent(
        state = state,
        onOpenReader = onOpenReader,
        onOpenBundledCourse = onOpenBundledCourse,
        onOpenCourseLibrary = onOpenCourseLibrary,
        onOpenImportedLibrary = onOpenImportedLibrary,
        onReloadCourses = onReloadCourses,
        onReloadLibrary = onReloadLibrary,
        dockBottomInset = LocalMainDockBottomInset.current,
        modifier = modifier,
        routeActive = homeActive,
        reviewHeader = reviewHeader,
    )
}
