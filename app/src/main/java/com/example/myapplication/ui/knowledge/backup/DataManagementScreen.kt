package com.example.myapplication.ui.knowledge.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.reader.export.*
import com.example.myapplication.ui.components.LocalMainDockBottomInset
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.mine.GlassPageBarHeight
import com.example.myapplication.ui.mine.SubPageScaffold
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import java.util.Locale

/** Stateless product surface. Capabilities, previews, progress and results remain service facts. */
@Composable
fun DataManagementScreen(
    state: DataManagementState,
    onAction: (DataManagementAction) -> Unit,
    onBack: () -> Unit,
    entryMessage: String? = null,
) {
    val dockBottomInset = LocalMainDockBottomInset.current
    val scrollStates = remember(state.identity) { mutableMapOf<DataManagementStep, LazyListState>() }
    val scroll = scrollStates.getOrPut(state.step) { LazyListState() }
    Box(
        Modifier.fillMaxSize()
            .background(UiRoles.surface(UiSurfaceRole.Page))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        SubPageScaffold(title = "导出与恢复", onBack = onBack, glassBack = true, actionId = "data-management.back") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = scroll,
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("data-management-screen"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                        top = GlassPageBarHeight + 8.dp, bottom = 24.dp + dockBottomInset),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    entryMessage?.let { message -> item(key = "entry-feedback") {
                        DataCard(Modifier.testTag("data-management-entry-feedback")) {
                            SectionHeading("本次入口", icon = UiIcons.Info)
                            DetailText(message)
                        }
                    } }
                    if (state.signedIn) {
                        state.error?.let { error -> item(key = "service-error") {
                            DataCard(Modifier.testTag("data-management-error")) {
                                SectionHeading("操作未完成", icon = UiIcons.Info)
                                Text(error.message, color = UiRoles.color(UiColorRole.Error),
                                    style = UiRoles.text(MaterialTheme.typography.bodyMedium))
                                if (error.retryable) DetailText("可重新检查选择后重试，或离开再进入查看原任务。")
                            }
                        } }
                        if (state.recoveryIssues.isNotEmpty()) item(key = "recovery-issues") {
                            DataCard(Modifier.testTag("data-management-recovery-issues")) {
                                SectionHeading("上次恢复需要检查", "${state.recoveryIssues.size} 条记录", UiIcons.Restore)
                                DetailText("原记录已保留，系统未重新恢复这些数据。")
                                state.recoveryIssues.forEach { DetailText(it.message) }
                            }
                        }
                    }
                    when {
                        state.loading -> item(key = "loading") {
                            val title = when (state.step) {
                                DataManagementStep.EXPORT_PREVIEW -> "正在生成导出预览"
                                DataManagementStep.RESTORE_PREVIEW -> "正在更新恢复方案"
                                else -> "正在读取数据与备份任务"
                            }
                            Notice(title, "请稍候，当前操作不会因页面关闭而重新执行。", busy = true)
                        }
                        !state.signedIn -> item(key = "signed-out") {
                            Notice("登录后管理学习数据", "导出和恢复都归当前登录账号。请先登录，再重新进入此页。")
                        }
                        state.busy -> item(key = "running") { RunningContent(state, onAction) }
                        else -> when (state.step) {
                            DataManagementStep.SELECTION -> selectionContent(state, onAction)
                            DataManagementStep.EXPORT_PREVIEW -> exportContent(state, onAction)
                            DataManagementStep.RESTORE_PREVIEW -> restoreContent(state, onAction)
                            DataManagementStep.REPORT -> reportContent(state, onAction)
                            DataManagementStep.RUNNING, DataManagementStep.RESTORE_VALIDATING ->
                                item(key = "running") { RunningContent(state, onAction) }
                        }
                    }
                    item(key = "backup-scope") {
                        DataCard(Modifier.testTag("data-management-backup-scope")) {
                            SectionHeading("备份范围", icon = UiIcons.Lock)
                            DetailText("备份仅包含所选版本支持的学习数据。密钥、AI 配置和聊天记录不纳入备份。")
                            DetailText("恢复的数据归当前账号；实际范围以预览和最终报告为准。")
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.selectionContent(state: DataManagementState, onAction: (DataManagementAction) -> Unit) {
    item(key = "export-heading") {
        DataCard {
            SectionHeading("导出备份", "1 · 选择文档与内容，先预览再保存", UiIcons.Export)
            DetailText("本次选择归当前账号，预览通过后再选择文件保存位置。")
        }
    }
    item(key = "versions") {
        DataCard {
            SectionHeading("备份版本")
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DataManagementVersion.entries.forEach { version ->
                    val capability = state.capabilities.forVersion(version)
                    ChoiceRow(version.label(), state.selection.version == version,
                        state.canEditSelection && capability?.exportSupported == true,
                    ) { onAction(DataManagementAction.ChangeVersion(version)) }
                }
            }
            DataManagementVersion.entries.forEach { version ->
                val capability = state.capabilities.forVersion(version)
                val reason = capability?.unavailableReason
                    ?: if (capability?.exportSupported != true) "当前尚未支持此版本" else null
                reason?.let { DetailText("${version.label()}：$it") }
            }
        }
    }
    item(key = "documents-heading") { SectionHeading("要导出的文档", "已选择 ${state.selection.documentIds.size} 本") }
    if (state.documents.isEmpty()) item(key = "empty-documents") {
        Notice("暂无可导出的文档", "导入图书或保存笔记后，可以在这里生成备份。")
    }
    state.documents.forEachIndexed { index, document -> item(key = "select-document-$index-${document.id}") {
        DataCard(Modifier.testTag("data-management-document-${document.id}")) {
            CheckRow(document.title, document.id in state.selection.documentIds,
                state.canEditSelection && (document.available || document.id in state.selection.documentIds),
            ) { onAction(DataManagementAction.ToggleDocument(document.id)) }
            (document.unavailableReason ?: document.subtitle)?.let { DetailText(it) }
        }
    } }
    state.selection.documentIds.filter { id -> state.documents.none { it.id == id } }.forEachIndexed { index, id ->
        item(key = "missing-selection-$index") {
            DataCard {
                SectionHeading("一本文档当前不可用", icon = UiIcons.Books)
                DetailText("它仍在当前草稿中，需要移除或重新检查文档权限后才能导出。")
                DataAction("移除这项选择", "data-management.remove-unavailable", state.canEditSelection) {
                    onAction(DataManagementAction.ToggleDocument(id))
                }
            }
        }
    }
    item(key = "content-options") {
        DataCard {
            SectionHeading("包含内容")
            val capability = state.capabilities.forVersion(state.selection.version)
            val supported = capability?.supportedContent.orEmpty()
            DataManagementContent.entries.forEach { content ->
                CheckRow(content.label(), content in state.selection.content, state.canEditSelection && content in supported) {
                    onAction(DataManagementAction.ToggleContent(content))
                }
            }
        }
    }
    item(key = "preview-action") {
        DataCard {
            val capability = state.capabilities.forVersion(state.selection.version)
            val required = capability?.requiredContent.orEmpty()
            val unsupported = DataManagementContent.entries.filter { it !in capability?.supportedContent.orEmpty() }
            if (state.selection.content.isNotEmpty()) {
                DetailText("本次包含：${state.selection.content.joinToString("、") { it.label() }}。")
            }
            if (required.isNotEmpty()) {
                DetailText("此版本需要同时选择：${required.joinToString("、") { it.label() }}。")
            }
            if (unsupported.isNotEmpty()) {
                DetailText("当前版本尚未支持：${unsupported.joinToString("、") { it.label() }}。")
            }
            if (state.selectionPending) DetailText("正在保存选择，完成后可查看预览。")
            DataAction("查看导出预览", "data-management-preview-export", state.canPreviewExport, filled = true) {
                onAction(DataManagementAction.PreviewExport)
            }
        }
    }
    item(key = "restore-entry") {
        DataCard {
            SectionHeading("从备份恢复", "先校验备份包，再确认恢复方案", UiIcons.Restore)
            DetailText("已有内容、冲突和可用处理方式会在确认前列出。")
            DataAction("选择备份包", "data-management-pick-restore", state.canPickRestore) {
                onAction(DataManagementAction.PickRestore)
            }
            if (!state.capabilities.versions.any { it.restoreSupported }) DetailText("当前恢复服务尚未可用，请保留原备份包。")
        }
    }
}

private fun LazyListScope.exportContent(state: DataManagementState, onAction: (DataManagementAction) -> Unit) {
    val preview = state.exportPreview?.takeIf { it.identity == state.identity && it.selection == state.selection }
    if (preview == null) {
        item(key = "invalid-export-preview") {
            DataCard {
                SectionHeading("导出预览已失效", icon = UiIcons.Info)
                DetailText("请重新检查选择后生成预览。")
                DataAction("调整选择", "data-management-edit-selection", !state.loading && !state.busy) {
                    onAction(DataManagementAction.EditSelection)
                }
            }
        }
        return
    }
    item(key = "export-preview") {
        DataCard(Modifier.testTag("data-management-export-preview")) {
            SectionHeading("检查导出内容", "2 · ${preview.selection.version.label()}", UiIcons.Export)
            ContentCounts(preview.counts)
            HorizontalDivider(color = UiRoles.color(UiColorRole.OutlineVariant))
            DetailText("预计大小 ${sizeLabel(preview.estimatedBytes)}（${preview.estimatedBytes} 字节）")
            DetailText("包含：${preview.selection.content.joinToString("、") { it.label() }}")
        }
    }
    preview.documents.forEachIndexed { index, document -> item(key = "preview-document-$index") {
        DataCard { DocumentDetail(document) }
    } }
    preview.warnings.forEachIndexed { index, warning -> item(key = "export-warning-$index") {
        DataCard { DetailText(warning) }
    } }
    item(key = "save-export") {
        DataCard {
            SectionHeading("保存位置", icon = UiIcons.Directory)
            DetailText("尚未选择。下一步由系统文件选择器确认目录和文件名；取消会保留当前预览。")
            DataAction("选择保存位置", "data-management-save-export", state.canSaveExport, filled = true) {
                onAction(DataManagementAction.SaveExport(preview.id))
            }
            DataAction("调整选择", "data-management-edit-selection", !state.loading && !state.busy) {
                onAction(DataManagementAction.EditSelection)
            }
        }
    }
}

private fun LazyListScope.restoreContent(state: DataManagementState, onAction: (DataManagementAction) -> Unit) {
    val plan = state.restorePlan?.takeIf { it.identity == state.identity }
    if (plan == null) {
        item(key = "invalid-restore-preview") {
            DataCard {
                SectionHeading("恢复预览已失效", icon = UiIcons.Info)
                DetailText("请重新选择备份包。")
                DataAction("重新选择备份包", "data-management-pick-restore", state.canPickRestore) {
                    onAction(DataManagementAction.PickRestore)
                }
            }
        }
        return
    }
    item(key = "restore-plan") {
        DataCard(Modifier.testTag("data-management-restore-preview")) {
            SectionHeading("确认恢复内容", "2 · ${plan.version.label()}", UiIcons.Restore)
            ContentCounts(plan.counts)
        }
    }
    plan.documents.forEachIndexed { index, document -> item(key = "restore-document-$index") {
        DataCard { DocumentDetail(document) }
    } }
    item(key = "conflict-heading") { SectionHeading("冲突处理", "检测到 ${plan.counts.conflicts} 处冲突") }
    plan.conflicts.forEachIndexed { index, conflict -> item(key = "conflict-$index") {
        DataCard {
            Text(conflict.documentTitle, color = UiRoles.foreground, fontWeight = FontWeight.SemiBold,
                style = UiRoles.text(MaterialTheme.typography.bodyLarge))
            DetailText("${conflict.content.label()} · ${conflict.count} 项")
            DetailText(conflict.detail)
        }
    } }
    item(key = "conflict-policies") {
        DataCard {
            plan.conflictExplanation?.let { DetailText(it) }
            if (plan.supportedPolicies.isEmpty() && plan.conflictExplanation == null) {
                DetailText("此备份使用固定恢复规则，请查看方案中的冲突明细。")
            }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DataManagementConflictPolicy.entries.filter { it in plan.supportedPolicies }.forEach { policy ->
                    ChoiceRow(policy.label(), plan.policy == policy,
                        !state.loading && !state.busy && state.capabilities.forVersion(plan.version)?.restoreSupported == true,
                    ) { onAction(DataManagementAction.ChooseConflict(policy)) }
                }
            }
            if (plan.conflicts.isNotEmpty() && plan.supportedPolicies.isNotEmpty() && plan.policy == null) {
                DetailText("请选择一种实际可用的冲突处理方式。")
            }
        }
    }
    plan.warnings.forEachIndexed { index, warning -> item(key = "restore-warning-$index") {
        DataCard { DetailText(warning) }
    } }
    item(key = "restore-actions") {
        DataCard {
            if (state.capabilities.forVersion(plan.version)?.restoreSupported != true) {
                DetailText("当前尚未支持恢复此备份格式，请保留原备份包。")
            }
            plan.policy?.takeIf { it in plan.supportedPolicies }?.let { policy ->
                DetailText("本次处理方式：${policy.label()}，${policy.description()}。")
            }
            DataAction("确认恢复", "data-management-confirm-restore", state.canConfirmRestore, filled = true) {
                onAction(DataManagementAction.ConfirmRestore(plan.id))
            }
            DataAction("重新选择备份包", "data-management-pick-restore", state.canPickRestore) {
                onAction(DataManagementAction.PickRestore)
            }
            DataAction("返回内容选择", "data-management-edit-selection", !state.loading && !state.busy) {
                onAction(DataManagementAction.EditSelection)
            }
        }
    }
}

@Composable
private fun RunningContent(state: DataManagementState, onAction: (DataManagementAction) -> Unit) {
    val job = state.job?.takeIf { it.identity == state.identity }
    DataCard(Modifier.testTag("data-management-running")) {
        val title = if (state.step == DataManagementStep.RESTORE_VALIDATING) "正在校验备份包"
            else "正在${job?.operation?.productName() ?: "处理任务"}"
        SectionHeading(title, icon = if (job?.operation == DataManagementOperation.EXPORT) UiIcons.Export else UiIcons.Restore)
        Text(job?.stageTitle ?: "正在读取任务状态…", color = UiRoles.foreground,
            style = UiRoles.text(MaterialTheme.typography.bodyLarge))
        val status = when {
            job?.committed == true -> "数据已提交"
            state.canCancelJob -> "可取消"
            else -> "进行中"
        }
        Surface(color = UiRoles.surface(UiSurfaceRole.Inset), shape = RoundedCornerShape(16.dp)) {
            Text(status, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = UiRoles.accent,
                style = UiRoles.text(MaterialTheme.typography.labelLarge))
        }
        val total = job?.totalUnits
        val completed = job?.completedUnits ?: 0L
        if (total != null && total > 0L) {
            LinearProgressIndicator(progress = { (completed.toDouble() / total).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(), color = UiRoles.accent,
                trackColor = UiRoles.surface(UiSurfaceRole.Inset))
            DetailText("处理进度 $completed / $total")
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = UiRoles.accent,
                trackColor = UiRoles.surface(UiSurfaceRole.Inset))
        }
        DetailText("离开页面后任务仍在后台进行，可重新进入查看。返回页面与取消任务是两个独立操作。")
        if (job?.committed == true) DetailText("数据已提交，正在整理结果；已提交内容会保留。")
        DataAction(if (job?.committed == true) "数据已提交" else "取消当前任务",
            "data-management-cancel-job", state.canCancelJob) { onAction(DataManagementAction.CancelJob) }
        if (!state.canCancelJob && job?.committed != true) DetailText("当前阶段无法取消，请以最终报告为准。")
    }
}

private fun LazyListScope.reportContent(state: DataManagementState, onAction: (DataManagementAction) -> Unit) {
    val report = state.report?.takeIf { it.identity == state.identity }
    if (report == null) {
        item(key = "unavailable-report") { Notice("结果暂不可用", "请重新进入查看原任务。") }
        return
    }
    item(key = "report") {
        DataCard(Modifier.testTag("data-management-report")) {
            SectionHeading(report.productTitle(), "3 · 实际处理结果", UiIcons.Records)
            ContentCounts(report.counts)
            HorizontalDivider(color = UiRoles.color(UiColorRole.OutlineVariant))
            OutcomeCounts(report.counts)
            report.bytes?.let { DetailText("文件大小 ${sizeLabel(it)}（$it 字节）") }
            DetailText("提交状态：${if (report.committed) "已提交" else "未提交"}")
            if (report.committed) {
                DetailText(if (report.operation == DataManagementOperation.EXPORT) "备份已保存到本次选择的文件位置。"
                    else "本次已提交的数据会保留。")
            }
            if (report.result == DataManagementResult.PARTIAL) {
                DetailText("仍有未完成的内容，请查看下面的明细并保留备份包。")
            }
        }
    }
    report.issues.forEachIndexed { index, issue -> item(key = "report-issue-$index") {
        DataCard {
            DetailText(issue.message)
            if (issue.retryable) DetailText("可重新检查选择后重试；系统不会自动重新执行此操作。")
        }
    } }
    item(key = "dismiss-report") {
        DataAction("完成", "data-management-dismiss-report", !state.busy, filled = true) {
            onAction(DataManagementAction.DismissReport)
        }
    }
}

@Composable
private fun DataCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = UiRoles.surface(UiSurfaceRole.Card)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String? = null, icon: ImageVector? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(UiRoles.iconContainer),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = UiRoles.iconTint, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = UiRoles.foreground, style = UiRoles.text(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            subtitle?.let { DetailText(it) }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp))
        .background(if (checked) UiRoles.accent.copy(alpha = .08f) else UiRoles.surface(UiSurfaceRole.Card))
        .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
        .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = UiRoles.accent,
                uncheckedColor = UiRoles.color(UiColorRole.OnSurfaceVariant)))
        RowText(label, checked, enabled)
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp))
        .background(if (selected) UiRoles.accent.copy(alpha = .08f) else UiRoles.surface(UiSurfaceRole.Card))
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, enabled = enabled, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = UiRoles.accent,
                unselectedColor = UiRoles.color(UiColorRole.OnSurfaceVariant)))
        RowText(label, selected, enabled)
    }
}

