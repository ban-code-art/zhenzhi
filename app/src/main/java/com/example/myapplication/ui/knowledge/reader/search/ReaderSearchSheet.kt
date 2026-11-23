package com.example.myapplication.ui.knowledge.reader.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.data.reader.search.SearchCoverage
import com.example.myapplication.data.reader.search.ExactMatchMode
import com.example.myapplication.data.reader.search.ExactMatchOptions
import com.example.myapplication.data.reader.search.SearchHit
import com.example.myapplication.data.reader.search.SearchSectionGroup
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiPane
import com.example.myapplication.ui.components.UiPaneRole
import com.example.myapplication.ui.components.UiSheet
import com.example.myapplication.ui.theme.ReaderTokens
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles

/**
 * 书内搜索面板（W5-04）。
 *
 * 只做三件事：输入查询（词/短语）、展示“覆盖度 + 进度 + 按章节分组的结果”、
 * 选中结果后把 Locator 交回阅读器跳转。命中位置的偏移与高亮全部由
 * `ReaderLocator` 承担，UI 不参与 DOM 偏移计算。
 */
@Composable
fun ReaderSearchSheet(
    documentId: String,
    onDismiss: () -> Unit,
    onOpenLocator: (ReaderLocator) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val factory = remember(documentId) { ReaderSearchViewModelFactory(context, documentId) }
    // 按 documentId 分键：同一宿主里切换/重进阅读器不会复用上一本书的搜索结果。
    val viewModel: ReaderSearchViewModel = viewModel(key = "reader-search:$documentId", factory = factory)
    val state by viewModel.state.collectAsState()
    DisposableEffect(viewModel) { onDispose { viewModel.cancelSearch() } }

    val openHit: (Int) -> Unit = { index ->
        viewModel.selectHit(index)
        viewModel.openHit(index, onOpenLocator)
    }
    val step: (Int) -> Unit = { delta ->
        // 只移动选中项（高亮），不关面板；真正跳转由“打开所选”/点某一行触发。
        viewModel.stepSelection(delta)
    }
    val listState = rememberLazyListState()

    UiSheet(
        title = "原文精确查找",
        onDismiss = onDismiss,
        maxContentFraction = 0.78f,
        dragContentAtTop = {
            state.hits.isEmpty() || (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0)
        },
        modifier = modifier.testTag("reader-exact-search-sheet"),
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = ReaderTokens.Primary,
                    )
                    UiAction(
                        label = "停止",
                        onClick = { viewModel.cancelSearch() },
                        inheritedPane = true,
                        foreground = UiRoles.accent,
                        actionId = "reader.search.stop",
                    )
                    Spacer(Modifier.weight(1f))
                }
                UiAction(
                    label = "关闭",
                    onClick = onDismiss,
                    inheritedPane = true,
                    actionId = "reader.search.close",
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                modifier = Modifier.fillMaxWidth().testTag("reader-exact-search-query"),
                singleLine = true,
                placeholder = { Text("输入要查找的原文词或整句") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
                trailingIcon = {
                    UiAction(
                        label = "查找",
                        onClick = { viewModel.submit() },
                        inheritedPane = true,
                        foreground = UiRoles.accent,
                        actionId = "reader.search.submit",
                    )
                },
            )

            MatchOptions(state.options, viewModel::onOptionsChanged)
            Text("范围：仅本书原文 · AI语义问答请使用知识问答",
                style = MaterialTheme.typography.labelSmall, color = ReaderTokens.TextSecondary)

            CoverageBanner(state)
            ProgressBanner(state)
            ResultSummary(state)
            if (state.error != null) {
                Text(errorText(state.error), color = ReaderTokens.Danger,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                UiAction(
                    label = "重试查找",
                    onClick = viewModel::submit,
                    inheritedPane = true,
                    foreground = UiRoles.accent,
                    actionId = "reader.search.retry",
                )
            }
            if (state.warnings.isNotEmpty()) {
                Text("部分原文读取存在限制（${state.warnings.joinToString()}）",
                    style = MaterialTheme.typography.labelSmall, color = ReaderTokens.Accent)
            }

            if (state.hits.isNotEmpty()) {
                val rows = remember(state.hits, state.groups) { buildRows(state.hits, state.groups) }
                // 上一处/下一处（含自动翻页）后把选中行滚进可视区。
                LaunchedEffect(state.selectedIndex, state.pageOffset, rows.size) {
                    val target = rows.indexOfFirst { it is SearchRow.HitRow && it.index == state.selectedIndex }
                    if (target >= 0) listState.animateScrollToItem(target)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp),
                ) {
                    items(count = rows.size, key = { index -> rows[index].key }) { index ->
                        when (val row = rows[index]) {
                            is SearchRow.Header -> SectionHeader(row.group)
                            is SearchRow.HitRow -> HitRow(
                                index = state.pageOffset + row.index,
                                hit = row.hit,
                                selected = row.index == state.selectedIndex,
                                enabled = state.navigationEnabled,
                                onClick = { openHit(row.index) },
                            )
                        }
                    }
                }
                ResultActions(
                    state = state,
                    onStep = step,
                    onPreviousPage = viewModel::previousPage,
                    onNextPage = viewModel::nextPage,
                    onOpenSelected = { openHit(state.selectedIndex) },
                )
            } else {
                EmptyState(state)
                if (state.canContinueScan) {
                    UiAction(
                        label = "继续扫描原文",
                        onClick = viewModel::nextPage,
                        enabled = state.hasNextPage,
                        inheritedPane = true,
                        foreground = UiRoles.accent,
                        actionId = "reader.search.continue",
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchOptions(options: ExactMatchOptions, onChange: (ExactMatchOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchOptionPill(
                label = "完整短语",
                selected = options.mode == ExactMatchMode.PHRASE,
                isRadio = true,
                onClick = { onChange(options.copy(mode = ExactMatchMode.PHRASE)) },
            )
            SearchOptionPill(
                label = "全部词项",
                selected = options.mode == ExactMatchMode.TERMS,
                isRadio = true,
                onClick = { onChange(options.copy(mode = ExactMatchMode.TERMS)) },
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchOptionPill(
                label = "忽略大小写",
                selected = options.ignoreCase,
                isRadio = false,
                onClick = { onChange(options.copy(ignoreCase = !options.ignoreCase)) },
            )
            SearchOptionPill(
                label = "合并空白",
                selected = options.collapseWhitespace,
                isRadio = false,
                onClick = { onChange(options.copy(collapseWhitespace = !options.collapseWhitespace)) },
            )
            SearchOptionPill(
                label = "全角/半角等价",
                selected = options.normalizeWidth,
                isRadio = false,
                onClick = { onChange(options.copy(normalizeWidth = !options.normalizeWidth)) },
            )
        }
        Text(
            if (options.mode == ExactMatchMode.PHRASE) "短语须连续出现；引号按原字符查找"
            else "同一原文段落包含全部词项，逐处列出最长词项的位置",
            style = MaterialTheme.typography.labelSmall,
            color = ReaderTokens.TextSecondary,
        )
    }
}

@Composable
private fun SearchOptionPill(
    label: String,
    selected: Boolean,
    isRadio: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) UiRoles.color(UiColorRole.OnPrimary) else UiRoles.foreground
    val selectionModifier = if (isRadio) {
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    } else {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
    }
    UiPane(
        role = if (selected) UiPaneRole.Tinted else UiPaneRole.Regular,
        shape = CircleShape,
        modifier = Modifier.heightIn(min = 48.dp).then(selectionModifier),
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected) {
                Icon(
                    UiIcons.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = contentColor,
                )
            }
            Text(
                label,
                style = UiRoles.text(UiRoles.actionText),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CoverageBanner(state: ReaderSearchUiState) {
    val coverage = state.coverage ?: return
    val text: String
    val color: Color
    when (coverage) {
        SearchCoverage.FULL -> {
            text = "原文扫描已完成 · 全书命中数完整"
            color = ReaderTokens.PrimaryDark
        }

        SearchCoverage.PARTIAL -> {
            text = if (state.canContinueScan) "原文扫描未完成 · 已找到数量为下界，可继续扫描"
                else "原文扫描未完成 · 暂无可继续位置，请重试查找"
            color = ReaderTokens.Accent
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun ProgressBanner(state: ReaderSearchUiState) {
    val progress = state.progress
    if (!state.isSearching || progress == null) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = ReaderTokens.Primary,
            trackColor = ReaderTokens.Divider,
        )
        Text(
            text = "正在扫描原文章节 ${progress.scannedSections}/${progress.totalSections} 节" +
                "（已读 ${progress.scannedBytes / 1024} KiB）· 已命中 ${progress.hits} 处",
            style = MaterialTheme.typography.labelSmall,
            color = ReaderTokens.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ResultSummary(state: ReaderSearchUiState) {
    if (!state.hasSearched || state.totalHits <= 0) return
    val first = state.pageOffset + 1
    val last = state.pageOffset + state.hits.size
    Text(
        text = (if (state.coverage == SearchCoverage.FULL) "命中 ${state.totalHits} 处"
            else "至少已找到 ${state.totalHits} 处") + " · 当前第 $first-$last 条",
        style = MaterialTheme.typography.labelMedium,
        color = ReaderTokens.TextSecondary,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultActions(
    state: ReaderSearchUiState,
    onStep: (Int) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onOpenSelected: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        HorizontalDivider(color = ReaderTokens.Divider)
        val canStepBack = state.selectedIndex > 0 || state.hasPreviousPage
        val canStepForward = state.selectedIndex < state.hits.lastIndex || state.hasNextPage
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            UiAction(
                label = "上一处",
                onClick = { onStep(-1) },
                enabled = canStepBack,
                actionId = "reader.search.prevHit",
            )
            UiAction(
                label = "打开所选",
                onClick = onOpenSelected,
                enabled = state.navigationEnabled,
                filled = true,
                actionId = "reader.search.openSelected",
            )
            UiAction(
                label = "下一处",
                onClick = { onStep(1) },
                enabled = canStepForward,
                actionId = "reader.search.nextHit",
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UiAction(
                label = "上一页结果",
                onClick = onPreviousPage,
                enabled = state.hasPreviousPage,
                inheritedPane = true,
                actionId = "reader.search.prevPage",
            )
            UiAction(
                label = if (state.canContinueScan && state.pageOffset + state.hits.size >= state.cachedHitCount)
                    "继续扫描原文" else "下一页结果",
                onClick = onNextPage,
                enabled = state.hasNextPage,
                inheritedPane = true,
                actionId = "reader.search.nextPage",
            )
        }
    }
}

@Composable
private fun EmptyState(state: ReaderSearchUiState) {
    if (state.isSearching) return
    val message = when {
        state.error != null -> return
        state.isEmptyResult -> "本书没有匹配的内容"
        !state.hasSearched -> "输入词或整句开始原文查找"
        state.coverage == SearchCoverage.PARTIAL -> "尚未扫描完本书，当前未找到命中"
        else -> return
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = ReaderTokens.TextSecondary,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    )
}

@Composable
private fun SectionHeader(group: SearchSectionGroup) {
    Text(
        text = "${group.sectionTitle.ifBlank { "未命名章节" }} · 本章已找到 ${group.hitCount} 处",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = ReaderTokens.PrimaryDark,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(ReaderTokens.SurfaceMuted)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun HitRow(
    index: Int,
    hit: SearchHit,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) ReaderTokens.SurfaceMuted else ReaderTokens.Surface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            text = buildSearchSnippet(hit, ReaderTokens.Accent),
            style = MaterialTheme.typography.bodyMedium,
            color = ReaderTokens.TextPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = buildString {
                    append("第 ${index + 1} 处")
                    hit.pageIndex?.let { append(" · 第 ${it + 1} 页") }
                    if (hit.fromFallback) append(" · 原文扫描")
                },
                style = MaterialTheme.typography.labelSmall,
                color = ReaderTokens.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            UiAction(
                label = "跳转",
                onClick = onClick,
                enabled = enabled,
                inheritedPane = true,
                foreground = UiRoles.accent,
                actionId = "reader.search.jump.$index",
            )
        }
    }
    HorizontalDivider(color = ReaderTokens.Divider)
}

/** Render source verbatim. Only an explicit, complete original UTF-16 range is a highlight;
 * missing/invalid legacy metadata never turns literal guillemets into formatting syntax. */
internal fun buildSearchSnippet(hit: SearchHit, highlightColor: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
    val text = hit.snippet
    append(text)
    val start = hit.snippetHighlightStart
    val end = hit.snippetHighlightEnd
    if (start != null && end != null && start >= 0 && start < end && end <= text.length &&
        isSnippetCodePointBoundary(text, start) && isSnippetCodePointBoundary(text, end)) {
        addStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold), start, end)
    }
}

private fun isSnippetCodePointBoundary(text: String, offset: Int): Boolean =
    offset == 0 || offset == text.length || !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())

private sealed interface SearchRow {
    val key: String

    data class Header(val group: SearchSectionGroup) : SearchRow {
        override val key: String get() = "h:${group.sectionId}"
    }

    data class HitRow(val index: Int, val hit: SearchHit) : SearchRow {
        override val key: String get() = "i:$index:${hit.sectionId}:${hit.canonicalStart}"
    }
}

/** 当前页命中按节分组：节变化时插一条小标题。 */
private fun buildRows(hits: List<SearchHit>, groups: List<SearchSectionGroup>): List<SearchRow> {
    val rows = ArrayList<SearchRow>(hits.size + groups.size)
    var currentSection: String? = null
    hits.forEachIndexed { index, hit ->
        if (hit.sectionId != currentSection) {
            currentSection = hit.sectionId
            val group = groups.firstOrNull { it.sectionId == hit.sectionId }
                ?: SearchSectionGroup(
                    sectionId = hit.sectionId,
                    sectionTitle = hit.sectionTitle,
                    sectionOrder = hit.sectionOrder,
                    hitCount = hits.count { it.sectionId == hit.sectionId },
                )
            rows += SearchRow.Header(group)
        }
        rows += SearchRow.HitRow(index, hit)
    }
    return rows
}

private fun errorText(code: String?): String = when (code) {
    null, "" -> "搜索失败，请重试"
    "INVALID_QUERY" -> "请输入要搜索的词"
    "NOT_FOUND" -> "这本书当前不可搜索（可能尚未导入完成）"
    "ACCESS_REVOKED" -> "这本书已被移入回收站，无法搜索"
    "REVISION_MISSING" -> "这本书的内容版本缺失，请重新导入后搜索"
    "IDENTITY_PENDING" -> "正在确认当前阅读身份，请稍后查找"
    "IDENTITY_UNAVAILABLE" -> "当前阅读身份不可用，请重新登录后查找"
    "IDENTITY_CHANGED" -> "阅读身份或本书内容已变化，请重新查找"
    "LOCATOR_INVALID", "RESULT_STALE", "CURSOR_INVALID", "REVISION_CHANGED" -> "查找位置已失效，请重新查找"
    "NAVIGATION_FAILED" -> "原文位置校验失败，请重试跳转或重新查找"
    else -> "搜索失败（$code），请重试"
}
