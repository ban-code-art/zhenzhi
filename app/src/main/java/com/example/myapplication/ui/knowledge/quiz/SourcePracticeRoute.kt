package com.example.myapplication.ui.knowledge.quiz

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.myapplication.LearningApplication
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.data.quiz.SourcePractice
import com.example.myapplication.data.quiz.SourcePracticeFactory
import com.example.myapplication.data.quiz.SourcePracticeSection
import com.example.myapplication.data.quiz.SourcePracticePool
import com.example.myapplication.data.quiz.SourcePracticeScope
import com.example.myapplication.data.quiz.SourcePracticeDocumentBinding
import com.example.myapplication.data.quiz.SourcePracticeText
import com.example.myapplication.data.quiz.SourcePracticeTextSegment
import com.example.myapplication.data.quiz.sourcePracticeSlice
import com.example.myapplication.data.quiz.QuizReviewService
import com.example.myapplication.data.quiz.QuizSupportState
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.BlockKind
import com.example.myapplication.domain.reader.ContentBlock
import com.example.myapplication.domain.reader.DocumentAccess
import com.example.myapplication.domain.reader.DocumentSnapshot
import com.example.myapplication.domain.reader.ExtractionQuality
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderPage
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.NormalizedRect
import com.example.myapplication.domain.reader.SectionDescriptor
import com.example.myapplication.domain.reader.SourceReadBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/** Directed device checks read this metric without adding diagnostic text to the learning UI. */
val SourcePracticeLoadTimeMs = SemanticsPropertyKey<Long>("SourcePracticeLoadTimeMs")
val SourcePracticeMoreLoadTimeMs = SemanticsPropertyKey<Long>("SourcePracticeMoreLoadTimeMs")
val SourcePracticeBatchNumber = SemanticsPropertyKey<Int>("SourcePracticeBatchNumber")

private data class SourcePracticeSession(
    val practice: SourcePractice,
    val accessContext: AccessContext? = null,
    val snapshot: DocumentSnapshot? = null,
    val continuation: SourcePracticeLoader.OriginalCursor? = null,
    val pool: SourcePracticePool? = null,
    val archiveScope: SourcePracticeScope? = null,
)