@Composable
private fun RowScope.RowText(label: String, selected: Boolean, enabled: Boolean) {
    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = if (enabled) UiRoles.foreground else UiRoles.color(UiColorRole.OnSurfaceVariant),
            style = UiRoles.text(MaterialTheme.typography.bodyLarge),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun DataAction(label: String, tag: String, enabled: Boolean, filled: Boolean = false, onClick: () -> Unit) {
    UiAction(label = label, onClick = onClick, enabled = enabled, filled = filled,
        foreground = if (filled) null else UiRoles.accent,
        actionId = tag, modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
private fun Notice(title: String, detail: String, busy: Boolean = false) {
    DataCard {
        SectionHeading(title, icon = UiIcons.Database)
        DetailText(detail)
        if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = UiRoles.accent, strokeWidth = 2.dp)
    }
}

@Composable
private fun ContentCounts(counts: DataManagementCounts) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("文档 ${counts.documents} 本 · 笔记 ${counts.notes} 条", color = UiRoles.foreground,
            style = UiRoles.text(MaterialTheme.typography.bodyLarge))
        DetailText("标注 ${counts.annotations} 条 · 手写 ${counts.inkPages} 页 · 原书来源 ${counts.sources} 份")
        if (counts.reviewItems + counts.reviewEvents + counts.reviewSessions + counts.practiceArchives + counts.missingReviewSources > 0) {
            DetailText("回顾卡 ${counts.reviewItems} 张 · 历史事件 ${counts.reviewEvents} 条 · 历史会话 ${counts.reviewSessions} 次")
            DetailText("随堂档案 ${counts.practiceArchives} 份 · 缺少来源 ${counts.missingReviewSources} 张")
        }
    }
}

