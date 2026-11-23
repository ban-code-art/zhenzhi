package com.example.myapplication.ui.test

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import com.example.myapplication.ui.components.LocalMainDockBottomInset
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackLayer
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.LocalPageBackPreview
import com.example.myapplication.ui.navigation.PageBackPreview
import com.example.myapplication.ui.navigation.UiPageStack
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.learning.OrdinaryQuestionAiContext
import com.example.myapplication.data.learning.OrdinaryQuestionAiPhase
import com.example.myapplication.data.reader.content.ContentPackageQuestionGrading
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.test.OrdinaryBankCatalog
import com.example.myapplication.data.test.OrdinaryBankEntry
import com.example.myapplication.data.test.OrdinaryTestRules
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiPane
import com.example.myapplication.ui.components.UiPaneRole
import com.example.myapplication.ui.knowledge.quiz.OrdinaryQuestionAiWindow
import com.example.myapplication.ui.mine.GlassPageBarHeight
import com.example.myapplication.ui.mine.SubPageScaffold
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class BankTypeFilter(val key: String, val label: String) {
    ALL("ALL", "全部题型"),
    CHOICE("CHOICE", "选择题"),
    JUDGE("JUDGE", "判断题"),
    FILL("FILL", "填空题"),
    ESSAY("ESSAY", "应用题"),
}

