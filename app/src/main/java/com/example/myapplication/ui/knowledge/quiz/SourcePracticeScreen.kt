package com.example.myapplication.ui.knowledge.quiz

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.quiz.SourcePractice
import com.example.myapplication.data.quiz.SourcePracticeFactory
import com.example.myapplication.data.quiz.SourcePracticeKind
import com.example.myapplication.data.quiz.SourcePracticeProgress
import com.example.myapplication.ui.ai.AgentMarkdown
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiInlineExpand
import com.example.myapplication.ui.components.learning.LearningChoiceRow
import com.example.myapplication.ui.navigation.LocalNavigationPageActive
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

/** Answering here remains a local learning session, with no publication or grade mutation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourcePracticeScreen(
    practice: SourcePractice,
    onBack: () -> Unit,
    onGenerateAi: (() -> Unit)? = null,
    onGenerateMore: (() -> Unit)? = null,
    moreLoading: Boolean = false,
    moreExhausted: Boolean = false,
    moreError: String? = null,
    batchNumber: Int = 1,
    onCancelMore: (() -> Unit)? = null,
    moreExhaustionReason: String? = null,
    onAskAi: (() -> Unit)? = null,
    initialProgress: SourcePracticeProgress? = null,
    onProgressChange: ((SourcePracticeProgress) -> Unit)? = null,
    favoriteQuestionIds: Set<String> = emptySet(),
    onToggleFavorite: ((String) -> Unit)? = null,
    archiveRecordId: String? = null,
    sourceWarning: String? = null,
    leaving: Boolean = false,
    favoritePending: Boolean = false,
    reviewAction: (@Composable (questionId: String, enabled: Boolean) -> Unit)? = null,
) {
    val questions = practice.questions
    val seed = remember(practice.id, archiveRecordId) { (initialProgress ?: SourcePracticeProgress()).normalized(practice) }
    var index by rememberSaveable(practice.id, archiveRecordId) { mutableStateOf(seed.index) }
    var responses by rememberSaveable(practice.id, archiveRecordId) { mutableStateOf(seed.responses) }
    var submitted by rememberSaveable(practice.id, archiveRecordId) { mutableStateOf(seed.submitted) }
    var selfReviews by rememberSaveable(practice.id, archiveRecordId) { mutableStateOf(seed.selfReviews) }
    var summary by rememberSaveable(practice.id, archiveRecordId) { mutableStateOf(seed.summary) }
    fun changed() { onProgressChange?.invoke(SourcePracticeProgress(responses, submitted, selfReviews, index, summary)) }
    val pageActive = LocalNavigationPageActive.current
    val canEdit = pageActive && !leaving
    val back: () -> Unit = {
        if (canEdit) {
            if (summary) {
                if (moreLoading) onCancelMore?.invoke()
                summary = false
                changed()
            } else onBack()
        }
    }
    val positionProgress by animateFloatAsState(
        targetValue = if (questions.isEmpty()) 0f else (index + 1).toFloat() / questions.size,
        label = "source-practice-position")
    val submittedProgress by animateFloatAsState(
        targetValue = if (questions.isEmpty()) 0f else submitted.count { it }.toFloat() / questions.size,
        label = "source-practice-submitted")
    val listState = rememberLazyListState()
    LaunchedEffect(practice.id, index, summary) { listState.scrollToItem(0) }
    BackHandler(enabled = summary && pageActive, onBack = back)
    Scaffold(
        modifier = Modifier.testTag("source-practice-screen"),
        containerColor = UiRoles.surface(UiSurfaceRole.Page),
        topBar = {
            Surface(color = UiRoles.surface(UiSurfaceRole.Page)) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UiAction(
                        label = if (summary) "返回题目" else "返回阅读",
                        icon = UiIcons.Back,
                        onClick = back,
                        enabled = canEdit,
                        keepEnabledStyle = !leaving,
                        busy = leaving,
                        modifier = Modifier.testTag("source-practice-back"),
                        actionId = "sourcePractice.back",
                    )
                    Text(practice.origin.label, Modifier.weight(1f), style = UiRoles.text(UiRoles.pageTitle))
                    if (questions.isNotEmpty() && !summary) {
                        UiAction(
                            label = "查看总结",
                            onClick = { summary = true; changed() },
                            enabled = canEdit,
                            keepEnabledStyle = true,
                            modifier = Modifier.testTag("source-practice-finish"),
                            actionId = "sourcePractice.summary",
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("source-practice-list"),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item(key = "title") {
                Text(practice.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (onGenerateMore != null || archiveRecordId != null) {
                    Text("第 " + batchNumber + " 组 · 随堂练习", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)
                            .testTag("source-practice-batch"))
                }
                Text(practice.coverageNote, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            if (sourceWarning != null) {
                item(key = "historical-source-warning") {
                    Card(Modifier.fillMaxWidth().testTag("source-practice-historical-source"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Text(sourceWarning, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (onAskAi != null) {
                item(key = "chapter-ai") {
                    UiAction(
                        label = "向 AI 提问本章内容",
                        onClick = onAskAi,
                        enabled = !moreLoading && canEdit,
                        keepEnabledStyle = !moreLoading,
                        modifier = Modifier.testTag("source-practice-ask-ai"),
                        actionId = "sourcePractice.askAi",
                    )
                }
            }
            if (questions.isEmpty()) {
                item(key = "empty") {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("当前原文暂未提取到合适的练习题。")
                            Text("可返回阅读选择内容更完整的章节。", style = MaterialTheme.typography.bodySmall)
                            SourcePracticeMoreActions(onGenerateMore, moreLoading, moreExhausted, moreError,
                                onCancelMore, onGenerateAi, moreExhaustionReason, actionsEnabled = canEdit)
                            UiAction(label = "返回阅读", onClick = onBack, enabled = canEdit,
                                keepEnabledStyle = true, actionId = "sourcePractice.exit")
                        }
                    }
                }
            } else if (summary) {
                item(key = "summary") {
                    val automaticIndices = questions.indices.filter {
                        submitted[it] && questions[it].kind != SourcePracticeKind.RECALL
                    }
                    val correctIndices = automaticIndices.filter {
                        SourcePracticeFactory.isAnswerCorrect(questions[it], responses[it]) == true
                    }
                    val automaticScore = correctIndices.sumOf { questions[it].score }
                    val automaticMax = automaticIndices.sumOf { questions[it].score }
                    val recallCount = questions.indices.count {
                        submitted[it] && questions[it].kind == SourcePracticeKind.RECALL
                    }
                    val pendingRecall = questions.indices.count {
                        submitted[it] && questions[it].kind == SourcePracticeKind.RECALL && selfReviews[it] == 0
                    }
                    Card(Modifier.fillMaxWidth().testTag("source-practice-summary")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("本次练习", style = MaterialTheme.typography.titleLarge)
                            Text("已提交 " + submitted.count { it } + "/" + questions.size + " 题")
                            if (automaticIndices.isNotEmpty()) {
                                Text("选择与填空答对 " + correctIndices.size + "/" + automaticIndices.size +
                                    " 题；已作答部分得分 " + automaticScore + "/" + automaticMax)
                            }
                            if (recallCount > 0) {
                                Text("简答已提交 " + recallCount + " 题，其中 " + pendingRecall + " 题待自行核对。")
                                Text("简答的掌握情况由你对照原文标记。", style = MaterialTheme.typography.bodySmall)
                            }
                            if (submitted.any { !it }) Text("未作答题目不计入本次得分。", style = MaterialTheme.typography.bodySmall)
                            SourcePracticeMoreActions(onGenerateMore, moreLoading, moreExhausted, moreError,
                                onCancelMore, onGenerateAi, moreExhaustionReason, actionsEnabled = canEdit)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                UiAction(
                                    label = "再练一次",
                                    onClick = {
                                        responses = List(questions.size) { "" }
                                        submitted = List(questions.size) { false }
                                        selfReviews = List(questions.size) { 0 }
                                        index = 0
                                        summary = false
                                        changed()
                                    },
                                    filled = true,
                                    enabled = !moreLoading && canEdit,
                                    keepEnabledStyle = !moreLoading,
                                    modifier = Modifier.testTag("source-practice-repeat"),
                                    actionId = "sourcePractice.repeat",
                                )
                                UiAction(label = "继续核对答案", onClick = back, enabled = canEdit,
                                    keepEnabledStyle = true, actionId = "sourcePractice.checkAnswers")
                                UiAction(label = "返回阅读", onClick = onBack, enabled = canEdit,
                                    keepEnabledStyle = true, actionId = "sourcePractice.exit")
                            }
                        }
                    }
                }
            } else {
                val current = index.coerceIn(0, questions.lastIndex)
                val question = questions[current]
                val revealed = submitted[current]
                item(key = "progress") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("第 " + (current + 1) + "/" + questions.size + " 题",
                            modifier = Modifier.testTag("source-practice-progress"))
                        Text(when (question.kind) {
                            SourcePracticeKind.CHOICE -> "单选"
                            SourcePracticeKind.CLOZE -> "填空"
                            SourcePracticeKind.RECALL -> "简答 · 自行核对"
                        }, style = MaterialTheme.typography.labelMedium)
                    }
                    @Suppress("DEPRECATION")
                    LinearProgressIndicator(progress = positionProgress,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("source-practice-position-bar"))
                    Text("已提交 " + submitted.count { it } + "/" + questions.size + " 题",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp).testTag("source-practice-submitted-count"))
                    @Suppress("DEPRECATION")
                    LinearProgressIndicator(progress = submittedProgress,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("source-practice-submitted-bar"))
                }
                item(key = question.id + "-stem") {
                    Card(Modifier.fillMaxWidth().testTag("source-practice-question")) {
                        Column(Modifier.padding(16.dp)) {
                            AgentMarkdown(question.id + "-stem", question.stem)
                            if (onToggleFavorite != null) {
                                val favorite = question.id in favoriteQuestionIds
                                UiAction(
                                    label = if (favorite) "已收藏" else "收藏本题",
                                    onClick = { onToggleFavorite(question.id) },
                                    enabled = canEdit && !favoritePending,
                                    keepEnabledStyle = !favoritePending,
                                    modifier = Modifier.testTag("source-practice-favorite").semantics {
                                        stateDescription = if (favorite) "已收藏" else "未收藏"
                                    },
                                    actionId = "sourcePractice.favorite",
                                )
                            }
                        }
                    }
                }
                if (question.kind == SourcePracticeKind.CHOICE) {
                    question.options.forEachIndexed { optionIndex, option ->
                        item(key = question.id + "-option-" + optionIndex) {
                            LearningChoiceRow(
                                label = ('A' + optionIndex).toString() + ". " + option,
                                selected = responses[current] == option,
                                onSelect = {
                                    responses = responses.mapIndexed { i, old -> if (i == current) option else old }
                                    changed()
                                },
                                modifier = Modifier.testTag("source-practice-option-" + optionIndex),
                                enabled = canEdit && !revealed,
                            )
                        }
                    }
                } else {
                    item(key = question.id + "-response") {
                        OutlinedTextField(
                            value = responses[current],
                            onValueChange = { value ->
                                responses = responses.mapIndexed { i, old -> if (i == current) value.take(4000) else old }
                                changed()
                            },
                            enabled = canEdit && !revealed,
                            label = { Text(if (question.kind == SourcePracticeKind.CLOZE) "填写数值和单位" else "写下你的理解") },
                            minLines = if (question.kind == SourcePracticeKind.RECALL) 3 else 1,
                            modifier = Modifier.fillMaxWidth().testTag("source-practice-input"),
                        )
                    }
                }
                if (!revealed) {
                    item(key = question.id + "-submit") {
                        UiAction(
                            label = "提交并查看解析",
                            onClick = {
                                if (canEdit && !submitted[current] && responses[current].isNotBlank()) {
                                    submitted = submitted.mapIndexed { i, old -> i == current || old }
                                    changed()
                                }
                            },
                            enabled = canEdit && responses[current].isNotBlank(),
                            keepEnabledStyle = responses[current].isNotBlank(),
                            filled = true,
                            modifier = Modifier.fillMaxWidth().testTag("source-practice-submit"),
                            actionId = "sourcePractice.submit",
                        )
                    }
                } else {
                    item(key = question.id + "-answer") {
                        val correct = SourcePracticeFactory.isAnswerCorrect(question, responses[current])
                        Card(Modifier.fillMaxWidth().testTag("source-practice-answer"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(when (correct) { true -> "回答正确"; false -> "再核对一下原文"; null -> "对照原文，自行核对" },
                                    style = MaterialTheme.typography.titleMedium)
                                Text("参考答案", style = MaterialTheme.typography.labelLarge)
                                AgentMarkdown(question.id + "-answer", question.answers.joinToString("\n\n"))
                                var analysisExpanded by rememberSaveable(archiveRecordId, question.id) { mutableStateOf(true) }
                                UiInlineExpand(
                                    ownerKey = Triple(practice.id, archiveRecordId, question.id),
                                    expanded = analysisExpanded,
                                    onExpandedChange = { analysisExpanded = it },
                                    trigger = { toggleModifier, _, enabled, toggle ->
                                        UiAction(
                                            label = if (analysisExpanded) "收起解析" else "展开解析",
                                            onClick = toggle,
                                            enabled = enabled && canEdit,
                                            keepEnabledStyle = enabled,
                                            modifier = toggleModifier.testTag("source-practice-analysis-toggle"),
                                            actionId = "sourcePractice.analysis.toggle",
                                        )
                                    },
                                ) {
                                    Column(Modifier.testTag("source-practice-analysis")) {
                                        AgentMarkdown(question.id + "-analysis", question.analysis)
                                    }
                                }
                                if (correct == null) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        UiAction(
                                            label = if (selfReviews[current] == 1) "已标记：已掌握" else "我已掌握",
                                            onClick = { selfReviews = selfReviews.mapIndexed { i, old -> if (i == current) 1 else old }; changed() },
                                            enabled = canEdit && selfReviews[current] != 1,
                                            keepEnabledStyle = selfReviews[current] != 1,
                                            modifier = Modifier.testTag("source-practice-self-understood"),
                                            actionId = "sourcePractice.understood",
                                        )
                                        UiAction(
                                            label = if (selfReviews[current] == 2) "已标记：需复习" else "需要复习",
                                            onClick = { selfReviews = selfReviews.mapIndexed { i, old -> if (i == current) 2 else old }; changed() },
                                            enabled = canEdit && selfReviews[current] != 2,
                                            keepEnabledStyle = selfReviews[current] != 2,
                                            actionId = "sourcePractice.review",
                                        )
                                    }
                                }
                                reviewAction?.invoke(question.id, !leaving)
                            }
                        }
                    }
                    item(key = question.id + "-source") {
                        Card(Modifier.fillMaxWidth().testTag("source-practice-source")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text((if (sourceWarning == null) "原文依据 · " else "生成时保存的摘录 · ") + question.sourceLabel,
                                    style = MaterialTheme.typography.labelLarge)
                                question.sourceQuotes.forEachIndexed { quoteIndex, quote ->
                                    Text(quote, style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.testTag("source-practice-quote-" + quoteIndex))
                                }
                            }
                        }
                    }
                }
                item(key = question.id + "-navigation") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        UiAction(label = "上一题", onClick = { index = current - 1; changed() }, enabled = current > 0 && canEdit,
                            keepEnabledStyle = current > 0,
                            modifier = Modifier.testTag("source-practice-previous"), actionId = "sourcePractice.previous")
                        UiAction(
                            label = if (current == questions.lastIndex) "查看总结" else "下一题",
                            onClick = { if (current == questions.lastIndex) summary = true else index = current + 1; changed() },
                            enabled = canEdit,
                            keepEnabledStyle = true,
                            filled = current == questions.lastIndex,
                            modifier = Modifier.testTag("source-practice-next"),
                            actionId = "sourcePractice.next",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePracticeMoreActions(
    onGenerateMore: (() -> Unit)?,
    moreLoading: Boolean,
    moreExhausted: Boolean,
    moreError: String?,
    onCancelMore: (() -> Unit)?,
    onGenerateAi: (() -> Unit)?,
    moreExhaustionReason: String?,
    actionsEnabled: Boolean,
) {
    if (onGenerateMore != null) {
        if (moreExhausted) {
            Text(moreExhaustionReason?.takeIf { it.isNotBlank() }
                ?: "当前可验证材料暂无更多未展示的新题。可以复习本组或返回阅读其他章节。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("source-practice-more-exhausted"))
        } else {
            UiAction(
                label = "生成更多随堂测试",
                onClick = onGenerateMore,
                enabled = !moreLoading && actionsEnabled,
                keepEnabledStyle = !moreLoading,
                filled = true,
                modifier = Modifier.testTag("source-practice-more"),
                actionId = "sourcePractice.generateMore",
            )
        }
        if (moreLoading) {
            Row(Modifier.testTag("source-practice-more-loading"),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("正在准备下一组新题…", style = MaterialTheme.typography.bodySmall)
                onCancelMore?.let { action ->
                    UiAction(label = "取消", onClick = action, enabled = actionsEnabled,
                        keepEnabledStyle = true,
                        modifier = Modifier.testTag("source-practice-more-cancel"), actionId = "sourcePractice.cancelGeneration")
                }
            }
        }
        moreError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("source-practice-more-error"), style = MaterialTheme.typography.bodySmall)
        }
    }
    onGenerateAi?.let { action ->
        UiAction(label = "AI 扩展练习", onClick = action, enabled = !moreLoading && actionsEnabled,
            keepEnabledStyle = !moreLoading,
            modifier = Modifier.testTag("source-practice-ai"), actionId = "sourcePractice.extendAi")
    }
}
