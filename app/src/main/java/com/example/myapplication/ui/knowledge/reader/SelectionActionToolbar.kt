package com.example.myapplication.ui.knowledge.reader

import androidx.compose.runtime.Composable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Inline overlay only: native gestures and focus remain owned by the attached WebView. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SelectionActionToolbar(
    state: ReflowSelectionToolbarState,
    onConfirm: (ReflowSelectionCapture) -> Unit,
    onCopy: (ReflowSelectionCapture) -> Unit,
    onClear: (ReflowSelectionCapture) -> Unit,
    onDismiss: (ReflowSelectionCapture) -> Unit,
    modifier: Modifier = Modifier,
    onAnnotate: ((ReflowSelectionCapture) -> Unit)? = null,
    annotationEnabled: Boolean = false,
    bookmarkContent: (@Composable () -> Unit)? = null,
    onHighlight: ((ReflowSelectionCapture) -> Unit)? = null,
) {
    val capture = state.capture ?: return
    if (!state.visible) return
    Surface(
        modifier = modifier.testTag("reflow-selection-toolbar"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            state.statusText?.let { message ->
                Text(message,
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("reflow-selection-feedback").semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.contains("失败") || message.contains("未完成")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (state.mode != ReflowSelectionToolbarMode.STABLE) {
                    TextButton(onClick = { onConfirm(capture) }, enabled = state.confirmEnabled,
                        modifier = Modifier.testTag("reflow-selection-confirm")) { Text("确认选区") }
                }
                TextButton(onClick = { onCopy(capture) }, enabled = state.copyEnabled,
                    modifier = Modifier.testTag("reflow-selection-copy")) { Text("复制") }
                if (onAnnotate != null) {
                    TextButton(onClick = { onAnnotate(capture) }, enabled = annotationEnabled,
                        modifier = Modifier.testTag("reflow-selection-annotate")) { Text("附注") }
                }
                if (onHighlight != null) {
                    TextButton(onClick = { onHighlight(capture) }, enabled = annotationEnabled,
                        modifier = Modifier.testTag("reflow-selection-highlight")) { Text("高亮") }
                }
                bookmarkContent?.invoke()
                TextButton(onClick = { onClear(capture) }, enabled = state.clearEnabled,
                    modifier = Modifier.testTag("reflow-selection-clear")) { Text("清除") }
                if (state.dismissEnabled) {
                    TextButton(onClick = { onDismiss(capture) }, modifier = Modifier.testTag("reflow-selection-dismiss")) { Text("收起") }
                }
            }
        }
    }
}