@Composable
private fun OutcomeCounts(counts: DataManagementCounts) {
    val values = listOf("新增" to counts.added, "复用" to counts.reused, "冲突" to counts.conflicts,
        "跳过" to counts.skipped, "损坏" to counts.damaged, "可重试" to counts.retryable)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        values.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, count) ->
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = UiRoles.surface(UiSurfaceRole.Inset)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            DetailText(label)
                            Text(count.toString(), color = UiRoles.foreground,
                                style = UiRoles.text(MaterialTheme.typography.headlineSmall))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentDetail(document: DataManagementDocument) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(UiIcons.Books, contentDescription = null, tint = UiRoles.iconTint, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(document.title, color = UiRoles.foreground, fontWeight = FontWeight.Medium,
                style = UiRoles.text(MaterialTheme.typography.bodyLarge))
            (document.unavailableReason ?: document.subtitle)?.let { DetailText(it) }
        }
    }
}

@Composable
private fun DetailText(text: String) {
    Text(text, color = UiRoles.color(UiColorRole.OnSurfaceVariant), style = UiRoles.text(MaterialTheme.typography.bodyMedium))
}

private fun sizeLabel(bytes: Long): String {
    val (value, unit) = when {
        bytes >= 1024L * 1024L * 1024L -> bytes / (1024.0 * 1024.0 * 1024.0) to "GiB"
        bytes >= 1024L * 1024L -> bytes / (1024.0 * 1024.0) to "MiB"
        bytes >= 1024L -> bytes / 1024.0 to "KiB"
        else -> return "$bytes B"
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, unit)
}

