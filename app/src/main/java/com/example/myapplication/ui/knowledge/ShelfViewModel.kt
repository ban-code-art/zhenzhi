package com.example.myapplication.ui.knowledge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.local.entity.ReaderJobEntity
import com.example.myapplication.data.reader.ReaderShelfItem
import com.example.myapplication.data.reader.export.BackupArchive
import com.example.myapplication.data.reader.export.ExportOutcome
import com.example.myapplication.data.reader.export.ExportRequest
import com.example.myapplication.data.reader.export.RestoreOutcome
import com.example.myapplication.data.reader.export.RestorePreview
import com.example.myapplication.data.reader.export.RestoreReport
import com.example.myapplication.data.reader.importer.ImportBatchPlanner
import com.example.myapplication.data.reader.importer.ImportEnqueueResult
import com.example.myapplication.data.reader.importer.ImportSource
import com.example.myapplication.data.reader.importer.ReaderImportScheduler
import com.example.myapplication.data.reader.importer.ImportCancellationPendingException
import com.example.myapplication.data.reader.importer.SafImportAttachments
import com.example.myapplication.domain.reader.OperationResult
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.AccessContext

data class ShelfUiState(
    val isLoading: Boolean = true,
    val documents: List<ReaderShelfItem> = emptyList(),
    val courseBooks: List<com.example.myapplication.data.local.entity.CourseBookEntity> = emptyList(),
    val jobs: List<ReaderJobEntity> = emptyList(),
    val trash: List<ReaderShelfItem> = emptyList(),
    val query: String = "",
    val message: String? = null,
    val backup: BackupUiState = BackupUiState(),
    val reparsingDocumentId: String? = null,
    val navigation: ShelfNavigationState = ShelfNavigationState(0, 0),
    val navigationVersion: Long = 0,
    val shelfError: String? = null,
) {
    val filteredImportedDocuments: List<ReaderShelfItem>
        get() = ShelfNavigationState.project(documents, query, navigation.sort)
    val filteredDocuments: List<ReaderShelfItem>
        get() = if (query.isBlank()) documents else documents.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.author?.contains(query, ignoreCase = true) == true
        }

    val filteredCourseBooks: List<com.example.myapplication.data.local.entity.CourseBookEntity>
        get() = if (query.isBlank()) courseBooks else courseBooks.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.summary?.contains(query, ignoreCase = true) == true
        }
}

/**
 * 导出/恢复的界面状态（W5-05 / W5-06 的 UI 接线）。
 *
 * 只放"能显示的事实"：条数、大小、冲突/损坏计数。原始 manifest、包内路径、墨迹内容
 * 一律不进入 UI 状态，避免被日志/截屏带出去。
 */
data class BackupUiState(
    val exporting: Boolean = false,
    val exportingTitle: String? = null,
    val exportResult: ExportPackageUi? = null,
    val restoring: Boolean = false,
    val restorePreview: RestorePreview? = null,
    val restoreReport: RestoreReport? = null,
    val error: String? = null,
) {
    val busy: Boolean get() = exporting || restoring
    val hasRestorePackage: Boolean get() = restorePreview != null
}

/** 一次成功导出后可分享的结果（zip 已落在 cache/exports 内）。 */
data class ExportPackageUi(
    val exportId: String,
    val zip: java.io.File,
    val bytes: Long,
    val documentCount: Int,
    val noteFileCount: Int,
    val annotationCount: Int,
    val inkPageCount: Int,
    val rejectedDocuments: Int,
)

