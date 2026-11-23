package com.example.myapplication.ui.knowledge.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.reader.ReaderShelfItem
import com.example.myapplication.data.reader.export.RestorePreview
import com.example.myapplication.data.reader.export.RestoreReport
import com.example.myapplication.ui.knowledge.BackupUiState
import com.example.myapplication.ui.theme.ReaderTokens
import java.io.File

/**
 * 已退休的 W5-05 / W5-06 对话框，只供历史视觉与脚手架测试。
 * 生产设置、书库和单书入口统一使用 AppDataManagementRoute；不再调用本文件。
 *
 * 只做三件事：选文档与内容类型 → 显示"导出成功，可分享的 zip"；恢复时先给**预览**再让用户确认；
 * 恢复完成后展示 added/reused/conflicts/damaged 明细。所有判断（能否导出、冲突如何处理）
 * 都在 data 层，UI 不重复实现规则，也不把失败显示成成功。
 */
@Composable
@Deprecated("Retired production UI; retained for legacy visual tests. Use AppDataManagementRoute.")
fun BackupDialog(
    documents: List<ReaderShelfItem>,
    preselected: String?,
    backup: BackupUiState,
    onExport: (List<String>, Boolean, Boolean, Boolean) -> Unit,
    onPickRestore: () -> Unit,
    onShare: (File) -> Unit,
    onClearExport: () -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember(preselected) {
        mutableStateOf(preselected?.let { setOf(it) } ?: emptySet())
    }
    val notes = remember { mutableStateOf(true) }
    val annotations = remember { mutableStateOf(true) }
    val ink = remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = { if (!backup.busy) onDismiss() },
        title = { Text("导出与恢复") },
        text = {
            Column {
                val result = backup.exportResult
                if (result != null) {
                    Text("已导出 ${result.documentCount} 本书的笔记", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append("笔记 ${result.noteFileCount} 条 · 标注 ${result.annotationCount} 条 · 手写 ${result.inkPageCount} 页")
                            append("\n文件大小 ${"%.1f".format(result.bytes / (1024.0 * 1024.0))} MiB")
                            if (result.rejectedDocuments > 0) append("\n跳过 ${result.rejectedDocuments} 本（无权限/未就绪）")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ReaderTokens.TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "备份包不含原书正文与聊天记录；恢复时只归当前账号。",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReaderTokens.TextSecondary,
                    )
                } else {
                    Text("选择要导出的书", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    if (documents.isEmpty()) {
                        Text("书架里还没有可导出的书", style = MaterialTheme.typography.bodySmall, color = ReaderTokens.TextSecondary)
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                            items(documents, key = { it.documentId }) { item ->
                                val checked = item.documentId in selected.value
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        enabled = !backup.busy,
                                        onCheckedChange = { value ->
                                            selected.value = if (value) {
                                                selected.value + item.documentId
                                            } else {
                                                selected.value - item.documentId
                                            }
                                        },
                                    )
                                    Text(item.title, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("包含内容", fontWeight = FontWeight.SemiBold)
                    ToggleRow("笔记", notes.value, !backup.busy) { notes.value = it }
                    ToggleRow("标注", annotations.value, !backup.busy) { annotations.value = it }
                    ToggleRow("手写墨迹", ink.value, !backup.busy) { ink.value = it }
                    if (backup.exporting) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("正在导出…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (backup.error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(backup.error, style = MaterialTheme.typography.bodySmall, color = ReaderTokens.PrimaryDark)
                    }
                }
            }
        },
        confirmButton = {
            val result = backup.exportResult
            if (result != null) {
                TextButton(onClick = { onShare(result.zip) }) { Text("分享备份包") }
            } else {
                TextButton(
                    enabled = !backup.busy && selected.value.isNotEmpty(),
                    onClick = {
                        onExport(selected.value.toList(), notes.value, annotations.value, ink.value)
                    },
                ) { Text("导出") }
            }
        },
        dismissButton = {
            if (backup.exportResult != null) {
                TextButton(onClick = { onClearExport(); onDismiss() }) { Text("完成") }
            } else {
                TextButton(onClick = { onClearError(); onPickRestore() }, enabled = !backup.busy) { Text("从备份包恢复…") }
            }
        },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 恢复前的预览与确认：条数、是否同书、预计冲突、无源文档数。 */
@Composable
@Deprecated("Retired production UI; retained for legacy visual tests. Use AppDataManagementRoute.")
fun RestorePreviewDialog(
    backup: BackupUiState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val preview: RestorePreview = backup.restorePreview ?: return
    AlertDialog(
        onDismissRequest = { if (!backup.restoring) onCancel() },
        title = { Text("确认恢复") },
        text = {
            Column {
                Text(
                    "来自 ${preview.appVersion} 的备份包",
                    style = MaterialTheme.typography.bodySmall,
                    color = ReaderTokens.TextSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Text("文档 ${preview.documentCount} 本 · 笔记 ${preview.noteCount} 条 · 标注 ${preview.annotationCount} 条 · 手写 ${preview.inkFileCount} 页")
                Spacer(Modifier.height(6.dp))
                if (preview.reusableDocumentIds.isNotEmpty()) {
                    Text(
                        "其中 ${preview.reusableDocumentIds.size} 本可复用本机已有书（不会重复占空间）",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReaderTokens.TextSecondary,
                    )
                }
                if (preview.noSourceDocumentCount > 0) {
                    Text(
                        "${preview.noSourceDocumentCount} 本在书架上找不到原书，恢复后只能看笔记、打不开正文",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReaderTokens.PrimaryDark,
                    )
                }
                if (preview.predictedConflicts > 0) {
                    Text(
                        "预计 ${preview.predictedConflicts} 处冲突：将保留本机内容并另存副本，不会覆盖",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReaderTokens.PrimaryDark,
                    )
                }
                if (preview.invalidAnnotations > 0) {
                    Text(
                        "${preview.invalidAnnotations} 条标注无法映射，将被跳过",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReaderTokens.TextSecondary,
                    )
                }
                if (backup.restoring) {
                    Spacer(Modifier.height(10.dp))
                    CircularProgressIndicator(Modifier.height(20.dp).fillMaxWidth(0.08f), strokeWidth = 2.dp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !backup.restoring, onClick = onConfirm) { Text("开始恢复") }
        },
        dismissButton = {
            TextButton(enabled = !backup.restoring, onClick = onCancel) { Text("取消") }
        },
    )
}

/** 恢复结果：新增/复用/冲突/损坏/可重试，逐条列问题；PARTIAL 绝不显示成成功。 */
@Composable
@Deprecated("Retired production UI; retained for legacy visual tests. Use AppDataManagementRoute.")
fun RestoreReportDialog(report: RestoreReport, onDismiss: () -> Unit) {
    val summary = report.summary()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (report.fullyApplied) "恢复完成" else "恢复部分完成") },
        text = {
            Column {
                Text(
                    buildString {
                        append("新增 ${summary["added"]} 项 · 复用 ${summary["reused"]} 项")
                        if ((summary["conflicts"] ?: 0) > 0) append(" · 冲突 ${summary["conflicts"]} 项")
                        if ((summary["damaged"] ?: 0) > 0) append(" · 损坏 ${summary["damaged"]} 项")
                        if ((summary["retryable"] ?: 0) > 0) append(" · 可重试 ${summary["retryable"]} 项")
                        if ((summary["skipped"] ?: 0) > 0) append(" · 跳过 ${summary["skipped"]} 项")
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "文档 新增 ${report.documentsAdded} / 复用 ${report.documentsReused} / 无源 ${report.documentsNoSource}；" +
                        "笔记 新增 ${report.notesAdded} / 副本 ${report.noteCopies}；" +
                        "标注 新增 ${report.annotationsAdded}；手写 新增 ${report.inkPagesAdded}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ReaderTokens.TextSecondary,
                )
                if (report.issues.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(report.issues.take(20)) { issue ->
                            Text("· ${issue.code}：${issue.detail}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (report.documentIdMap.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已恢复的文档会出现在书架；无源文档标记为“待关联来源”。",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReaderTokens.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
