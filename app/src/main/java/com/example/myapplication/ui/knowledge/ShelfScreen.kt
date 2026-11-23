package com.example.myapplication.ui.knowledge

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.data.local.entity.ReaderJobEntity
import com.example.myapplication.data.reader.ReaderShelfItem
import com.example.myapplication.data.reader.importer.FormatRegistry
import com.example.myapplication.data.reader.importer.ReaderFormat
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.ui.components.AnchoredActionMenu
import com.example.myapplication.ui.components.AnchoredMenuChoice
import com.example.myapplication.ui.components.LocalMainDockBottomInset
import com.example.myapplication.ui.components.PopupAnchor
import com.example.myapplication.ui.components.PopupDrag
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiActionGroup
import com.example.myapplication.ui.components.UiAlertHost
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.UiNoticeHost
import com.example.myapplication.ui.components.UiNoticeVisuals
import com.example.myapplication.ui.components.UiSheet
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.mine.datamanagement.AppDataManagementRoute
import com.example.myapplication.ui.navigation.LocalPageBackPreview
import com.example.myapplication.ui.navigation.PageBackPreview
import com.example.myapplication.ui.theme.ReaderTokens
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.components.rememberAdaptiveWindowInfo
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShelfScreen(
    onOpenReader: (String) -> Unit = {},
    onOpenCourseBook: ((String) -> Unit)? = null,
    onOpenCourseLibrary: (() -> Unit)? = null,
    importedOnly: Boolean = false,
    initialPosition: String = "{}",
    onPosition: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val adaptive = rememberAdaptiveWindowInfo()
    val context = LocalContext.current
    val factory = remember { ShelfViewModelFactory(context) }
    val viewModel: ShelfViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    val owner = state.navigation.ownerUserId to state.navigation.sessionEpoch
    val snackbar = remember(owner) { SnackbarHostState() }
    var consumedIncoming by remember(owner) { mutableStateOf<String?>(null) }
    var lastPublished by remember(owner) { mutableStateOf<String?>(null) }
    val publishPosition by rememberUpdatedState(onPosition)
    val incomingPending = importedOnly && consumedIncoming != initialPosition && initialPosition != state.navigation.encode()
    val currentIncomingPending by rememberUpdatedState(incomingPending)
    LaunchedEffect(importedOnly, initialPosition, owner, state.isLoading, state.shelfError) {
        if (importedOnly && viewModel.restoreIncomingNavigation(initialPosition)) consumedIncoming = initialPosition
    }
    var renameTarget by remember(owner) { mutableStateOf<ReaderShelfItem?>(null) }
    var renameValue by remember(owner) { mutableStateOf("") }
    var renameError by remember(owner) { mutableStateOf<String?>(null) }
    var renameSaving by remember(owner) { mutableStateOf(false) }
    var showTrash by remember(owner) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dockBottomInset = LocalMainDockBottomInset.current
    val scroll = rememberLazyListState()
    var restoredVersion by remember(owner) { mutableStateOf(-1L) }
    val activeJobs = state.jobs.filter { it.kind == "IMPORT" && it.status in setOf("PENDING", "RUNNING", "RETRY_WAIT") }
    val documents = if (importedOnly) state.filteredImportedDocuments else state.filteredDocuments
    val continueReading = documents.filter { it.lastReadAt > 0 }
    val imported = documents.filter { it.kind == "IMPORTED" }
    val importedKeys = buildList {
        add("shelf-header"); add("shelf-query")
        if (state.shelfError != null || state.isLoading) add("shelf-loading-state")
        if (state.reparsingDocumentId != null) add("shelf-reparse")
        if (activeJobs.isNotEmpty()) { add("shelf-jobs"); addAll(activeJobs.map { "job-${it.id}" }) }
        if (continueReading.isNotEmpty()) { add("shelf-continue"); addAll(continueReading.map { "continue-${it.documentId}" }) }
        add("shelf-imported")
        if (imported.isEmpty() && activeJobs.isEmpty()) add("shelf-empty") else addAll(imported.map { it.documentId })
    }
    val currentImportedKeys by rememberUpdatedState(importedKeys)
    LaunchedEffect(importedOnly, state.navigationVersion, state.isLoading, state.shelfError, importedKeys, incomingPending) {
        if (importedOnly && !state.isLoading && state.shelfError == null && !incomingPending) {
            restoredVersion = -1
            withFrameNanos { }
            snapshotFlow { scroll.layoutInfo.totalItemsCount }.first { it == importedKeys.size }
            val savedAnchor = if (state.navigation.anchorKey == "shelf-sort") "shelf-query" else state.navigation.anchorKey
            val anchor = savedAnchor?.let(importedKeys::indexOf) ?: -1
            val index = if (anchor >= 0) anchor else state.navigation.index.coerceIn(0, importedKeys.lastIndex)
            scroll.scrollToItem(index, state.navigation.offset)
            restoredVersion = state.navigationVersion
        }
    }
    LaunchedEffect(importedOnly, scroll, viewModel, owner) {
        if (importedOnly) snapshotFlow {
            Triple(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset,
                scroll.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String)
        }.collect { (index, offset, anchor) ->
            if (!state.isLoading && state.shelfError == null && !currentIncomingPending && restoredVersion == state.navigationVersion && scroll.layoutInfo.totalItemsCount == currentImportedKeys.size)
                viewModel.saveScroll(index, offset, anchor, scroll.layoutInfo.totalItemsCount)
        }
    }
    LaunchedEffect(importedOnly, state.navigation, state.isLoading, state.shelfError, incomingPending, restoredVersion, initialPosition) {
        if (importedOnly && !state.isLoading && state.shelfError == null && !incomingPending &&
            state.navigation.ownerUserId > 0 && restoredVersion == state.navigationVersion) {
            val snapshot = viewModel.navigationSnapshot()
            if (snapshot != lastPublished || snapshot != initialPosition) {
                lastPublished = snapshot
                publishPosition(snapshot)
            }
        }
    }
    fun open(documentId: String) {
        if (importedOnly) {
            // 打开一本书不能被书架位置恢复卡住：恢复期间用户点书也必须能进入阅读器。
            // 仅在恢复完成后回写现场位置；恢复未完成时沿用路由里已有的现场，
            // 返回时仍能回到进入前的位置。
            if (state.isLoading || state.shelfError != null) return
            if (restoredVersion == state.navigationVersion) {
                viewModel.saveScroll(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset,
                    scroll.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String, scroll.layoutInfo.totalItemsCount)
                val snapshot = viewModel.navigationSnapshot()
                lastPublished = snapshot
                publishPosition(snapshot) // Must be synchronous before NavHost saves the ReturnPoint.
            }
        }
        onOpenReader(documentId)
    }

    LaunchedEffect(owner, state.message) {
        val message = state.message ?: return@LaunchedEffect
        try {
            snackbar.showSnackbar(
                UiNoticeVisuals(eventId = UUID.randomUUID().toString(), ownerKey = owner, message = message),
            )
        } finally {
            val latest = viewModel.state.value
            if (latest.navigation.ownerUserId == owner.first &&
                latest.navigation.sessionEpoch == owner.second && latest.message == message
            ) {
                viewModel.clearMessage()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importUris(uris)
    }
    val backupVisibility = remember(owner) { MutableTransitionState(false) }
    val backupPreview = remember(owner) { PageBackPreview() }
    val showBackup = backupVisibility.currentState || backupVisibility.targetState
    val backupDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    var exportTarget by remember(owner) { mutableStateOf<String?>(null) }
    var backupReturnSource by remember(owner) { mutableStateOf<PopupAnchor?>(null) }
    var backupReturnDocument by remember(owner) { mutableStateOf<String?>(null) }
    LaunchedEffect(showBackup, owner) {
        if (!showBackup) {
            backupPreview.reset()
            exportTarget = null
            val source = backupReturnSource ?: return@LaunchedEffect
            val documentId = backupReturnDocument
            withFrameNanos { }
            val latest = viewModel.state.value
            if (latest.navigation.ownerUserId == owner.first && latest.navigation.sessionEpoch == owner.second &&
                !latest.isLoading && latest.shelfError == null && latest.documents.any { it.documentId == documentId } &&
                source.bounds != null
            ) runCatching { source.focus.requestFocus() }
            backupReturnSource = null
            backupReturnDocument = null
        }
    }

    val sortEnabled = importedOnly && !state.isLoading && !incomingPending && state.shelfError == null
    val backdrop = rememberUiBackdrop()
    val sortSource = remember(owner) { PopupAnchor() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var sceneOrigin by remember { mutableStateOf(Offset.Zero) }
    var sortMenu by remember(owner) { mutableStateOf<OpenSortMenu?>(null) }
    var bookMenu by remember(owner) { mutableStateOf<OpenBookMenu?>(null) }

    fun menuBounds(bounds: Rect?): Rect? =
        bounds?.takeIf { it.width > 0 && it.height > 0 }?.translate(-sceneOrigin)

    fun currentOwnerMatches(current: ShelfUiState): Boolean =
        current.navigation.ownerUserId == owner.first && current.navigation.sessionEpoch == owner.second &&
            !current.isLoading && current.shelfError == null

    fun openSortMenu(drag: PopupDrag?): Boolean {
        val sourceBounds = menuBounds(sortSource.bounds) ?: return false
        if (!sortEnabled || sortMenu != null || bookMenu != null) return false
        val current = viewModel.state.value
        if (!currentOwnerMatches(current)) return false
        keyboard?.hide()
        focusManager.clearFocus()
        sortMenu = OpenSortMenu(sourceBounds, current.navigationVersion, current.navigation.sort, current.query, drag)
        return true
    }

    val openBookMenu: (PopupAnchor, ReaderShelfItem, PopupDrag?) -> Boolean = { source, item, drag ->
        val bounds = menuBounds(source.bounds)
        val current = viewModel.state.value
        if (bookMenu == null && sortMenu == null && bounds != null &&
            currentOwnerMatches(current) && item in current.documents
        ) {
            keyboard?.hide()
            focusManager.clearFocus()
            bookMenu = OpenBookMenu(source, bounds, item, drag)
            true
        } else {
            false
        }
    }

    fun openBackup(documentId: String? = null, source: PopupAnchor? = null) {
        if (showBackup) return
        // The retained shelf must relinquish its editor before the opaque page opens.
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        exportTarget = documentId
        backupReturnSource = source
        backupReturnDocument = documentId
        sortMenu = null
        bookMenu = null
        backupVisibility.targetState = true
    }

    val shelfContents: @Composable BoxScope.() -> Unit = {
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = adaptive.contentMaxWidth).fillMaxSize()) {
            LazyColumn(
                state = scroll,
                modifier = Modifier
                    .testTag("shelf-list")
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(top = if (onBack != null) 56.dp else 0.dp, bottom = 24.dp + dockBottomInset),
            ) {
                item(key = "shelf-header") {
                    ShelfHeader(
                        onImport = { picker.launch(SUPPORTED_MIME_TYPES) },
                        onTrash = { showTrash = true },
                        hasTrash = state.trash.isNotEmpty(),
                        onBackup = { openBackup() },
                        importedOnly = importedOnly,
                        sortAction = if (importedOnly) {
                            {
                                val latestSortOpen by rememberUpdatedState<(PopupDrag?) -> Boolean> { drag -> openSortMenu(drag) }
                                UiAction(
                                    label = "更多",
                                    icon = UiIcons.More,
                                    enabled = sortEnabled,
                                    onClick = { openSortMenu(null) },
                                    modifier = Modifier.testTag("imported-sort-menu")
                                        .focusRequester(sortSource.focus)
                                        .then(sortSource.inputModifier(sortEnabled) { drag -> latestSortOpen(drag) }),
                                    actionId = "shelf.sort.more",
                                    visualModifier = sortSource.visualModifier,
                                    foregroundModifier = sortSource.foregroundModifier(),
                                )
                            }
                        } else null,
                    )
                }
                item(key = "shelf-query") {
                    BasicTextField(
                        value = state.query,
                        onValueChange = { query -> if (!showBackup) viewModel.setQuery(query) },
                        enabled = !showBackup && (!importedOnly || (!state.isLoading && !incomingPending)),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = ReaderTokens.TextPrimary),
                        cursorBrush = SolidColor(ReaderTokens.Primary),
                        modifier = Modifier.testTag("imported-query").fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        decorationBox = { inner ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ReaderTokens.Surface).padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(UiIcons.Search, contentDescription = null, tint = ReaderTokens.TextSecondary)
                                Spacer(Modifier.width(10.dp))
                                Box(Modifier.weight(1f)) {
                                    if (state.query.isEmpty()) Text("搜索书名或作者", color = ReaderTokens.TextSecondary)
                                    inner()
                                }
                            }
                        },
                    )
                }
                if (state.isLoading || state.shelfError != null) item(key = "shelf-loading-state") {
                    if (state.isLoading) CircularProgressIndicator(Modifier.padding(20.dp).testTag("shelf-loading"))
                    else Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            state.shelfError.orEmpty(),
                            Modifier.testTag("shelf-error"),
                            color = ReaderTokens.Danger,
                            style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                        )
                        UiAction(
                            label = "重试",
                            onClick = viewModel::reloadShelf,
                            foreground = UiRoles.accent,
                            actionId = "shelf.retry",
                            modifier = Modifier.testTag("shelf-retry"),
                        )
                    }
                }
                if (state.reparsingDocumentId != null) {
                    item(key = "shelf-reparse") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "正在重新解析…",
                                modifier = Modifier.weight(1f),
                                color = ReaderTokens.TextPrimary,
                                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                            )
                            UiAction(
                                label = "取消",
                                onClick = viewModel::cancelReparse,
                                foreground = UiRoles.accent,
                                actionId = "shelf.reparse.cancel",
                            )
                        }
                    }
                }
                if (activeJobs.isNotEmpty()) {
                    item(key = "shelf-jobs") { SectionTitle("正在导入") }
                    items(activeJobs, key = { "job-${it.id}" }) { job -> ImportJobRow(job, onCancel = { viewModel.cancelImport(job.id) }) }
                }
                val builtIn = documents.filter { it.kind == "BUILTIN" }.sortedWith(compareBy(nullsLast()) { it.legacyChapterId })
                if (continueReading.isNotEmpty()) {
                    item(key = "shelf-continue") { SectionTitle("继续阅读") }
                    items(continueReading, key = { "continue-${it.documentId}" }) { item ->
                        DocumentCard(
                            item = item,
                            onOpen = { open(item.documentId) },
                            cardTag = "shelf-continue-${item.documentId}",
                            onMenu = openBookMenu,
                            openEnabled = state.reparsingDocumentId != item.documentId &&
                                (!importedOnly || (!state.isLoading && state.shelfError == null)),
                        )
                    }
                }
                if (!importedOnly) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("内置课程", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ReaderTokens.TextPrimary)
                        if (onOpenCourseLibrary != null) {
                            UiAction(
                                label = "全部课程",
                                onClick = onOpenCourseLibrary,
                                foreground = UiRoles.accent,
                                actionId = "shelf.courseLibrary.open",
                                modifier = Modifier.testTag("shelf-course-library-btn"),
                            )
                        }
                    }
                }
                if (onOpenCourseBook != null || onOpenCourseLibrary != null) {
                    val courses = state.filteredCourseBooks
                    if (courses.isEmpty()) {
                        item { EmptyHint("内置课程正在准备中") }
                    } else {
                        items(courses, key = { "course-${it.bookId}" }) { book ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpenCourseBook?.invoke(book.bookId) ?: onOpenCourseLibrary?.invoke() }
                                    .testTag("shelf-course-book-${book.bookId}"),
                                colors = CardDefaults.cardColors(containerColor = ReaderTokens.Surface),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ReaderTokens.TextPrimary)
                                    book.summary?.takeIf { it.isNotBlank() }?.let {
                                        Spacer(Modifier.height(4.dp))
                                        Text(it, style = MaterialTheme.typography.bodyMedium, color = ReaderTokens.TextSecondary)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            when (book.reviewStatus) {
                                                "APPROVED" -> "已审核"
                                                "DRAFT" -> "准备中"
                                                else -> "审核中"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = ReaderTokens.Primary,
                                            modifier = Modifier.testTag("shelf-course-status-${book.bookId}")
                                        )
                                        Spacer(Modifier.weight(1f))
                                        UiAction(
                                            label = "进入课程",
                                            onClick = { onOpenCourseBook?.invoke(book.bookId) ?: onOpenCourseLibrary?.invoke() },
                                            foreground = UiRoles.accent,
                                            actionId = "shelf.course.open",
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (builtIn.isEmpty()) {
                        item { EmptyHint("内置课程正在准备中") }
                    } else {
                        items(builtIn, key = { it.documentId }) { item ->
                            DocumentCard(
                                item = item,
                                onOpen = { onOpenReader(item.documentId) },
                                onMenu = openBookMenu,
                                openEnabled = state.reparsingDocumentId != item.documentId,
                            )
                        }
                    }
                }
                }
                item(key = "shelf-imported") { SectionTitle("我的书架") }
                if (imported.isEmpty() && activeJobs.isEmpty()) {
                    item(key = "shelf-empty") { EmptyShelf(onImport = { picker.launch(SUPPORTED_MIME_TYPES) }) }
                } else {
                    items(imported, key = { it.documentId }) { item ->
                        DocumentCard(
                            item = item,
                            onOpen = { open(item.documentId) },
                            onMenu = openBookMenu,
                            openEnabled = state.reparsingDocumentId != item.documentId &&
                                (!importedOnly || (!state.isLoading && state.shelfError == null)),
                        )
                    }
                }
            }
        }
    }
    Box(
        Modifier.fillMaxSize().clipToBounds().background(ReaderTokens.PageBackground)
            .onGloballyPositioned { sceneOrigin = it.positionInRoot() },
    ) {
        UiGlassBackdrop(
            backdrop,
            Modifier.fillMaxSize().then(
                if (sortMenu == null && bookMenu == null && !showBackup) Modifier
                else Modifier.clearAndSetSemantics { hideFromAccessibility() },
            ).focusProperties { onEnter = { if (showBackup) cancelFocusChange() } }.focusGroup(),
            // Render the scrolling shelf directly. Its controls supply their own glass surface.
            captureEnabled = false,
            content = shelfContents,
        )
        if (onBack != null) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = adaptive.contentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .then(
                        if (sortMenu == null && bookMenu == null && !showBackup) Modifier
                        else Modifier.clearAndSetSemantics { hideFromAccessibility() },
                    ),
            ) {
                UiAction(
                    label = "返回",
                    icon = UiIcons.Back,
                    onClick = onBack,
                    enabled = !showBackup,
                    keepEnabledStyle = true,
                    backdrop = backdrop,
                    modifier = Modifier.testTag("imported-back"),
                    actionId = "shelf.back",
                )
            }
        }
        if (!showBackup) UiNoticeHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter)
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + dockBottomInset)
                .then(if (sortMenu == null && bookMenu == null) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }),
            ownerKey = owner,
            backdrop = backdrop,
        )
        sortMenu?.takeIf { !showBackup }?.let { menu ->
            val anchor = menuBounds(sortSource.bounds).takeIf { sortEnabled }
            AnchoredActionMenu(
                anchor = anchor,
                openFrom = menu.openingBounds,
                sourceAnchor = sortSource,
                drag = menu.drag,
                ownerMatches = {
                    val current = viewModel.state.value
                    currentOwnerMatches(current) && current.navigationVersion == menu.navigationVersion &&
                        current.navigation.sort == menu.sort && current.query == menu.query
                },
                submenuLabel = "排序",
                choices = importedSortChoices,
                selected = state.navigation.sort,
                onChoose = { sort -> if (sort != state.navigation.sort) viewModel.setSort(sort) },
                onClosed = {
                    sortMenu = null
                    if (anchor != null) sortSource.focus.requestFocus()
                },
                backdrop = backdrop,
                tagPrefix = "imported-sort",
            )
        }
        bookMenu?.takeIf { !showBackup }?.let { menu ->
            val current = documents.firstOrNull { it.documentId == menu.item.documentId }
            val bookAnchor = menuBounds(menu.source.bounds).takeIf {
                current != null && !state.isLoading && state.shelfError == null
            }
            AnchoredActionMenu(
                anchor = bookAnchor,
                openFrom = menu.openingBounds,
                sourceAnchor = menu.source,
                drag = menu.drag,
                ownerMatches = {
                    val latest = viewModel.state.value
                    currentOwnerMatches(latest) && menu.item in latest.documents
                },
                choices = current?.bookMenuChoices(state.reparsingDocumentId == null).orEmpty(),
                paneTitleText = current?.let { "${it.title}的操作" } ?: "书籍操作",
                onChoose = { value ->
                    if (current != null && menuBounds(menu.source.bounds) != null) {
                        when (BookAction.valueOf(value)) {
                            BookAction.Rename -> {
                                renameTarget = current
                                renameValue = current.title
                                renameError = null
                                renameSaving = false
                            }
                            BookAction.Reparse -> viewModel.reparseMarkdown(current.documentId)
                            BookAction.Export -> openBackup(current.documentId, menu.source)
                            BookAction.Trash -> viewModel.trash(current.documentId)
                        }
                    }
                },
                onClosed = {
                    bookMenu = null
                    if (bookAnchor != null && menu.source.bounds != null && renameTarget == null && !showBackup) {
                        menu.source.focus.requestFocus()
                    }
                },
                backdrop = backdrop,
                tagPrefix = "shelf-book-menu",
            )
        }
        AnimatedVisibility(
            visibleState = backupVisibility,
            enter = slideInHorizontally(tween(240)) { it * backupDirection },
            exit = slideOutHorizontally(tween(260)) { it * (backupPreview.committedDirection ?: backupDirection) },
        ) {
            // Only the export page follows back gestures. The retained shelf stays in place.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = size.width * backupPreview.offset }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
                        }
                    },
            ) {
                CompositionLocalProvider(LocalPageBackPreview provides backupPreview) {
                    AppDataManagementRoute(
                        onBack = { backupVisibility.targetState = false },
                        initialDocumentId = exportTarget,
                        initialIdentity = SessionIdentity(owner.first.takeIf { it > 0L }, owner.second),
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        UiAlertHost(
            title = "重命名",
            ownerKey = owner to target.documentId,
            onDismissRequest = { _, _ ->
                if (!renameSaving) {
                    renameTarget = null
                    renameError = null
                }
            },
            modifier = Modifier.testTag("shelf-rename-dialog"),
        ) { interaction, _, _ ->
            Text(
                "重命名",
                modifier = Modifier.fillMaxWidth().semantics { heading() },
                style = UiRoles.text(MaterialTheme.typography.titleLarge),
                color = ReaderTokens.TextPrimary,
                textAlign = TextAlign.Center,
            )
            OutlinedTextField(
                value = renameValue,
                onValueChange = {
                    renameValue = it
                    renameError = null
                },
                singleLine = true,
                enabled = !renameSaving,
                isError = renameError != null,
                supportingText = renameError?.let { errorMessage ->
                    {
                        Text(
                            errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = UiRoles.text(MaterialTheme.typography.bodySmall),
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp)
                    .testTag("shelf-rename-input"),
            )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UiAction(
                    label = "取消",
                    onClick = {
                        if (!renameSaving) {
                            renameTarget = null
                            renameError = null
                        }
                    },
                    enabled = !renameSaving,
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    actionId = "shelf.rename.cancel",
                )
                UiAction(
                    label = "保存",
                    onClick = {
                        if (renameSaving) return@UiAction
                        renameSaving = true
                        renameError = null
                        scope.launch {
                            try {
                                when (val result = viewModel.renameDocument(target.documentId, renameValue)) {
                                    is OperationResult.Ok -> {
                                        renameTarget = null
                                        renameError = null
                                    }
                                    is OperationResult.Rejected -> {
                                        renameError = when (result.code) {
                                            "EMPTY_TITLE", "INVALID_ARGUMENT" -> "书名不能为空"
                                            "DUPLICATE_TITLE" -> "书架中已有同名书籍"
                                            "NOT_FOUND" -> "书籍已不可用"
                                            "NOT_SIGNED_IN" -> "登录状态已变化，请重新打开书架"
                                            else -> "无法重命名：${result.code}"
                                        }
                                    }
                                    is OperationResult.Conflict -> renameError = "内容已变化，请重试"
                                    is OperationResult.Failed -> renameError = "重命名失败，请重试"
                                }
                            } finally {
                                renameSaving = false
                            }
                        }
                    },
                    enabled = !renameSaving,
                    busy = renameSaving,
                    foreground = UiRoles.accent,
                    inheritedPane = true,
                    interactionSource = interaction,
                    animatePress = false,
                    actionId = "shelf.rename.confirm",
                    modifier = Modifier.testTag("shelf-rename-confirm"),
                )
            }
        }
    }

    if (showTrash) {
        val trashScroll = rememberLazyListState()
        UiSheet(
            title = "回收站",
            onDismiss = { showTrash = false },
            modifier = Modifier.testTag("shelf-trash-dialog"),
            actions = { UiAction(label = "关闭", onClick = { showTrash = false }, actionId = "library.trash.close") },
            dragContentAtTop = { !trashScroll.canScrollBackward },
        ) {
            if (state.trash.isEmpty()) Text(
                "回收站为空",
                color = ReaderTokens.TextSecondary,
                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
            )
            else LazyColumn(modifier = Modifier.height(260.dp), state = trashScroll) {
                items(state.trash, key = { it.documentId }) { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.title,
                            modifier = Modifier.weight(1f),
                            color = ReaderTokens.TextPrimary,
                            style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                        )
                        UiAction(
                            label = "恢复",
                            onClick = { viewModel.restore(item.documentId) },
                            modifier = Modifier.testTag("shelf-restore-${item.documentId}"),
                            actionId = "shelf.trash.restore",
                        )
                    }
                }
            }
        }
    }

}