@Composable
fun SourcePracticeRoute(
    documentId: String,
    sectionId: String?,
    onBack: () -> Unit,
    onGenerateAi: () -> Unit,
    paperId: String? = null,
    onAskAi: ((SourcePracticeScope) -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as LearningApplication).container }
    val archive = container.sourcePracticeArchiveRepository
    val identity by container.sessionManager.identity.collectAsState(SessionIdentity(null, -1))
    val scope = rememberCoroutineScope()
    var session by remember(documentId, sectionId, paperId, identity) { mutableStateOf<SourcePracticeSession?>(null) }
    var error by remember(documentId, sectionId, paperId, identity) { mutableStateOf<String?>(null) }
    var loading by remember(documentId, sectionId, paperId, identity) { mutableStateOf(true) }
    var loadTime by remember(documentId, sectionId, paperId, identity) { mutableStateOf(0L) }
    var retry by remember(documentId, sectionId, paperId, identity) { mutableStateOf(0) }
    var moreLoading by remember(documentId, sectionId, paperId, identity) { mutableStateOf(false) }
    var moreError by remember(documentId, sectionId, paperId, identity) { mutableStateOf<String?>(null) }
    var sourceWarning by remember(documentId, sectionId, paperId, identity) { mutableStateOf<String?>(null) }
    var moreLoadTime by remember(documentId, sectionId, paperId, identity) { mutableStateOf(0L) }
    var moreRequest by remember { mutableStateOf(0) }
    var moreJob by remember { mutableStateOf<Job?>(null) }
    val cancelMore: () -> Unit = {
        moreRequest++
        moreJob?.cancel()
        moreJob = null
        moreLoading = false
    }
    val back: () -> Unit = { cancelMore(); onBack() }
    DisposableEffect(documentId, sectionId, paperId, identity) {
        onDispose {
            moreRequest++
            moreJob?.cancel()
            moreJob = null
        }
    }
    LaunchedEffect(documentId, sectionId, paperId, identity, retry) {
        cancelMore()
        session = null
        error = null
        sourceWarning = null
        loading = true
        val captured = identity
        val userId = captured.userId
        if (userId == null || userId <= 0 || captured.epoch < 0) {
            error = "请先登录后开始练习。"
            loading = false
            return@LaunchedEffect
        }
        val started = SystemClock.elapsedRealtime()
        try {
            val loaded = withTimeoutOrNull(7_500L) { withContext(Dispatchers.IO) {
                val ctx = AccessContext(userId, captured.epoch, "source-practice-" + UUID.randomUUID())
                val loader = SourcePracticeLoader(container.roomDocumentAccess)
                if (paperId != null) {
                    val reviewed = container.quizReviewService.load(userId, paperId)
                        ?: throw SourcePracticeLoadException("这份学习草稿已处理、已变化或不可用。")
                    loader.verifyReviewSources(ctx, reviewed)
                    val title = (reviewed.draft.scopeSnapshot["documentId"] as? JsonPrimitive)?.contentOrNull
                        ?.let { container.database.readerCatalogDao().findDocumentIncludingDeleted(it)?.title }
                        ?: "随堂学习草稿"
                    val practice = SourcePracticeFactory.fromReviewDraft(title, reviewed)
                    val usedSources = reviewed.draft.items.mapIndexedNotNull { index, item ->
                        item.takeIf { reviewed.supportAssessments.getOrNull(index)?.state != QuizSupportState.UNSUPPORTED }
                    }.flatMap { it.evidenceIds }.toSet()
                    val bindings = reviewed.sources.filter { it.evidenceId in usedSources }.groupBy { it.documentId }.map { (document, refs) ->
                        val source = loader.open(ctx, document, reviewed.draft.sourceRevision)
                        SourcePracticeDocumentBinding(document, source.contentRevision, refs.map { it.sectionId }.distinct(),
                            source.ownerUserId, source.accessEpoch, source.metadataRevision, source.extractorVersion)
                    }
                    val primary = bindings.firstOrNull { it.documentId == documentId } ?: bindings.firstOrNull()
                        ?: throw SourcePracticeLoadException("草稿缺少可恢复的原文章节。")
                    val practiceScope = SourcePracticeScope(primary.documentId, primary.sectionIds, title = title,
                        documentOwnerUserId = primary.ownerUserId, accessEpoch = primary.accessEpoch,
                        metadataRevision = primary.metadataRevision, extractorVersion = primary.extractorVersion,
                        relatedSources = bindings, sourceRevision = practice.sourceRevision)
                    archive.ensure(captured, practice, practiceScope)
                    SourcePracticeSession(practice, archiveScope = practiceScope)
                } else {
                    val snapshot = loader.open(ctx, documentId)
                    val title = container.database.readerCatalogDao().findDocumentIncludingDeleted(documentId)?.title
                        ?: "原文随堂练习"
                    val read = loader.readOriginal(ctx, snapshot, sectionId?.takeIf { it.isNotBlank() })
                    val pool = withContext(Dispatchers.Default) {
                        SourcePracticePool(title, snapshot.contentRevision).also {
                            it.append(read.sections, read.complete, read.continuation != null)
                        }
                    }
                    val batch = pool.nextBatch()
                    loader.verifySnapshot(ctx, snapshot)
                    currentCoroutineContext().ensureActive()
                    val practice = batch?.practice ?: pool.emptyPractice()
                    val practiceScope = sourcePracticeScope(snapshot, practice, sectionId)
                    archive.ensure(captured, practice, practiceScope, batch?.number ?: 1)
                    SourcePracticeSession(practice, ctx, snapshot, read.continuation, pool, practiceScope)
                }
            } } ?: throw SourcePracticeLoadException("准备原文练习超时，请重试或选择较短的章节。")
            currentCoroutineContext().ensureActive()
            if (container.sessionManager.identity.first() != captured) return@LaunchedEffect
            session = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (container.sessionManager.identity.first() == captured) {
                error = (failure as? SourcePracticeLoadException)?.message ?: "读取练习材料失败，请重试。"
            }
        } finally {
            if (container.sessionManager.identity.first() == captured) {
                loadTime = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                loading = false
            }
        }
    }
    val generateMore: () -> Unit = more@{
        val current = session ?: return@more
        val pool = current.pool ?: return@more
        val snapshot = current.snapshot ?: return@more
        val ctx = current.accessContext ?: return@more
        if (moreLoading || sourceWarning != null || !pool.hasMore) return@more
        val captured = identity
        val request = ++moreRequest
        moreLoading = true
        moreError = null
        moreJob = scope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                val prepared = withTimeoutOrNull(5_000L) { withContext(Dispatchers.IO) {
                    val loader = SourcePracticeLoader(container.roomDocumentAccess)
                    loader.verifySnapshot(ctx, snapshot)
                    val nextPool = pool.fork()
                    var continuation = current.continuation
                    var next = nextPool.nextBatch()
                    var windows = 0
                    while (next == null && continuation != null && windows < 4) {
                        currentCoroutineContext().ensureActive()
                        val read = loader.readOriginal(ctx, snapshot, sectionId?.takeIf { it.isNotBlank() }, continuation)
                        continuation = read.continuation
                        withContext(Dispatchers.Default) {
                            nextPool.append(read.sections, read.complete, continuation != null)
                        }
                        next = nextPool.nextBatch()
                        windows++
                    }
                    loader.verifySnapshot(ctx, snapshot)
                    currentCoroutineContext().ensureActive()
                    val practiceScope = if (next != null) sourcePracticeScope(snapshot, next.practice, sectionId) else current.archiveScope
                    if (next != null && practiceScope != null) archive.ensure(captured, next.practice, practiceScope, next.number)
                    current.copy(practice = next?.practice ?: current.practice, continuation = continuation,
                        pool = nextPool, archiveScope = practiceScope)
                } } ?: throw SourcePracticeLoadException("准备新题超时，当前答案已保留，请重试。")
                currentCoroutineContext().ensureActive()
                if (request != moreRequest || session !== current || container.sessionManager.identity.first() != captured)
                    return@launch
                session = prepared
                if (prepared.practice === current.practice && prepared.pool?.hasMore == true) {
                    moreError = "这段材料暂无新题，可继续读取后文。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (request == moreRequest && session === current && container.sessionManager.identity.first() == captured) {
                    val sourceFailure = failure as? SourcePracticeLoadException
                    if (sourceFailure?.invalidatesSource == true) {
                        sourceWarning = "来源已变化，当前题目、作答与历史摘录已保留。请返回阅读后重新开始练习。"
                        moreError = null
                    } else {
                        moreError = sourceFailure?.message ?: "准备新题失败，当前答案已保留，请重试。"
                    }
                }
            } finally {
                if (request == moreRequest && container.sessionManager.identity.first() == captured) {
                    moreLoadTime = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
                    moreLoading = false
                    moreJob = null
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().testTag("source-practice-route").semantics {
        this[SourcePracticeLoadTimeMs] = loadTime
        this[SourcePracticeMoreLoadTimeMs] = moreLoadTime
        this[SourcePracticeBatchNumber] = session?.pool?.batchNumber?.coerceAtLeast(1) ?: 1
    }) {
        val loaded = session
        if (loaded != null) {
            TrackedSourcePracticeScreen(
                practice = loaded.practice,
                scope = checkNotNull(loaded.archiveScope),
                onBack = back,
                onAskAi = onAskAi,
                repository = archive,
                onGenerateAi = onGenerateAi.takeIf { paperId == null },
                onGenerateMore = generateMore.takeIf { loaded.pool != null && paperId == null },
                moreLoading = moreLoading,
                moreExhausted = loaded.pool?.hasMore == false,
                moreError = moreError,
                batchNumber = loaded.pool?.batchNumber?.coerceAtLeast(1) ?: 1,
                onCancelMore = cancelMore,
                sourceWarning = sourceWarning,
                moreExhaustionReason = ("本次练习已达到 " + SourcePracticePool.MAX_SESSION_QUESTIONS +
                    " 题上限，请返回阅读后按章节继续。").takeIf { loaded.pool?.limitReached == true },
            )
        } else {
            Column(Modifier.align(Alignment.Center).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading) {
                    CircularProgressIndicator()
                    Text("正在准备原文练习…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(error ?: "当前没有可用练习。", modifier = Modifier.testTag("source-practice-error"))
                    UiAction(label = "重新读取", onClick = { retry++ }, filled = true,
                        modifier = Modifier.testTag("source-practice-retry"), actionId = "sourcePractice.archive.reload")
                    UiAction(label = "返回阅读", onClick = back, actionId = "sourcePractice.archive.back")
                }
            }
        }
    }
}

