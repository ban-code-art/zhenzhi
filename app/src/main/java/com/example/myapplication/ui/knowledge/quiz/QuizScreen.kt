package com.example.myapplication.ui.knowledge.quiz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiConfirmDialog
import com.example.myapplication.ui.components.UiSheet
import com.example.myapplication.ui.components.learning.LearningInputKind
import com.example.myapplication.ui.components.learning.LearningOption
import com.example.myapplication.ui.components.learning.LearningQuestion
import com.example.myapplication.ui.components.learning.LearningQuestionCard
import com.example.myapplication.ui.knowledge.course.CourseScreenHeader
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackLayer
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

@Composable
internal fun quizPageBack(onBack: () -> Unit): () -> Unit =
    unifiedBackRequest(BackState(page = BackPage.CHILD, imeVisible = isBackImeVisible())) { decision ->
        if (decision == BackDecision.PopPage) onBack()
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuizScreen(
    attempt: QuizAttemptUi,
    saveState: QuizSaveStateUi,
    onBack: () -> Unit,
    onSelectOrdinal: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAnswerChange: (String) -> Unit,
    onSave: () -> Unit,
    onSubmit: (acknowledgedUnanswered: Boolean) -> Unit,
) {
    var showAnswerSheet by rememberSaveable(attempt.attemptId) { mutableStateOf(false) }
    var showSubmitDialog by rememberSaveable(attempt.attemptId) { mutableStateOf(false) }
    val question = attempt.currentQuestion ?: return
    val canAnswer = attempt.status == QuizAttemptStatusUi.IN_PROGRESS && !saveState.leaving && !saveState.submitting
    val requestBack = unifiedBackRequest(
        BackState(
            page = BackPage.CHILD,
            imeVisible = isBackImeVisible(),
            topLayer = when {
                showSubmitDialog -> BackLayer.Overlay("quiz-submit")
                showAnswerSheet -> BackLayer.Panel("quiz-answer-sheet")
                else -> null
            },
        ),
    ) { decision ->
        when (decision) {
            is BackDecision.CloseLayer -> if (showSubmitDialog) showSubmitDialog = false else showAnswerSheet = false
            BackDecision.PopPage -> if (!saveState.submitting && !saveState.leaving) onBack()
            else -> Unit
        }
    }

    Scaffold(
        containerColor = UiRoles.surface(UiSurfaceRole.Page),
        topBar = {
            CourseScreenHeader(
                title = attempt.title,
                subtitle = "已答 ${attempt.answeredCount}/${attempt.questionCount} · 有效用时 ${formatElapsed(attempt.activeUsedMs)}",
                onBack = requestBack,
                backTag = "quiz-attempt-back",
            )
        },
        bottomBar = {
            FlowRow(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UiAction("上一题", onClick = onPrevious, enabled = canAnswer && attempt.currentOrdinal > 0,
                    actionId = "quiz.attempt.previous")
                UiAction("下一题", onClick = onNext, enabled = canAnswer && attempt.currentOrdinal < attempt.questions.lastIndex,
                    actionId = "quiz.attempt.next")
                UiAction("答题卡", onClick = { showAnswerSheet = true }, enabled = canAnswer,
                    actionId = "quiz.attempt.answerSheet")
                UiAction("交卷", onClick = { showSubmitDialog = true }, filled = true, enabled = canAnswer,
                    busy = saveState.submitting, actionId = "quiz.attempt.submit")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val typeLabel = if (question.type == QuizItemTypeUi.CHOICE) "选择题" else "简答题"
            Text(
                "第 ${question.ordinal + 1}/${attempt.questionCount} 题 · $typeLabel ${question.maxScore} 分",
                style = MaterialTheme.typography.labelMedium,
            )
            LearningQuestionCard(
                question = LearningQuestion(
                    question.itemId, question.ordinal, question.stem,
                    question.options.map { LearningOption(it.letter, it.text) },
                    if (question.type == QuizItemTypeUi.CHOICE) LearningInputKind.CHOICE else LearningInputKind.TEXT,
                    question.maxScore,
                ),
                answer = question.savedAnswer,
                onAnswerChange = onAnswerChange,
                enabled = canAnswer,
            )
            val status = when {
                saveState.submitting -> "正在保存并交卷…"
                saveState.leaving -> "正在保存作答位置…"
                saveState.saving -> "保存中…"
                saveState.lastSavedOrdinal == question.ordinal && question.answered -> "已保存"
                question.answered -> "未保存"
                else -> null
            }
            UiAction("保存本题", onClick = onSave, enabled = canAnswer && !saveState.saving,
                actionId = "quiz.attempt.save")
            status?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            saveState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (attempt.status != QuizAttemptStatusUi.IN_PROGRESS) {
                Text("本次作答已交卷，不能继续修改。", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showAnswerSheet) {
        UiSheet(
            title = "答题卡",
            onDismiss = { showAnswerSheet = false },
            actions = {
                UiAction("关闭", onClick = { showAnswerSheet = false }, actionId = "quiz.answerSheet.close")
            },
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attempt.questions.forEach { item ->
                    UiAction(
                        label = "${item.ordinal + 1}${if (item.answered) "✓" else "○"}",
                        filled = item.ordinal == attempt.currentOrdinal,
                        onClick = { onSelectOrdinal(item.ordinal); showAnswerSheet = false },
                        actionId = "quiz.answerSheet.question.${item.ordinal}",
                    )
                }
            }
            Text("✓ 已答 · ○ 未答 · 高亮为当前题", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showSubmitDialog) {
        val unanswered = attempt.unansweredCount
        UiConfirmDialog(
            title = "确认交卷",
            message = if (unanswered > 0) "还有 $unanswered 题未作答，未作答按 0 分计。"
                else "已答完 ${attempt.questionCount} 题。",
            confirmLabel = "确认交卷",
            dismissLabel = "继续作答",
            onConfirm = {
                showSubmitDialog = false
                onSubmit(unanswered > 0)
            },
            onDismiss = { showSubmitDialog = false },
            ownerKey = attempt.attemptId,
            canConfirm = canAnswer,
            destructive = false,
            actionId = "quiz.attempt.confirmSubmit",
        )
    }
}

@Composable
fun QuizScreenRoute(
    attemptId: String,
    onBack: () -> Unit,
    onOpenResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as com.example.myapplication.LearningApplication).container
    val identity by container.sessionManager.identity.collectAsState(initial = null)
    val factory = remember(attemptId, identity) { QuizViewModelFactory(context.applicationContext, "", null, autoStartGeneration = false) }
    val viewModel: QuizViewModel = viewModel(key = "quiz-attempt-$attemptId-${identity?.userId}-${identity?.epoch}", factory = factory)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) {
        viewModel.setActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setActive(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> viewModel.setActive(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); viewModel.setActive(false) }
    }
    val attempt by viewModel.attempt.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    LaunchedEffect(attemptId, identity) { if (identity?.userId != null) viewModel.loadAttempt(attemptId) }
    LaunchedEffect(attempt?.status) {
        val loaded = attempt
        if (loaded != null && loaded.status != QuizAttemptStatusUi.IN_PROGRESS) onOpenResult(loaded.attemptId)
    }
    val loaded = attempt
    if (loaded == null) {
        val requestBack = quizPageBack(onBack)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (saveState.error == null) Text("正在读取作答…", color = UiRoles.foreground)
                saveState.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                    UiAction("重试", onClick = { viewModel.loadAttempt(attemptId) }, actionId = "quiz.attempt.retry")
                }
                UiAction("返回", onClick = requestBack, actionId = "quiz.attempt.back")
            }
        }
        return
    }
    QuizScreen(
        attempt = loaded,
        saveState = saveState,
        onBack = { viewModel.leave(onBack) },
        onSelectOrdinal = viewModel::selectOrdinal,
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        onAnswerChange = viewModel::updateAnswer,
        onSave = { viewModel.saveCurrentAnswer() },
        onSubmit = viewModel::submit,
    )
}

internal fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
