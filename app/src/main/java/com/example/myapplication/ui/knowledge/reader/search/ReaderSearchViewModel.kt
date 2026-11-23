package com.example.myapplication.ui.knowledge.reader.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.reader.search.*
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Pages view an accumulated, deduplicated original-position cache. PARTIAL counts are lower bounds. */
data class ReaderSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val querySeq: Long = 0L,
    val hits: List<SearchHit> = emptyList(),
    val groups: List<SearchSectionGroup> = emptyList(),
    val totalHits: Int = 0,
    val coverage: SearchCoverage? = null,
    val pageOffset: Int = 0,
    val pageSize: Int = BookSearchQuery.DEFAULT_PAGE_SIZE,
    val selectedIndex: Int = 0,
    val progress: SearchProgress? = null,
    val error: String? = null,
    val hasSearched: Boolean = false,
    val contentRevision: String? = null,
    val options: ExactMatchOptions = ExactMatchOptions(),
    val cachedHitCount: Int = 0,
    val canContinueScan: Boolean = false,
    val navigationEnabled: Boolean = false,
    val warnings: List<String> = emptyList(),
) {
    val hasNextPage: Boolean get() = !isSearching &&
        (pageOffset + hits.size < cachedHitCount || (error == null && canContinueScan))
    val hasPreviousPage: Boolean get() = !isSearching && pageOffset > 0
    val isEmptyResult: Boolean get() = hasSearched && !isSearching && totalHits == 0 &&
        coverage == SearchCoverage.FULL && !canContinueScan && error == null
    fun groupHitCount(sectionId: String): Int = groups.firstOrNull { it.sectionId == sectionId }?.hitCount ?: 0
}

/** Query/options/identity events change generation; fetch seq never belongs to a cursor.
 * Every identity emission matters, including equal session values for a document revision change. */