private fun sourcePracticeScope(snapshot: DocumentSnapshot, practice: SourcePractice, sectionId: String?) = SourcePracticeScope(
    documentId = snapshot.documentId,
    sectionIds = practice.questions.flatMap { it.sectionIds }.distinct().ifEmpty { listOfNotNull(sectionId?.takeIf { it.isNotBlank() }) },
    title = practice.title,
    documentOwnerUserId = snapshot.ownerUserId,
    accessEpoch = snapshot.accessEpoch,
    metadataRevision = snapshot.metadataRevision,
    extractorVersion = snapshot.extractorVersion,
    sourceRevision = practice.sourceRevision,
)

internal class SourcePracticeLoadException(message: String, val invalidatesSource: Boolean = false) : Exception(message)

internal class SourcePracticeLoader(private val access: DocumentAccess) {
    data class OriginalRead(
        val sections: List<SourcePracticeSection>,
        val complete: Boolean,
        val continuation: OriginalCursor? = null,
        /** Same order as sections; offsets refer to the immutable source, not generated questions. */
        val locators: List<ReaderLocator> = emptyList(),
    )

    /** A cursor is bound to one authorized snapshot and keeps the unconsumed page and block offset. */
    data class OriginalCursor(
        val userId: Long,
        val sessionEpoch: Long,
        val snapshot: DocumentSnapshot,
        val descriptors: List<SectionDescriptor>,
        val sectionIndex: Int = 0,
        val pageCursor: String? = null,
        val page: ReaderPage<ContentBlock>? = null,
        val blockIndex: Int = 0,
        val blockOffset: Int = 0,
        val blockSegments: List<SourcePracticeTextSegment>? = null,
        val segmentIndex: Int = 0,
        val seenCursors: Set<String> = emptySet(),
        val pageCount: Int = 0,
        val fullyVerified: Boolean = true,
    )

