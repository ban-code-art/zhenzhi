package com.example.myapplication.ui.knowledge.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.reader.notes.*
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.ui.components.ColorSwatchOption
import com.example.myapplication.ui.components.ColorSwatchRow
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiConfirmDialog
import com.example.myapplication.ui.components.UiSheet
import com.example.myapplication.ui.knowledge.review.ReviewQuickAddAction
import com.example.myapplication.ui.theme.UiRoles

private val annotationColorOptions = AnnotationColor.entries.map {
    ColorSwatchOption(key = it.name, hex = it.hex, displayName = it.label)
}

@Composable
fun AnnotationActionSheet(state: AnnotationActionState, onNoteChange: (String?) -> Unit,
    onColorChange: (AnnotationColor) -> Unit, onSave: () -> Unit, onDelete: () -> Unit,
    onUndo: () -> Unit, onRetry: () -> Unit, onReload: () -> Unit, onDismiss: () -> Unit) {
    if (!state.isOpen || state.original == null) return
    var confirmDiscard by remember(state.original.id) { mutableStateOf(false) }
    val requestDismiss = {
        if (!state.busy && !state.outcomeUnknown) {
            if (state.dirty) confirmDiscard = true else onDismiss()
        }
    }
    val latestState by rememberUpdatedState(state)
    val editable = !state.busy && state.pending == null && !state.deleted
    UiSheet(
        title = if (state.deleted) "标注已删除" else "编辑标注",
        onDismiss = requestDismiss,
        confirmDismiss = {
            if (latestState.busy || latestState.outcomeUnknown) false
            else if (latestState.dirty) { confirmDiscard = true; false } else true
        },
        modifier = Modifier.testTag("annotation-action-sheet"),
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
            ) {
                UiAction(
                    label = "关闭",
                    onClick = requestDismiss,
                    enabled = !state.busy && !state.outcomeUnknown,
                    inheritedPane = true,
                    actionId = "annotation.edit.close",
                )
                if (state.pending != null && !state.busy && state.failure !is OperationResult.Conflict) {
                    UiAction(
                        label = if (state.outcomeUnknown) "确认保存结果" else "重试",
                        onClick = onRetry,
                        inheritedPane = true,
                        foreground = UiRoles.accent,
                        actionId = "annotation.edit.retry",
                    )
                }
                if (state.deleted) {
                    UiAction(
                        label = "撤销删除",
                        onClick = onUndo,
                        enabled = !state.busy && state.pending == null,
                        filled = true,
                        actionId = "annotation.edit.undo",
                    )
                } else {
                    UiAction(
                        label = "删除",
                        onClick = onDelete,
                        enabled = editable,
                        inheritedPane = true,
                        foreground = UiRoles.extended.destructive,
                        actionId = "annotation.edit.delete",
                    )
                    UiAction(
                        label = "保存",
                        onClick = onSave,
                        enabled = editable && state.dirty,
                        filled = true,
                        busy = state.busy,
                        actionId = "annotation.edit.save",
                    )
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                state.original.quote.orEmpty().take(240),
                style = MaterialTheme.typography.bodyLarge,
                color = UiRoles.foreground,
            )
            ColorSwatchRow(
                options = annotationColorOptions,
                selectedKey = state.draftStyle.color.name,
                onSelected = { key ->
                    AnnotationColor.entries.firstOrNull { it.name == key }?.let(onColorChange)
                },
                enabled = editable,
            )
            OutlinedTextField(
                value = state.draftNote.orEmpty(),
                onValueChange = { onNoteChange(it.take(1_000_000)) },
                enabled = editable,
                label = { Text("附注") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("annotation-note"),
            )
            AnnotationFailure(state.failure, state.busy, state.outcomeUnknown)
            if (!state.deleted && !state.original.quote.isNullOrBlank()) {
                ReviewQuickAddAction(
                    sourceKey = state.original.id to state.original.revision,
                    tag = "review-quick-add-annotation",
                    enabled = editable && !state.dirty && !state.outcomeUnknown,
                    inheritedPane = true,
                ) { identity ->
                    quickAnnotation(identity, state.original.id, state.original.revision)
                }
            }
            if (state.failure is OperationResult.Conflict) {
                UiAction(
                    label = "加载最新版本，保留草稿",
                    onClick = onReload,
                    enabled = !state.busy,
                    filled = true,
                    actionId = "annotation.edit.reload",
                )
            }
        }
    }
    if (confirmDiscard) AnnotationDiscardDialog(
        ownerKey = "annotation-edit-${state.original.id}",
        onKeep = { confirmDiscard = false },
        onDiscard = { confirmDiscard = false; onDismiss() },
    )
}

