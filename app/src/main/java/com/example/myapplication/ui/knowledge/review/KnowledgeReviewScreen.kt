package com.example.myapplication.ui.knowledge.review

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.example.myapplication.data.review.ReviewAvailability
import com.example.myapplication.data.review.ReviewCandidate
import com.example.myapplication.data.review.ReviewItemEntity
import com.example.myapplication.data.review.ReviewMaterialGroup
import com.example.myapplication.data.review.ReviewQuestion
import com.example.myapplication.data.review.ReviewQuestionBuilder
import com.example.myapplication.data.review.ReviewRepository
import com.example.myapplication.data.review.ReviewScope
import com.example.myapplication.data.review.ReviewSessionState
import com.example.myapplication.data.review.ReviewSourceCatalog
import com.example.myapplication.data.review.ReviewSourceLink
import com.example.myapplication.data.review.ReviewSummary
import com.example.myapplication.data.review.ticket
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.reader.sha256Hex
import com.example.myapplication.domain.review.ReviewFocusTimer
import com.example.myapplication.domain.review.ReviewRating
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.mine.GlassPageBarHeight
import com.example.myapplication.ui.mine.SubPageScaffold
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackLayer
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.LocalNavigationPageActive
import com.example.myapplication.ui.navigation.Routes
import com.example.myapplication.ui.navigation.UiPageStack
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.navigation.unifiedPageBack
import com.example.myapplication.ui.theme.UiRoles
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun KnowledgeReviewHeader(
    repository: ReviewRepository,
    identity: SessionIdentity,
    open: () -> Unit,
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var summary by remember(identity) { mutableStateOf<ReviewSummary?>(null) }
    var failed by remember(identity) { mutableStateOf(false) }
    val pageActive = LocalNavigationPageActive.current
    val moving = com.example.myapplication.ui.components.LocalUiMotionBusy.current
    LaunchedEffect(identity, lifecycle, pageActive, moving) {
        if (!pageActive || moving || lifecycle != Lifecycle.State.RESUMED) return@LaunchedEffect
        try {
            summary = repository.summary(identity)
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    KnowledgeReviewHomeCard(summary, failed, open)
}

@Composable
internal fun KnowledgeReviewRoute(
    repository: ReviewRepository,
    sources: ReviewSourceCatalog,
    sessionManager: SessionManager,
    onBack: () -> Unit,
    onSource: (String) -> Unit,
) {
    val identity by sessionManager.identity.collectAsState(SessionIdentity(null, -1))
    if (identity.userId == null || identity.epoch < 0) {
        ReviewPageLayout(panel = null, onBack = unifiedPageBack(onBack)) {
            Text("请登录后查看知识回顾")
        }
    } else key(identity) {
        ReviewContent(identity, repository, sources, onBack, onSource)
    }
}

private enum class ReviewPanel(val title: String) {
    MATERIALS("挑选素材"),
    CARDS("管理回顾卡"),
}

@Composable
private fun ReviewContent(
    identity: SessionIdentity,
    repository: ReviewRepository,
    sources: ReviewSourceCatalog,
    onBack: () -> Unit,
    onSource: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<ReviewSummary?>(null) }
    var session by remember { mutableStateOf<ReviewSessionState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<ReviewPanel?>(null) }
    var management by remember { mutableStateOf<List<ReviewItemEntity>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<ReviewItemEntity?>(null) }
    var managePage by remember { mutableIntStateOf(0) }
    var group by remember { mutableStateOf(ReviewMaterialGroup.ANNOTATIONS) }
    var page by remember { mutableIntStateOf(0) }
    var materials by remember { mutableStateOf<List<ReviewCandidate>>(emptyList()) }
    var hasMoreMaterials by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<Map<String, ReviewCandidate>>(emptyMap()) }
    var sourceQuestion by remember { mutableStateOf<ReviewSourceLink.Question?>(null) }
    var showEndConfirm by remember { mutableStateOf(false) }
    var materialRefresh by remember { mutableIntStateOf(0) }
    var initialRefresh by remember { mutableIntStateOf(0) }
    var editingExcerpt by remember { mutableStateOf<ReviewCandidate?>(null) }
    var recentDays by remember { mutableStateOf<List<String>>(emptyList()) }

    fun command(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "操作未完成，请刷新后重试。"
            } finally {
                busy = false
            }
        }
    }

    val dialog = when {
        editingExcerpt != null -> BackLayer.Overlay("review-excerpt")
        pendingDelete != null -> BackLayer.Overlay("review-delete")
        sourceQuestion != null -> BackLayer.Overlay("review-source-question")
        showEndConfirm -> BackLayer.Overlay("review-end-confirmation")
        else -> null
    }
    val requestBack = unifiedBackRequest(
        BackState(page = BackPage.CHILD, imeVisible = isBackImeVisible(), topLayer = dialog),
    ) { decision ->
        when (decision) {
            BackDecision.PopPage -> {
                val currentPanel = panel
                if (currentPanel == null) {
                    onBack()
                } else {
                    panel = null
                    if (currentPanel == ReviewPanel.CARDS) command { summary = repository.summary(identity) }
                }
            }
            is BackDecision.CloseLayer -> when (decision.layer.key) {
                "review-excerpt" -> editingExcerpt = null
                "review-delete" -> pendingDelete = null
                "review-source-question" -> sourceQuestion = null
                "review-end-confirmation" -> showEndConfirm = false
                else -> Unit
            }
            else -> Unit
        }
    }

    LaunchedEffect(initialRefresh) {
        command {
            summary = repository.summary(identity)
            recentDays = repository.reviewedDates(identity).sortedDescending().take(7)
            summary?.activeSessionId?.let { session = repository.session(identity, it) }
        }
    }
    LaunchedEffect(panel, group, page, materialRefresh) {
        if (panel != ReviewPanel.MATERIALS) return@LaunchedEffect
        materials = emptyList()
        hasMoreMaterials = false
        error = null
        try {
            val result = sources.page(identity, group, page)
            materials = result.items
            hasMoreMaterials = result.hasMore
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "该类素材暂时无法读取。"
        }
    }
    editingExcerpt?.let { candidate ->
        key(candidate.sourceKey) {
            var prompt by remember { mutableStateOf(chosen[candidate.sourceKey]?.prompt.orEmpty()) }
            var excerpt by remember {
                mutableStateOf((chosen[candidate.sourceKey]?.materialExcerpt ?: candidate.materialExcerpt).orEmpty().take(8000))
            }
            var excerptError by remember { mutableStateOf(false) }
            val operation = remember { UUID.randomUUID().toString() }
            AlertDialog(
                onDismissRequest = { editingExcerpt = null },
                title = { Text("选择笔记节选") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("保留一个完整知识点。你可以写下问题，也可以让应用根据节选出题。")
                        OutlinedTextField(prompt, { prompt = it.take(500) }, label = { Text("回顾问题（可选）") })
                        OutlinedTextField(excerpt, { excerpt = it.take(8000) }, label = { Text("正文节选") }, minLines = 3)
                        if (excerptError) Text("这段节选缺少可出题的知识点。请选择更完整的正文，或填写自己的问题。")
                    }
                },
                confirmButton = { UiAction(label = "选定节选", enabled = excerpt.isNotBlank(), onClick = {
                    val selected = excerpt.trim()
                    val question = if (prompt.isBlank()) ReviewQuestionBuilder.fromPassage(selected)
                        else ReviewQuestion(prompt.trim(), selected)
                    if (question == null) {
                        excerptError = true
                    } else {
                        chosen = chosen + (candidate.sourceKey to candidate.copy(
                            sourceNamespace = "notebook-excerpt",
                            sourceKey = "notebook-excerpt:${candidate.sourceKey.removePrefix("notebook:")}:$operation",
                            prompt = question.prompt,
                            answerSnapshot = question.answer,
                            creationOperationId = operation,
                            excerptFingerprint = sha256Hex(candidate.sourceKey + "\n" + selected),
                            materialExcerpt = selected,
                        ))
                        editingExcerpt = null
                    }
                }) },
                dismissButton = { UiAction(label = "取消", onClick = { editingExcerpt = null }) },
            )
        }
    }
    sourceQuestion?.let { question ->
        AlertDialog(
            onDismissRequest = { sourceQuestion = null },
            title = { Text("原题") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(question.title)
                    Text(question.answerAndAnalysis)
                }
            },
            confirmButton = { UiAction(label = "关闭", onClick = { sourceQuestion = null }) },
        )
    }
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除回顾卡？") },
            text = { Text("将清理卡面和草稿，已完成的回顾记录保留计数。") },
            confirmButton = { UiAction(label = "删除", enabled = !busy, onClick = {
                pendingDelete = null
                command {
                    repository.deleteItem(identity, item.id, item.itemRevision)
                    management = repository.items(identity)
                }
            }) },
            dismissButton = { UiAction(label = "取消", onClick = { pendingDelete = null }) },
        )
    }
    if (showEndConfirm) {
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text("结束本轮回顾？") },
            text = { Text("已提交的自评保留，剩余卡片可在下一轮继续。") },
            confirmButton = { UiAction(label = "结束回顾", enabled = !busy, onClick = {
                showEndConfirm = false
                session?.let { value ->
                    command { session = repository.finish(identity, value.session.id) }
                }
            }) },
            dismissButton = { UiAction(label = "继续本轮", onClick = { showEndConfirm = false }) },
        )
    }

    val round = session
    val focusTimer = round?.current?.ticket?.let { ticket ->
        key(ticket) { rememberSaveable(saver = reviewFocusSaver) { ReviewFocusTimer() } }
    }
    ReviewPageLayout(panel, requestBack, focusTimer) { visiblePanel ->
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) CircularProgressIndicator()
        when {
            visiblePanel == ReviewPanel.MATERIALS -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewMaterialGroup.entries.forEach { option ->
                        UiAction(label = option.label, filled = group == option, enabled = !busy, onClick = {
                            group = option
                            page = 0
                        })
                    }
                }
                if (materials.isEmpty()) Text("这一页没有可加入的素材")
                materials.forEach { candidate ->
                    val selected = candidate.sourceKey in chosen
                    ReviewPaper(Modifier.fillMaxWidth(), spacing = 8) {
                        ReviewSourceCaption(candidate.sourceTitle.ifBlank { group.label }, maxLines = 2)
                        Text(
                            candidate.prompt.ifBlank { "选择笔记中的完整知识点并创建问题" },
                            maxLines = 3,
                        )
                        UiAction(
                            label = when {
                                selected -> "取消选择"
                                candidate.sourceNamespace == "notebook" -> "选择节选"
                                else -> "选择"
                            },
                            enabled = !busy && (selected || chosen.size < 20), onClick = {
                                when {
                                    selected -> chosen = chosen - candidate.sourceKey
                                    candidate.sourceNamespace == "notebook" -> editingExcerpt = candidate
                                    else -> chosen = chosen + (candidate.sourceKey to candidate)
                                }
                            },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UiAction(label = "上一页", enabled = page > 0 && !busy, onClick = { page-- })
                    UiAction(label = "下一页", enabled = hasMoreMaterials && page < 1000 && !busy, onClick = { page++ })
                    UiAction(label = "刷新", enabled = !busy, onClick = { materialRefresh++ })
                }
                UiAction(label = "确认加入或更新（${chosen.size}）", filled = true, enabled = !busy && chosen.isNotEmpty(), onClick = {
                    command {
                        val selected = chosen.size
                        val existing = repository.items(identity).associateBy { it.sourceNamespace to it.sourceKey }
                        val fresh = ArrayList<ReviewCandidate>(selected)
                        var updated = 0
                        for (candidate in chosen.values) {
                            val old = existing[candidate.sourceNamespace to candidate.sourceKey]
                            if (old != null && old.sourceRevision != candidate.sourceRevision) {
                                repository.updateCard(identity, old.id, old.itemRevision, candidate)
                                updated++
                            } else fresh += candidate
                        }
                        val accepted = repository.addItems(identity, fresh).size + updated
                        chosen = emptyMap()
                        panel = null
                        if (accepted < selected) error = "已确认 $accepted 条，${selected - accepted} 条来源有变化，请重选。"
                        summary = repository.summary(identity)
                    }
                })
            }
            visiblePanel == ReviewPanel.CARDS -> {
                val cards = management
                val lastPage = ((cards.size - 1) / 20).coerceAtLeast(0)
                val currentPage = managePage.coerceIn(0, lastPage)
                if (cards.isEmpty()) Text("还没有回顾卡")
                cards.drop(currentPage * 20).take(20).forEach { card ->
                    ReviewPaper(Modifier.fillMaxWidth(), spacing = 8) {
                        ReviewSourceCaption(card.sourceTitle.ifBlank { "回顾卡" }, maxLines = 2)
                        if (card.availability in setOf(ReviewAvailability.AVAILABLE, ReviewAvailability.SOURCE_CHANGED)) {
                            Text(reviewPromptText(card.prompt, card.sourceTitle), maxLines = 2)
                        }
                        Text(when {
                            card.availability == ReviewAvailability.CONTENT_INCOMPLETE ->
                                "原摘录缺少完整知识点，已保留卡片。可以重新选择更完整的素材。"
                            card.paused -> "已暂停"
                            card.availability == ReviewAvailability.SOURCE_CHANGED -> "来源更新，重新选中素材即可确认更新"
                            card.availability != ReviewAvailability.AVAILABLE -> "来源暂不可用"
                            else -> "安排 ${formatReviewDate(card.dueAtUtc)}"
                        })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UiAction(label = if (card.paused) "恢复回顾" else "暂停", enabled = !busy, onClick = {
                                command {
                                    repository.setPaused(identity, card.id, card.itemRevision, !card.paused)
                                    management = repository.items(identity)
                                }
                            })
                            card.bookKey?.let { book ->
                                UiAction(label = "回顾本书", enabled = !busy, onClick = {
                                    command {
                                        val next = repository.start(identity, ReviewScope.Book(book), UUID.randomUUID().toString())
                                        if (next == null) {
                                            error = "本书暂无可回顾的到期卡片。"
                                        } else {
                                            panel = null
                                            session = next
                                        }
                                    }
                                })
                            }
                            UiAction(label = "删除", enabled = !busy, onClick = { pendingDelete = card })
                        }
                    }
                }
                if (lastPage > 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UiAction(label = "上一页", enabled = currentPage > 0 && !busy, onClick = { managePage = currentPage - 1 })
                    UiAction(label = "下一页", enabled = currentPage < lastPage && !busy, onClick = { managePage = currentPage + 1 })
                }
            }
            round?.current != null -> {
                val card = checkNotNull(round.current)
                key(card.ticket) {
                    var draft by remember { mutableStateOf(card.draftAnswer.orEmpty()) }
                    var useFrozen by remember { mutableStateOf(false) }
                    val submissionId = rememberSaveable { UUID.randomUUID().toString() }
                    var submittedRating by rememberSaveable { mutableStateOf<String?>(null) }
                    val timer = checkNotNull(focusTimer)
                    val sameSource = round.currentSourceAvailability == ReviewAvailability.AVAILABLE ||
                        round.currentSourceAvailability == null
                    val usable = sameSource || useFrozen
                    ReviewFocusVisibility(timer, usable && !busy && sourceQuestion == null &&
                        !showEndConfirm && submittedRating == null)
                    ReviewRoundProgress(round.remainingCount, round.items.size)
                    ReviewPaper(Modifier.fillMaxWidth().testTag("knowledge-review-active-card"), tinted = !card.revealed) {
                        card.sourceTitle?.takeIf(String::isNotBlank)?.let { ReviewSourceCaption(it) }
                        if (!sameSource) {
                            Text(when (round.currentSourceAvailability) {
                                ReviewAvailability.SOURCE_CHANGED -> "来源已更新，可选择按本轮快照回顾。"
                                ReviewAvailability.CONTENT_INCOMPLETE -> "原摘录缺少完整知识点，请跳过此卡片。"
                                else -> "来源已不可访问，请跳过此卡片。"
                            })
                            if (round.currentSourceAvailability == ReviewAvailability.SOURCE_CHANGED && !useFrozen) {
                                UiAction(label = "按本轮快照继续", enabled = !busy, onClick = { useFrozen = true })
                            }
                        }
                        if (usable) {
                            Text(
                                "回想一下",
                                style = UiRoles.text(MaterialTheme.typography.labelLarge),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                reviewPromptText(card.prompt.orEmpty(), card.sourceTitle),
                                style = UiRoles.text(MaterialTheme.typography.titleLarge),
                            )
                            if (!card.revealed) {
                                OutlinedTextField(
                                    value = draft,
                                    onValueChange = {
                                        timer.interaction(SystemClock.elapsedRealtime())
                                        draft = it.take(4000)
                                    },
                                    label = { Text("写下回想内容（可选）") },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (draft != card.draftAnswer.orEmpty()) {
                                    UiAction(label = "保存草稿", enabled = !busy, onClick = {
                                        command { session = repository.saveDraft(identity, card.ticket, draft, useFrozen) }
                                    })
                                }
                                UiAction(label = "查看答案", enabled = !busy, filled = true, onClick = {
                                    command {
                                        repository.saveDraft(identity, card.ticket, draft, useFrozen)
                                        session = repository.reveal(identity, card.ticket, useFrozen)
                                    }
                                })
                            }
                        }
                    }
                    if (usable && card.revealed) {
                        ReviewPaper(Modifier.fillMaxWidth().testTag("knowledge-review-card-answer"), tinted = true) {
                            Text(
                                "参考内容",
                                style = UiRoles.text(MaterialTheme.typography.titleMedium),
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(card.answerSnapshot.orEmpty(), style = UiRoles.text(MaterialTheme.typography.bodyLarge))
                        }
                        Text(
                            "自评记忆程度",
                            style = UiRoles.text(MaterialTheme.typography.labelLarge),
                            color = UiRoles.pageSupporting,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            reviewRatings.forEach { (rating, label) ->
                                UiAction(
                                    label = label,
                                    enabled = !busy && (submittedRating == null || submittedRating == rating.name),
                                    onClick = {
                                        if (!busy) {
                                            val now = SystemClock.elapsedRealtime()
                                            timer.setVisible(false, now)
                                            submittedRating = rating.name
                                            val measuredMs = timer.activeMillis(now)
                                            command {
                                                repository.submit(
                                                    identity, card.ticket, submissionId, rating,
                                                    activeMs = measuredMs, allowFrozenSource = useFrozen,
                                                )
                                                session = repository.session(identity, card.sessionId)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        card.sourceRefJson?.takeIf { sameSource }?.let { reference ->
                            UiAction(label = "查看来源", enabled = !busy, onClick = {
                                command {
                                    if (!card.revealed && draft != card.draftAnswer.orEmpty()) {
                                        session = repository.saveDraft(identity, card.ticket, draft, useFrozen)
                                    }
                                    val target = sources.open(identity, reference)
                                    if (target != null) timer.setVisible(false, SystemClock.elapsedRealtime())
                                    when (target) {
                                        is ReviewSourceLink.Reader -> onSource(Routes.reader(target.documentId, target.locatorJson))
                                        is ReviewSourceLink.Notebook -> onSource(Routes.notebook(target.documentId))
                                        is ReviewSourceLink.Practice -> onSource(Routes.sourcePracticeArchive(target.recordId, target.questionId))
                                        is ReviewSourceLink.Acupoint -> onSource("meridian-acupoint/${target.pointCode}")
                                        is ReviewSourceLink.Question -> sourceQuestion = target
                                        null -> error = "当前已无法打开这条来源"
                                    }
                                }
                            })
                        }
                        UiAction(label = "稍后", enabled = !busy, onClick = {
                            command { session = if (sameSource || useFrozen) repository.skip(identity, card.ticket)
                                else repository.unavailable(identity, card.ticket) }
                        })
                        UiAction(label = "退出本轮", enabled = !busy, onClick = { showEndConfirm = true })
                    }
                }
            }
            round != null -> {
                ReviewPaper(Modifier.fillMaxWidth(), tinted = true) {
                    Text(
                        "本轮回顾已结束",
                        style = UiRoles.text(MaterialTheme.typography.titleLarge),
                        modifier = Modifier.semantics { heading() },
                    )
                    Text("已评 ${round.session.completedCount} 张 · 稍后 ${round.session.skippedCount} 张 · 来源不可用 ${round.session.unavailableCount} 张")
                    val activeMs = round.events.sumOf { it.activeMs }
                    if (activeMs > 0L) Text("本轮专注回顾 ${formatFocusDuration(activeMs)}")
                    round.events.minOfOrNull { it.afterDueAtUtc }?.let { Text("最早安排 ${formatReviewDate(it)}") }
                    UiAction(label = "返回知识回顾", enabled = !busy, filled = true, onClick = {
                        command {
                            session = null
                            summary = repository.summary(identity)
                            recentDays = repository.reviewedDates(identity).sortedDescending().take(7)
                        }
                    })
                }
            }
            else -> {
                ReviewOverview(
                    summary = summary,
                    busy = busy,
                    onStart = {
                        command { session = repository.start(identity, ReviewScope.All, UUID.randomUUID().toString()) }
                    },
                    onChoose = { panel = ReviewPanel.MATERIALS },
                )
                val preview = summary?.preview.orEmpty()
                if (preview.isNotEmpty()) {
                    Text(
                        "待回顾内容",
                        style = UiRoles.text(MaterialTheme.typography.titleMedium),
                        modifier = Modifier.semantics { heading() },
                    )
                    preview.forEach { item -> ReviewPreviewCard(item) }
                }
                if (recentDays.isNotEmpty()) {
                    Text("近期知识回顾", style = MaterialTheme.typography.titleSmall)
                    Text(recentDays.joinToString(" · "))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (summary?.totalItemCount != 0) {
                        UiAction(label = "挑选素材", enabled = !busy, onClick = { panel = ReviewPanel.MATERIALS })
                    }
                    UiAction(label = "管理回顾卡", enabled = !busy, onClick = {
                        command {
                            management = repository.items(identity)
                            managePage = 0
                            panel = ReviewPanel.CARDS
                        }
                    })
                    UiAction(label = "刷新进度", enabled = !busy, onClick = { initialRefresh++ })
                }
            }
        }
    }
}

@Composable
private fun ReviewPageLayout(
    panel: ReviewPanel?,
    onBack: () -> Unit,
    focusTimer: ReviewFocusTimer? = null,
    content: @Composable (ReviewPanel?) -> Unit,
) {
    UiPageStack(panel?.let { listOf(it.name) }.orEmpty()) { visualStack ->
        val destination = visualStack.lastOrNull()?.let(ReviewPanel::valueOf)
        SubPageScaffold(
            title = destination?.title ?: "知识回顾",
            onBack = onBack,
            glassBack = true,
            actionId = "knowledge-review.back",
        ) {
            Column(
                Modifier.fillMaxSize()
                    .pointerInput(focusTimer) {
                        if (focusTimer == null) return@pointerInput
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.pressed }) focusTimer.interaction(SystemClock.elapsedRealtime())
                            }
                        }
                    }
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = GlassPageBarHeight + 8.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content(destination)
            }
        }
    }
}

private val reviewFocusSaver = Saver<ReviewFocusTimer, Long>(
    save = { it.activeMillis(SystemClock.elapsedRealtime()) },
    restore = { ReviewFocusTimer(it) },
)

@Composable
private fun ReviewFocusVisibility(timer: ReviewFocusTimer, enabled: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pageActive = LocalNavigationPageActive.current
    DisposableEffect(lifecycle, timer, enabled, pageActive) {
        fun update() {
            timer.setVisible(enabled && pageActive && lifecycle.currentState == Lifecycle.State.RESUMED,
                SystemClock.elapsedRealtime())
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            timer.setVisible(false, SystemClock.elapsedRealtime())
        }
    }
}

private fun formatFocusDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1_000L
    return when {
        seconds == 0L -> "不足 1 秒"
        seconds < 60L -> "$seconds 秒"
        else -> "${seconds / 60} 分 ${seconds % 60} 秒"
    }
}

private val reviewRatings = listOf(
    ReviewRating.FORGOT to "忘记了",
    ReviewRating.VAGUE to "有点模糊",
    ReviewRating.REMEMBERED to "记住了",
)

internal fun formatReviewDate(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