    suspend fun open(ctx: AccessContext, documentId: String, revision: String? = null): DocumentSnapshot =
        when (val result = access.open(ctx, documentId, revision)) {
            is OperationResult.Ok -> result.value
            is OperationResult.Failed -> throw SourcePracticeLoadException("原文暂时无法读取，请重试。")
            else -> throw SourcePracticeLoadException("原文已变化或无法访问，请返回阅读后重试。", true)
        }

    suspend fun verifySnapshot(ctx: AccessContext, expected: DocumentSnapshot) {
        val current = open(ctx, expected.documentId)
        if (current.documentId != expected.documentId || current.contentRevision != expected.contentRevision ||
            current.ownerUserId != expected.ownerUserId ||
            current.accessEpoch != expected.accessEpoch || current.metadataRevision != expected.metadataRevision ||
            current.extractorVersion != expected.extractorVersion) {
            throw SourcePracticeLoadException("原文已变化，请返回阅读重新开始练习。", true)
        }
    }

    suspend fun readOriginal(
        ctx: AccessContext,
        snapshot: DocumentSnapshot,
        sectionId: String?,
        continuation: OriginalCursor? = null,
    ): OriginalRead {
        val coroutine = currentCoroutineContext()
        val budget = SourceReadBudget(32L * 1024 * 1024, { coroutine.ensureActive() })
        verifySnapshot(ctx, snapshot)
        if (continuation != null && (continuation.userId != ctx.userId ||
                continuation.sessionEpoch != ctx.sessionEpoch || continuation.snapshot != snapshot)) {
            throw SourcePracticeLoadException("练习来源或登录状态已变化，请返回阅读重新开始。", true)
        }
        val selected = continuation?.descriptors ?: descriptors(ctx, snapshot).let { descriptors ->
            if (sectionId == null) descriptors else listOf(descriptors.firstOrNull { it.id == sectionId }
                ?: throw SourcePracticeLoadException("当前章节已变化，请返回阅读重新选择。", true))
        }
        val sections = mutableListOf<SourcePracticeSection>()
        val locators = mutableListOf<ReaderLocator>()
        var sectionIndex = continuation?.sectionIndex ?: 0
        var pageCursor = continuation?.pageCursor
        var page = continuation?.page
        var blockIndex = continuation?.blockIndex ?: 0
        var blockOffset = continuation?.blockOffset ?: 0
        var blockSegments = continuation?.blockSegments
        var segmentIndex = continuation?.segmentIndex ?: 0
        val seen = continuation?.seenCursors?.toMutableSet() ?: mutableSetOf()
        var pageCount = continuation?.pageCount ?: 0
        var fullyVerified = continuation?.fullyVerified ?: true
        var completedSections = 0
        var sectionChars = 0
        var totalChars = 0
        fun nextBlock() {
            blockIndex++
            blockOffset = 0
            blockSegments = null
            segmentIndex = 0
        }
        fun finishPage() {
            val current = page ?: return
            if (blockIndex < current.items.size) return
            page = null
            blockIndex = 0
            blockOffset = 0
            blockSegments = null
            segmentIndex = 0
            pageCursor = current.nextCursor
            if (pageCursor == null) {
                sectionIndex++
                completedSections++
                sectionChars = 0
                seen.clear()
                pageCount = 0
            }
        }
        while (sectionIndex < selected.size && totalChars < MAX_TEXT_CHARS &&
            sectionChars < MAX_SECTION_TEXT && completedSections < MAX_SECTIONS) {
            coroutine.ensureActive()
            val descriptor = selected[sectionIndex]
            if (page == null) {
                if (budget.remaining <= 0) break
                if (pageCount >= MAX_PAGES || pageCursor?.let { it in seen } == true) {
                    throw SourcePracticeLoadException("当前章节分页异常，请返回阅读后重试。")
                }
                val result = access.readBlocksBounded(ctx, snapshot.snapshotId, descriptor.id, pageCursor, 512 * 1024, budget)
                val read = if (result is OperationResult.Failed && result.code == "BOUNDED_READ_UNSUPPORTED")
                    access.readBlocks(ctx, snapshot.snapshotId, descriptor.id, pageCursor, 512 * 1024) else result
                val loaded = when (read) {
                    is OperationResult.Ok -> read.value
                    is OperationResult.Failed -> {
                        if (read.code == "SOURCE_IO_BUDGET_EXCEEDED") {
                            if (sections.isNotEmpty()) break
                            throw SourcePracticeLoadException("当前章节超出本次读取预算，请选择较短的章节。")
                        }
                        throw SourcePracticeLoadException("当前章节暂不可读，请重试。")
                    }
                    else -> throw SourcePracticeLoadException("原文权限或版本已变化，请返回阅读。", true)
                }
                if (loaded.items.sumOf { it.text.length.toLong() } > MAX_CACHED_PAGE_CHARS) {
                    throw SourcePracticeLoadException("当前原文段落过长，请选择较短的章节或拆分材料后重试。")
                }
                if (loaded.items.any { it.sectionId != descriptor.id }) {
                    throw SourcePracticeLoadException("章节内容与来源不一致，请返回阅读重新开始。", true)
                }
                pageCursor?.let(seen::add)
                pageCount++
                page = loaded
            }
            val currentPage = checkNotNull(page)
            if (blockIndex >= currentPage.items.size) { finishPage(); continue }
            val block = currentPage.items[blockIndex]
            if (block.extractionQuality != ExtractionQuality.VERIFIED_TEXT) {
                fullyVerified = false
                nextBlock()
                finishPage()
                continue
            }
            if (block.kind == BlockKind.HEADING) { nextBlock(); finishPage(); continue }
            val segments = blockSegments ?: SourcePracticeText.segments(block.text).also { blockSegments = it }
            if (segmentIndex >= segments.size) { nextBlock(); finishPage(); continue }
            val segment = segments[segmentIndex]
            val start = maxOf(blockOffset, segment.sourceStart)
            val limit = minOf(MAX_SECTION_TEXT - sectionChars, MAX_TEXT_CHARS - totalChars, segment.sourceEnd - start)
            if (limit <= 0) {
                segmentIndex++
                continue
            }
            var value = sourcePracticeSlice(block.text, start, limit)
            if (value.isEmpty()) break // A surrogate pair at the window edge remains for the next window.
            if (start + value.length < segment.sourceEnd && value.length > 512) {
                val boundary = value.indexOfLast { it in "。！？；\r\n" }
                if (boundary >= value.length - 512) value = value.substring(0, boundary + 1)
            }
            blockOffset = start + value.length
            totalChars += value.length
            sectionChars += value.length
            if (value.isNotBlank()) {
                sections += SourcePracticeSection(descriptor.id, descriptor.title, value,
                    segment.explanation.takeIf { blockOffset == segment.sourceEnd }.orEmpty())
                val fixedPage = block.fixedPageIndex ?: descriptor.pageStart
                locators += if (fixedPage != null) ReaderLocator.Fixed(
                    snapshot.documentId, descriptor.id, snapshot.contentRevision, null, fixedPage,
                    NormalizedRect(0f, 0f, 1f, 1f), 0, emptyList(), value,
                ) else ReaderLocator.Reflow(snapshot.documentId, descriptor.id, snapshot.contentRevision,
                    null, block.nativeLocator, value, canonicalStart = block.canonicalStart + start,
                    canonicalEnd = block.canonicalStart + start + value.length, blockId = block.blockId)
            }
            if (blockOffset >= segment.sourceEnd) segmentIndex++
            if (segmentIndex >= segments.size) { nextBlock(); finishPage() }
        }
        finishPage()
        val next = if (sectionIndex < selected.size) OriginalCursor(
            ctx.userId, ctx.sessionEpoch, snapshot, selected, sectionIndex, pageCursor, page, blockIndex,
            blockOffset, blockSegments, segmentIndex, seen.toSet(), pageCount, fullyVerified,
        ) else null
        return OriginalRead(sections, next == null && fullyVerified, next, locators)
    }