private enum class BookAction { Rename, Reparse, Export, Trash }

private data class OpenSortMenu(
    val openingBounds: Rect,
    val navigationVersion: Long,
    val sort: String,
    val query: String,
    val drag: PopupDrag?,
)

private data class OpenBookMenu(
    val source: PopupAnchor,
    val openingBounds: Rect,
    val item: ReaderShelfItem,
    val drag: PopupDrag?,
)

private fun ReaderShelfItem.bookMenuChoices(reparseEnabled: Boolean) = buildList {
    add(AnchoredMenuChoice(BookAction.Rename.name, "重命名", UiIcons.Edit, testTag = "shelf-rename-$documentId"))
    if (kind == "IMPORTED" && format == ReaderFormat.MARKDOWN) {
        add(AnchoredMenuChoice(BookAction.Reparse.name, "重新解析", UiIcons.Refresh,
            enabled = reparseEnabled, testTag = "shelf-reparse-$documentId"))
    }
    add(AnchoredMenuChoice(BookAction.Export.name, "导出笔记", UiIcons.Export, testTag = "shelf-export-$documentId"))
    add(AnchoredMenuChoice(BookAction.Trash.name, if (kind == "BUILTIN") "从书架隐藏" else "移到回收站",
        UiIcons.Trash, testTag = "shelf-remove-$documentId", destructive = true))
}

