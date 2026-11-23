package com.example.myapplication.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.learning.OrdinaryQuestionAiContext
import com.example.myapplication.data.learning.OrdinaryQuestionAiPhase
import com.example.myapplication.data.local.dao.QuestionDao
import com.example.myapplication.data.reader.content.ContentPackageQuestionGrading
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.ui.ai.AgentSourceUi
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiAlertDecision
import com.example.myapplication.ui.components.UiAlertHost
import com.example.myapplication.ui.components.UiConfirmDialog
import com.example.myapplication.ui.knowledge.quiz.OrdinaryQuestionAiWindow
import com.example.myapplication.ui.knowledge.review.ReviewQuickAddAction
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.LocalPageBackPreview
import com.example.myapplication.ui.navigation.PageBackPreview
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.test.TestScreen
import com.example.myapplication.ui.test.formatOrdinaryOfficialAnswer
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Composable
fun FavoritesScreen(
    title: String,
    source: String,
    onBack: () -> Unit,
    onOpenSource: (AgentSourceUi) -> Unit = {},
    initialReturnPosition: String = "{}",
    onSourceCheckpoint: (String) -> Unit = {},
    onOpenSourcePracticeArchive: (String, String?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val container = (context.applicationContext as LearningApplication).container
    val identity by container.sessionManager.identity.collectAsState(initial = null)
    val account = identity
    val factory = remember(source, account) { FavoritesViewModelFactory(context.applicationContext, source) }
    val viewModel: FavoritesViewModel = viewModel(key = "favorites_$source-${account?.userId}-${account?.epoch}", factory = factory)
    val items by viewModel.items.collectAsState()
    val error by viewModel.error.collectAsState()
    val removingId by viewModel.removingQuestionId.collectAsState()
    val removeError by viewModel.removeError.collectAsState()
    val manualFavorites by remember(container, account?.userId) {
        account?.userId?.let { container.ordinaryTestService.observeManualFavorites(it) } ?: flowOf(emptySet())
    }.collectAsState(emptySet())
    val archiveFlow = remember(container) { container.sourcePracticeArchiveRepository.observeRecords() }
    val practiceRecords by archiveFlow.collectAsState(emptyList())
    val practiceFavorites = remember(practiceRecords, account, source) {
        if (source != "MANUAL") emptyList() else practiceRecords.filter { it.ownerUserId == account?.userId }
            .flatMap { record -> record.favoriteQuestions.map { record to it } }.distinctBy { it.second.id }
    }
    val restored = remember(initialReturnPosition, source) {
        runCatching { Json.parseToJsonElement(initialReturnPosition).jsonObject }.getOrNull()
            ?.takeIf { it["source"]?.jsonPrimitive?.contentOrNull?.let { saved -> saved == source } != false }
    }
    var selectedId by rememberSaveable(source, account, initialReturnPosition) {
        mutableStateOf(restored?.get("questionId")?.jsonPrimitive?.longOrNull)
    }
    var confirmRemoval by rememberSaveable(source, account, initialReturnPosition, selectedId) { mutableStateOf(false) }
    var aiQuestionId by rememberSaveable(source, account) { mutableLongStateOf(-1L) }
    var viewingAnswerLocation by remember { mutableStateOf<QuestionDao.SubmittedAnswerLocation?>(null) }
    val recordViewerPreview = remember { PageBackPreview() }
    LaunchedEffect(viewingAnswerLocation) { recordViewerPreview.settleAfterNavigation() }
    viewingAnswerLocation?.let { loc ->
        CompositionLocalProvider(LocalPageBackPreview provides recordViewerPreview) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = size.width * recordViewerPreview.offset }) {
                TestScreen(
                    testId = loc.testId,
                    initialOrdinal = loc.orderIndex,
                    onBack = { viewingAnswerLocation = null },
                    onOpenSource = onOpenSource,
                    initialReturnPosition = initialReturnPosition,
                    onSourceCheckpoint = onSourceCheckpoint,
                )
            }
        }
        return
    }
    val selected = items.firstOrNull { it.question.id == selectedId }
    val scope = rememberCoroutineScope()

    var selectedRecordLocation by remember(selectedId, account, source) {
        mutableStateOf<QuestionDao.SubmittedAnswerLocation?>(null)
    }
    LaunchedEffect(selected?.question?.id, account?.userId, source) {
        val qId = selected?.question?.id
        val uid = account?.userId
        selectedRecordLocation = if (source == "WRONG" && qId != null && uid != null) {
            runCatching { container.ordinaryTestService.findLatestSubmittedLocation(uid, qId, account) }.getOrNull()
        } else {
            null
        }
    }

    val listState = key(source, account, initialReturnPosition) {
        rememberLazyListState(
            restored?.get("listIndex")?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0,
            restored?.get("listOffset")?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0,
        )
    }
    val back = unifiedBackRequest(BackState(page = BackPage.CHILD, imeVisible = isBackImeVisible())) { decision ->
        if (decision == BackDecision.PopPage) onBack()
    }
    SubPageScaffold(title = title, onBack = back, glassBack = true, actionId = "favorites.back") {
        Column(Modifier.fillMaxSize().padding(top = GlassPageBarHeight)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (items.isEmpty() && practiceFavorites.isEmpty()) {
                EmptyHint(if (source == "WRONG") "暂无错题" else "暂无收藏")
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("favorites-list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (practiceFavorites.isNotEmpty()) {
                        item(key = "practice-favorites-heading") {
                            Text(
                                "随堂测收藏",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        items(practiceFavorites, key = { "practice-favorite-${it.second.id}" }) { (record, question) ->
                            Card(
                                onClick = { onOpenSourcePracticeArchive(record.id, question.id) },
                                modifier = Modifier.fillMaxWidth().testTag("favorite-practice-${question.id}"),
                                colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(record.title, style = MaterialTheme.typography.labelMedium, color = UiRoles.accent)
                                    Text(question.stem, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        record.practice.origin.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    items(items, key = { it.question.id }) { item ->
                        FavoriteCard(item = item, onClick = {
                            viewModel.clearRemoveError()
                            selectedId = item.question.id
                        })
                    }
                }
            }
        }
    }

    if (selected != null && account?.userId != null) {
        val questionId = selected.question.id
        val wrong = source == "WRONG"
        val removeLabel = if (wrong) "移除错题" else "取消收藏"
        val ownerKey = Triple(source, account, questionId)
        val isManuallyFavorited = questionId in manualFavorites
        key(ownerKey) {
            val detailScroll = rememberScrollState(
                if (restored?.get("questionId")?.jsonPrimitive?.longOrNull == questionId)
                    restored?.get("detailScroll")?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0 else 0,
            )
            FavoriteDetails(
                item = selected,
                account = account,
                ownerKey = ownerKey,
                wrong = wrong,
                isManuallyFavorited = isManuallyFavorited,
                canOpenRecord = selectedRecordLocation != null,
                removeLabel = removeLabel,
                removing = removingId == questionId,
                removeError = removeError,
                detailScroll = detailScroll,
                onDismiss = { selectedId = null },
                onAskRemoval = {
                    viewModel.clearRemoveError()
                    confirmRemoval = true
                },
                onToggleManualFavorite = {
                    val uid = account.userId ?: return@FavoriteDetails
                    scope.launch {
                        runCatching {
                            container.ordinaryTestService.setManualFavorite(uid, questionId, !isManuallyFavorited, account)
                        }
                    }
                },
                onAskAi = { aiQuestionId = questionId },
                onOpenRecord = {
                    selectedRecordLocation?.let { loc ->
                        viewingAnswerLocation = loc
                    }
                },
                onOpenSource = { itemSource ->
                    onSourceCheckpoint(buildJsonObject {
                        put("questionId", questionId)
                        put("source", source)
                        put("listIndex", listState.firstVisibleItemIndex)
                        put("listOffset", listState.firstVisibleItemScrollOffset)
                        put("detailScroll", detailScroll.value)
                    }.toString())
                    onOpenSource(itemSource)
                },
            )
            if (confirmRemoval) {
                UiConfirmDialog(
                    title = "确认$removeLabel",
                    message = if (wrong) "将这道题移出错题本，已保存的作答、原成绩和手动收藏仍会保留。"
                    else "将这道题移出你的收藏，可在题目中再次收藏。",
                    confirmLabel = removeLabel,
                    dismissLabel = "取消",
                    ownerKey = ownerKey,
                    onDismiss = { confirmRemoval = false },
                    onConfirm = {
                        confirmRemoval = false
                        viewModel.remove(selected, account) {
                            if (selectedId == questionId) selectedId = null
                        }
                    },
                    actionId = "favorites.remove.confirm",
                )
            }
        }
    }

    val aiTarget = items.firstOrNull { it.question.id == aiQuestionId }
    val aiSnapshot = remember(aiTarget, source) {
        aiTarget?.let {
            OrdinaryQuestionAiContext.fromQuestion(
                question = it.question,
                phase = OrdinaryQuestionAiPhase.BANK_OPEN,
                sourceLabel = if (source == "WRONG") "错题本" else "收藏题目",
            )
        }
    }
    OrdinaryQuestionAiWindow(
        visible = aiSnapshot != null,
        snapshot = aiSnapshot,
        returnLabel = if (source == "WRONG") "返回错题本" else "返回收藏",
        onDismiss = { aiQuestionId = -1L },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavoriteDetails(
    item: FavoriteItem,
    account: SessionIdentity?,
    ownerKey: Any,
    wrong: Boolean,
    isManuallyFavorited: Boolean,
    canOpenRecord: Boolean,
    removeLabel: String,
    removing: Boolean,
    removeError: String?,
    detailScroll: androidx.compose.foundation.ScrollState,
    onDismiss: () -> Unit,
    onAskRemoval: () -> Unit,
    onToggleManualFavorite: () -> Unit,
    onAskAi: () -> Unit,
    onOpenRecord: () -> Unit,
    onOpenSource: (AgentSourceUi) -> Unit,
) {
    val title = typeLabel(item.question.type)
    val question = item.question
    val colors = MaterialTheme.colorScheme
    val decision = remember(ownerKey) { UiAlertDecision(ownerKey) }
    val exit = decision.exit
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .52f
    UiAlertHost(
        title = title,
        ownerKey = ownerKey,
        onDismissRequest = { scale, offset -> decision.request(false, scale, offset) },
        exitRequested = exit != null,
        frozenScale = exit?.scale,
        frozenPull = exit?.pull,
        onVisibilitySettled = { if (decision.consume(ownerKey) == false) onDismiss() },
        modifier = Modifier.testTag("favorite-details-${question.id}"),
    ) { _, scale, offset ->
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(detailScroll).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(question.stem, color = UiRoles.foreground)
            if (!question.sourceExcerpt.isNullOrBlank()) {
                Text("历史出处：${question.sourceExcerpt}", color = colors.onSurfaceVariant)
                val itemSource = item.source
                if (itemSource?.canNavigate == true) {
                    UiAction(
                        label = "查看原文",
                        onClick = { onOpenSource(itemSource) },
                        enabled = !removing,
                        actionId = "favorites.openSource",
                    )
                } else {
                    Text(itemSource?.unavailableReason ?: "来源不可定位，保留历史摘录", color = colors.onSurfaceVariant)
                }
            }
            Text("答案：${formatOrdinaryOfficialAnswer(question)}", color = UiRoles.accent)
            if (question.type == "ESSAY" && ContentPackageQuestionGrading.hasEnvelope(question)) {
                ContentPackageQuestionGrading.displayLines(question).forEach { line ->
                    Text("• $line", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if (question.analysis.isNotBlank()) Text("解析：${question.analysis}", color = colors.onSurfaceVariant)
            if (!question.knowledgePoint.isNullOrBlank()) Text("知识点：${question.knowledgePoint}", color = colors.onSurfaceVariant)
            if (question.answer.isNotBlank() && account != null) {
                val revision = question.sourceRevision ?: question.createdAt.toString()
                ReviewQuickAddAction(
                    sourceKey = question.id to revision,
                    tag = "review-quick-add-question",
                    enabled = exit == null && !removing,
                    expectedIdentity = account,
                    inheritedPane = true,
                ) { identity -> quickQuestion(identity, question.id, revision) }
            }
            removeError?.let { Text(it, color = colors.error) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (wrong) {
                UiAction(
                    label = if (isManuallyFavorited) "已收藏" else "收藏",
                    icon = if (isManuallyFavorited) UiIcons.FavoriteFilled else UiIcons.Favorite,
                    onClick = onToggleManualFavorite,
                    enabled = exit == null && !removing,
                    filled = isManuallyFavorited,
                    inheritedPane = true,
                    modifier = Modifier.testTag("favorites-wrong-manual-toggle"),
                    actionId = "favorites.wrong.favorite",
                )
            }
            UiAction(
                label = "问 AI",
                icon = UiIcons.Ai,
                onClick = onAskAi,
                enabled = exit == null && !removing,
                inheritedPane = true,
                modifier = Modifier.testTag("favorites-ask-ai"),
                actionId = "favorites.askAi",
            )
            if (wrong && canOpenRecord) {
                UiAction(
                    label = "查看该次作答",
                    onClick = onOpenRecord,
                    enabled = exit == null && !removing,
                    inheritedPane = true,
                    modifier = Modifier.testTag("favorites-open-record"),
                    actionId = "favorites.openRecord",
                )
            }
            UiAction(
                label = removeLabel,
                onClick = onAskRemoval,
                enabled = exit == null && !removing,
                busy = removing,
                foreground = UiRoles.extended.destructive,
                inheritedPane = true,
                actionId = "favorites.remove",
            )
            UiAction(
                label = "知道了",
                onClick = { decision.request(false, scale(), offset()) },
                enabled = exit == null,
                inheritedPane = true,
                actionId = "favorites.dismissDetails",
                modifier = Modifier.testTag("favorites-detail-close"),
            )
        }
    }
}

@Composable
private fun FavoriteCard(item: FavoriteItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("favorite-question-${item.question.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(typeLabel(item.question.type), style = MaterialTheme.typography.bodySmall, color = UiRoles.accent, fontWeight = FontWeight.Medium)
                Text(formatDateTime(item.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(item.question.stem, style = MaterialTheme.typography.bodyLarge, color = UiRoles.foreground)
        }
    }
}