    suspend fun verifyReviewSources(ctx: AccessContext, session: QuizReviewService.Session) {
        val sourceIds = session.draft.items.mapIndexedNotNull { index, item ->
            item.takeIf { session.supportAssessments.getOrNull(index)?.state != QuizSupportState.UNSUPPORTED }
        }.flatMap { it.evidenceIds }.toSet()
        val sources = session.sources.filter { it.evidenceId in sourceIds }
        if (sources.size != sourceIds.size || sources.isEmpty())
            throw SourcePracticeLoadException("草稿缺少可核对的原文依据。")
        for ((documentId, refs) in sources.groupBy { it.documentId }) {
            val snapshot = open(ctx, documentId, session.draft.sourceRevision)
            val frozen = session.draft.scopeSnapshot
            fun matches(key: String, value: String) = (frozen[key] as? JsonPrimitive)?.contentOrNull == value
            if (!matches("accessEpoch", snapshot.accessEpoch.toString()) ||
                !matches("metadataRevision", snapshot.metadataRevision.toString()) ||
                !matches("sourceOwnerUserId", snapshot.ownerUserId?.toString() ?: "PUBLIC") ||
                !matches("extractorVersion", snapshot.extractorVersion)) {
                throw SourcePracticeLoadException("草稿对应的原文已变化，请重新生成。")
            }
            for (ref in refs) {
                currentCoroutineContext().ensureActive()
                val read = access.readRange(ctx, snapshot.snapshotId, ref.locator, 8192)
                val evidence = (read as? OperationResult.Ok)?.value
                    ?: throw SourcePracticeLoadException("草稿的原文依据暂不可读，请重试。")
                if (evidence.quote != ref.quote || evidence.quoteSha256 != ref.quoteSha256 ||
                    evidence.locator != ref.locator || evidence.documentId != ref.documentId ||
                    evidence.contentRevision != ref.contentRevision || evidence.sectionId != ref.sectionId ||
                    evidence.extractorVersion != snapshot.extractorVersion) {
                    throw SourcePracticeLoadException("草稿对应的原文已变化，请重新生成。")
                }
            }
            verifySnapshot(ctx, snapshot)
        }
    }

