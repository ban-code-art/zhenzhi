package com.example.myapplication.ui.knowledge.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.review.ReviewItemEntity
import com.example.myapplication.data.review.ReviewSummary
import com.example.myapplication.ui.components.LocalUiBackdrop
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

@Composable
internal fun KnowledgeReviewHomeCard(summary: ReviewSummary?, failed: Boolean, open: () -> Unit) {
    val hasSession = summary?.activeSessionId != null
    val count = if (hasSession) summary?.remainingCount else summary?.dueCount
    val countLabel = if (hasSession) "本轮剩余" else "待回顾"
    val detail = when {
        failed -> "暂时无法载入回顾安排"
        summary == null -> "正在读取回顾安排"
        hasSession -> "上次回顾尚未完成"
        summary.totalItemCount == 0 -> "从标注、笔记和做过的题目中挑选素材"
        summary.dueCount > 0 -> "预计约 ${summary.estimatedMinutes} 分钟"
        summary.nextDueAtUtc != null -> "下次回顾 ${formatReviewDate(summary.nextDueAtUtc)}"
        else -> "今天已回顾 ${summary.todayReviewedCount} 张"
    }
    ReviewPaper(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("knowledge-review-home"),
        tinted = true,
        spacing = 12,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stack = maxWidth < 250.dp || LocalDensity.current.fontScale >= 1.35f
            val showCount = !failed && count != null && (count > 0 || summary?.totalItemCount != 0)
            if (stack) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewHomeHeading(detail)
                    if (showCount) ReviewMetric(checkNotNull(count), countLabel)
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f)) { ReviewHomeHeading(detail) }
                    if (showCount) ReviewMetric(checkNotNull(count), countLabel)
                }
            }
        }
        UiAction(
            label = when {
                hasSession -> "继续上次"
                summary?.totalItemCount == 0 -> "挑选素材"
                else -> "打开知识回顾"
            },
            onClick = open,
            actionId = "knowledge-review.open",
            filled = hasSession || (count ?: 0) > 0,
        )
    }
}