private val importedSortChoices = ShelfNavigationState.SORTS.map { sort ->
    AnchoredMenuChoice(sort, when (sort) {
        "TITLE_ASC" -> "书名升序"
        "TITLE_DESC" -> "书名降序"
        else -> "最近阅读"
    })
}

@Composable
private fun ShelfHeader(
    onImport: () -> Unit,
    onTrash: () -> Unit,
    hasTrash: Boolean,
    onBackup: () -> Unit,
    importedOnly: Boolean,
    sortAction: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 24.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (importedOnly) "导入图书" else "知识学习", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = ReaderTokens.TextPrimary)
            Text(if (importedOnly) "我的书籍与导入任务" else "内置课程与我的书架", style = MaterialTheme.typography.bodySmall, color = ReaderTokens.TextSecondary)
        }
        UiActionGroup {
            UiAction(
                label = "导出与恢复",
                icon = UiIcons.Export,
                onClick = onBackup,
                modifier = Modifier.testTag("shelf-backup"),
                actionId = "shelf.backup.open",
            )
            if (hasTrash || importedOnly) {
                UiAction(
                    label = "回收站",
                    icon = UiIcons.Trash,
                    onClick = onTrash,
                    modifier = Modifier.testTag("shelf-trash"),
                    actionId = "shelf.trash.open",
                )
            }
            UiAction(
                label = "导入书籍",
                icon = UiIcons.Import,
                onClick = onImport,
                modifier = Modifier.testTag("shelf-import"),
                actionId = "shelf.import.open",
                foreground = ReaderTokens.Primary,
            )
        }
        sortAction?.invoke()
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ReaderTokens.TextPrimary)
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = ReaderTokens.TextSecondary)
}

