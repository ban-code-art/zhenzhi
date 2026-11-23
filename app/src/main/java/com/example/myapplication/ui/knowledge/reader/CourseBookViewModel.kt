package com.example.myapplication.ui.knowledge.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.entity.StudyProgressEntity
import com.example.myapplication.data.local.dao.CourseQuizAttemptSummary
import com.example.myapplication.data.reader.CourseCatalogSnapshot
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class CourseBookRow(val target: ReaderTarget, val title: String,
    val readingStatus: CourseReadingStatus = CourseReadingStatus.NOT_STARTED,
    val resumeLocator: ReaderLocator? = null, val resumeResetReason: String? = null,
    val quizProgress: CourseQuizProgress = CourseQuizProgress.Unavailable,
    val latestQuiz: CourseLatestQuizProgress? = null)
data class CourseBookUiState(val loading: Boolean = true, val title: String = "", val rows: List<CourseBookRow> = emptyList(), val error: String? = null, val closed: Boolean = false, val continueReading: CourseContinueReading? = null, val quizError: String? = null, val combinedQuizzes: List<CourseCombinedQuizSummary> = emptyList())

internal class CourseBookStateCommitter(
    private val mutableState: MutableStateFlow<CourseBookUiState>,
    private val jobLock: Any,
    private val owner: () -> SessionIdentity?,
    private val loadGeneration: () -> Long,
) {
    fun isCurrent(identity: SessionIdentity, generation: Long) =
        owner() == identity && loadGeneration() == generation

    fun update(identity: SessionIdentity, generation: Long, transform: (CourseBookUiState) -> CourseBookUiState) {
        synchronized(jobLock) {
            mutableState.update { current ->
                if (current.closed || !isCurrent(identity, generation)) current else transform(current)
            }
        }
    }
}

