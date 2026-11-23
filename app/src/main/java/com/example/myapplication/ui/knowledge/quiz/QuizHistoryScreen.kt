package com.example.myapplication.ui.knowledge.quiz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.knowledge.course.CourseScreenHeader
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import com.example.myapplication.data.quiz.SourcePracticeRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史页（设计 §9）：
 *  - paper + attempt 分别分页展示；
 *  - “重练原卷”（固定 item snapshot，不调用 AI）与“重新生成”（新 paper，需要 AI）分开；
 *  - 待评不显示为终分；已删除书籍的历史仍可看已保存 quote。
 */
@Composable
fun QuizHistoryScreen(
    state: QuizHistoryUi,
    onBack: () -> Unit,
    onOpenResult: (attemptId: String) -> Unit,
    onRetrain: (paperId: String, attemptId: String) -> Unit,
    onRegenerate: (paperId: String) -> Unit,
    onLoadMore: () -> Unit,
    onOpenReview: (String?) -> Unit = {},
    onOpenAttempt: (String) -> Unit = {},
    sourcePracticeRecords: List<SourcePracticeRecord> = emptyList(),
    onOpenSourcePracticeRecord: (String) -> Unit = {},
    onRetry: () -> Unit = onLoadMore,
    actionsBusy: Boolean = false,
    actionError: String? = null,
) {
    val requestBack = unifiedBackRequest(BackState(page = BackPage.CHILD, imeVisible = isBackImeVisible())) { decision ->
        if (decision == BackDecision.PopPage) onBack()
    }
    val colors = MaterialTheme.colorScheme
    val historyList = rememberLazyListState()
    Scaffold(
        containerColor = UiRoles.surface(UiSurfaceRole.Page),
        topBar = { CourseScreenHeader(title = "随堂测记录", onBack = requestBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            UiAction(
                label = "待核对草稿",
                onClick = { onOpenReview(null) },
                enabled = !actionsBusy,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                actionId = "quizHistory.openDrafts",
            )
            actionError?.let { Text(it, color = colors.error, modifier = Modifier.padding(12.dp)) }
            if (state.items.isEmpty() && sourcePracticeRecords.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    when {
                        state.loading -> {
                            CircularProgressIndicator()
                            Text("正在读取随堂测记录", color = colors.onSurfaceVariant)
                        }
                        state.error != null -> {
                            Text(state.error, color = colors.error)
                            UiAction(label = "重试", onClick = onRetry, actionId = "quizHistory.retry")
                        }
                        state.hasMore -> {
                            Text("这一页没有作答记录", color = colors.onSurfaceVariant)
                            UiAction(label = "加载更多", onClick = onLoadMore, actionId = "quizHistory.loadMore")
                        }
                        else -> {
                            Text("还没有随堂测记录", color = colors.onSurfaceVariant)
                            Text("从课程或阅读页开始随堂测验", style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                        }
                    }
                }
                return@Column
            }

            LazyColumn(
                state = historyList,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("quiz-history-list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sourcePracticeRecords.isNotEmpty()) {
                    item(key = "source-practice-heading") { QuizHistoryHeading("原文与课程随堂测") }
                    items(sourcePracticeRecords, key = { "practice-record-${it.id}" }) { record ->
                        SourcePracticeHistoryRow(
                            record,
                            enabled = !actionsBusy,
                            onOpen = { onOpenSourcePracticeRecord(record.id) },
                        )
                    }
                }
                if (state.items.isNotEmpty()) {
                    item(key = "formal-quiz-heading") { QuizHistoryHeading("历史试卷") }
                }
                items(state.items, key = { "formal-attempt-${it.attemptId}" }) { entry ->
                    HistoryRow(
                        entry = entry,
                        actionsEnabled = !actionsBusy,
                        onOpenResult = { onOpenResult(entry.attemptId) },
                        onOpenAttempt = { onOpenAttempt(entry.attemptId) },
                        onRetrain = { onRetrain(entry.paperId, entry.attemptId) },
                        onRegenerate = { onRegenerate(entry.paperId) },
                    )
                }
                if (state.error != null || state.hasMore || state.loading) {
                    item(key = "history-pagination") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.error?.let { Text(it, color = colors.error) }
                            when {
                                state.error != null -> UiAction(label = "重试", onClick = onRetry,
                                    enabled = !state.loading, actionId = "quizHistory.retry")
                                state.hasMore -> UiAction(label = "加载更多", onClick = onLoadMore,
                                    busy = state.loading, enabled = !state.loading, actionId = "quizHistory.loadMore")
                                state.loading -> Text("正在读取历史试卷", color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuizHistoryHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun SourcePracticeHistoryRow(record: SourcePracticeRecord, enabled: Boolean, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("source-practice-history-${record.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Text(SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(record.createdAt)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${record.practice.origin.label} · 第 ${record.batchNumber} 组 · ${record.questionCount} 题",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { record.submittedCount.toFloat() / record.questionCount.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已提交 ${record.submittedCount}/${record.questionCount}" +
                    if (record.favoriteQuestionIds.isEmpty()) "" else " · 已收藏 ${record.favoriteQuestionIds.size}",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text(if (record.completed) "查看练习" else "继续作答",
                    color = UiRoles.accent, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(
    entry: QuizHistoryItemUi,
    actionsEnabled: Boolean,
    onOpenResult: () -> Unit,
    onOpenAttempt: () -> Unit,
    onRetrain: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = UiRoles.foreground,
                )
                Text(entry.submittedAtLabel, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Text(entry.courseLabel, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.scoreLabel, style = MaterialTheme.typography.labelMedium, color = UiRoles.accent)
                Text(entry.statusLabel, style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                Text(entry.generatedByLabel, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entry.hasInProgressAttempt) {
                    UiAction(label = "继续作答", onClick = onOpenAttempt, enabled = actionsEnabled, actionId = "quizHistory.resume")
                } else {
                    UiAction(label = "查看结果", onClick = onOpenResult, enabled = actionsEnabled, actionId = "quizHistory.openResult")
                }
                UiAction(label = "重练原卷", onClick = onRetrain, enabled = actionsEnabled, actionId = "quizHistory.retrain")
                if (entry.generatedByLabel.contains("AI")) {
                    UiAction(label = "重新生成", onClick = onRegenerate, enabled = actionsEnabled, actionId = "quizHistory.regenerate")
                }
            }
        }
    }
}

@Composable
fun QuizHistoryRoute(
    onBack: () -> Unit,
    onOpenResult: (String) -> Unit,
    onOpenAttempt: (String) -> Unit,
    onRegenerate: (paperId: String) -> Unit,
    onOpenReview: (String?) -> Unit = {},
    onOpenSourcePracticeRecord: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as com.example.myapplication.LearningApplication).container }
    val identity by container.sessionManager.identity.collectAsState(com.example.myapplication.data.session.SessionIdentity(null, -1))
    val factory = remember(identity) { QuizViewModelFactory(context.applicationContext, "", null, autoStartGeneration = false) }
    val viewModel: QuizViewModel = viewModel(key = "quiz-history-${identity.userId}-${identity.epoch}", factory = factory)
    val history by viewModel.history.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    var openingAttempt by remember(viewModel) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(saveState.error) { if (saveState.error != null) openingAttempt = false }
    val archiveFlow = remember(container) { container.sourcePracticeArchiveRepository.observeRecords() }
    val records by archiveFlow.collectAsState(emptyList())
    LaunchedEffect(viewModel, identity) { if (identity.userId != null) viewModel.loadHistory() }
    QuizHistoryScreen(
        state = history,
        onBack = onBack,
        onOpenResult = onOpenResult,
        onRetrain = { paperId, _ ->
            if (!openingAttempt) {
                openingAttempt = true
                viewModel.consumeSaveError()
                viewModel.retrain(paperId) { attemptId ->
                    openingAttempt = false
                    if (lifecycle.currentState == Lifecycle.State.RESUMED) onOpenAttempt(attemptId)
                }
            }
        },
        onRegenerate = onRegenerate,
        onLoadMore = viewModel::loadMoreHistory,
        onOpenReview = onOpenReview,
        onOpenAttempt = onOpenAttempt,
        sourcePracticeRecords = records.filter { it.ownerUserId == identity.userId },
        onOpenSourcePracticeRecord = onOpenSourcePracticeRecord,
        onRetry = { if (history.hasMore) viewModel.loadMoreHistory() else viewModel.loadHistory() },
        actionsBusy = openingAttempt,
        actionError = saveState.error,
    )
}
