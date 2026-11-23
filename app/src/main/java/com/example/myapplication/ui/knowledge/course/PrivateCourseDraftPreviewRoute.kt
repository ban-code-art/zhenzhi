package com.example.myapplication.ui.knowledge.course

import android.content.Context
import android.graphics.Canvas
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myapplication.LearningApplication
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiAlertHost
import com.example.myapplication.ui.components.UiBackdrop
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.UiInlineExpand
import com.example.myapplication.ui.components.rememberAdaptiveWindowInfo
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import com.example.myapplication.data.reader.content.*
import com.example.myapplication.data.quiz.SourcePractice
import com.example.myapplication.data.quiz.SourcePracticeFactory
import com.example.myapplication.data.quiz.SourcePracticeScope
import com.example.myapplication.ui.knowledge.TrackCourseReadingActivity
import com.example.myapplication.ui.knowledge.quiz.SourcePracticeChapterQa
import com.example.myapplication.ui.knowledge.quiz.TrackedSourcePracticeScreen
import com.example.myapplication.ui.navigation.LocalNavigationPageActive
import com.example.myapplication.ui.navigation.UiPageStack
import com.example.myapplication.ui.knowledge.reader.SelectionActionSheet
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

private data class PrivateDraft(val archive: ContentPackageArchiveInput, val root: JsonObject,
    val chapters: List<JsonObject>, val zipSha: String, val manifestSha: String,
    val catalog: BundledCourseCatalog)
private fun draftSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
private fun readPrivateDraft(bytes: ByteArray): PrivateDraft {
    val archive = ContentPackageArchive.read(bytes)
    require(ContentPackageValidator().validate(archive.manifestBytes, archive.resources,
        ContentValidationMode.CANONICAL).canonicalValid == true) { "课程结构或正文身份校验失败" }
    val root = ContentPackageCodec.decode(archive.manifestBytes)
    val chapters = root.getValue("chapters").jsonArray.map { it.jsonObject }
    require(chapters.isNotEmpty() && chapters.all {
        it.getValue("review").jsonObject.getValue("status").jsonPrimitive.content == "DRAFT"
    }) { "此课程包暂不支持当前阅读入口" }
    val byKey = chapters.associateBy { it.getValue("chapterKey").jsonPrimitive.content }
    val ordered = root.getValue("books").jsonArray.map { it.jsonObject }
        .sortedBy { it.getValue("orderIndex").jsonPrimitive.int }
        .flatMap { book -> book.getValue("chapterKeys").jsonArray.map { byKey.getValue(it.jsonPrimitive.content) } }
    require(ordered.size == chapters.size && ordered.toSet().size == chapters.size)
    return PrivateDraft(archive, root, ordered, draftSha(bytes), draftSha(archive.manifestBytes),
        BundledCourseCatalog.fromManifest(root))
}

private fun readDraftBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        require(output.size().toLong() + count <= 32L * 1024 * 1024) { "课程 ZIP 不得超过 32MiB" }
        output.write(buffer, 0, count)
    }
}

private const val BUNDLED_COURSE_DRAFT_ASSET = "course-drafts/builtin-acupuncture-draft.zip"

/** Bundled learning material is read directly from the APK; it never replaces a private import. */
@Composable
fun BundledCourseDraftPreviewRoute(onBack: () -> Unit, initialBookKey: String? = null,
    onAskAi: ((SourcePracticeScope) -> Unit)? = null, initialChapterKey: String? = null,
    sourceQuote: String? = null, sourceRevision: String? = null) =
    CourseDraftPreviewRoute(BUNDLED_COURSE_DRAFT_ASSET, onBack, initialBookKey, onAskAi,
        initialChapterKey, sourceQuote, sourceRevision)

/** Private byte-preserving preview only. No course publication, quiz authority or study-progress writes. */
@Composable
fun PrivateCourseDraftPreviewRoute(onBack: () -> Unit, onAskAi: ((SourcePracticeScope) -> Unit)? = null) =
    CourseDraftPreviewRoute(null, onBack, onAskAi = onAskAi)

