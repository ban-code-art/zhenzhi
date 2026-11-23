package com.example.myapplication.ui.knowledge.course

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.rememberAdaptiveWindowInfo
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseLibraryScreen(model: CourseLibraryViewModel, onOpen: (String) -> Unit, onBack: () -> Unit,
    onOpenDraft: (() -> Unit)? = null, onOpenBundledDraft: (() -> Unit)? = null,
    bundledCatalog: BundledCourseCatalog? = null, bundledLoading: Boolean = false, bundledError: String? = null,
    onReloadBundled: (() -> Unit)? = null, onOpenBundledBook: ((String) -> Unit)? = null,
    scroll: LazyListState = rememberLazyListState()) {
    val state by model.state.collectAsState()
    val adaptive = rememberAdaptiveWindowInfo()
    val backdrop = rememberUiBackdrop()
    var query by rememberSaveable { mutableStateOf("") }
    var subjectName by rememberSaveable { mutableStateOf(CourseSubject.ALL.name) }
    val subject = CourseSubject.entries.firstOrNull { it.name == subjectName } ?: CourseSubject.ALL
    val bundledByKey = bundledCatalog?.books?.associateBy { it.key }.orEmpty()
    val usableOfficialKeys = state.rows.filter { it.canOpen }.map { it.book.contentKey }.toSet()
    val bundledBooks = bundledCatalog?.search(query, subject).orEmpty().filterNot { it.key in usableOfficialKeys }
    // A validated local draft is the visible learning entry, rather than a second disabled stub.
    // An actually available official record keeps its own production navigation and policy.
    val officialRows = state.rows.filter { row ->
        !(row.book.contentKey in bundledByKey && !row.canOpen) &&
            (subject == CourseSubject.ALL || CourseSubject.forBook(row.book.contentKey, row.book.title) == subject) &&
            (query.isBlank() || row.book.title.contains(query.trim(), ignoreCase = true) ||
                row.book.summary?.contains(query.trim(), ignoreCase = true) == true)
    }
    val subjects = listOf(CourseSubject.ALL) + CourseSubject.entries.filter { candidate ->
        candidate != CourseSubject.ALL && (bundledCatalog?.books?.any { it.subject == candidate } == true ||
            state.rows.any { CourseSubject.forBook(it.book.contentKey, it.book.title) == candidate })
    }
    val refresh: () -> Unit = { model.reload(); onReloadBundled?.invoke() }
    val density = LocalDensity.current
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val headerTopPadding = with(density) { headerHeightPx.toDp() }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(UiRoles.surface(UiSurfaceRole.Page))) {
        UiGlassBackdrop(
            backdrop = backdrop,
            modifier = Modifier.fillMaxSize(),
            captureEnabled = false,
        ) {
            LazyColumn(
                state = scroll,
                modifier = Modifier.align(Alignment.TopCenter)
                    .widthIn(max = adaptive.contentMaxWidth).fillMaxSize().testTag("course-library-list"),
                contentPadding = PaddingValues(
                    start = if (adaptive.widthDp < 360) 16.dp else 20.dp,
                    top = maxOf(headerTopPadding, 76.dp) + 8.dp,
                    end = if (adaptive.widthDp < 360) 16.dp else 20.dp,
                    bottom = 16.dp + navBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "library-search") { CourseSearchField(query, { query = it }, "course-library-search") }
                item(key = "library-filters") { CourseSubjectFilters(subjects, subject) { subjectName = it.name } }
                item(key = "library-catalog-summary") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        bundledCatalog?.let { catalog ->
                            Text("${catalog.books.size} 本教材 · ${catalog.chapterCount} 章 · ${catalog.questionCount} 道练习",
                                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                color = UiRoles.foreground)
                            Text("逐章阅读概念讲解与案例，独立打开本章自测。", style = UiRoles.text(MaterialTheme.typography.bodySmall),
                                color = UiRoles.color(UiColorRole.OnSurfaceVariant))
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            onOpenBundledDraft?.let { open ->
                                UiAction(
                                    label = "全部章节",
                                    onClick = open,
                                    actionId = "courseLibrary.bundledDraft",
                                    modifier = Modifier.testTag("course-bundled-draft-preview"),
                                )
                            }
                            onOpenDraft?.let { open ->
                                UiAction(
                                    label = "导入课程",
                                    onClick = open,
                                    actionId = "courseLibrary.privateDraft",
                                    modifier = Modifier.testTag("course-private-draft-preview"),
                                )
                            }
                        }
                    }
                }
                if (bundledLoading && bundledCatalog == null) item(key = "bundled-loading") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("course-bundled-loading"))
                        Text("正在载入本机教材…", style = UiRoles.text(MaterialTheme.typography.bodySmall),
                            color = UiRoles.color(UiColorRole.OnSurfaceVariant))
                    }
                }
                bundledError?.let { message -> item(key = "bundled-error") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CourseInlineNotice(message, Modifier.testTag("course-bundled-error"), error = true)
                        onReloadBundled?.let { retry ->
                            UiAction(
                                label = "重新载入",
                                onClick = retry,
                                enabled = !bundledLoading,
                                actionId = "courseLibrary.reloadBundled",
                            )
                        }
                    }
                } }
                items(bundledBooks, key = { "bundled-${it.key}" }) { book ->
                    CourseBookCard(number = bundledCatalog!!.books.indexOf(book) + 1, title = book.title,
                        description = book.description, metadata = "${book.chapters.size} 章 · ${book.questionCount} 题",
                        status = "全文教材",
                        subject = book.subject.label, enabled = onOpenBundledBook != null || onOpenBundledDraft != null,
                        onClick = { onOpenBundledBook?.invoke(book.key) ?: onOpenBundledDraft?.invoke() },
                        modifier = Modifier.testTag("course-bundled-book-${book.key}"),
                        statusModifier = Modifier.testTag("course-bundled-status-${book.key}"))
                }
                items(officialRows, key = { "official-${it.book.bookId}" }) { row ->
                    CourseBookCard(number = state.rows.indexOf(row) + 1, title = row.book.title,
                        description = row.book.summary, metadata = "${row.chapterCount} 章",
                        status = if (row.canOpen) "开始阅读" else "暂不可用",
                        subject = CourseSubject.forBook(row.book.contentKey, row.book.title).label,
                        enabled = row.canOpen && !state.loading && !state.closed && state.error == null,
                        onClick = { onOpen(row.book.bookId) }, modifier = Modifier.testTag("course-library-book-${row.book.bookId}"),
                        statusModifier = Modifier.testTag("course-library-status-${row.book.bookId}"),
                        unavailable = row.unavailableReason?.takeIf { it != "COURSE_NOT_APPROVED" }?.let(::courseDisplayError))
                }
                if (bundledBooks.isEmpty() && officialRows.isEmpty() && !bundledLoading && !state.loading &&
                    bundledError == null && state.error == null) item(key = "library-empty") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CourseInlineNotice(if (query.isNotBlank() || subject != CourseSubject.ALL) "未找到相关课程，试试课程名或章节关键词。"
                            else "课程正在准备中。", Modifier.testTag("course-library-empty"))
                        if (query.isNotBlank() || subject != CourseSubject.ALL) UiAction(
                            label = "查看全部课程",
                            onClick = { query = ""; subjectName = CourseSubject.ALL.name },
                            actionId = "courseLibrary.showAll",
                        )
                    }
                }
                if (state.loading) item(key = "official-loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("course-library-loading"))
                }
                state.error?.let { error -> item(key = "official-error") {
                    CourseInlineNotice(courseDisplayError(error), Modifier.testTag("course-library-error"), error = true)
                } }
            }
        }
        Box(Modifier.fillMaxWidth().onSizeChanged { headerHeightPx = it.height }) {
            CourseScreenHeader("内置课程", onBack, backTag = "course-library-back", backdrop = backdrop) {
                UiAction(
                    label = "刷新课程",
                    icon = UiIcons.Refresh,
                    onClick = refresh,
                    enabled = !bundledLoading && !state.closed,
                    backdrop = backdrop,
                    actionId = "courseLibrary.refresh",
                    modifier = Modifier.testTag("course-library-refresh"),
                )
            }
        }
    }
}