@Composable
private fun EmptyShelf(onImport: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = ReaderTokens.Surface)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有导入书籍", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = ReaderTokens.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text("支持 EPUB、PDF、TXT、Markdown、HTML/MHTML、FB2、MOBI/AZW3、CBZ 漫画", color = ReaderTokens.TextSecondary)
            Spacer(Modifier.height(14.dp))
            UiAction(
                label = "导入第一本书",
                onClick = onImport,
                filled = true,
                actionId = "shelf.empty.import",
            )
        }
    }
}

@Composable
private fun DocumentCard(
    item: ReaderShelfItem,
    onOpen: () -> Unit,
    onMenu: (PopupAnchor, ReaderShelfItem, PopupDrag?) -> Boolean,
    openEnabled: Boolean = true,
    cardTag: String = "shelf-document-${item.documentId}",
) {
    val menuAnchor = remember(cardTag, item.documentId) { PopupAnchor() }
    Card(
        modifier = Modifier.testTag(cardTag).fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp).clickable(enabled = openEnabled, onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ReaderTokens.Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(ReaderTokens.SurfaceMuted), contentAlignment = Alignment.Center) {
                Text(item.format.badge(), color = ReaderTokens.PrimaryDark, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 2, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = ReaderTokens.TextPrimary)
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(if (item.kind == "BUILTIN") "内置课程" else "我的书籍")
                        if (item.kind != "BUILTIN") item.author?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                        if (item.isCompleted) append(" · 已完成")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ReaderTokens.TextSecondary,
                )
                if (item.lastReadAt > 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { progressFromLocator(item.locatorJson) }, modifier = Modifier.fillMaxWidth().height(2.dp), color = ReaderTokens.Primary, trackColor = ReaderTokens.Divider)
                }
            }
            val menuTag = if (cardTag == "shelf-document-${item.documentId}") {
                "shelf-menu-${item.documentId}"
            } else {
                "$cardTag-menu"
            }
            UiAction(
                label = "更多，${item.title}",
                icon = UiIcons.More,
                onClick = { onMenu(menuAnchor, item, null) },
                modifier = Modifier.testTag(menuTag).focusRequester(menuAnchor.focus),
                actionId = "shelf.book.more",
                visualModifier = menuAnchor.visualModifier,
                foregroundModifier = menuAnchor.foregroundModifier(),
            )
        }
    }
}

