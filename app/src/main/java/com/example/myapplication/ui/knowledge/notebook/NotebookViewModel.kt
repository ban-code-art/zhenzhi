package com.example.myapplication.ui.knowledge.notebook

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.local.entity.ReaderNoteVersionEntity
import com.example.myapplication.data.reader.notes.NoteRepository
import com.example.myapplication.data.reader.notes.NoteSaveOutcome
import com.example.myapplication.data.reader.notes.NoteSequenceGuard
import com.example.myapplication.data.reader.notes.ReaderNoteLoad
import com.example.myapplication.data.reader.notes.ReaderAnnotationView
import com.example.myapplication.data.reader.notes.ReaderMutationHasher
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.SourceEvidence
import com.example.myapplication.domain.reader.OperationResult
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class NoteSaveStatus { IDLE, DIRTY, SAVING, SAVED, CONFLICT, ERROR }

data class NotebookConflict(
    val currentRevision: Long,
    val currentBody: String,
    val draftBody: String?,
)

data class NotebookUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val documentId: String = "",
    val notebookId: String? = null,
    val title: String = "笔记本",
    val content: TextFieldValue = TextFieldValue(""),
    val revision: Long = 0,
    val isDirty: Boolean = false,
    val status: NoteSaveStatus = NoteSaveStatus.IDLE,
    val statusText: String = "尚未创建笔记",
    val conflict: NotebookConflict? = null,
    val versions: List<ReaderNoteVersionEntity> = emptyList(),
    val showVersions: Boolean = false,
    val materials: List<ReaderAnnotationView> = emptyList(),
    val showMaterials: Boolean = false,
)

