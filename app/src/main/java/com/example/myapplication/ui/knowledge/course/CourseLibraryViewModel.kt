package com.example.myapplication.ui.knowledge.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.entity.CourseBookEntity
import com.example.myapplication.data.reader.CourseCatalogSnapshot
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

data class CourseLibraryRow(val book: CourseBookEntity, val chapterCount: Int, val canOpen: Boolean, val unavailableReason: String? = null)
data class CourseLibraryUiState(val loading: Boolean = true, val rows: List<CourseLibraryRow> = emptyList(),
    val error: String? = null, val closed: Boolean = false)

object CourseLibraryPolicy {
    fun canAttempt(reviewStatus: String, allowDevelopmentDraft: Boolean): Boolean =
        reviewStatus == "APPROVED" || (reviewStatus == "DRAFT" && allowDevelopmentDraft)
    fun reviewLabel(reviewStatus: String, allowDevelopmentDraft: Boolean): String = when (reviewStatus) {
        "APPROVED" -> "已审核"
        "DRAFT" -> if (allowDevelopmentDraft) "开发草稿" else "准备中"
        "REVIEWED" -> "待发布"
        else -> "审核状态未知"
    }
}

/** Isolated course list; no legacy chapter is promoted into a separate book. */
class CourseLibraryViewModel(
    identities: Flow<SessionIdentity>,
    private val observeBooks: () -> Flow<List<CourseBookEntity>>,
    private val catalog: suspend (String) -> CourseCatalogSnapshot?,
    private val sequence: suspend (AccessContext, String, Boolean) -> OperationResult<ReadingSequence>,
    val allowDevelopmentDraft: Boolean = false,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CourseLibraryUiState())
    val state = mutableState.asStateFlow()
    private var owner: SessionIdentity? = null
    private var observation: Job? = null
    init { viewModelScope.launch {
        var first = true
        identities.distinctUntilChanged().collect { identity ->
            if (first) { first = false; owner = identity; reload() }
            else if (owner != identity) {
                observation?.cancel()
                mutableState.value = CourseLibraryUiState(loading = false, error = "SESSION_CHANGED", closed = true)
            }
        }
    } }
    fun reload() {
        val identity = owner ?: return
        if (state.value.closed) return
        observation?.cancel()
        val user = identity.userId
        if (user == null) { publish(CourseLibraryUiState(false, error = "LOGIN_REQUIRED")); return }
        mutableState.update { if (it.closed) it else it.copy(loading = true, error = null) }
        observation = viewModelScope.launch(Dispatchers.IO) {
            try {
                observeBooks().collectLatest { books ->
                    require(books.map { it.bookId }.distinct().size == books.size) { "duplicate course books" }
                    mutableState.update { if (it.closed) it else it.copy(loading = true, error = null) }
                    val context = AccessContext(user, identity.epoch, UUID.randomUUID().toString())
                    val rows = books.sortedWith(compareBy<CourseBookEntity> { it.orderIndex }.thenBy { it.contentKey }).map { book ->
                        currentCoroutineContext().ensureActive()
                        val source = catalog(book.bookId)
                        currentCoroutineContext().ensureActive()
                        if (source == null || source.book != book) CourseLibraryRow(book, 0, false, "COURSE_CHANGED")
                        else {
                            val count = source.chapters.count { it.enabled }
                            if (!CourseLibraryPolicy.canAttempt(book.reviewStatus, allowDevelopmentDraft))
                                CourseLibraryRow(book, count, false, "COURSE_NOT_APPROVED")
                            else {
                                val resolved = sequence(context, book.bookId, allowDevelopmentDraft)
                                currentCoroutineContext().ensureActive()
                                val latest = catalog(book.bookId)
                                currentCoroutineContext().ensureActive()
                                val valid = resolved is OperationResult.Ok && resolved.value.orderedTargets.isNotEmpty() &&
                                    resolved.value.orderedTargets.all { it.courseBookId == book.bookId && !it.chapterKey.isNullOrBlank() }
                                val reason = if (latest != source) "COURSE_CHANGED" else if (valid) null else when (resolved) {
                                    is OperationResult.Rejected -> resolved.code
                                    is OperationResult.Failed -> resolved.code
                                    else -> "COURSE_SEQUENCE_INVALID"
                                }
                                CourseLibraryRow(book, count, reason == null, reason)
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    publish(CourseLibraryUiState(false, rows))
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.update { if (it.closed) it else it.copy(loading = false, error = "COURSE_LIBRARY_LOAD_FAILED") }
            }
        }
    }
    private fun publish(next: CourseLibraryUiState) { mutableState.update { if (it.closed) it else next } }
}