@Composable
private fun ReviewHomeHeading(detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            ReviewBookMark(size = 32)
            Text(
                "知识回顾",
                style = UiRoles.text(MaterialTheme.typography.titleLarge),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(detail, style = UiRoles.text(MaterialTheme.typography.bodyMedium), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ReviewOverview(
    summary: ReviewSummary?,
    busy: Boolean,
    onStart: () -> Unit,
    onChoose: () -> Unit,
) {
    ReviewPaper(
        modifier = Modifier.fillMaxWidth().testTag("knowledge-review-overview"),
        tinted = true,
        spacing = 14,
    ) {
        Text(
            "今日回顾",
            style = UiRoles.text(MaterialTheme.typography.titleLarge),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        if (summary == null) {
            Text(
                if (busy) "正在读取回顾安排" else "暂时无法读取回顾安排",
                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("knowledge-review-due")) {
                if (maxWidth < 220.dp || LocalDensity.current.fontScale >= 1.5f) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReviewMetric(summary.dueCount, "待回顾")
                        ReviewMetric(summary.todayReviewedCount, "今日已回顾")
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReviewMetric(summary.dueCount, "待回顾", Modifier.weight(1f))
                        ReviewMetric(summary.todayReviewedCount, "今日已回顾", Modifier.weight(1f))
                    }
                }
            }
            val detail = when {
                summary.activeSessionId != null -> "本轮剩余 ${summary.remainingCount} 张"
                summary.dueCount > 0 -> "预计约 ${summary.estimatedMinutes} 分钟"
                summary.totalItemCount == 0 -> "加入标注、笔记或题目，开始建立回顾卡"
                summary.nextDueAtUtc != null -> "下次回顾 ${formatReviewDate(summary.nextDueAtUtc)}"
                else -> "今天的回顾已完成"
            }
            Text(detail, style = UiRoles.text(MaterialTheme.typography.bodyMedium), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val canStart = summary.activeSessionId != null || summary.dueCount > 0
            if (canStart || summary.totalItemCount == 0) {
                UiAction(
                    label = when {
                        summary.activeSessionId != null -> "继续上次"
                        canStart -> "开始回顾"
                        else -> "挑选素材"
                    },
                    onClick = if (canStart) onStart else onChoose,
                    enabled = !busy,
                    filled = true,
                )
            }
        }
    }
}

@Composable
private fun ReviewMetric(count: Int, label: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = lerp(UiRoles.surface(UiSurfaceRole.Card), colors.primaryContainer, .32f),
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    count.toString(),
                    style = UiRoles.text(MaterialTheme.typography.headlineMedium),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.alignByBaseline(),
                )
                Text("张", style = UiRoles.text(MaterialTheme.typography.labelMedium), modifier = Modifier.alignByBaseline())
            }
            Text(label, style = UiRoles.text(MaterialTheme.typography.labelMedium), color = colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ReviewPreviewCard(item: ReviewItemEntity) {
    ReviewPaper(modifier = Modifier.fillMaxWidth().testTag("knowledge-review-preview"), spacing = 7) {
        ReviewSourceCaption(
            item.sourceTitle.ifBlank { "回顾卡" },
            modifier = Modifier.testTag("knowledge-review-preview-source"),
            maxLines = 1,
        )
        Text(
            reviewPromptText(item.prompt, item.sourceTitle),
            style = UiRoles.text(MaterialTheme.typography.bodyLarge),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ReviewSourceCaption(title: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        ReviewBookMark(size = 27)
        Text(
            title,
            style = UiRoles.text(MaterialTheme.typography.labelLarge),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ReviewRoundProgress(remaining: Int, total: Int) {
    Text("剩余 $remaining 张", style = UiRoles.text(MaterialTheme.typography.labelLarge), color = UiRoles.pageSupporting)
    LinearProgressIndicator(
        progress = { if (total == 0) 0f else (total - remaining).toFloat().div(total).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(4.dp),
    )
}

@Composable
internal fun ReviewPaper(
    modifier: Modifier = Modifier,
    tinted: Boolean = false,
    spacing: Int = 12,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val base = UiRoles.surface(UiSurfaceRole.Card)
    val shape = RoundedCornerShape(if (tinted) 22.dp else 16.dp)
    val background = if (tinted) lerp(base, colors.primaryContainer, .28f) else base
    if (tinted) {
        val paperBackdrop = rememberUiBackdrop()
        val accentWash = lerp(colors.primaryContainer, colors.tertiaryContainer, .42f)
        val deepWash = lerp(background, colors.secondaryContainer, .45f)
        Surface(
            modifier = modifier,
            shape = shape,
            border = BorderStroke(1.dp, lerp(colors.outlineVariant, colors.primary, .19f)),
            color = background,
            contentColor = colors.onSurface,
        ) {
            Box(Modifier.fillMaxWidth()) {
                UiGlassBackdrop(paperBackdrop, Modifier.matchParentSize()) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.linearGradient(
                                colors = listOf(background, accentWash.copy(alpha = .78f), deepWash),
                                start = Offset(0f, 0f),
                                end = Offset(900f, 520f),
                            ),
                        ),
                    )
                }
                CompositionLocalProvider(LocalUiBackdrop provides paperBackdrop) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(spacing.dp),
                        content = content,
                    )
                }
            }
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            border = BorderStroke(1.dp, lerp(colors.outlineVariant, colors.primary, .06f)),
            color = background,
            contentColor = colors.onSurface,
        ) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(spacing.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ReviewBookMark(size: Int) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(size.dp).background(colors.primaryContainer, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(UiIcons.Books, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size((size - 12).dp))
    }
}

internal fun reviewPromptText(prompt: String, sourceTitle: String?): String {
    val heading = sourceTitle?.takeIf(String::isNotBlank)?.let { "回想你在「$it」记录的要点" }
    if (heading == null || prompt.substringBefore('\n').trimEnd() != heading) return prompt
    return prompt.substringAfter('\n', "").trim().ifEmpty { prompt }
}

