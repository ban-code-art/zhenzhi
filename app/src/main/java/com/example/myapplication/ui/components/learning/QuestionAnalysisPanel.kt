package com.example.myapplication.ui.components.learning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiInlineExpand
import com.example.myapplication.ui.theme.UiRoles

data class LearningSource(
    val key: String,
    val label: String,
    val quote: String,
    val available: Boolean = true,
    val unavailableReason: String = "来源已失效，保留历史摘录",
)

data class LearningAnalysis(
    val answer: String,
    val analysis: String,
    val knowledgePoint: String = "",
    val rubric: List<String> = emptyList(),
    val sources: List<LearningSource> = emptyList(),
)

fun LearningAnalysis.visibleAnalysis(submitted: Boolean): LearningAnalysis? = if (submitted) this else null

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionAnalysisPanel(
    analysis: LearningAnalysis,
    submitted: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onOpenSource: (LearningSource) -> Unit = {},
) {
    val visible = analysis.visibleAnalysis(submitted) ?: return
    UiInlineExpand(
        ownerKey = visible,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.fillMaxWidth(),
        trigger = { modifier, _, enabled, toggle ->
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UiAction(
                    label = if (expanded) "收起解析" else "展开解析",
                    onClick = toggle,
                    enabled = enabled,
                    modifier = modifier,
                    actionId = "learning.analysis.toggle",
                )
                if (onToggleFavorite != null) {
                    UiAction(
                        label = if (isFavorite) "取消收藏" else "收藏",
                        onClick = onToggleFavorite,
                        actionId = "learning.analysis.favorite",
                    )
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("参考答案：${visible.answer}", style = UiRoles.text(MaterialTheme.typography.titleSmall))
            if (visible.analysis.isNotBlank()) Text(visible.analysis)
            if (visible.knowledgePoint.isNotBlank()) Text("知识点：${visible.knowledgePoint}")
            visible.rubric.forEach { Text("• $it") }
            visible.sources.forEach { source ->
                if (source.quote.isNotBlank()) Text(source.quote)
                if (source.available) {
                    UiAction(label = source.label, onClick = { onOpenSource(source) },
                        actionId = "learning.analysis.source")
                } else {
                    Text("${source.label}（${source.unavailableReason}）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
