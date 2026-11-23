package com.example.myapplication.ui.knowledge.quiz

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myapplication.AppContainer
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.learning.ChapterQaException
import com.example.myapplication.data.learning.ChapterQaPassage
import com.example.myapplication.data.learning.locatorWithin
import com.example.myapplication.data.agent.evidence.SourceVerifier
import com.example.myapplication.data.agent.evidence.VerificationOutcome
import com.example.myapplication.data.quiz.SourcePracticeDocumentBinding
import com.example.myapplication.data.quiz.SourcePracticeScope
import com.example.myapplication.data.quiz.SourcePracticeSection
import com.example.myapplication.data.quiz.sourcePracticeSlice
import com.example.myapplication.data.reader.content.ContentPackageArchive
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.ai.ChatMessage
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.ui.ai.*
import com.example.myapplication.ui.ai.AgentPresentedMarkdown
import com.example.myapplication.ui.ai.rememberAgentAnswerPresentation
import com.example.myapplication.ui.ai.AgentSourceUi
import com.example.myapplication.ui.ai.AiSourcePreviewDialog
import com.example.myapplication.ui.ai.AiAnswerStatus
import com.example.myapplication.ui.ai.AiComposer
import com.example.myapplication.ui.ai.AiMessageBubble
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiActionId
import com.example.myapplication.ui.components.UiInlineExpand
import com.example.myapplication.ui.knowledge.course.BundledCourseCatalog
import com.example.myapplication.ui.knowledge.course.CourseDraftDisplayHtml
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val ChapterQuestionLimit = 2_000
private val ChapterQaContentWidth = 680.dp

private data class ChapterQaMaterial(
    val sections: List<SourcePracticeSection>,
    val verify: suspend () -> Unit,
    val resolve: suspend (ChapterQaPassage) -> AgentSourceUi,
)

private data class ChapterQaBubble(
    val id: String,
    val user: Boolean,
    val text: String,
    val streaming: Boolean = false,
    val passages: List<ChapterQaPassage> = emptyList(),
    val notice: String? = null,
    val interrupted: Boolean = false,
)

/** The independent window leaves the caller and its reading or quiz state in place. */
@Composable
fun SourcePracticeChapterQa(scope: SourcePracticeScope, onClose: () -> Unit, selectedExcerpt: String? = null,
    onOpenPassage: ((ChapterQaPassage, AgentSourceUi) -> Unit)? = null, visible: Boolean = true) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as LearningApplication).container }
    val identity by container.sessionManager.identity.collectAsState(SessionIdentity(null, -1))
    key(scope, identity, selectedExcerpt) {
        ChapterQaWindow(container, scope, identity, selectedExcerpt, onClose, onOpenPassage, visible)
    }
}

