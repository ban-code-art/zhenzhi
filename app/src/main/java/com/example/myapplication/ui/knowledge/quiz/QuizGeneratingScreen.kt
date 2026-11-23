package com.example.myapplication.ui.knowledge.quiz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.knowledge.course.CourseScreenHeader
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuizGeneratingScreen(
    state: QuizGenerationUi,
    onCancel: () -> Unit,
    onBackToReading: () -> Unit,
    onRetry: () -> Unit,
    onStartAnswer: () -> Unit,
    onOpenReview: (String) -> Unit = {},
    onOpenPractice: (String) -> Unit = {},
    opening: Boolean = false,
    openingError: String? = null,
) {
    val draftPaper = state.paperId.takeIf { state.errorCode == "REVIEW_REQUIRED" }
    val requestBack = quizPageBack(onBackToReading)
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = UiRoles.surface(UiSurfaceRole.Page),
        topBar = {
            CourseScreenHeader(
                title = if (draftPaper != null) "学习草稿已生成" else "生成随堂测",
                subtitle = state.sourceLabel,
                onBack = requestBack,
                backTag = "quiz-generation-back",
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = when {
                    draftPaper != null -> "题目与原文依据已准备好。可先自测，或逐题核对后创建个人试卷。"
                    state.ready -> "已生成 ${state.questionCount} 题，满分 ${state.maxScore}。"
                    else -> state.message
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!state.terminal) {
                val progress = state.progressPercent
                if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
            }
            QuizGenerationStageUi.TIMELINE.forEach { stage ->
                val reached = state.ready || (draftPaper != null && stage.ordinal <= QuizGenerationStageUi.VALIDATING.ordinal) ||
                    (!state.terminal && stage.ordinal <= state.stage.ordinal)
                val isCurrent = stage == state.stage
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (reached) "●" else "○", color = if (isCurrent) UiRoles.accent else colors.onSurfaceVariant)
                    Text(
                        stage.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (reached) UiRoles.foreground else colors.onSurfaceVariant,
                    )
                }
            }
            if (state.cancelling) Text("正在取消生成…", style = MaterialTheme.typography.bodySmall)
            if (!state.terminal) Text("可先返回阅读，生成任务仍会继续。", style = MaterialTheme.typography.bodySmall)
            if (draftPaper == null && state.errorCode != null) {
                Text("生成未完成（${state.errorCode}）", color = colors.error, style = MaterialTheme.typography.bodySmall)
            }
            listOfNotNull(state.actionError, openingError).forEach { Text(it, color = colors.error) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    draftPaper != null -> {
                        UiAction("开始学习自测", onClick = { onOpenPractice(draftPaper) }, filled = true,
                            actionId = "quiz.generation.practice")
                        UiAction("核对草稿", onClick = { onOpenReview(draftPaper) }, actionId = "quiz.generation.review")
                    }
                    state.ready -> UiAction("开始答题", onClick = onStartAnswer, filled = true, busy = opening,
                        actionId = "quiz.generation.start")
                    state.retryable -> UiAction("重试生成", onClick = onRetry, filled = true, actionId = "quiz.generation.retry")
                    state.stage in setOf(QuizGenerationStageUi.CANCELLED, QuizGenerationStageUi.UNKNOWN) ->
                        UiAction("重新生成", onClick = onRetry, filled = true, actionId = "quiz.generation.retry")
                    state.canCancel -> UiAction("取消生成", onClick = onCancel, foreground = colors.error,
                        actionId = "quiz.generation.cancel")
                }
                UiAction("回阅读", onClick = requestBack, actionId = "quiz.generation.back")
            }
        }
    }
}

/** 路由壳（真实端口）：生成 READY 后经端口打开首次作答，不在 UI 侧造 attempt。 */
@Composable
fun QuizGeneratingRoute(
    documentId: String,
    sectionId: String?,
    onBackToReading: () -> Unit,
    onOpenAnswer: (attemptId: String) -> Unit,
    onOpenReview: (String) -> Unit = {},
    onOpenPractice: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as com.example.myapplication.LearningApplication).container }
    val identity by container.sessionManager.identity.collectAsState(com.example.myapplication.data.session.SessionIdentity(null, -1))
    val factory = remember(documentId, sectionId, identity) {
        QuizViewModelFactory(context.applicationContext, documentId, sectionId, autoStartGeneration = identity.userId != null)
    }
    val viewModel: QuizViewModel = viewModel(key = "quiz-generate-$documentId-$sectionId-${identity.userId}-${identity.epoch}", factory = factory)
    val state by viewModel.generation.collectAsState()
    val openState by viewModel.saveState.collectAsState()
    QuizGeneratingScreen(
        state = state,
        opening = openState.saving,
        openingError = openState.error,
        onOpenReview = onOpenReview,
        onOpenPractice = onOpenPractice,
        onCancel = viewModel::cancelGeneration,
        onBackToReading = onBackToReading,
        onRetry = viewModel::startGeneration,
        onStartAnswer = {
            val paper = state.paperId
            if (paper != null) viewModel.openPaper(paper, onOpened = onOpenAnswer) else onBackToReading()
        },
    )
}

/**
 * 重新生成（新卷）：用原 paper 的 scope 起新的生成运行（与“重练原卷”区分）。
 * 不复用旧 paper/attempt；成功后走同一答题页。
 */
@Composable
fun QuizRegeneratingRoute(
    sourcePaperId: String,
    onBackToReading: () -> Unit,
    onOpenAnswer: (attemptId: String) -> Unit,
    onOpenReview: (String) -> Unit = {},
    onOpenPractice: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as com.example.myapplication.LearningApplication).container }
    val identity by container.sessionManager.identity.collectAsState(com.example.myapplication.data.session.SessionIdentity(null, -1))
    val factory = remember(sourcePaperId, identity) {
        QuizViewModelFactory(context.applicationContext, documentId = "", sectionId = null, autoStartGeneration = false)
    }
    val viewModel: QuizViewModel = viewModel(key = "quiz-regenerate-$sourcePaperId-${identity.userId}-${identity.epoch}", factory = factory)
    val state by viewModel.generation.collectAsState()
    val openState by viewModel.saveState.collectAsState()
    LaunchedEffect(sourcePaperId, viewModel, identity) { if (identity.userId != null) viewModel.ensureRegeneration(sourcePaperId) }
    QuizGeneratingScreen(
        state = state,
        opening = openState.saving,
        openingError = openState.error,
        onOpenReview = onOpenReview,
        onOpenPractice = onOpenPractice,
        onCancel = viewModel::cancelGeneration,
        onBackToReading = onBackToReading,
        onRetry = { viewModel.startRegeneration(sourcePaperId) },
        onStartAnswer = {
            val paper = state.paperId
            if (paper != null) viewModel.openPaper(paper, onOpened = onOpenAnswer) else onBackToReading()
        },
    )
}