@Composable
private fun ImportJobRow(job: ReaderJobEntity, onCancel: () -> Unit) {
    val name = remember(job.progressJson) { displayNameOf(job) ?: "导入任务" }
    Card(Modifier.testTag("import-job-${job.id}").fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = ReaderTokens.SurfaceMuted)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = ReaderTokens.TextPrimary)
                Text(stageLabel(job.stage, job.status), style = MaterialTheme.typography.labelSmall, color = ReaderTokens.PrimaryDark)
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = ReaderTokens.Primary, trackColor = ReaderTokens.Divider)
            UiAction(
                label = "取消",
                onClick = onCancel,
                foreground = UiRoles.accent,
                actionId = "shelf.import.cancel",
                modifier = Modifier.testTag("import-cancel-${job.id}"),
            )
        }
    }
}

// 徽章口径统一由 FormatRegistry 提供（HTML 仍显示"课程"以区分内置课程与导入网页）。
private fun ReaderFormat.badge(): String =
    if (this == ReaderFormat.HTML) "课程" else FormatRegistry.badgeOf(this)

private fun stageLabel(stage: String, status: String): String = when {
    status == "RETRY_WAIT" -> "等待重试"
    status == "PENDING" -> "等待处理"
    stage == "COPYING" -> "复制中"
    stage == "VALIDATING" -> "校验中"
    stage == "PARSING" -> "解析中"
    stage == "PUBLISHING" -> "发布中"
    else -> "处理中"
}

