package com.example.myapplication.ui.knowledge.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.myapplication.AppContainer
import com.example.myapplication.data.reader.CourseCatalogSnapshot
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.*
import com.example.myapplication.ui.knowledge.reader.TurnBoundary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

data class CourseReaderUiState(val loading: Boolean = true, val binding: CourseReaderBinding? = null,
    val previousTitle: String? = null, val nextTitle: String? = null, val error: String? = null, val closed: Boolean = false,
    val quizAvailable: Boolean = false)

internal suspend fun AppContainer.courseCatalog(bookId: String): CourseCatalogSnapshot? = database.withTransaction {
    val dao = database.courseBookDao()
    dao.findBook(bookId)?.let { CourseCatalogSnapshot(it, dao.listAllChapters(bookId)) }
}

/** Finite production binding; every confirmed turn rechecks both owner and the complete catalog. */
class CourseReaderViewModel(private val container: AppContainer, private val bookId: String,
    private val chapterKey: String, private val placement: ReaderPlacement, private val locator: ReaderLocator?,
    private val consumedPositionJson: String? = null) : ViewModel() {
    private val mutableState = MutableStateFlow(CourseReaderUiState())
    val state = mutableState.asStateFlow()
    private var owner: SessionIdentity? = null
    private var baseline: CourseCatalogSnapshot? = null
    private var loadJob: Job? = null
    init {
        viewModelScope.launch {
            var first = true
            container.sessionManager.identity.distinctUntilChanged().collect { identity ->
                if (first) { first = false; owner = identity; reload() }
                else if (owner != identity) {
                    owner = identity; loadJob?.cancel()
                    mutableState.value = CourseReaderUiState(false, error = "登录会话已变化，请返回目录重新打开", closed = true)
                }
            }
        }
    }
    fun reload() {
        val identity = owner ?: return
        if (state.value.closed) return
        loadJob?.cancel()
        mutableState.value = CourseReaderUiState()
        loadJob = viewModelScope.launch {
            try {
                val user = identity.userId ?: error("请先登录")
                val source = withContext(Dispatchers.IO) { container.courseCatalog(bookId) } ?: error("课程不存在")
                val sequence = container.courseReadingSequenceProvider.load(AccessContext(user, identity.epoch, UUID.randomUUID().toString()),
                    bookId, allowDevelopmentDraft = false)
                ensureOwner(identity)
                if (sequence !is OperationResult.Ok) error("这章暂不可用或内容已变化，请返回目录")
                var resetNotice: String? = null
                var effectivePlacement = placement
                var effectiveLocator = locator
                if (consumedPositionJson != null) {
                    val unit = sequence.value.orderedTargets.singleOrNull { it.courseBookId == bookId && it.chapterKey == chapterKey }
                        ?: error("课程章节已变化，请返回目录")
                    val sections = withContext(Dispatchers.IO) { container.database.readerCatalogDao().findSections(unit.documentId, unit.contentRevision) }
                    var restored = CourseConsumedPositionCodec.restore(consumedPositionJson, user, identity.epoch,
                        bookId, chapterKey, unit, sections)
                    if (restored == CourseConsumedPosition.LegacyWithoutLocator) {
                        val saved = withContext(Dispatchers.IO) { container.database.readerStateDao().find(user, unit.documentId)?.locatorJson }
                            ?.let(ReaderLocatorCodec::decodeOrNull)
                        restored = if (saved != null && CourseReaderNavigationPolicy.placement(
                                ReaderOpenRequest(unit, ReaderPlacement.AT_LOCATOR, saved), unit.documentId,
                                unit.contentRevision, sections) is OperationResult.Ok) CourseConsumedPosition.Located(saved)
                            else CourseConsumedPosition.Reset
                    }
                    when (restored) {
                        is CourseConsumedPosition.Located -> {
                            effectivePlacement = ReaderPlacement.AT_LOCATOR; effectiveLocator = restored.locator
                        }
                        CourseConsumedPosition.Reset -> {
                            effectivePlacement = ReaderPlacement.START; effectiveLocator = null
                            resetNotice = "保存的阅读位置已失效，将从本章开头阅读"
                        }
                        else -> Unit // A new entry with no consumption marker keeps its explicit initial placement.
                    }
                }
                val resolved = CourseReaderNavigationPolicy.resolve(bookId, chapterKey, source, sequence.value, effectivePlacement, effectiveLocator)
                if (resolved !is OperationResult.Ok) error("章节或阅读位置已失效，请返回目录重新打开")
                val binding = resolved.value
                val titles = withContext(Dispatchers.IO) {
                    suspend fun title(target: ReaderTarget?): String? = target?.let {
                        container.database.readerCatalogDao().findSections(it.documentId, it.contentRevision)
                            .firstOrNull { section -> section.id == it.firstSectionId }?.title
                            ?: container.database.readerCatalogDao().findDocument(it.documentId)?.title
                    }
                    title(binding.previous) to title(binding.next)
                }
                if (withContext(Dispatchers.IO) { container.courseCatalog(bookId) } != source) error("课程目录已变化，请重新打开")
                ensureOwner(identity)
                baseline = source
                val mapping = source.chapters.single { it.chapterKey == chapterKey }
                mutableState.value = CourseReaderUiState(false, binding, titles.first, titles.second, error = resetNotice,
                    quizAvailable = mapping.sectionId == null && mapping.legacyChapterId != null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                ensureOwner(identity)
                mutableState.value = CourseReaderUiState(false, error = failure.message ?: "课程加载失败")
            }
        }
    }
    suspend fun confirmedTurn(direction: ReaderTurnDirection): ReaderOpenRequest? {
        val identity = owner ?: return null
        val binding = state.value.binding ?: return null
        try {
            ensureOwner(identity)
            val user = identity.userId ?: return null
            val source = withContext(Dispatchers.IO) { container.courseCatalog(bookId) }
            if (source != baseline) error("课程目录已变化，请返回目录重新打开")
            val fresh = container.courseReadingSequenceProvider.load(AccessContext(user, identity.epoch, UUID.randomUUID().toString()),
                bookId, allowDevelopmentDraft = false)
            ensureOwner(identity)
            if (fresh !is OperationResult.Ok || withContext(Dispatchers.IO) { container.courseCatalog(bookId) } != baseline)
                error("课程内容已变化，请返回目录重新打开")
            ensureOwner(identity)
            val turn = CourseReaderNavigationPolicy.turn(binding, fresh.value, direction)
            if (turn !is TurnBoundary.Adjacent) error("章节目标已变化，请返回目录重新打开")
            val request = ReaderOpenRequest(turn.target, turn.placement)
            // Prepare the actual target body before replacing the current entry or writer.
            // A missing/corrupt target leaves this reader and its just-flushed point intact.
            withContext(Dispatchers.IO) {
                val opened = container.readerRepository.openSession(user, request.target.documentId)
                if (opened !is OperationResult.Ok) error("目标章节无法加载，当前章节已保留")
                val position = CourseReaderNavigationPolicy.placement(request, opened.value.document.id,
                    opened.value.revision.revision, opened.value.sections)
                if (position !is OperationResult.Ok) error("目标章节范围已变化，当前章节已保留")
                val content = container.readerRepository.loadSection(opened.value, position.value.sectionId)
                if (content !is OperationResult.Ok) error("目标正文缺失，当前章节已保留")
                if (opened.value.format == com.example.myapplication.data.reader.importer.ReaderFormat.PDF) {
                    val file = content.value.pdfFile ?: error("目标 PDF 缺失，当前章节已保留")
                    android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                            val page = position.value.pageIndex ?: error("目标 PDF 页码缺失")
                            if (page !in 0 until renderer.pageCount) error("目标 PDF 页码已变化")
                            renderer.openPage(page).use { sourcePage ->
                                val bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                                try { sourcePage.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                                finally { bitmap.recycle() }
                            }
                        }
                    }
                } else if (content.value.html.isBlank()) error("目标正文为空，当前章节已保留")
                if (container.database.readerCatalogDao().findDocument(request.target.documentId)?.contentRevision != request.target.contentRevision)
                    error("目标正文版本已变化，当前章节已保留")
                if (container.courseCatalog(bookId) != baseline) error("课程目录已变化，当前章节已保留")
            }
            ensureOwner(identity)
            return request
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            ensureOwner(identity)
            mutableState.update { it.copy(error = failure.message ?: "无法打开相邻章节") }
            return null
        }
    }
    fun clearNotice() { mutableState.update { if (it.binding != null) it.copy(error = null) else it } }
    private suspend fun ensureOwner(identity: SessionIdentity) {
        currentCoroutineContext().ensureActive()
        if (owner != identity || state.value.closed || container.sessionManager.identity.first() != identity)
            throw CancellationException("Course reader owner changed")
    }
}

