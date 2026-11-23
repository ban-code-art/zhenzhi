package com.example.myapplication.ui.knowledge.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.ocr.OcrBackgroundWorker
import com.example.myapplication.data.ocr.OcrCheckpoint
import com.example.myapplication.data.ocr.OcrScheduler
import com.example.myapplication.data.local.entity.ReaderSectionEntity
import com.example.myapplication.data.reader.ReaderSectionContent
import com.example.myapplication.data.reader.ReaderSession
import com.example.myapplication.data.reader.WriterLease
import com.example.myapplication.data.reader.importer.ReaderFormat
import com.example.myapplication.data.reader.ink.InkManifest
import com.example.myapplication.data.reader.ink.InkPageTarget
import com.example.myapplication.data.reader.ink.InkStroke
import com.example.myapplication.data.reader.ink.InkTool
import com.example.myapplication.data.reader.notes.AnnotationColor
import com.example.myapplication.data.reader.notes.AnnotationKind
import com.example.myapplication.data.reader.notes.AnnotationStyle
import com.example.myapplication.data.reader.notes.ReaderAnnotationView
import com.example.myapplication.data.reader.notes.ReaderBookmarkView
import com.example.myapplication.data.reader.notes.ReaderSelectionCodec
import com.example.myapplication.data.reader.notes.ReaderSelectionSnapshot
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.NormalizedRect
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.reader.ReadingScope
import com.example.myapplication.domain.reader.ReaderOpenRequest
import com.example.myapplication.domain.reader.ReaderPlacement
import com.example.myapplication.domain.reader.ReaderTurnDirection
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import com.example.myapplication.ui.knowledge.course.CourseReaderNavigationPolicy
import com.example.myapplication.ui.knowledge.course.CourseLegacyPlacement
import com.example.myapplication.ui.knowledge.course.courseCatalog
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

enum class ReaderTheme { LIGHT, SEPIA, NIGHT }

data class ReaderSettings(
    val fontScale: Float = 1f,
    val lineHeight: Float = 1.75f,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val fontColor: ReaderFontColor = ReaderFontColor.Automatic,
    val marginDp: Int = 16,
    val flowMode: com.example.myapplication.domain.reader.ReaderFlowMode = com.example.myapplication.domain.reader.ReaderFlowMode.SCROLL,
    val edgeTap: Boolean = true,
    val immersivePreference: Boolean = false,
    val pageIndicator: Boolean = true,
)

data class ReaderOutlineItem(val id: String, val title: String, val targetSectionId: String?)

data class ReaderOcrUiState(
    val canRun: Boolean = false,
    val workState: String? = null,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val progressPage: Int? = null,
    val outcomeCode: String? = null,
    val outcomeMessage: String? = null,
    val publishedPages: Int = 0,
    val reviewRequiredPages: Int = 0,
    val checkpoint: OcrCheckpoint? = null,
)

data class ReaderUiState(
    val documentId: String? = null,
    val contentRevision: String? = null,
    val isLoading: Boolean = true,
    val historyReadOnly: Boolean = false,
    val error: String? = null,
    val title: String = "",
    val format: ReaderFormat = ReaderFormat.UNKNOWN,
    val outline: List<ReaderOutlineItem> = emptyList(),
    val currentSectionId: String? = null,
    val currentSectionTitle: String = "",
    val sectionContent: ReaderSectionContent? = null,
    val progress: Float = 0f,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val allowedPageStart: Int = 0,
    val allowedPageEnd: Int? = null,
    val isCompleted: Boolean = false,
    val settings: ReaderSettings = ReaderSettings(),
    val settingsPending: Boolean = false,
    val settingsSaving: Boolean = false,
    val settingsFailure: String? = null,
    val settingsOutcomeUnknown: Boolean = false,
    val settingsAbandoned: Boolean = false,
    val settingsRetryable: Boolean = true,
    val settingsInheritsDefaults: Boolean = true,
    val settingsFollowsGlobalTheme: Boolean = true,
    val initialProgress: Float = 0f,
    val annotations: List<ReaderAnnotationView> = emptyList(),
    val bookmarks: List<ReaderBookmarkView> = emptyList(),
    val selection: ReaderSelectionSnapshot? = null,
    val selectionClearToken: Long = 0,
    val locateQuoteToken: Long = 0,
    val locateQuote: String? = null,
    val locateTarget: ReaderLocator? = null,
    val restoreViewportOffset: Float? = null,
    val message: String? = null,
    val inkMode: Boolean = false,
    val inkTool: InkTool = InkTool.PEN,
    val inkColor: String = "#2F3B36",
    val inkWidth: Float = 0.006f,
    val inkStrokes: List<InkStroke> = emptyList(),
    /** 已保存的本页笔迹（只读展示层）；进入画笔模式后由可编辑层接管。 */
    val displayInkStrokes: List<InkStroke> = emptyList(),
    val inkAnnotationId: String? = null,
    val inkRevision: Long = 0,
    val ocr: ReaderOcrUiState = ReaderOcrUiState(),
    /** Page-owned ink for the PDF pager, including the neighboring page before a turn settles. */
    val pdfInkStrokes: Map<Int, List<InkStroke>> = emptyMap(),
)

/**
 * W4-11：阅读现场快照（只读，供“问 AI / 生成随堂测”使用）。
 * locatorJson 来自既有 [ReaderViewModel.currentLocatorJson]；scope 只表达当前节/当前书，
 * 真正的授权范围仍由 RetrievalScopeResolver 解析，UI 不自行扩大 scope。
 */
data class ReaderReadingContext(
    val documentId: String,
    val sectionId: String?,
    val sectionTitle: String?,
    val quote: String?,
    val locatorJson: String?,
    val readingScope: ReadingScope,
)

