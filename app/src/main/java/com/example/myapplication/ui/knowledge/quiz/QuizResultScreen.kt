package com.example.myapplication.ui.knowledge.quiz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.ai.AgentSourceUi
import com.example.myapplication.ui.theme.ReaderTokens
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiConfirmDialog
import com.example.myapplication.ui.components.UiSheet
import com.example.myapplication.ui.knowledge.course.CourseScreenHeader
import com.example.myapplication.ui.components.learning.*
import com.example.myapplication.ui.components.rememberAdaptiveWindowInfo
import kotlinx.serialization.json.*

/**
 * 结果页（设计 §9/§16/§18）：
 *  - 有 pending 时显示“已评 X · 待自评 N”，**不把 NULL 当 0 宣称最终分**；
 *  - 逐题显示我的答案/参考答案/rubric/解析/知识点/出处；
 *  - 待评题提供自评入口；来源失效仍显示历史 quote；
 *  - “重练原卷”与“重新生成”分开（前者固定 item snapshot，后者新 paper）；
 *  - 已在错题本（favorites.WRONG）的题显示状态并提供“移除”，移除前必须二次确认；
 *    修分改对**不会**自动删除历史错题（设计 §16），也不会影响总分。
 */
@Composable
fun QuizResultScreen(
    result: QuizResultUi,
    onBack: () -> Unit,
    onSelfGrade: (itemId: String, scoreText: String, note: String) -> Unit,
    onRetrain: (paperId: String) -> Unit,
    onRegenerate: (paperId: String) -> Unit,
    onOpenSource: (AgentSourceUi) -> Unit,
    onRemoveWrong: (QuizQuestionUi) -> Unit = {},
    onToggleFavorite: (QuizQuestionUi) -> Unit = {},
    onReviewAi: (String) -> Unit = {},
    onAdoptAi: (String) -> Unit = {},
    initialReturnPosition: String = "{}",
    onSourceCheckpoint: (String) -> Unit = {},
    saveState: QuizSaveStateUi = QuizSaveStateUi(),
) {
    val requestBack = quizPageBack(onBack)
    if (result.status == QuizAttemptStatusUi.IN_PROGRESS) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("本次作答尚未交卷，请继续作答后查看解析。")
            UiAction("返回", onClick = requestBack, actionId = "quiz.result.back")
        }
        return
    }
    val restored = remember(result.attemptId, initialReturnPosition) {
        runCatching { Json.parseToJsonElement(initialReturnPosition).jsonObject }
            .getOrNull()?.takeIf { it["attemptId"]?.jsonPrimitive?.contentOrNull == result.attemptId }
    }
    val savedScroll = restored?.get("scroll")?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0
    val scrollState = rememberScrollState(savedScroll)
    var gradeTargetId by rememberSaveable(result.attemptId) { mutableStateOf<String?>(null) }
    var removeTargetId by rememberSaveable(result.attemptId) { mutableStateOf<String?>(null) }
    var scoreText by rememberSaveable(result.attemptId) { mutableStateOf("") }
    var note by rememberSaveable(result.attemptId) { mutableStateOf("") }
    var gradeSaving by remember(result.attemptId) { mutableStateOf(false) }
    val gradeTarget = result.questions.firstOrNull { it.itemId == gradeTargetId }
    val removeTarget = result.questions.firstOrNull { it.itemId == removeTargetId }
    val adaptive = rememberAdaptiveWindowInfo()
    LaunchedEffect(gradeTarget?.gradeStatus, saveState.saving, saveState.error) {
        if (gradeTarget != null && gradeTarget.gradeStatus != QuizItemGradeStatusUi.PENDING_SELF_REVIEW) {
            gradeTargetId = null
            scoreText = ""
            note = ""
            gradeSaving = false
        } else if (!saveState.saving && saveState.error != null) {
            gradeSaving = false
        }
    }

    Scaffold(
        containerColor = UiRoles.surface(UiSurfaceRole.Page),
        topBar = {
            CourseScreenHeader(title = "随堂测结果", onBack = requestBack, backTag = "quiz-result-back")
        },
        bottomBar = {
            if (adaptive.preferStackedControls) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    UiAction("重练原卷", onClick = { onRetrain(result.paperId) }, filled = true,
                        enabled = !saveState.saving, modifier = Modifier.fillMaxWidth(), actionId = "quiz.result.retrain")
                    UiAction("重新生成（新卷）", onClick = { onRegenerate(result.paperId) },
                        enabled = !saveState.saving, modifier = Modifier.fillMaxWidth(), actionId = "quiz.result.regenerate")
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UiAction("重练原卷", onClick = { onRetrain(result.paperId) }, filled = true,
                        enabled = !saveState.saving, modifier = Modifier.weight(1f), actionId = "quiz.result.retrain")
                    UiAction("重新生成（新卷）", onClick = { onRegenerate(result.paperId) },
                        enabled = !saveState.saving, modifier = Modifier.weight(1f), actionId = "quiz.result.regenerate")
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
          Column(
            modifier = Modifier.widthIn(max = adaptive.contentMaxWidth).fillMaxWidth()
                .verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            if (saveState.saving) Text("正在更新…", color = ReaderTokens.TextSecondary)
            saveState.error?.let { Text(it, color = ReaderTokens.Danger) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ReaderTokens.Surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(result.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = ReaderTokens.TextPrimary)
                    Text(result.courseLabel, style = MaterialTheme.typography.labelMedium, color = ReaderTokens.TextSecondary)
                    if (result.pendingCount > 0) {
                        Text(
                            text = "已评 ${result.gradedScore}/${result.maxScore} 分 · 待自评 ${result.pendingCount} 题",
                            style = MaterialTheme.typography.titleMedium,
                            color = ReaderTokens.Accent,
                        )
                        Text(
                            "待自评项暂无分数（NULL 不等于 0），完成自评后才形成终分。",
                            style = MaterialTheme.typography.bodySmall,
                            color = ReaderTokens.TextSecondary,
                        )
                    } else {
                        Text(
                            text = "总分 ${result.gradedScore}/${result.maxScore} 分",
                            style = MaterialTheme.typography.titleMedium,
                            color = ReaderTokens.PrimaryDark,
                        )
                    }
                    Text(
                        text = "有效用时 ${formatElapsed(result.elapsedMs)} · 状态 ${statusLabel(result.status)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ReaderTokens.TextSecondary,
                    )
                    result.gradeNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = ReaderTokens.Primary) }
                    if (result.wrongCount > 0) {
                        Text(
                            text = "错题本：共 ${result.wrongCount} 题可手动移除",
                            style = MaterialTheme.typography.labelMedium,
                            color = ReaderTokens.Danger,
                        )
                        Text(
                            "改对后不会自动删除历史错题；如需清理请在下方逐题移除（不影响成绩与解析）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = ReaderTokens.TextSecondary,
                        )
                    }
                }
            }

            result.questions.forEach { question ->
                ResultItemCard(
                    question = question,
                    submitted = result.status != QuizAttemptStatusUi.IN_PROGRESS,
                    onOpenSource = { source ->
                        onSourceCheckpoint(buildJsonObject {
                            put("attemptId", result.attemptId); put("ordinal", question.ordinal)
                            put("expanded", true); put("scroll", scrollState.value)
                        }.toString())
                        onOpenSource(source)
                    },
                    onSelfGrade = {
                        scoreText = ""
                        note = ""
                        gradeTargetId = question.itemId
                    },
                    onRemoveWrong = { removeTargetId = question.itemId },
                    onToggleFavorite = { onToggleFavorite(question) },
                    initialExpanded = restored?.get("ordinal")?.jsonPrimitive?.intOrNull == question.ordinal &&
                        restored?.get("expanded")?.jsonPrimitive?.booleanOrNull == true,
                    aiReviewEnabled = result.aiReviewEnabled,
                    controlsEnabled = !saveState.saving,
                    onReviewAi = { onReviewAi(question.itemId) },
                    onAdoptAi = { onAdoptAi(question.itemId) },
                )
            }
          }
        }
    }

    gradeTarget?.let { target ->
        val busy = gradeSaving || saveState.saving
        val score = scoreText.toIntOrNull()
        val correct = score != null && score in 0..target.maxScore
        UiSheet(
            title = "自评第 ${target.ordinal + 1} 题",
            onDismiss = { if (!busy) gradeTargetId = null },
            confirmDismiss = { !busy },
            actions = {
                UiAction("取消", onClick = { gradeTargetId = null }, enabled = !busy,
                    actionId = "quiz.result.selfGrade.cancel")
                UiAction(
                    "保存自评",
                    onClick = {
                        gradeSaving = true
                        onSelfGrade(target.itemId, scoreText, note)
                    },
                    filled = true,
                    enabled = correct,
                    busy = busy,
                    actionId = "quiz.result.selfGrade.save",
                )
            },
        ) {
            Text("评分范围 0–${target.maxScore}，保存后更新总分与待评状态。")
            target.rubricLines.forEach { Text("· $it", style = MaterialTheme.typography.labelSmall) }
            OutlinedTextField(value = scoreText, onValueChange = { scoreText = it },
                label = { Text("分数") }, enabled = !busy, isError = scoreText.isNotEmpty() && !correct,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注/理由（可空）") },
                enabled = !busy, modifier = Modifier.fillMaxWidth())
            saveState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    removeTarget?.let { target ->
        UiConfirmDialog(
            title = "从错题本移除？",
            message = "将移除第 ${target.ordinal + 1} 题的错题记录，作答、成绩及手动收藏仍保留。",
            confirmLabel = "确认移除",
            dismissLabel = "取消",
            onConfirm = {
                onRemoveWrong(target)
                removeTargetId = null
            },
            onDismiss = { removeTargetId = null },
            ownerKey = result.attemptId to target.itemId,
            canConfirm = target.inWrongBook && !saveState.saving,
            actionId = "quiz.result.removeWrong",
        )
    }
}

@Composable
private fun ResultItemCard(
    question: QuizQuestionUi,
    submitted: Boolean,
    onOpenSource: (AgentSourceUi) -> Unit,
    onSelfGrade: () -> Unit,
    onRemoveWrong: () -> Unit,
    onToggleFavorite: () -> Unit,
    initialExpanded: Boolean,
    aiReviewEnabled: Boolean,
    controlsEnabled: Boolean,
    onReviewAi: () -> Unit,
    onAdoptAi: () -> Unit,
) {
    var expanded by rememberSaveable(question.itemId) { mutableStateOf(initialExpanded) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ReaderTokens.Surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "第 ${question.ordinal + 1} 题 · " + if (question.type == QuizItemTypeUi.CHOICE) "选择" else "简答",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    color = ReaderTokens.TextPrimary,
                )
                Text(
                    text = when (question.gradeStatus) {
                        QuizItemGradeStatusUi.GRADED -> "${question.score}/${question.maxScore} 分"
                        QuizItemGradeStatusUi.PENDING_SELF_REVIEW -> "待自评"
                        QuizItemGradeStatusUi.NOT_GRADED -> "未交卷"
                        QuizItemGradeStatusUi.UNANSWERED -> "未作答（0 分）"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (question.gradeStatus == QuizItemGradeStatusUi.PENDING_SELF_REVIEW) ReaderTokens.Accent else ReaderTokens.PrimaryDark,
                )
            }
            Text(question.stem, style = MaterialTheme.typography.bodyMedium, color = ReaderTokens.TextPrimary)
            question.options.forEach { option ->
                Text("${option.letter}. ${option.text}", style = MaterialTheme.typography.bodySmall, color = ReaderTokens.TextSecondary)
            }
            Text(
                text = "我的答案：" + question.savedAnswer.ifBlank { "（未作答）" },
                style = MaterialTheme.typography.bodySmall,
                color = ReaderTokens.TextSecondary,
            )
            QuestionAnalysisPanel(
                analysis = LearningAnalysis(question.rubricLines.joinToString("；"), question.analysis,
                    question.knowledgePoint, question.rubricLines,
                    question.sources.map { LearningSource(it.referenceId ?: "${it.ordinal}:${it.documentId}", it.documentTitle, it.quote, it.canNavigate) }),
                submitted = submitted,
                expanded = expanded, onExpandedChange = { expanded = it },
                isFavorite = question.manuallyFavorited,
                onToggleFavorite = onToggleFavorite.takeIf { controlsEnabled && question.questionId != null },
                onOpenSource = { source -> question.sources.firstOrNull { (it.referenceId ?: "${it.ordinal}:${it.documentId}") == source.key }?.let(onOpenSource) },
            )
            if (question.type == QuizItemTypeUi.ESSAY && aiReviewEnabled && question.savedAnswer.isNotBlank()) {
                UiAction("请求 AI 复核建议", onClick = onReviewAi, enabled = controlsEnabled,
                    actionId = "quiz.result.reviewAi")
            }
            question.aiReviewSuggestion?.let { suggestion ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("AI 复核建议（未采纳）", fontWeight = FontWeight.SemiBold)
                        Text("本地分：${suggestion.localScore?.let { "$it/${suggestion.maxScore}" } ?: "待评"} · 建议：${suggestion.score}/${suggestion.maxScore}")
                        if (suggestion.rationale.isNotBlank()) Text("理由：${suggestion.rationale}", style = MaterialTheme.typography.bodySmall)
                        suggestion.rubricJudgments.forEach { judgment ->
                            Text("${if (judgment.hit) "✓" else "○"} ${judgment.point}${judgment.note.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                        }
                        suggestion.uncertainties.forEach { uncertainty ->
                            Text("不确定：$uncertainty", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text("模型 ${suggestion.model} · ${suggestion.rubricVersion}", style = MaterialTheme.typography.labelSmall)
                        UiAction("采纳建议并更新分数", onClick = onAdoptAi, enabled = controlsEnabled,
                            actionId = "quiz.result.adoptAi")
                    }
                }
            }
            if (question.gradeStatus == QuizItemGradeStatusUi.PENDING_SELF_REVIEW) {
                UiAction("自评本题", onClick = onSelfGrade, enabled = controlsEnabled,
                    actionId = "quiz.result.selfGrade")
            }
            if (question.inWrongBook) {
                // 只显示“当前状态 + 明确移除”，不在修分/查看结果时自动删除。
                Text(
                    text = "已在错题本（可移除）",
                    style = MaterialTheme.typography.labelMedium,
                    color = ReaderTokens.Danger,
                )
                UiAction("从错题本移除", onClick = onRemoveWrong, enabled = controlsEnabled,
                    foreground = MaterialTheme.colorScheme.error, actionId = "quiz.result.requestRemoveWrong")
            }
        }
    }
}

@Composable
fun QuizResultRoute(
    attemptId: String,
    onBack: () -> Unit,
    onOpenAttempt: (attemptId: String) -> Unit,
    onRegenerate: (paperId: String) -> Unit,
    onOpenSource: (AgentSourceUi) -> Unit,
    initialReturnPosition: String = "{}",
    onSourceCheckpoint: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val container = (context.applicationContext as com.example.myapplication.LearningApplication).container
    val identity by container.sessionManager.identity.collectAsState(initial = null)
    val factory = remember(attemptId, identity) { QuizViewModelFactory(context.applicationContext, "", null, autoStartGeneration = false) }
    val viewModel: QuizViewModel = viewModel(key = "quiz-result-$attemptId-${identity?.userId}-${identity?.epoch}", factory = factory)
    val result by viewModel.result.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    LaunchedEffect(attemptId, identity) { if (identity?.userId != null) viewModel.loadResult(attemptId) }
    val loaded = result
    if (loaded == null) {
        val requestBack = quizPageBack(onBack)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (saveState.error == null) "正在读取结果…" else "结果暂不可用", color = ReaderTokens.TextSecondary)
            saveState.error?.let { Text(it, color = ReaderTokens.Danger) }
            if (saveState.error != null) {
                UiAction("重试读取结果", onClick = { viewModel.loadResult(attemptId) },
                    enabled = identity?.userId != null && !saveState.saving,
                    modifier = Modifier.testTag("quiz-result-retry"), actionId = "quiz.result.retry")
            }
            if (saveState.error?.contains("ATTEMPT_NOT_SUBMITTED") == true) {
                UiAction("继续作答", onClick = { onOpenAttempt(attemptId) }, filled = true, actionId = "quiz.result.resume")
            }
            UiAction("返回", onClick = requestBack, actionId = "quiz.result.back")
        }
        return
    }
    QuizResultScreen(
        result = loaded,
        onBack = onBack,
        onSelfGrade = viewModel::selfGrade,
        onRetrain = { paperId -> viewModel.retrain(paperId) { attemptId -> onOpenAttempt(attemptId) } },
        onRegenerate = onRegenerate,
        onOpenSource = onOpenSource,
        initialReturnPosition = initialReturnPosition,
        onSourceCheckpoint = onSourceCheckpoint,
        onRemoveWrong = viewModel::removeWrong,
        onToggleFavorite = viewModel::toggleManualFavorite,
        onReviewAi = viewModel::requestAiReview,
        onAdoptAi = viewModel::adoptAiReview,
        saveState = saveState,
    )
}

internal fun statusLabel(status: QuizAttemptStatusUi): String = when (status) {
    QuizAttemptStatusUi.IN_PROGRESS -> "作答中"
    QuizAttemptStatusUi.SUBMITTED_PENDING_REVIEW -> "已交卷·待自评"
    QuizAttemptStatusUi.SUBMITTED_FINAL -> "已评"
}