class ShelfViewModel(
    private val application: LearningApplication,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private var reparseJob: Job? = null
    private var ownerScope: CoroutineScope? = null
    private var ownerIdentity: SessionIdentity? = null
    private val reload = MutableStateFlow(0L)

    fun reparseMarkdown(documentId: String) {
        if (_state.value.reparsingDocumentId != null) return
        _state.update { it.copy(reparsingDocumentId = documentId) }
        reparseJob = ownerScope?.launch {
            try {
                val identity = container.sessionManager.identity.first()
                val owner = userId
                if (owner == null || identity.userId != owner) {
                    _state.update { it.copy(message = "登录状态已变化，请重新打开书架") }
                    return@launch
                }
                val document = container.database.readerCatalogDao().findDocument(documentId)
                if (document == null) {
                    _state.update { it.copy(message = "书籍已不可用") }; return@launch
                }
                val result = container.markdownReparser.reparse(
                    AccessContext(owner, identity.epoch, UUID.randomUUID().toString()), documentId, document.contentRevision)
                val indexingPending = if (result is OperationResult.Ok && result.value.changed) {
                    runCatching { container.scheduleIndexing(owner) }.onFailure {
                        android.util.Log.w("ShelfViewModel", "Reparse index scheduling deferred: ${it.javaClass.simpleName}")
                    }.isFailure
                } else false
                val message = when (result) {
                    is OperationResult.Ok -> if (result.value.changed) {
                        if (indexingPending) "正文已更新，旧标注已保留；索引将在稍后重试"
                        else "重新解析完成，旧标注已保留；阅读位置已重置"
                    } else "正文已是当前解析版本"
                    is OperationResult.Conflict -> "书籍已变化，请重新操作"
                    is OperationResult.Rejected -> if (result.code == "ACCESS_REVOKED") "登录状态已变化，请重新打开书架" else "无法重新解析，原书和标注已保留"
                    is OperationResult.Failed -> "重新解析失败，原书和标注已保留"
                }
                _state.update { it.copy(message = message) }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(message = "操作已停止，已保存内容和标注仍可使用") }
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(message = "重新解析失败，已保存的书籍和标注仍可使用") }
            } finally { _state.update { it.copy(reparsingDocumentId = null) } }
        }
    }

    fun cancelReparse() { reparseJob?.cancel() }
    private val container = application.container
    private val _state = MutableStateFlow(ShelfUiState())
    val state: StateFlow<ShelfUiState> = _state.asStateFlow()
    private var userId: Long? = null
    /** 待确认恢复的已解包目录（只在校验通过后设置）。 */
    private var restoreDirectory: java.io.File? = null

    init {
        viewModelScope.launch {
            combine(container.sessionManager.identity.distinctUntilChanged(), reload) { identity, _ -> identity }.collectLatest { identity ->
                // collectLatest cancels AND joins all old-owner work before exposing the new state.
                coroutineScope {
                    if (ownerIdentity != null) {
                        restoreDirectory?.deleteRecursively()
                        restoreDirectory = null
                    }
                    ownerIdentity = identity
                    userId = identity.userId
                    val navigation = ShelfNavigationState.restore(savedStateHandle[ShelfNavigationState.SAVED_KEY],
                        identity.userId ?: 0, identity.epoch)
                    val version = _state.value.navigationVersion + 1
                    _state.value = ShelfUiState(isLoading = identity.userId != null, query = navigation.query,
                        navigation = navigation, navigationVersion = version)
                    savedStateHandle[ShelfNavigationState.SAVED_KEY] = navigation.encode()
                    ownerScope = this
                    try {
                        val id = identity.userId ?: return@coroutineScope
                        launch {
                            try {
                                combine(container.readerRepository.observeShelf(id),
                                    container.database.readerJobDao().observeJobs(id),
                                    container.readerRepository.observeTrash(id),
                                    container.database.courseBookDao().observeBooks()) { documents, jobs, trash, books ->
                                    _state.update { it.copy(isLoading = false, documents = documents, jobs = jobs,
                                        trash = trash, courseBooks = books, shelfError = null) }
                                }.collect { }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { _state.update { it.copy(isLoading = false, shelfError = "书架加载失败，请重试") } }
                        }
                        launch { savedStateHandle.getStateFlow(ShelfNavigationState.SAVED_KEY, navigation.encode())
                            .collect { raw -> restoreNavigation(raw) } }
                        awaitCancellation()
                    } finally { ownerScope = null; reparseJob = null }
                }
            }
        }
    }

    fun setQuery(value: String) {
        val navigation = state.value.navigation.copy(query = value.take(1024), anchorKey = null, index = 0, offset = 0)
        setNavigation(navigation, restoreScroll = true)
    }

    fun reloadShelf() { reload.update { it + 1 } }

    fun setSort(sort: String) {
        if (sort !in ShelfNavigationState.SORTS) return
        setNavigation(state.value.navigation.copy(sort = sort, anchorKey = null, index = 0, offset = 0), true)
    }

    fun navigationSnapshot(): String = state.value.navigation.encode()

    /** Nav entry and VM have separate handles; consume the explicit bridge only after this owner is ready. */
    fun restoreIncomingNavigation(raw: String): Boolean {
        val identity = ownerIdentity ?: return false
        val owner = identity.userId ?: return false
        if (state.value.isLoading || state.value.shelfError != null ||
            state.value.navigation.ownerUserId != owner || state.value.navigation.sessionEpoch != identity.epoch) return false
        val matches = runCatching {
            if (raw.length > 32768) return@runCatching false
            val root = kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
                ?: return@runCatching false
            (root["ownerUserId"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() == owner &&
                (root["sessionEpoch"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() == identity.epoch
        }.getOrDefault(false)
        // A missing, malformed or old-owner nav entry cannot erase this owner's VM recovery.
        if (matches) {
            val incoming = ShelfNavigationState.restore(raw, owner, identity.epoch)
            if (incoming != state.value.navigation) setNavigation(incoming, true)
        }
        return true
    }

    fun restoreNavigation(raw: String) {
        val identity = ownerIdentity ?: return
        val restored = ShelfNavigationState.restore(raw, identity.userId ?: 0, identity.epoch)
        if (restored != state.value.navigation) setNavigation(restored, true, persist = false)
    }

    fun saveScroll(index: Int, offset: Int, anchorKey: String?, itemCount: Int) {
        if (state.value.isLoading || state.value.shelfError != null) return
        val navigation = state.value.navigation.capture(index, offset, anchorKey, itemCount)
        if (navigation != state.value.navigation) setNavigation(navigation, false)
    }

    private fun setNavigation(navigation: ShelfNavigationState, restoreScroll: Boolean, persist: Boolean = true) {
        val identity = ownerIdentity ?: return
        if (navigation.ownerUserId != (identity.userId ?: 0) || navigation.sessionEpoch != identity.epoch) return
        _state.update { it.copy(query = navigation.query, navigation = navigation,
            navigationVersion = it.navigationVersion + if (restoreScroll) 1 else 0) }
        if (persist) savedStateHandle[ShelfNavigationState.SAVED_KEY] = navigation.encode()
    }

    fun cancelImport(jobId: String) {
        val identity = ownerIdentity ?: return
        ownerScope?.launch {
            try {
                val cancelled = ReaderImportScheduler.cancel(application, jobId, identity)
                _state.update { it.copy(message = if (cancelled) "已取消导入" else "导入任务已经结束或登录状态已变化") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ImportCancellationPendingException) { _state.update { it.copy(message = "已取消导入，后台清理仍在完成") } }
            catch (_: Exception) { _state.update { it.copy(message = "取消失败，请重试") } }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        ownerScope?.launch {
            val id = userId ?: container.sessionManager.currentUserId.first() ?: return@launch
            val resolver = application.contentResolver
            // M04：同一次选择里若含 Markdown，图片文件作为其附件采集，而不是单独当书导入。
            val selected = uris.map { uri ->
                SafImportAttachments.Selected(uri, queryDisplayName(uri), runCatching { resolver.getType(uri) }.getOrNull())
            }
            val plan = ImportBatchPlanner.plan(selected.map { ImportBatchPlanner.Entry(it.displayName, it.mimeType) })
            val companions = plan.companions.map(selected::get)
            plan.books.map(selected::get).forEach { item ->
                val uri = item.uri
                val displayName = item.displayName
                runCatching {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val isMarkdown = ImportBatchPlanner.isMarkdown(ImportBatchPlanner.Entry(displayName, item.mimeType))
                val source = ImportSource(
                    displayName = displayName,
                    attachmentResolver = if (isMarkdown) SafImportAttachments.resolverFor(resolver, item, companions) else null,
                ) {
                    resolver.openInputStream(uri) ?: throw IOException("无法打开所选文件")
                }
                val result = runCatching {
                    container.importCoordinator.enqueue(
                        userId = id,
                        requestKey = "picker:${UUID.randomUUID()}",
                        source = source,
                    )
                }.getOrElse {
                    if (it is CancellationException) throw it
                    _state.update { state -> state.copy(message = "导入失败：${it.message ?: "未知错误"}") }
                    return@forEach
                }
                when (result) {
                    is ImportEnqueueResult.Accepted -> {
                        ReaderImportScheduler.enqueue(application, result.job.jobId)
                        _state.update { it.copy(message = "已加入导入队列：${displayName ?: "书籍"}") }
                    }

                    is ImportEnqueueResult.Duplicate -> {
                        if (result.job.status in setOf("PENDING", "RUNNING", "RETRY_WAIT")) {
                            ReaderImportScheduler.enqueue(application, result.job.jobId)
                        }
                        _state.update { it.copy(message = "该文件已在书架或导入队列中") }
                    }

                    is ImportEnqueueResult.Failed -> {
                        _state.update { it.copy(message = "导入失败：${result.code}") }
                    }
                }
            }
        }
    }

    fun rename(documentId: String, title: String) {
        ownerScope?.launch {
            val id = userId ?: return@launch
            when (val result = container.readerRepository.rename(id, documentId, title)) {
                is OperationResult.Rejected -> _state.update { it.copy(message = "无法重命名：${result.code}") }
                is OperationResult.Failed -> _state.update { it.copy(message = "重命名失败") }
                is OperationResult.Conflict -> _state.update { it.copy(message = "内容已变化，请重试") }
                is OperationResult.Ok -> _state.update { it.copy(message = "名称已更新") }
            }
        }
    }

    suspend fun renameDocument(documentId: String, title: String): OperationResult<Unit> {
        val clean = title.trim()
        if (clean.isBlank()) return OperationResult.Rejected("EMPTY_TITLE")
        val duplicate = _state.value.documents.any {
            it.documentId != documentId && it.title.trim() == clean
        }
        if (duplicate) return OperationResult.Rejected("DUPLICATE_TITLE")
        val id = userId ?: return OperationResult.Rejected("NOT_SIGNED_IN")
        return when (val result = container.readerRepository.rename(id, documentId, clean)) {
            is OperationResult.Ok -> {
                _state.update { it.copy(message = "名称已更新") }
                result
            }
            else -> result
        }
    }

    fun trash(documentId: String) {
        ownerScope?.launch {
            val id = userId ?: return@launch
            when (val result = container.readerRepository.trash(id, documentId)) {
                is OperationResult.Ok -> _state.update { it.copy(message = "已移到回收站") }
                is OperationResult.Rejected -> _state.update { it.copy(message = "无法移除：${result.code}") }
                is OperationResult.Failed -> _state.update { it.copy(message = "移除失败") }
                is OperationResult.Conflict -> _state.update { it.copy(message = "请稍后重试") }
            }
        }
    }

    fun restore(documentId: String) {
        ownerScope?.launch {
            val id = userId ?: return@launch
            when (val result = container.readerRepository.restore(id, documentId)) {
                is OperationResult.Ok -> _state.update { it.copy(message = "已恢复") }
                is OperationResult.Rejected -> _state.update { it.copy(message = "无法恢复：${result.code}") }
                is OperationResult.Failed -> _state.update { it.copy(message = "恢复失败") }
                is OperationResult.Conflict -> _state.update { it.copy(message = "请稍后重试") }
            }
        }
    }

    fun setCompleted(documentId: String, completed: Boolean) {
        ownerScope?.launch {
            val id = userId ?: return@launch
            container.readerRepository.setCompleted(id, documentId, completed)
        }
    }

    // ---------------------------------------------------------------- 导出 / 恢复（W5-05 / W5-06）

    /**
     * 导出所选文档的笔记/标注/手写为单个 zip。
     *
     * 原书正文与聊天历史一律不导出（`ExportRequest` 的 includeSources/includeChatHistory 恒为 false，
     * 传 true 会被 validationIssues 直接拒绝并在 UI 报错，不静默忽略）。
     */
    fun exportNotes(
        documentIds: List<String>,
        includeNotes: Boolean = true,
        includeAnnotations: Boolean = true,
        includeInk: Boolean = true,
        titleHint: String? = null,
    ) {
        val id = userId ?: return
        if (_state.value.backup.busy) return
        ownerScope?.launch {
            updateBackup { it.copy(exporting = true, exportingTitle = titleHint, exportResult = null, error = null) }
            val request = ExportRequest(
                userId = id,
                documentIds = documentIds,
                includeNotes = includeNotes,
                includeAnnotations = includeAnnotations,
                includeInk = includeInk,
            )
            val issues = request.validationIssues()
            if (issues.isNotEmpty()) {
                updateBackup { it.copy(exporting = false, error = issues.first().detail) }
                return@launch
            }
            when (val outcome = container.backupPackageWriter.export(request)) {
                is ExportOutcome.Rejected -> updateBackup {
                    it.copy(exporting = false, error = "导出被拒绝：${outcome.detail}")
                }
                is ExportOutcome.Completed -> {
                    when (val packed = container.backupPackageWriter.packageZip(outcome.preview.exportId)) {
                        is BackupArchive.Outcome.Rejected -> updateBackup {
                            it.copy(exporting = false, error = "打包失败：${packed.detail}")
                        }
                        is BackupArchive.Outcome.Ok -> updateBackup {
                            it.copy(
                                exporting = false,
                                exportResult = ExportPackageUi(
                                    exportId = outcome.preview.exportId,
                                    zip = packed.file,
                                    bytes = packed.file.length(),
                                    documentCount = outcome.preview.documentCount,
                                    noteFileCount = outcome.preview.noteFileCount,
                                    annotationCount = outcome.preview.annotationCount,
                                    inkPageCount = outcome.preview.inkPageCount,
                                    rejectedDocuments = outcome.rejectedDocuments.size,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun clearExportResult() {
        updateBackup { it.copy(exportResult = null) }
    }

    fun clearBackupError() {
        updateBackup { it.copy(error = null) }
    }

    /** 关闭恢复结果弹窗（报告已展示过，之后只保留数据库里的真实状态）。 */
    fun clearRestoreReport() {
        updateBackup { it.copy(restoreReport = null) }
    }

    /**
     * 用户在系统选择器里挑了一个 zip：复制到 `cache/exports` → 解包 → **只校验不写库**。
     *
     * 预览结果交给用户确认后才会 [confirmRestore]；校验失败时不会留下任何文件或数据库改动。
     */
    fun prepareRestore(uri: Uri) {
        val id = userId ?: return
        if (_state.value.backup.busy) return
        ownerScope?.launch {
            updateBackup { it.copy(restoring = true, restorePreview = null, restoreReport = null, error = null) }
            restoreDirectory?.deleteRecursively()
            restoreDirectory = null

            val root = container.backupExportsRoot
            root.mkdirs()
            val token = UUID.randomUUID().toString().replace("-", "").take(12)
            val zip = File(root, "incoming-$token.zip")
            val copied = runCatching {
                application.contentResolver.openInputStream(uri)?.use { input ->
                    zip.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("无法读取所选文件")
            }
            if (copied.isFailure) {
                zip.delete()
                updateBackup {
                    it.copy(
                        restoring = false,
                        error = "读取压缩包失败：${copied.exceptionOrNull()?.message?.take(80) ?: "未知原因"}",
                    )
                }
                return@launch
            }
            if (zip.length() > MAX_RESTORE_ZIP_BYTES) {
                zip.delete()
                updateBackup { it.copy(restoring = false, error = "压缩包超过 ${MAX_RESTORE_ZIP_BYTES / (1024 * 1024)} MiB，已拒绝") }
                return@launch
            }

            val directory = File(root, "incoming-$token")
            when (val unpacked = container.backupArchive.extract(zip, directory)) {
                is BackupArchive.Outcome.Rejected -> {
                    zip.delete()
                    updateBackup { it.copy(restoring = false, error = "解包被拒绝：${unpacked.detail}") }
                }
                is BackupArchive.Outcome.Ok -> {
                    zip.delete()
                    when (val preview = container.restoreCoordinator.preview(unpacked.file, id)) {
                        is RestoreOutcome.Previewed -> {
                            restoreDirectory = unpacked.file
                            updateBackup { it.copy(restoring = false, restorePreview = preview.preview) }
                        }
                        is RestoreOutcome.Rejected -> {
                            directory.deleteRecursively()
                            updateBackup { it.copy(restoring = false, error = "压缩包无法恢复：${preview.code}") }
                        }
                        else -> {
                            directory.deleteRecursively()
                            updateBackup { it.copy(restoring = false, error = "恢复预览返回了意外结果") }
                        }
                    }
                }
            }
        }
    }

    /** 用户确认后真正写库；冲突/损坏会体现为 PARTIAL 报告，不会伪造成"全部成功"。 */
    fun confirmRestore() {
        val id = userId ?: return
        val directory = restoreDirectory ?: return
        if (_state.value.backup.restoring) return
        ownerScope?.launch {
            updateBackup { it.copy(restoring = true, error = null) }
            when (val outcome = runCatching { container.restoreCoordinator.apply(directory, id) }.getOrNull()) {
                is RestoreOutcome.Applied -> {
                    updateBackup { it.copy(restoring = false, restorePreview = null, restoreReport = outcome.report) }
                    _state.update { it.copy(message = "恢复完成：新增 ${outcome.report.summary()["added"]} 项、复用 ${outcome.report.summary()["reused"]} 项") }
                }
                is RestoreOutcome.PartialFailure -> {
                    updateBackup { it.copy(restoring = false, restorePreview = null, restoreReport = outcome.report) }
                    _state.update {
                        it.copy(
                            message = "已部分恢复：冲突 ${outcome.report.conflicts} 项、损坏 ${outcome.report.damaged} 项，请查看明细",
                        )
                    }
                }
                is RestoreOutcome.Rejected -> updateBackup { it.copy(restoring = false, error = "恢复失败：${outcome.detail}") }
                else -> updateBackup { it.copy(restoring = false, error = "恢复失败：未知结果") }
            }
            directory.deleteRecursively()
            restoreDirectory = null
        }
    }

    /** 取消待确认的恢复：删掉解包目录，数据库零改动。 */
    fun cancelRestore() {
        restoreDirectory?.deleteRecursively()
        restoreDirectory = null
        updateBackup { it.copy(restorePreview = null, restoreReport = null, error = null) }
    }

    private fun updateBackup(transform: (BackupUiState) -> BackupUiState) {
        _state.update { it.copy(backup = transform(it.backup)) }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        application.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    companion object {
        /** 单个恢复包上限：与包校验器一致（超过就没有必要再复制进 cache）。 */
        private const val MAX_RESTORE_ZIP_BYTES = 512L * 1024 * 1024
    }
}

class ShelfViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ShelfViewModel(context.applicationContext as LearningApplication) as T
    }
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        return ShelfViewModel(context.applicationContext as LearningApplication, extras.createSavedStateHandle()) as T
    }
}