/** Metadata-only directory. Reading owns its separate scope/access checks; no renderer is opened here. */
class CourseBookViewModel(
    private val bookId: String,
    identities: Flow<SessionIdentity>,
    private val catalog: suspend (String) -> CourseCatalogSnapshot?,
    private val sequence: suspend (AccessContext, String) -> OperationResult<ReadingSequence>,
    private val title: suspend (Long, ReaderTarget) -> String,
    private val readState: suspend (Long, String) -> com.example.myapplication.data.local.entity.ReaderStateEntity? = { _, _ -> null },
    private val studyProgress: (Long) -> Flow<List<StudyProgressEntity>> = { flowOf(emptyList()) },
    private val quizAttempts: ((Long, List<String>) -> Flow<List<CourseQuizAttemptSummary>>)? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CourseBookUiState())
    val state = mutableState.asStateFlow()
    private val jobLock = Any()
    private val loadGeneration = AtomicLong(0)
    @Volatile
    private var owner: SessionIdentity? = null
    private var loadJob: Job? = null
    private var quizJob: Job? = null
    private val stateCommitter = CourseBookStateCommitter(mutableState, jobLock, { owner }, { loadGeneration.get() })
    init { viewModelScope.launch {
        var first = true
        identities.distinctUntilChanged().collect { current ->
            if (first) { first = false; owner = current; reload() }
            else if (owner != current) {
                synchronized(jobLock) {
                    owner = current
                    loadGeneration.incrementAndGet()
                    loadJob?.cancel()
                    quizJob?.cancel()
                    loadJob = null
                    quizJob = null
                    mutableState.value = CourseBookUiState(loading = false, error = "SESSION_CHANGED", closed = true)
                }
            }
        }
    } }
    fun reload() {
        synchronized(jobLock) {
            val identity = owner ?: return
            if (state.value.closed || (state.value.loading && loadJob?.isActive == true)) return
            val generation = loadGeneration.incrementAndGet()
            loadJob?.cancel()
            quizJob?.cancel()
            loadJob = null
            quizJob = null
            val user = identity.userId
            if (user == null) {
                publish(identity, generation, CourseBookUiState(false, error = "LOGIN_REQUIRED"))
                return
            }
            update(identity, generation) { current -> current.copy(
                loading = true,
                error = null,
                quizError = null,
                rows = current.rows.withoutQuizSummary(),
                combinedQuizzes = emptyList(),
            ) }
            loadJob = viewModelScope.launch(Dispatchers.IO) { loadDirectory(identity, generation, user) }
        }
    }

    private suspend fun loadDirectory(identity: SessionIdentity, generation: Long, user: Long) {
        val loadingJob = currentCoroutineContext().job
        try {
            ensureCurrent(identity, generation)
            val source = catalog(bookId)?.snapshot()
            ensureCurrent(identity, generation)
            if (source == null || source.book.bookId != bookId) {
                publish(identity, generation, CourseBookUiState(false, error = "COURSE_NOT_FOUND"))
                return
            }
            val result = sequence(AccessContext(user, identity.epoch, UUID.randomUUID().toString()), bookId)
            ensureCurrent(identity, generation)
            if (result !is OperationResult.Ok) {
                publish(identity, generation, CourseBookUiState(false, source.book.title, error = when (result) {
                    is OperationResult.Rejected -> result.code
                    is OperationResult.Failed -> result.code
                    else -> "COURSE_SEQUENCE_CONFLICT"
                }))
                return
            }
            val readingSequence = ReadingSequence(result.value.version, result.value.orderedTargets)
            val progressRows = readingSequence.orderedTargets.map { it.documentId }.distinct().mapNotNull { id ->
                readState(user, id).also { ensureCurrent(identity, generation) }
            }
            val progress = CourseReadingProgress.project(user, readingSequence, progressRows)
            val rows = readingSequence.orderedTargets.map {
                val reading = progress.chapters.getValue(it)
                val row = CourseBookRow(it, title(user, it), reading.status, reading.locator, reading.resetReason,
                    latestQuiz = if (quizAttempts == null) null else CourseLatestQuizProgress.Unavailable)
                ensureCurrent(identity, generation)
                row
            }
            val latest = catalog(bookId)?.snapshot()
            ensureCurrent(identity, generation)
            if (latest != source) {
                publish(identity, generation, CourseBookUiState(false, source.book.title, error = "COURSE_CHANGED"))
                return
            }
            publish(identity, generation, CourseBookUiState(false, source.book.title, rows, continueReading = progress.continueReading))
            startQuizObservation(identity, generation, user, source, readingSequence)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ensureCurrent(identity, generation)
            update(identity, generation) { current -> current.copy(loading = false, error = "COURSE_DIRECTORY_LOAD_FAILED",
                rows = current.rows.withoutQuizSummary(), combinedQuizzes = emptyList()) }
        } finally {
            synchronized(jobLock) {
                if (loadJob === loadingJob) loadJob = null
            }
        }
    }

    private fun startQuizObservation(
        identity: SessionIdentity,
        generation: Long,
        user: Long,
        source: CourseCatalogSnapshot,
        readingSequence: ReadingSequence,
    ) {
        synchronized(jobLock) {
            if (!isCurrent(identity, generation) || state.value.closed || state.value.loading || state.value.error != null) return
            quizJob?.cancel()
            // A sibling scope keeps the metadata job finite even when the DAO flow never completes.
            quizJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    ensureCurrent(identity, generation)
                    val documentIds = readingSequence.orderedTargets.map { it.documentId }.distinct()
                    val latestFlow: Flow<List<CourseQuizAttemptSummary>?> = quizAttempts?.let { observe ->
                        observe(user, documentIds).map { it.toList() }
                    } ?: flowOf(null)
                    combine(studyProgress(user), latestFlow) { legacy, attempts ->
                        legacy.toList() to attempts?.toList()
                    }.collect { (progressRows, attemptRows) ->
                        ensureCurrent(identity, generation)
                        val latest = catalog(bookId)?.snapshot()
                        ensureCurrent(identity, generation)
                        if (latest != source) {
                            update(identity, generation) { current -> current.copy(
                                loading = false,
                                error = "COURSE_CHANGED",
                                quizError = null,
                                rows = current.rows.withoutQuizSummary(),
                                combinedQuizzes = emptyList(),
                            ) }
                            throw CancellationException("Course catalog changed")
                        }
                        val summaries = CourseQuizProgressProjector.project(user, source, readingSequence, progressRows)
                        val latestSummaries = attemptRows?.let {
                            CourseLatestQuizProjector.project(user, source, readingSequence, it)
                        }
                        val combined = attemptRows?.let {
                            CourseLatestQuizProjector.projectCombined(user, source, readingSequence, it)
                        }.orEmpty()
                        ensureCurrent(identity, generation)
                        update(identity, generation) { current ->
                            if (current.loading || current.error != null) current else current.copy(
                                quizError = null,
                                combinedQuizzes = combined,
                                rows = current.rows.map { row -> row.copy(
                                    quizProgress = summaries[row.target] ?: CourseQuizProgress.Unavailable,
                                    latestQuiz = latestSummaries?.get(row.target)
                                        ?: if (quizAttempts == null) null else CourseLatestQuizProgress.Unavailable,
                                ) },
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ensureCurrent(identity, generation)
                    update(identity, generation) { current ->
                        if (current.loading || current.error != null) current else current.copy(
                            quizError = "COURSE_QUIZ_LOAD_FAILED",
                            rows = current.rows.withoutQuizSummary(),
                            combinedQuizzes = emptyList(),
                        )
                    }
                }
            }
        }
    }

    private suspend fun ensureCurrent(identity: SessionIdentity, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(identity, generation) || state.value.closed) throw CancellationException("Course directory load superseded")
    }

    private fun isCurrent(identity: SessionIdentity, generation: Long) =
        stateCommitter.isCurrent(identity, generation)

    private fun update(identity: SessionIdentity, generation: Long, transform: (CourseBookUiState) -> CourseBookUiState) {
        stateCommitter.update(identity, generation, transform)
    }

    private fun publish(identity: SessionIdentity, generation: Long, next: CourseBookUiState) {
        update(identity, generation) { next }
    }

    private fun CourseCatalogSnapshot.snapshot() = copy(chapters = chapters.toList())
    private fun List<CourseBookRow>.withoutQuizSummary() = map { it.copy(quizProgress = CourseQuizProgress.Unavailable,
        latestQuiz = if (quizAttempts == null) null else CourseLatestQuizProgress.Unavailable) }

    override fun onCleared() {
        synchronized(jobLock) {
            loadGeneration.incrementAndGet()
            loadJob?.cancel()
            quizJob?.cancel()
            loadJob = null
            quizJob = null
        }
        super.onCleared()
    }

}