@Composable
private fun ChapterQaWindow(
    container: AppContainer,
    scope: SourcePracticeScope,
    identity: SessionIdentity,
    selectedExcerpt: String?,
    onClose: () -> Unit,
    onOpenPassage: ((ChapterQaPassage, AgentSourceUi) -> Unit)?,
    visible: Boolean,
) {
    val context = LocalContext.current
    val jobs = rememberCoroutineScope()
    var material by remember { mutableStateOf<ChapterQaMaterial?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var draft by remember {
        mutableStateOf(selectedExcerpt?.let { "请结合本章原文解释这段选文：\n$it".take(ChapterQuestionLimit) }.orEmpty())
    }
    var bubbles by remember { mutableStateOf(emptyList<ChapterQaBubble>()) }
    var busy by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var run by remember { mutableStateOf<Job?>(null) }
    var ticket by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    var previewPassage by remember { mutableStateOf<ChapterQaPassage?>(null) }
    var previewSource by remember { mutableStateOf<AgentSourceUi?>(null) }
    var previewTicket by remember { mutableIntStateOf(0) }

    fun preview(passage: ChapterQaPassage) {
        val source = material ?: return
        val request = ++previewTicket
        previewPassage = passage
        previewSource = AgentSourceUi(passage.id.removePrefix("S").toIntOrNull() ?: 0,
            scope.documentId.orEmpty(), scope.title, passage.sectionId, passage.title, passage.text,
            unavailableReason = "正在核对原文…")
        jobs.launch {
            try {
                val resolved = withContext(Dispatchers.IO) { source.resolve(passage) }
                if (request == previewTicket && !closing) previewSource = if (onOpenPassage != null) resolved
                    else resolved.copy(unavailableReason = "当前入口无法跳转，仍可查看和复制原文。")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (request == previewTicket && !closing) previewSource = previewSource?.copy(
                    unavailableReason = failure.message ?: "原文暂时无法定位，请重试")
            }
        }
    }

    LaunchedEffect(Unit) {
        if (identity.epoch < 0) return@LaunchedEffect
        if (identity.userId == null) {
            loadError = "请先登录后打开章节问答"
            return@LaunchedEffect
        }
        try {
            material = withContext(Dispatchers.IO) {
                withTimeout(12_000) { loadChapterQaMaterial(container, context, scope, identity) }
            }
        } catch (_: TimeoutCancellationException) {
            loadError = "章节正文读取超时，请关闭后重新打开"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadError = failure.message ?: "当前章节正文暂时无法读取"
        }
    }

    fun invalidateRun() {
        ticket++
        run?.cancel()
        run = null
    }

    fun close() {
        if (closing) return
        closing = true
        invalidateRun()
        onClose()
    }

    fun stop() {
        if (!busy || closing) return
        invalidateRun()
        busy = false
        bubbles = bubbles.map { if (it.streaming) it.copy(streaming = false, notice = "已停止", interrupted = true) else it }
    }

    DisposableEffect(Unit) {
        onDispose { invalidateRun() }
    }

    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) list.animateScrollToItem(bubbles.lastIndex)
    }

    fun send() {
        val source = material ?: return
        val userId = identity.userId ?: return
        if (busy || closing || draft.isBlank()) return
        val question = draft.trim().take(ChapterQuestionLimit)
        val history = bubbles.filter { !it.streaming && it.notice == null }.map {
            if (it.user) ChatMessage.user(it.text) else ChatMessage.assistant(it.text)
        }
        val replyId = UUID.randomUUID().toString()
        val request = ++ticket
        bubbles = bubbles + ChapterQaBubble(UUID.randomUUID().toString(), true, question) +
            ChapterQaBubble(replyId, false, "", streaming = true)
        draft = ""
        busy = true

        run = jobs.launch {
            fun update(transform: (ChapterQaBubble) -> ChapterQaBubble) {
                if (request == ticket && !closing) {
                    bubbles = bubbles.map { if (it.id == replyId) transform(it) else it }
                }
            }
            try {
                val reply = withContext(Dispatchers.IO) {
                    container.chapterQuestionAnswerService.ask(
                        userId, source.sections, question, history, source.verify,
                        onPassages = { passages ->
                            withContext(Dispatchers.Main.immediate) { update { it.copy(passages = passages) } }
                        },
                        onDelta = { delta ->
                            withContext(Dispatchers.Main.immediate) { update { it.copy(text = it.text + delta) } }
                        },
                    )
                }
                update {
                    it.copy(
                        text = reply.answer,
                        streaming = false,
                        notice = if (reply.truncated) "回答已达长度限制，可以继续追问" else null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                update { it.copy(streaming = false, notice = failure.message ?: "回答失败，请重试", interrupted = true) }
            } finally {
                if (request == ticket) {
                    busy = false
                    run = null
                }
            }
        }
    }

    if (visible) Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize().testTag("chapter-qa-screen"), color = UiRoles.surface(UiSurfaceRole.Page)) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                ChapterQaHeading(
                    chapter = scope.title.ifBlank { "当前章节" },
                    fromReading = selectedExcerpt != null,
                    onClose = ::close,
                )

                if (bubbles.isEmpty()) {
                    ChapterQaEmptyState(material != null, loadError, Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f)
                            .align(Alignment.CenterHorizontally)
                            .widthIn(max = ChapterQaContentWidth)
                            .fillMaxWidth(),
                        state = list,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        items(bubbles, key = { it.id }) { bubble -> ChapterQaMessage(bubble, scope.title, scope.documentId.orEmpty(), ::preview) }
                    }
                }

                Surface(color = UiRoles.surface(UiSurfaceRole.Card)) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AiComposer(
                            draft = draft,
                            onDraftChange = { draft = it.take(ChapterQuestionLimit) },
                            canSend = material != null && draft.isNotBlank() && !closing,
                            onSend = ::send,
                            modifier = Modifier.widthIn(max = ChapterQaContentWidth)
                                .fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            enabled = material != null && !busy && !closing,
                            onStop = if (busy && !closing) ::stop else null,
                            placeholder = "询问当前章节…",
                            inputModifier = Modifier.testTag("chapter-qa-input"),
                            sendModifier = Modifier.testTag("chapter-qa-send"),
                            stopModifier = Modifier.testTag("chapter-qa-stop"),
                            actionPrefix = "chapter.qa",
                        )
                    }
                }
            }
        }
    }
    if (visible) previewSource?.let { source ->
        AiSourcePreviewDialog(source, onDismiss = { previewTicket++; previewSource = null; previewPassage = null },
            onOpenSource = {
                val passage = previewPassage
                val origin = material
                if (passage != null && origin != null && onOpenPassage != null && source.canNavigate) {
                    val request = ++previewTicket
                    previewSource = source.copy(unavailableReason = "正在核对原文…")
                    jobs.launch {
                        try {
                            val verified = withContext(Dispatchers.IO) { origin.resolve(passage) }
                            if (request == previewTicket && !closing) {
                                onOpenPassage(passage, verified)
                                previewSource = null
                                previewPassage = null
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            if (request == previewTicket && !closing) previewSource = source.copy(
                                unavailableReason = failure.message ?: "跳转失败，请重试")
                        }
                    }
                }
            })
    }
}