    private suspend fun descriptors(ctx: AccessContext, snapshot: DocumentSnapshot): List<SectionDescriptor> {
        val result = snapshot.sections.toMutableList()
        var cursor = snapshot.sectionsNextCursor
        val seen = mutableSetOf<String>()
        var pages = 0
        while (cursor != null) {
            currentCoroutineContext().ensureActive()
            if (++pages > MAX_PAGES || !seen.add(cursor) || result.size > MAX_DESCRIPTOR_COUNT)
                throw SourcePracticeLoadException("章节目录过大，请选择单个章节练习。")
            val page = when (val read = access.readSectionDescriptors(ctx, snapshot.snapshotId, cursor, 64)) {
                is OperationResult.Ok -> read.value
                else -> throw SourcePracticeLoadException("章节目录已变化，请返回阅读。")
            }
            result += page.items
            if (result.size > MAX_DESCRIPTOR_COUNT)
                throw SourcePracticeLoadException("章节目录过大，请选择单个章节练习。")
            cursor = page.nextCursor
        }
        return result.distinctBy { it.id }.sortedBy { it.orderIndex }
    }

    companion object {
        private const val MAX_SECTIONS = 24
        private const val MAX_DESCRIPTOR_COUNT = 1024
        private const val MAX_PAGES = 128
        private const val MAX_SECTION_TEXT = 16 * 1024
        private const val MAX_TEXT_CHARS = 128 * 1024
        private const val MAX_CACHED_PAGE_CHARS = 4L * 1024 * 1024
    }
}