class ReaderViewModel(
    private val application: LearningApplication,
    private val documentId: String,
    /** 来源卡片带入的 Locator：优先于上次阅读进度，只影响首屏定位。 */
    private val initialLocatorJson: String? = null,
    private val courseRequest: ReaderOpenRequest? = null,
) : ViewModel() {
    private val container = application.container
    private val repository = container.readerRepository
    private val annotationRepository = container.annotationRepository
    val annotationActions = AnnotationActionController(
        annotationRepository::update, annotationRepository::removeText,
        annotationRepository::restoreText, { UUID.randomUUID().toString() },
    )
    val annotationCreation = AnnotationCreationController(
        create = { operation ->
            annotationRepository.create(operation.context, operation.locator.documentId, operation.kind,
                operation.locator, operation.quote, operation.style, operation.noteText,
                targets = operation.targets.takeIf { it.isNotEmpty() }, writeGuard = operation.guard)
        },
        newRequestId = { UUID.randomUUID().toString() },
    )
    private var readerIdentity: com.example.myapplication.data.session.SessionIdentity? = null
    private var readerInstance = UUID.randomUUID().toString()
    private var viewportLocator: ReaderLocator.Reflow? = null
    private var viewportRanges: List<ReaderLocator> = emptyList()
    private var selectedAnnotation: Pair<ReaderSelectionSnapshot, ReaderAnnotationView>? = null
    private var viewportEpoch: String? = null
    private var bookmarkInput: BookmarkPositionInput? = null
    private var selectionFragments: List<ReaderSelectionSnapshot> = emptyList()
    private var nativeSelectionUnavailable = false
    private var liveBookmarkOwner: (suspend (BookmarkPositionInput) -> Boolean)? = null
    fun bindBookmarkOwner(owner: (suspend (BookmarkPositionInput) -> Boolean)?) {
        liveBookmarkOwner = owner
    }
    val bookmarkActions = LegacyBookmarkController(viewModelScope, annotationRepository,
        { container.sessionManager.identity.first() },
        { input -> input == bookmarkInput && readerIdentity == input.identity &&
            session?.revision?.revision == input.token.contentRevision &&
            currentSection?.id == (input.selection?.firstOrNull()?.locator ?: input.viewport)?.sectionId &&
            container.sessionManager.identity.first() == input.identity &&
            (if (session?.format == ReaderFormat.PDF) true else liveBookmarkOwner?.invoke(input) == true) &&
            input == bookmarkInput })
    private val preferenceStore = com.example.myapplication.data.reader.ReaderPreferenceStore(application)
    @Volatile private var globalDarkTheme: Boolean = false
    private var settingsPersistence: ReaderSettingsPersistence? = null
    private var defaultsJob: Job? = null
    private var settingsJob: Job? = null
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var session: ReaderSession? = null
    private var historyReadOnly = false
    private var loadJob: Job? = null
    private var requestedLocatorJson: String? = initialLocatorJson
    private var lease: WriterLease? = null
    private var sequence = 0L
    private var saveJob: Job? = null
    private var knowledgeJob: Job? = null
    private var ocrObserveJob: Job? = null
    private var userId: Long? = null
    private var currentSection: ReaderSectionEntity? = null
    private val inkUndoStack = java.util.ArrayDeque<List<InkStroke>>()
    private val inkRedoStack = java.util.ArrayDeque<List<InkStroke>>()
    private var inkSaveJob: Job? = null
    private var lastInkCommitJob: Job? = null
    private val inkSaveMutex = kotlinx.coroutines.sync.Mutex()

    // ---- 二期 P1-15：一页一个墨迹会话 ----
    /** 当前画笔会话固定的落盘锚点；翻页/换节必须先用它收尾，再换新 target。 */
    private var inkTarget: InkPageTarget? = null
    /** 本会话是否真的落过笔（避免把"还没加载完的空笔画"写回文件、清掉原页笔迹）。 */
    private var inkDirty = false
    /** 本会话已知的最新 annotation revision：DB flow 可能滞后于刚成功的保存。 */
    private val knownInkRevisions = mutableMapOf<String, Long>()
    /** 本进程内已知的"页 → INK annotation id"：避免同一页被判成"新建"而产生重复 annotation。 */
    private val knownInkAnnotationIds = mutableMapOf<ReaderInkPageKey, String>()
    private val inkPages = ReaderInkPageDisplay()

    /** 待提交的一页笔迹快照（翻页/换节/退出时按**原页**落盘）。 */
    private data class InkCommit(
        val target: InkPageTarget,
        val strokes: List<InkStroke>,
        val annotationId: String?,
        val baseRevision: Long,
        val pageKey: ReaderInkPageKey,
        val localVersion: Long,
        val identity: com.example.myapplication.data.session.SessionIdentity,
        val readerInstance: String,
        val context: AccessContext,
    )

    init {
        load()
        if (courseRequest != null) viewModelScope.launch {
            container.sessionManager.identity.distinctUntilChanged().collect { identity ->
                val bound = readerIdentity
                if (bound != null && identity != bound) {
                    loadJob?.cancel(); saveJob?.cancel(); knowledgeJob?.cancel(); defaultsJob?.cancel(); settingsJob?.cancel()
                    settingsPersistence?.close(); settingsPersistence = null
                    lease = null; session = null; currentSection = null
                    invalidateWebPosition()
                    _state.update { it.copy(isLoading = false, sectionContent = null, error = "登录会话已变化，请返回目录") }
                }
            }
        }
    }

    private fun load(requestedLocatorJson: String? = this.requestedLocatorJson) {
        this.requestedLocatorJson = requestedLocatorJson
        loadJob?.cancel()
        defaultsJob?.cancel()
        settingsJob?.cancel()
        settingsPersistence?.close()
        settingsPersistence = null
        annotationCreation.close()
        saveJob?.cancel()
        lease = null
        // 重新打开文档时丢弃上一轮墨迹会话（目标/缓存的 revision 都属于旧会话）。
        inkTarget = null
        inkDirty = false
        inkSaveJob?.cancel()
        inkSaveJob = null
        knownInkRevisions.clear()
        knownInkAnnotationIds.clear()
        inkUndoStack.clear()
        inkRedoStack.clear()
        inkPages.clear()
        _state.update { it.copy(inkMode = false, inkStrokes = emptyList(), displayInkStrokes = emptyList(),
            inkAnnotationId = null, inkRevision = 0, pdfInkStrokes = emptyMap()) }
        loadJob = viewModelScope.launch {
            try {
            val settingsIdentity = container.sessionManager.identity.first { it.userId != null }
            val id = checkNotNull(settingsIdentity.userId)
            val defaultsDeferred = async {
                try {
                    preferenceStore.observeDefaultsState(id).first()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.update { it.copy(settingsFailure = "无法读取全局阅读偏好") }
                    com.example.myapplication.data.reader.ReaderPreferenceStore.ReaderDefaultsEntry(
                        settings = ReaderSettings(),
                        hasExplicitDefaults = false,
                    )
                }
            }
            userId = id
            readerIdentity = settingsIdentity
            readerInstance = UUID.randomUUID().toString()
            viewportLocator = null
            viewportRanges = emptyList()
            selectionFragments = emptyList()
            selectedAnnotation = null
            viewportEpoch = null
            bookmarkInput = null
            bookmarkActions.updatePosition(null)
            val requestedLocator = courseRequest?.locator ?: requestedLocatorJson?.let(ReaderLocatorCodec::decodeOrNull)
            if (!requestedLocatorJson.isNullOrBlank() && (requestedLocator == null || requestedLocator.documentId != documentId)) {
                _state.update { it.copy(isLoading = false, error = "INVALID_LOCATOR") }
                return@launch
            }
            if (courseRequest != null && !validCourseOwner(settingsIdentity)) {
                _state.update { it.copy(isLoading = false, error = "课程内容或登录会话已变化，请返回目录") }
                return@launch
            }
            when (val opened = repository.openSession(id, documentId, if (courseRequest == null) requestedLocator?.contentRevision else null)) {
                is OperationResult.Ok -> {
                    val loaded = opened.value
                    val coursePlacement = courseRequest?.let {
                        CourseReaderNavigationPolicy.placement(it, loaded.document.id, loaded.revision.revision, loaded.sections)
                    }
                    if (courseRequest != null && (coursePlacement !is OperationResult.Ok || !validCourseOwner(settingsIdentity))) {
                        _state.update { it.copy(isLoading = false, error = "课程章节范围已变化，请返回目录") }
                        return@launch
                    }
                    session = loaded
                    historyReadOnly = container.database.readerCatalogDao().findDocument(documentId)?.contentRevision != loaded.revision.revision
                    _state.update { it.copy(historyReadOnly = historyReadOnly) }
                    val canRunOcr = !historyReadOnly && loaded.format == ReaderFormat.PDF &&
                        loaded.document.kind == "IMPORTED" &&
                        loaded.document.ownerUserId == id &&
                        loaded.document.blobPath != null
                    _state.update { it.copy(ocr = it.ocr.copy(canRun = canRunOcr)) }
                    if (canRunOcr) observeOcrWork()
                    if (!historyReadOnly) when (val writer = repository.acquireWriter(id, documentId, courseRequest?.target?.contentRevision)) {
                        is OperationResult.Ok -> lease = writer.value
                        else -> _state.update { it.copy(error = "无法取得阅读写权") }
                    }
                    val locator = if (courseRequest != null) courseRequest.locator else requestedLocator
                        ?: loaded.state?.locatorJson?.let(ReaderLocatorCodec::decodeOrNull)
                    val defaultsEntry = defaultsDeferred.await()
                    suspend fun validSettingsOwner(): Boolean = session === loaded &&
                        container.sessionManager.identity.first() == settingsIdentity &&
                        (historyReadOnly || container.database.readerCatalogDao().findDocument(documentId)?.contentRevision == loaded.revision.revision)
                    val persistence = ReaderSettingsPersistence(viewModelScope, defaultsEntry.settings, loaded.state?.settingsJson,
                        historyReadOnly, ::validSettingsOwner,
                        { json -> repository.saveSettings(id, documentId, json, loaded.revision.revision, ::validSettingsOwner) },
                        { value -> if (validSettingsOwner()) preferenceStore.saveDefaults(id, value) else OperationResult.Rejected("SESSION_CHANGED") },
                        defaultsExplicitTheme = defaultsEntry.hasExplicitDefaults,
                        initialGlobalDark = globalDarkTheme)
                    settingsPersistence = persistence
                    settingsJob = viewModelScope.launch {
                        persistence.state.collect { value -> if (settingsPersistence === persistence) _state.update {
                            it.copy(settings = value.settings, settingsPending = value.pending, settingsSaving = value.saving,
                                settingsFailure = value.failure, settingsInheritsDefaults = value.inheritsDefaults,
                                settingsOutcomeUnknown = value.outcomeUnknown, settingsRetryable = value.retryable,
                                settingsAbandoned = value.abandoned, settingsFollowsGlobalTheme = value.followsGlobalTheme)
                        } }
                    }
                    defaultsJob = viewModelScope.launch {
                        try { preferenceStore.observeDefaultsState(id).collect { entry ->
                            if (settingsPersistence === persistence && validSettingsOwner()) {
                                persistence.updateDefaults(entry.settings, entry.hasExplicitDefaults)
                            }
                        } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { if (settingsPersistence === persistence) _state.update { it.copy(settingsFailure = "无法读取全局阅读偏好") } }
                    }
                    val settings = persistence.state.value.settings
                    val position = (coursePlacement as? OperationResult.Ok)?.value
                    val section = position?.let { point -> loaded.sections.single { it.id == point.sectionId } } ?: chooseSection(loaded, locator)
                    currentSection = section
                    observeKnowledge(id, loaded)
                    loadSection(section, locator, settings, locateExactQuote = requestedLocator != null, placement = position)
                }
                is OperationResult.Rejected -> _state.update { it.copy(isLoading = false, error = opened.code) }
                is OperationResult.Failed -> _state.update { it.copy(isLoading = false, error = opened.code) }
                is OperationResult.Conflict -> _state.update { it.copy(isLoading = false, error = "CONFLICT") }
            }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(isLoading = false, error = "阅读内容加载失败，请重试或返回目录") } }
        }
    }

    private fun observeKnowledge(id: Long, loaded: ReaderSession) {
        knowledgeJob?.cancel()
        knowledgeJob = viewModelScope.launch {
            launch {
                annotationRepository.observeAnnotations(id, documentId).collect { rows ->
                    if (session !== loaded || userId != id) return@collect
                    _state.update { it.copy(annotations = if (courseRequest == null) rows else rows.filter { row ->
                        row.locator?.let(::isAllowedLocator) == true && row.targets.all { target -> isAllowedLocator(target.locator) }
                    }) }
                    refreshDisplayInk()
                }
            }
            launch {
                annotationRepository.observeBookmarks(id, documentId).collect { rows ->
                    _state.update { it.copy(bookmarks = if (courseRequest == null) rows else rows.filter { row -> row.locator?.let(::isAllowedLocator) == true }) }
                }
            }
        }
    }

    private suspend fun loadSection(section: ReaderSectionEntity, locator: ReaderLocator?, settings: ReaderSettings, locateExactQuote: Boolean = false,
        placement: CourseLegacyPlacement? = null) {
        val loaded = session ?: return
        if (!isAllowedSection(section.id)) return
        when (val content = repository.loadSection(loaded, section.id)) {
            is OperationResult.Ok -> {
                if (session !== loaded || currentSection?.id != section.id) return
                val progress = placement?.progression ?: locator?.progression ?: 0f
                val page = placement?.pageIndex ?: when (locator) {
                    is ReaderLocator.Fixed -> locator.pageIndex
                    else -> section.pageStart ?: 0
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        documentId = loaded.document.id,
                        contentRevision = loaded.revision.revision,
                        error = null,
                        title = loaded.document.title.takeUnless {
                            it.matches(Regex("(?i)^(?:source|imported|document)[-:][0-9a-f]{24,}(?:\\.[a-z0-9]+)?$"))
                        }?.takeIf { it.isNotBlank() } ?: section.title.takeIf { it.isNotBlank() } ?: "导入图书",
                        format = loaded.format,
                        outline = loaded.outline.filter { node -> courseRequest == null || node.targetSectionId?.let(::isAllowedSection) == true }
                            .map { node -> ReaderOutlineItem(node.id, node.title, node.targetSectionId) },
                        currentSectionId = section.id,
                        currentSectionTitle = section.title,
                        sectionContent = content.value,
                        progress = progress.coerceIn(0f, 1f),
                        currentPage = page,
                        totalPages = if (loaded.format == ReaderFormat.PDF) physicalPageCount(loaded) else 0,
                        allowedPageStart = courseSections(loaded).firstOrNull()?.pageStart ?: 0,
                        allowedPageEnd = courseSections(loaded).lastOrNull()?.let { it.pageEnd ?: it.pageStart },
                        isCompleted = loaded.state?.isCompleted ?: false,
                        settings = settingsPersistence?.state?.value?.settings ?: settings,
                        settingsFollowsGlobalTheme = settingsPersistence?.state?.value?.followsGlobalTheme ?: it.settingsFollowsGlobalTheme,
                        initialProgress = progress.coerceIn(0f, 1f),
                        locateQuote = if (!locateExactQuote) null else when (locator) {
                            is ReaderLocator.Reflow -> locator.exactQuote
                            is ReaderLocator.Fixed -> locator.textQuote
                            else -> null
                        },
                        locateQuoteToken = it.locateQuoteToken + 1,
                        locateTarget = locator,
                        restoreViewportOffset = null,
                        message = if (historyReadOnly) "正在阅读历史版本（只读）" else null,
                    )
                }
                refreshDisplayInk()
            }
            is OperationResult.Rejected -> _state.update { it.copy(isLoading = false, error = content.code) }
            is OperationResult.Failed -> _state.update { it.copy(isLoading = false, error = content.code) }
            is OperationResult.Conflict -> _state.update { it.copy(isLoading = false, error = "CONFLICT") }
        }
    }

    fun selectSection(sectionId: String) {
        val loaded = session ?: return
        if (!isAllowedSection(sectionId)) { _state.update { it.copy(message = "该位置不在当前课程章节内") }; return }
        val section = loaded.sections.firstOrNull { it.id == sectionId } ?: return
        val nextLocator = if (loaded.format == ReaderFormat.PDF) {
            ReaderLocator.Fixed(
                loaded.document.id, section.id, loaded.revision.revision, 0f,
                section.pageStart ?: 0, NormalizedRect(0f, 0f, 1f, 1f), 0, emptyList(), section.title,
            )
        } else {
            ReaderLocator.Reflow(
                loaded.document.id, section.id, loaded.revision.revision, 0f,
                "web:0", section.title, null, null, 0, 0, section.id,
            )
        }
        // P1-15：换节前先用旧会话的锚点收尾，新节从干净会话开始。
        val nextInkTarget = nextInkTargetAfterNavigation(section, loaded, 0f, section.pageStart ?: 0)
        val restartInk = nextInkTarget != null && inkPageChanged(nextInkTarget)
        if (restartInk) startInkCommit(detachInkSession())
        currentSection = section
        _state.update { it.copy(selection = null, selectionClearToken = it.selectionClearToken + 1) }
        if (restartInk) resumeInkAfterNavigation(nextInkTarget)
        viewModelScope.launch { loadSection(section, nextLocator, _state.value.settings) }
    }

    fun onProgress(progress: Float, visibleText: String) {
        val clamped = progress.coerceIn(0f, 1f)
        _state.update { it.copy(progress = clamped) }
        scheduleSave(clamped, visibleText)
    }

    fun onPdfPageChanged(pageIndex: Int) {
        val loaded = session ?: return
        val total = physicalPageCount(loaded)
        if (total <= 0) return
        val clamped = pageIndex.coerceIn(0, total - 1)
        val section = loaded.sections.firstOrNull { it.pageStart != null && clamped in it.pageStart..(it.pageEnd ?: it.pageStart) }
            ?: (if (courseRequest == null) loaded.sections.getOrNull(clamped) else null)
            ?: return
        if (!isAllowedSection(section.id)) return
        val progression = if (total <= 1) 0f else clamped.toFloat() / (total - 1)
        // P1-15：翻页前结束当前墨迹会话（未提交笔画按原页落盘），然后为新页建干净会话；
        // 否则落笔会把上一页的笔迹写进新页 manifest，并把旧页 INK annotation 改锚到新页。
        val nextInkTarget = nextInkTargetAfterNavigation(section, loaded, progression, clamped)
        val restartInk = nextInkTarget != null && inkPageChanged(nextInkTarget)
        if (restartInk) startInkCommit(detachInkSession())
        currentSection = section
        _state.update { it.copy(currentPage = clamped, currentSectionId = section.id, currentSectionTitle = section.title,
            progress = progression, selection = null, displayInkStrokes = emptyList()) }
        if (restartInk) resumeInkAfterNavigation(nextInkTarget)
        refreshDisplayInk()
        viewModelScope.launch {
            val content = repository.loadSection(loaded, section.id)
            if (content is OperationResult.Ok && session === loaded && currentSection?.id == section.id && state.value.currentPage == clamped) {
                _state.update { it.copy(sectionContent = content.value) }
            }
        }
        refreshBookmarkPosition()
        scheduleSave(progression, section.title)
    }

    fun setGlobalDarkTheme(dark: Boolean) {
        globalDarkTheme = dark
        settingsPersistence?.setGlobalDarkTheme(dark)
    }

    fun updateSettings(settings: ReaderSettings) {
        settingsPersistence?.edit(settings)
    }

    fun updateReadingOptions(settings: ReaderSettings) {
        settingsPersistence?.editOptions(settings)
    }

    fun selectTheme(settings: ReaderSettings) {
        settingsPersistence?.edit(settings, forceThemeOverride = true)
    }

    fun useDefaultSettings() { settingsPersistence?.useDefaults() }
    fun makeDefaultSettings() { settingsPersistence?.makeDefaults() }
    fun retrySettingsSave() { settingsPersistence?.retry() }

    fun toggleCompleted() {
        if (historyReadOnly) return
        val id = userId ?: return
        val next = !_state.value.isCompleted
        _state.update { it.copy(isCompleted = next) }
        viewModelScope.launch { repository.setCompleted(id, documentId, next) }
    }

    fun onTextSelection(raw: String?) {
        val parsed = ReaderSelectionCodec.decode(raw) ?: run {
            val payload = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            nativeSelectionUnavailable = raw != null && (payload?.get("cleared") as? JsonPrimitive)?.contentOrNull != "true"
            selectionFragments = emptyList()
            selectedAnnotation = null
            _state.update { it.copy(selection = null) }
            refreshBookmarkPosition()
            return
        }
        nativeSelectionUnavailable = false
        val loaded = session ?: return
        val section = loaded.sections.firstOrNull { it.id == parsed.sectionId } ?: return
        if (section.id != currentSection?.id) return
        val html = _state.value.sectionContent?.html ?: return
        val sourceText = legacyReaderText(html)
        val fragments = legacyReaderSelectionFragments(html, section.id, parsed.canonicalStart, parsed.canonicalEnd)
        val supplied = runCatching { Json.parseToJsonElement(raw!!).jsonObject["fragments"] as? kotlinx.serialization.json.JsonArray }
            .getOrNull()?.map { ReaderSelectionCodec.decode(it.toString()) }
        if (fragments == null || parsed.canonicalEnd > sourceText.length ||
            sourceText.substring(parsed.canonicalStart, parsed.canonicalEnd) != parsed.quote ||
            (supplied != null && supplied != fragments)) {
            selectionFragments = emptyList()
            selectedAnnotation = null
            _state.update { it.copy(selection = null, message = "选区来源已变化，请重新选择") }
            refreshBookmarkPosition()
            return
        }
        selectionFragments = fragments
        _state.update { it.copy(selection = parsed) }
        if (selectedAnnotation?.first != parsed) selectedAnnotation = null
        refreshBookmarkPosition()
    }

    fun clearSelection() {
        nativeSelectionUnavailable = false
        selectionFragments = emptyList()
        selectedAnnotation = null
        _state.update { it.copy(selection = null, selectionClearToken = it.selectionClearToken + 1) }
        refreshBookmarkPosition()
    }

    fun onSemanticViewport(raw: String) {
        val parsed = ReaderSelectionCodec.decode(raw) ?: return
        val loaded = session ?: return
        if (parsed.sectionId != currentSection?.id || _state.value.isLoading) return
        val data = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val epoch = (data["epoch"] as? JsonPrimitive)?.contentOrNull ?: return
        val progress = (data["progress"] as? JsonPrimitive)?.floatOrNull ?: _state.value.progress
        if (!progress.isFinite()) return
        viewportEpoch = epoch
        viewportLocator = ReaderLocator.Reflow(documentId, parsed.sectionId, loaded.revision.revision,
            progress.coerceIn(0f, 1f), "canonical:${parsed.canonicalStart}:${parsed.canonicalEnd}",
            parsed.quote, parsed.prefix, parsed.suffix, parsed.canonicalStart, parsed.canonicalEnd,
            parsed.blockId ?: return)
        // Recreated WebViews restore the latest semantic point. Navigation effects are keyed
        // solely by locateQuoteToken, so updating this anchor never starts a scroll loop.
        val offset = (data["viewportOffset"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() }
        _state.update { it.copy(progress = progress.coerceIn(0f, 1f), locateTarget = viewportLocator, restoreViewportOffset = offset) }
        viewportRanges = (data["visibleRanges"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { value ->
            ReaderSelectionCodec.decode(value.toString())?.takeIf { it.sectionId == parsed.sectionId }?.let { range ->
                ReaderLocator.Reflow(documentId, range.sectionId, loaded.revision.revision, progress,
                    "canonical:${range.canonicalStart}:${range.canonicalEnd}", range.quote, range.prefix, range.suffix,
                    range.canonicalStart, range.canonicalEnd, range.blockId ?: return@mapNotNull null)
            }
        }
        refreshBookmarkPosition()
        scheduleSave(progress, parsed.quote)
    }

    fun invalidateWebPosition() {
        viewportLocator = null
        viewportRanges = emptyList()
        viewportEpoch = null
        bookmarkInput = null
        bookmarkActions.updatePosition(null)
    }

    private fun refreshBookmarkPosition() {
        if (nativeSelectionUnavailable) {
            bookmarkInput = null
            bookmarkActions.updatePosition(null)
            return
        }
        val identity = readerIdentity ?: return
        val id = identity.userId ?: return
        val loaded = session ?: return
        val locator = buildCurrentLocator()?.let { point ->
            if (_state.value.selection != null) point else when (point) {
                is ReaderLocator.Reflow -> point
                is ReaderLocator.Fixed -> point.copy(rects = emptyList(), textQuote = null)
                else -> point
            }
        }
        if (locator == null || locator.sectionId != currentSection?.id) {
            bookmarkInput = null
            bookmarkActions.updatePosition(null)
            return
        }
        val selected = _state.value.selection?.let {
            selectionFragments.map { part -> com.example.myapplication.domain.reader.ReaderSelectionFragment(
                selectionLocator(part), part.quote) }.takeIf { it.isNotEmpty() }
        }
        val order = if (selected != null) legacyReaderSelectionOrder(
            _state.value.sectionContent?.html.orEmpty(), currentSection?.id.orEmpty(), selectionFragments)
            ?.let { BookmarkReadingOrder(documentId, loaded.revision.revision, it) } else null
        if (_state.value.selection != null && (selected == null || order == null)) {
            bookmarkInput = null
            bookmarkActions.updatePosition(null)
            return
        }
        val previous = bookmarkInput
        val token = com.example.myapplication.domain.reader.ReaderSessionToken(id, identity.epoch,
            documentId, loaded.revision.revision, readerInstance, 0L)
        if (previous?.token == token && previous.selection == selected && previous.viewport == locator &&
            previous.writable == !historyReadOnly && previous.visibleRanges == viewportRanges) return
        val input = BookmarkPositionInput(identity, token, UUID.randomUUID().toString(), !historyReadOnly,
            selected, locator, viewportRanges)
        bookmarkInput = input
        bookmarkActions.updatePosition(input, order)
    }

    private fun selectionLocator(selection: ReaderSelectionSnapshot): ReaderLocator.Reflow =
        ReaderLocator.Reflow(documentId, selection.sectionId, checkNotNull(session).revision.revision,
            _state.value.progress, "canonical:${selection.canonicalStart}:${selection.canonicalEnd}",
            selection.quote, selection.prefix, selection.suffix, selection.canonicalStart,
            selection.canonicalEnd, selection.blockId ?: selection.sectionId)

    fun editAnnotation(annotation: ReaderAnnotationView) {
        val identity = readerIdentity ?: return
        val id = identity.userId ?: return
        if (historyReadOnly || annotation.kind == AnnotationKind.INK || annotation.documentId != documentId ||
            annotation.locator?.contentRevision != session?.revision?.revision) return
        if (annotationActions.state.value.busy || annotationActions.state.value.outcomeUnknown ||
            annotationCreation.state.value.busy || annotationCreation.state.value.outcomeUnknown) return
        annotationActions.open(accessContext(id), annotation, annotationGuard())
    }

    private fun annotationGuard(requiredSelection: BookmarkPositionInput? = null): com.example.myapplication.data.reader.notes.AnnotationWriteGuard? {
        val identity = readerIdentity ?: return null
        val revision = session?.revision?.revision ?: return null
        val loaded = session ?: return null
        return com.example.myapplication.data.reader.notes.AnnotationWriteGuard(documentId, revision, loaded.document.accessEpoch) check@{ context, _, targets ->
            if (historyReadOnly || context.userId != identity.userId || context.sessionEpoch != identity.epoch ||
                container.sessionManager.identity.first() != identity ||
                container.database.readerCatalogDao().findDocument(documentId)?.contentRevision != revision)
                return@check OperationResult.Rejected("SESSION_CHANGED")
            if (requiredSelection != null) {
                if (requiredSelection != bookmarkInput || liveBookmarkOwner?.invoke(requiredSelection) != true ||
                    requiredSelection != bookmarkInput || nativeSelectionUnavailable ||
                    currentSection?.id != requiredSelection.selection?.firstOrNull()?.locator?.sectionId ||
                    container.sessionManager.identity.first() != identity || session?.revision?.revision != revision)
                    return@check OperationResult.Rejected("SELECTION_CHANGED")
            }
            for (target in targets) {
                val locator = target.locator as? ReaderLocator.Reflow ?: continue
                if (target.precision == "UNRESOLVED") {
                    val section = repository.loadSection(loaded, locator.sectionId ?: return@check OperationResult.Rejected("SOURCE_CHANGED"))
                    if (section !is OperationResult.Ok) return@check OperationResult.Rejected("SOURCE_UNAVAILABLE")
                    val text = legacyReaderText(section.value.html)
                    if (locator.canonicalStart < 0 || locator.canonicalEnd > text.length ||
                        text.substring(locator.canonicalStart, locator.canonicalEnd) != locator.exactQuote)
                        return@check OperationResult.Rejected("SOURCE_CHANGED")
                    if (targets.size > 1) {
                        val parts = legacyReaderSelectionFragments(section.value.html, locator.sectionId.orEmpty(),
                            locator.canonicalStart, locator.canonicalEnd)
                        if (parts?.size != 1 || parts.single().blockId != locator.blockId)
                            return@check OperationResult.Rejected("SOURCE_CHANGED")
                    }
                } else {
                    val opened = container.roomDocumentAccess.open(context, documentId, revision)
                    if (opened !is OperationResult.Ok) return@check OperationResult.Rejected("SOURCE_UNAVAILABLE")
                    val evidence = container.roomDocumentAccess.readRange(context, opened.value.snapshotId, locator, maxTokens = 8192)
                    if (evidence !is OperationResult.Ok || evidence.value.quote != locator.exactQuote)
                        return@check OperationResult.Rejected("SOURCE_CHANGED")
                }
            }
            if (requiredSelection != null && (requiredSelection != bookmarkInput || nativeSelectionUnavailable ||
                    currentSection?.id != requiredSelection.selection?.firstOrNull()?.locator?.sectionId ||
                    session?.revision?.revision != revision || container.sessionManager.identity.first() != identity ||
                    requiredSelection != bookmarkInput))
                return@check OperationResult.Rejected("SELECTION_CHANGED")
            OperationResult.Ok(Unit, context.requestId)
        }
    }

    fun saveAnnotation() { viewModelScope.launch {
        annotationActions.save()
        rememberSelectedAnnotation()
        clearSelectionAfterCommittedEdit()
    } }
    fun deleteAnnotation() { viewModelScope.launch {
        annotationActions.delete()
        if (annotationActions.state.value.deleted) selectedAnnotation = null
    } }
    fun undoAnnotation() { viewModelScope.launch { annotationActions.undo(); rememberSelectedAnnotation() } }
    fun retryAnnotation() { viewModelScope.launch { annotationActions.retry(); rememberSelectedAnnotation() } }
    private fun rememberSelectedAnnotation() {
        val action = annotationActions.state.value
        val selection = _state.value.selection ?: return
        val original = action.original ?: return
        val locator = buildCurrentLocator() ?: return
        if (!action.deleted && matchesCurrentAnnotation(original, locator)) selectedAnnotation = selection to original
    }
    fun reloadAnnotation() {
        val id = annotationActions.state.value.original?.id ?: return
        _state.value.annotations.firstOrNull { it.id == id }?.let(annotationActions::rebase)
    }
    fun saveAnnotationCreation() { viewModelScope.launch {
        annotationCreation.save()
        adoptCreatedAnnotation()
    } }
    fun retryAnnotationCreation() { viewModelScope.launch {
        annotationCreation.retry()
        adoptCreatedAnnotation()
    } }
    private fun adoptCreatedAnnotation() {
        val created = annotationCreation.state.value.committed ?: return
        if (userId == null) return
        if (created.documentId != documentId || created.locator?.contentRevision != session?.revision?.revision) return
        reflectCommittedAnnotation(created)
        clearSelection()
        annotationCreation.discard()
    }

    /** Room's receipt can arrive before its list invalidation. Draw the confirmed mark before clearing the native selection. */
    private fun reflectCommittedAnnotation(annotation: ReaderAnnotationView) {
        _state.update { current ->
            val existing = current.annotations.firstOrNull { it.id == annotation.id }
            if (existing != null && existing.revision >= annotation.revision) current
            else current.copy(annotations = if (existing == null) listOf(annotation) + current.annotations
                else current.annotations.map { if (it.id == annotation.id) annotation else it })
        }
    }

    /** 保存/更新成功后收起选区，让页面只显示已保存的高亮，而不是继续停在“选中”状态。 */
    private fun clearSelectionAfterCommittedEdit() {
        val action = annotationActions.state.value
        if (!action.busy && action.pending == null && action.failure == null && !action.outcomeUnknown) {
            action.original?.let(::reflectCommittedAnnotation)
            if (_state.value.selection != null) clearSelection()
            if (!action.showEditor) annotationActions.discard()
        }
    }

    fun createHighlight(style: AnnotationStyle, noteText: String? = null): Boolean {
        val selection = _state.value.selection ?: return false
        if (session == null || currentSection == null) return false
        val first = selectionFragments.firstOrNull() ?: return false
        val locator = selectionLocator(first)
        return createAnnotation(locator, AnnotationKind.HIGHLIGHT, selection.quote, style, noteText)
    }

    fun createPdfAnnotation(
        quote: String,
        pageIndex: Int,
        style: AnnotationStyle,
        noteText: String? = null,
    ) {
        val loaded = session ?: return
        val section = currentSection ?: return
        if (quote.isBlank()) {
            _state.update { it.copy(message = "当前页没有可选择的文本") }
            return
        }
        val locator = ReaderLocator.Fixed(
            documentId = loaded.document.id,
            sectionId = section.id,
            contentRevision = loaded.revision.revision,
            progression = _state.value.progress,
            pageIndex = pageIndex.coerceAtLeast(0),
            pageBox = NormalizedRect(0f, 0f, 1f, 1f),
            rotationDegrees = 0,
            rects = listOf(NormalizedRect(0f, 0f, 1f, 1f)),
            textQuote = quote.take(8192),
        )
        createAnnotation(locator, if (noteText.isNullOrBlank()) AnnotationKind.HIGHLIGHT else AnnotationKind.NOTE, quote, style, noteText)
    }

    fun toggleCurrentBookmark() { viewModelScope.launch { bookmarkActions.toggleBookmark() } }
    fun ensureCurrentBookmark() { viewModelScope.launch { bookmarkActions.ensureBookmark() } }
    fun retryBookmark() { viewModelScope.launch { bookmarkActions.retryWrite() } }
    fun selectBookmark(id: String) { bookmarkActions.selectBookmark(id) }

    fun openAnnotation(annotation: ReaderAnnotationView) {
        val locator = annotation.locator ?: return
        jumpToLocator(locator)
    }

    fun openBookmark(bookmark: ReaderBookmarkView) {
        bookmark.locator?.let(::jumpToLocator)
    }

    fun jumpToLocator(locator: ReaderLocator) {
        val loaded = session ?: return
        if (!isAllowedLocator(locator)) { _state.update { it.copy(message = "该位置不在当前课程章节内或版本已变化") }; return }
        if (locator.documentId != documentId) return
        if (locator.contentRevision != loaded.revision.revision) {
            _state.update { it.copy(isLoading = true, selection = null) }
            load(ReaderLocatorCodec.encode(locator))
            return
        }
        val section = loaded.sections.firstOrNull { it.id == locator.sectionId }
            ?: loaded.sections.firstOrNull { value -> locator is ReaderLocator.Fixed && value.pageStart == locator.pageIndex }
            ?: return
        // P1-15：跳转同样可能换页/换节，先按原页收尾再重建会话。
        val nextInkTarget = nextInkTargetAfterNavigation(section, loaded, locator.progression ?: _state.value.progress,
            (locator as? ReaderLocator.Fixed)?.pageIndex ?: section.pageStart ?: 0)
        val restartInk = nextInkTarget != null && inkPageChanged(nextInkTarget)
        if (restartInk) startInkCommit(detachInkSession())
        currentSection = section
        if (restartInk) resumeInkAfterNavigation(nextInkTarget)
        viewModelScope.launch {
            val result = repository.loadSection(loaded, section.id)
            if (result is OperationResult.Ok && session === loaded && currentSection?.id == section.id) {
                if (loaded.format == ReaderFormat.PDF && locator is ReaderLocator.Fixed) {
                    _state.update {
                        it.copy(
                            currentSectionId = section.id,
                            currentSectionTitle = section.title,
                            currentPage = locator.pageIndex,
                            sectionContent = result.value,
                            progress = locator.progression ?: it.progress,
                        )
                    }
                } else {
                    val quote = when (locator) {
                        is ReaderLocator.Reflow -> locator.exactQuote
                        is ReaderLocator.Fixed -> locator.textQuote
                        is ReaderLocator.Note -> null
                    }
                    _state.update {
                        it.copy(
                            currentSectionId = section.id,
                            currentSectionTitle = section.title,
                            sectionContent = result.value,
                            initialProgress = locator.progression ?: 0f,
                            progress = locator.progression ?: 0f,
                            locateQuote = quote,
                            locateTarget = locator,
                            restoreViewportOffset = null,
                            locateQuoteToken = it.locateQuoteToken + 1,
                        )
                    }
                }
                refreshDisplayInk()
            }
        }
    }

    fun openOcrPage(pageIndex: Int) {
        val loaded = session ?: return
        val section = loaded.sections.firstOrNull { it.pageStart == pageIndex } ?: return
        if (!isAllowedSection(section.id)) return
        jumpToLocator(
            ReaderLocator.Fixed(
                documentId = documentId,
                sectionId = section.id,
                contentRevision = loaded.revision.revision,
                progression = _state.value.progress,
                pageIndex = pageIndex,
                pageBox = NormalizedRect(0f, 0f, 1f, 1f),
                rotationDegrees = 0,
                rects = emptyList(),
                textQuote = null,
            ),
        )
    }

    fun startOcr() {
        val id = userId
        if (id == null || !_state.value.ocr.canRun) {
            _state.update { it.copy(message = "当前账号不能对这份 PDF 执行 OCR") }
            return
        }
        container.scheduleOcr(id, documentId)
        _state.update {
            it.copy(ocr = it.ocr.copy(workState = WorkInfo.State.ENQUEUED.name, outcomeCode = null, outcomeMessage = null))
        }
    }

    fun cancelOcr() {
        if (!_state.value.ocr.canRun) return
        OcrScheduler.cancel(application, documentId)
    }

    fun confirmOcrPage(pageIndex: Int) {
        val id = userId ?: return
        if (!_state.value.ocr.canRun) return
        viewModelScope.launch {
            when (val outcome = container.ocrReviewService.confirmPage(id, documentId, pageIndex)) {
                is com.example.myapplication.data.ocr.OcrReviewService.Outcome.Confirmed -> {
                    _state.update { it.copy(ocr = it.ocr.copy(checkpoint = outcome.checkpoint)) }
                    container.scheduleOcr(id, documentId)
                    _state.update { it.copy(message = "已记录第 ${pageIndex + 1} 页复核，正在发布已确认文字") }
                }
                is com.example.myapplication.data.ocr.OcrReviewService.Outcome.AlreadyVerified -> {
                    _state.update { it.copy(ocr = it.ocr.copy(checkpoint = outcome.checkpoint)) }
                    container.scheduleOcr(id, documentId)
                }
                is com.example.myapplication.data.ocr.OcrReviewService.Outcome.Rejected -> {
                    _state.update { it.copy(message = "复核未保存：${outcome.message}") }
                }
            }
        }
    }

    private fun observeOcrWork() {
        ocrObserveJob?.cancel()
        ocrObserveJob = viewModelScope.launch {
            OcrScheduler.observe(application, documentId).collect { workInfos ->
                val info = workInfos.firstOrNull()
                val checkpoint = withContext(Dispatchers.IO) {
                    container.ocrCheckpointStore.load(documentId)
                }
                val output = info?.outputData
                val progress = info?.progress
                _state.update { current ->
                    current.copy(
                        ocr = current.ocr.copy(
                            workState = info?.state?.name,
                            progressDone = progress?.getInt(OcrBackgroundWorker.KEY_PROGRESS_DONE, 0) ?: 0,
                            progressTotal = progress?.getInt(OcrBackgroundWorker.KEY_PROGRESS_TOTAL, 0) ?: 0,
                            progressPage = progress?.getInt(OcrBackgroundWorker.KEY_PROGRESS_PAGE, -1)
                                ?.takeIf { it >= 0 },
                            outcomeCode = output?.getString(OcrBackgroundWorker.KEY_OUTCOME_CODE),
                            outcomeMessage = output?.getString(OcrBackgroundWorker.KEY_OUTCOME_MESSAGE),
                            publishedPages = output?.getInt(OcrBackgroundWorker.KEY_PUBLISHED_PAGES, 0) ?: 0,
                            reviewRequiredPages = output?.getInt(OcrBackgroundWorker.KEY_REVIEW_REQUIRED_PAGES, 0)
                                ?: checkpoint?.pages?.count {
                                    it.quality == com.example.myapplication.domain.reader.ExtractionQuality.OCR_REVIEW_REQUIRED
                                }
                                ?: 0,
                            checkpoint = checkpoint,
                        ),
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun retry() {
        _state.update { it.copy(isLoading = true, error = null) }
        load()
    }

    fun enterInkMode() {
        if (historyReadOnly) return
        val loaded = session ?: return
        val section = currentSection ?: return
        val id = userId
        if (id == null || id <= 0L) {
            // P1-14：没有用户就不写入（墨迹目录按用户隔离，不能回落到共享目录）。
            _state.update { it.copy(message = "用户会话未就绪，暂不能使用画笔") }
            return
        }
        _state.update { it.copy(inkMode = true, selection = null, displayInkStrokes = emptyList()) }
        resumeInkAfterNavigation(inkTargetOf(section, loaded, _state.value.progress))
        refreshDisplayInk()
    }

    fun exitInkMode() {
        // P1-15：结束会话时把待提交笔画按原页落盘，而不是丢弃在途任务。
        val visibleStrokes = _state.value.inkStrokes
        val pending = detachInkSession()
        // Even an unchanged edit session keeps its actual strokes while Room catches up.
        _state.update { it.copy(inkMode = false, displayInkStrokes = visibleStrokes) }
        startInkCommit(pending)
        refreshDisplayInk()
    }

    /**
     * 刷新“已保存笔迹”的只读展示层（不在画笔模式时也要能看到已保存的字迹）。
     *
     * PDF 邻页提前加载并绑定物理页；本地编辑和待回流的成功保存不会被旧 Room 行清空。
     */
    fun refreshDisplayInk() {
        val loaded = session ?: return
        val section = currentSection ?: return
        val id = userId ?: return
        // A save can be acknowledged while its page is outside the current neighbor pair.
        // Reconcile cached identities first so clean, distant pages can be evicted normally.
        inkPages.snapshots().keys.filter { it.userId == id && it.documentId == loaded.document.id &&
            it.contentRevision == loaded.revision.revision }.forEach { key ->
            val cachedSection = loaded.sections.firstOrNull { it.id == key.sectionId } ?: return@forEach
            readInkPage(inkTargetOf(cachedSection, loaded, 0f, key.pageIndex), id)
        }
        val target = inkTargetOf(section, loaded, _state.value.progress)
        val current = readInkPage(target, id)
        if (loaded.format == ReaderFormat.PDF) {
            val snapshot = _state.value
            val end = snapshot.allowedPageEnd ?: (physicalPageCount(loaded) - 1)
            for (page in (snapshot.currentPage - 1)..(snapshot.currentPage + 1)) {
                if (page !in snapshot.allowedPageStart..end || page == snapshot.currentPage) continue
                val pageSection = pdfSectionForPage(loaded, page) ?: continue
                if (!isAllowedSection(pageSection.id)) continue
                readInkPage(inkTargetOf(pageSection, loaded, 0f, page), id)
            }
        }
        val pdfPages = cachedPdfInk(loaded, id)
        _state.update {
            val display = if (it.inkMode) emptyList() else current.strokes
            if (it.displayInkStrokes == display && it.pdfInkStrokes == pdfPages) it
            else it.copy(displayInkStrokes = display, pdfInkStrokes = pdfPages)
        }
    }

    fun setInkTool(tool: InkTool) {
        _state.update { it.copy(inkTool = tool) }
    }

    fun setInkColor(color: String) {
        _state.update { it.copy(inkColor = color) }
    }

    fun setInkWidth(width: Float) {
        _state.update { it.copy(inkWidth = width.coerceIn(0.002f, 0.05f)) }
    }

    fun appendInkStroke(stroke: InkStroke) {
        if (!_state.value.inkMode || inkTarget == null || historyReadOnly) return
        val current = _state.value.inkStrokes
        if (current.size >= InkManifest.MAX_STROKES) {
            _state.update { it.copy(message = "本页笔迹数量已达上限") }
            return
        }
        inkUndoStack.addLast(current)
        inkRedoStack.clear()
        _state.update { it.copy(inkStrokes = current + stroke) }
        persistInk()
    }

    fun eraseInkStroke(strokeId: String) {
        if (!_state.value.inkMode || inkTarget == null || historyReadOnly) return
        val current = _state.value.inkStrokes
        if (current.none { it.strokeId == strokeId }) return
        inkUndoStack.addLast(current)
        inkRedoStack.clear()
        _state.update { it.copy(inkStrokes = current.filterNot { value -> value.strokeId == strokeId }) }
        persistInk()
    }

    fun eraseInkMatching(matches: (InkStroke) -> Boolean) {
        val hit = _state.value.inkStrokes.lastOrNull(matches)
        if (hit != null) eraseInkStroke(hit.strokeId)
    }

    fun undoInk() {
        if (!_state.value.inkMode || inkTarget == null || historyReadOnly) return
        val previous = inkUndoStack.pollLast() ?: return
        inkRedoStack.addLast(_state.value.inkStrokes)
        _state.update { it.copy(inkStrokes = previous) }
        persistInk()
    }

    fun redoInk() {
        if (!_state.value.inkMode || inkTarget == null || historyReadOnly) return
        val next = inkRedoStack.pollLast() ?: return
        inkUndoStack.addLast(_state.value.inkStrokes)
        _state.update { it.copy(inkStrokes = next) }
        persistInk()
    }

    /**
     * 落笔/擦除/撤销后的防抖保存。
     *
     * P1-15：写入目标在**调用时**就从 [inkTarget] 取快照，落盘时不再读"当前页"，
     * 所以翻页/换节后迟到的保存只会写回原页；annotationId 也必须属于该页。
     */
    private fun persistInk() {
        val target = inkTarget ?: return
        val id = userId ?: return
        if (!_state.value.inkMode) return
        inkPages.edit(inkKey(target, id), _state.value.inkStrokes)
        val commit = currentInkCommit() ?: return
        inkDirty = true
        refreshDisplayInk()
        inkSaveJob?.cancel()
        inkSaveJob = viewModelScope.launch {
            delay(INK_SAVE_DEBOUNCE_MS)
            // Cancelling a newer debounce must never cancel a file/Room commit already started.
            startInkCommit(commit)
        }
    }

    private fun currentInkCommit(): InkCommit? {
        val target = inkTarget ?: return null
        val id = userId ?: return null
        val identity = readerIdentity ?: return null
        if (identity.userId != id) return null
        val pageKey = inkKey(target, id)
        val page = inkPages.snapshot(pageKey) ?: return null
        val snapshot = _state.value
        if (!snapshot.inkMode) return null
        return InkCommit(
            target = target,
            strokes = snapshot.inkStrokes,
            annotationId = resolveInkAnnotationId(target, snapshot.inkAnnotationId),
            baseRevision = snapshot.inkRevision,
            pageKey = pageKey,
            localVersion = page.version,
            identity = identity,
            readerInstance = readerInstance,
            context = AccessContext(id, identity.epoch, UUID.randomUUID().toString()),
        )
    }

    /** 翻页/换节/退出时的收尾：把待提交笔画交给 [startInkCommit]，并重置为干净会话。 */
    private fun detachInkSession(): InkCommit? {
        if (inkTarget == null) return null
        val commit = if (inkDirty) currentInkCommit() else null
        inkTarget = null
        inkDirty = false
        inkUndoStack.clear()
        inkRedoStack.clear()
        _state.update { it.copy(inkStrokes = emptyList(), inkAnnotationId = null, inkRevision = 0) }
        return commit
    }

    /**
     * 异步落盘一页旧会话；与本会话的防抖保存共用互斥锁，先后顺序不会被写乱。
     *
     * 这里刻意**不**取消在途的防抖保存：它可能已经把文件/DB 写进去了，取消只会让本提交的
     * baseRevision 落后（CAS 冲突或重复 annotation）。两者按入队顺序串行，[applyInkCommit]
     * 在锁内重新解析 annotationId 与 revision，因此谁先落库都不会写错页、写重页。
     */
    private fun startInkCommit(commit: InkCommit?) {
        if (commit == null) return
        lastInkCommitJob = viewModelScope.launch { inkSaveMutex.withLock { applyInkCommit(commit) } }
    }

    private suspend fun applyInkCommit(commit: InkCommit) {
        if (!ownsInkCommit(commit) || container.sessionManager.identity.first() != commit.identity ||
            !ownsInkCommit(commit) || !inkPages.shouldCommit(commit.pageKey, commit.localVersion)) return
        val id = commit.pageKey.userId
        val now = System.currentTimeMillis()
        // 在互斥锁内再解析一次身份：同一页的防抖保存在本提交之前落库时，必须复用它的
        // annotationId 与最新 revision，否则会重复新建 annotation 或 CAS 冲突。
        val annotationId = commit.annotationId ?: knownInkAnnotationIds[commit.pageKey]
        val baseRevision = maxOf(commit.baseRevision, annotationId?.let { knownInkRevisions[it] } ?: 0L)
        val path = runCatching {
            container.inkStore.save(
                userId = id,
                documentId = commit.target.documentId,
                sectionId = commit.target.sectionId,
                contentRevision = commit.target.contentRevision,
                pageIndex = commit.target.pageIndex,
                manifest = commit.target.manifest(commit.strokes, baseRevision + 1, now),
            )
        }.getOrElse {
            _state.update { it.copy(message = "笔迹文件保存失败") }
            return
        }
        val result = annotationRepository.saveInk(
            context = commit.context,
            documentId = commit.target.documentId,
            locator = commit.target.locator(),
            manifestPath = path,
            annotationId = annotationId,
            baseRevision = baseRevision,
        )
        if (!ownsInkCommit(commit)) return
        when (result) {
            is OperationResult.Ok -> {
                knownInkRevisions[result.value.id] = result.value.revision
                knownInkAnnotationIds[commit.pageKey] = result.value.id
                inkPages.committed(commit.pageKey, commit.localVersion,
                    ReaderInkPageSource(result.value.id, result.value.revision, path))
                _state.update { state ->
                    if (inkTarget?.let { inkKey(it, id) } != commit.pageKey) {
                        // 该页已不是当前会话：只落盘，不回写新页的会话状态（不得改锚）。
                        state
                    } else {
                        state.copy(inkAnnotationId = result.value.id, inkRevision = result.value.revision)
                    }
                }
                refreshDisplayInk()
            }

            is OperationResult.Conflict -> _state.update { it.copy(message = "笔迹版本已变化，请重新进入画笔模式") }
            is OperationResult.Rejected -> _state.update { it.copy(message = "笔迹保存失败：${result.code}") }
            is OperationResult.Failed -> _state.update { it.copy(message = "笔迹保存失败") }
        }
    }

    /** 导航前为新页面算出的会话锚点；非画笔模式返回 null（不需要重建会话）。 */
    private fun nextInkTargetAfterNavigation(
        section: ReaderSectionEntity,
        loaded: ReaderSession,
        progression: Float,
        pageIndex: Int,
    ): InkPageTarget? = if (_state.value.inkMode) inkTargetOf(section, loaded, progression, pageIndex) else null

    /**
     * 导航后为新页面建立干净会话：target 与 annotation 身份同步确定，该页已有笔迹同步读入。
     *
     * 这里刻意同步读文件（与进入画笔模式一致）：若异步加载，用户在加载完成前的第一批笔画
     * 会让"本页已有笔迹"处于未知状态，收尾时可能把空笔画写回文件。文件很小，代价可接受。
     */
    private fun resumeInkAfterNavigation(target: InkPageTarget?) {
        if (target == null) return
        if (!_state.value.inkMode) return
        val id = userId ?: return
        val page = readInkPage(target, id)
        inkTarget = target
        inkDirty = page.version > page.committedVersion
        inkUndoStack.clear()
        inkRedoStack.clear()
        _state.update {
            it.copy(
                inkStrokes = page.strokes,
                inkAnnotationId = page.source?.annotationId,
                inkRevision = page.source?.let { source -> maxOf(source.revision, knownInkRevisions[source.annotationId] ?: 0) } ?: 0,
            )
        }
    }

    private fun inkTargetOf(section: ReaderSectionEntity, loaded: ReaderSession, progression: Float,
        pageIndex: Int = _state.value.currentPage): InkPageTarget =
        InkPageTarget(
            documentId = loaded.document.id,
            sectionId = section.id,
            sectionTitle = section.title,
            contentRevision = loaded.revision.revision,
            pageIndex = if (loaded.format == ReaderFormat.PDF) pageIndex.coerceAtLeast(0) else 0,
            isPdf = loaded.format == ReaderFormat.PDF,
            progression = progression.coerceIn(0f, 1f),
        )

    private fun findInkAnnotation(target: InkPageTarget): ReaderAnnotationView? =
        _state.value.annotations.firstOrNull { item ->
            item.kind == AnnotationKind.INK &&
                item.documentId == target.documentId && item.locator?.contentRevision == target.contentRevision &&
                target.acceptsAnnotation(item.sectionId, inkPageOf(item))
        }

    /**
     * 会话内的 annotation 身份校验：只有**属于本页**的 INK annotation 才能复用 id；
     * 否则按新建处理（绝不把上一页的锚点改到新页）。
     */
    private fun resolveInkAnnotationId(target: InkPageTarget, annotationId: String?): String? {
        if (annotationId == null) return null
        val view = _state.value.annotations.firstOrNull { it.id == annotationId } ?: return annotationId
        if (view.kind != AnnotationKind.INK) return null
        return if (view.documentId == target.documentId && view.locator?.contentRevision == target.contentRevision &&
            target.acceptsAnnotation(view.sectionId, inkPageOf(view))) annotationId else null
    }

    private fun inkKey(target: InkPageTarget, id: Long) = ReaderInkPageKey(id, target.documentId,
        target.contentRevision, target.sectionId, target.pageIndex)

    private fun inkPageChanged(next: InkPageTarget): Boolean = inkTarget?.let {
        it.documentId != next.documentId || it.contentRevision != next.contentRevision ||
            it.sectionId != next.sectionId || it.pageIndex != next.pageIndex
    } == true

    private fun ownsInkCommit(commit: InkCommit): Boolean = commit.readerInstance == readerInstance &&
        readerIdentity == commit.identity && userId == commit.pageKey.userId &&
        session?.document?.id == commit.pageKey.documentId && session?.revision?.revision == commit.pageKey.contentRevision

    private fun readInkPage(target: InkPageTarget, id: Long): ReaderInkPageSnapshot {
        val annotation = findInkAnnotation(target)
        val source = annotation?.inkBlobPath?.let { ReaderInkPageSource(annotation.id, annotation.revision, it) }
        return inkPages.read(inkKey(target, id), source) { observed ->
            runCatching { container.inkStore.load(id, observed.manifestPath) }.getOrNull()
        }
    }

    private fun cachedPdfInk(loaded: ReaderSession, id: Long): Map<Int, List<InkStroke>> =
        if (loaded.format != ReaderFormat.PDF) emptyMap() else inkPages.snapshots().filterKeys {
            it.userId == id && it.documentId == loaded.document.id && it.contentRevision == loaded.revision.revision
        }.mapKeys { it.key.pageIndex }.mapValues { it.value.strokes }

    private fun pdfSectionForPage(loaded: ReaderSession, page: Int): ReaderSectionEntity? =
        loaded.sections.firstOrNull { it.pageStart != null && page in it.pageStart..(it.pageEnd ?: it.pageStart) }
            ?: (if (courseRequest == null) loaded.sections.getOrNull(page) else null)

    private fun inkPageOf(annotation: ReaderAnnotationView): Int =
        (annotation.locator as? ReaderLocator.Fixed)?.pageIndex ?: 0

    private fun createAnnotation(
        locator: ReaderLocator,
        kind: AnnotationKind,
        quote: String,
        style: AnnotationStyle,
        noteText: String?,
    ): Boolean {
        if (historyReadOnly) return false
        val id = userId ?: return false
        selectedAnnotation?.takeIf { it.first == _state.value.selection && matchesCurrentAnnotation(it.second, locator) }?.let { (_, saved) ->
            if (!annotationActions.state.value.isOpen)
                annotationActions.open(accessContext(id), saved, annotationGuard(), showEditor = false)
        }
        val existing = annotationActions.state.value
        if (existing.isOpen && existing.original?.let { matchesCurrentAnnotation(it, locator) } == true && !existing.deleted) {
            annotationActions.chooseColor(style.color)
            if (noteText != null) annotationActions.editNote(noteText)
            saveAnnotation()
            return true
        }
        val precision = if (locator is ReaderLocator.Fixed) "PAGE_ONLY" else "UNRESOLVED"
        val targets = if (locator is ReaderLocator.Reflow && _state.value.selection != null)
            selectionFragments.map { com.example.myapplication.data.reader.notes.AnnotationTargetInput(selectionLocator(it), precision) }
        else listOf(com.example.myapplication.data.reader.notes.AnnotationTargetInput(locator, precision))
        if (targets.isEmpty()) return false
        val selectionProof = if (locator is ReaderLocator.Reflow) bookmarkInput ?: return false else null
        val accepted = annotationCreation.open(accessContext(id), locator, quote, kind, style, noteText,
            targets = targets, guard = annotationGuard(selectionProof), showEditor = false)
        if (accepted) saveAnnotationCreation()
        return accepted
    }

    private fun matchesCurrentAnnotation(annotation: ReaderAnnotationView, locator: ReaderLocator): Boolean {
        if (!legacySameAnnotationSource(annotation.locator, locator)) return false
        val selected = _state.value.selection
        if (locator !is ReaderLocator.Reflow || selected == null) return true
        return legacySelectionMatchesAnnotation(annotation, selected.quote,
            selectionFragments.map { com.example.myapplication.data.reader.notes.AnnotationTargetInput(selectionLocator(it), "UNRESOLVED") })
    }

    private fun buildCurrentLocator(): ReaderLocator? {
        val loaded = session ?: return null
        val section = currentSection ?: return null
        val current = _state.value
        current.selection?.let { selection ->
            return selectionFragments.firstOrNull()?.let(::selectionLocator)
        }
        return if (loaded.format == ReaderFormat.PDF) {
            ReaderLocator.Fixed(
                loaded.document.id,
                section.id,
                loaded.revision.revision,
                current.progress,
                current.currentPage,
                NormalizedRect(0f, 0f, 1f, 1f),
                0,
                listOf(NormalizedRect(0f, 0f, 1f, 1f)),
                current.sectionContent?.title ?: section.title,
            )
        } else {
            viewportLocator?.takeIf { it.sectionId == section.id && it.contentRevision == loaded.revision.revision }
        }
    }

    private fun accessContext(id: Long): AccessContext =
        AccessContext(id, sessionEpoch = readerIdentity?.epoch ?: 0, requestId = UUID.randomUUID().toString())

    private fun scheduleSave(progression: Float, visibleText: String) {
        if (historyReadOnly) return
        val id = userId ?: return
        val loaded = session ?: return
        val section = currentSection ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            val locator = if (loaded.format == ReaderFormat.PDF) {
                ReaderLocator.Fixed(
                    loaded.document.id, section.id, loaded.revision.revision, progression,
                    _state.value.currentPage,
                    NormalizedRect(0f, 0f, 1f, 1f), 0, emptyList(),
                    visibleText.take(160).ifBlank { section.title },
                )
            } else {
                viewportLocator?.takeIf { it.sectionId == section.id && it.contentRevision == loaded.revision.revision }
                    ?.copy(progression = progression) ?: return@launch
            }

            val encoded = ReaderLocatorCodec.encode(locator)
            if (courseRequest != null && readerIdentity != container.sessionManager.identity.first()) return@launch
            val activeLease = lease ?: run {
                val result = repository.acquireWriter(id, documentId)
                if (result is OperationResult.Ok) result.value.also { lease = it } else return@launch
            }
            sequence += 1
            if (repository.saveLocator(id, documentId, activeLease, sequence, encoded) is OperationResult.Conflict) {
                val renewed = repository.acquireWriter(id, documentId)
                if (renewed is OperationResult.Ok) {
                    lease = renewed.value
                    sequence += 1
                    repository.saveLocator(id, documentId, renewed.value, sequence, encoded)
                }
            }
        }
    }

    private fun courseSections(loaded: ReaderSession): List<ReaderSectionEntity> {
        val ordered = loaded.sections.sortedBy { it.orderIndex }
        val target = courseRequest?.target ?: return ordered
        val first = ordered.indexOfFirst { it.id == target.firstSectionId }
        val last = ordered.indexOfFirst { it.id == target.lastSectionId }
        return if (first >= 0 && last >= first) ordered.subList(first, last + 1) else emptyList()
    }

    private fun isAllowedSection(sectionId: String): Boolean = session?.let { courseSections(it).any { section -> section.id == sectionId } } == true

    fun isAllowedLocator(locator: ReaderLocator): Boolean {
        if (courseRequest == null) return locator.documentId == documentId
        val loaded = session ?: return false
        return CourseReaderNavigationPolicy.placement(courseRequest.copy(placement = ReaderPlacement.AT_LOCATOR, locator = locator),
            documentId, loaded.revision.revision, loaded.sections) is OperationResult.Ok
    }

    private fun physicalPageCount(loaded: ReaderSession): Int =
        maxOf(loaded.sections.size, (loaded.sections.maxOfOrNull { it.pageEnd ?: it.pageStart ?: -1 } ?: -1) + 1)

    private suspend fun validCourseOwner(identity: com.example.myapplication.data.session.SessionIdentity): Boolean {
        val request = courseRequest ?: return true
        val book = request.target.courseBookId ?: return false
        val chapter = request.target.chapterKey ?: return false
        val user = identity.userId ?: return false
        if (container.sessionManager.identity.first() != identity) return false
        val source = withContext(Dispatchers.IO) { container.courseCatalog(book) } ?: return false
        val latest = container.courseReadingSequenceProvider.load(AccessContext(user, identity.epoch, UUID.randomUUID().toString()),
            book, allowDevelopmentDraft = false)
        if (latest !is OperationResult.Ok || container.sessionManager.identity.first() != identity ||
            withContext(Dispatchers.IO) { container.courseCatalog(book) } != source) return false
        val resolved = CourseReaderNavigationPolicy.resolve(book, chapter, source, latest.value, request.placement, request.locator)
        return resolved is OperationResult.Ok && resolved.value.request == request
    }

    /** Called only after a fresh viewport reports a real physical boundary. */
    suspend fun moveWithinCourse(direction: ReaderTurnDirection): Boolean {
        val loaded = session ?: return false
        val ordered = courseSections(loaded)
        val index = ordered.indexOfFirst { it.id == currentSection?.id }
        if (index < 0) return false
        val next = ordered.getOrNull(index + if (direction == ReaderTurnDirection.NEXT) 1 else -1) ?: return false
        if (!flushBeforeLeave()) throw IllegalStateException("阅读位置或标注尚未保存")
        currentSection = next
        invalidateWebPosition()
        clearSelection()
        val end = direction == ReaderTurnDirection.PREVIOUS
        val position = CourseLegacyPlacement(next.id, if (end) 1f else 0f,
            if (end) next.pageEnd ?: next.pageStart else next.pageStart)
        _state.update { it.copy(isLoading = true) }
        loadSection(next, null, state.value.settings, placement = position)
        return true
    }

    private fun chooseSection(session: ReaderSession, locator: ReaderLocator?): ReaderSectionEntity {
        locator?.let { value ->
            session.sections.firstOrNull { it.id == value.sectionId }?.let { return it }
            if (value is ReaderLocator.Fixed) {
                session.sections.firstOrNull { it.pageStart == value.pageIndex }?.let { return it }
            }
        }
        return session.sections.first()
    }

    /**
     * W4-11：只读当前 Locator JSON（不触发保存、不改导航）。
     * 选区存在时返回选区 locator；否则返回当前节/当前进度 locator。
     */
    fun currentLocatorJson(): String? =
        buildCurrentLocator()?.let { locator -> runCatching { ReaderLocatorCodec.encode(locator) }.getOrNull() }

    /** Await committed points/ink before the route owner leaves this screen. */
    fun hasFailedPendingSettings(): Boolean = settingsPersistence?.state?.value?.let {
        it.pending && !it.saving && it.failure != null
    } == true

    /** Called only after the reader explicitly confirms stopping unconfirmed settings retries. */
    suspend fun abandonSettingsBeforeLeave(): Boolean {
        settingsPersistence?.abandonPending()
        return flushBeforeLeave()
    }

    suspend fun flushBeforeLeave(): Boolean {
        if (courseRequest != null && readerIdentity?.let { validCourseOwner(it) } != true) return false
        if (annotationCreation.state.value.busy || annotationCreation.state.value.dirty || annotationCreation.state.value.pending != null ||
            annotationActions.state.value.busy || annotationActions.state.value.pending != null ||
            annotationActions.state.value.dirty) return false
        if (settingsPersistence?.flush() == false) return false
        if (historyReadOnly) return true
        saveJob?.join()
        inkSaveJob?.join()
        lastInkCommitJob?.join()
        val id = userId ?: return true
        val locator = currentLocatorJson() ?: return courseRequest == null
        if (courseRequest != null && readerIdentity != container.sessionManager.identity.first()) return false
        val activeLease = lease ?: when (val acquired = repository.acquireWriter(id, documentId)) {
            is OperationResult.Ok -> acquired.value.also { lease = it }
            else -> return false
        }
        sequence += 1
        val result = repository.saveLocator(id, documentId, activeLease, sequence, locator)
        if (result is OperationResult.Ok) return true
        if (result !is OperationResult.Conflict) return false
        val renewed = repository.acquireWriter(id, documentId)
        if (renewed !is OperationResult.Ok) return false
        lease = renewed.value
        sequence += 1
        return repository.saveLocator(id, documentId, renewed.value, sequence, locator) is OperationResult.Ok
    }

    /**
     * W4-11：阅读现场（document/section/quote/locator/scope），供 Agent 工作区与随堂测启动参数。
     * 加载完成前返回 null；不修改阅读状态。
     */
    fun captureReadingContext(expectedSelection: ReaderSelectionSnapshot? = null): ReaderReadingContext? {
        val current = _state.value
        if (session == null || currentSection == null) return null
        val selection = current.selection
        if (expectedSelection != null && (selection != expectedSelection || current.isLoading ||
                current.error != null || current.historyReadOnly || expectedSelection.sectionId != currentSection?.id)) return null
        return ReaderReadingContext(
            documentId = documentId,
            sectionId = selection?.sectionId ?: current.currentSectionId,
            sectionTitle = current.currentSectionTitle.takeIf { it.isNotBlank() },
            quote = selection?.quote,
            locatorJson = currentLocatorJson(),
            readingScope = if (selection != null || courseRequest != null) ReadingScope.CURRENT_SECTION else ReadingScope.CURRENT_DOCUMENT,
        )
    }

    override fun onCleared() {
        annotationCreation.close()
        bookmarkActions.close()
        annotationActions.close()
        settingsPersistence?.close()
        defaultsJob?.cancel()
        settingsJob?.cancel()
        super.onCleared()
    }

    private fun parseSettings(json: String?): ReaderSettings = ReaderSettingsCodec.decode(json)

    private fun encodeSettings(settings: ReaderSettings): String = ReaderSettingsCodec.encode(settings)
    companion object {
        private const val INK_SAVE_DEBOUNCE_MS = 100L
    }

}

class ReaderViewModelFactory(
    private val context: Context,
    private val documentId: String,
    private val initialLocatorJson: String? = null,
    private val courseRequest: ReaderOpenRequest? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ReaderViewModel(
            application = context.applicationContext as LearningApplication,
            documentId = documentId,
            initialLocatorJson = initialLocatorJson,
            courseRequest = courseRequest,
        ) as T
}


/** Shared document-settings serialization used by the reader runtime. */
internal object ReaderSettingsCodec {
    fun decode(json: String?): ReaderSettings {
        return com.example.myapplication.data.reader.ReaderPreferenceStore.decode(json)
    }

    fun encode(settings: ReaderSettings): String = com.example.myapplication.data.reader.ReaderPreferenceStore.encode(settings)
}