@Composable
private fun ChapterQaHeading(chapter: String, fromReading: Boolean, onClose: () -> Unit) {
    Surface(color = UiRoles.surface(UiSurfaceRole.Page)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier.widthIn(max = ChapterQaContentWidth)
                    .fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UiAction(
                    label = if (fromReading) "返回阅读" else "返回随堂测",
                    icon = UiIcons.Back,
                    onClick = onClose,
                    modifier = Modifier.testTag("chapter-qa-close"),
                    actionId = "chapter.qa.close",
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "章节 AI 问答",
                        modifier = Modifier.semantics { heading() },
                        style = UiRoles.text(UiRoles.pageTitle),
                    )
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            UiIcons.Books,
                            contentDescription = null,
                            modifier = Modifier.padding(top = 2.dp).size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            chapter,
                            modifier = Modifier.testTag("chapter-qa-scope"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ChapterQaEmptyState(ready: Boolean, error: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 380.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                error != null -> Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                !ready -> AiAnswerStatus("正在读取章节原文…", running = true)
                else -> {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                UiIcons.Books,
                                contentDescription = null,
                                modifier = Modifier.size(27.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Text("你想了解本章的什么？", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(
                        "可以请 AI 结合本章原文解释概念或比较知识点。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterQaMessage(bubble: ChapterQaBubble, documentTitle: String, documentId: String, onPreview: (ChapterQaPassage) -> Unit) {
    val messageId = "chapter-qa-${bubble.id}"
    if (bubble.user) {
        AiMessageBubble(messageId = messageId, content = bubble.text, isUser = true)
        return
    }
    val sources = remember(bubble.passages, documentTitle, documentId) { bubble.passages.map { passage ->
        AgentSourceUi(passage.id.removePrefix("S").toIntOrNull() ?: 0, documentId, documentTitle,
            passage.sectionId, passage.title, passage.text)
    } }
    val answer = AgentMessageUi(messageId, AgentMessageRole.ASSISTANT, bubble.text, streaming = bubble.streaming,
        sources = sources, toolSteps = if (bubble.passages.isEmpty()) emptyList() else listOf(
            AgentToolStepUi("retrieval", "chapter_retrieval", AgentToolStatus.SUCCEEDED,
                "召回 ${bubble.passages.size} 段", "检索", "本章原文",
                results = bubble.passages.map { AgentToolHitUi(documentId, it.title, it.text, it.sectionId) }),
        ))
    val showPreview: (AgentSourceUi) -> Unit = { source -> bubble.passages.singleOrNull { it.id == source.label }?.let(onPreview) }
    val presentation = rememberAgentAnswerPresentation(bubble.text, animate = !bubble.interrupted)
    Column(Modifier.fillMaxWidth().testTag("chapter-qa-answer"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val waiting = (bubble.streaming || presentation.revealing) && presentation.blocks.isNullOrEmpty()
        if (answer.toolSteps.isNotEmpty()) {
            AiExplorationCard(answer, waiting, "正在查找…", summaryText = "检索到 ${bubble.passages.size} 段章节原文", onPreview = showPreview)
        } else if (waiting) AiAnswerStatus("正在核对章节原文…", running = true, modifier = Modifier.testTag("chapter-qa-status"))
        if (!presentation.blocks.isNullOrEmpty()) {
            AgentPresentedMarkdown(messageId = messageId, presentation = presentation,
                citationLabels = sources.map { it.label }.toSet(), citationSources = sources.associateBy { it.label },
                onCitationClick = { id -> bubble.passages.singleOrNull { it.id == id }?.let(onPreview) })
        }
        bubble.notice?.let { notice ->
            Surface(shape = RoundedCornerShape(12.dp), color = UiRoles.surface(UiSurfaceRole.Block)) {
                Text(
                    notice,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!bubble.streaming && !presentation.revealing && bubble.passages.isNotEmpty()) {
            AiSourcesFooter(answer, showPreview)
        }
    }
}

private suspend fun loadChapterQaMaterial(container: AppContainer, context: android.content.Context,
    scope: SourcePracticeScope, identity: SessionIdentity): ChapterQaMaterial {
    suspend fun verifyIdentity() {
        if (container.sessionManager.identity.first() != identity) throw ChapterQaException("登录状态已变化，请重新打开章节问答")
    }
    verifyIdentity()
    val chapterKey = scope.bundledChapterKey
    if (chapterKey != null && scope.bundledBookKey != null) {
        val bytes = context.assets.open(BundledCourseCatalog.ASSET_PATH).use { it.readBytes() }
        val catalog = BundledCourseCatalog.fromBytes(bytes)
        val book = catalog.books.singleOrNull { it.key == scope.bundledBookKey }
        val chapter = book?.chapters?.singleOrNull { it.key == chapterKey }
            ?: throw ChapterQaException("课程章节已变化，请从课程页面重新生成随堂测")
        val body = ContentPackageArchive.read(bytes).resources.getValue(chapter.bodyPath)
        val revision = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        if (revision != scope.sourceRevision) throw ChapterQaException("课程正文已更新，历史题目仍保留，请从当前章节重新开始问答")
        val text = CourseDraftDisplayHtml.sourceText(body)
        verifyIdentity()
        return ChapterQaMaterial(listOf(SourcePracticeSection(chapterKey, chapter.title, text)), ::verifyIdentity) { passage ->
            verifyIdentity()
            if (passage.sectionId != chapterKey || passage.sourceSectionIndex != 0 || passage.sourceStart < 0 ||
                passage.text.isBlank() || passage.sourceStart > text.length - passage.text.length ||
                !text.regionMatches(passage.sourceStart, passage.text, 0, passage.text.length))
                throw ChapterQaException("来源摘录与当前章节不一致")
            AgentSourceUi(passage.id.removePrefix("S").toInt(), "bundled:${scope.bundledBookKey}",
                requireNotNull(book).title, chapterKey, chapter.title, passage.text,
                locatorJson = buildJsonObject {
                    put("kind", "bundled_chapter"); put("bookKey", scope.bundledBookKey)
                    put("chapterKey", chapterKey); put("revision", revision)
                    put("start", passage.sourceStart); put("quote", passage.text)
                }.toString())
        }
    }
    val user = identity.userId ?: throw ChapterQaException("请先登录")
    val bindings = scope.relatedSources.ifEmpty {
        listOf(SourcePracticeDocumentBinding(scope.documentId ?: throw ChapterQaException("当前题目没有可检索的章节来源"),
            scope.sourceRevision, scope.sectionIds, scope.documentOwnerUserId, scope.accessEpoch,
            scope.metadataRevision, scope.extractorVersion))
    }
    val loader = SourcePracticeLoader(container.roomDocumentAccess)
    val checks = mutableListOf<suspend () -> Unit>()
    val sections = mutableListOf<SourcePracticeSection>()
    val locations = mutableListOf<ReaderLocator>()
    var charCount = 0
    for (binding in bindings) {
        if (binding.sectionIds.isEmpty()) throw ChapterQaException("题目缺少章节范围，请从阅读页重新开始")
        val access = AccessContext(user, identity.epoch, "chapter-qa-${UUID.randomUUID()}")
        val snapshot = loader.open(access, binding.documentId)
        if (snapshot.contentRevision != binding.sourceRevision || snapshot.ownerUserId != binding.ownerUserId ||
            (binding.accessEpoch != null && snapshot.accessEpoch != binding.accessEpoch) ||
            (binding.metadataRevision != null && snapshot.metadataRevision != binding.metadataRevision) ||
            (binding.extractorVersion != null && snapshot.extractorVersion != binding.extractorVersion))
            throw ChapterQaException("章节原文已变化，请从阅读页重新生成随堂测后问答")
        checks += { loader.verifySnapshot(access, snapshot) }
        for (sectionId in binding.sectionIds.distinct()) {
            var cursor: SourcePracticeLoader.OriginalCursor? = null
            var windows = 0
            do {
                currentCoroutineContext().ensureActive()
                val page = loader.readOriginal(access, snapshot, sectionId, cursor)
                for ((index, section) in page.sections.withIndex()) {
                    if (section.sectionId != sectionId) throw ChapterQaException("检索范围与当前章节不一致")
                    val remaining = 512_000 - charCount
                    if (remaining <= 0) break
                    val text = sourcePracticeSlice(section.text, 0, remaining)
                    sections += section.copy(text = text)
                    locations += page.locators.getOrNull(index)
                        ?: throw ChapterQaException("章节来源缺少原文位置，请重新打开")
                    charCount += text.length
                }
                cursor = page.continuation
                windows++
            } while (cursor != null && charCount < 512_000 && windows < 32)
            if (charCount >= 512_000) break
        }
    }
    verifyIdentity()
    val verify: suspend () -> Unit = { verifyIdentity(); checks.forEach { it() }; verifyIdentity() }
    return ChapterQaMaterial(sections, verify) { passage ->
        verify()
        val section = sections.getOrNull(passage.sourceSectionIndex)
            ?: throw ChapterQaException("来源摘录已失效")
        val parent = locations.getOrNull(passage.sourceSectionIndex)
            ?: throw ChapterQaException("来源缺少定位信息")
        val locator = passage.locatorWithin(section, parent)
            ?: throw ChapterQaException("来源摘录与原文位置不一致")
        val access = AccessContext(user, identity.epoch, "chapter-source-${UUID.randomUUID()}")
        val verified = SourceVerifier(container.roomDocumentAccess).verifyLocator(
            access, locator.documentId, locator.contentRevision, locator, passage.text)
        if (verified !is VerificationOutcome.Verified) throw ChapterQaException("来源已变化或不可访问，历史摘录仍保留")
        verifyIdentity()
        AgentSourceUi(passage.id.removePrefix("S").toInt(), locator.documentId,
            verified.evidence.sourceTitleSnapshot, locator.sectionId, section.title, passage.text,
            locatorJson = ReaderLocatorCodec.encode(locator))
    }
}
