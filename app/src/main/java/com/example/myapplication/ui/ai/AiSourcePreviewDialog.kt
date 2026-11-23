package com.example.myapplication.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

/** Shows the answer's verified source snapshot; navigation revalidates its identity at the boundary. */
@Composable
fun AiSourcePreviewDialog(
    source: AgentSourceUi,
    onDismiss: () -> Unit,
    onOpenSource: (AgentSourceUi) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("ai-source-preview"),
        containerColor = UiRoles.surface(UiSurfaceRole.Card),
        title = { Text("[${source.label}] ${source.documentTitle.ifBlank { "原文来源" }}") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                source.sectionTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                SelectionContainer {
                    Text(source.quote.ifBlank { "此来源没有保存摘录" }, Modifier.testTag("ai-source-preview-quote"),
                        style = MaterialTheme.typography.bodyLarge)
                }
                if (!source.canNavigate) Text(
                    source.unavailableReason ?: "此来源缺少定位信息，仍可查看和复制摘录。",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpenSource(source) }, enabled = source.canNavigate,
                modifier = Modifier.testTag("ai-source-preview-open")) { Text("跳回原文") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("ai-source-preview-close")) { Text("返回回答") }
        },
    )
}
