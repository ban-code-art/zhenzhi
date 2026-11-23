package com.example.myapplication.ui.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.local.dao.ReaderShelfRow
import com.example.myapplication.data.local.dao.ReaderHomeShelfRow
import com.example.myapplication.data.local.entity.ReaderSectionEntity
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.ui.knowledge.course.BundledCourseCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bounded homepage reads only. It never opens a reader session or changes reading progress. */
class KnowledgeHomeViewModel(
    identities: Flow<SessionIdentity>,
    private val loadCourses: suspend () -> BundledCourseCatalog,
    private val observeShelf: (Long) -> Flow<List<ReaderShelfRow>>,
    private val readSections: suspend (String, String) -> List<ReaderSectionEntity>,
    private val observeHomeImports: ((Long) -> Flow<List<ReaderHomeShelfRow>>)? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(KnowledgeHomeUiState())
    val state = mutableState.asStateFlow()
    private val libraryReload = MutableStateFlow(0L)
    private var courseJob: Job? = null

    constructor(application: LearningApplication) : this(
        identities = application.container.sessionManager.identity,
        loadCourses = {
            application.assets.open(BundledCourseCatalog.ASSET_PATH).use { input ->
                BundledCourseCatalog.fromBytes(input.readBytes())
            }
        },
        observeShelf = { application.container.database.readerCatalogDao().observeShelf(it) },
        observeHomeImports = { application.container.database.readerCatalogDao().observeHomeImports(it) },
        readSections = { documentId, revision ->
            application.container.database.readerCatalogDao().findSections(documentId, revision)
        },
    )

    init {
        reloadCourses()
        viewModelScope.launch {
            combine(identities.distinctUntilChanged(), libraryReload) { identity, _ -> identity }
                .collectLatest { captured ->
                    val userId = captured.userId?.takeIf { it > 0L && captured.epoch >= 0L }
                    mutableState.update { it.copy(identity = captured, libraryLoading = userId != null,
                        importedBooks = emptyList(), shelfBooks = emptyList(), importedBookCount = 0, libraryError = null) }
                    if (userId == null) return@collectLatest
                    try {
                        val homeSource = observeHomeImports?.invoke(userId)?.map { rows ->
                            rows.map(ReaderHomeShelfRow::shelf) to (rows.firstOrNull()?.visibleTotal ?: 0)
                        } ?: observeShelf(userId).map { rows ->
                            val visible = KnowledgeHomeProjection.visibleImports(userId, rows)
                            visible to visible.size
                        }
                        homeSource.collectLatest { (visible, total) ->
                            val books = withContext(Dispatchers.IO) {
                                visible.take(KnowledgeHomeProjection.SHELF_BOOKS).map { row ->
                                    currentCoroutineContext().ensureActive()
                                    KnowledgeHomeProjection.book(row,
                                        readSections(row.document.id, row.document.contentRevision))
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            mutableState.update { current ->
                                if (current.identity != captured) current else current.copy(libraryLoading = false,
                                    importedBooks = books.take(KnowledgeHomeProjection.PREVIEW_BOOKS),
                                    shelfBooks = books, importedBookCount = total, libraryError = null)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        mutableState.update { current ->
                            if (current.identity != captured) current else current.copy(libraryLoading = false,
                                importedBooks = emptyList(), shelfBooks = emptyList(), importedBookCount = 0,
                                libraryError = "个人书库暂时无法载入，请重试。")
                        }
                    }
                }
        }
    }

    fun reloadCourses() {
        if (courseJob?.isActive == true) return
        mutableState.update { it.copy(coursesLoading = true, courseError = null) }
        courseJob = viewModelScope.launch {
            try {
                val catalog = withContext(Dispatchers.IO) { loadCourses() }
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(coursesLoading = false, catalog = catalog, courseError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(coursesLoading = false,
                    courseError = "内置教材暂时无法载入，请重试。") }
            }
        }
    }

    fun reloadLibrary() { libraryReload.update { it + 1L } }

    companion object {
        fun factory(application: LearningApplication): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(KnowledgeHomeViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return KnowledgeHomeViewModel(application) as T
            }
        }
    }
}