@Composable
fun OrdinaryQuestionBankScreen(
    manualFavorites: Set<Long>,
    onToggleFavorite: (Long) -> Unit,
    onBack: () -> Unit,
    identity: SessionIdentity,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as LearningApplication).container
    val userId = identity.userId
    var catalog by remember(userId) { mutableStateOf<OrdinaryBankCatalog?>(null) }
    var loading by remember(userId) { mutableStateOf(true) }
    var loadError by remember(userId) { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable(userId) { mutableStateOf("") }
    var selectedTypeName by rememberSaveable(userId) { mutableStateOf(BankTypeFilter.ALL.name) }
    val selectedFilter = BankTypeFilter.entries.firstOrNull { it.name == selectedTypeName } ?: BankTypeFilter.ALL
    var bankStack by rememberSaveable(userId) { mutableStateOf(listOf("list")) }
    var aiTargetQuestionId by rememberSaveable(userId) { mutableStateOf(-1L) }

    var filterMenuOpen by remember { mutableStateOf(false) }
    var filterMenuClosing by remember { mutableStateOf(false) }
    val filterAnim = remember { Animatable(0f) }
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val detailPreview = remember(userId, identity.epoch) { PageBackPreview() }

    LaunchedEffect(userId, identity.epoch) {
        if (userId == null) {
            loading = false
            loadError = "请先登录后查看题库"
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        try {
            catalog = container.ordinaryTestService.loadBankCatalog(userId, identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "题库加载失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(filterMenuOpen, filterMenuClosing) {
        if (filterMenuOpen && !filterMenuClosing) {
            filterAnim.snapTo(0f)
            // ~180ms downward expand with slight spring bounce
            filterAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 190, easing = filterOpenBounceEasing()),
            )
        } else if (filterMenuClosing) {
            filterAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
            )
            filterMenuOpen = false
            filterMenuClosing = false
        }
    }

    fun closeFilterMenu() {
        if (filterMenuOpen && !filterMenuClosing) {
            filterMenuClosing = true
        }
    }

    val entries = catalog?.entries.orEmpty()
    val filteredEntries = remember(entries, searchQuery, selectedFilter) {
        val q = searchQuery.trim().lowercase()
        entries.filter { entry ->
            val typeOk = selectedFilter == BankTypeFilter.ALL || entry.question.type == selectedFilter.key
            if (!typeOk) return@filter false
            if (q.isEmpty()) return@filter true
            val prov = entry.provenance
            val matchesStem = entry.question.stem.lowercase().contains(q)
            val matchesKnowledge = entry.question.knowledgePoint.orEmpty().lowercase().contains(q)
            val matchesChapter = prov?.chapterTitle.orEmpty().lowercase().contains(q)
            val idToken = q.removePrefix("#")
            val matchesId = prov != null && (
                prov.designId.toString() == idToken ||
                    "#${prov.designId}" == q ||
                    prov.sourceId?.toString() == idToken ||
                    prov.sourceId?.let { "#$it" } == q
                ) || entry.question.id.toString() == idToken
            matchesStem || matchesKnowledge || matchesChapter || matchesId
        }
    }

    val aiEntry = remember(entries, aiTargetQuestionId) {
        entries.firstOrNull { it.question.id == aiTargetQuestionId }
    }
    val parentPreview = LocalPageBackPreview.current
    val density = LocalDensity.current
    val dockBottomInset = LocalMainDockBottomInset.current

    Box(
        modifier = Modifier.fillMaxSize()
            .background(UiRoles.surface(UiSurfaceRole.Page))
            .navigationBarsPadding(),
    ) {
        UiPageStack(bankStack, preview = detailPreview) { visualBankPage ->
            val topRoute = visualBankPage.lastOrNull() ?: "list"
            if (topRoute.startsWith("detail:")) {
                val detailQuestionId = topRoute.removePrefix("detail:").toLongOrNull() ?: -1L
                val detailEntry = entries.firstOrNull { it.question.id == detailQuestionId }
                CompositionLocalProvider(LocalPageBackPreview provides detailPreview) {
                    val detailBack = unifiedBackRequest(
                        BackState(
                            page = BackPage.CHILD,
                            imeVisible = isBackImeVisible(),
                            topLayer = if (aiEntry != null) BackLayer.Overlay("ordinary-bank-ai") else null,
                        ),
                    ) { decision ->
                        when (decision) {
                            is BackDecision.CloseLayer -> aiTargetQuestionId = -1L
                            BackDecision.PopPage -> bankStack = listOf("list")
                            else -> Unit
                        }
                    }
                    Box(
                        Modifier.fillMaxSize()
                            .graphicsLayer { translationX = size.width * detailPreview.offset },
                    ) {
                        if (detailEntry != null) {
                            BankQuestionDetailOverlay(
                                entry = detailEntry,
                                isFavorite = detailEntry.question.id in manualFavorites,
                                onToggleFavorite = { onToggleFavorite(detailEntry.question.id) },
                                onAskAi = { aiTargetQuestionId = detailEntry.question.id },
                                onBack = detailBack,
                            )
                        }
                    }
                }
            } else {
                CompositionLocalProvider(LocalPageBackPreview provides parentPreview) {
                    val handleListBack = unifiedBackRequest(
                        BackState(
                            page = BackPage.CHILD,
                            imeVisible = isBackImeVisible(),
                            topLayer = if (filterMenuOpen) BackLayer.Overlay("ordinary-bank-filter") else null,
                        ),
                    ) { decision ->
                        when (decision) {
                            is BackDecision.CloseLayer -> closeFilterMenu()
                            BackDecision.PopPage -> onBack()
                            else -> Unit
                        }
                    }
                    SubPageScaffold(
                        title = "浏览题库",
                        onBack = handleListBack,
                        glassBack = true,
                        actionId = "ordinary.bank.back",
                        bottomOverlay = { pageBackdrop ->
                            if (filterMenuOpen) {
                                val progress = filterAnim.value
                    val counts = catalog?.summary?.countsByType.orEmpty()
                    val total = catalog?.summary?.totalBankCount ?: entries.size
                    val rootRect = rootBounds
                    val anchorRect = anchorBounds
                    val topOffsetPx = if (rootRect != null && anchorRect != null) {
                        (anchorRect.bottom - rootRect.top + with(density) { 8.dp.toPx() }).roundToInt().coerceAtLeast(0)
                    } else {
                        with(density) { 160.dp.toPx() }.roundToInt()
                    }
                    val rightInsetDp = if (rootRect != null && anchorRect != null) {
                        with(density) { (rootRect.right - anchorRect.right).coerceAtLeast(16f).toDp() }
                    } else {
                        16.dp
                    }

                    Box(
                        modifier = Modifier.fillMaxSize()
                            .onGloballyPositioned { rootBounds = it.boundsInRoot() }
                            .pointerInput(Unit) { detectTapGestures { closeFilterMenu() } }
                            .testTag("ordinary-bank-filter-scrim"),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .offset { IntOffset(0, topOffsetPx) }
                                .padding(end = rightInsetDp),
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            val scaleY = (0.86f + 0.14f * progress).coerceIn(0.6f, 1.08f)
                            val scaleX = (0.94f + 0.06f * progress).coerceIn(0.8f, 1.04f)
                            val translateY = with(density) { ((1f - progress) * -10.dp.toPx()) }
                            UiPane(
                                role = UiPaneRole.Menu,
                                shape = RoundedCornerShape(18.dp),
                                backdrop = pageBackdrop,
                                modifier = Modifier
                                    .widthIn(min = 210.dp, max = 248.dp)
                                    .graphicsLayer {
                                        transformOrigin = TransformOrigin(0.85f, 0f)
                                        this.scaleX = scaleX
                                        this.scaleY = scaleY
                                        this.translationY = translateY
                                        this.alpha = progress.coerceIn(0f, 1f)
                                    }
                                    .pointerInput(Unit) { detectTapGestures { } }
                                    .testTag("ordinary-bank-filter-menu"),
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    BankTypeFilter.entries.forEach { option ->
                                        val count = when (option) {
                                            BankTypeFilter.ALL -> total
                                            else -> counts[option.key] ?: entries.count { it.question.type == option.key }
                                        }
                                        val selected = option == selectedFilter
                                        val rowBg = if (selected) UiRoles.accent.copy(alpha = 0.14f) else Color.Transparent
                                        Row(
                                            modifier = Modifier.fillMaxWidth()
                                                .background(rowBg, RoundedCornerShape(12.dp))
                                                .clickable(role = Role.RadioButton) {
                                                    selectedTypeName = option.name
                                                    closeFilterMenu()
                                                    scope.launch { listState.scrollToItem(0) }
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                                .testTag("ordinary-bank-filter-${option.key}"),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "${option.label}（$count）",
                                                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                                color = if (selected) UiRoles.accent else UiRoles.foreground,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                            if (selected) {
                                                Icon(
                                                    imageVector = UiIcons.Check,
                                                    contentDescription = "已选中",
                                                    tint = UiRoles.accent,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .padding(top = GlassPageBarHeight)
                    .testTag("ordinary-bank-screen"),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().testTag("ordinary-bank-search"),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        leadingIcon = { Icon(UiIcons.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                UiAction(
                                    label = "清空",
                                    onClick = { searchQuery = "" },
                                    horizontalPadding = 8.dp,
                                    actionId = "ordinary.bank.search.clear",
                                )
                            }
                        },
                        placeholder = { Text("搜索题干、知识点、章节或 #题号") },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val totalCount = catalog?.summary?.totalBankCount ?: entries.size
                        Text(
                            text = if (selectedFilter == BankTypeFilter.ALL && searchQuery.isBlank()) {
                                "共 $totalCount 题（来件 60 · 原创 140）"
                            } else {
                                "显示 ${filteredEntries.size} / $totalCount 题"
                            },
                            modifier = Modifier.testTag("ordinary-bank-count"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val filterLabel = if (selectedFilter == BankTypeFilter.ALL) {
                            "筛选题型"
                        } else {
                            "筛选 · ${selectedFilter.label}"
                        }
                        UiAction(
                            label = filterLabel,
                            trailingIcon = UiIcons.ChevronDown,
                            onClick = {
                                focusManager.clearFocus()
                                if (filterMenuOpen) closeFilterMenu()
                                else {
                                    filterMenuClosing = false
                                    filterMenuOpen = true
                                }
                            },
                            filled = selectedFilter != BankTypeFilter.ALL,
                            horizontalPadding = 12.dp,
                            modifier = Modifier
                                .onGloballyPositioned { anchorBounds = it.boundsInRoot() }
                                .testTag("ordinary-bank-filter"),
                            actionId = "ordinary.bank.filter",
                        )
                    }
                }

                when {
                    loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("正在读取内置题库…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    loadError != null -> {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        }
                    }

                    filteredEntries.isEmpty() -> {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("未找到匹配的题目", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "可尝试更换关键词或切换到「全部题型」",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().testTag("ordinary-bank-list"),
                            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp + dockBottomInset),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                                items(filteredEntries, key = { it.question.id }) { entry ->
                                    BankQuestionRow(
                                        entry = entry,
                                        isFavorite = entry.question.id in manualFavorites,
                                        onClick = { bankStack = listOf("list", "detail:${entry.question.id}") },
                                    )
                                }
                            }
                        }
                    }
                }
            }
                }
            }
        }

        val boundAiSnapshot = remember(aiEntry) {
            aiEntry?.let { entry ->
                OrdinaryQuestionAiContext.fromQuestion(
                    question = entry.question,
                    phase = if (entry.inActivePaper) {
                        OrdinaryQuestionAiPhase.BANK_ACTIVE_HIDDEN
                    } else {
                        OrdinaryQuestionAiPhase.BANK_OPEN
                    },
                    sourceLabel = buildString {
                        append("题库浏览")
                        entry.provenance?.let { append(" · #${it.designId}") }
                    },
                    provenance = entry.provenance,
                )
            }
        }
        OrdinaryQuestionAiWindow(
            visible = boundAiSnapshot != null,
            snapshot = boundAiSnapshot,
            returnLabel = "返回题库",
            onDismiss = { aiTargetQuestionId = -1L },
        )
    }
}

@Composable
private fun BankQuestionRow(
    entry: OrdinaryBankEntry,
    isFavorite: Boolean,
    onClick: () -> Unit,
) {
    val question = entry.question
    val provenance = entry.provenance
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("ordinary-bank-item-${provenance?.designId ?: question.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = UiRoles.accent.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = "${ordinaryTypeLabel(question.type)} · ${question.score}分",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = UiRoles.accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = ordinaryOriginSummary(question.id, provenance),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isFavorite) {
                    Icon(
                        imageVector = UiIcons.FavoriteFilled,
                        contentDescription = "已收藏",
                        tint = UiRoles.accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text = question.stem,
                style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                color = UiRoles.foreground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = listOfNotNull(
                        provenance?.chapterTitle?.takeIf { it.isNotBlank() },
                        question.knowledgePoint?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.inActivePaper) {
                    Text(
                        text = "当前卷作答中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BankQuestionDetailOverlay(
    entry: OrdinaryBankEntry,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onAskAi: () -> Unit,
    onBack: () -> Unit,
) {
    val question = entry.question
    val provenance = entry.provenance
    Surface(
        modifier = Modifier.fillMaxSize().testTag("ordinary-bank-detail"),
        color = UiRoles.surface(UiSurfaceRole.Page),
    ) {
        SubPageScaffold(
            title = provenance?.let { "第 ${it.designId} 题" } ?: "题目详情",
            onBack = onBack,
            glassBack = true,
            actionId = "ordinary.bank.detail.back",
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .padding(top = GlassPageBarHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${ordinaryTypeLabel(question.type)} · ${question.score} 分",
                            modifier = Modifier.semantics { heading() },
                            style = UiRoles.text(MaterialTheme.typography.titleMedium),
                            color = UiRoles.accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = ordinaryOriginSummary(question.id, provenance),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UiAction(
                            label = if (isFavorite) "已收藏" else "收藏",
                            icon = if (isFavorite) UiIcons.FavoriteFilled else UiIcons.Favorite,
                            onClick = onToggleFavorite,
                            filled = isFavorite,
                            horizontalPadding = 10.dp,
                            modifier = Modifier.testTag("ordinary-bank-detail-favorite"),
                            actionId = "ordinary.bank.detail.favorite",
                        )
                        UiAction(
                            label = "问 AI",
                            icon = UiIcons.Ai,
                            onClick = onAskAi,
                            horizontalPadding = 10.dp,
                            modifier = Modifier.testTag("ordinary-bank-detail-ask-ai"),
                            actionId = "ordinary.bank.detail.ai",
                        )
                    }
                }

                ordinaryReviewBadge(provenance)?.let { badge ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = "$badge（保留题库状态标识）",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = question.stem,
                            style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                            color = UiRoles.foreground,
                        )
                        if (question.type == "CHOICE") {
                            OrdinaryTestRules.choiceOptions(question.options).forEach { (key, value) ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = UiRoles.surface(UiSurfaceRole.Block),
                                ) {
                                    Text(
                                        text = "$key. $value",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = UiRoles.foreground,
                                    )
                                }
                            }
                        } else if (question.type == "FILL") {
                            Text(
                                text = "共 ${ordinaryFillBlankCount(question)} 个填空空位",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (entry.inActivePaper) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag("ordinary-bank-active-hidden"),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            text = "该题属于当前未交卷的随机测试试卷，作答期间暂不显示参考答案与解析，交卷后自动解锁。",
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("ordinary-bank-solution"),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "参考答案：${formatOrdinaryOfficialAnswer(question)}",
                                style = UiRoles.text(MaterialTheme.typography.titleSmall),
                                color = UiRoles.accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                            formatOrdinaryFillSynonyms(question).forEach { line ->
                                Text(line, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (question.type == "ESSAY" && ContentPackageQuestionGrading.hasEnvelope(question)) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Text("得分要点与权重：", style = MaterialTheme.typography.labelLarge)
                                ContentPackageQuestionGrading.displayLines(question).forEach { point ->
                                    Text("• $point", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text("解析", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = question.analysis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = UiRoles.foreground,
                            )
                            listOfNotNull(
                                provenance?.chapterTitle?.let { "章节：$it" },
                                question.knowledgePoint?.takeIf { it.isNotBlank() }?.let { "知识点：$it" },
                            ).forEach { metaLine ->
                                Text(
                                    text = metaLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp + LocalMainDockBottomInset.current))
            }
        }
    }
}

private fun filterOpenBounceEasing(): androidx.compose.animation.core.Easing {
    val dampingRatio = 0.72f
    val omega = 28f
    val omegaD = omega * kotlin.math.sqrt(1f - dampingRatio * dampingRatio)
    val durationSec = 0.19f
    val rawEnd = 1f - exp(-dampingRatio * omega * durationSec) * (
        cos(omegaD * durationSec) +
            (dampingRatio / kotlin.math.sqrt(1f - dampingRatio * dampingRatio)) * sin(omegaD * durationSec)
        )
    return androidx.compose.animation.core.Easing { fraction ->
        if (fraction <= 0f) return@Easing 0f
        if (fraction >= 1f) return@Easing 1f
        val t = fraction * durationSec
        val value = 1f - exp(-dampingRatio * omega * t) * (
            cos(omegaD * t) +
                (dampingRatio / kotlin.math.sqrt(1f - dampingRatio * dampingRatio)) * sin(omegaD * t)
            )
        value / rawEnd
    }
}