@Composable
private fun CourseDraftPreviewRoute(bundledAssetPath: String?, onBack: () -> Unit, initialBookKey: String? = null,
    onAskAi: ((SourcePracticeScope) -> Unit)? = null, initialChapterKey: String? = null,
    sourceQuote: String? = null, sourceRevision: String? = null) {
    val context = LocalContext.current
    val container = (context.applicationContext as LearningApplication).container
    val identity by container.sessionManager.identity.collectAsState(initial = null)
    val bundled = bundledAssetPath != null
    val draftOwner = if (bundled) null else identity
    val scope = rememberCoroutineScope()
    var draft by remember(draftOwner, bundledAssetPath) { mutableStateOf<PrivateDraft?>(null) }
    var error by remember(draftOwner, bundledAssetPath) { mutableStateOf<String?>(null) }
    var restoreError by remember(draftOwner, bundledAssetPath) { mutableStateOf<String?>(null) }
    var busy by remember(draftOwner, bundledAssetPath) { mutableStateOf(false) }
    var practice by remember(draftOwner, bundledAssetPath) { mutableStateOf<SourcePractice?>(null) }
    var practiceOpen by remember(draftOwner, bundledAssetPath) { mutableStateOf(false) }
    var practiceBusy by remember(draftOwner, bundledAssetPath) { mutableStateOf(false) }
    var practiceTicket by remember(draftOwner, bundledAssetPath) { mutableLongStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var showIdentityDetails by rememberSaveable(bundledAssetPath, draftOwner?.userId, draftOwner?.epoch, initialBookKey) {
        mutableStateOf(false)
    }
    var selectedChapterKey by rememberSaveable(bundledAssetPath, draftOwner?.userId, draftOwner?.epoch, initialBookKey, initialChapterKey) {
        mutableStateOf(initialChapterKey)
    }
    var lastVisitedChapterKey by rememberSaveable(bundledAssetPath, draftOwner?.userId, draftOwner?.epoch, initialBookKey) {
        mutableStateOf<String?>(null)
    }
    var query by rememberSaveable(bundledAssetPath, draftOwner?.userId, draftOwner?.epoch, initialBookKey) { mutableStateOf("") }
    var showObjectives by rememberSaveable(bundledAssetPath, draftOwner?.userId, draftOwner?.epoch, initialBookKey) {
        mutableStateOf(false)
    }
    val chapterScroll = rememberLazyListState()
    val bodyPositions = rememberSaveable(bundledAssetPath, draftOwner?.userId, draftOwner?.epoch, initialBookKey,
        saver = mapSaver<MutableMap<String, Int>>(
            save = { positions -> positions.mapValues { it.value } },
            restore = { positions -> positions.mapValues { (it.value as Number).toInt() }.toMutableMap() }
        )) { mutableMapOf<String, Int>() }
    var directoryAnchor by rememberSaveable(bundledAssetPath, draftOwner?.userId, draftOwner?.epoch, initialBookKey) {
        mutableStateOf<String?>(null)
    }
    var directoryIndex by rememberSaveable(bundledAssetPath, draftOwner?.userId, draftOwner?.epoch, initialBookKey) {
        mutableIntStateOf(0)
    }
    var directoryOffset by rememberSaveable(bundledAssetPath, draftOwner?.userId, draftOwner?.epoch, initialBookKey) {
        mutableIntStateOf(0)
    }
    var directoryRestored by remember(draftOwner, bundledAssetPath, initialBookKey) { mutableStateOf(false) }
    val privateFile = remember(identity, bundledAssetPath) { if (bundled) null else identity?.userId?.let {
        File(context.filesDir, "private-course-drafts/${draftSha(it.toString().toByteArray(Charsets.UTF_8))}/preview.zip")
    } }
    LaunchedEffect(draftOwner, bundledAssetPath, reload) {
        if (bundledAssetPath != null) {
            busy = true; restoreError = null
            try {
                draft = withContext(Dispatchers.IO) {
                    context.assets.open(bundledAssetPath).use { readPrivateDraft(readDraftBytes(it)) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { restoreError = "内置课程载入失败，请重新加载" }
            finally { busy = false }
            return@LaunchedEffect
        }
        val owner = identity ?: return@LaunchedEffect
        val file = privateFile ?: return@LaunchedEffect
        if (file.isFile) try {
            busy = true
            val restored = withContext(Dispatchers.IO) { require(file.length() <= 32L * 1024 * 1024); readPrivateDraft(file.readBytes()) }
            if (container.sessionManager.identity.first() == owner) draft = restored
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (container.sessionManager.identity.first() == owner) {
                restoreError = failure.message ?: "私人课程读取失败"
            }
        }
        finally { busy = false }
    }
    // Ticket survives Activity recreation and is independent from the current user composition.
    var pickerOwnerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickerOwnerEpoch by rememberSaveable { mutableStateOf<Long?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val ticketId = pickerOwnerId
        val ticketEpoch = pickerOwnerEpoch
        pickerOwnerId = null; pickerOwnerEpoch = null
        val owner = identity?.takeIf { it.userId == ticketId && it.epoch == ticketEpoch && ticketId != null }
        val file = if (owner != null) privateFile else null
        if (uri != null && !bundled && owner == null) error = "选择文件期间登录会话已变化，请重新选择"
        if (!bundled && uri != null && owner?.userId != null && file != null) scope.launch {
            busy = true; error = null; restoreError = null
            try {
                val loaded = withContext(Dispatchers.IO) {
                    check(container.sessionManager.identity.first() == owner) { "登录会话已变化" }
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use(::readDraftBytes)
                    val parsed = readPrivateDraft(bytes)
                    ensureActive()
                    check(container.sessionManager.identity.first() == owner) { "登录会话已变化" }
                    check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                    val temporary = File(file.parentFile, "preview-${java.util.UUID.randomUUID()}.tmp")
                    try {
                        temporary.outputStream().use { it.write(bytes) }
                        ensureActive()
                        check(container.sessionManager.identity.first() == owner) { "登录会话已变化" }
                        // Atomic replacement retains the preceding valid private preview on an IO failure.
                        android.system.Os.rename(temporary.absolutePath, file.absolutePath)
                    } finally { temporary.delete() }
                    parsed
                }
                if (container.sessionManager.identity.first() == owner) {
                    draft = loaded; selectedChapterKey = null; lastVisitedChapterKey = null; query = ""
                    bodyPositions.clear()
                    directoryAnchor = null; directoryIndex = 0; directoryOffset = 0; directoryRestored = false
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "私人课程导入失败" }
            finally { busy = false }
        }
    }
    fun selectChapter(chapter: BundledCourseChapter) {
        practiceTicket++; practiceBusy = false; error = null
        selectedChapterKey = chapter.key
        lastVisitedChapterKey = chapter.key
    }
    val back = {
        practiceTicket++; practiceBusy = false
        when {
            practiceOpen -> practiceOpen = false
            selectedChapterKey != null && selectedChapterKey == initialChapterKey -> onBack()
            selectedChapterKey != null -> selectedChapterKey = null
            else -> onBack()
        }
    }

    val current = draft
    val selectedBook = current?.catalog?.books?.singleOrNull { it.key == initialBookKey }
    val scopedBooks = if (initialBookKey == null) current?.catalog?.books.orEmpty() else listOfNotNull(selectedBook)
    val scopedChapters = scopedBooks.flatMap { it.chapters }
    val activeChapter = scopedChapters.singleOrNull { it.key == selectedChapterKey }
    val lastVisited = scopedChapters.singleOrNull { it.key == lastVisitedChapterKey }
    val bookMissing = current != null && initialBookKey != null && selectedBook == null
    val adaptive = rememberAdaptiveWindowInfo()
    val entries = remember(current?.zipSha, initialBookKey, query) {
        draftDirectoryEntries(scopedBooks, query, showBookHeadings = initialBookKey == null)
    }
    val matchedCount = entries.count { it.chapter != null }
    LaunchedEffect(current?.zipSha, scopedChapters.map { it.key }) {
        if (current != null) {
            if (selectedChapterKey != null && activeChapter == null) {
                practiceOpen = false
                selectedChapterKey = null
            }
            if (lastVisitedChapterKey != null && lastVisited == null) lastVisitedChapterKey = null
        }
    }
    LaunchedEffect(current?.zipSha, selectedChapterKey, entries.map { it.stableKey }) {
        if (current != null && !bookMissing && selectedChapterKey == null && !directoryRestored) {
            val anchorIndex = directoryAnchor?.let { anchor ->
                entries.indexOfFirst { it.stableKey == anchor }.takeIf { it >= 0 }
            }
            val index = if (directoryAnchor == null) directoryIndex.coerceIn(0, entries.lastIndex) else anchorIndex ?: 0
            val offset = if (directoryAnchor != null && anchorIndex == null) 0 else directoryOffset
            chapterScroll.scrollToItem(index, offset)
            directoryRestored = true
        }
    }
    LaunchedEffect(chapterScroll, current?.zipSha, selectedChapterKey, entries.map { it.stableKey }) {
        if (current != null && !bookMissing && selectedChapterKey == null) {
            snapshotFlow { chapterScroll.firstVisibleItemIndex to chapterScroll.firstVisibleItemScrollOffset }.collect { (index, offset) ->
                if (directoryRestored) {
                    directoryAnchor = entries.getOrNull(index)?.stableKey
                    directoryIndex = index
                    directoryOffset = offset
                }
            }
        }
    }

    val importDraft: () -> Unit = {
        val owner = identity
        if (!bundled && owner?.userId != null && pickerOwnerId == null) {
            pickerOwnerId = owner.userId; pickerOwnerEpoch = owner.epoch
            try { picker.launch(arrayOf("application/zip", "application/octet-stream")) }
            catch (failure: Exception) {
                pickerOwnerId = null; pickerOwnerEpoch = null
                error = failure.message ?: "无法打开文件选择器"
            }
        }
    }
    val openPractice: () -> Unit = {
        if (bundled && current != null && activeChapter != null && !practiceBusy && activeChapter.questionCount > 0) {
            val targetKey = activeChapter.key
            val body = current.archive.resources.getValue(activeChapter.bodyPath)
            val ticket = ++practiceTicket
            practiceBusy = true; error = null
            scope.launch {
                try {
                    val prepared = withContext(Dispatchers.Default) {
                        SourcePracticeFactory.fromCourseDraft(activeChapter.title, current.root, targetKey,
                            draftSha(body), CourseDraftDisplayHtml.sourceText(body))
                    }
                    if (ticket == practiceTicket && draft === current && selectedChapterKey == targetKey) {
                        practice = prepared
                        practiceOpen = true
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (ticket == practiceTicket && draft === current && selectedChapterKey == targetKey) {
                        error = "本章练习载入失败，请重试"
                    }
                } finally { if (ticket == practiceTicket) practiceBusy = false }
            }
        }
    }
    val page = listOfNotNull(selectedChapterKey, practice?.id?.takeIf { practiceOpen })
    UiPageStack(page) { visiblePage ->
        val pageActive = LocalNavigationPageActive.current
        val requestBack = coursePageBack(back)
        val chapter = scopedChapters.singleOrNull { it.key == visiblePage.firstOrNull() }
        val selectedIndex = if (chapter == null) -1 else scopedChapters.indexOf(chapter)
        if (visiblePage.size == 2) {
            val prepared = practice?.takeIf { it.id == visiblePage.last() }
            val book = chapter?.let { target -> scopedBooks.singleOrNull { target in it.chapters } }
            if (bundled && chapter != null && book != null && prepared != null) {
                TrackedSourcePracticeScreen(prepared, SourcePracticeScope(
                    bundledBookKey = book.key, bundledChapterKey = chapter.key,
                    title = chapter.title, sourceRevision = prepared.sourceRevision),
                    onBack = requestBack, onAskAi = onAskAi)
            } else if (pageActive) LaunchedEffect(visiblePage) {
                practiceOpen = false
                error = "本章来源已变化，请重新打开练习"
            }
            return@UiPageStack
        }

        val backdrop = rememberUiBackdrop()
        val density = LocalDensity.current
        var topBarPx by remember { mutableIntStateOf(0) }
        var bottomBarPx by remember { mutableIntStateOf(0) }
        val topBarDp = with(density) { topBarPx.toDp() }
        val bottomBarDp = if (chapter != null) with(density) { bottomBarPx.toDp() } else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(Modifier.fillMaxSize().background(UiRoles.surface(UiSurfaceRole.Page))) {
            UiGlassBackdrop(
                backdrop = backdrop,
                modifier = Modifier.fillMaxSize(),
                captureEnabled = false,
            ) {
                Column(
                    Modifier.align(Alignment.TopCenter).widthIn(max = adaptive.contentMaxWidth).fillMaxSize()
                        .padding(top = topBarDp, bottom = bottomBarDp),
                ) {
                    (error ?: restoreError)?.let { message ->
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CourseInlineNotice(message, Modifier.testTag("draft-preview-error"), error = true)
                            if (bundled && restoreError != null) UiAction(
                                label = "重新载入",
                                onClick = { reload++ },
                                enabled = !busy,
                                actionId = "draftPreview.reload",
                                modifier = Modifier.testTag("draft-preview-reload"),
                            )
                        }
                    }
                    when {
                        current == null -> {
                            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (busy) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("draft-preview-loading"))
                                    Text("正在载入教材…", style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                        color = UiRoles.foreground)
                                } else if (bundled && restoreError == null) {
                                    CourseInlineNotice("教材尚未载入，请重新载入。")
                                    UiAction(
                                        label = "重新载入",
                                        onClick = { reload++ },
                                        actionId = "draftPreview.reload",
                                        modifier = Modifier.testTag("draft-preview-reload"),
                                    )
                                }
                                if (!bundled) {
                                    Text("导入课程后，即可按章节阅读原文。",
                                        style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                        color = UiRoles.foreground)
                                    DraftImportAction(identity?.userId != null, !busy && pickerOwnerId == null, importDraft)
                                }
                            }
                        }
                        bookMissing -> {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                CourseInlineNotice("这本教材暂不可用，请返回课程列表。", Modifier.testTag("draft-preview-book-missing"), error = true)
                                UiAction(
                                    label = "返回课程列表",
                                    onClick = requestBack,
                                    actionId = "draftPreview.backToList",
                                )
                            }
                        }
                        chapter == null -> {
                            LazyColumn(state = chapterScroll,
                                modifier = Modifier.weight(1f).fillMaxWidth().testTag("draft-preview-chapters"),
                                contentPadding = PaddingValues(horizontal = if (adaptive.widthDp < 360) 16.dp else 20.dp, vertical = 12.dp)) {
                                items(entries, key = { it.stableKey }) { entry ->
                                    when (entry.stableKey) {
                                        "overview" -> {
                                            Surface(shape = RoundedCornerShape(16.dp), color = UiRoles.surface(UiSurfaceRole.Card)) {
                                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    Text(if (selectedBook != null) "${selectedBook.chapters.size} 章 · ${selectedBook.questionCount} 道随堂练习"
                                                        else "${current.catalog.books.size} 本教材 · ${current.catalog.chapterCount} 章 · ${current.catalog.questionCount} 道练习",
                                                        modifier = Modifier.testTag(if (selectedBook != null)
                                                            "draft-preview-book-${selectedBook.key}" else "draft-preview-overview"),
                                                        style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                                        fontWeight = FontWeight.Medium,
                                                        color = UiRoles.foreground)
                                                    selectedBook?.description?.takeIf(String::isNotBlank)?.let {
                                                        Text(it, style = UiRoles.text(MaterialTheme.typography.bodySmall), maxLines = 3,
                                                            overflow = TextOverflow.Ellipsis, color = UiRoles.color(UiColorRole.OnSurfaceVariant))
                                                    }
                                                    lastVisited?.let {
                                                        Text("本次读到：${it.title}", style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                                            color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                                                            modifier = Modifier.testTag("draft-preview-last-visited"))
                                                    }
                                                    val start = lastVisited ?: scopedChapters.firstOrNull()
                                                    if (start != null) UiAction(
                                                        label = if (lastVisited != null) "继续阅读" else "开始阅读",
                                                        onClick = { selectChapter(start) },
                                                        filled = true,
                                                        actionId = "draftPreview.start",
                                                        modifier = Modifier.heightIn(min = 48.dp).testTag("draft-preview-start"),
                                                    )
                                                    if (selectedBook != null && selectedBook.objectives.isNotEmpty()) {
                                                        UiInlineExpand(
                                                            ownerKey = selectedBook.key,
                                                            expanded = showObjectives,
                                                            onExpandedChange = { showObjectives = it },
                                                            contentAvailable = selectedBook.objectives.isNotEmpty(),
                                                            trigger = { mod, _, enabled, onToggle ->
                                                                UiAction(
                                                                    label = if (showObjectives) "收起学习目标" else "查看学习目标",
                                                                    onClick = onToggle,
                                                                    enabled = enabled,
                                                                    modifier = mod.testTag("draft-preview-objectives"),
                                                                    actionId = "draftPreview.objectives",
                                                                )
                                                            },
                                                        ) {
                                                            Column(
                                                                Modifier.fillMaxWidth().padding(12.dp),
                                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                                            ) {
                                                                selectedBook.objectives.forEachIndexed { index, objective ->
                                                                    Text(
                                                                        "${index + 1}. $objective",
                                                                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                                                        color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                    if (!bundled) DraftImportAction(identity?.userId != null,
                                                        !busy && pickerOwnerId == null, importDraft)
                                                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("draft-preview-loading"))
                                                }
                                            }
                                        }
                                        "search" -> CourseSearchField(query, { query = it }, "draft-preview-search",
                                            placeholder = "搜索章节或学习目标")
                                        "heading" -> CourseSectionHeading(if (query.isBlank()) "章节目录" else "搜索结果",
                                            Modifier.padding(top = 4.dp), "$matchedCount 章")
                                        "empty" -> Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            CourseInlineNotice("未找到相关章节，试试章节名或学习目标关键词。",
                                                Modifier.testTag("draft-preview-empty"))
                                            UiAction(
                                                label = "查看全部章节",
                                                onClick = { query = "" },
                                                actionId = "draftPreview.clearSearch",
                                            )
                                        }
                                        else -> {
                                            val book = entry.book
                                            val item = entry.chapter
                                            if (item == null && book != null) CourseSectionHeading(book.title,
                                                Modifier.testTag("draft-preview-book-${book.key}").padding(top = 8.dp),
                                                "${book.chapters.size} 章 · ${book.questionCount} 题")
                                            else if (item != null && book != null) {
                                                CourseChapterRow(book.chapters.indexOf(item) + 1, item.title, { selectChapter(item) },
                                                    modifier = Modifier.testTag("draft-preview-chapter-${item.archiveIndex}"),
                                                    highlighted = item.key == lastVisitedChapterKey) {
                                                    val objectiveSummary = item.objectives
                                                        .take(if (LocalDensity.current.fontScale >= 1.5f) 1 else 2)
                                                        .joinToString("；")
                                                    objectiveSummary.takeIf(String::isNotBlank)?.let {
                                                        Text(it, style = UiRoles.text(MaterialTheme.typography.bodySmall), maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis, color = UiRoles.color(UiColorRole.OnSurfaceVariant))
                                                    }
                                                    Text(if (bundled) "${item.questionCount} 道随堂练习" else "课程原文",
                                                        style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                                        color = UiRoles.color(UiColorRole.OnSurfaceVariant))
                                                }
                                                HorizontalDivider(color = UiRoles.color(UiColorRole.OutlineVariant))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> key(current.zipSha, chapter.bodyPath, identity) {
                            CourseDraftBody(current, chapter, bodyPositions, Modifier.weight(1f).fillMaxWidth(),
                                readingAllowed = pageActive && !showIdentityDetails && !busy && !practiceBusy,
                                bundledBookKey = if (bundled) scopedBooks.single { chapter in it.chapters }.key else null,
                                ownerUserId = identity?.userId,
                                initialSourceQuote = sourceQuote?.takeIf { chapter.key == initialChapterKey },
                                expectedSourceRevision = sourceRevision)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().onSizeChanged { topBarPx = it.height }) {
                CourseScreenHeader(
                    title = chapter?.title ?: selectedBook?.title ?: if (bundled) "内置课程章节" else "私人课程",
                    onBack = requestBack,
                    subtitle = if (chapter == null) "${scopedChapters.size} 章 · 全文教材"
                        else "第 ${selectedIndex + 1} / ${scopedChapters.size} 章",
                    backTag = "draft-preview-back",
                    titleTag = if (chapter != null) "draft-preview-title"
                        else if (selectedBook != null) "draft-preview-book-title" else "draft-preview-directory-title",
                    subtitleTag = "draft-preview-status",
                    backdrop = backdrop,
                ) {
                    if (current != null) {
                        UiAction(
                            label = "内容信息",
                            icon = UiIcons.Info,
                            onClick = { showIdentityDetails = true },
                            backdrop = backdrop,
                            actionId = "draftPreview.details",
                            modifier = Modifier.testTag("draft-preview-details"),
                        )
                    }
                }
            }
            if (chapter != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { bottomBarPx = it.height },
                ) {
                    DraftReaderControls(
                        previous = { scopedChapters.getOrNull(selectedIndex - 1)?.let(::selectChapter) },
                        next = { scopedChapters.getOrNull(selectedIndex + 1)?.let(::selectChapter) },
                        hasPrevious = selectedIndex > 0,
                        hasNext = selectedIndex >= 0 && selectedIndex < scopedChapters.lastIndex,
                        index = selectedIndex + 1,
                        total = scopedChapters.size,
                        questionCount = if (bundled) chapter.questionCount else null,
                        practiceBusy = practiceBusy,
                        openPractice = openPractice,
                        backdrop = backdrop,
                    )
                }
            }
        }
        if (pageActive && showIdentityDetails && current != null) DraftContentDetails(current, selectedBook, chapter,
            onDismiss = { showIdentityDetails = false })
    }
}

private data class DraftDirectoryEntry(
    val stableKey: String,
    val book: BundledCourseBook? = null,
    val chapter: BundledCourseChapter? = null,
)

/** The same keys drive layout and the saved return anchor, including overview and book headings. */
private fun draftDirectoryEntries(books: List<BundledCourseBook>, query: String,
    showBookHeadings: Boolean): List<DraftDirectoryEntry> = buildList {
    add(DraftDirectoryEntry("overview"))
    add(DraftDirectoryEntry("search"))
    add(DraftDirectoryEntry("heading"))
    val term = query.trim()
    var matchCount = 0
    books.forEach { book ->
        val matches = book.chapters.filter { chapter ->
            term.isEmpty() || book.title.contains(term, ignoreCase = true) ||
                chapter.title.contains(term, ignoreCase = true) || chapter.summary.contains(term, ignoreCase = true) ||
                chapter.objectives.any { it.contains(term, ignoreCase = true) }
        }
        if (matches.isNotEmpty()) {
            if (showBookHeadings) add(DraftDirectoryEntry("book:${book.key}", book))
            matches.forEach { chapter -> add(DraftDirectoryEntry("chapter:${chapter.key}", book, chapter)) }
            matchCount += matches.size
        }
    }
    if (matchCount == 0) add(DraftDirectoryEntry("empty"))
}

@Composable
private fun DraftImportAction(loggedIn: Boolean, enabled: Boolean, onImport: () -> Unit) {
    if (!loggedIn) CourseInlineNotice("登录后可保存和恢复私人课程。")
    else UiAction(
        label = "选择课程 ZIP",
        onClick = onImport,
        enabled = enabled,
        actionId = "draftPreview.import",
        modifier = Modifier.heightIn(min = 48.dp).testTag("draft-preview-import"),
    )
}

@Composable
private fun DraftReaderControls(previous: () -> Unit, next: () -> Unit, hasPrevious: Boolean, hasNext: Boolean,
    index: Int, total: Int, questionCount: Int?, practiceBusy: Boolean, openPractice: () -> Unit,
    backdrop: UiBackdrop? = null) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val chromeBg = UiRoles.surface(UiSurfaceRole.Page)
    Box(
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(listOf(Color.Transparent, chromeBg.copy(alpha = .76f), chromeBg.copy(alpha = .94f))),
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
            val stacked = largeText || maxWidth < 336.dp
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (stacked && questionCount != null) {
                    UiAction(
                        label = if (practiceBusy) "正在打开练习…" else "本章自测 · $questionCount 题",
                        onClick = openPractice,
                        enabled = questionCount > 0 && !practiceBusy,
                        filled = true,
                        backdrop = backdrop,
                        actionId = "draftPreview.practice",
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("draft-preview-practice"),
                        visualModifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (stacked) {
                        UiAction(
                            label = "上一章",
                            onClick = previous,
                            enabled = hasPrevious,
                            backdrop = backdrop,
                            actionId = "draftPreview.previous",
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("draft-preview-previous"),
                            visualModifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        UiAction(
                            label = "上一章",
                            icon = UiIcons.Back,
                            onClick = previous,
                            enabled = hasPrevious,
                            backdrop = backdrop,
                            actionId = "draftPreview.previous",
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("draft-preview-previous"),
                        )
                    }
                    if (!stacked && questionCount != null) {
                        UiAction(
                            label = if (practiceBusy) "正在打开练习…" else "本章自测 · $questionCount 题",
                            onClick = openPractice,
                            enabled = questionCount > 0 && !practiceBusy,
                            filled = true,
                            backdrop = backdrop,
                            actionId = "draftPreview.practice",
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("draft-preview-practice"),
                            visualModifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            "$index / $total",
                            Modifier.then(if (stacked) Modifier else Modifier.weight(1f)),
                            style = UiRoles.text(MaterialTheme.typography.bodySmall),
                            color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (stacked) {
                        UiAction(
                            label = "下一章",
                            onClick = next,
                            enabled = hasNext,
                            backdrop = backdrop,
                            actionId = "draftPreview.next",
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("draft-preview-next"),
                            visualModifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        UiAction(
                            label = "下一章",
                            icon = UiIcons.Chevron,
                            onClick = next,
                            enabled = hasNext,
                            backdrop = backdrop,
                            actionId = "draftPreview.next",
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("draft-preview-next"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftContentDetails(current: PrivateDraft, book: BundledCourseBook?, chapter: BundledCourseChapter?,
    onDismiss: () -> Unit) {
    val displayBook = book ?: chapter?.let { selected ->
        current.catalog.books.singleOrNull { item -> item.chapters.any { it.key == selected.key } }
    }
    val sourceKeys = remember(current.zipSha, book?.key, chapter?.key) {
        val keys = chapter?.let { setOf(it.key) } ?: book?.chapters?.map { it.key }?.toSet()
        current.chapters.filter { keys == null || it.getValue("chapterKey").jsonPrimitive.content in keys }
            .flatMap { item ->
                (item["knowledgePoints"] as? JsonArray).orEmpty().flatMap { point ->
                    (point.jsonObject["sourceKeys"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                }
            }.toSet()
    }
    val sources = remember(current.zipSha, sourceKeys) {
        (current.root["sources"] as? JsonArray).orEmpty().map { it.jsonObject }
            .filter { sourceKeys.isEmpty() || it["sourceKey"]?.jsonPrimitive?.content in sourceKeys }
    }
    UiAlertHost(
        title = "内容信息",
        ownerKey = current.zipSha to (book?.key to chapter?.key),
        onDismissRequest = { _, _ -> onDismiss() },
        modifier = Modifier.testTag("draft-preview-details-dialog"),
    ) { interaction, _, _ ->
        Text(
            "内容信息",
            modifier = Modifier.fillMaxWidth().semantics { heading() },
            style = UiRoles.text(MaterialTheme.typography.titleLarge),
            color = UiRoles.foreground,
            textAlign = TextAlign.Center,
        )
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)
                .heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                displayBook?.title ?: "课程教材",
                Modifier.testTag("draft-preview-details-book"),
                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                color = UiRoles.foreground,
            )
            chapter?.let {
                Text(
                    it.title,
                    Modifier.testTag("draft-preview-details-chapter"),
                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                    color = UiRoles.foreground,
                )
            }
            if (sources.isNotEmpty()) {
                Text(
                    "参考来源",
                    Modifier.testTag("draft-preview-details-references"),
                    style = UiRoles.text(MaterialTheme.typography.titleSmall),
                    fontWeight = FontWeight.SemiBold,
                    color = UiRoles.foreground,
                )
                sources.forEach { source ->
                    val authors = (source["authors"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.joinToString("、")
                    val publication = listOfNotNull(authors.takeIf(String::isNotBlank),
                        source["publisher"]?.jsonPrimitive?.contentOrNull,
                        source["year"]?.jsonPrimitive?.contentOrNull,
                        source["edition"]?.jsonPrimitive?.contentOrNull).filter(String::isNotBlank).joinToString(" · ")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            source.getValue("title").jsonPrimitive.content,
                            style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                            color = UiRoles.foreground,
                        )
                        if (publication.isNotBlank()) Text(
                            publication,
                            style = UiRoles.text(MaterialTheme.typography.bodySmall),
                            color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            UiAction(
                label = "关闭",
                onClick = onDismiss,
                inheritedPane = true,
                interactionSource = interaction,
                animatePress = false,
                foreground = UiRoles.accent,
                actionId = "draftPreview.details.close",
                modifier = Modifier.testTag("draft-preview-details-close"),
            )
        }
    }
}

private class DraftWebView(context: Context) : WebView(context) {
    var positionReady = false
    var released = false
    var visualRequest = 0L
    var awaitingBodyDraw = false
    var onBodyDrawn: (() -> Unit)? = null
    var selectionGeneration = 0
        private set
    var onTextSelection: ((CourseDraftSelection?) -> Unit)? = null
    var clearSelectionToken = 0
    private var selectionInstalled = false
    private var highlights = emptyList<CourseDraftHighlight>()
    private var lastRevealKey: String? = null
    private var pendingReveal: (() -> Unit)? = null

    fun revealSource(quote: String, requestKey: String, onResult: (Boolean) -> Unit) {
        if (lastRevealKey == requestKey) return
        lastRevealKey = requestKey
        pendingReveal = {
            val generation = selectionGeneration
            evaluateJavascript("!!(window.courseDraftSelection && window.courseDraftSelection.reveal(${JsonPrimitive(quote)}));") { result ->
                if (!released && selectionGeneration == generation && lastRevealKey == requestKey) onResult(result == "true")
            }
        }
        if (selectionInstalled && positionReady) { pendingReveal?.invoke(); pendingReveal = null }
    }

    fun resetSelectionPage() {
        selectionInstalled = false
        selectionGeneration++
        onTextSelection?.invoke(null)
    }

    fun installSelection(script: String) {
        if (selectionInstalled) return
        selectionInstalled = true
        val generation = selectionGeneration
        evaluateJavascript("$script($generation);") {
            if (!released && selectionGeneration == generation) {
                if (highlights.isNotEmpty()) decorateHighlights()
                pendingReveal?.invoke()
                pendingReveal = null
            }
        }
    }

    fun clearTextSelection() {
        if (selectionInstalled) evaluateJavascript("window.courseDraftSelection && window.courseDraftSelection.clear();", null)
    }

    fun updateHighlights(value: List<CourseDraftHighlight>) {
        if (highlights == value) return
        highlights = value
        if (selectionInstalled) decorateHighlights()
    }

    private fun decorateHighlights() {
        evaluateJavascript("window.courseDraftSelection && window.courseDraftSelection.decorate(${encodeCourseDraftHighlights(highlights)});", null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (awaitingBodyDraw && positionReady && !released && isShown && width > 0 && height > 0) {
            awaitingBodyDraw = false
            post { if (!released && positionReady && isShown) onBodyDrawn?.invoke() }
        }
    }
}

private class DraftSelectionBridge(private val web: DraftWebView) {
    @JavascriptInterface
    fun report(generation: Int, start: Int, end: Int, quote: String) {
        web.post {
            if (!web.released && web.positionReady && web.selectionGeneration == generation) {
                val capture = if (start >= 0 && end > start && quote.isNotBlank() && quote.length <= 8192)
                    CourseDraftSelection(CourseDraftRange(start, end), quote) else null
                web.onTextSelection?.invoke(capture)
            }
        }
    }
}

/** The source archive stays untouched and scroll tracking starts after restore. */
@Composable
private fun CourseDraftBody(current: PrivateDraft, chapter: BundledCourseChapter,
    bodyPositions: MutableMap<String, Int>, modifier: Modifier = Modifier, readingAllowed: Boolean = true,
    bundledBookKey: String? = null, ownerUserId: Long? = null,
    initialSourceQuote: String? = null, expectedSourceRevision: String? = null) {
    val context = LocalContext.current
    val body = current.archive.resources.getValue(chapter.bodyPath)
    val path = chapter.bodyPath
    val positionKey = "${current.zipSha}:$path"
    val revision = remember(body) { draftSha(body) }
    val activityKey = "course:${chapter.key}:$revision"
    val chapterQa = bundledBookKey?.let {
        SourcePracticeScope(bundledBookKey = it, bundledChapterKey = chapter.key, title = chapter.title, sourceRevision = revision)
    }
    val highlightStore = remember(context, chapterQa, ownerUserId) {
        chapterQa?.let { CourseDraftHighlightStore(context, ownerUserId, chapter.key, revision) }
    }
    val selectionScript = remember(context, bundledBookKey != null) {
        if (bundledBookKey != null) context.assets.open("course-drafts/course-draft-selection.js")
            .bufferedReader().use { it.readText() } else null
    }
    var highlights by remember(highlightStore) { mutableStateOf(highlightStore?.load().orEmpty()) }
    var liveSelection by remember(positionKey) { mutableStateOf<CourseDraftSelection?>(null) }
    var actionSelection by remember(positionKey) { mutableStateOf<CourseDraftSelection?>(null) }
    var selectedExcerpt by remember(positionKey) { mutableStateOf<String?>(null) }
    var qaVisible by remember(positionKey) { mutableStateOf(true) }
    var sourceRequest by remember(positionKey, initialSourceQuote) {
        mutableStateOf(initialSourceQuote?.takeIf { expectedSourceRevision == revision }?.let { UUID.randomUUID().toString() to it })
    }
    var sourceError by remember(positionKey, initialSourceQuote) {
        mutableStateOf(if (initialSourceQuote != null && expectedSourceRevision != revision) "原文已更新，无法按历史引用定位" else null)
    }
    var clearSelectionToken by remember(positionKey) { mutableIntStateOf(0) }
    var bodyDrawn by remember(positionKey) { mutableStateOf(false) }
    TrackCourseReadingActivity(activityKey, readingReady = bodyDrawn && readingAllowed && actionSelection == null && (selectedExcerpt == null || !qaVisible))
    fun clearSelection() {
        liveSelection = null
        actionSelection = null
        clearSelectionToken++
    }
    BackHandler(enabled = readingAllowed && liveSelection != null && actionSelection == null, onBack = ::clearSelection)
    val colors = MaterialTheme.colorScheme
    val background = colors.background.toArgb()
    val foreground = colors.onBackground.toArgb()
    val secondary = colors.onSurfaceVariant.toArgb()
    val accent = colors.primary.toArgb()
    val onAccent = colors.onPrimary.toArgb()
    val surface = colors.surfaceContainer.toArgb()
    val textZoom = (LocalDensity.current.fontScale * 100).roundToInt().coerceIn(50, 300)
    val rendered by produceState<String?>(null, background, foreground, secondary, accent, surface, onAccent) {
        value = withContext(Dispatchers.Default) {
            CourseDraftDisplayHtml.render(body, background, foreground, secondary, accent, surface, onAccent,
                bundledSelection = selectionScript != null)
        }
    }
    val html = rendered
    if (html == null) Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.testTag("draft-preview-body-loading"))
    }
    else Box(modifier) {
        AndroidView(modifier = Modifier.fillMaxSize().testTag("draft-preview-body"), factory = { ctx ->
            DraftWebView(ctx).apply {
                onBodyDrawn = { bodyDrawn = true }
                settings.javaScriptEnabled = selectionScript != null
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.domStorageEnabled = false
                if (selectionScript != null) addJavascriptInterface(DraftSelectionBridge(this), "CourseDraftSelectionBridge")
                settings.textZoom = textZoom
                setBackgroundColor(background)
                setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    if (positionReady && !released) bodyPositions[positionKey] = scrollY.coerceAtLeast(0)
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val displayed = view as? DraftWebView ?: return
                        val generation = displayed.selectionGeneration
                        displayed.post {
                            if (displayed.isAttachedToWindow && !displayed.released &&
                                generation == displayed.selectionGeneration) {
                                displayed.scrollTo(0, bodyPositions[positionKey] ?: 0)
                                displayed.positionReady = true
                                selectionScript?.let(displayed::installSelection)
                                val request = ++displayed.visualRequest
                                val currentMarkup = displayed.tag
                                displayed.postVisualStateCallback(request, object : WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) {
                                        if (requestId == displayed.visualRequest && displayed.positionReady &&
                                            !displayed.released && displayed.tag == currentMarkup) {
                                            displayed.awaitingBodyDraw = true
                                            displayed.invalidate()
                                        }
                                    }
                                })
                            }
                        }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val uri = request?.url
                        // loadDataWithBaseURL supplies the app-owned inline main document as data:.
                        // Every other request is intercepted and can only read the current archive.
                        if (request?.isForMainFrame == true && uri?.scheme == "data") return null
                        val resourcePath = uri?.path?.removePrefix("/")
                        val bytes = if (uri?.scheme == "https" && uri.host == "private-draft.invalid")
                            current.archive.resources[resourcePath] else null
                        val mime = when (resourcePath?.substringAfterLast('.')?.lowercase()) {
                            "css" -> "text/css"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
                            "svg" -> "image/svg+xml"; "woff2" -> "font/woff2"; else -> "application/octet-stream"
                        }
                        return WebResourceResponse(mime, null, ByteArrayInputStream(bytes ?: byteArrayOf()))
                    }
                }
                tag = html
                loadDataWithBaseURL("https://private-draft.invalid/" + path.substringBeforeLast('/', "") + "/",
                    html, "text/html", "UTF-8", null)
            }
        }, update = { web ->
            web.settings.textZoom = textZoom
            web.onTextSelection = { liveSelection = it }
            if (web.tag != html) {
                if (web.positionReady) bodyPositions[positionKey] = web.scrollY.coerceAtLeast(0)
                web.positionReady = false
                actionSelection = null
                web.resetSelectionPage()
                bodyDrawn = false; web.awaitingBodyDraw = false; web.visualRequest++
                web.tag = html; web.setBackgroundColor(background)
                web.loadDataWithBaseURL("https://private-draft.invalid/" + path.substringBeforeLast('/', "") + "/",
                    html, "text/html", "UTF-8", null)
            }
            if (web.clearSelectionToken != clearSelectionToken) {
                web.clearSelectionToken = clearSelectionToken
                web.clearTextSelection()
            }
            web.updateHighlights(highlights)
            sourceRequest?.let { (requestKey, quote) ->
                web.revealSource(quote, requestKey) { found ->
                    sourceError = if (found) null else "未能唯一定位这段原文，摘录和问答仍保留。"
                }
            }
        }, onRelease = { web ->
            if (web.positionReady) bodyPositions[positionKey] = web.scrollY.coerceAtLeast(0)
            web.released = true; web.positionReady = false
            web.onTextSelection = null
            if (selectionScript != null) web.removeJavascriptInterface("CourseDraftSelectionBridge")
            bodyDrawn = false; web.awaitingBodyDraw = false; web.visualRequest++; web.onBodyDrawn = null
            web.setOnScrollChangeListener(null)
            web.stopLoading(); web.destroy()
        })
        if (readingAllowed && liveSelection != null && actionSelection == null && selectedExcerpt == null) {
            UiAction(
                label = "选区操作",
                onClick = { actionSelection = liveSelection },
                filled = true,
                actionId = "reader.selection.actions",
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("reader-selection-actions"),
            )
        }
        sourceError?.let { message ->
            Surface(Modifier.align(Alignment.TopCenter).padding(12.dp), color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp)) {
                Text(message, Modifier.padding(12.dp).testTag("chapter-source-error"))
            }
        }
        if (selectedExcerpt != null && !qaVisible) UiAction(
            label = "返回问答", onClick = { qaVisible = true }, filled = true,
            actionId = "reader.chapter.return", modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                .testTag("chapter-source-return"),
        )
    }
    actionSelection?.let { selected ->
        SelectionActionSheet(
            quote = selected.quote,
            selectionKey = selected,
            onDismiss = ::clearSelection,
            onHighlight = { color ->
                checkNotNull(highlightStore).let { store ->
                    highlights = store.save(CourseDraftHighlight(selected.range, color))
                }
                clearSelection()
            },
            onAskAi = {
                selectedExcerpt = selected.quote
                qaVisible = true
                clearSelection()
            },
        )
    }
    if (chapterQa != null) selectedExcerpt?.let { excerpt ->
        SourcePracticeChapterQa(chapterQa, onClose = { selectedExcerpt = null }, selectedExcerpt = excerpt,
            visible = qaVisible,
            onOpenPassage = { passage, source ->
                check(passage.sectionId == chapter.key && source.sectionId == chapter.key) { "来源不属于当前章节" }
                clearSelection()
                sourceError = null
                sourceRequest = UUID.randomUUID().toString() to passage.text
                qaVisible = false
            })
    }
}
