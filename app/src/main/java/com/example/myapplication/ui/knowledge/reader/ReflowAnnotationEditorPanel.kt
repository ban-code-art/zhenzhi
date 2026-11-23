package com.example.myapplication.ui.knowledge.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.reader.notes.AnnotationColor
import com.example.myapplication.data.reader.notes.AnnotationStyle

/** Sibling overlay: opening the editor never changes the reader viewport's layout constraints. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ReflowAnnotationEditorPanel(
    state: ReflowAnnotationEditorState,
    onNoteChange: (String, String) -> Unit,
    onStyleChange: (String, AnnotationStyle) -> Unit,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    actionError: String? = null,
    closing: Boolean = false,
    onReload: ((String) -> Unit)? = null,
) {
    val draft = state.draft ?: return
    val editBlocked = state.editBlocked || state.sourceUnavailable || closing
    val closeBlocked = state.saving || state.outcomeUnknown || closing
    val status = when {
        state.saving -> "正在保存附注…"
        state.outcomeUnknown -> "结果待确认，请确认保存结果"
        closing -> "正在关闭附注…"
        state.sourceUnavailable -> "阅读会话已结束，草稿已保留；当前无法保存，可关闭面板"
        state.saved != null && !state.dirty -> "已保存，可继续编辑"
        state.dirty -> "有未保存的修改"
        else -> "选择颜色，写下你的理解"
    }
    Surface(
        modifier = modifier.testTag("reflow-annotation-editor"),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("附注", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose, enabled = !closeBlocked,
                    modifier = Modifier.testTag("reflow-annotation-close")) { Text("关闭") }
            }
            Text("引用原文", style = MaterialTheme.typography.labelLarge)
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)) {
                Text(draft.quote,
                    Modifier.fillMaxWidth().heightIn(max = 128.dp).verticalScroll(rememberScrollState())
                        .padding(12.dp).testTag("reflow-annotation-quote"),
                    style = MaterialTheme.typography.bodyMedium)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnnotationColor.entries.forEach { color ->
                    FilterChip(
                        selected = draft.style.color == color,
                        onClick = { onStyleChange(draft.draftId, draft.style.copy(color = color)) },
                        enabled = !editBlocked,
                        label = { Text(color.label) },
                        leadingIcon = {
                            Box(Modifier.size(14.dp).background(Color(android.graphics.Color.parseColor(color.hex)), CircleShape))
                        },
                        modifier = Modifier.testTag("reflow-annotation-color-${color.name}"),
                    )
                }
            }
            OutlinedTextField(
                value = draft.noteText.orEmpty(),
                onValueChange = { if (!state.sourceUnavailable) onNoteChange(draft.draftId, it) },
                enabled = !state.editBlocked && !closing,
                readOnly = state.sourceUnavailable,
                label = { Text("写下你的理解") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().testTag("reflow-annotation-note"),
            )
            Text(status,
                Modifier.fillMaxWidth().testTag("reflow-annotation-status")
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.outcomeUnknown) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            (if (state.outcomeUnknown) null else actionError ?:
                state.error?.takeUnless { state.sourceUnavailable }?.let(::annotationEditorErrorText))?.let { message ->
                Text(message,
                    Modifier.fillMaxWidth().testTag("reflow-annotation-error")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { onSave(draft.draftId) },
                enabled = state.conflictRevision == null && !state.saving && !closing && (!state.sourceUnavailable || state.outcomeUnknown) &&
                    (state.dirty || state.saved == null || state.outcomeUnknown),
                modifier = Modifier.fillMaxWidth().testTag("reflow-annotation-save"),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(when {
                    state.saving -> "正在保存…"
                    state.outcomeUnknown -> "确认保存结果"
                    state.error != null -> "重试保存"
                    state.saved != null && !state.dirty -> "已保存"
                    else -> "保存附注"
                })
            }
            if (state.conflictRevision != null && onReload != null) {
                Button(onClick = { onReload(draft.draftId) }, enabled = !state.editBlocked && !state.sourceUnavailable && !closing,
                    modifier = Modifier.fillMaxWidth().testTag("reflow-annotation-reload")) {
                    Text("加载最新版本，保留本地草稿")
                }
            }
        }
    }
}

private fun annotationEditorErrorText(code: String): String = when {
    code.contains("UNKNOWN", ignoreCase = true) -> "结果待确认，请重试保存"
    code.contains("TOO_LONG", ignoreCase = true) || code.contains("NOTE_LENGTH", ignoreCase = true) ->
        "笔记内容过长，请缩短后保存（最多 100 万字符）"
    code.contains("IDENTITY", ignoreCase = true) || code.contains("SESSION", ignoreCase = true) ||
        code.contains("OWNER", ignoreCase = true) -> "当前账号或阅读会话已变化，附注未保存；草稿已保留"
    code.contains("READ_ONLY", ignoreCase = true) -> "当前内容为只读，无法保存附注；草稿已保留"
    code.contains("CONFLICT", ignoreCase = true) || code.contains("REVISION", ignoreCase = true) ->
        "内容或附注版本已变化，暂时无法保存；草稿已保留"
    code.contains("SOURCE", ignoreCase = true) || code.contains("CANONICAL", ignoreCase = true) ||
        code.contains("STALE", ignoreCase = true) -> "原文或选区已变化，暂时无法保存；草稿已保留"
    else -> "附注未保存，请重试；草稿已保留"
}