class ReaderSearchViewModel(
    private val searcher: BookSearcher,
    private val documentId: String,
    private val userIdProvider: suspend () -> Long,
    taskScope: CoroutineScope? = null,
    private val identityFlow: Flow<SessionIdentity>? = null,
    private val validateLocator: suspend (SessionIdentity, ReaderLocator) -> Boolean = { _, _ -> true },
) : ViewModel() {
    private val searchScope = taskScope ?: viewModelScope
    private val _state = MutableStateFlow(ReaderSearchUiState())
    val state: StateFlow<ReaderSearchUiState> = _state.asStateFlow()
    private val sequenceGate = SearchSequenceGate()
    private var generation = 0L
    private var identityReady = identityFlow == null
    private var currentIdentity: SessionIdentity? = null
    private var resultsIdentity: SessionIdentity? = null
    private var resultsGeneration = -1L
    private var searchJob: Job? = null
    private var debounceJob: Job? = null
    private var navigationJob: Job? = null
    private var navigationSeq = 0L
    private var nextCursor: ExactMatchCursor? = null
    private val cache = LinkedHashMap<PositionKey, SearchHit>()

    init {
        identityFlow?.let { identities -> searchScope.launch {
            try {
                identities.collect { identity ->
                    val initialized = identityReady
                    currentIdentity = identity
                    identityReady = true
                    invalidateResults(if (initialized) "IDENTITY_CHANGED" else null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                identityReady = false
                currentIdentity = null
                invalidateResults("IDENTITY_UNAVAILABLE")
            }
        } }
    }

    fun onQueryChanged(text: String) {
        if (text == _state.value.query) return
        invalidateResults()
        _state.update { it.copy(query = text) }
        scheduleSearch()
    }

    fun onOptionsChanged(options: ExactMatchOptions) {
        if (options == _state.value.options) return
        invalidateResults()
        _state.update { it.copy(options = options) }
        scheduleSearch()
    }

    private fun scheduleSearch() {
        if (_state.value.query.isBlank()) return
        debounceJob = searchScope.launch { delay(SEARCH_DEBOUNCE_MS); submit() }
    }

    fun submit() {
        invalidateResults()
        if (_state.value.query.isBlank()) return
        fetch(0, null)
    }

    fun nextPage() = moveNext(PendingSelection.FIRST)
    private fun moveNext(pending: PendingSelection) {
        val current = _state.value
        if (!current.hasNextPage) return
        val target = current.pageOffset + current.pageSize
        if (target < cache.size) showPage(target, pending)
        else nextCursor?.let {
            // A short/empty batch fills this page; a full page advances to the next one.
            fetch(if (current.hits.size < current.pageSize) current.pageOffset else target, it, pending)
        }
    }

    fun previousPage() = movePrevious(PendingSelection.FIRST)
    private fun movePrevious(pending: PendingSelection) {
        val current = _state.value
        if (!current.hasPreviousPage) return
        showPage((current.pageOffset - current.pageSize).coerceAtLeast(0), pending)
    }

    fun selectHit(index: Int) {
        if (index in _state.value.hits.indices) _state.update { it.copy(selectedIndex = index) }
    }

    fun stepSelection(delta: Int): Int? {
        val current = _state.value
        if (current.isSearching || current.hits.isEmpty()) return null
        val next = current.selectedIndex + delta
        if (next in current.hits.indices) { selectHit(next); return next }
        if (delta > 0 && current.hasNextPage) { moveNext(PendingSelection.FIRST); return null }
        if (delta < 0 && current.hasPreviousPage) {
            movePrevious(PendingSelection.LAST)
            return _state.value.selectedIndex
        }
        return current.selectedIndex
    }

    /** Decode an admitted cached position only. Production navigation must use openHit. */
    fun locatorAt(index: Int): ReaderLocator? {
        val current = _state.value
        if (!identityReady || !current.navigationEnabled || resultsGeneration != generation ||
            (identityFlow != null && currentIdentity != resultsIdentity)) return null
        val hit = current.hits.getOrNull(index) ?: return null
        return ReaderLocatorCodec.decodeOrNull(hit.locatorJson)?.takeIf {
            it.documentId == documentId && it.contentRevision == current.contentRevision && it.sectionId == hit.sectionId
        }
    }

    /** Recheck generation/identity/position after asynchronous validation; cancellation alone is insufficient. */
    fun openHit(index: Int, onOpenLocator: (ReaderLocator) -> Unit) {
        val locator = locatorAt(index) ?: return
        val identity = resultsIdentity ?: return
        val capturedGeneration = generation
        val seq = ++navigationSeq
        navigationJob?.cancel()
        fun admitted() = seq == navigationSeq && generation == capturedGeneration &&
            resultsIdentity == identity && locatorAt(index) == locator
        navigationJob = searchScope.launch {
            try {
                if (!admitted()) return@launch
                if (identityFlow == null && userIdProvider() != identity.userId) return@launch
                val allowed = validateLocator(identity, locator)
                if (!admitted()) return@launch
                if (!allowed) { invalidateResults("LOCATOR_INVALID"); return@launch }
                if (identityFlow == null && userIdProvider() != identity.userId) return@launch
                if (admitted()) onOpenLocator(locator)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (admitted()) _state.update { it.copy(error = "NAVIGATION_FAILED") }
            }
        }
    }

    /** Explicit stop preserves admitted cache and the last successfully returned cursor. */
    fun cancelSearch() {
        cancelTasks()
        _state.update { it.copy(isSearching = false, progress = null) }
    }

    /** Invalidation uses this without publishing an intermediate view of the previous identity's data. */
    private fun cancelTasks() {
        sequenceGate.next()
        searchJob?.cancel(); searchJob = null
        debounceJob?.cancel(); debounceJob = null
        navigationSeq++
        navigationJob?.cancel(); navigationJob = null
    }

    private fun invalidateResults(error: String? = null) {
        generation++
        cancelTasks()
        cache.clear(); nextCursor = null; resultsIdentity = null; resultsGeneration = -1L
        _state.update {
            ReaderSearchUiState(query = it.query, options = it.options, pageSize = it.pageSize,
                querySeq = sequenceGate.latest(), error = error)
        }
    }

    private fun fetch(offset: Int, cursor: ExactMatchCursor?, pending: PendingSelection = PendingSelection.FIRST) {
        if (!identityReady) { _state.update { it.copy(error = "IDENTITY_PENDING") }; return }
        val capturedIdentity = currentIdentity
        if (identityFlow != null && (capturedIdentity?.userId ?: 0L) <= 0L) {
            invalidateResults("IDENTITY_UNAVAILABLE"); return
        }
        val query = _state.value.query
        val options = _state.value.options
        val capturedGeneration = generation
        val seq = sequenceGate.next()
        val pageSize = _state.value.pageSize
        searchJob?.cancel()
        fun admitted() = generation == capturedGeneration && sequenceGate.accept(seq) &&
            _state.value.query == query && _state.value.options == options &&
            (identityFlow == null || (identityReady && currentIdentity == capturedIdentity))
        _state.update { it.copy(isSearching = true, querySeq = seq, progress = null, error = null) }
        searchJob = searchScope.launch {
            try {
                val identity = capturedIdentity ?: SessionIdentity(userIdProvider(), 0)
                if (!admitted()) return@launch
                if ((identity.userId ?: 0L) <= 0L) { invalidateResults("IDENTITY_UNAVAILABLE"); return@launch }
                val outcome = searcher.searchExact(
                    userId = identity.userId!!, documentId = documentId, query = query, querySeq = seq,
                    offset = 0, limit = pageSize, latestQuerySeq = sequenceGate::latest,
                    onProgress = { progress -> if (admitted()) _state.update { it.copy(progress = progress) } },
                    cursor = cursor, options = options,
                )
                if (!admitted()) return@launch
                when (outcome) {
                    is BookSearchOutcome.Ok -> applyResult(outcome.result, identity, seq, query, cursor, offset, pending)
                    is BookSearchOutcome.Rejected -> invalidateResults(outcome.code)
                    is BookSearchOutcome.Superseded -> _state.update { it.copy(isSearching = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (admitted()) _state.update { it.copy(isSearching = false, progress = null, error = SEARCH_FAILED) }
            } finally {
                if (admitted()) _state.update { it.copy(isSearching = false, progress = null) }
            }
        }
    }

    private fun applyResult(result: BookSearchResult, identity: SessionIdentity, seq: Long, query: String,
        cursor: ExactMatchCursor?, offset: Int, pending: PendingSelection) {
        val revision = _state.value.contentRevision
        if (result.documentId != documentId || result.querySeq != seq || result.query != query ||
            result.contentRevision.isBlank() || (cursor != null && revision != result.contentRevision)) {
            invalidateResults("RESULT_STALE"); return
        }
        val decoded = result.hits.map { hit -> hit to ReaderLocatorCodec.decodeOrNull(hit.locatorJson) }
        if (decoded.any { (hit, locator) -> locator == null || locator.documentId != documentId ||
                locator.contentRevision != result.contentRevision || locator.sectionId != hit.sectionId }) {
            invalidateResults("LOCATOR_INVALID"); return
        }
        decoded.forEach { (hit, locator) ->
            val key = PositionKey(hit.sectionId, hit.pageIndex, (locator as? ReaderLocator.Reflow)?.blockId,
                hit.canonicalStart, hit.canonicalEnd)
            cache.putIfAbsent(key, hit)
        }
        nextCursor = result.nextCursor
        resultsIdentity = identity
        resultsGeneration = generation
        // Cursor absence alone is not completeness: an interrupted scan can lack one.
        val completeClaim = result.coverage == SearchCoverage.FULL && nextCursor == null && !result.truncated
        val full = completeClaim && result.totalHits == cache.size
        val countWarnings = if (completeClaim && !full) listOf("RESULT_COUNT_MISMATCH") else emptyList()
        // Completion is one immutable snapshot: observers must never see FULL/counts with an old page.
        _state.update {
            withCachedPage(it.copy(totalHits = cache.size, cachedHitCount = cache.size,
                coverage = if (full) SearchCoverage.FULL else SearchCoverage.PARTIAL,
                contentRevision = result.contentRevision, hasSearched = true, navigationEnabled = true,
                canContinueScan = nextCursor != null, isSearching = false, progress = null, error = null,
                warnings = (it.warnings + result.warnings + countWarnings).distinct()), offset, pending)
        }
    }

    private fun showPage(offset: Int, pending: PendingSelection) {
        _state.update { withCachedPage(it, offset, pending) }
    }

    private fun withCachedPage(current: ReaderSearchUiState, offset: Int,
        pending: PendingSelection): ReaderSearchUiState {
        val ordered = cache.values.sortedWith(compareBy<SearchHit> { it.sectionOrder }
            .thenBy { it.pageIndex ?: -1 }.thenBy { it.canonicalStart }.thenBy { it.canonicalEnd })
        val pageSize = current.pageSize
        val safeOffset = if (ordered.isEmpty()) 0 else offset.coerceAtMost(((ordered.size - 1) / pageSize) * pageSize)
        val hits = ordered.drop(safeOffset).take(pageSize)
        val groups = ordered.groupBy { it.sectionId }.map { (id, hitsInSection) ->
            val first = hitsInSection.first()
            SearchSectionGroup(id, first.sectionTitle, first.sectionOrder, hitsInSection.size)
        }
        return current.copy(hits = hits, groups = groups, pageOffset = safeOffset,
            selectedIndex = if (pending == PendingSelection.LAST) hits.lastIndex.coerceAtLeast(0) else 0)
    }

    private data class PositionKey(val section: String, val page: Int?, val block: String?, val start: Int, val end: Int)
    private enum class PendingSelection { FIRST, LAST }
    companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
        const val SEARCH_FAILED = "SEARCH_FAILED"
    }
}

class ReaderSearchViewModelFactory(private val context: Context, private val documentId: String) : ViewModelProvider.Factory {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ReaderSearchViewModel::class.java))
        val container = (context.applicationContext as LearningApplication).container
        val catalog = container.database.readerCatalogDao()
        var observedDocument: DocumentIdentity? = null
        var observedGeneration = 0L
        val identityEvents = container.sessionManager.identity.distinctUntilChanged().flatMapLatest { identity ->
            observedDocument = null
            observedGeneration++
            val user = identity.userId
            if (user == null) flowOf(identity)
            else catalog.observeVisibleDocuments(user).map { documents ->
                documents.firstOrNull { it.id == documentId }?.let {
                    DocumentIdentity(it.id, it.ownerUserId, it.kind, it.contentRevision, it.accessEpoch, it.importStatus)
                }
            }.distinctUntilChanged().map { document ->
                observedDocument = document
                observedGeneration++
                // Emit even for the same session value: the source doc/rev/epoch has changed.
                identity
            }.onStart { emit(identity) }
        }.buffer(0)
        return ReaderSearchViewModel(
            searcher = BookSearchService(container.database, container.lexicalIndex, container.roomDocumentAccess),
            documentId = documentId,
            userIdProvider = { container.sessionManager.identity.first().userId ?: 0L },
            identityFlow = identityEvents,
            validateLocator = validate@{ identity, locator ->
                val user = identity.userId ?: return@validate false
                val original = observedDocument ?: return@validate false
                val capturedGeneration = observedGeneration
                fun current() = capturedGeneration == observedGeneration && original == observedDocument
                if (locator.documentId != documentId || locator.contentRevision != original.revision ||
                    !current() || container.sessionManager.identity.first() != identity) return@validate false
                val ctx = AccessContext(user, identity.epoch, UUID.randomUUID().toString())
                val opened = container.roomDocumentAccess.open(ctx, documentId, locator.contentRevision)
                if (opened !is OperationResult.Ok || opened.value.accessEpoch != original.accessEpoch ||
                    opened.value.ownerUserId != original.owner ||
                    opened.value.documentId != documentId || opened.value.contentRevision != locator.contentRevision ||
                    !current() || container.sessionManager.identity.first() != identity) return@validate false
                val range = container.roomDocumentAccess.readRange(ctx, opened.value.snapshotId, locator)
                val latest = container.roomDocumentAccess.open(ctx, documentId, locator.contentRevision)
                range is OperationResult.Ok && range.value.documentId == documentId &&
                    range.value.contentRevision == locator.contentRevision && range.value.locator == locator &&
                    latest is OperationResult.Ok && latest.value.ownerUserId == original.owner &&
                    latest.value.documentId == documentId && latest.value.contentRevision == locator.contentRevision &&
                    latest.value.accessEpoch == original.accessEpoch && current() &&
                    container.sessionManager.identity.first() == identity
            },
        ) as T
    }

    private data class DocumentIdentity(val document: String, val owner: Long?, val kind: String,
        val revision: String, val accessEpoch: Long, val status: String)
}