@Composable
fun AnnotationCreationSheet(state: AnnotationCreationState, onNoteChange: (String?) -> Unit,
    onColorChange: (AnnotationColor) -> Unit, onSave: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val draft = state.draft ?: return
    var confirmDiscard by remember(draft.locator) { mutableStateOf(false) }
    val requestDismiss = {
        if (!state.busy && !state.outcomeUnknown) {
            if (state.dirty) confirmDiscard = true else onDismiss()
        }
    }
    val latestState by rememberUpdatedState(state)
    val editable = !state.busy && state.pending == null && state.committed == null
    UiSheet(
        title = "标注所选原文",
        onDismiss = requestDismiss,
        confirmDismiss = {
            if (latestState.busy || latestState.outcomeUnknown) false
            else if (latestState.dirty) { confirmDiscard = true; false } else true
        },
        modifier = Modifier.testTag("annotation-creation-sheet"),
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
            ) {
                UiAction(
                    label = "关闭",
                    onClick = requestDismiss,
                    enabled = !state.busy && !state.outcomeUnknown,
                    inheritedPane = true,
                    actionId = "annotation.create.close",
                )
                if (state.pending != null && !state.busy) {
                    UiAction(
                        label = "确认保存结果",
                        onClick = onRetry,
                        inheritedPane = true,
                        foreground = UiRoles.accent,
                        actionId = "annotation.create.retry",
                    )
                }
                UiAction(
                    label = "保存标注",
                    onClick = onSave,
                    enabled = editable,
                    filled = true,
                    busy = state.busy,
                    actionId = "annotation.create.save",
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                draft.quote.take(240),
                style = MaterialTheme.typography.bodyLarge,
                color = UiRoles.foreground,
            )
            ColorSwatchRow(
                options = annotationColorOptions,
                selectedKey = draft.style.color.name,
                onSelected = { key ->
                    AnnotationColor.entries.firstOrNull { it.name == key }?.let(onColorChange)
                },
                enabled = editable,
            )
            OutlinedTextField(
                value = draft.noteText.orEmpty(),
                onValueChange = { onNoteChange(it.take(1_000_000)) },
                enabled = editable,
                label = { Text("附注（可选）") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("annotation-create-note"),
            )
            AnnotationFailure(state.failure, state.busy, state.outcomeUnknown)
        }
    }
    if (confirmDiscard) AnnotationDiscardDialog(
        ownerKey = "annotation-create-${draft.locator}",
        onKeep = { confirmDiscard = false },
        onDiscard = { confirmDiscard = false; onDismiss() },
    )
}

@Composable
private fun AnnotationFailure(failure: OperationResult<*>?, busy: Boolean, unknown: Boolean) {
    val errorColor = MaterialTheme.colorScheme.error
    when {
        busy -> Text("正在保存…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        unknown -> Text("保存结果待确认，草稿已保留。请重试确认。", color = errorColor)
        failure is OperationResult.Conflict -> Text("标注已被修改，本地草稿已保留。", color = errorColor)
        failure is OperationResult.Rejected -> Text("保存未完成：${failure.code}。草稿已保留。", color = errorColor)
        failure != null -> Text("保存失败，草稿已保留。", color = errorColor)
    }
}

@Composable
private fun AnnotationDiscardDialog(ownerKey: Any, onKeep: () -> Unit, onDiscard: () -> Unit) {
    UiConfirmDialog(
        title = "保留未保存的修改？",
        message = "关闭将放弃当前草稿。",
        confirmLabel = "放弃修改",
        dismissLabel = "继续编辑",
        onConfirm = onDiscard,
        onDismiss = onKeep,
        destructive = true,
        ownerKey = ownerKey,
        actionId = "annotation.discard",
    )
}