private fun DataManagementVersion.label() = when (this) {
    DataManagementVersion.V1 -> "基础备份（V1）"
    DataManagementVersion.V2 -> "学习备份（V2）"
    DataManagementVersion.V3 -> "知识回顾备份（V3）"
}
private fun DataManagementContent.label() = when (this) {
    DataManagementContent.NOTES -> "笔记"
    DataManagementContent.ANNOTATIONS -> "标注"
    DataManagementContent.INK -> "手写墨迹"
    DataManagementContent.SOURCES -> "原书与阅读来源"
    DataManagementContent.READING_STATE -> "阅读状态与偏好"
    DataManagementContent.LEARNING_STATE -> "学习记录与试卷收藏"
    DataManagementContent.REVIEW -> "知识回顾"
    DataManagementContent.STANDALONE_REVIEW -> "独立回顾卡"
}
private fun DataManagementConflictPolicy.label() = when (this) {
    DataManagementConflictPolicy.KEEP_LOCAL -> "保留本机内容"
    DataManagementConflictPolicy.COPY -> "另存完整副本"
    DataManagementConflictPolicy.REPLACE_REVIEW_SCHEDULE -> "替换回顾调度"
}
private fun DataManagementConflictPolicy.description() = when (this) {
    DataManagementConflictPolicy.KEEP_LOCAL -> "不覆盖已存在的内容"
    DataManagementConflictPolicy.COPY -> "为冲突内容保留独立副本"
    DataManagementConflictPolicy.REPLACE_REVIEW_SCHEDULE -> "同源回顾卡使用备份中的间隔、到期时间与暂停状态"
}