private fun progressFromLocator(json: String?): Float {
    if (json.isNullOrBlank()) return 0f
    return runCatching {
        com.example.myapplication.domain.reader.ReaderLocatorCodec.decode(json).progression ?: 0f
    }.getOrDefault(0f).coerceIn(0f, 1f)
}

private fun displayNameOf(job: ReaderJobEntity): String? = runCatching {
    val root = Json.parseToJsonElement(job.progressJson).jsonObject
    (root["displayName"] as? JsonPrimitive)?.content
}.getOrNull()

/**
 * 导入选择器的 MIME 白名单（P5-01 扩展格式）。
 *
 * 这里只是"让选择器显示出来"：真正的格式判定在 [FormatDetector]（内容优先）。
 * 各种系统的 MIME 命名并不统一（AZW3 常被报成 application/octet-stream），
 * 所以 octet-stream 必须保留，否则多数第三方文件管理器会看不到新格式。
 */
private val SUPPORTED_MIME_TYPES = arrayOf(
    "application/epub+zip",
    "application/pdf",
    "text/plain",
    "text/markdown",
    "text/x-markdown",
    "text/html",
    // MOBI / AZW / AZW3
    "application/x-mobipocket-ebook",
    "application/vnd.amazon.ebook",
    // FB2 / FB2.zip
    "application/x-fictionbook+xml",
    "application/fb2",
    // MHTML
    "multipart/related",
    "application/x-mimearchive",
    // CBZ / 普通 ZIP 容器（内容判定会拒绝非书籍 ZIP）
    "application/vnd.comicbook+zip",
    "application/x-cbz",
    "application/zip",
    "application/octet-stream",
    // M04：Markdown 引用的本地图片需与 .md 一同选中才能采集（SAF 单文档授权读不到兄弟文件）；
    // 批次中没有 Markdown 时图片仍按普通文件交给 FormatDetector，会明确报不支持。
    "image/*",
)
