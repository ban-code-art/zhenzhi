package com.example.myapplication.ui.mine.ai

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.AppContainer
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.ai.config.EndpointResolver
import com.example.myapplication.data.knowledge.KnowledgeStats
import com.example.myapplication.data.knowledge.KnowledgeStorageUsage
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiConfirmDialog
import com.example.myapplication.ui.components.UiNoticeHost
import com.example.myapplication.ui.components.UiNoticeVisuals
import com.example.myapplication.ui.mine.GlassPageBarHeight
import com.example.myapplication.ui.mine.SubPageScaffold
import com.example.myapplication.ui.theme.UiRoles
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun KnowledgeStatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as LearningApplication).container
    }
    val identity by container.sessionManager.identity.collectAsState(initial = null)
    key(identity) {
        KnowledgeStatsContent(context, container, identity, onBack)
    }
}

@Composable
private fun KnowledgeStatsContent(
    context: Context,
    container: AppContainer,
    identity: SessionIdentity?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val noticeHost = remember { SnackbarHostState() }
    var data by remember { mutableStateOf<StatsScreenData?>(null) }
    var loading by remember { mutableStateOf(identity?.userId != null) }
    var readError by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<StatsAction?>(null) }
    var activeAction by remember { mutableStateOf<StatsAction?>(null) }
    var pendingAction by remember { mutableStateOf<StatsAction?>(null) }
    var notice by remember { mutableStateOf<UiNoticeVisuals?>(null) }
    val busy = loading || activeAction != null

    suspend fun refresh() {
        val userId = identity?.userId ?: return
        loading = true
        readError = false
        try {
            val refreshed = withContext(Dispatchers.IO) { loadScreenData(context, container, userId) }
            if (container.sessionManager.identity.first() == identity) {
                data = refreshed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readError = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(identity) { refresh() }
    LaunchedEffect(notice) {
        val event = notice ?: return@LaunchedEffect
        noticeHost.showSnackbar(event)
    }

    fun runAction(action: StatsAction) {
        val actionOwner = identity ?: return
        val userId = actionOwner.userId ?: return
        val summary = data?.summary ?: return
        if (loading || activeAction != null || !action.isAvailable(summary)) return
        activeAction = action
        actionError = null
        notice = null
        scope.launch {
            try {
                val message = withContext(Dispatchers.IO) {
                    if (container.sessionManager.identity.first() != actionOwner) return@withContext null
                    executeAction(action, container, userId)
                }
                if (message != null && container.sessionManager.identity.first() == actionOwner) {
                    notice = UiNoticeVisuals(
                        eventId = UUID.randomUUID().toString(),
                        ownerKey = actionOwner,
                        message = message,
                        duration = SnackbarDuration.Long,
                    )
                    refresh()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionError = action
            } finally {
                activeAction = null
            }
        }
    }

    fun requestAction(action: StatsAction) {
        val summary = data?.summary ?: return
        if (loading || activeAction != null || !action.isAvailable(summary)) return
        if (action.confirmHint != null) pendingAction = action else runAction(action)
    }

    pendingAction?.let { action ->
        UiConfirmDialog(
            title = action.title,
            message = requireNotNull(action.confirmHint),
            confirmLabel = "确定",
            dismissLabel = "取消",
            ownerKey = identity to action,
            canConfirm = data?.summary?.let(action::isAvailable) == true,
            busy = busy,
            destructive = action == StatsAction.CancelPending,
            onConfirm = {
                pendingAction = null
                runAction(action)
            },
            onDismiss = { pendingAction = null },
            actionId = action.actionId,
        )
    }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        SubPageScaffold(
            title = "知识库统计",
            onBack = onBack,
            glassBack = true,
            actionId = "knowledge.stats.back",
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = GlassPageBarHeight + 8.dp, end = 16.dp, bottom = 80.dp)
                    .testTag("knowledge-stats"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val current = data
                when {
                    identity == null -> StatsProgress("统计中…")
                    identity.userId == null -> AiSectionCard {
                        StatsBody("请先登录后查看知识库统计。")
                    }
                    current == null && loading -> StatsProgress("统计中…")
                    current != null -> {
                        OverviewCard(current.summary)
                        MaintenanceCard(
                            summary = current.summary,
                            busy = busy,
                            activeAction = activeAction,
                            errorAction = actionError,
                            onAction = ::requestAction,
                        )
                        StorageCard(current.storage)
                    }
                }
                if (readError) {
                    AiSectionCard {
                        StatsError("知识库统计读取失败。")
                        UiAction(
                            label = "重新读取",
                            onClick = { if (!loading && activeAction == null) scope.launch { refresh() } },
                            enabled = !busy,
                            actionId = "knowledge.stats.refresh",
                        )
                    }
                }
            }
        }
        UiNoticeHost(
            hostState = noticeHost,
            modifier = Modifier.align(Alignment.BottomCenter),
            ownerKey = identity,
        )
    }
}

@Composable
private fun OverviewCard(summary: KnowledgeStats.Summary) {
    AiSectionCard {
        AiSectionHeader("索引概况")
        if (summary.documentCount == 0) {
            StatsBody("暂无已登记文档。")
        }
        StatRow("已登记文档", summary.documentCount.toString())
        StatRow("词法就绪", summary.lexicalReadyCount.toString())
        StatRow("当前代次分块", summary.chunkCount.toString())
        StatRow("当前 profile 向量", summary.embeddedCount.toString())
        StatRow("进行中任务", summary.pendingJobs.toString())
        StatRow("被阻断任务", summary.blockedJobs.toString())
        StatRow("永久失败任务", summary.failedJobs.toString())
        StatsBody("向量计数只反映当前配置使用的向量模型。")
        if (summary.failureReasons.isNotEmpty()) {
            AiSectionHeader("失败原因")
            summary.failureReasons.forEach { reason ->
                StatRow(reason.errorCode, reason.count.toString())
            }
        }
    }
}

@Composable
private fun MaintenanceCard(
    summary: KnowledgeStats.Summary,
    busy: Boolean,
    activeAction: StatsAction?,
    errorAction: StatsAction?,
    onAction: (StatsAction) -> Unit,
) {
    AiSectionCard {
        AiSectionHeader("维护操作")
        StatsAction.entries.forEach { action ->
            UiAction(
                label = action.title,
                onClick = { onAction(action) },
                enabled = !busy && action.isAvailable(summary),
                modifier = Modifier.fillMaxWidth(),
                visualModifier = Modifier.fillMaxWidth(),
                actionId = action.actionId,
            )
        }
        activeAction?.let { StatsProgress("正在处理「${it.title}」…") }
        errorAction?.let {
            StatsError("无法完成「${it.title}」，请重试。")
        }
    }
}

@Composable
private fun StorageCard(usage: KnowledgeStorageUsage.Usage) {
    AiSectionCard {
        AiSectionHeader("存储占用")
        StatRow("数据库（含 wal/shm）", formatBytes(usage.databaseBytes))
        StatRow("阅读数据目录", formatBytes(usage.readerDirectoryBytes))
        StatRow("其中 ANN 索引（可重建）", formatBytes(usage.annDirectoryBytes))
        StatRow("合计", formatBytes(usage.totalBytes))
        StatsBody("派生索引可以重建。此处仅显示占用情况。")
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = UiRoles.text(MaterialTheme.typography.bodyMedium),
        )
        Text(
            text = value,
            color = UiRoles.foreground,
            style = UiRoles.text(MaterialTheme.typography.bodyMedium),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatsBody(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = UiRoles.text(MaterialTheme.typography.bodyMedium),
    )
}

@Composable
private fun StatsError(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = UiRoles.text(MaterialTheme.typography.bodyMedium),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun StatsProgress(message: String) {
    Row(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = UiRoles.accent)
        StatsBody(message)
    }
}

private enum class StatsAction(
    val title: String,
    val actionId: String,
    val confirmHint: String? = null,
) {
    RetryFailed("重试失败任务", "knowledge.stats.retryFailed"),
    SchedulePending("立即调度待处理任务", "knowledge.stats.schedule"),
    CancelPending(
        "取消待处理任务",
        "knowledge.stats.cancelPending",
        "只取消尚未开始的任务，已发布内容、原文与笔记保留。",
    ),
    Rebuild(
        "重建索引",
        "knowledge.stats.rebuild",
        "按当前源内容登记新的索引代次，重建期间可继续检索旧索引。",
    );

    fun isAvailable(summary: KnowledgeStats.Summary): Boolean = when (this) {
        RetryFailed -> summary.blockedJobs > 0 || summary.failedJobs > 0
        SchedulePending, CancelPending -> summary.pendingJobs > 0
        Rebuild -> summary.documentCount > 0
    }
}

private data class StatsScreenData(
    val summary: KnowledgeStats.Summary,
    val storage: KnowledgeStorageUsage.Usage,
)

/** Read the captured account's counts with its active embedding profile. */
private suspend fun loadScreenData(
    context: Context,
    container: AppContainer,
    userId: Long,
): StatsScreenData {
    val settings = container.database.aiProfileDao().findSettings(userId)
    val embeddingProfileId = settings?.activeEmbeddingProfileId
    val profileFingerprint = embeddingProfileId?.let { id ->
        container.profileRepository.find(userId, id)?.takeIf { it.enabled }?.let { profile ->
            EndpointResolver.embeddingFingerprint(
                userId = userId,
                endpoint = profile.endpoint,
                protocol = profile.protocol,
                model = profile.model,
                paramsJson = profile.paramsJson,
            )
        }
    }
    val summary = container.knowledgeStats.summary(userId, profileFingerprint)
    return StatsScreenData(summary = summary, storage = measureStorage(context, container))
}

private suspend fun executeAction(action: StatsAction, container: AppContainer, userId: Long): String {
    return when (action) {
        StatsAction.RetryFailed -> {
            val requeued = container.knowledgeStats.retryFailed(userId)
            container.scheduleIndexing(userId)
            if (requeued > 0) {
                "已把 $requeued 个失败任务放回待处理队列并请求调度。"
            } else {
                "没有可重试的失败任务，已请求调度待处理任务。"
            }
        }

        StatsAction.SchedulePending -> {
            container.scheduleIndexing(userId)
            "已请求调度待处理任务。"
        }

        StatsAction.CancelPending -> {
            val cancelled = container.knowledgeStats.cancelPending(userId)
            "已取消 $cancelled 个尚未开始的任务。"
        }

        StatsAction.Rebuild -> {
            val registered = container.knowledgeStats.rebuild(userId)
            if (registered > 0) {
                container.scheduleIndexing(userId)
                "已登记 $registered 个重建任务并请求调度。"
            } else {
                "没有需要重建的文档（或已有重建任务在途）。"
            }
        }
    }
}

private fun measureStorage(context: Context, container: AppContainer): KnowledgeStorageUsage.Usage {
    val databaseFile = runCatching {
        container.database.openHelper.writableDatabase.path?.let(::File)
    }.getOrNull()
    val readerDirectory = File(context.applicationContext.filesDir, "reader")
    return KnowledgeStorageUsage.measure(
        databaseFile = databaseFile,
        readerDirectory = readerDirectory,
        annDirectory = File(readerDirectory, "ann"),
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index += 1
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}
