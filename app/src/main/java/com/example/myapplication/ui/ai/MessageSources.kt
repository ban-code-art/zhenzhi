package com.example.myapplication.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

/**
 * 回答来源卡片（W4-11）。
 *
 * 设计 §5/§17：显示书名、章节、短 quote；**不要把 RRF 分当正确概率**。
 * 来源失效时保留历史引文。调用方可解释失效原因，导航仍只接有效来源。
 * 真实跳转由 SourceNavigator 完成（接线阶段）；这里是纯展示 + 回调。
 */
@Composable
fun AgentSourcesBlock(
    sources: List<AgentSourceUi>,
    onOpenSource: (AgentSourceUi) -> Unit,
    modifier: Modifier = Modifier,
    onUnavailableSource: ((AgentSourceUi) -> Unit)? = null,
    showHeading: Boolean = true,
) {
    if (sources.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showHeading) {
            Text(
                text = "来源（${sources.size}）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sources.forEach { source ->
            SourceCard(source = source, onOpenSource = onOpenSource, onUnavailableSource = onUnavailableSource)
        }
    }
}

@Composable
fun SourceCard(
    source: AgentSourceUi,
    onOpenSource: (AgentSourceUi) -> Unit,
    modifier: Modifier = Modifier,
    onUnavailableSource: ((AgentSourceUi) -> Unit)? = null,
) {
    val clickableModifier = when {
        source.canNavigate -> Modifier.clickable { onOpenSource(source) }
        onUnavailableSource != null -> Modifier.clickable { onUnavailableSource(source) }
        else -> Modifier
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier),
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        source.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        source.documentTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = UiRoles.foreground,
                    )
                    source.sectionTitle?.takeIf { it.isNotBlank() }?.let { section ->
                        Text(section, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (source.canNavigate) Icon(
                    UiIcons.Chevron,
                    contentDescription = null,
                    tint = UiRoles.accent,
                )
            }
            Text(
                text = "“${source.quote}”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
            )
            if (!source.canNavigate) {
                Text(
                    text = source.unavailableReason ?: "来源不可用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