/** A verified source gains a course scope only when its current physical position has one owner. */
internal suspend fun resolveCourseSource(container: AppContainer, documentId: String, locator: ReaderLocator): CourseReaderBinding? {
    val identity = container.sessionManager.identity.first()
    val user = identity.userId ?: return null
    val books = container.database.courseBookDao().observeBooks().first().filter { it.reviewStatus == "APPROVED" }
    val candidates = mutableListOf<CourseReaderBinding>()
    for (book in books) {
        val source = container.courseCatalog(book.bookId) ?: continue
        if (source.chapters.none { it.enabled && it.documentId == documentId }) continue
        val sequence = container.courseReadingSequenceProvider.load(AccessContext(user, identity.epoch, UUID.randomUUID().toString()),
            book.bookId, allowDevelopmentDraft = false)
        if (sequence !is OperationResult.Ok) continue
        val sections = container.database.readerCatalogDao().findSections(documentId, locator.contentRevision)
        for (target in sequence.value.orderedTargets.filter { it.documentId == documentId }) {
            val resolved = CourseReaderNavigationPolicy.resolve(book.bookId, target.chapterKey.orEmpty(), source,
                sequence.value, ReaderPlacement.AT_LOCATOR, locator)
            if (resolved is OperationResult.Ok && CourseReaderNavigationPolicy.placement(resolved.value.request, documentId,
                    locator.contentRevision, sections) is OperationResult.Ok) candidates += resolved.value
        }
        if (container.courseCatalog(book.bookId) != source) return null
    }
    if (container.sessionManager.identity.first() != identity) return null
    return candidates.singleOrNull()
}
