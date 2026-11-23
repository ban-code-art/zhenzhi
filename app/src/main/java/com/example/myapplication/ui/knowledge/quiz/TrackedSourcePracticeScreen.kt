package com.example.myapplication.ui.knowledge.quiz

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.quiz.*
import com.example.myapplication.data.review.isReviewableQuestion
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.knowledge.review.ReviewQuickAddAction
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest
import com.example.myapplication.ui.navigation.unifiedPageBack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Shared host for imported, AI draft and direct bundled-course exercises. */
@Composable
fun TrackedSourcePracticeScreen(
    practice: SourcePractice,
    scope: SourcePracticeScope,
    onBack: () -> Unit,
    onAskAi: ((SourcePracticeScope) -> Unit)? = null,
    repository: SourcePracticeArchiveRepository? = null,
    onGenerateAi: (() -> Unit)? = null,
    onGenerateMore: (() -> Unit)? = null,
    moreLoading: Boolean = false,
    moreExhausted: Boolean = false,
    moreError: String? = null,
    batchNumber: Int = 1,
    onCancelMore: (() -> Unit)? = null,
    moreExhaustionReason: String? = null,
    recordId: String? = null,
    initialQuestionId: String? = null,
    sourceWarning: String? = null,
) {
    val context = LocalContext.current
    val archive = repository ?: (context.applicationContext as LearningApplication).container.sourcePracticeArchiveRepository
    val identity by archive.identityFlow.collectAsState(SessionIdentity(null, -1))
    val uiScope = rememberCoroutineScope()
    var record by remember(practice.id, scope, recordId, identity) { mutableStateOf<SourcePracticeRecord?>(null) }
    var loading by remember(practice.id, scope, recordId, identity) { mutableStateOf(true) }
    var error by remember(practice.id, scope, recordId, identity) { mutableStateOf<String?>(null) }
    var saveError by remember(practice.id, scope, recordId, identity) { mutableStateOf<String?>(null) }
    var retry by remember(practice.id, scope, recordId, identity) { mutableStateOf(0) }
    var favoritePending by remember(practice.id, scope, recordId, identity) { mutableStateOf(false) }
    var favoriteError by remember(practice.id, scope, recordId, identity) { mutableStateOf<String?>(null) }
    var leaving by remember(practice.id, scope, recordId, identity) { mutableStateOf(false) }

    LaunchedEffect(practice.id, scope, recordId, initialQuestionId, identity, retry) {
        record = null
        error = null
        loading = true
        val captured = identity
        try {
            val prepared = if (recordId == null) archive.ensure(captured, practice, scope, batchNumber)
                else archive.load(captured, recordId) ?: throw SourcePracticeArchiveException("这份随堂记录已不存在。")
            if (prepared.practice != practice || prepared.scope != scope.normalized(practice))
                throw SourcePracticeArchiveException("记录的题目或章节来源已变化，请重新打开。")
            val questionIndex = initialQuestionId?.let { question -> prepared.practice.questions.indexOfFirst { it.id == question } }
            if (questionIndex != null && questionIndex < 0) throw SourcePracticeArchiveException("收藏的题目已不在这份记录中。")
            if (archive.identityFlow.first() != captured) return@LaunchedEffect
            record = if (questionIndex == null) prepared else prepared.copy(progress = prepared.progress.copy(index = questionIndex, summary = false))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (archive.identityFlow.first() == captured)
                error = (failure as? SourcePracticeArchiveException)?.message ?: "读取随堂记录失败，请重试。"
        } finally { if (archive.identityFlow.first() == captured) loading = false }
    }

    val loaded = record
    if (loaded == null) {
        val requestBack = unifiedPageBack(onBack)
        Box(Modifier.fillMaxSize().testTag("source-practice-archive-host"), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) { CircularProgressIndicator(); Text("正在恢复随堂练习…") }
                else {
                    Text(error ?: "当前没有可恢复的随堂练习。", modifier = Modifier.testTag("source-practice-archive-error"))
                    UiAction(label = "重试", onClick = { retry++ }, filled = true,
                        modifier = Modifier.testTag("source-practice-archive-retry"), actionId = "sourcePractice.archive.retry")
                }
                UiAction(label = "返回阅读", onClick = requestBack, actionId = "sourcePractice.archive.back")
            }
        }
        return
    }

    val captured = identity
    val activeRecordId = loaded.id
    val latestProgress = remember(activeRecordId, captured) { mutableStateOf(loaded.progress) }
    fun accept(updated: SourcePracticeRecord) {
        val current = record
        if (current?.id == updated.id && updated.updatedAt >= current.updatedAt) record = updated
    }
    fun persist(progress: SourcePracticeProgress) {
        latestProgress.value = progress.normalized(practice)
        val pending = archive.enqueueProgress(captured, activeRecordId, latestProgress.value)
        uiScope.launch {
            try {
                val updated = pending.await()
                if (archive.identityFlow.first() == captured && record?.id == activeRecordId) { accept(updated); saveError = null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (archive.identityFlow.first() == captured && record?.id == activeRecordId)
                    saveError = "作答记录暂未保存，请重试保存。"
            }
        }
    }
    val flushThen: (() -> Unit) -> Unit = action@{ action ->
        if (leaving) return@action
        leaving = true
        uiScope.launch(Dispatchers.Main.immediate) {
            try {
                val saved = archive.enqueueProgress(captured, activeRecordId, latestProgress.value).await()
                if (archive.identityFlow.first() == captured && record?.id == activeRecordId) {
                    accept(saved); saveError = null; action()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (archive.identityFlow.first() == captured && record?.id == activeRecordId)
                    saveError = "作答记录暂未保存，请重试保存。"
            } finally { if (archive.identityFlow.first() == captured && record?.id == activeRecordId) leaving = false }
        }
    }
    val back = unifiedBackRequest(BackState(page = BackPage.CHILD, imeVisible = isBackImeVisible())) { decision ->
        if (decision == BackDecision.PopPage) {
            onCancelMore?.invoke()
            flushThen(onBack)
        }
    }
    DisposableEffect(archive, captured, activeRecordId) {
        onDispose { archive.enqueueProgress(captured, activeRecordId, latestProgress.value) }
    }

    Column(Modifier.fillMaxSize()) {
        if (saveError != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("source-practice-save-error"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(saveError.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                UiAction(label = "重试保存", onClick = { persist(latestProgress.value) }, enabled = !leaving,
                    actionId = "sourcePractice.retrySave")
            }
        }
        favoriteError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("source-practice-favorite-error"),
                style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.weight(1f)) {
            SourcePracticeScreen(
                practice = practice,
                onBack = back,
                onGenerateAi = onGenerateAi?.takeIf { sourceWarning == null }?.let { action -> { flushThen(action) } },
                onGenerateMore = onGenerateMore?.takeIf { sourceWarning == null }?.let { action -> { flushThen(action) } },
                moreLoading = moreLoading,
                moreExhausted = moreExhausted,
                moreError = moreError,
                batchNumber = loaded.batchNumber,
                onCancelMore = onCancelMore,
                moreExhaustionReason = moreExhaustionReason,
                onAskAi = onAskAi?.takeIf { sourceWarning == null }?.let { action -> { flushThen { action(loaded.scope) } } },
                initialProgress = latestProgress.value,
                onProgressChange = ::persist,
                favoriteQuestionIds = loaded.favoriteQuestionIds,
                onToggleFavorite = { question ->
                    if (!favoritePending && !leaving) {
                        favoritePending = true
                        favoriteError = null
                        uiScope.launch {
                            try {
                                val updated = archive.toggleFavorite(captured, activeRecordId, question)
                                if (archive.identityFlow.first() == captured && record?.id == activeRecordId) accept(updated)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                if (archive.identityFlow.first() == captured && record?.id == activeRecordId)
                                    favoriteError = "收藏暂未保存，请重新操作。"
                            } finally {
                                favoritePending = false
                            }
                        }
                    }
                },
                archiveRecordId = activeRecordId,
                sourceWarning = sourceWarning,
                leaving = leaving,
                favoritePending = favoritePending,
                reviewAction = { questionId, visible ->
                    if (sourceWarning == null && loaded.isReviewableQuestion(questionId)) {
                        ReviewQuickAddAction(
                            sourceKey = activeRecordId to questionId,
                            tag = "review-quick-add-practice",
                            enabled = visible && !favoritePending && saveError == null,
                            expectedIdentity = captured,
                        ) { account -> quickPractice(account, activeRecordId, questionId) }
                    }
                },
            )
        }
    }
}