class NotebookViewModel(
    private val application: LearningApplication,
    private val documentId: String,
) : ViewModel() {
    private val container = application.container
    private val repository = container.noteRepository
    private val annotationRepository = container.annotationRepository
    private var materialsJob: Job? = null
    private val _state = MutableStateFlow(NotebookUiState(documentId = documentId))
    val state: StateFlow<NotebookUiState> = _state.asStateFlow()
    private val saveMutex = Mutex()
    private var userId: Long? = null
    private var editorId: String = "editor-${UUID.randomUUID()}"
    private var editorSequence: Long = 0
    private var seededNotebookId: String? = null
    private var draftJob: Job? = null
    private var saveJob: Job? = null

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val id = container.sessionManager.currentUserId.filterNotNull().first()
            userId = id
            val loaded = repository.load(accessContext(id), documentId)
            if (loaded == null) {
                _state.update { it.copy(isLoading = false, error = "无法打开笔记") }
                return@launch
            }
            applyLoaded(loaded)
            observeMaterials(id)
        }
    }

    private suspend fun applyLoaded(loaded: ReaderNoteLoad) {
        val notebook = loaded.notebook
        val draft = loaded.draft
        if (draft != null) editorId = draft.editorId
        val body = if (draft != null && draft.body != notebook?.body) draft.body else notebook?.body.orEmpty()
        editorSequence = maxOf(editorSequence, draft?.editorSequence ?: 0)
        _state.update {
            it.copy(
                isLoading = false,
                title = if (notebook == null) "新建笔记" else "笔记本",
                notebookId = notebook?.notebookId ?: draft?.notebookId,
                content = TextFieldValue(body, TextRange(body.length)),
                revision = notebook?.revision ?: 0,
                isDirty = draft != null && draft.body != notebook?.body,
                status = if (draft != null && draft.body != notebook?.body) NoteSaveStatus.DIRTY else NoteSaveStatus.IDLE,
                statusText = when {
                    draft != null && draft.body != notebook?.body -> "已恢复未提交草稿"
                    notebook == null -> "输入后将创建笔记本"
                    else -> "已保存"
                },
            )
        }
        if (notebook != null) loadVersions()
        // 拿到 notebookId 后把序列种回该编辑器已持久化的值，避免跨会话小序列被旧大序列误杀。
        seedEditorSequence(_state.value.notebookId)
    }

    /**
     * 跨会话单调：把内存编辑器序列抬到该编辑器持久化的 savedSequence（只抬不降）。
     *
     * UI 的 editorSequence 是内存计数器，重新进入会话从 0 自增；若同一 editorId（例如从草稿恢复）
     * 之前已用更大序列保存过，旧 receipt 会把后续保存误判为乱序写（STALE_SEQUENCE）。
     */
    private suspend fun seedEditorSequence(notebookId: String?) {
        if (notebookId.isNullOrBlank() || notebookId == seededNotebookId) return
        val id = userId ?: return
        val persisted = repository.latestSavedSequence(accessContext(id), notebookId, editorId)
        editorSequence = NoteSequenceGuard.seedSequence(editorSequence, persisted)
        seededNotebookId = notebookId
    }

    fun onValueChange(value: TextFieldValue) {
        if (value.text.length > MAX_BODY_CHARS) return
        editorSequence += 1
        _state.update {
            it.copy(content = value, isDirty = true, status = NoteSaveStatus.DIRTY, statusText = "编辑中…")
        }
        scheduleDraft()
        scheduleSave()
    }

    fun insertMarkdown(prefix: String, suffix: String = "") {
        val current = _state.value.content
        val start = minOf(current.selection.start, current.selection.end).coerceIn(0, current.text.length)
        val end = maxOf(current.selection.start, current.selection.end).coerceIn(start, current.text.length)
        val selected = current.text.substring(start, end)
        val replacement = "$prefix$selected$suffix"
        val newText = current.text.replaceRange(start, end, replacement)
        val cursor = if (selected.isEmpty()) (start + prefix.length).coerceAtMost(newText.length) else (start + replacement.length).coerceAtMost(newText.length)
        onValueChange(TextFieldValue(newText, TextRange(cursor)))
    }

    fun showMaterials(show: Boolean) {
        _state.update { it.copy(showMaterials = show) }
    }

    fun insertMaterial(annotation: ReaderAnnotationView) {
        val id = userId ?: return
        val locator = annotation.locator ?: return
        val quote = annotation.quote?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val evidence = SourceEvidence(
                evidenceId = "annotation-${annotation.id}",
                documentId = documentId,
                sectionId = annotation.sectionId ?: locator.sectionId,
                contentRevision = locator.contentRevision,
                locator = locator,
                quote = quote,
                quoteSha256 = ReaderMutationHasher.sha256(quote),
                sourceTitleSnapshot = documentId,
                extractorVersion = "notebook-v1",
            )
            val result = annotationRepository.registerSourceReference(accessContext(id), evidence)
            if (result is OperationResult.Ok) {
                val ref = result.value
                insertMarkdown(
                    "> ${quote.replace("\n", "\n> ")}\n>\n> 来源：${documentId}\n\n[返回原文](aculearn://source/${ref.id})\n",
                )
                _state.update { it.copy(showMaterials = false) }
            } else {
                _state.update { it.copy(statusText = "无法登记来源引用") }
            }
        }
    }

    private fun observeMaterials(id: Long) {
        materialsJob?.cancel()
        materialsJob = viewModelScope.launch {
            annotationRepository.observeAnnotations(id, documentId).collectLatest { values ->
                _state.update { it.copy(materials = values.filter { annotation -> annotation.quote != null }) }
            }
        }
    }

    fun showVersions(show: Boolean) {
        _state.update { it.copy(showVersions = show) }
        if (show) loadVersions()
    }

    fun loadVersions() {
        val notebookId = _state.value.notebookId ?: return
        val id = userId ?: return
        viewModelScope.launch {
            val versions = repository.listVersions(accessContext(id), notebookId)
            _state.update { it.copy(versions = versions) }
        }
    }

    fun restoreVersion(version: ReaderNoteVersionEntity) {
        val id = userId ?: return
        val notebookId = _state.value.notebookId ?: return
        viewModelScope.launch {
            saveMutex.withLock {
                val result = repository.restoreVersion(
                    context = accessContext(id),
                    notebookId = notebookId,
                    versionRevision = version.revision,
                    baseRevision = _state.value.revision,
                    editorSequence = ++editorSequence,
                    editorId = editorId,
                    actor = "USER",
                )
                handleSaveOutcome(result, editorSequence)
            }
        }
    }

    fun resolveConflictUseServer() {
        val conflict = _state.value.conflict ?: return
        _state.update {
            it.copy(
                content = TextFieldValue(conflict.currentBody, TextRange(conflict.currentBody.length)),
                revision = conflict.currentRevision,
                isDirty = false,
                conflict = null,
                status = NoteSaveStatus.IDLE,
                statusText = "已采用服务端版本",
            )
        }
    }

    fun resolveConflictKeepLocal() {
        val conflict = _state.value.conflict ?: return
        _state.update {
            it.copy(revision = conflict.currentRevision, conflict = null, isDirty = true, status = NoteSaveStatus.DIRTY, statusText = "正在保存本地修改…")
        }
        viewModelScope.launch { commitNow() }
    }

    fun dismissConflict() {
        _state.update { it.copy(conflict = null) }
    }

    fun flushBeforeExit(onDone: () -> Unit) {
        val id = userId ?: run { onDone(); return }
        viewModelScope.launch {
            flushDraftNow(id)
            if (_state.value.isDirty && _state.value.content.text.isNotBlank()) commitNow(id)
            onDone()
        }
    }

    fun saveNow() {
        val id = userId ?: return
        viewModelScope.launch { commitNow(id) }
    }

    private fun scheduleDraft() {
        draftJob?.cancel()
        val id = userId ?: return
        draftJob = viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            flushDraftNow(id)
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        val id = userId ?: return
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            commitNow(id)
        }
    }

    private suspend fun flushDraftNow(id: Long) {
        val snapshot = _state.value
        if (snapshot.content.text.isBlank()) return
        val result = repository.flushDraft(
            context = accessContext(id),
            documentId = documentId,
            notebookId = snapshot.notebookId,
            editorId = editorId,
            editorSequence = editorSequence,
            baseRevision = snapshot.revision,
            body = snapshot.content.text,
        )
        if (result is OperationResult.Ok) {
            _state.update { it.copy(notebookId = result.value.notebookId) }
            // 首次新建笔记本时才拿到 notebookId，这里补种一次序列。
            seedEditorSequence(result.value.notebookId)
        }
    }

    private suspend fun commitNow(id: Long? = null) {
        val effectiveUserId = id ?: userId ?: return
        saveMutex.withLock {
            val snapshot = _state.value
            if (snapshot.content.text.isBlank() && snapshot.notebookId == null) {
                _state.update { it.copy(status = NoteSaveStatus.IDLE, statusText = "输入后将创建笔记本") }
                return@withLock
            }
            val sequence = editorSequence
            _state.update { it.copy(status = NoteSaveStatus.SAVING, statusText = "保存中…") }
            val result = repository.save(
                context = accessContext(effectiveUserId),
                documentId = documentId,
                notebookId = snapshot.notebookId,
                baseRevision = snapshot.revision,
                editorId = editorId,
                editorSequence = sequence,
                body = snapshot.content.text,
                actor = "USER",
            )
            handleSaveOutcome(result, sequence)
        }
    }

    private fun handleSaveOutcome(result: NoteSaveOutcome, sequence: Long = editorSequence) {
        when (result) {
            is NoteSaveOutcome.Saved -> {
                val current = _state.value
                val sameEditorState = sequence == editorSequence
                _state.update {
                    it.copy(
                        notebookId = result.ack.notebookId,
                        revision = result.ack.revision,
                        isDirty = if (sameEditorState) false else it.isDirty,
                        status = if (sameEditorState) NoteSaveStatus.SAVED else NoteSaveStatus.DIRTY,
                        statusText = if (sameEditorState) "已保存" else "已保存较早版本，继续编辑…",
                    )
                }
                loadVersions()
            }

            is NoteSaveOutcome.Conflict -> _state.update {
                it.copy(
                    status = NoteSaveStatus.CONFLICT,
                    statusText = "内容已在其他编辑器更新",
                    conflict = NotebookConflict(result.currentRevision, result.currentBody, result.draftBody),
                )
            }

            is NoteSaveOutcome.Rejected -> _state.update {
                it.copy(status = NoteSaveStatus.ERROR, statusText = "无法保存：${result.code}")
            }

            is NoteSaveOutcome.Failed -> _state.update {
                it.copy(status = NoteSaveStatus.ERROR, statusText = "保存失败，请重试")
            }
        }
    }

    private fun accessContext(id: Long): AccessContext = AccessContext(id, 0, UUID.randomUUID().toString())

    companion object {
        private const val DRAFT_DEBOUNCE_MS = 500L
        private const val SAVE_DEBOUNCE_MS = 800L
        private const val MAX_BODY_CHARS = 8_000_000
    }
}

class NotebookViewModelFactory(
    private val context: Context,
    private val documentId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        NotebookViewModel(context.applicationContext as LearningApplication, documentId) as T
}




